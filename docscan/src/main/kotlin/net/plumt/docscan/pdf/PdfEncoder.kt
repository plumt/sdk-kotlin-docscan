package net.plumt.docscan.pdf

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * 배치가 끝난 페이지를 PDF 바이트로 쓴다. [PdfComposer] 가 페이지마다 [Session.addPage] 를 부르고 끝에 [Session.finish] 를 부른다.
 *
 * - [platform]: `android.graphics.pdf.PdfDocument`. 이미지를 무손실로 담아 파일이 크다. 텍스트 층은 이미지 아래에 깐다
 * - [compact]: 이 SDK 의 PDF 1.4 작성기. 이미지를 JPEG 그대로 담아 파일이 더 작고(입력에 따라 차이가 다르다 — README), 텍스트 층은 PDF 표준의 보이지 않는 글자(렌더링 모드 3)다
 */
public interface PdfEncoder {
    /** 블로킹 IO 다. [PdfComposer] 는 IO 디스패처에서 부른다. */
    public fun open(output: OutputStream): Session

    public interface Session {
        public fun addPage(page: PdfPage)

        /** 문서를 끝까지 쓴다. 스트림은 닫지 않는다. */
        public fun finish()

        /** 쓰다 실패했거나 취소됐을 때 자원을 푼다. [finish] 뒤에 불러도 된다. */
        public fun abort()
    }

    public companion object {
        public fun platform(): PdfEncoder = PlatformPdfEncoder

        /** @param jpegQuality 1..100 */
        public fun compact(jpegQuality: Int = CompactPdfEncoder.DEFAULT_QUALITY): PdfEncoder = CompactPdfEncoder(jpegQuality)
    }
}

/**
 * 페이지 하나. 좌표는 포인트, 왼쪽 위 원점.
 *
 * @property image 이미지. [PdfEncoder.Session.addPage] 가 끝나면 SDK 가 recycle 한다
 * @property texts 보이지 않게 깔 글자(텍스트 층). 없으면 빈 목록
 */
public class PdfPage(
    public val widthPt: Float,
    public val heightPt: Float,
    public val image: Bitmap,
    public val imageLeft: Float,
    public val imageTop: Float,
    public val imageWidth: Float,
    public val imageHeight: Float,
    public val texts: List<PdfText>,
)

/** 텍스트 층의 글자 한 줄. 좌표는 포인트, 왼쪽 위 원점. */
public class PdfText(
    public val text: String,
    public val left: Float,
    public val top: Float,
    public val width: Float,
    public val height: Float,
) {
    override fun equals(other: Any?): Boolean = other is PdfText && text == other.text && left == other.left &&
        top == other.top && width == other.width && height == other.height

    override fun hashCode(): Int = listOf(text, left, top, width, height).hashCode()
    override fun toString(): String = "PdfText(\"$text\", $left, $top, $width x $height)"
}
