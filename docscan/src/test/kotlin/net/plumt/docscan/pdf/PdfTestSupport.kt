package net.plumt.docscan.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** 시험용 최소 PDF 판독기 — 교차 참조표가 모든 객체 위치를 정확히 가리키는지 검사하고 객체 본문을 꺼낸다. 정규식을 쓰지 않는다. */
internal class PdfProbe(bytes: ByteArray) {
    val text = String(bytes, Charsets.ISO_8859_1)
    val objects: Map<Int, String>

    init {
        assertTrue("헤더", text.startsWith("%PDF-1.4\n"))
        assertTrue("끝 표시", text.endsWith("%%EOF\n"))
        val sx = text.lastIndexOf("startxref\n")
        val xrefAt = text.substring(sx + "startxref\n".length, text.indexOf('\n', sx + "startxref\n".length)).toInt()
        assertTrue("xref 위치", text.startsWith("xref\n", xrefAt))
        val header = text.substring(xrefAt + 5, text.indexOf('\n', xrefAt + 5)).split(' ')
        assertEquals("0", header[0])
        val count = header[1].toInt()
        var p = text.indexOf('\n', xrefAt + 5) + 1
        val map = LinkedHashMap<Int, String>()
        for (i in 0 until count) {
            val entry = text.substring(p, p + 20)
            assertEquals("xref 항목 길이", 20, entry.length)
            assertTrue(entry.endsWith(" \n"))
            if (i == 0) {
                assertEquals("0000000000 65535 f \n", entry)
            } else {
                assertTrue(entry, entry.substring(10).startsWith(" 00000 n"))
                val off = entry.substring(0, 10).toInt()
                assertTrue("객체 $i 위치가 맞다", text.startsWith("$i 0 obj\n", off))
                val end = text.indexOf("endobj\n", off)
                map[i] = text.substring(off + "$i 0 obj\n".length, end)
            }
            p += 20
        }
        val trailer = text.substring(p)
        assertTrue(trailer, trailer.startsWith("trailer\n<< /Size $count /Root 1 0 R >>"))
        objects = map
        assertTrue("카탈로그", objects.getValue(1).contains("/Type /Catalog /Pages 2 0 R"))
    }

    val pageIds: List<Int>
        get() {
            val pages = objects.getValue(2)
            val kids = pages.substring(pages.indexOf("[") + 1, pages.indexOf("]")).trim()
            return if (kids.isEmpty()) emptyList() else kids.split(" 0 R").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }
        }

    fun ref(dict: String, key: String): Int {
        val token = if (key.endsWith("[")) key else "$key "
        val at = dict.indexOf(token)
        assertTrue("$key 없음: $dict", at >= 0)
        return dict.substring(at + token.length).substringBefore(' ').toInt()
    }

    fun stream(id: Int): String {
        val body = objects.getValue(id)
        val len = body.substring(body.indexOf("/Length ") + 8).takeWhile { it.isDigit() }.toInt()
        val start = body.indexOf("stream\n") + 7
        val data = body.substring(start, start + len)
        assertTrue("스트림 길이가 맞다", body.startsWith("\nendstream\n", start + len))
        return data
    }

    fun contentOf(pageId: Int): String = stream(ref(objects.getValue(pageId), "/Contents"))
}
