package net.plumt.docscan

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import net.plumt.docscan.internal.ScanResultReader
import net.plumt.docscan.internal.parseScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 29, 34])
class ScanResultTest {
    private val page1 = ScanPage(Uri.parse("file:///cache/p1.jpg"))
    private val page2 = ScanPage(Uri.parse("content://x/p2.jpg"))
    private val pdf = ScannedPdf(Uri.parse("file:///cache/doc.pdf"), 2)
    private val doc = ScannedDocument(listOf(page1, page2), pdf)

    @Test
    fun `취소 코드는 Cancelled`() {
        assertSame(DocumentScanResult.Cancelled, parseScanResult(Activity.RESULT_CANCELED, null, { error("unused") }))
        assertSame(DocumentScanResult.Cancelled, parseScanResult(Activity.RESULT_CANCELED, Intent(), { doc }))
    }

    @Test
    fun `성공 코드와 문서는 Success`() {
        val r = parseScanResult(Activity.RESULT_OK, Intent(), { doc })
        assertEquals(DocumentScanResult.Success(doc), r)
        val onlyPdf = ScannedDocument(emptyList(), pdf)
        assertEquals(DocumentScanResult.Success(onlyPdf), parseScanResult(Activity.RESULT_OK, Intent(), { onlyPdf }))
        val onlyPages = ScannedDocument(listOf(page1), null)
        assertEquals(DocumentScanResult.Success(onlyPages), parseScanResult(Activity.RESULT_OK, Intent(), { onlyPages }))
    }

    @Test
    fun `성공 코드인데 결과가 없거나 비면 RESULT_MISSING`() {
        fun reason(r: DocumentScanResult) = (r as DocumentScanResult.Failure).error.reason
        assertEquals(DocScanFailureReason.RESULT_MISSING, reason(parseScanResult(Activity.RESULT_OK, null, { doc })))
        assertEquals(DocScanFailureReason.RESULT_MISSING, reason(parseScanResult(Activity.RESULT_OK, Intent(), { null })))
        assertEquals(
            DocScanFailureReason.RESULT_MISSING,
            reason(parseScanResult(Activity.RESULT_OK, Intent(), { ScannedDocument(emptyList(), null) })),
        )
    }

    @Test
    fun `결과를 읽다 던지면 RESULT_MISSING 과 cause`() {
        val boom = IllegalStateException("bad parcel")
        val r = parseScanResult(Activity.RESULT_OK, Intent(), { throw boom }) as DocumentScanResult.Failure
        assertEquals(DocScanFailureReason.RESULT_MISSING, r.error.reason)
        assertSame(boom, r.error.cause)
    }

    @Test
    fun `알 수 없는 결과 코드는 UNKNOWN 과 코드`() {
        val r = parseScanResult(Activity.RESULT_FIRST_USER + 3, Intent(), { doc }) as DocumentScanResult.Failure
        assertEquals(DocScanFailureReason.UNKNOWN, r.error.reason)
        assertEquals(Activity.RESULT_FIRST_USER + 3, r.error.statusCode)
    }

    @Test
    fun `Play 서비스 판독기 — 결과 extra 가 없는 인텐트는 RESULT_MISSING`() {
        val r = DocumentScanContract().parseResult(Activity.RESULT_OK, Intent()) as DocumentScanResult.Failure
        assertEquals(DocScanFailureReason.RESULT_MISSING, r.error.reason)
        assertNull(ScanResultReader.Gms.read(Intent()))
    }

