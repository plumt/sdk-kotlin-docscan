package net.plumt.docscan

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import android.os.Bundle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import net.plumt.docscan.internal.GmsScanIntentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 29, 34])
class ScanLaunchTest {
    private val activity: ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private class Capture : ScanIntentCallback {
        val ready = mutableListOf<IntentSenderRequest>()
        val errors = mutableListOf<DocScanException>()
        override fun onReady(request: IntentSenderRequest) { ready += request }
        override fun onError(error: DocScanException) { errors += error }
    }

    // --- GmsScanIntentSource ---

    @Test
    fun `메모리가 부족하면 Play 서비스를 부르지 않고 UNSUPPORTED_DEVICE`() {
        val platform = FakePlatform(memory = 1_000_000_000L)
        val c = Capture()
        GmsScanIntentSource(platform).prepare(activity, DocumentScanOptions(), c)
        assertEquals(DocScanFailureReason.UNSUPPORTED_DEVICE, c.errors.single().reason)
        assertEquals(0, platform.scanner.calls)
    }

    @Test
    fun `Play 서비스 상태가 나쁘면 그 분류`() {
        for ((status, reason) in listOf(
            ConnectionResult.SERVICE_MISSING to DocScanFailureReason.PLAY_SERVICES_MISSING,
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED to DocScanFailureReason.PLAY_SERVICES_UPDATE_REQUIRED,
        )) {
            val platform = FakePlatform(status = status)
            val c = Capture()
            GmsScanIntentSource(platform).prepare(activity, DocumentScanOptions(), c)
            assertEquals(reason, c.errors.single().reason)
            assertEquals(status, c.errors.single().statusCode)
            assertEquals(0, platform.scanner.calls)
        }
    }

    @Test
    fun `화면 인텐트가 오면 onReady, 설정이 그대로 전달된다`() {
        val sender = intentSender(activity)
        val platform = FakePlatform(scanner = FakeScanner { Tasks.forResult(sender) })
        val c = Capture()
        val options = DocumentScanOptions(pageLimit = 5)
        GmsScanIntentSource(platform).prepare(activity, options, c)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertSame(sender, c.ready.single().intentSender)
        assertEquals(listOf(options), platform.requestedOptions)
        assertTrue(c.errors.isEmpty())
    }

    @Test
    fun `나중에 실패하는 Task — ML Kit 코드 분류와 cause`() {
        val pending = TaskCompletionSource<android.content.IntentSender>()
        val platform = FakePlatform(scanner = FakeScanner { pending.task })
        val c = Capture()
        GmsScanIntentSource(platform).prepare(activity, DocumentScanOptions(), c)
        assertTrue(c.errors.isEmpty() && c.ready.isEmpty())
        val src = MlKitException("low ram", MlKitException.UNSUPPORTED)
        pending.setException(src)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(DocScanFailureReason.UNSUPPORTED_DEVICE, c.errors.single().reason)
        assertSame(src, c.errors.single().cause)
    }

    @Test
    fun `취소된 Task·동기 예외도 실패로 알린다`() {
        val tokens = CancellationTokenSource()
        val canceled = TaskCompletionSource<android.content.IntentSender>(tokens.token)
        tokens.cancel()
        val c1 = Capture()
        GmsScanIntentSource(FakePlatform(scanner = FakeScanner { canceled.task })).prepare(activity, DocumentScanOptions(), c1)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(DocScanFailureReason.UNKNOWN, c1.errors.single().reason)

        val c2 = Capture()
        val boom = IllegalStateException("MlKitContext 없음")
        GmsScanIntentSource(FakePlatform(scanner = FakeScanner { throw boom })).prepare(activity, DocumentScanOptions(), c2)
        assertSame(boom, c2.errors.single().cause)
    }

    @Test
    fun `기본 공급원을 만들 수 있다`() {
        assertNotNull(ScanIntentSource.gms(activity))
    }

    // --- DocumentScanLauncher ---

    private class ManualSource : ScanIntentSource {
        val pending = mutableListOf<ScanIntentCallback>()
        override fun prepare(activity: Activity, options: DocumentScanOptions, callback: ScanIntentCallback) {
            pending += callback
        }
    }

