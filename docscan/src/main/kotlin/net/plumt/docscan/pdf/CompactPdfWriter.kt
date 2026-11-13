package net.plumt.docscan.pdf

import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 최소 PDF 1.4 작성기. 페이지를 받는 대로 흘려 쓰고(메모리에 문서 전체를 두지 않는다) 끝에 페이지 트리·글꼴·교차 참조표를 쓴다.
 *
 * - 이미지: JPEG 바이트를 DCTDecode XObject 로 그대로 담는다(재압축 없음)
 * - 텍스트 층: Type0/Identity-H 글꼴(내장 안 함)과 ToUnicode CMap. 글자마다 CID 를 순서대로 매기고 ToUnicode 로 유니코드에 잇는다.
 *   렌더링 모드 3(보이지 않음)이라 글꼴 모양은 쓰이지 않고 검색·선택·복사만 된다. 줄 너비는 수평 배율(Tz)로 영역에 맞춘다
 */
internal class CompactPdfWriter(output: OutputStream) {
    private val out = CountingStream(output)
    private val offsets = mutableListOf<Long>() // offsets[i] = 객체 i+1 의 위치
    private val pageIds = mutableListOf<Int>()
    private val cids = LinkedHashMap<Int, Int>() // 코드 포인트 → CID
    private var fontId = 0
    private var finished = false

