package net.plumt.docscan.internal

import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import net.plumt.docscan.DocumentScanOptions
import net.plumt.docscan.ScanOutput
import net.plumt.docscan.ScannerMode

/** SDK 설정을 Play 서비스 스캐너 설정으로 바꾼다. */
@Suppress("SpreadOperator")
internal fun DocumentScanOptions.toGms(): GmsDocumentScannerOptions {
    val formats = outputs.sortedBy { it.ordinal }.map {
        when (it) {
            ScanOutput.JPEG -> GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
            ScanOutput.PDF -> GmsDocumentScannerOptions.RESULT_FORMAT_PDF
        }
    }
    val builder = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(allowGalleryImport)
        .setScannerMode(
            when (mode) {
                ScannerMode.BASE -> GmsDocumentScannerOptions.SCANNER_MODE_BASE
                ScannerMode.BASE_WITH_FILTER -> GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER
                ScannerMode.FULL -> GmsDocumentScannerOptions.SCANNER_MODE_FULL
            },
        )
        // Play 서비스 API 가 가변 인자다. 원소는 많아야 둘이라 복사 비용이 없다
        .setResultFormats(formats.first(), *formats.drop(1).toIntArray())
    pageLimit?.let { builder.setPageLimit(it) }
    return builder.build()
}
