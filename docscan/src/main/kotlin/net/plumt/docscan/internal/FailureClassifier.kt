package net.plumt.docscan.internal

import android.system.ErrnoException
import android.system.OsConstants
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes
import com.google.mlkit.common.MlKitException
import net.plumt.docscan.DocScanException
import net.plumt.docscan.DocScanFailureReason
import java.io.IOException

/** 외부 엔진의 예외·상태 코드를 [DocScanException] 으로 분류한다. 순수 함수 — 원인은 cause 에 보존한다. */
internal object FailureClassifier {

    /** 문서 스캐너가 요구하는 최소 전체 RAM. 판단이 갈리는 기기를 막지 않도록 10진 1.7GB(더 낮은 쪽)로 둔다 */
    const val MIN_TOTAL_MEMORY_BYTES: Long = 1_700_000_000L

    fun classify(error: Throwable, context: String): DocScanException = when (error) {
        is DocScanException -> error
        is MlKitException -> fromMlKit(error, context)
        is ApiException -> fromApiNotConnected(error, context) ?: fromStatus(error.statusCode, context, error)
            ?: DocScanException(DocScanFailureReason.UNKNOWN, "$context: 성공 코드의 ApiException", error, error.statusCode)
        is TaskCancelledException -> DocScanException(DocScanFailureReason.UNKNOWN, "$context: Play 서비스 작업이 취소됐다", error)
        is OutOfMemoryError -> DocScanException(DocScanFailureReason.OUT_OF_MEMORY, "$context: 메모리 부족", error)
        is IOException, is SecurityException -> fromIo(error, context)
        else -> DocScanException(DocScanFailureReason.UNKNOWN, "$context: ${error.javaClass.simpleName}: ${error.message}", error)
    }

    fun fromMlKit(error: MlKitException, context: String): DocScanException {
        val reason = when (error.errorCode) {
            MlKitException.UNSUPPORTED -> DocScanFailureReason.UNSUPPORTED_DEVICE
            MlKitException.NOT_ENOUGH_SPACE -> DocScanFailureReason.INSUFFICIENT_STORAGE
            MlKitException.UNAVAILABLE -> DocScanFailureReason.MODULE_DOWNLOAD_REQUIRED
            MlKitException.NETWORK_ISSUE -> DocScanFailureReason.NETWORK_ERROR
            MlKitException.MODEL_HASH_MISMATCH, MlKitException.MODEL_INCOMPATIBLE_WITH_TFLITE ->
                DocScanFailureReason.MODULE_DOWNLOAD_FAILED
            MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD -> DocScanFailureReason.PLAY_SERVICES_UPDATE_REQUIRED
            else -> DocScanFailureReason.UNKNOWN
        }
        return DocScanException(reason, "$context: ML Kit 오류 ${error.errorCode}: ${error.message}", error, error.errorCode)
    }

    /**
     * `ApiException` 의 코드는 `CommonStatusCodes` 다. 17 은 거기서 `API_NOT_CONNECTED` 이고 `ConnectionResult` 에서는 다른 뜻(`SIGN_IN_FAILED`)이라
     * [fromStatus] 가 아니라 여기서 가른다. 사전 확인을 통과한 Play 서비스 20~21 에서 모듈 확인·설치가 이 코드로 끝났다.
     */
    private fun fromApiNotConnected(error: ApiException, context: String): DocScanException? =
        if (error.statusCode == CommonStatusCodes.API_NOT_CONNECTED) {
            DocScanException(
                DocScanFailureReason.PLAY_SERVICES_API_UNAVAILABLE,
                "$context: Play 서비스가 API 에 연결하지 못했다(상태 ${error.statusCode})",
                error,
                error.statusCode,
            )
        } else {
            null
        }

    /**
     * Play 서비스 `ConnectionResult`·모듈 설치 상태 코드. 성공(0)이면 null.
     *
     * 16 은 `ConnectionResult.API_UNAVAILABLE` 과 `CommonStatusCodes.CANCELED` 가 같은 값이다. 이 함수에 16 이 오는 길은
     * `ApiException`·설치 리스너뿐이고 둘 다 `CommonStatusCodes` 계열이라 취소로 읽어 어느 분류에도 넣지 않는다.
     */
    fun fromStatus(code: Int, context: String, cause: Throwable? = null): DocScanException? {
        val reason = when (code) {
            ConnectionResult.SUCCESS -> return null
            ConnectionResult.SERVICE_MISSING, ConnectionResult.SERVICE_DISABLED, ConnectionResult.SERVICE_INVALID ->
                DocScanFailureReason.PLAY_SERVICES_MISSING
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, ConnectionResult.SERVICE_UPDATING ->
                DocScanFailureReason.PLAY_SERVICES_UPDATE_REQUIRED
            ConnectionResult.NETWORK_ERROR -> DocScanFailureReason.NETWORK_ERROR
            ModuleInstallStatusCodes.INSUFFICIENT_STORAGE -> DocScanFailureReason.INSUFFICIENT_STORAGE
            ModuleInstallStatusCodes.UNKNOWN_MODULE, ModuleInstallStatusCodes.NOT_ALLOWED_MODULE,
            ModuleInstallStatusCodes.MODULE_NOT_FOUND -> DocScanFailureReason.MODULE_DOWNLOAD_FAILED
            else -> DocScanFailureReason.UNKNOWN
        }
        return DocScanException(reason, "$context: Play 서비스 상태 $code", cause, code)
    }

    fun fromIo(error: Throwable, context: String): DocScanException {
        val reason = if (isNoSpace(error)) DocScanFailureReason.INSUFFICIENT_STORAGE else DocScanFailureReason.IO_ERROR
        return DocScanException(reason, "$context: ${error.javaClass.simpleName}: ${error.message}", error)
    }

    fun memoryCheck(totalMemoryBytes: Long): DocScanException? =
        if (totalMemoryBytes in 1 until MIN_TOTAL_MEMORY_BYTES) {
            DocScanException(
                DocScanFailureReason.UNSUPPORTED_DEVICE,
                "문서 스캐너는 전체 RAM 1.7GB 이상 기기만 지원한다: ${totalMemoryBytes}B",
            )
        } else {
            null
        }

    /** 원인 사슬 어디든 ENOSPC 가 있으면 저장 공간 부족이다. */
    private fun isNoSpace(error: Throwable): Boolean {
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < MAX_CAUSE_DEPTH) {
            if (e is ErrnoException && e.errno == OsConstants.ENOSPC) return true
            if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space left") == true) return true
            e = e.cause
            depth++
        }
        return false
    }

    private const val MAX_CAUSE_DEPTH = 8
}
