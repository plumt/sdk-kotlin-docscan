package net.plumt.docscan.pdf

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** 페이지 이미지를 JPEG 로 인코딩해 [CompactPdfWriter] 에 넘긴다. */
internal class CompactPdfEncoder(private val jpegQuality: Int) : PdfEncoder {
    init {
        require(jpegQuality in 1..MAX_QUALITY) { "jpegQuality 는 1..$MAX_QUALITY 이어야 한다: $jpegQuality" }
    }

    override fun open(output: OutputStream): PdfEncoder.Session = object : PdfEncoder.Session {
        private val writer = CompactPdfWriter(output)

        override fun addPage(page: PdfPage) {
            val jpeg = ByteArrayOutputStream().use {
                check(page.image.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)) { "JPEG 인코딩 실패" }
                it.toByteArray()
            }
            writer.addPage(page, jpeg)
        }

        override fun finish() = writer.finish()

        override fun abort() = Unit
    }

    override fun toString(): String = "PdfEncoder.compact(jpegQuality=$jpegQuality)"

    companion object {
        const val DEFAULT_QUALITY: Int = 85
        const val MAX_QUALITY: Int = 100
    }
}