    init {
        out.ascii("%PDF-1.4\n%")
        out.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte()))
        out.ascii("\n")
        reserve() // 1: 카탈로그
        reserve() // 2: 페이지 트리
    }

    fun addPage(page: PdfPage, jpeg: ByteArray) {
        check(!finished) { "이미 끝난 문서다" }
        val imageId = beginObject()
        out.ascii(
            "<< /Type /XObject /Subtype /Image /Width ${page.image.width} /Height ${page.image.height} " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n",
        )
        out.write(jpeg)
        out.ascii("\nendstream\nendobj\n")

        val content = buildContent(page)
        val contentId = beginObject()
        out.ascii("<< /Length ${content.size} >>\nstream\n")
        out.write(content)
        out.ascii("\nendstream\nendobj\n")

        val pageId = beginObject()
        val font = if (page.texts.any { it.text.isNotBlank() }) " /Font << /F1 ${font()} 0 R >>" else ""
        out.ascii(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(page.widthPt)} ${num(page.heightPt)}] " +
                "/Resources << /XObject << /Im1 $imageId 0 R >>$font >> /Contents $contentId 0 R >>\nendobj\n",
        )
        pageIds += pageId
    }

    fun finish() {
        check(!finished) { "이미 끝난 문서다" }
        check(pageIds.isNotEmpty()) { "페이지가 없다" }
        finished = true
        if (fontId != 0) writeFont()
        writeAt(2, "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>")
        writeAt(1, "<< /Type /Catalog /Pages 2 0 R >>")
        val xref = out.count
        out.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) out.ascii(o.toString().padStart(OFFSET_DIGITS, '0') + " 00000 n \n")
        out.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }

    private fun buildContent(page: PdfPage): ByteArray {
        val sb = StringBuilder()
        // 이미지: 단위 정사각형을 이미지 영역으로 늘린다. PDF 는 왼쪽 아래 원점이다
        val y = page.heightPt - page.imageTop - page.imageHeight
        sb.append("q ${num(page.imageWidth)} 0 0 ${num(page.imageHeight)} ${num(page.imageLeft)} ${num(y)} cm /Im1 Do Q\n")
        for (t in page.texts) {
            val codes = codePoints(t.text).map { if (it < SPACE) SPACE else it }
            if (!drawable(t, codes)) continue
            val size = t.height
            // 모든 글자의 기본 너비는 /DW 500 → 글자 수 x 0.5 x 크기. 영역 너비에 맞게 수평 배율(%)을 정한다
            val natural = codes.size * GLYPH_WIDTH / GLYPH_UNITS * size
            val scale = t.width / natural * PERCENT
            val baseline = page.heightPt - t.top - t.height
            val hex = codes.joinToString("") { cid(it).toString(HEX).padStart(HEX_DIGITS, '0').uppercase() }
            sb.append("BT 3 Tr /F1 ${num(size)} Tf ${num(scale)} Tz 1 0 0 1 ${num(t.left)} ${num(baseline)} Tm <$hex> Tj ET\n")
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun drawable(t: PdfText, codes: List<Int>): Boolean =
        t.text.isNotBlank() && codes.isNotEmpty() && t.width > 0f && t.height > 0f

    private fun cid(codePoint: Int): Int = cids.getOrPut(codePoint) {
        check(cids.size < MAX_CID) { "텍스트 층의 서로 다른 글자가 ${MAX_CID}개를 넘었다" }
        cids.size + 1
    }

    /** 글꼴 객체 번호 4개(Type0·CIDFont·FontDescriptor·ToUnicode)를 처음 쓸 때 잡는다. */
    private fun font(): Int {
        if (fontId == 0) {
            fontId = reserve()
            repeat(FONT_OBJECTS - 1) { reserve() }
        }
        return fontId
    }

    private fun writeFont() {
        val (cidFont, descriptor, toUnicode) = listOf(fontId + 1, fontId + 2, fontId + FONT_OBJECTS - 1)
        writeAt(
            fontId,
            "<< /Type /Font /Subtype /Type0 /BaseFont /$FONT_NAME /Encoding /Identity-H " +
                "/DescendantFonts [$cidFont 0 R] /ToUnicode $toUnicode 0 R >>",
        )
        writeAt(
            cidFont,
            "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /$FONT_NAME " +
                "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                "/FontDescriptor $descriptor 0 R /DW ${GLYPH_WIDTH.toInt()} /CIDToGIDMap /Identity >>",
        )
        writeAt(
            descriptor,
            "<< /Type /FontDescriptor /FontName /$FONT_NAME /Flags 4 /FontBBox [0 0 ${GLYPH_WIDTH.toInt()} ${GLYPH_UNITS.toInt()}] " +
                "/ItalicAngle 0 /Ascent 1000 /Descent 0 /CapHeight 1000 /StemV 80 >>",
        )
        val cmap = buildCMap().toByteArray(Charsets.US_ASCII)
        offsets[toUnicode - 1] = out.count
        out.ascii("$toUnicode 0 obj\n<< /Length ${cmap.size} >>\nstream\n")
        out.write(cmap)
        out.ascii("\nendstream\nendobj\n")
    }

    private fun buildCMap(): String {
        val sb = StringBuilder()
        sb.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        sb.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        sb.append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        sb.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        cids.entries.chunked(BFCHAR_CHUNK).forEach { chunk ->
            sb.append("${chunk.size} beginbfchar\n")
            for ((codePoint, cid) in chunk) {
                val utf16 = String(Character.toChars(codePoint)).map { it.code.toString(HEX).padStart(HEX_DIGITS, '0') }.joinToString("")
                sb.append("<${cid.toString(HEX).padStart(HEX_DIGITS, '0').uppercase()}> <${utf16.uppercase()}>\n")
            }
            sb.append("endbfchar\n")
        }
        sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend")
        return sb.toString()
    }

    private fun reserve(): Int {
        offsets += -1L
        return offsets.size
    }

    private fun beginObject(): Int {
        val id = reserve()
        offsets[id - 1] = out.count
        out.ascii("$id 0 obj\n")
        return id
    }

    private fun writeAt(id: Int, dict: String) {
        offsets[id - 1] = out.count
        out.ascii("$id 0 obj\n$dict\nendobj\n")
    }

    private class CountingStream(private val target: OutputStream) {
        var count = 0L
            private set

        fun write(bytes: ByteArray) {
            target.write(bytes)
            count += bytes.size
        }

        fun ascii(text: String) = write(text.toByteArray(Charsets.US_ASCII))

        fun flush() = target.flush()
    }

    companion object {
        private const val FONT_NAME = "PlumtInvisibleText"
        private const val FONT_OBJECTS = 4
        private const val GLYPH_WIDTH = 500f
        private const val GLYPH_UNITS = 1000f
        private const val PERCENT = 100f
        private const val HEX = 16
        private const val SPACE = 0x20
        private const val OFFSET_DIGITS = 10
        private const val BFCHAR_CHUNK = 100
        private const val MAX_CID = 0xFFFF
        private const val CENTI = 100.0
        private const val TENTHS = 10L
        private const val HEX_DIGITS = 4

        /** 스트림(`CharSequence.codePoints()`)을 거치지 않고 직접 센다. 짝 없는 서로게이트는 그 값 그대로 둔다. */
        fun codePoints(text: String): List<Int> {
            val result = ArrayList<Int>(text.length)
            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                result += cp
                i += Character.charCount(cp)
            }
            return result
        }

        /** 로캘과 무관한 소수 둘째 자리 표기. 지수 표기·`-0` 을 쓰지 않는다. */
        fun num(value: Float): String {
            require(value.isFinite()) { "유한수가 아니다: $value" }
            val scaled = (value * CENTI).roundToLong()
            if (scaled == 0L) return "0"
            val sign = if (scaled < 0) "-" else ""
            val a = abs(scaled)
            val whole = a / CENTI.toLong()
            val frac = a % CENTI.toLong()
            return when {
                frac == 0L -> "$sign$whole"
                frac % TENTHS == 0L -> "$sign$whole.${frac / TENTHS}"
                else -> "$sign$whole.${frac.toString().padStart(2, '0')}"
            }
        }
    }
}
