package net.plumt.docscan

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.Api
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.api.internal.ApiKey
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleAvailabilityResponse
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallIntentResponse
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallResponse
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import net.plumt.docscan.internal.ScannerPlatform
import java.io.ByteArrayOutputStream
import java.io.File

internal class FakeOptionalApi : OptionalModuleApi {
    override fun getOptionalFeatures(): Array<Feature> = arrayOf(Feature("fake_module", 1L))
}

internal class FakeScanner(var next: () -> Task<IntentSender>) : GmsDocumentScanner {
    var calls = 0
    override fun getStartScanIntent(activity: Activity): Task<IntentSender> {
        calls++
        return next()
    }

    override fun getOptionalFeatures(): Array<Feature> = arrayOf(Feature("doc_scanner", 1L))
}

internal class FakeModuleInstall : ModuleInstallClient {
    var availability: () -> Task<ModuleAvailabilityResponse> = { Tasks.forResult(ModuleAvailabilityResponse(true, 0)) }
    var install: (ModuleInstallRequest) -> Task<ModuleInstallResponse> = { Tasks.forResult(ModuleInstallResponse(0, false)) } // 세션 0 = 이미 설치됨
    val listeners = mutableListOf<InstallStatusListener>()
    val unregistered = mutableListOf<InstallStatusListener>()
    var lastRequest: ModuleInstallRequest? = null

    override fun areModulesAvailable(vararg apis: OptionalModuleApi): Task<ModuleAvailabilityResponse> = availability()

    override fun installModules(request: ModuleInstallRequest): Task<ModuleInstallResponse> {
        lastRequest = request
        request.listener?.let { listeners += it }
        return install(request)
    }

    override fun unregisterListener(listener: InstallStatusListener): Task<Boolean> {
        unregistered += listener
        return Tasks.forResult(true)
    }

    override fun deferredInstall(vararg apis: OptionalModuleApi): Task<Void> = throw UnsupportedOperationException()
    override fun getInstallModulesIntent(vararg apis: OptionalModuleApi): Task<ModuleInstallIntentResponse> = throw UnsupportedOperationException()
    override fun releaseModules(vararg apis: OptionalModuleApi): Task<Void> = throw UnsupportedOperationException()
    override fun getApiKey(): ApiKey<Api.ApiOptions.NoOptions> = throw UnsupportedOperationException()
}

internal class FakePlatform(
    var memory: Long = 4_000_000_000L,
    var status: Int = 0,
    val scanner: FakeScanner = FakeScanner { Tasks.forResult(null) },
    val install: FakeModuleInstall = FakeModuleInstall(),
) : ScannerPlatform {
    val requestedOptions = mutableListOf<DocumentScanOptions>()
    override fun totalMemoryBytes(): Long = memory
    override fun playServicesStatus(): Int = status
    override fun scanner(options: DocumentScanOptions): GmsDocumentScanner {
        requestedOptions += options
        return scanner
    }

    override fun moduleInstall(): ModuleInstallClient = install
}

internal class RecordingLauncher : ActivityResultLauncher<IntentSenderRequest>() {
    val launched = mutableListOf<IntentSenderRequest>()
    var failure: RuntimeException? = null
    override fun launch(input: IntentSenderRequest, options: ActivityOptionsCompat?) {
        failure?.let { throw it }
        launched += input
    }

    override fun unregister() = Unit
    override val contract: ActivityResultContract<IntentSenderRequest, *> get() = DocumentScanContract()
}

internal fun intentSender(context: Context): IntentSender =
    PendingIntent.getActivity(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE).intentSender

internal fun <T> pending(): TaskCompletionSource<T> = TaskCompletionSource()

internal object TestImages {
    fun jpeg(width: Int, height: Int, color: Int = 0xFF3366CC.toInt()): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
            bitmap.recycle()
            it.toByteArray()
        }
    }

    fun file(dir: File, name: String, width: Int, height: Int): Uri {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        f.writeBytes(jpeg(width, height))
        return Uri.fromFile(f)
    }
}
