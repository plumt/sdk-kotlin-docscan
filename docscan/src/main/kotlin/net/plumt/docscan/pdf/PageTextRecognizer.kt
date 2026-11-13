package net.plumt.docscan.pdf

import android.graphics.Bitmap

/**
 * 페이지 이미지에서 글자 줄을 찾는다. 검색 가능한 PDF 의 텍스트 층에 쓴다.
 *
 * 기본 구현은 docscan-ocr 의 `KoreanTextRecognizer`(ML Kit, 한국어·라틴 문자)다. 다른 엔진을 쓰려면 직접 구현한다.
 * 실패는 [net.plumt.docscan.DocScanException] 으로 던지는 것을 권한다 — 다른 예외는
 * [net.plumt.docscan.DocScanFailureReason.TEXT_RECOGNITION_FAILED] 로 감싸진다.
 */
public fun interface PageTextRecognizer {
    /**
     * @param image 페이지 이미지. 호출이 끝나면 SDK 가 recycle 하므로 보관하지 않는다
     * @return 줄 목록. 좌표는 [image] 의 픽셀 기준
     */
    public suspend fun recognize(image: Bitmap): List<TextLine>
}

/**
 * 인식한 글자 한 줄과 그 영역(픽셀, 왼쪽 위 원점).
 *
 * @throws IllegalArgumentException 좌표가 유한수가 아니거나 오른쪽·아래가 왼쪽·위보다 작을 때
 */
public class TextLine(
    public val text: String,
    public val left: Float,
    public val top: Float,
    public val right: Float,
    public val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "좌표는 유한수여야 한다: ($left, $top, $right, $bottom)"
        }
        require(right >= left && bottom >= top) { "right >= left, bottom >= top 이어야 한다: ($left, $top, $right, $bottom)" }
    }

    override fun equals(other: Any?): Boolean = other is TextLine && text == other.text && left == other.left &&
        top == other.top && right == other.right && bottom == other.bottom

    override fun hashCode(): Int = listOf(text, left, top, right, bottom).hashCode()
    override fun toString(): String = "TextLine(\"$text\", $left, $top, $right, $bottom)"
}
