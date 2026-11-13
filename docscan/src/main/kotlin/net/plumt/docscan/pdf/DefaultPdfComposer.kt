package net.plumt.docscan.pdf

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.plumt.docscan.DocScanException
import net.plumt.docscan.DocScanFailureReason
import net.plumt.docscan.ScanPage
import net.plumt.docscan.internal.FailureClassifier
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

internal class DefaultPdfComposer(
    private val loader: PageImageLoader,
    private val dispatcher: CoroutineDispatcher,
) : PdfComposer {

    override suspend fun compose(pages: List<ScanPage>, output: OutputStream, options: PdfOptions): PdfReport {
        require(pages.isNotEmpty()) { "pages 가 비었다 — 1..${PdfComposer.MAX_PAGES} 장" }
        require(pages.size <= PdfComposer.MAX_PAGES) { "pages 는 ${PdfComposer.MAX_PAGES} 장 이하여야 한다: ${pages.size}" }
        return withContext(dispatcher) { write(pages, output, options) }
    }

    private suspend fun write(pages: List<ScanPage>, output: OutputStream, options: PdfOptions): PdfReport {
        val session = guard(DocScanFailureReason.PDF_FAILED, "PDF 시작") { options.encoder.open(output) }
        var lines = 0
        try {
            for ((index, page) in pages.withIndex()) {
                coroutineContext.ensureActive()
                lines += addPage(session, page, index, options)
            }
            coroutineContext.ensureActive()
            guard(DocScanFailureReason.PDF_FAILED, "PDF 마무리") { session.finish() }
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            session.abort()
            throw e
        }
        return PdfReport(pages.size, lines)
    }

    private suspend fun addPage(session: PdfEncoder.Session, page: ScanPage, index: Int, options: PdfOptions): Int {
        val where = "${index + 1}번째 페이지"
        val (w, h) = guard(DocScanFailureReason.IMAGE_DECODE_FAILED, where) { loader.bounds(page.uri) }
        val plan = PageLayout.plan(w, h, options.pageSize, options.marginPt)
        val (tw, th) = PageLayout.targetPixels(plan, options.imageDpi, w, h)
        val bitmap = guard(DocScanFailureReason.IMAGE_DECODE_FAILED, where) { loader.load(page.uri, tw, th) }
        try {
            val texts = options.textRecognizer?.let { recognize(it, bitmap, plan, where) }.orEmpty()
            val pdfPage = PdfPage(
                plan.widthPt, plan.heightPt, bitmap,
                plan.imageLeft, plan.imageTop, plan.imageWidth, plan.imageHeight, texts,
            )
            guard(DocScanFailureReason.PDF_FAILED, where) { session.addPage(pdfPage) }
            return texts.size
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognize(recognizer: PageTextRecognizer, bitmap: Bitmap, plan: PagePlan, where: String): List<PdfText> {
        val found = try {
            recognizer.recognize(bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DocScanException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw FailureClassifier.classify(e, where)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            throw DocScanException(DocScanFailureReason.TEXT_RECOGNITION_FAILED, "$where: 텍스트 인식 실패: ${e.message}", e)
        }
        return PageLayout.mapText(found, plan, bitmap.width, bitmap.height)
    }

    /** 블록의 예외를 분류한다. 이미 분류된 것·IAE·취소는 그대로, IO·메모리는 그 분류로, 나머지는 [fallback]. */
    private inline fun <T> guard(fallback: DocScanFailureReason, where: String, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: DocScanException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: IOException) {
        throw FailureClassifier.fromIo(e, where)
    } catch (e: OutOfMemoryError) {
        throw FailureClassifier.classify(e, where)
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        throw DocScanException(fallback, "$where: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}
