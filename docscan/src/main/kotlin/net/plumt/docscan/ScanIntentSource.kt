package net.plumt.docscan

import android.app.Activity
import android.content.Context
import androidx.activity.result.IntentSenderRequest
import net.plumt.docscan.internal.AndroidScannerPlatform
import net.plumt.docscan.internal.GmsScanIntentSource

/**
 * 스캐너 화면을 열 [IntentSenderRequest] 를 준비한다. [DocumentScanLauncher] 가 쓴다.
 *
 * 기본 구현([gms])은 메모리·Play 서비스 상태를 확인한 뒤 Play 서비스에 화면 인텐트를 요청한다.
 * 시험에서 가짜 화면을 띄우거나, 준비 전에 앱 정책(약관 동의 등)을 끼우려면 직접 구현한다.
 */
public fun interface ScanIntentSource {
    /**
     * 메인 스레드에서 불린다. [callback] 은 정확히 한 번, 메인 스레드에서 부른다.
     */
    public fun prepare(activity: Activity, options: DocumentScanOptions, callback: ScanIntentCallback)

    public companion object {
        /**
         * Google Play 서비스 문서 스캐너. [context] 는 [prepare] 때 처음 읽으므로 attach 전의 액티비티(필드 초기화)를 넘겨도 된다.
         */
        public fun gms(context: Context): ScanIntentSource = GmsScanIntentSource(AndroidScannerPlatform(context))
    }
}

/** [ScanIntentSource.prepare] 의 결과. 둘 중 하나만 한 번 부른다. */
public interface ScanIntentCallback {
    public fun onReady(request: IntentSenderRequest)

    public fun onError(error: DocScanException)
}
