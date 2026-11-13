package net.plumt.docscan.pdf

/**
 * PDF 페이지 크기. 단위는 포인트(1/72 인치).
 *
 * 고정 크기([A4]·[Letter]·[Custom])는 이미지가 가로로 길면 가로 방향 페이지로 돌려 쓰고, 이미지를 여백 안에 비율 유지로 가운데 맞춘다.
 */
public sealed class PageSize {
    /** 210 x 297 mm (595 x 842 pt) */
    public data object A4 : PageSize()

    /** 8.5 x 11 in (612 x 792 pt) */
    public data object Letter : PageSize()

    /** 이미지 비율 그대로. 긴 변을 A4 의 긴 변(842 pt)에 맞춘다 — 인쇄했을 때 A4 와 비슷한 크기다. */
    public data object Original : PageSize()

    /**
     * 임의 크기(세로 기준).
     *
     * @throws IllegalArgumentException 너비·높이가 [MIN_POINTS]..[MAX_POINTS] 밖이거나 유한수가 아닐 때
     */
    public class Custom(public val widthPt: Float, public val heightPt: Float) : PageSize() {
        init {
            require(widthPt.isFinite() && widthPt in MIN_POINTS..MAX_POINTS) { "widthPt 는 $MIN_POINTS..$MAX_POINTS 이어야 한다: $widthPt" }
            require(heightPt.isFinite() && heightPt in MIN_POINTS..MAX_POINTS) { "heightPt 는 $MIN_POINTS..$MAX_POINTS 이어야 한다: $heightPt" }
        }

        override fun equals(other: Any?): Boolean = other is Custom && widthPt == other.widthPt && heightPt == other.heightPt
        override fun hashCode(): Int = 31 * widthPt.hashCode() + heightPt.hashCode()
        override fun toString(): String = "Custom(${widthPt}pt x ${heightPt}pt)"
    }

    public companion object {
        /** 1 인치 */
        public const val MIN_POINTS: Float = 72f

        /** 200 인치 — PDF 1.4 가 허용하는 최대 페이지 크기 */
        public const val MAX_POINTS: Float = 14_400f
    }
}
