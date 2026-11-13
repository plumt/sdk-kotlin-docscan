package net.plumt.docscan

/**
 * 이 SDK 의 실패는 모두 이 예외 하나로 알린다. 분기는 [reason] 으로 한다(메시지 문구는 바뀔 수 있다).
 *
 * 스캔 결과에서는 [DocumentScanResult.Failure.error] 로, suspend 함수(PDF·저장·설치)에서는 던져진다.
 * 틀린 인자는 이 예외가 아니라 [IllegalArgumentException] 이다. 코루틴 취소는 감싸지 않고 그대로 전파한다.
 *
 * @property reason 실패 분류
 * @property statusCode 원인이 된 외부 코드 — Play 서비스 `ConnectionResult`·모듈 설치 상태 코드·ML Kit `MlKitException` 코드·
 *   액티비티 결과 코드 중 하나. 외부 코드가 없으면 null. [showPlayServicesResolution] 이 이 값을 쓴다
 */
public class DocScanException(
    public val reason: DocScanFailureReason,
    message: String,
    cause: Throwable? = null,
    public val statusCode: Int? = null,
) : Exception(message, cause) {
    override fun toString(): String =
        "DocScanException(reason=$reason" + (statusCode?.let { ", statusCode=$it" } ?: "") + ", message=$message)"
}

/** [DocScanException] 의 분류. 호출부가 사용자에게 보일 안내를 고르는 기준이다. */
public enum class DocScanFailureReason {
    /** 기기에 Google Play 서비스가 없거나 꺼져 있거나 정품이 아니다. 문서 스캐너를 쓸 수 없다 */
    PLAY_SERVICES_MISSING,

    /** Google Play 서비스가 구버전이거나 업데이트 중이다. [showPlayServicesResolution] 으로 업데이트를 안내할 수 있다 */
    PLAY_SERVICES_UPDATE_REQUIRED,

    /**
     * Play 서비스는 있지만 필요한 API 에 연결하지 못했다(`CommonStatusCodes.API_NOT_CONNECTED`, 17). 구버전 Play 서비스에서 관측됐고
     * 그때는 [showPlayServicesResolution] 으로 업데이트를 안내할 수 있다. 공식 의미에는 다른 원인도 있어 구버전으로 단정하지는 않는다
     */
    PLAY_SERVICES_API_UNAVAILABLE,

    /** 스캐너 모듈(또는 인식 모델)이 아직 기기에 없다. [ModuleAvailability.install] 로 미리 내려받는다 */
    MODULE_DOWNLOAD_REQUIRED,

    /** 모듈 내려받기가 실패했거나 취소됐다. 네트워크를 확인하고 다시 시도한다 */
    MODULE_DOWNLOAD_FAILED,

    /** 저장 공간이 부족하다(모듈 설치·PDF 쓰기·파일 복사) */
    INSUFFICIENT_STORAGE,

    /** 기기가 문서 스캐너를 지원하지 않는다(전체 RAM 1.7GB 미만 등). 다시 시도해도 바뀌지 않는다 */
    UNSUPPORTED_DEVICE,

    /** 네트워크 문제로 모듈을 받지 못했다 */
    NETWORK_ERROR,

    /** 스캐너 화면이 성공으로 끝났지만 결과(페이지·PDF)가 비어 있거나 읽을 수 없다 */
    RESULT_MISSING,

    /** 페이지 이미지를 디코딩하지 못했다(손상·지원하지 않는 형식·빈 파일) */
    IMAGE_DECODE_FAILED,

    /** 파일 읽기·쓰기 실패(없는 파일·권한 만료·provider 오류) */
    IO_ERROR,

    /** PDF 엔진이 페이지를 만들거나 쓰지 못했다 */
    PDF_FAILED,

    /** 텍스트 인식 엔진이 실패했다 */
    TEXT_RECOGNITION_FAILED,

    /** 처리 중 메모리가 부족했다. [net.plumt.docscan.pdf.PdfOptions.imageDpi] 를 낮춘다 */
    OUT_OF_MEMORY,

    /** 위 분류에 들지 않는 실패. [DocScanException.statusCode]·[Throwable.cause] 를 본다 */
    UNKNOWN,
}
