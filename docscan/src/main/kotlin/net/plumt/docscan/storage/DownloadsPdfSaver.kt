package net.plumt.docscan.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.plumt.docscan.DocScanException
import net.plumt.docscan.internal.FailureClassifier
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

internal class DownloadsPdfSaver(
    private val context: Context,
    private val subdirectory: String?,
    private val dispatcher: CoroutineDispatcher,
) : PdfSaver {

    override suspend fun save(displayName: String, write: suspend (OutputStream) -> Unit): SavedPdf {
        val name = FileNames.pdfName(displayName)
        return withContext(dispatcher) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToMediaStore(name, write) else saveToAppFolder(name, write)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveToMediaStore(name: String, write: suspend (OutputStream) -> Unit): SavedPdf {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, PDF_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + (subdirectory?.let { "/$it" } ?: ""))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = io("다운로드 항목 만들기") { resolver.insert(collection, values) }
            ?: throw FailureClassifier.fromIo(IOException("MediaStore 가 항목을 만들지 않았다"), "다운로드 항목 만들기")
        var written = 0L
        try {
            val stream = io("다운로드 열기") { resolver.openOutputStream(uri, "w") }
                ?: throw FailureClassifier.fromIo(IOException("MediaStore 가 스트림을 주지 않았다"), "다운로드 열기")
            stream.use { raw ->
                val counting = CountingOutputStream(raw)
                runWrite(counting, write)
                io("다운로드 쓰기") { counting.flush() }
                written = counting.count
            }
            io("다운로드 공개") {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // 쓰다 만 항목을 지운다. 지우기 실패는 원래 실패를 가리지 않도록 버린다
            withContext(NonCancellable) { runCatching { resolver.delete(uri, null, null) } }
            throw e
        }
        val shown = queryName(uri) ?: name
        return SavedPdf(uri, shown, SaveLocation.DOWNLOADS, written, null)
    }

    private suspend fun saveToAppFolder(name: String, write: suspend (OutputStream) -> Unit): SavedPdf {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: File(context.filesDir, Environment.DIRECTORY_DOCUMENTS)
        val dir = subdirectory?.let { File(root, it) } ?: root
        io("앱 폴더 만들기") { if (!dir.isDirectory && !dir.mkdirs()) throw IOException("폴더를 만들지 못했다: ${dir.name}") }
        val finalName = FileNames.unique(name) { File(dir, it).exists() }
        val target = File(dir, finalName)
        // 쓰기 전에 만든다 — provider 를 앱이 매니페스트에서 뺐다면 파일을 남기지 않고 여기서 실패한다
        val uri = PdfSharing.savedFileUri(context, target)
        val temp = File(dir, ".$finalName.part")
        var written = 0L
        try {
            io("앱 폴더 쓰기") { temp.outputStream() }.use { raw ->
                val counting = CountingOutputStream(raw)
                runWrite(counting, write)
                io("앱 폴더 쓰기") { counting.flush() }
                written = counting.count
            }
            io("앱 폴더 저장") { if (!temp.renameTo(target)) throw IOException("이름을 바꾸지 못했다: $finalName") }
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            temp.delete()
            throw e
        }
        return SavedPdf(uri, finalName, SaveLocation.APP_SPECIFIC, written, target)
    }

    /** 쓰기 블록의 IO 실패는 분류하고, 그 밖의 예외(DocScanException·IAE·취소 등)는 그대로 둔다. */
    private suspend fun runWrite(stream: OutputStream, write: suspend (OutputStream) -> Unit) {
        try {
            write(stream)
        } catch (e: IOException) {
            throw FailureClassifier.fromIo(e, "PDF 쓰기")
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private inline fun <T> io(what: String, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: DocScanException) {
        throw e
    } catch (e: IOException) {
        throw FailureClassifier.fromIo(e, what)
    } catch (e: SecurityException) {
        throw FailureClassifier.fromIo(e, what)
    } catch (e: IllegalStateException) {
        // provider 가 런타임 예외로 실패를 알리는 경우(볼륨 없음 등)
        throw FailureClassifier.fromIo(e, what)
    } catch (e: UnsupportedOperationException) {
        throw FailureClassifier.fromIo(e, what)
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    companion object {
        const val PDF_MIME = "application/pdf"
    }
}
