package net.plumt.docscan.internal

import android.app.Activity
import androidx.activity.result.IntentSenderRequest
import net.plumt.docscan.DocumentScanOptions
import net.plumt.docscan.ScanIntentCallback
import net.plumt.docscan.ScanIntentSource

/** Play 서비스 스캐너 화면 인텐트를 준비한다. 메모리 → Play 서비스 상태 → 화면 인텐트 순으로 확인한다. */
internal class GmsScanIntentSource(private val platform: ScannerPlatform) : ScanIntentSource {

    override fun prepare(activity: Activity, options: DocumentScanOptions, callback: ScanIntentCallback) {
        FailureClassifier.memoryCheck(platform.totalMemoryBytes())?.let { return callback.onError(it) }
        FailureClassifier.fromStatus(platform.playServicesStatus(), CONTEXT)?.let { return callback.onError(it) }
        val task = try {
            platform.scanner(options).getStartScanIntent(activity)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return callback.onError(FailureClassifier.classify(e, CONTEXT))
        }
        task.addOnCompleteListener { done ->
            val error = when {
                done.isCanceled -> TaskCancelledException()
                else -> done.exception
            }
            if (error != null) {
                callback.onError(FailureClassifier.classify(error, CONTEXT))
            } else {
                callback.onReady(IntentSenderRequest.Builder(done.result).build())
            }
        }
    }

    private companion object {
        const val CONTEXT = "스캐너 화면 준비"
    }
}
