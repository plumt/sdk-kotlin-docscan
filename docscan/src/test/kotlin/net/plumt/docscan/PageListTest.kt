package net.plumt.docscan

import android.net.Uri
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class PageListTest {
    private fun p(n: Int) = ScanPage(Uri.parse("file:///p/$n.jpg"))
    private fun list(vararg n: Int) = PageList.of(n.map { p(it) })

    @Test
    fun `여러 스캔 합치기`() {
        val first = ScannedDocument(listOf(p(1), p(2)), null)
        val second = ScannedDocument(listOf(p(3)), ScannedPdf(Uri.parse("file:///x.pdf"), 1))
        val merged = PageList.Empty + first + second
        assertEquals(listOf(p(1), p(2), p(3)), merged.pages)
        assertEquals(3, merged.size)
        assertEquals(p(2), merged[1])
        // PDF 만 받은 결과는 더할 페이지가 없다
        assertSame(merged, merged + ScannedDocument(emptyList(), ScannedPdf(Uri.parse("file:///y.pdf"), 2)))
        assertEquals(merged, PageList.of(first) + second.pages)
        assertSame(PageList.Empty, PageList.of(emptyList()))
        assertTrue(PageList.Empty.isEmpty())
    }

    @Test
    fun `순서 바꾸기 — 끌어서 옮기기 의미`() {
        val l = list(0, 1, 2, 3)
        assertEquals(list(1, 2, 0, 3), l.move(0, 2))
        assertEquals(list(3, 0, 1, 2), l.move(3, 0))
        assertSame(l, l.move(2, 2))
        assertEquals(list(2, 0, 3, 1), l.reorder(listOf(2, 0, 3, 1)))
        assertEquals(list(0, 1, 2, 3), l, )
    }

    @Test
    fun `삭제와 끼우기`() {
        val l = list(0, 1, 2)
        assertEquals(list(0, 2), l.removeAt(1))
        assertEquals(PageList.Empty, list(5).removeAt(0))
        assertEquals(list(0, 9, 1, 2), l.insert(1, listOf(p(9))))
        assertEquals(list(9, 0, 1, 2), l.insert(0, listOf(p(9))))
        assertEquals(list(0, 1, 2, 9), l.insert(3, listOf(p(9))))
    }

    @Test
    fun `범위 밖은 IAE 와 받은 값`() {
        val l = list(0, 1)
        assertEquals("index 은 0 until 2 이어야 한다: 2", assertThrows(IllegalArgumentException::class.java) { l[2] }.message)
        assertThrows(IllegalArgumentException::class.java) { l[-1] }
        assertThrows(IllegalArgumentException::class.java) { l.move(0, 2) }
        assertThrows(IllegalArgumentException::class.java) { l.move(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { l.removeAt(5) }
        assertThrows(IllegalArgumentException::class.java) { PageList.Empty.removeAt(0) }
        assertThrows(IllegalArgumentException::class.java) { l.insert(3, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { l.insert(-1, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { l.reorder(listOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { l.reorder(listOf(0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { l.reorder(listOf(0, 2)) }
    }

    @Test
    fun `원본 목록을 바꿔도 PageList 는 그대로`() {
        val source = mutableListOf(p(1))
        val l = PageList.of(source)
        source += p(2)
        assertEquals(1, l.size)
    }

    @Test
    fun `Parcel 왕복 — 프로세스 종료 복원`() {
        for (l in listOf(PageList.Empty, list(1), list(3, 1, 2), PageList.of((0 until 300).map { p(it) }))) {
            val parcel = Parcel.obtain()
            try {
                parcel.writeParcelable(l, 0)
                parcel.setDataPosition(0)
                @Suppress("DEPRECATION")
                assertEquals(l, parcel.readParcelable<PageList>(javaClass.classLoader))
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test
    fun `동등성과 문자열`() {
        assertEquals(list(1, 2), list(1, 2))
        assertEquals(list(1, 2).hashCode(), list(1, 2).hashCode())
        assertNotEquals(list(1, 2), list(2, 1))
        assertNotEquals(list(1), "x")
        assertEquals("PageList(size=2)", list(1, 2).toString())
    }

    @Test
    fun `무작위 편집 — 참조 모델과 같고 IAE 외 예외가 없다`() {
        val random = Random(20261103)
        var actual = PageList.Empty
        val model = mutableListOf<ScanPage>()
        repeat(5_000) { step ->
            val op = random.nextInt(5)
            val a = random.nextInt(-2, model.size + 3)
            val b = random.nextInt(-2, model.size + 3)
            val result = runCatching {
                when (op) {
                    0 -> actual + listOf(p(step))
                    1 -> actual.move(a, b)
                    2 -> actual.removeAt(a)
                    3 -> actual.insert(a, listOf(p(step), p(-step)))
                    else -> actual.reorder(model.indices.shuffled(random))
                }
            }
            val valid = when (op) {
                0, 4 -> true
                1 -> a in model.indices && b in model.indices
                2 -> a in model.indices
                else -> a in 0..model.size
            }
            if (valid) {
                when (op) {
                    0 -> model += p(step)
                    1 -> model.add(b, model.removeAt(a))
                    2 -> model.removeAt(a)
                    3 -> model.addAll(a, listOf(p(step), p(-step)))
                    else -> Unit
                }
                actual = result.getOrThrow()
                if (op == 4) {
                    assertEquals(model.map { it.uri.toString() }.sorted(), actual.pages.map { it.uri.toString() }.sorted())
                    model.clear()
                    model.addAll(actual.pages)
                }
                assertEquals("step $step", model, actual.pages)
            } else {
                assertTrue("step $step: ${result.exceptionOrNull()}", result.exceptionOrNull() is IllegalArgumentException)
            }
        }
    }
}
