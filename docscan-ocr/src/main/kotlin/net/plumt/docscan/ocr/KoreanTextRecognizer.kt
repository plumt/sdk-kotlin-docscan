package net.plumt.docscan.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.plumt.docscan.DocScanException
import net.plumt.docscan.DocScanFailureReason
import net.plumt.docscan.ModuleAvailability
import net.plumt.docscan.pdf.PageTextRecognizer
import net.plumt.docscan.pdf.TextLine
import java.io.Closeable
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit 한국어 텍스트 인식기(한글·라틴 문자). [net.plumt.docscan.pdf.PdfOptions.textRecognizer] 에 넣으면 검색 가능한 PDF 를 만든다.
 *
 * 인식 모델은 Play 서비스가 내려받는다(앱에 싣지 않는다). 첫 인식 전에 [availability] 의 `install()` 로 미리 받아 두면
 * 모델이 없어 실패하는 일([DocScanFailureReason.MODULE_DOWNLOAD_REQUIRED])이 없다. 다 쓰면 [close] 한다.
 * 메인 스레드에서 불러도 인식은 ML Kit 작업 스레드에서 돈다.
 */
public class KoreanTextRecognizer internal constructor(private val client: TextRecognizer) : PageTextRecognizer, Closeable {

    /** 앱 시작 시 ML Kit 이 초기화된 뒤(일반 앱 프로세스) 만든다. */
    public constructor() : this(TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()))

    override suspend fun recognize(image: Bitmap): List<TextLine> {
        require(!image.isRecycled) { "recycle 된 Bitmap 이다" }
        val text = try {
            client.process(InputImage.fromBitmap(image, 0)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw classify(e)
        }
        return text.toLines()
    }

    /** 인식 모델의 사용 가능 여부 확인·미리 내려받기. */
    public fun availability(context: Context): ModuleAvailability = ModuleAvailability.of(context, client)

    override fun close() {
        client.close()
    }

    internal companion object {
        fun classify(e: Exception): DocScanException {
            if (e is DocScanException) return e
            val code = (e as? MlKitException)?.errorCode
            val reason = when (code) {
                MlKitException.UNAVAILABLE -> DocScanFailureReason.MODULE_DOWNLOAD_REQUIRED
                MlKitException.NOT_ENOUGH_SPACE -> DocScanFailureReason.INSUFFICIENT_STORAGE
                MlKitException.NETWORK_ISSUE -> DocScanFailureReason.NETWORK_ERROR
                else -> DocScanFailureReason.TEXT_RECOGNITION_FAILED
            }
            return DocScanException(reason, "텍스트 인식 실패: ${e.message}", e, code)
        }
    }
}

/** 블록 안의 줄을 위에서 아래 순서 그대로 꺼낸다. 영역이 없는 줄·빈 글자는 버린다. */
internal fun Text.toLines(): List<TextLine> = textBlocks.flatMap { it.lines }.mapNotNull { line ->
    val box = line.boundingBox ?: return@mapNotNull null
    val text = line.text.trim()
    if (text.isEmpty() || box.width() <= 0 || box.height() <= 0) return@mapNotNull null
    TextLine(text, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
}

private val Direct = Executor { it.run() }

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener(Direct) { task ->
        val error = if (task.isCanceled) MlKitException("작업이 취소됐다", MlKitException.CANCELLED) else task.exception
        if (error != null) cont.resumeWithException(error) else cont.resume(task.result)
    }
}
