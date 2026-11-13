package net.plumt.docscan.storage

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.plumt.docscan.internal.FailureClassifier
import java.io.File
import java.io.IOException

/**
 * PDF 공유 인텐트. 받는 앱에는 읽기 권한만 일시적으로 준다(`FLAG_GRANT_READ_URI_PERMISSION`).
 *
 * 파일은 이 SDK 의 FileProvider(authority `<applicationId>.plumt.docscan.files`)로 내보내며, 내보낼 수 있는 곳은
 * 캐시 안 공유 폴더다(API 28 이하는 [PdfSaver.downloads] 의 앱 전용 저장 폴더도). 다른 위치의 PDF 는 [copyForSharing] 으로
 * 먼저 복사한다. [PdfSaver] 로 저장한 PDF 는 API 와 상관없이 [SavedPdf.uri] 를 [shareIntent] 의 Uri 판에 그대로 넘긴다.
 *
 * 인텐트는 모든 API 에서 선택 화면(`ACTION_CHOOSER`)으로 만든다. 받을 수 있는 앱이 하나뿐이면 시스템이 선택 화면을 건너뛰고 바로 열 수 있다.
 */
public object PdfSharing {
    private const val DIR = "plumt_docscan/share"
    private const val AUTHORITY_SUFFIX = ".plumt.docscan.files"

    /** 이 앱의 공유 provider authority. */
    public fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    /** 공유 폴더. 없으면 만든다. */
    public fun shareDirectory(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    /**
     * 공유 폴더 안의 새 파일 경로(아직 만들지 않는다). PDF 를 여기에 바로 쓰면 복사 없이 [shareIntent] 로 보낼 수 있다.
     *
     * @throws IllegalArgumentException 이름 규칙([PdfSaver.save] 와 같다) 위반
     */
    public fun newShareFile(context: Context, displayName: String): File {
        val name = FileNames.pdfName(displayName)
        val dir = shareDirectory(context)
        return File(dir, FileNames.unique(name) { File(dir, it).exists() })
    }

    /**
     * [source] 를 공유 폴더로 복사한다.
     *
     * @throws net.plumt.docscan.DocScanException 읽기·쓰기 실패
     */
    public suspend fun copyForSharing(
        context: Context,
        source: Uri,
        displayName: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): File {
        val target = newShareFile(context, displayName)
        return withContext(dispatcher) {
            try {
                val input = context.contentResolver.openInputStream(source) ?: throw IOException("provider 가 스트림을 주지 않았다")
                input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
                target
            } catch (e: IOException) {
                target.delete()
                throw FailureClassifier.fromIo(e, "공유용 복사")
            } catch (e: SecurityException) {
                target.delete()
                throw FailureClassifier.fromIo(e, "공유용 복사")
            }
        }
    }

    /**
     * 공유 폴더 안 [file] 을 보내는 선택 화면(chooser) 인텐트.
     *
     * @throws IllegalArgumentException [file] 이 공유 폴더 밖이거나 없을 때 — [copyForSharing] 을 먼저 쓴다
     */
    public fun shareIntent(context: Context, file: File, chooserTitle: CharSequence? = null): Intent {
        val dir = shareDirectory(context).canonicalFile
        val canonical = file.canonicalFile
        require(canonical.parentFile == dir) {
            "공유 폴더 밖 파일이다 — copyForSharing 으로 먼저 복사한다: ${file.name}"
        }
        require(canonical.isFile) { "파일이 없다: ${file.name}" }
        val uri = FileProvider.getUriForFile(context, authority(context), canonical)
        return shareIntent(uri, canonical.name, chooserTitle)
    }

    /**
     * 이미 다른 앱에 권한을 줄 수 있는 [uri]([SavedPdf.uri] 등)를 보내는 선택 화면 인텐트.
     *
     * @throws IllegalArgumentException [uri] 가 `file:` 일 때 — 다른 앱에 넘기면 `FileUriExposedException` 이 난다. [copyForSharing] 을 먼저 쓴다
     */
    public fun shareIntent(uri: Uri, displayName: String? = null, chooserTitle: CharSequence? = null): Intent {
        require(!ContentResolver.SCHEME_FILE.equals(uri.scheme, ignoreCase = true)) {
            "file: Uri 는 공유할 수 없다 — SavedPdf.uri 를 넘기거나 copyForSharing 으로 먼저 복사한다"
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = DownloadsPdfSaver.PDF_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            displayName?.let { putExtra(Intent.EXTRA_TITLE, it) }
            // 선택 화면 미리보기·받는 앱 모두 읽기 권한을 받도록 ClipData 에도 싣는다
            clipData = ClipData.newRawUri(displayName ?: "", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** 앱 전용 저장 폴더의 [file] 을 가리키는 content: Uri. provider 를 찾지 못하거나 경로가 노출 범위 밖이면 [net.plumt.docscan.DocScanFailureReason.IO_ERROR]. */
    internal fun savedFileUri(context: Context, file: File): Uri = try {
        FileProvider.getUriForFile(context, authority(context), file)
    } catch (e: IllegalArgumentException) {
        throw FailureClassifier.fromIo(e, "공유 Uri 만들기")
    }

    /** 공유 폴더를 비운다. 공유가 끝난 뒤(다음 실행 등)에 부른다 — 받는 앱이 읽는 중이면 실패할 수 있다. */
    public suspend fun clearShared(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.IO): Int =
        withContext(dispatcher) {
            shareDirectory(context).listFiles().orEmpty().count { it.isFile && it.delete() }
        }
}
