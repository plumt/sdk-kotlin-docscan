package net.plumt.docscan.pdf

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.plumt.docscan.PageList
import net.plumt.docscan.ScanPage
import java.io.OutputStream

/**
 * 페이지 이미지로 PDF 를 다시 만든다. 여러 번 스캔해 합친 [PageList] 를 순서대로 담고, 페이지 크기를 고른다.
 *
 * 한 번에 페이지 하나만 메모리에 올린다(디코딩 → 텍스트 인식 → 쓰기 → recycle). 실패하면 [net.plumt.docscan.DocScanException],
 * 틀린 인자는 [IllegalArgumentException]. 코루틴 취소는 페이지 사이에서 반영되고 그대로 전파한다 — 출력 스트림에는
 * 쓰다 만 바이트가 남을 수 있으므로 저장은 [net.plumt.docscan.storage.PdfSaver] 를 거친다(실패·취소 시 지운다).
 */
public interface PdfComposer {
    /**
     * @param pages 1..[MAX_PAGES] 장
     * @param output 닫지 않는다
     */
    public suspend fun compose(pages: List<ScanPage>, output: OutputStream, options: PdfOptions = PdfOptions()): PdfReport

    public suspend fun compose(pages: PageList, output: OutputStream, options: PdfOptions = PdfOptions()): PdfReport =
        compose(pages.pages, output, options)

    public companion object {
        public const val MAX_PAGES: Int = 500

        /** @param dispatcher 디코딩·인코딩·쓰기를 돌릴 곳. 기본 [Dispatchers.IO] */
        public fun create(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.IO): PdfComposer =
            DefaultPdfComposer(ResolverPageImageLoader(context.applicationContext.contentResolver), dispatcher)
    }
}

/**
 * @property pageCount 쓴 페이지 수
 * @property textLineCount 텍스트 층에 깐 줄 수. 인식기를 주지 않았으면 0
 */
public class PdfReport(public val pageCount: Int, public val textLineCount: Int) {
    override fun equals(other: Any?): Boolean = other is PdfReport && pageCount == other.pageCount && textLineCount == other.textLineCount
    override fun hashCode(): Int = 31 * pageCount + textLineCount
    override fun toString(): String = "PdfReport(pageCount=$pageCount, textLineCount=$textLineCount)"
}
