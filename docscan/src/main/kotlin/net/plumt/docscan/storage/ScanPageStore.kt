package net.plumt.docscan.storage

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.plumt.docscan.ScanPage
import net.plumt.docscan.ScannedDocument
import net.plumt.docscan.internal.FailureClassifier
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 스캔 페이지 이미지를 캐시 밖에 보관한다. 스캐너 결과는 앱 캐시에 있어 저장 공간이 부족하면 시스템이 지울 수 있다 —
 * 여러 번 스캔해 모으는 동안(프로세스 종료를 넘어) 페이지가 사라지지 않게 하려면 받자마자 [keep] 한다.
 */
public interface ScanPageStore {
    /** 문서의 페이지 이미지를 보관소로 복사하고 새 페이지를 돌려준다. 실패하면 이번에 복사한 것은 지운다. */
    public suspend fun keep(document: ScannedDocument): List<ScanPage>

    /** 보관소 안의 페이지만 지운다. 보관소 밖 URI 는 건드리지 않는다. @return 지운 수 */
    public suspend fun delete(pages: Collection<ScanPage>): Int

    /** 보관소를 비운다. @return 지운 수 */
    public suspend fun clear(): Int

    public companion object {
        /**
         * 앱 내부 저장소(`filesDir/plumt_docscan/<name>`). 다른 앱이 읽을 수 없다.
         *
         * @param name 보관소 이름(폴더 하나). 여러 문서를 따로 모으려면 이름을 나눈다
         */
        public fun files(context: Context, name: String = "pages", dispatcher: CoroutineDispatcher = Dispatchers.IO): ScanPageStore {
            val folder = requireNotNull(FileNames.subdirectory(name)?.takeIf { '/' !in it }) { "name 은 폴더 하나의 이름이어야 한다: $name" }
            return FileScanPageStore(context.applicationContext, File(context.filesDir, "plumt_docscan/$folder"), dispatcher) {
                UUID.randomUUID().toString()
            }
        }
    }
}

internal class FileScanPageStore(
    private val context: Context,
    private val dir: File,
    private val dispatcher: CoroutineDispatcher,
    private val newId: () -> String,
) : ScanPageStore {

    override suspend fun keep(document: ScannedDocument): List<ScanPage> = withContext(dispatcher) {
        val copied = ArrayList<File>()
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("보관소 폴더를 만들지 못했다")
            document.pages.map { page ->
                val target = File(dir, newId() + ".jpg")
                val input = context.contentResolver.openInputStream(page.uri) ?: throw IOException("provider 가 스트림을 주지 않았다")
                copied += target
                input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
                ScanPage(Uri.fromFile(target))
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            copied.forEach { it.delete() }
            throw when (e) {
                is IOException, is SecurityException -> FailureClassifier.fromIo(e, "페이지 보관")
                is OutOfMemoryError -> FailureClassifier.classify(e, "페이지 보관")
                else -> e
            }
        }
    }

    override suspend fun delete(pages: Collection<ScanPage>): Int = withContext(dispatcher) {
        pages.mapNotNull { owned(it.uri) }.count { it.delete() }
    }

    override suspend fun clear(): Int = withContext(dispatcher) {
        dir.listFiles().orEmpty().count { it.isFile && it.delete() }
    }

    /** 보관소 안 파일이면 그 파일. */
    private fun owned(uri: Uri): File? {
        if (uri.scheme != "file") return null
        val file = File(uri.path ?: return null).canonicalFile
        return file.takeIf { it.parentFile == dir.canonicalFile }
    }
}
