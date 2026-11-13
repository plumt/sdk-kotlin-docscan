package net.plumt.docscan.storage

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.OutputStream

/**
 * PDF 를 저장한다. [save] 에 쓰기 블록을 넘기면 저장소를 열어 블록을 실행하고, 블록이 실패하거나 취소되면 쓰다 만 파일을 지운다.
 *
 * ```kotlin
 * val saved = PdfSaver.downloads(context, subdirectory = "Scans").save("영수증 2026-11") { out ->
 *     PdfComposer.create(context).compose(pages, out)
 * }
 * ```
 */
public interface PdfSaver {
    /**
     * @param displayName 파일 이름. `.pdf` 가 없으면 붙인다. 같은 이름이 있으면 `이름 (1).pdf` 처럼 번호가 붙는다 —
     *   API 29+ 는 MediaStore 가 붙이고(기기에 따라 형식이 다를 수 있다), 28 이하는 SDK 가 같은 형식으로 붙인다. 실제 이름은 [SavedPdf.displayName]
     * @throws IllegalArgumentException 빈 이름·120자 초과·`/ \ : * ? " < > |`·제어 문자
     * @throws net.plumt.docscan.DocScanException 저장소 실패([net.plumt.docscan.DocScanFailureReason.IO_ERROR]·
     *   [net.plumt.docscan.DocScanFailureReason.INSUFFICIENT_STORAGE]). [write] 가 던진 예외는 그대로 전달한다
     */
    public suspend fun save(displayName: String, write: suspend (OutputStream) -> Unit): SavedPdf

    public companion object {
        /**
         * 공용 다운로드 폴더.
         *
         * - API 29+: `MediaStore.Downloads` 에 넣는다(범위 저장소). 권한이 필요 없고 파일 앱에 바로 보인다.
         *   다 쓸 때까지 `IS_PENDING` 으로 숨겨 다른 앱이 반쯤 쓴 파일을 보지 않는다
         * - API 28 이하: 공용 다운로드 폴더에 쓰려면 저장소 권한이 필요하다. 이 SDK 는 권한을 추가하지 않으므로
         *   앱 전용 폴더(`getExternalFilesDir(DIRECTORY_DOCUMENTS)`, 없으면 내부 저장소)에 저장하고 [SaveLocation.APP_SPECIFIC] 으로 알린다.
         *   앱을 지우면 함께 지워진다 — 사용자에게 남기려면 [SavedPdf.uri] 를 [PdfSharing] 으로 내보낸다
         *
         * @param subdirectory 하위 폴더(`Scans` 또는 `Scans/2026`). `..`·금지 문자는 IAE
         */
        public fun downloads(
            context: Context,
            subdirectory: String? = null,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): PdfSaver = DownloadsPdfSaver(context.applicationContext, FileNames.subdirectory(subdirectory), dispatcher)
    }
}

/**
 * 저장된 PDF.
 *
 * @property uri 언제나 `content:` 다 — MediaStore 항목(API 29+) 또는 이 SDK 의 FileProvider 항목(API 28 이하).
 *   [PdfSharing.shareIntent] 에 그대로 넘긴다. 파일 경로가 필요하면 [file] 을 쓴다
 * @property file 앱 전용 폴더에 저장했을 때의 파일. MediaStore 면 null
 * @property sizeBytes 쓴 바이트 수
 */
public class SavedPdf(
    public val uri: Uri,
    public val displayName: String,
    public val location: SaveLocation,
    public val sizeBytes: Long,
    public val file: File?,
) {
    override fun toString(): String = "SavedPdf($displayName, $location, ${sizeBytes}B, $uri)"
}

/** 저장 위치. */
public enum class SaveLocation {
    /** 공용 다운로드 폴더(API 29+ MediaStore). 앱을 지워도 남는다 */
    DOWNLOADS,

    /** 앱 전용 폴더(API 28 이하). 앱을 지우면 함께 지워진다 */
    APP_SPECIFIC,
}