    @Test
    fun `계약의 인텐트는 IntentSender 실행 요청이다`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val request = IntentSenderRequest.Builder(intentSender(context)).build()
        val intent = DocumentScanContract().createIntent(context, request)
        assertEquals(ActivityResultContracts.StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST, intent.action)
        val contract = DocumentScanContract(ScanResultReader { doc })
        assertEquals(DocumentScanResult.Success(doc), contract.parseResult(Activity.RESULT_OK, Intent()))
    }

    @Test
    fun `결과는 Parcel 왕복 뒤에도 같다 — 프로세스 종료 복원`() {
        assertEquals(DocumentScanResult.Success(doc), roundTrip(DocumentScanResult.Success(doc)))
        assertSame(DocumentScanResult.Cancelled, roundTrip(DocumentScanResult.Cancelled))
        val noPdf = DocumentScanResult.Success(ScannedDocument(listOf(page1), null))
        assertEquals(noPdf, roundTrip(noPdf))
    }

    @Test
    fun `Failure 는 Parcel 왕복에서 분류·메시지·코드를 지키고 cause 는 버린다`() {
        for (reason in DocScanFailureReason.entries) {
            val original = DocumentScanResult.Failure(DocScanException(reason, "메시지 $reason", RuntimeException("c"), 42))
            val restored = roundTrip(original) as DocumentScanResult.Failure
            assertEquals(original, restored)
            assertEquals(reason, restored.error.reason)
            assertEquals("메시지 $reason", restored.error.message)
            assertEquals(42, restored.error.statusCode)
            assertNull(restored.error.cause)
        }
        val noCode = DocumentScanResult.Failure(DocScanException(DocScanFailureReason.UNKNOWN, ""))
        assertNull((roundTrip(noCode) as DocumentScanResult.Failure).error.statusCode)
    }

    @Test
    fun `값 동등성과 문자열`() {
        assertEquals(ScanPage(page1.uri), page1)
        assertEquals(page1.hashCode(), ScanPage(page1.uri).hashCode())
        assertNotEquals(page1, page2)
        assertNotEquals(page1, "x")
        assertEquals(ScannedPdf(pdf.uri, 2), pdf)
        assertNotEquals(ScannedPdf(pdf.uri, 3), pdf)
        assertNotEquals(pdf, "x")
        assertEquals(ScannedPdf(pdf.uri, 2).hashCode(), pdf.hashCode())
        assertEquals(ScannedDocument(listOf(page1, page2), pdf), doc)
        assertEquals(ScannedDocument(listOf(page1, page2), pdf).hashCode(), doc.hashCode())
        assertEquals(ScannedDocument(listOf(page1), null).hashCode(), ScannedDocument(listOf(page1), null).hashCode())
        assertNotEquals(doc, ScannedDocument(listOf(page1), pdf))
        assertNotEquals(doc, "x")
        assertTrue(doc.toString().contains("pages=2"))
        assertTrue(page1.toString().contains("p1.jpg"))
        assertTrue(pdf.toString().contains("pageCount=2"))
        val s = DocumentScanResult.Success(doc)
        assertEquals(s.hashCode(), DocumentScanResult.Success(doc).hashCode())
        assertNotEquals(s, DocumentScanResult.Cancelled)
        assertTrue(s.toString().startsWith("Success("))
        val f = DocumentScanResult.Failure(DocScanException(DocScanFailureReason.IO_ERROR, "m", statusCode = 1))
        assertEquals(f.hashCode(), DocumentScanResult.Failure(DocScanException(DocScanFailureReason.IO_ERROR, "m", statusCode = 1)).hashCode())
        assertNotEquals(f, DocumentScanResult.Failure(DocScanException(DocScanFailureReason.IO_ERROR, "m", statusCode = 2)))
        assertNotEquals(f, DocumentScanResult.Failure(DocScanException(DocScanFailureReason.IO_ERROR, "n", statusCode = 1)))
        assertNotEquals(f, DocumentScanResult.Failure(DocScanException(DocScanFailureReason.UNKNOWN, "m", statusCode = 1)))
        assertNotEquals(f, s)
        assertTrue(f.toString().contains("IO_ERROR"))
    }

    @Suppress("DEPRECATION")
    private fun roundTrip(value: DocumentScanResult): DocumentScanResult {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(value, 0)
            parcel.setDataPosition(0)
            return parcel.readParcelable<Parcelable>(javaClass.classLoader) as DocumentScanResult
        } finally {
            parcel.recycle()
        }
    }
}
