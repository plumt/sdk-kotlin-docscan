package net.plumt.docscan.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PageLayoutTest {
    private val eps = 0.01f

    private fun assertPlan(p: PagePlan, w: Float, h: Float, l: Float, t: Float, iw: Float, ih: Float) {
        assertEquals("w $p", w, p.widthPt, eps)
        assertEquals("h $p", h, p.heightPt, eps)
        assertEquals("l $p", l, p.imageLeft, eps)
        assertEquals("t $p", t, p.imageTop, eps)
        assertEquals("iw $p", iw, p.imageWidth, eps)
        assertEquals("ih $p", ih, p.imageHeight, eps)
    }

    @Test
    fun `A4 세로 이미지는 세로 페이지, 가운데 맞춤`() {
        // 1000x2000: 높이 기준 842 → 너비 421, 가로 여백 (595-421)/2
        assertPlan(PageLayout.plan(1000, 2000, PageSize.A4, 0f), 595f, 842f, 87f, 0f, 421f, 842f)
        // A4 와 같은 비율은 꽉 찬다
        assertPlan(PageLayout.plan(595, 842, PageSize.A4, 0f), 595f, 842f, 0f, 0f, 595f, 842f)
    }

    @Test
    fun `가로 이미지는 가로 페이지 — A4·Letter·Custom`() {
        assertPlan(PageLayout.plan(842, 595, PageSize.A4, 0f), 842f, 595f, 0f, 0f, 842f, 595f)
        val letter = PageLayout.plan(2000, 1000, PageSize.Letter, 0f)
        assertEquals(792f, letter.widthPt, eps)
        assertEquals(612f, letter.heightPt, eps)
        val custom = PageLayout.plan(300, 200, PageSize.Custom(100f, 400f), 0f)
        assertEquals(400f, custom.widthPt, eps)
        assertEquals(100f, custom.heightPt, eps)
        // 정사각형은 세로로 둔다
        assertEquals(612f, PageLayout.plan(500, 500, PageSize.Letter, 0f).widthPt, eps)
    }

    @Test
    fun `원본 크기 — 비율 유지, 긴 변 842, 짧은 변 최소 72`() {
        assertPlan(PageLayout.plan(1000, 2000, PageSize.Original, 0f), 421f, 842f, 0f, 0f, 421f, 842f)
        assertPlan(PageLayout.plan(3000, 1000, PageSize.Original, 0f), 842f, 280.67f, 0f, 0f, 842f, 280.67f)
        val strip = PageLayout.plan(10_000, 1, PageSize.Original, 0f)
        assertEquals(842f, strip.widthPt, eps)
        assertEquals(72f, strip.heightPt, eps)
        assertTrue(strip.imageHeight <= strip.heightPt)
    }

    @Test
    fun `여백은 짧은 변의 1-4 까지만`() {
        assertPlan(PageLayout.plan(595, 842, PageSize.A4, 36f), 595f, 842f, 36f, 50.94f, 523f, 740.11f)
        val tiny = PageLayout.plan(100, 100, PageSize.Custom(72f, 72f), 144f)
        assertEquals(18f, tiny.imageLeft, eps)
        assertEquals(36f, tiny.imageWidth, eps)
    }

    @Test
    fun `이미지 크기는 양수`() {
        assertThrows(IllegalArgumentException::class.java) { PageLayout.plan(0, 10, PageSize.A4, 0f) }
        assertThrows(IllegalArgumentException::class.java) { PageLayout.plan(10, -1, PageSize.A4, 0f) }
    }

    @Test
    fun `목표 픽셀 — dpi 에 맞추고 원본보다 키우지 않는다`() {
        val plan = PageLayout.plan(4000, 6000, PageSize.A4, 0f)
        val (w, h) = PageLayout.targetPixels(plan, 150, 4000, 6000)
        // 이미지 자리 561.3 x 842 pt 를 150 dpi 로 → 약 1169 x 1754
        assertTrue("$w", kotlin.math.abs(w - 1169) <= 1)
        assertTrue("$h", kotlin.math.abs(h - 1754) <= 1)
        assertEquals(400 to 600, PageLayout.targetPixels(PageLayout.plan(400, 600, PageSize.A4, 0f), 600, 400, 600))
        assertEquals(1 to 1, PageLayout.targetPixels(PageLayout.plan(1, 1, PageSize.A4, 0f), 50, 1, 1))
        val strip = PageLayout.plan(100_000, 1, PageSize.A4, 0f)
        val (sw, sh) = PageLayout.targetPixels(strip, 50, 100_000, 1)
        assertTrue(sw >= 1 && sh == 1)
    }

    @Test
    fun `글자 영역을 페이지 좌표로 옮긴다 — 축척·자르기·빈 줄 버림`() {
        val plan = PagePlan(595f, 842f, 100f, 50f, 200f, 400f)
        val texts = PageLayout.mapText(
            listOf(
                TextLine("안녕 Hello", 10f, 20f, 60f, 40f),
                TextLine("   ", 0f, 0f, 10f, 10f),
                TextLine("밖", -50f, -50f, 1000f, 1000f),
                TextLine("넓이 없음", 30f, 30f, 30f, 50f),
                TextLine("완전히 밖", 200f, 0f, 300f, 10f),
                TextLine("  공백 정리  ", 0f, 0f, 100f, 200f),
            ),
            plan, 100, 200,
        )
        assertEquals(
            listOf(
                PdfText("안녕 Hello", 120f, 90f, 100f, 40f),
                PdfText("밖", 100f, 50f, 200f, 400f),
                PdfText("공백 정리", 100f, 50f, 200f, 400f),
            ),
            texts,
        )
    }

    @Test
    fun `무작위 크기 — 이미지 자리는 항상 페이지 안, 비율 유지`() {
        val random = Random(7)
        val sizes = listOf(PageSize.A4, PageSize.Letter, PageSize.Original, PageSize.Custom(72f, 14_400f))
        repeat(3_000) {
            val w = random.nextInt(1, 20_000)
            val h = random.nextInt(1, 20_000)
            val size = sizes[random.nextInt(sizes.size)]
            val margin = random.nextFloat() * 144f
            val p = PageLayout.plan(w, h, size, margin)
            assertTrue("$p", p.imageLeft >= -eps && p.imageTop >= -eps)
            assertTrue("$p", p.imageLeft + p.imageWidth <= p.widthPt + eps && p.imageTop + p.imageHeight <= p.heightPt + eps)
            assertEquals("$w x $h $p", w.toFloat() / h, p.imageWidth / p.imageHeight, w.toFloat() / h * 0.001f + 0.001f)
            val (tw, th) = PageLayout.targetPixels(p, random.nextInt(50, 601), w, h)
            assertTrue(tw in 1..w && th in 1..h)
        }
    }
}
