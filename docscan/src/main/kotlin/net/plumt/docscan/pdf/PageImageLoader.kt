package net.plumt.docscan.pdf

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import net.plumt.docscan.DocScanException
import net.plumt.docscan.DocScanFailureReason
import java.io.IOException
import java.io.InputStream

/** 페이지 이미지를 읽는다. */
internal interface PageImageLoader {
    /** 이미지 크기(픽셀). 읽을 수 없으면 [DocScanException]. */
    fun bounds(uri: Uri): Pair<Int, Int>

    /** [width] x [height] 로 줄여 디코딩한다. */
    fun load(uri: Uri, width: Int, height: Int): Bitmap
}

/** `ContentResolver` 로 `content://`·`file://` 를 연다. 크기만 먼저 읽고 축소 디코딩한다. */
internal class ResolverPageImageLoader(private val resolver: ContentResolver) : PageImageLoader {

    override fun bounds(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw DocScanException(DocScanFailureReason.IMAGE_DECODE_FAILED, "이미지 크기를 읽지 못했다: $uri")
        }
        return options.outWidth to options.outHeight
    }

    override fun load(uri: Uri, width: Int, height: Int): Bitmap {
        val (w, h) = bounds(uri)
        var sample = 1
        while (w / (sample * 2) >= width && h / (sample * 2) >= height) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw DocScanException(DocScanFailureReason.IMAGE_DECODE_FAILED, "이미지를 디코딩하지 못했다: $uri")
        if (decoded.width == width && decoded.height == height) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun open(uri: Uri): InputStream = try {
        resolver.openInputStream(uri) ?: throw IOException("provider 가 스트림을 주지 않았다")
    } catch (e: IOException) {
        throw DocScanException(DocScanFailureReason.IO_ERROR, "페이지 이미지를 열지 못했다: $uri", e)
    } catch (e: SecurityException) {
        throw DocScanException(DocScanFailureReason.IO_ERROR, "페이지 이미지 읽기 권한이 없다: $uri", e)
    }
}
