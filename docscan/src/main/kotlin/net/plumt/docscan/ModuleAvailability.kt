package net.plumt.docscan

import android.content.Context
import com.google.android.gms.common.api.OptionalModuleApi
import net.plumt.docscan.internal.AndroidScannerPlatform
import net.plumt.docscan.internal.ModuleAvailabilityImpl

/**
 * Play 서비스 선택 모듈(문서 스캐너·텍스트 인식 모델)이 이 기기에서 쓸 수 있는지 미리 확인하고 내려받는다.
 *
 * 스캐너 모듈은 처음 쓸 때 Play 서비스가 내려받는다. 첫 스캔 전에(예: 문서 메뉴에 들어갈 때) [install] 을 불러 두면
 * 사용자가 버튼을 눌렀을 때 기다리지 않는다. 실패는 던지지 않고 [ScannerAvailability.Unavailable] 로 돌려준다.
 * 코루틴 취소는 그대로 전파하며, 진행 중이던 설치 리스너를 해제한다(설치 자체는 Play 서비스가 이어서 할 수 있다).
 */
public interface ModuleAvailability {
    /** 내려받지 않고 상태만 확인한다. */
    public suspend fun check(): ScannerAvailability

    /**
     * 없으면 내려받는다. 이미 있으면 바로 [ScannerAvailability.Available].
     *
     * @param onProgress 내려받는 동안의 진행 상황. Play 서비스가 알리는 스레드에서 바로 불린다(메인 스레드가 아닐 수 있다). UI 갱신은 상태 흐름 등으로 넘긴다
     */
    public suspend fun install(onProgress: (DownloadProgress) -> Unit = {}): ScannerAvailability

    public companion object {
        /** 문서 스캐너 모듈. 전체 RAM 1.7GB 미만 기기는 [DocScanFailureReason.UNSUPPORTED_DEVICE] 다. */
        public fun documentScanner(context: Context, options: DocumentScanOptions = DocumentScanOptions()): ModuleAvailability {
            val platform = AndroidScannerPlatform(context)
            return ModuleAvailabilityImpl(platform, checkMemory = true) { platform.scanner(options) }
        }

        /**
         * 임의의 Play 서비스 선택 모듈 API(예: ML Kit 텍스트 인식기). 분류·진행 보고는 [documentScanner] 와 같다.
         * docscan-ocr 가 인식 모델 확인에 쓴다 — GMS 타입을 받는 유일한 공개 진입점이다.
         */
        public fun of(context: Context, api: OptionalModuleApi): ModuleAvailability =
            ModuleAvailabilityImpl(AndroidScannerPlatform(context), checkMemory = false) { api }
    }
}

/** 모듈 사용 가능 여부. */
public sealed class ScannerAvailability {
    /** 바로 쓸 수 있다. */
    public data object Available : ScannerAvailability()

    /** 기기는 지원하지만 모듈이 아직 없다. [ModuleAvailability.install] 로 내려받는다. */
    public data object DownloadRequired : ScannerAvailability()

    /** 쓸 수 없다. [error] 의 [DocScanException.reason] 으로 안내를 고른다. */
    public class Unavailable(public val error: DocScanException) : ScannerAvailability() {
        override fun toString(): String = "Unavailable($error)"
    }
}

/**
 * 내려받기 진행 상황.
 *
 * @property bytesDownloaded 받은 바이트. 모르면 0
 * @property totalBytes 전체 바이트. 모르면 0
 */
public class DownloadProgress(
    public val state: DownloadState,
    public val bytesDownloaded: Long,
    public val totalBytes: Long,
) {
    /** 0..1 진행률. 전체 크기를 모르면 null */
    public val fraction: Float? get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else null

    override fun equals(other: Any?): Boolean = other is DownloadProgress && state == other.state &&
        bytesDownloaded == other.bytesDownloaded && totalBytes == other.totalBytes

    override fun hashCode(): Int = listOf(state, bytesDownloaded, totalBytes).hashCode()

    override fun toString(): String = "DownloadProgress($state, $bytesDownloaded/$totalBytes)"
}

/** Play 서비스 모듈 설치 단계. */
public enum class DownloadState { PENDING, DOWNLOADING, PAUSED, INSTALLING, COMPLETED, FAILED, CANCELED, UNKNOWN }
