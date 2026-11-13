package net.plumt.docscan.internal

import android.app.Activity
import android.content.Intent
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import net.plumt.docscan.DocScanException
import net.plumt.docscan.DocScanFailureReason
import net.plumt.docscan.DocumentScanResult
import net.plumt.docscan.ScanPage
import net.plumt.docscan.ScannedDocument
import net.plumt.docscan.ScannedPdf

/** 결과 인텐트에서 문서를 읽는다. 없으면 null. */
internal fun interface ScanResultReader {
    fun read(intent: Intent): ScannedDocument?

    companion object {
        val Gms = ScanResultReader { intent ->
            GmsDocumentScanningResult.fromActivityResultIntent(intent)?.let { r ->
                ScannedDocument(
                    pages = r.pages.orEmpty().map { ScanPage(it.imageUri) },
                    pdf = r.pdf?.let { ScannedPdf(it.uri, it.pageCount) },
                )
            }
        }
    }
}

/** 액티비티 결과 코드·인텐트를 타입 있는 결과로 바꾼다. */
internal fun parseScanResult(resultCode: Int, intent: Intent?, reader: ScanResultReader): DocumentScanResult {
    when (resultCode) {
        Activity.RESULT_CANCELED -> return DocumentScanResult.Cancelled
        Activity.RESULT_OK -> Unit
        else -> return DocumentScanResult.Failure(
            DocScanException(DocScanFailureReason.UNKNOWN, "스캐너 화면이 알 수 없는 결과 코드로 끝났다: $resultCode", statusCode = resultCode),
        )
    }
    if (intent == null) return missing("결과 인텐트가 없다")
    val document = try {
        reader.read(intent)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        return DocumentScanResult.Failure(
            DocScanException(DocScanFailureReason.RESULT_MISSING, "스캔 결과를 읽지 못했다: ${e.javaClass.simpleName}", e),
        )
    }
    return when {
        document == null -> missing("결과 인텐트에 스캔 결과가 없다")
        document.pages.isEmpty() && document.pdf == null -> missing("스캔 결과에 페이지도 PDF 도 없다")
        else -> DocumentScanResult.Success(document)
    }
}

private fun missing(message: String) =
    DocumentScanResult.Failure(DocScanException(DocScanFailureReason.RESULT_MISSING, message))
