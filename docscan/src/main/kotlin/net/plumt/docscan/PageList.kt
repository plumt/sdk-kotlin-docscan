package net.plumt.docscan

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 여러 번 스캔한 페이지를 모은 불변 목록. 합치기·순서 바꾸기·삭제는 새 목록을 돌려준다.
 *
 * [Parcelable] 이라 `rememberSaveable { mutableStateOf(PageList.Empty) }` 나 `SavedStateHandle` 에 담아
 * 회전·프로세스 종료 뒤에도 유지된다. 페이지 파일 자체를 오래 두려면 [net.plumt.docscan.storage.ScanPageStore.keep] 으로
 * 캐시 밖에 옮긴 페이지를 담는다. 완성된 목록은 [net.plumt.docscan.pdf.PdfComposer] 로 PDF 를 다시 만든다.
 */
@Parcelize
public class PageList private constructor(public val pages: List<ScanPage>) : Parcelable {

    public val size: Int get() = pages.size

    public fun isEmpty(): Boolean = pages.isEmpty()

    public operator fun get(index: Int): ScanPage {
        checkIndex(index, "index")
        return pages[index]
    }

    /** 스캔 결과의 페이지 이미지를 뒤에 붙인다. 페이지 이미지가 없는(PDF 만 받은) 결과는 아무것도 더하지 않는다. */
    public operator fun plus(document: ScannedDocument): PageList = plus(document.pages)

    public operator fun plus(more: List<ScanPage>): PageList = if (more.isEmpty()) this else PageList(pages + more)

    /** [index] 위치에 [more] 를 끼운다. [index] 는 0..[size]. */
    public fun insert(index: Int, more: List<ScanPage>): PageList {
        require(index in 0..size) { "index 는 0..$size 이어야 한다: $index" }
        return PageList(pages.subList(0, index) + more + pages.subList(index, size))
    }

    /** [from] 의 페이지를 빼서 [to] 위치에 둔다(끌어서 옮기기와 같은 의미). */
    public fun move(from: Int, to: Int): PageList {
        checkIndex(from, "from")
        checkIndex(to, "to")
        if (from == to) return this
        val list = pages.toMutableList()
        list.add(to, list.removeAt(from))
        return PageList(list)
    }

    public fun removeAt(index: Int): PageList {
        checkIndex(index, "index")
        return PageList(pages.filterIndexed { i, _ -> i != index })
    }

    /** [order] 순서로 다시 배열한다. [order] 는 0 until [size] 의 순열이어야 한다. */
    public fun reorder(order: List<Int>): PageList {
        require(order.size == size && order.sorted() == pages.indices.toList()) {
            "order 는 0 until $size 의 순열이어야 한다: $order"
        }
        return PageList(order.map { pages[it] })
    }

    override fun equals(other: Any?): Boolean = other is PageList && pages == other.pages
    override fun hashCode(): Int = pages.hashCode()
    override fun toString(): String = "PageList(size=$size)"

    private fun checkIndex(index: Int, name: String) {
        require(index in pages.indices) { "$name 은 0 until $size 이어야 한다: $index" }
    }

    public companion object {
        @JvmField
        public val Empty: PageList = PageList(emptyList())

        public fun of(pages: List<ScanPage>): PageList = if (pages.isEmpty()) Empty else PageList(pages.toList())

        public fun of(document: ScannedDocument): PageList = of(document.pages)
    }
}