    @Test
    fun `launch — 준비 중엔 다시 열지 않고, 준비되면 런처를 부른다`() {
        val source = ManualSource()
        val launcher = RecordingLauncher()
        val results = mutableListOf<DocumentScanResult>()
        val scan = DocumentScanLauncher(launcher, { activity }, DocumentScanOptions(), source) { results += it }
        assertFalse(scan.isPreparing)
        assertTrue(scan.launch())
        assertTrue(scan.isPreparing)
        assertFalse(scan.launch())
        assertEquals(1, source.pending.size)
        val request = IntentSenderRequest.Builder(intentSender(activity)).build()
        source.pending.single().onReady(request)
        assertFalse(scan.isPreparing)
        assertSame(request, launcher.launched.single())
        // 콜백을 두 번 불러도 한 번만 반영한다
        source.pending.single().onReady(request)
        source.pending.single().onError(DocScanException(DocScanFailureReason.UNKNOWN, "x"))
        assertEquals(1, launcher.launched.size)
        assertTrue(results.isEmpty())
        // 화면이 떠서 결과를 기다리는 동안에도 다시 열지 않는다
        assertTrue(scan.isInProgress)
        assertFalse(scan.launch())
        assertEquals(1, source.pending.size)
        // 결과가 오면 풀리고 다시 열 수 있다
        scan.dispatchResult(DocumentScanResult.Cancelled)
        assertEquals(listOf<DocumentScanResult>(DocumentScanResult.Cancelled), results)
        assertFalse(scan.isInProgress)
        assertTrue(scan.launch())
    }

    @Test
    fun `준비 실패는 Failure 결과로`() {
        val source = ManualSource()
        val results = mutableListOf<DocumentScanResult>()
        val scan = DocumentScanLauncher(RecordingLauncher(), { activity }, DocumentScanOptions(), source) { results += it }
        scan.launch()
        val error = DocScanException(DocScanFailureReason.MODULE_DOWNLOAD_REQUIRED, "x")
        source.pending.single().onError(error)
        assertSame(error, (results.single() as DocumentScanResult.Failure).error)
        assertFalse(scan.isPreparing)
    }

    @Test
    fun `액티비티가 없거나 끝났으면 열지 않는다`() {
        val source = ManualSource()
        assertFalse(DocumentScanLauncher(RecordingLauncher(), { null }, DocumentScanOptions(), source) {}.launch())
        val finished = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().apply { finish() }
        assertFalse(DocumentScanLauncher(RecordingLauncher(), { finished }, DocumentScanOptions(), source) {}.launch())
        assertTrue(source.pending.isEmpty())
    }

    @Test
    fun `준비하는 동안 액티비티가 끝나면 화면도 결과도 없다`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val host = controller.get()
        val source = ManualSource()
        val launcher = RecordingLauncher()
        val results = mutableListOf<DocumentScanResult>()
        val scan = DocumentScanLauncher(launcher, { host }, DocumentScanOptions(), source) { results += it }
        assertTrue(scan.launch())
        controller.pause().stop().destroy()
        source.pending.single().onReady(IntentSenderRequest.Builder(intentSender(host)).build())
        assertTrue(launcher.launched.isEmpty())
        assertTrue(results.isEmpty())
        assertFalse(scan.isPreparing)

