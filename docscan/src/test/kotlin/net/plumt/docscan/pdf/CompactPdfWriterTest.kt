package net.plumt.docscan.pdf

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class CompactPdfWriterTest {
    private val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun page(texts: List<PdfText> = emptyList(), w: Int = 40, h: Int = 60) = PdfPage(
        595f, 842f, Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), 10f, 20f, 400f, 600f, texts,
    )

    private fun write(vararg pages: PdfPage): PdfProbe {
        val out = ByteArrayOutputStream()
        val writer = CompactPdfWriter(out)
        pages.forEach { writer.addPage(it, fakeJpeg) }
        writer.finish()
        return PdfProbe(out.toByteArray())
    }

    @Test
    fun `글자 없는 한 페이지 — 구조·교차 참조·이미지·내용`() {
        val pdf = write(page())
        assertEquals(1, pdf.pageIds.size)
        val pageDict = pdf.objects.getValue(pdf.pageIds.single())
        assertTrue(pageDict, pageDict.contains("/MediaBox [0 0 595 842]"))
        assertFalse("글꼴 없음", pageDict.contains("/Font"))
        assertFalse(pdf.text.contains("/Type0"))
        val image = pdf.objects.getValue(pdf.ref(pageDict, "/Im1"))
        assertTrue(image, image.contains("/Width 40 /Height 60 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode"))
        assertEquals(String(fakeJpeg, Charsets.ISO_8859_1), pdf.stream(pdf.ref(pageDict, "/Im1")))
        // 왼쪽 아래 원점: y = 842 - 20 - 600 = 222
        assertEquals("q 400 0 0 600 10 222 cm /Im1 Do Q\n", pdf.contentOf(pdf.pageIds.single()))
        assertTrue(pdf.objects.getValue(2).contains("/Count 1"))
    }

    @Test
    fun `텍스트 층 — 보이지 않는 글자, 너비 맞춤, ToUnicode`() {
        val pdf = write(page(listOf(PdfText("가A가", 50f, 100f, 60f, 20f))))
        val pageDict = pdf.objects.getValue(pdf.pageIds.single())
        val fontId = pdf.ref(pageDict, "/F1")
        val font = pdf.objects.getValue(fontId)
        assertTrue(font, font.contains("/Subtype /Type0") && font.contains("/Encoding /Identity-H"))
        val content = pdf.contentOf(pdf.pageIds.single())
        // 글자 3개 x 0.5 x 20 = 30 → 60 에 맞추려면 200%. 기준선 y = 842 - 100 - 20 = 722
        assertTrue(content, content.contains("BT 3 Tr /F1 20 Tf 200 Tz 1 0 0 1 50 722 Tm <000100020001> Tj ET"))
        val cmap = pdf.stream(pdf.ref(font, "/ToUnicode"))
        assertTrue(cmap, cmap.contains("2 beginbfchar\n<0001> <AC00>\n<0002> <0041>\nendbfchar"))
        val cid = pdf.objects.getValue(pdf.ref(font, "/DescendantFonts ["))
        assertTrue(cid, cid.contains("/CIDFontType2") && cid.contains("/DW 500"))
        assertTrue(pdf.objects.getValue(pdf.ref(cid, "/FontDescriptor")).contains("/Type /FontDescriptor"))
    }

    @Test
    fun `여러 페이지가 글꼴 하나를 함께 쓰고 CID 를 이어 매긴다`() {
        val pdf = write(
            page(listOf(PdfText("ab", 0f, 0f, 10f, 10f))),
            page(),
            page(listOf(PdfText("bc", 0f, 0f, 10f, 10f))),
        )
        assertEquals(3, pdf.pageIds.size)
        val fonts = pdf.pageIds.mapNotNull { id -> pdf.objects.getValue(id).takeIf { "/F1" in it }?.let { pdf.ref(it, "/F1") } }
        assertEquals(2, fonts.size)
        assertEquals(1, fonts.toSet().size)
        assertTrue(pdf.contentOf(pdf.pageIds[2]).contains("<00020003>"))
        assertTrue(pdf.objects.getValue(2).contains("/Count 3"))
    }

    @Test
    fun `보조 평면 글자(이모지)·제어 문자·빈 줄·넓이 없는 줄`() {
        val pdf = write(
            page(
                listOf(
                    PdfText("a\u0007😀", 0f, 0f, 30f, 10f),
                    PdfText("   ", 0f, 0f, 10f, 10f),
                    PdfText("x", 0f, 0f, 0f, 10f),
                    PdfText("y", 0f, 0f, 10f, 0f),
                ),
            ),
        )
        val content = pdf.contentOf(pdf.pageIds.single())
        assertEquals(1, content.split("BT ").size - 1)
        val font = pdf.objects.getValue(pdf.ref(pdf.objects.getValue(pdf.pageIds.single()), "/F1"))
        val cmap = pdf.stream(pdf.ref(font, "/ToUnicode"))
        assertTrue(cmap, cmap.contains("<0002> <0020>"))
        assertTrue(cmap, cmap.contains("<0003> <D83DDE00>"))
    }

    @Test
    fun `ToUnicode 는 100개씩 나눈다`() {
        val text = (0 until 250).map { (0xAC00 + it).toChar() }.joinToString("")
        val pdf = write(page(listOf(PdfText(text, 0f, 0f, 500f, 10f))))
        val font = pdf.objects.getValue(pdf.ref(pdf.objects.getValue(pdf.pageIds.single()), "/F1"))
        val cmap = pdf.stream(pdf.ref(font, "/ToUnicode"))
        assertEquals(2, cmap.split("100 beginbfchar").size - 1)
        assertEquals(1, cmap.split("50 beginbfchar").size - 1)
    }

    @Test
    fun `서로 다른 글자가 65535개를 넘으면 실패`() {
        val writer = CompactPdfWriter(ByteArrayOutputStream())
        val many = buildString { for (cp in 0x10000 until 0x10000 + 0xFFFF) appendCodePoint(cp) }
        writer.addPage(page(listOf(PdfText(many, 0f, 0f, 10f, 10f))), fakeJpeg)
        val e = assertThrows(IllegalStateException::class.java) {
            writer.addPage(page(listOf(PdfText("😀가", 0f, 0f, 10f, 10f))), fakeJpeg)
        }
        assertTrue(e.message!!.contains("65535"))
    }

    @Test
    fun `순서 위반은 IllegalStateException`() {
        val writer = CompactPdfWriter(ByteArrayOutputStream())
        assertThrows(IllegalStateException::class.java) { writer.finish() }
        writer.addPage(page(), fakeJpeg)
        writer.finish()
        assertThrows(IllegalStateException::class.java) { writer.finish() }
        assertThrows(IllegalStateException::class.java) { writer.addPage(page(), fakeJpeg) }
    }

    @Test
    fun `숫자 표기 — 로캘 무관, 소수 둘째 자리, 지수·음의 0 없음`() {
        val cases = mapOf(
            0f to "0", -0f to "0", 0.001f to "0", -0.004f to "0", 1f to "1", 1.5f to "1.5", 1.25f to "1.25",
            1.05f to "1.05", -2.5f to "-2.5", 595.28f to "595.28", 14400f to "14400", 0.1f to "0.1", 99.999f to "100",
            1e-7f to "0", 123456.78f to "123456.78",
        )
        for ((v, s) in cases) assertEquals("$v", s, CompactPdfWriter.num(v))
        assertThrows(IllegalArgumentException::class.java) { CompactPdfWriter.num(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { CompactPdfWriter.num(Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `코드 포인트 분해 — 짝 없는 서로게이트는 그대로`() {
        assertEquals(listOf(0x41, 0x1F600, 0xAC00), CompactPdfWriter.codePoints("A😀가"))
        assertEquals(listOf(0xD83D, 0x41), CompactPdfWriter.codePoints("\uD83DA"))
        assertEquals(emptyList<Int>(), CompactPdfWriter.codePoints(""))
    }

    @Test
    fun `무작위 페이지·글자 — 언제나 올바른 교차 참조표`() {
        val random = Random(42)
        repeat(60) { round ->
            val pages = (0 until random.nextInt(1, 6)).map {
                val texts = (0 until random.nextInt(0, 8)).map {
                    val chars = (0 until random.nextInt(0, 30)).map { random.nextInt(0, 0x10FFFF).takeUnless { it in 0xD800..0xDFFF } ?: 0x41 }
                    PdfText(
                        buildString { chars.forEach { appendCodePoint(it) } },
                        random.nextFloat() * 500, random.nextFloat() * 800, random.nextFloat() * 300, random.nextFloat() * 40,
                    )
                }
                page(texts, random.nextInt(1, 50), random.nextInt(1, 50))
            }
            val pdf = write(*pages.toTypedArray())
            assertEquals("round $round", pages.size, pdf.pageIds.size)
            pdf.pageIds.forEach { pdf.contentOf(it) }
        }
    }
}
