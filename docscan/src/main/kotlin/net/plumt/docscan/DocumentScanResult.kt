package net.plumt.docscan

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler

/**
 * 스캐너 화면 한 번의 결과. [Parcelable] 이라 `rememberSaveable`·`SavedStateHandle`·`onSaveInstanceState` 에 그대로 담아
 * 회전·프로세스 종료 뒤에도 유지할 수 있다.
 */
public sealed class DocumentScanResult : Parcelable {
    /** 스캔이 끝나 페이지 이미지 또는 PDF 가 하나 이상 있다. */
    @Parcelize
    public class Success(public val document: ScannedDocument) : DocumentScanResult() {
        override fun equals(other: Any?): Boolean = other is Success && document == other.document
        override fun hashCode(): Int = document.hashCode()
        override fun toString(): String = "Success($document)"
    }

    /** 사용자가 스캐너 화면을 닫았다(뒤로 가기·취소). 실패가 아니다. */
    @Parcelize
    public data object Cancelled : DocumentScanResult()

    /**
     * 스캐너를 열지 못했거나 결과를 읽지 못했다.
     *
     * 저장(Parcel)하면 [DocScanException.reason]·메시지·[DocScanException.statusCode] 만 남고 `cause` 는 사라진다 —
     * 외부 예외는 직렬화할 수 없는 필드를 가질 수 있기 때문이다.
     */
    @Parcelize
    @TypeParceler<DocScanException, DocScanExceptionParceler>
    public class Failure(public val error: DocScanException) : DocumentScanResult() {
        override fun equals(other: Any?): Boolean = other is Failure && error.reason == other.error.reason &&
            error.message == other.error.message && error.statusCode == other.error.statusCode
        override fun hashCode(): Int = listOf(error.reason, error.message, error.statusCode).hashCode()
        override fun toString(): String = "Failure($error)"
    }
}

/**
 * 스캐너가 돌려준 문서.
 *
 * [pages] 와 [pdf] 의 URI 는 앱 캐시 안 파일이다. 시스템이 저장 공간이 부족하면 캐시를 지울 수 있으므로
 * 오래 둘 페이지는 [net.plumt.docscan.storage.ScanPageStore.keep] 으로 앱 저장소에 옮긴다.
 *
 * @property pages 페이지 이미지(JPEG). [DocumentScanOptions.outputs] 에 JPEG 가 없으면 비어 있다
 * @property pdf 스캐너가 만든 PDF. [DocumentScanOptions.outputs] 에 PDF 가 없으면 null
 */
@Parcelize
public class ScannedDocument(
    public val pages: List<ScanPage>,
    public val pdf: ScannedPdf?,
) : Parcelable {
    override fun equals(other: Any?): Boolean = other is ScannedDocument && pages == other.pages && pdf == other.pdf
    override fun hashCode(): Int = 31 * pages.hashCode() + (pdf?.hashCode() ?: 0)
    override fun toString(): String = "ScannedDocument(pages=${pages.size}, pdf=$pdf)"
}

/** 페이지 이미지 하나. */
@Parcelize
public class ScanPage(public val uri: Uri) : Parcelable {
    override fun equals(other: Any?): Boolean = other is ScanPage && uri == other.uri
    override fun hashCode(): Int = uri.hashCode()
    override fun toString(): String = "ScanPage($uri)"
}

/** 스캐너가 만든 PDF. */
@Parcelize
public class ScannedPdf(public val uri: Uri, public val pageCount: Int) : Parcelable {
    override fun equals(other: Any?): Boolean = other is ScannedPdf && uri == other.uri && pageCount == other.pageCount
    override fun hashCode(): Int = 31 * uri.hashCode() + pageCount
    override fun toString(): String = "ScannedPdf($uri, pageCount=$pageCount)"
}

/** [DocScanException] 을 cause 없이 저장한다. 분류는 순서값으로 쓴다(같은 앱 빌드 안에서만 읽힌다). */
internal object DocScanExceptionParceler : Parceler<DocScanException> {
    override fun create(parcel: Parcel): DocScanException {
        val reason = DocScanFailureReason.entries[parcel.readInt()]
        val message = parcel.readString().orEmpty()
        val status = if (parcel.readInt() == 1) parcel.readInt() else null
        return DocScanException(reason, message, statusCode = status)
    }

    override fun DocScanException.write(parcel: Parcel, flags: Int) {
        parcel.writeInt(reason.ordinal)
        parcel.writeString(message)
        val status = statusCode
        if (status == null) {
            parcel.writeInt(0)
        } else {
            parcel.writeInt(1)
            parcel.writeInt(status)
        }
    }
}
