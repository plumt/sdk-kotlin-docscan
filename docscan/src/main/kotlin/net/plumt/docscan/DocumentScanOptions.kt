package net.plumt.docscan

/**
 * 스캐너 화면 설정. 화면 자체(촬영·갤러리 가져오기·모서리 감지·자르기·필터)는 Google Play 서비스가 그린다.
 *
 * @property allowGalleryImport true 면 스캐너 화면에 갤러리 버튼이 생겨 이미 찍은 사진을 불러올 수 있다.
 *   불러온 사진에도 모서리 감지·자르기가 그대로 적용된다. 스캐너를 갤러리 화면부터 여는 방법은 Play 서비스가 제공하지 않는다
 * @property pageLimit 한 번에 찍을 수 있는 최대 페이지 수. null 이면 제한하지 않는다(Play 서비스 기본값)
 * @property mode 화면에서 쓸 수 있는 편집 기능 범위
 * @property outputs 스캐너가 만들 결과. [ScanOutput.JPEG] 를 빼면 페이지 이미지가 없어 [PageList] 합치기·PDF 재생성을 할 수 없다
 * @throws IllegalArgumentException [pageLimit] 이 1 미만이거나 [outputs] 가 비었을 때
 */
public class DocumentScanOptions(
    public val allowGalleryImport: Boolean = true,
    public val pageLimit: Int? = null,
    public val mode: ScannerMode = ScannerMode.FULL,
    public val outputs: Set<ScanOutput> = setOf(ScanOutput.JPEG, ScanOutput.PDF),
) {
    init {
        require(pageLimit == null || pageLimit >= 1) { "pageLimit 은 null 이거나 1 이상이어야 한다: $pageLimit" }
        require(outputs.isNotEmpty()) { "outputs 는 비어 있을 수 없다 — JPEG·PDF 중 하나 이상" }
    }

    override fun equals(other: Any?): Boolean = other is DocumentScanOptions &&
        allowGalleryImport == other.allowGalleryImport && pageLimit == other.pageLimit &&
        mode == other.mode && outputs == other.outputs

    override fun hashCode(): Int = listOf(allowGalleryImport, pageLimit, mode, outputs).hashCode()

    override fun toString(): String =
        "DocumentScanOptions(allowGalleryImport=$allowGalleryImport, pageLimit=$pageLimit, mode=$mode, outputs=$outputs)"
}

/** 스캐너 화면의 편집 기능 범위. */
public enum class ScannerMode {
    /** 자르기·회전·순서 바꾸기 같은 기본 편집만 */
    BASE,

    /** 기본 편집 + 흑백·자동 보정 같은 필터 */
    BASE_WITH_FILTER,

    /** 필터 + 얼룩·손가락 지우기 같은 ML 정리(필요하면 Play 서비스가 추가 모델을 내려받는다) */
    FULL,
}

/** 스캐너가 만들 결과 형식. */
public enum class ScanOutput {
    /** 페이지마다 JPEG 이미지 */
    JPEG,

    /** 모든 페이지를 담은 PDF 하나 */
    PDF,
}
