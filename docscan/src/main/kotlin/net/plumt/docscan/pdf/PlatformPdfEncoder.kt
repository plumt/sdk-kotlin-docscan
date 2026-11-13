package net.plumt.docscan.pdf

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * `android.graphics.pdf.PdfDocument`(Skia PDF) 로 쓴다.
 *
 * 텍스트 층: Skia 는 완전히 투명한 글자를 그리지 않고 버리므로, 글자를 먼저 그리고 그 위를 불투명한 페이지 이미지로 덮는다.
 * 글자는 PDF 안에 남아 검색·선택·복사되지만 화면·인쇄에는 이미지만 보인다.
 */
internal object PlatformPdfEncoder : PdfEncoder {
    override fun open(output: OutputStream): PdfEncoder.Session = PlatformSession(output)

    override fun toString(): String = "PdfEncoder.platform"

    private class PlatformSession(private val output: OutputStream) : PdfEncoder.Session {
        private val document = PdfDocument()
        private var number = 0
        private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        override fun addPage(page: PdfPage) {
            number++
            val info = PdfDocument.PageInfo.Builder(page.widthPt.roundToInt(), page.heightPt.roundToInt(), number).create()
            val pdfPage = document.startPage(info)
            val canvas = pdfPage.canvas
            for (t in page.texts) {
                textPaint.textScaleX = 1f
                textPaint.textSize = t.height * TEXT_SIZE_RATIO
                val natural = textPaint.measureText(t.text)
                if (natural > 0f) textPaint.textScaleX = t.width / natural
                canvas.drawText(t.text, t.left, t.top + t.height * BASELINE_RATIO, textPaint)
            }
            val dst = RectF(page.imageLeft, page.imageTop, page.imageLeft + page.imageWidth, page.imageTop + page.imageHeight)
            canvas.drawBitmap(page.image, null, dst, imagePaint)
            document.finishPage(pdfPage)
        }

        override fun finish() {
            document.writeTo(output)
            document.close()
        }

        override fun abort() {
            document.close()
        }
    }

    private const val TEXT_SIZE_RATIO = 0.85f
    private const val BASELINE_RATIO = 0.8f
}
