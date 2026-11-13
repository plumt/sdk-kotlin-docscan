package net.plumt.docscan.internal

import android.system.ErrnoException
import android.system.OsConstants
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.mlkit.common.MlKitException
import net.plumt.docscan.DocScanException
import net.plumt.docscan.DocScanFailureReason
import net.plumt.docscan.DocScanFailureReason.INSUFFICIENT_STORAGE
import net.plumt.docscan.DocScanFailureReason.IO_ERROR
import net.plumt.docscan.DocScanFailureReason.MODULE_DOWNLOAD_FAILED
import net.plumt.docscan.DocScanFailureReason.MODULE_DOWNLOAD_REQUIRED
import net.plumt.docscan.DocScanFailureReason.NETWORK_ERROR
import net.plumt.docscan.DocScanFailureReason.OUT_OF_MEMORY
import net.plumt.docscan.DocScanFailureReason.PLAY_SERVICES_MISSING
import net.plumt.docscan.DocScanFailureReason.PLAY_SERVICES_UPDATE_REQUIRED
import net.plumt.docscan.DocScanFailureReason.UNKNOWN
import net.plumt.docscan.DocScanFailureReason.UNSUPPORTED_DEVICE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class FailureClassifierTest {

    @Test
    fun `ML Kit 오류 코드별 분류와 cause·statusCode 보존`() {
        val cases = mapOf(
            MlKitException.UNSUPPORTED to UNSUPPORTED_DEVICE,
            MlKitException.NOT_ENOUGH_SPACE to INSUFFICIENT_STORAGE,
            MlKitException.UNAVAILABLE to MODULE_DOWNLOAD_REQUIRED,
            MlKitException.NETWORK_ISSUE to NETWORK_ERROR,
            MlKitException.MODEL_HASH_MISMATCH to MODULE_DOWNLOAD_FAILED,
            MlKitException.MODEL_INCOMPATIBLE_WITH_TFLITE to MODULE_DOWNLOAD_FAILED,
            MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD to PLAY_SERVICES_UPDATE_REQUIRED,
            MlKitException.INTERNAL to UNKNOWN,
            MlKitException.CANCELLED to UNKNOWN,
        )
        for ((code, reason) in cases) {
            val src = MlKitException("m$code", code)
            val e = FailureClassifier.classify(src, "ctx")
            assertEquals("code $code", reason, e.reason)
            assertSame(src, e.cause)
            assertEquals(code, e.statusCode)
            assertTrue(e.message!!.startsWith("ctx: "))
        }
    }

    @Test
    fun `Play 서비스 상태 코드별 분류`() {
        val cases = mapOf(
            ConnectionResult.SERVICE_MISSING to PLAY_SERVICES_MISSING,
            ConnectionResult.SERVICE_DISABLED to PLAY_SERVICES_MISSING,
            ConnectionResult.SERVICE_INVALID to PLAY_SERVICES_MISSING,
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED to PLAY_SERVICES_UPDATE_REQUIRED,
            ConnectionResult.SERVICE_UPDATING to PLAY_SERVICES_UPDATE_REQUIRED,
            ConnectionResult.NETWORK_ERROR to NETWORK_ERROR,
            46003 to INSUFFICIENT_STORAGE,
            46000 to MODULE_DOWNLOAD_FAILED,
            46001 to MODULE_DOWNLOAD_FAILED,
            46002 to MODULE_DOWNLOAD_FAILED,
            ConnectionResult.INTERNAL_ERROR to UNKNOWN,
            -1 to UNKNOWN,
            Int.MAX_VALUE to UNKNOWN,
        )
        for ((code, reason) in cases) {
            val e = FailureClassifier.fromStatus(code, "c")!!
            assertEquals("code $code", reason, e.reason)
            assertEquals(code, e.statusCode)
        }
        assertNull(FailureClassifier.fromStatus(ConnectionResult.SUCCESS, "c"))
    }

    @Test
    fun `ApiException 은 상태 코드로, 성공 코드면 UNKNOWN`() {
        val api = ApiException(Status(ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED))
        val e = FailureClassifier.classify(api, "c")
        assertEquals(PLAY_SERVICES_UPDATE_REQUIRED, e.reason)
        assertSame(api, e.cause)
        val ok = FailureClassifier.classify(ApiException(Status(0)), "c")
        assertEquals(UNKNOWN, ok.reason)
        assertEquals(0, ok.statusCode)
    }

    @Test
    fun `ApiException 17 은 API 연결 실패 — cause·statusCode 보존`() {
        val api = ApiException(Status(CommonStatusCodes.API_NOT_CONNECTED))
        val e = FailureClassifier.classify(api, "ctx")
        assertEquals(DocScanFailureReason.PLAY_SERVICES_API_UNAVAILABLE, e.reason)
        assertSame(api, e.cause)
        assertEquals(17, e.statusCode)
        assertTrue(e.message!!.startsWith("ctx: "))
    }

    @Test
    fun `ApiException 16 은 취소라 모듈 받기 필요로 분류하지 않는다 — cause·statusCode 보존`() {
        val api = ApiException(Status(CommonStatusCodes.CANCELED))
        val e = FailureClassifier.classify(api, "c")
        assertEquals(UNKNOWN, e.reason)
        assertSame(api, e.cause)
        assertEquals(16, e.statusCode)
    }

    @Test
    fun `ConnectionResult 17 은 다른 뜻이라 API 연결 실패로 분류하지 않는다`() {
        assertEquals(UNKNOWN, FailureClassifier.fromStatus(ConnectionResult.SIGN_IN_FAILED, "c")!!.reason)
    }

    @Test
    fun `이미 분류된 예외는 그대로`() {
        val e = DocScanException(NETWORK_ERROR, "x")
        assertSame(e, FailureClassifier.classify(e, "c"))
    }

    @Test
    fun `IO·권한·메모리·취소·기타`() {
        assertEquals(IO_ERROR, FailureClassifier.classify(FileNotFoundException("x"), "c").reason)
        assertEquals(IO_ERROR, FailureClassifier.classify(SecurityException("x"), "c").reason)
        val oom = OutOfMemoryError("x")
        val e = FailureClassifier.classify(oom, "c")
        assertEquals(OUT_OF_MEMORY, e.reason)
        assertSame(oom, e.cause)
        assertEquals(UNKNOWN, FailureClassifier.classify(TaskCancelledException(), "c").reason)
        val other = IllegalStateException("boom")
        val u = FailureClassifier.classify(other, "c")
        assertEquals(UNKNOWN, u.reason)
        assertSame(other, u.cause)
        assertTrue(u.message!!.contains("IllegalStateException"))
    }

    @Test
    fun `원인 사슬의 ENOSPC 는 저장 공간 부족`() {
        val errno = ErrnoException("write", OsConstants.ENOSPC)
        assertEquals(INSUFFICIENT_STORAGE, FailureClassifier.fromIo(IOException("w", errno), "c").reason)
        assertEquals(INSUFFICIENT_STORAGE, FailureClassifier.fromIo(IOException("write failed: ENOSPC"), "c").reason)
        assertEquals(INSUFFICIENT_STORAGE, FailureClassifier.fromIo(IOException("x", IOException("No space left on device")), "c").reason)
        assertEquals(IO_ERROR, FailureClassifier.fromIo(IOException("w", ErrnoException("write", OsConstants.EIO)), "c").reason)
        // 순환·깊은 사슬에서도 끝난다
        var deep: Throwable = IOException("0")
        repeat(20) { deep = IOException("$it", deep) }
        assertEquals(IO_ERROR, FailureClassifier.fromIo(deep, "c").reason)
        // ENOSPC 가 확인 깊이(8)보다 깊으면 보지 않는다
        var tooDeep: Throwable = IOException("ENOSPC")
        repeat(10) { tooDeep = IOException("l$it", tooDeep) }
        assertEquals(IO_ERROR, FailureClassifier.fromIo(tooDeep, "c").reason)
    }

    @Test
    fun `메모리 하한 경계`() {
        val min = FailureClassifier.MIN_TOTAL_MEMORY_BYTES
        assertEquals(UNSUPPORTED_DEVICE, FailureClassifier.memoryCheck(min - 1)!!.reason)
        assertEquals(UNSUPPORTED_DEVICE, FailureClassifier.memoryCheck(1)!!.reason)
        assertNull(FailureClassifier.memoryCheck(min))
        assertNull(FailureClassifier.memoryCheck(Long.MAX_VALUE))
        // 모르면(0) 막지 않는다
        assertNull(FailureClassifier.memoryCheck(0))
        assertNull(FailureClassifier.memoryCheck(-5))
    }

    @Test
    fun `예외 문자열에 분류와 코드가 보인다`() {
        assertEquals(
            "DocScanException(reason=NETWORK_ERROR, statusCode=7, message=m)",
            DocScanException(NETWORK_ERROR, "m", statusCode = 7).toString(),
        )
        assertEquals("DocScanException(reason=UNKNOWN, message=m)", DocScanException(DocScanFailureReason.UNKNOWN, "m").toString())
    }
}
