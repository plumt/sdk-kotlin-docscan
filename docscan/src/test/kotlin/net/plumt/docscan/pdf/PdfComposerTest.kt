package net.plumt.docscan.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.plumt.docscan.DocScanException
import net.plumt.docscan.DocScanFailureReason
import net.plumt.docscan.PageList
import net.plumt.docscan.ScanPage
import net.plumt.docscan.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfComposerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "pages").apply { mkdirs() }

    /** 받은 페이지를 기록하는 인코더. 이미지는 크기만 남긴다(recycle 되기 전에). */
    private class Recording : PdfEncoder {
        class Seen(val widthPt: Float, val heightPt: Float, val bw: Int, val bh: Int, val rect: List<Float>, val texts: List<PdfText>, val image: Bitmap)
        val pages = mutableListOf<Seen>()
        var opened = 0
        var finished = 0
        var aborted = 0
        var failOnPage: Int = -1
        var failWith: Throwable = IllegalStateException("encoder")
        var failFinish: Throwable? = null

        override fun open(output: OutputStream): PdfEncoder.Session {
            opened++
            return object : PdfEncoder.Session {
                override fun addPage(page: PdfPage) {
                    if (pages.size == failOnPage) throw failWith
                    pages += Seen(page.widthPt, page.heightPt, page.image.width, page.image.height,
                        listOf(page.imageLeft, page.imageTop, page.imageWidth, page.imageHeight), page.texts, page.image)
                }
                override fun finish() {
                    failFinish?.let { throw it }
                    finished++
                }
                override fun abort() { aborted++ }
            }
        }
    }

    private fun composer() = PdfComposer.create(context, UnconfinedTestDispatcher())

    private fun page(name: String, w: Int, h: Int) = ScanPage(TestImages.file(dir, name, w, h))

    @Test
    fun `페이지마다 배치·축소 디코딩하고 끝나면 Bitmap 을 회수한다`() = runTest {
        val enc = Recording()
        val pages = listOf(page("a.jpg", 1200, 1800), page("b.jpg", 1800, 1200), page("c.jpg", 100, 150))
        val report = composer().compose(pages, ByteArrayOutputStream(), PdfOptions(pageSize = PageSize.A4, imageDpi = 72, encoder = enc))
        assertEquals(PdfReport(3, 0), report)
        assertEquals(1, enc.finished)
        assertEquals(0, enc.aborted)
        val (a, b, c) = enc.pages
        assertEquals(595f to 842f, a.widthPt to a.heightPt)
        assertEquals(842f to 595f, b.widthPt to b.heightPt)
        // 72 dpi 면 포인트 = 픽셀 — 이미지 자리 크기로 줄여 디코딩한다
        assertEquals(a.rect[2].toInt(), a.bw)
        assertEquals(a.rect[3].toInt(), a.bh)
        // 작은 이미지는 키우지 않는다
        assertEquals(100 to 150, c.bw to c.bh)
        assertTrue(enc.pages.all { it.image.isRecycled })
    }

    @Test
    fun `PageList 판과 기본 설정(플랫폼 인코더)`() = runTest {
        val enc = Recording()
        val list = PageList.of(listOf(page("a.jpg", 50, 50), page("b.jpg", 60, 40)))
        assertEquals(PdfReport(2, 0), composer().compose(list, ByteArrayOutputStream(), PdfOptions(encoder = enc)))
        assertEquals("PdfEncoder.platform", PdfOptions().encoder.toString())
        assertTrue(PdfOptions().toString().contains("textLayer=false"))
    }

    @Test
    fun `텍스트 층 — 인식 결과를 페이지 좌표로 넘긴다`() = runTest {
        val enc = Recording()
        val recognizer = PageTextRecognizer { bmp ->
            listOf(TextLine("영수증 합계 12,000원", 0f, 0f, bmp.width / 2f, bmp.height / 10f), TextLine(" ", 0f, 0f, 1f, 1f))
        }
        val report = composer().compose(
            listOf(page("a.jpg", 595, 842)),
            ByteArrayOutputStream(),
            PdfOptions(imageDpi = 72, encoder = enc, textRecognizer = recognizer),
        )
        assertEquals(1, report.textLineCount)
        val t = enc.pages.single().texts.single()
        assertEquals("영수증 합계 12,000원", t.text)
        assertEquals(0f, t.left, 0.5f)
        assertEquals(297.5f, t.width, 0.5f)
        assertEquals(84.2f, t.height, 0.5f)
        assertTrue(PdfOptions(textRecognizer = recognizer).toString().contains("textLayer=true"))
    }

    @Test
    fun `압축 인코더 — 실제 JPEG 를 담은 올바른 PDF`() = runTest {
        val out = ByteArrayOutputStream()
        val recognizer = PageTextRecognizer { listOf(TextLine("검색 가능", 1f, 1f, 30f, 10f)) }
        composer().compose(
            listOf(page("a.jpg", 300, 400), page("b.jpg", 400, 300)),
            out,
            PdfOptions(pageSize = PageSize.Letter, marginPt = 18f, encoder = PdfEncoder.compact(70), textRecognizer = recognizer),
        )
        val pdf = PdfProbe(out.toByteArray())
        assertEquals(2, pdf.pageIds.size)
        for (id in pdf.pageIds) {
            val dict = pdf.objects.getValue(id)
            val jpeg = pdf.stream(pdf.ref(dict, "/Im1"))
            assertEquals(0xFF, jpeg[0].code)
            assertEquals(0xD8, jpeg[1].code)
            assertTrue(pdf.contentOf(id).contains("3 Tr"))
        }
        assertTrue(pdf.objects.getValue(pdf.pageIds[1]).contains("/MediaBox [0 0 792 612]"))
        assertEquals("PdfEncoder.compact(jpegQuality=70)", PdfEncoder.compact(70).toString())
    }

    @Test
    fun `압축 인코더 품질은 1~100`() {
        assertThrows(IllegalArgumentException::class.java) { PdfEncoder.compact(0) }
        assertThrows(IllegalArgumentException::class.java) { PdfEncoder.compact(101) }
        assertNotNull(PdfEncoder.compact(1))
        assertNotNull(PdfEncoder.compact(100))
        PdfEncoder.compact().open(ByteArrayOutputStream()).abort()
    }

    @Test
    fun `인자 검사`() = runTest {
        val c = composer()
        assertEquals(
            "pages 가 비었다 — 1..500 장",
            runCatching { c.compose(emptyList(), ByteArrayOutputStream()) }.exceptionOrNull()!!.message,
        )
        val tooMany = List(501) { ScanPage(Uri.parse("file:///x")) }
        assertTrue(runCatching { c.compose(tooMany, ByteArrayOutputStream()) }.exceptionOrNull() is IllegalArgumentException)
        assertThrows(IllegalArgumentException::class.java) { PdfOptions(marginPt = -1f) }
        assertThrows(IllegalArgumentException::class.java) { PdfOptions(marginPt = 145f) }
        assertThrows(IllegalArgumentException::class.java) { PdfOptions(marginPt = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { PdfOptions(imageDpi = 49) }
        assertThrows(IllegalArgumentException::class.java) { PdfOptions(imageDpi = 601) }
        PdfOptions(marginPt = 144f, imageDpi = 50)
        PdfOptions(imageDpi = 600)
    }

    @Test
    fun `없는 파일은 IO_ERROR, 손상 파일은 IMAGE_DECODE_FAILED — 인코더를 정리한다`() = runTest {
        val enc = Recording()
        val missing = ScanPage(Uri.fromFile(File(dir, "none.jpg")))
        val e1 = runCatching { composer().compose(listOf(missing), ByteArrayOutputStream(), PdfOptions(encoder = enc)) }.exceptionOrNull()
        assertEquals(DocScanFailureReason.IO_ERROR, (e1 as DocScanException).reason)
        assertTrue(e1.cause is IOException)
        assertEquals(1, enc.aborted)

        val broken = File(dir, "broken.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val e2 = runCatching { composer().compose(listOf(ScanPage(Uri.fromFile(broken))), ByteArrayOutputStream(), PdfOptions(encoder = enc)) }
            .exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.IMAGE_DECODE_FAILED, e2.reason)
        assertTrue(e2.message!!.contains("broken.jpg"))
        val empty = File(dir, "empty.jpg").apply { writeBytes(ByteArray(0)) }
        val e3 = runCatching { composer().compose(listOf(ScanPage(Uri.fromFile(empty))), ByteArrayOutputStream(), PdfOptions(encoder = enc)) }
            .exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.IMAGE_DECODE_FAILED, e3.reason)
    }

    /** 읽기 권한을 거부하는 provider. */
    class DenyingProvider : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor = throw SecurityException("denied")
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: android.content.ContentValues?) = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Test
    fun `provider 가 읽기를 거부하면 IO_ERROR 와 cause`() {
        org.robolectric.Robolectric.setupContentProvider(DenyingProvider::class.java, "deny.test")
        val loader = ResolverPageImageLoader(context.contentResolver)
        val e = assertThrows(DocScanException::class.java) { loader.bounds(Uri.parse("content://deny.test/x.jpg")) }
        assertEquals(DocScanFailureReason.IO_ERROR, e.reason)
        assertTrue(e.cause is SecurityException)
    }

    @Test
    fun `텍스트 인식 실패 분류 — 감싸기·그대로 전달·메모리`() = runTest {
        suspend fun failWith(t: Throwable): Throwable? = runCatching {
            composer().compose(listOf(page("a.jpg", 20, 20)), ByteArrayOutputStream(), PdfOptions(encoder = Recording(), textRecognizer = { throw t }))
        }.exceptionOrNull()

        val boom = RuntimeException("engine")
        val wrapped = failWith(boom) as DocScanException
        assertEquals(DocScanFailureReason.TEXT_RECOGNITION_FAILED, wrapped.reason)
        assertSame(boom, wrapped.cause)
        val own = DocScanException(DocScanFailureReason.MODULE_DOWNLOAD_REQUIRED, "model")
        assertSame(own, failWith(own))
        val iae = IllegalArgumentException("bad")
        assertSame(iae, failWith(iae))
        assertEquals(DocScanFailureReason.OUT_OF_MEMORY, (failWith(OutOfMemoryError()) as DocScanException).reason)
    }

    @Test
    fun `인코더 실패 분류 — PDF_FAILED·IO·저장 공간·메모리, abort 호출`() = runTest {
        suspend fun failWith(t: Throwable, onFinish: Boolean = false): Pair<Throwable?, Recording> {
            val enc = Recording().apply { if (onFinish) failFinish = t else { failOnPage = 1; failWith = t } }
            val r = runCatching {
                composer().compose(listOf(page("a.jpg", 20, 20), page("b.jpg", 20, 20)), ByteArrayOutputStream(), PdfOptions(encoder = enc))
            }.exceptionOrNull()
            return r to enc
        }
        val (e1, enc1) = failWith(IllegalStateException("skia"))
        assertEquals(DocScanFailureReason.PDF_FAILED, (e1 as DocScanException).reason)
        assertEquals(1, enc1.aborted)
        assertTrue(enc1.pages.single().image.isRecycled)
        assertEquals(DocScanFailureReason.IO_ERROR, ((failWith(IOException("pipe"), onFinish = true).first) as DocScanException).reason)
        assertEquals(
            DocScanFailureReason.INSUFFICIENT_STORAGE,
            ((failWith(IOException("write failed: ENOSPC (No space left on device)"), onFinish = true).first) as DocScanException).reason,
        )
        assertEquals(DocScanFailureReason.OUT_OF_MEMORY, (failWith(OutOfMemoryError()).first as DocScanException).reason)
        val own = DocScanException(DocScanFailureReason.UNKNOWN, "x")
        assertSame(own, failWith(own).first)
    }

    @Test
    fun `인코더를 열다 실패하면 PDF_FAILED`() = runTest {
        val enc = object : PdfEncoder {
            override fun open(output: OutputStream): PdfEncoder.Session = throw UnsupportedOperationException("no")
        }
        val e = runCatching { composer().compose(listOf(page("a.jpg", 20, 20)), ByteArrayOutputStream(), PdfOptions(encoder = enc)) }
            .exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.PDF_FAILED, e.reason)
    }

    @Test
    fun `페이지 사이에서 취소되면 그대로 전파하고 abort`() = runTest {
        val enc = Recording()
        val gate = CompletableDeferred<Unit>()
        val recognizer = PageTextRecognizer { gate.await(); emptyList() }
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            PdfComposer.create(context, StandardTestDispatcher(testScheduler))
                .compose(listOf(page("a.jpg", 20, 20), page("b.jpg", 20, 20)), ByteArrayOutputStream(), PdfOptions(encoder = enc, textRecognizer = recognizer))
        }
        testScheduler.runCurrent()
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(1, enc.aborted)
        assertEquals(0, enc.finished)
    }

    @Test
    fun `PdfText·TextLine·PdfReport 값과 검사`() {
        assertEquals(PdfText("a", 1f, 2f, 3f, 4f), PdfText("a", 1f, 2f, 3f, 4f))
        assertEquals(PdfText("a", 1f, 2f, 3f, 4f).hashCode(), PdfText("a", 1f, 2f, 3f, 4f).hashCode())
        assertTrue(PdfText("a", 1f, 2f, 3f, 4f) != PdfText("a", 1f, 2f, 3f, 5f))
        assertTrue(PdfText("a", 1f, 2f, 3f, 4f) != Any())
        assertTrue(PdfText("a", 1f, 2f, 3f, 4f).toString().contains("\"a\""))
        assertEquals(TextLine("t", 0f, 0f, 1f, 1f), TextLine("t", 0f, 0f, 1f, 1f))
        assertEquals(TextLine("t", 0f, 0f, 1f, 1f).hashCode(), TextLine("t", 0f, 0f, 1f, 1f).hashCode())
        assertTrue(TextLine("t", 0f, 0f, 1f, 1f) != TextLine("u", 0f, 0f, 1f, 1f))
        assertTrue(TextLine("t", 0f, 0f, 1f, 1f) != Any())
        assertTrue(TextLine("t", 0f, 0f, 1f, 1f).toString().startsWith("TextLine("))
        assertThrows(IllegalArgumentException::class.java) { TextLine("t", 2f, 0f, 1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { TextLine("t", 0f, 2f, 1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { TextLine("t", Float.NaN, 0f, 1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { TextLine("t", 0f, 0f, Float.POSITIVE_INFINITY, 1f) }
        assertEquals(PdfReport(1, 2), PdfReport(1, 2))
        assertEquals(PdfReport(1, 2).hashCode(), PdfReport(1, 2).hashCode())
        assertTrue(PdfReport(1, 2) != PdfReport(1, 3))
        assertTrue(PdfReport(1, 2) != Any())
        assertEquals("PdfReport(pageCount=1, textLineCount=2)", PdfReport(1, 2).toString())
    }

    @Test
    fun `페이지 크기 값과 검사`() {
        assertEquals(PageSize.Custom(100f, 200f), PageSize.Custom(100f, 200f))
        assertEquals(PageSize.Custom(100f, 200f).hashCode(), PageSize.Custom(100f, 200f).hashCode())
        assertTrue(PageSize.Custom(100f, 200f) != PageSize.Custom(200f, 100f))
        assertTrue(PageSize.Custom(100f, 200f) != PageSize.A4)
        assertEquals("Custom(100.0pt x 200.0pt)", PageSize.Custom(100f, 200f).toString())
        for (bad in listOf(71.9f, 14_400.1f, Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertThrows(IllegalArgumentException::class.java) { PageSize.Custom(bad, 100f) }
            assertThrows(IllegalArgumentException::class.java) { PageSize.Custom(100f, bad) }
        }
        PageSize.Custom(72f, 14_400f)
    }
}
