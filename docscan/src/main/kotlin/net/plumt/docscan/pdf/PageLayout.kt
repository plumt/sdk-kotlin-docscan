package net.plumt.docscan.pdf

import kotlin.math.max
import kotlin.math.min

/** 페이지 크기와 이미지 자리(포인트, 왼쪽 위 원점). */
internal class PagePlan(
    val widthPt: Float,
    val heightPt: Float,
    val imageLeft: Float,
    val imageTop: Float,
    val imageWidth: Float,
    val imageHeight: Float,
) {
    override fun toString(): String = "PagePlan(${widthPt}x$heightPt, image=($imageLeft,$imageTop ${imageWidth}x$imageHeight))"
}

/** 순수 배치 계산. */
internal object PageLayout {
    const val A4_WIDTH = 595f
    const val A4_HEIGHT = 842f
    const val LETTER_WIDTH = 612f
    const val LETTER_HEIGHT = 792f
    private const val POINTS_PER_INCH = 72f

    /** 여백은 페이지 짧은 변의 1/4 을 넘지 않게 줄인다 — 이미지 자리가 언제나 짧은 변의 절반 이상 남는다. */
    private const val MAX_MARGIN_FRACTION = 0.25f

    fun plan(imageWidthPx: Int, imageHeightPx: Int, size: PageSize, marginPt: Float): PagePlan {
        require(imageWidthPx > 0 && imageHeightPx > 0) { "이미지 크기는 양수여야 한다: ${imageWidthPx}x$imageHeightPx" }
        val landscape = imageWidthPx > imageHeightPx
        val (pw, ph) = when (size) {
            PageSize.A4 -> orient(A4_WIDTH, A4_HEIGHT, landscape)
            PageSize.Letter -> orient(LETTER_WIDTH, LETTER_HEIGHT, landscape)
            is PageSize.Custom -> orient(size.widthPt, size.heightPt, landscape)
            PageSize.Original -> {
                val longEdge = A4_HEIGHT
                val ratio = min(imageWidthPx, imageHeightPx).toFloat() / max(imageWidthPx, imageHeightPx)
                val short = max(PageSize.MIN_POINTS, longEdge * ratio)
                if (landscape) longEdge to short else short to longEdge
            }
        }
        val margin = min(marginPt, min(pw, ph) * MAX_MARGIN_FRACTION)
        val boxW = pw - 2 * margin
        val boxH = ph - 2 * margin
        val scale = min(boxW / imageWidthPx, boxH / imageHeightPx)
        val w = imageWidthPx * scale
        val h = imageHeightPx * scale
        return PagePlan(pw, ph, (pw - w) / 2, (ph - h) / 2, w, h)
    }

    /** [plan] 의 이미지 자리를 [dpi] 로 채울 픽셀 크기. 원본보다 크게 하지 않는다. */
    fun targetPixels(plan: PagePlan, dpi: Int, imageWidthPx: Int, imageHeightPx: Int): Pair<Int, Int> {
        val wantW = plan.imageWidth / POINTS_PER_INCH * dpi
        val wantH = plan.imageHeight / POINTS_PER_INCH * dpi
        val scale = min(1f, min(wantW / imageWidthPx, wantH / imageHeightPx))
        return max(1, (imageWidthPx * scale).toInt()) to max(1, (imageHeightPx * scale).toInt())
    }

    /** 이미지 픽셀 좌표의 글자 줄을 페이지 좌표로 옮긴다. 빈 글자·넓이 없는 영역은 버리고, 이미지 밖은 잘라낸다. */
    fun mapText(lines: List<TextLine>, plan: PagePlan, bitmapWidth: Int, bitmapHeight: Int): List<PdfText> {
        val sx = plan.imageWidth / bitmapWidth
        val sy = plan.imageHeight / bitmapHeight
        return lines.mapNotNull { line ->
            if (line.text.isBlank()) return@mapNotNull null
            val l = line.left.coerceIn(0f, bitmapWidth.toFloat())
            val r = line.right.coerceIn(0f, bitmapWidth.toFloat())
            val t = line.top.coerceIn(0f, bitmapHeight.toFloat())
            val b = line.bottom.coerceIn(0f, bitmapHeight.toFloat())
            if (r <= l || b <= t) return@mapNotNull null
            PdfText(line.text.trim(), plan.imageLeft + l * sx, plan.imageTop + t * sy, (r - l) * sx, (b - t) * sy)
        }
    }

    private fun orient(w: Float, h: Float, landscape: Boolean): Pair<Float, Float> {
        val short = min(w, h)
        val long = max(w, h)
        return if (landscape) long to short else short to long
    }
}
