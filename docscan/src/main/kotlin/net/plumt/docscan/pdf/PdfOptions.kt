package net.plumt.docscan.pdf

/**
 * PDF 재생성 설정.
 *
 * @property pageSize 페이지 크기
 * @property marginPt 사방 여백(포인트). 0..[MAX_MARGIN_PT]
 * @property imageDpi 페이지에 넣을 이미지의 최대 해상도. 원본이 더 크면 이 해상도로 줄인다(키우지 않는다). [MIN_DPI]..[MAX_DPI].
 *   PDF 크기·메모리·텍스트 인식 정확도가 이 값에 달렸다 — 150 이면 A4 한 장이 약 1240 x 1754 픽셀이다
 * @property encoder PDF 를 쓰는 방법. 기본은 플랫폼 [PdfEncoder.platform]
 * @property textRecognizer 주면 페이지마다 글자를 인식해 보이지 않는 텍스트 층을 깔아 검색·복사할 수 있는 PDF 를 만든다(docscan-ocr)
 * @throws IllegalArgumentException 범위를 벗어난 값
 */
public class PdfOptions(
    public val pageSize: PageSize = PageSize.A4,
    public val marginPt: Float = 0f,
    public val imageDpi: Int = DEFAULT_DPI,
    public val encoder: PdfEncoder = PdfEncoder.platform(),
    public val textRecognizer: PageTextRecognizer? = null,
) {
    init {
        require(marginPt.isFinite() && marginPt in 0f..MAX_MARGIN_PT) { "marginPt 는 0..$MAX_MARGIN_PT 이어야 한다: $marginPt" }
        require(imageDpi in MIN_DPI..MAX_DPI) { "imageDpi 는 $MIN_DPI..$MAX_DPI 이어야 한다: $imageDpi" }
    }

    override fun toString(): String =
        "PdfOptions(pageSize=$pageSize, marginPt=$marginPt, imageDpi=$imageDpi, encoder=$encoder, textLayer=${textRecognizer != null})"

    public companion object {
        public const val DEFAULT_DPI: Int = 150
        public const val MIN_DPI: Int = 50
        public const val MAX_DPI: Int = 600

        /** 2 인치 — 가장 작은 페이지(1 인치)에서도 이미지 자리가 남도록 페이지별로 다시 줄인다 */
        public const val MAX_MARGIN_PT: Float = 144f
    }
}