        // 끝난 액티비티로는 다시 열지 않는다
        assertFalse(scan.launch())
    }

    @Test
    fun `준비 실패를 받을 때 액티비티가 끝났으면 버린다`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val source = ManualSource()
        val results = mutableListOf<DocumentScanResult>()
        val scan = DocumentScanLauncher(RecordingLauncher(), { controller.get() }, DocumentScanOptions(), source) { results += it }
        scan.launch()
        controller.pause().stop().destroy()
        source.pending.single().onError(DocScanException(DocScanFailureReason.UNKNOWN, "x"))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `런처가 실패하면 원인 보존 Failure`() {
        val source = ManualSource()
        val launcher = RecordingLauncher()
        val results = mutableListOf<DocumentScanResult>()
        val scan = DocumentScanLauncher(launcher, { activity }, DocumentScanOptions(), source) { results += it }

        launcher.failure = IllegalStateException("unregistered")
        scan.launch()
        source.pending.last().onReady(IntentSenderRequest.Builder(intentSender(activity)).build())
        val f1 = results.last() as DocumentScanResult.Failure
        assertEquals(DocScanFailureReason.UNKNOWN, f1.error.reason)
        assertSame(launcher.failure, f1.error.cause)

        launcher.failure = ActivityNotFoundException("no gms")
        scan.launch()
        source.pending.last().onReady(IntentSenderRequest.Builder(intentSender(activity)).build())
        assertEquals(DocScanFailureReason.PLAY_SERVICES_MISSING, (results.last() as DocumentScanResult.Failure).error.reason)
    }

    @Test
    fun `등록이 풀린 런처가 실패해도 액티비티가 끝났으면 결과 없음`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val host = controller.get()
        val source = ManualSource()
        val launcher = object : ActivityResultLauncher<IntentSenderRequest>() {
            override fun launch(input: IntentSenderRequest, options: ActivityOptionsCompat?) {
                controller.pause().stop().destroy()
                throw IllegalStateException("unregistered")
            }
            override fun unregister() = Unit
            override val contract: ActivityResultContract<IntentSenderRequest, *> get() = DocumentScanContract()
        }
        val results = mutableListOf<DocumentScanResult>()
        DocumentScanLauncher(launcher, { host }, DocumentScanOptions(), source) { results += it }.launch()
        source.pending.single().onReady(IntentSenderRequest.Builder(intentSender(host)).build())
        assertTrue(results.isEmpty())
    }

    @Test
    fun `액티비티 등록 확장 — onCreate 에서 등록하고 결과 콜백이 이어진다`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val host = controller.get()
        val results = mutableListOf<DocumentScanResult>()
        val source = ManualSource()
        val scan = host.registerDocumentScanner(DocumentScanOptions(pageLimit = 2), source) { results += it }
        assertEquals(2, scan.options.pageLimit)
        controller.start().resume()
        assertTrue(scan.launch())
        source.pending.single().onError(DocScanException(DocScanFailureReason.NETWORK_ERROR, "x"))
        assertEquals(DocScanFailureReason.NETWORK_ERROR, (results.single() as DocumentScanResult.Failure).error.reason)
        // 기본 공급원 인자도 만들 수 있다
        val c2 = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        assertNotNull(c2.registerDocumentScanner { })
    }

    // --- 회전·프로세스 종료: 액티비티 결과 등록부가 결과를 보관해 다시 등록된 콜백으로 준다 ---

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class TestRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += requestCode
        }
    }

    private class Caller(private val registry: ActivityResultRegistry, private val owner: LifecycleOwner) : ActivityResultCaller {
        override fun <I, O> registerForActivityResult(contract: ActivityResultContract<I, O>, callback: ActivityResultCallback<O>) =
            registry.register("docscan", owner, contract, callback)

        override fun <I, O> registerForActivityResult(
            contract: ActivityResultContract<I, O>,
            registry: ActivityResultRegistry,
            callback: ActivityResultCallback<O>,
        ) = registry.register("docscan", owner, contract, callback)
    }

    @Test
    fun `화면이 떠 있는 동안 프로세스가 종료돼도 복원된 콜백으로 결과가 온다`() {
        // 1) 첫 프로세스: 등록하고 화면을 연다
        val registry1 = TestRegistry()
        val owner1 = Owner().apply { registry.currentState = Lifecycle.State.CREATED }
        val first = mutableListOf<DocumentScanResult>()
        val source = object : ScanIntentSource {
            override fun prepare(activity: Activity, options: DocumentScanOptions, callback: ScanIntentCallback) =
                callback.onReady(IntentSenderRequest.Builder(intentSender(activity)).build())
        }
        val scan1 = registerDocumentScanner(Caller(registry1, owner1), { activity }, DocumentScanOptions(), source) { first += it }
        owner1.registry.currentState = Lifecycle.State.STARTED
        scan1.launch()
        val code = registry1.launches.single()
        val saved = Bundle().also { registry1.onSaveInstanceState(it) }

        // 2) 프로세스 종료 뒤 새 등록부가 상태를 복원하고, 콜백이 등록되기 전에 결과가 도착한다
        val registry2 = TestRegistry().apply { onRestoreInstanceState(saved) }
        assertTrue(registry2.dispatchResult(code, Activity.RESULT_CANCELED, null))

        // 3) 새 화면이 같은 키로 다시 등록하고 STARTED 가 되면 결과가 전달된다
        val owner2 = Owner().apply { registry.currentState = Lifecycle.State.CREATED }
        val second = mutableListOf<DocumentScanResult>()
        registerDocumentScanner(Caller(registry2, owner2), { activity }, DocumentScanOptions(), source) { second += it }
        assertTrue(second.isEmpty())
        owner2.registry.currentState = Lifecycle.State.STARTED
        assertEquals(listOf<DocumentScanResult>(DocumentScanResult.Cancelled), second)
        assertTrue(first.isEmpty())
    }
}
