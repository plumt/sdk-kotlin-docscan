package net.plumt.docscan.internal

import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.plumt.docscan.DocScanException
import net.plumt.docscan.DocScanFailureReason
import net.plumt.docscan.DownloadProgress
import net.plumt.docscan.DownloadState
import net.plumt.docscan.ModuleAvailability
import net.plumt.docscan.ScannerAvailability
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal class ModuleAvailabilityImpl(
    private val platform: ScannerPlatform,
    private val checkMemory: Boolean,
    private val api: () -> OptionalModuleApi,
) : ModuleAvailability {

    override suspend fun check(): ScannerAvailability {
        precheck()?.let { return ScannerAvailability.Unavailable(it) }
        return try {
            val response = platform.moduleInstall().areModulesAvailable(api()).await()
            if (response.areModulesAvailable()) ScannerAvailability.Available else ScannerAvailability.DownloadRequired
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ScannerAvailability.Unavailable(FailureClassifier.classify(e, CHECK))
        }
    }

    override suspend fun install(onProgress: (DownloadProgress) -> Unit): ScannerAvailability {
        precheck()?.let { return ScannerAvailability.Unavailable(it) }
        val client = platform.moduleInstall()
        return suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            fun finish(value: ScannerAvailability, listener: InstallStatusListener) {
                if (resumed.compareAndSet(false, true)) {
                    client.unregisterListener(listener)
                    cont.resumeIfActive(value)
                }
            }
            val listener = object : InstallStatusListener {
                override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                    val state = update.installState.toDownloadState()
                    val info = update.progressInfo
                    onProgress(DownloadProgress(state, info?.bytesDownloaded ?: 0, info?.totalBytesToDownload ?: 0))
                    terminal(state, update.errorCode)?.let { finish(it, this) }
                }
            }
            cont.invokeOnCancellation { client.unregisterListener(listener) }
            val request = try {
                ModuleInstallRequest.newBuilder().addApi(api()).setListener(listener, Direct).build()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                finish(ScannerAvailability.Unavailable(FailureClassifier.classify(e, INSTALL)), listener)
                return@suspendCancellableCoroutine
            }
            client.installModules(request).addOnCompleteListener(Direct) { task ->
                val error = if (task.isCanceled) TaskCancelledException() else task.exception
                when {
                    error != null -> finish(ScannerAvailability.Unavailable(FailureClassifier.classify(error, INSTALL)), listener)
                    task.result?.areModulesAlreadyInstalled() == true -> finish(ScannerAvailability.Available, listener)
                    // 설치가 시작됐다 — 끝은 리스너가 알린다
                    else -> Unit
                }
            }
        }
    }

    private fun precheck(): DocScanException? {
        if (checkMemory) FailureClassifier.memoryCheck(platform.totalMemoryBytes())?.let { return it }
        return FailureClassifier.fromStatus(platform.playServicesStatus(), CHECK)
    }

    private fun terminal(state: DownloadState, errorCode: Int): ScannerAvailability? = when (state) {
        DownloadState.COMPLETED -> ScannerAvailability.Available
        DownloadState.FAILED -> ScannerAvailability.Unavailable(
            FailureClassifier.fromStatus(errorCode, INSTALL)
                ?.let { if (it.reason == DocScanFailureReason.UNKNOWN) downloadFailed(errorCode) else it }
                ?: downloadFailed(errorCode),
        )
        DownloadState.CANCELED -> ScannerAvailability.Unavailable(
            DocScanException(DocScanFailureReason.MODULE_DOWNLOAD_FAILED, "$INSTALL: 내려받기가 취소됐다", statusCode = errorCode),
        )
        else -> null
    }

    private fun downloadFailed(code: Int) =
        DocScanException(DocScanFailureReason.MODULE_DOWNLOAD_FAILED, "$INSTALL: 내려받기 실패 (상태 $code)", statusCode = code)

    private fun <T> CancellableContinuation<T>.resumeIfActive(value: T) {
        if (isActive) resume(value)
    }

    private companion object {
        const val CHECK = "모듈 확인"
        const val INSTALL = "모듈 설치"
        val Direct = Executor { it.run() }
    }
}

internal fun Int.toDownloadState(): DownloadState = when (this) {
    InstallState.STATE_PENDING -> DownloadState.PENDING
    InstallState.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
    InstallState.STATE_DOWNLOAD_PAUSED -> DownloadState.PAUSED
    InstallState.STATE_INSTALLING -> DownloadState.INSTALLING
    InstallState.STATE_COMPLETED -> DownloadState.COMPLETED
    InstallState.STATE_FAILED -> DownloadState.FAILED
    InstallState.STATE_CANCELED -> DownloadState.CANCELED
    else -> DownloadState.UNKNOWN
}
