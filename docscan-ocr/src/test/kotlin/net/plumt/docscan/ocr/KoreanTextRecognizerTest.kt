package net.plumt.docscan.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.Feature
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.odml.image.MlImage
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import net.plumt.docscan.DocScanException
import net.plumt.docscan.DocScanFailureReason
import net.plumt.docscan.pdf.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class KoreanTextRecognizerTest {

    @org.junit.Before
    fun initMlKit() {
        // 앱에서는 ML Kit 의 초기화 provider 가 하는 일이다. InputImage 가 사용 기록을 위해 요구한다
        com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(ApplicationProvider.getApplicationContext())
    }

    private class FakeClient(var next: () -> Task<Text>) : TextRecognizer {
        var closed = 0
        var images = 0
        override fun process(image: InputImage): Task<Text> {
            images++
            return next()
        }
        override fun process(image: MlImage): Task<Text> = throw UnsupportedOperationException()
        override fun close() { closed++ }
        override fun getOptionalFeatures(): Array<Feature> = arrayOf(Feature("ocr_korean", 1L))
        override fun getDetectorType(): Int = 0
        override fun process(bitmap: Bitmap, rotation: Int): Task<Text> = throw UnsupportedOperationException()
        override fun process(image: android.media.Image, rotation: Int): Task<Text> = throw UnsupportedOperationException()
        override fun process(image: android.media.Image, rotation: Int, matrix: Matrix): Task<Text> = throw UnsupportedOperationException()
        override fun process(buffer: java.nio.ByteBuffer, w: Int, h: Int, r: Int, f: Int): Task<Text> = throw UnsupportedOperationException()
    }

    private fun line(text: String, box: Rect) =
        Text.Line(text, box, emptyList<android.graphics.Point>(), "ko", Matrix(), emptyList<Text.Element>(), 0.9f, 0f)

    private fun block(vararg lines: Text.Line) =
        Text.TextBlock(lines.joinToString("\n") { it.text }, Rect(0, 0, 1, 1), emptyList<android.graphics.Point>(), "ko", Matrix(), lines.toList())

    private val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

    @Test
    fun `줄 단위로 좌표와 글자를 넘긴다 — 빈 줄·영역 없는 줄은 버린다`() = runTest {
        val text = Text(
            "",
            listOf(
                block(line("영수증", Rect(10, 20, 60, 40)), line("합계 12,000원", Rect(10, 50, 90, 70))),
                block(line("  ", Rect(0, 0, 10, 10)), line("넓이 0", Rect(5, 5, 5, 9)), line("높이 0", Rect(5, 5, 9, 5))),
                block(line(" Total ", Rect(1, 2, 3, 4))),
            ),
        )
        val client = FakeClient { Tasks.forResult(text) }
        val lines = KoreanTextRecognizer(client).recognize(bitmap)
        assertEquals(
            listOf(
                TextLine("영수증", 10f, 20f, 60f, 40f),
                TextLine("합계 12,000원", 10f, 50f, 90f, 70f),
                TextLine("Total", 1f, 2f, 3f, 4f),
            ),
            lines,
        )
        assertEquals(1, client.images)
    }

    @Test
    fun `인식 실패 분류 — 모델 없음·저장 공간·네트워크·기타, cause 보존`() = runTest {
        val cases = mapOf(
            MlKitException.UNAVAILABLE to DocScanFailureReason.MODULE_DOWNLOAD_REQUIRED,
            MlKitException.NOT_ENOUGH_SPACE to DocScanFailureReason.INSUFFICIENT_STORAGE,
            MlKitException.NETWORK_ISSUE to DocScanFailureReason.NETWORK_ERROR,
            MlKitException.INTERNAL to DocScanFailureReason.TEXT_RECOGNITION_FAILED,
        )
        for ((code, reason) in cases) {
            val src = MlKitException("m", code)
            val e = runCatching { KoreanTextRecognizer(FakeClient { Tasks.forException(src) }).recognize(bitmap) }.exceptionOrNull() as DocScanException
            assertEquals(reason, e.reason)
            assertSame(src, e.cause)
            assertEquals(code, e.statusCode)
        }
        val other = IllegalStateException("x")
        val e = runCatching { KoreanTextRecognizer(FakeClient { Tasks.forException(other) }).recognize(bitmap) }.exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.TEXT_RECOGNITION_FAILED, e.reason)
        assertEquals(null, e.statusCode)
        val own = DocScanException(DocScanFailureReason.UNKNOWN, "own")
        assertSame(own, KoreanTextRecognizer.classify(own))
        // 동기 예외도 같은 분류
        val sync = runCatching { KoreanTextRecognizer(FakeClient { throw MlKitException("s", MlKitException.UNAVAILABLE) }).recognize(bitmap) }
            .exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.MODULE_DOWNLOAD_REQUIRED, sync.reason)
    }

    @Test
    fun `Task 취소·나중 완료·코루틴 취소`() = runTest {
        val tokens = CancellationTokenSource()
        val canceled = TaskCompletionSource<Text>(tokens.token)
        tokens.cancel()
        ShadowLooper.idleMainLooper()
        val e = runCatching { KoreanTextRecognizer(FakeClient { canceled.task }).recognize(bitmap) }.exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.TEXT_RECOGNITION_FAILED, e.reason)

        val later = TaskCompletionSource<Text>()
        val job = async(start = CoroutineStart.UNDISPATCHED) { KoreanTextRecognizer(FakeClient { later.task }).recognize(bitmap) }
        later.setResult(Text("", emptyList<Text.TextBlock>()))
        assertEquals(emptyList<TextLine>(), job.await())

        val never = TaskCompletionSource<Text>()
        val stuck = async(start = CoroutineStart.UNDISPATCHED) { KoreanTextRecognizer(FakeClient { never.task }).recognize(bitmap) }
        stuck.cancel()
        assertTrue(runCatching { stuck.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun `recycle 된 Bitmap 은 IAE, close 는 엔진을 닫는다, 모델 확인 진입점`() = runTest {
        val client = FakeClient { Tasks.forResult(Text("", emptyList<Text.TextBlock>())) }
        val recognizer = KoreanTextRecognizer(client)
        val recycled = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { recycle() }
        assertTrue(runCatching { recognizer.recognize(recycled) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, client.images)
        recognizer.close()
        assertEquals(1, client.closed)
        assertNotNull(recognizer.availability(ApplicationProvider.getApplicationContext()))
        assertThrows(IllegalArgumentException::class.java) { TextLine("x", 2f, 0f, 1f, 1f) }
    }
}
