package net.plumt.docscan

import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import net.plumt.docscan.internal.toGms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OptionsTest {

    @Test
    fun `기본값 — 갤러리 허용, 제한 없음, FULL, JPEG+PDF`() {
        val o = DocumentScanOptions()
        assertTrue(o.allowGalleryImport)
        assertEquals(null, o.pageLimit)
        assertEquals(ScannerMode.FULL, o.mode)
        assertEquals(setOf(ScanOutput.JPEG, ScanOutput.PDF), o.outputs)
        val expected = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .build()
        assertEquals(expected, o.toGms())
    }

    @Test
    fun `모든 조합이 Play 서비스 설정으로 정확히 옮겨진다`() {
        val modes = mapOf(
            ScannerMode.BASE to GmsDocumentScannerOptions.SCANNER_MODE_BASE,
            ScannerMode.BASE_WITH_FILTER to GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER,
            ScannerMode.FULL to GmsDocumentScannerOptions.SCANNER_MODE_FULL,
        )
        val outputs = listOf(
            setOf(ScanOutput.JPEG) to intArrayOf(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG),
            setOf(ScanOutput.PDF) to intArrayOf(GmsDocumentScannerOptions.RESULT_FORMAT_PDF),
            // 순서가 달라도 같은 설정이다
            setOf(ScanOutput.PDF, ScanOutput.JPEG) to
                intArrayOf(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF),
        )
        for ((mode, gmsMode) in modes) for (gallery in listOf(true, false)) for (limit in listOf(null, 1, 7, Int.MAX_VALUE)) {
            for ((out, formats) in outputs) {
                val b = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(gallery)
                    .setScannerMode(gmsMode)
                    .setResultFormats(formats.first(), *formats.drop(1).toIntArray())
                limit?.let { b.setPageLimit(it) }
                assertEquals("$mode $gallery $limit $out", b.build(), DocumentScanOptions(gallery, limit, mode, out).toGms())
            }
        }
        assertNotEquals(DocumentScanOptions(allowGalleryImport = false).toGms(), DocumentScanOptions().toGms())
    }

    @Test
    fun `잘못된 값은 IAE 와 받은 값`() {
        val e = assertThrows(IllegalArgumentException::class.java) { DocumentScanOptions(pageLimit = 0) }
        assertEquals("pageLimit 은 null 이거나 1 이상이어야 한다: 0", e.message)
        assertThrows(IllegalArgumentException::class.java) { DocumentScanOptions(pageLimit = Int.MIN_VALUE) }
        val o = assertThrows(IllegalArgumentException::class.java) { DocumentScanOptions(outputs = emptySet()) }
        assertTrue(o.message!!.contains("outputs"))
    }

    @Test
    fun `값 동등성`() {
        val a = DocumentScanOptions(false, 3, ScannerMode.BASE, setOf(ScanOutput.PDF))
        val b = DocumentScanOptions(false, 3, ScannerMode.BASE, setOf(ScanOutput.PDF))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, DocumentScanOptions(true, 3, ScannerMode.BASE, setOf(ScanOutput.PDF)))
        assertNotEquals(a, DocumentScanOptions(false, 4, ScannerMode.BASE, setOf(ScanOutput.PDF)))
        assertNotEquals(a, DocumentScanOptions(false, 3, ScannerMode.FULL, setOf(ScanOutput.PDF)))
        assertNotEquals(a, DocumentScanOptions(false, 3, ScannerMode.BASE, setOf(ScanOutput.JPEG)))
        assertNotEquals(a, "x")
        assertEquals(
            "DocumentScanOptions(allowGalleryImport=false, pageLimit=3, mode=BASE, outputs=[PDF])",
            a.toString(),
        )
    }
}
