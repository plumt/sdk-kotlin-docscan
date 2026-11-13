package net.plumt.docscan

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import net.plumt.docscan.internal.AndroidScannerPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 필드 등록(DOC-07)·중복 실행 방지(DOC-13)·프로세스 종료 뒤 진행 중 상태 복원. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 29, 34])
class LaunchProgressTest {

    /** 준비 결과를 손으로 주는 공급원. */
    class ManualSource : ScanIntentSource {
        val pending = mutableListOf<ScanIntentCallback>()
        val hosts = mutableListOf<Activity>()
        override fun prepare(activity: Activity, options: DocumentScanOptions, callback: ScanIntentCallback) {
            hosts += activity
            pending += callback
        }
    }

    /** README 예시 그대로 필드에서 등록하는 액티비티. 기본 공급원(gms)을 쓴다. */
    class FieldDefaultActivity : ComponentActivity() {
        val results = mutableListOf<DocumentScanResult>()
        val scanner = registerDocumentScanner(DocumentScanOptions(allowGalleryImport = false)) { results += it }
    }

    /** 필드에서 등록하되 시험용 공급원을 쓰는 액티비티. */
    class FieldManualActivity : ComponentActivity() {
        val source = ManualSource()
        val results = mutableListOf<DocumentScanResult>()
        val scanner = registerDocumentScanner(DocumentScanOptions(), source) { results += it }
    }

    /** onCreate 에서 등록하는 액티비티. */
    class OnCreateActivity : ComponentActivity() {
        val source = ManualSource()
        val results = mutableListOf<DocumentScanResult>()
        lateinit var scanner: DocumentScanLauncher
        override fun onCreate(savedInstanceState: Bundle?) {
            scanner = registerDocumentScanner(DocumentScanOptions(), source) { results += it }
            super.onCreate(savedInstanceState)
        }
    }

    // --- DOC-07: attach 전 생성 ---

    @Test
    fun `attach 전 액티비티로 기본 공급원·플랫폼을 만들어도 예외가 없다`() {
        val unattached = ComponentActivity()
        assertNotNull(ScanIntentSource.gms(unattached))
        assertNotNull(AndroidScannerPlatform(unattached))
        assertNotNull(ModuleAvailability.documentScanner(unattached))
    }

    @Test
    fun `README 대로 필드에서 기본 공급원으로 등록해도 액티비티가 만들어진다`() {
        val activity = Robolectric.buildActivity(FieldDefaultActivity::class.java).setup().get()
        assertFalse(activity.scanner.isInProgress)
        assertEquals(DocumentScanOptions(allowGalleryImport = false), activity.scanner.options)
    }

    @Test
    fun `필드 등록 — 현재 액티비티로 준비하고 결과가 콜백으로 온다`() {
        val activity = Robolectric.buildActivity(FieldManualActivity::class.java).setup().get()
        assertTrue(activity.scanner.launch())
        assertEquals(activity, activity.source.hosts.single())
        activity.source.pending.single().onError(DocScanException(DocScanFailureReason.NETWORK_ERROR, "x"))
        assertEquals(DocScanFailureReason.NETWORK_ERROR, (activity.results.single() as DocumentScanResult.Failure).error.reason)
        assertFalse(activity.scanner.isInProgress)
    }

    @Test
    fun `onCreate 등록 — 같은 방식으로 동작한다`() {
        val activity = Robolectric.buildActivity(OnCreateActivity::class.java).setup().get()
        assertTrue(activity.scanner.launch())
        assertFalse(activity.scanner.launch())
        activity.source.pending.single().onError(DocScanException(DocScanFailureReason.UNKNOWN, "x"))
        assertEquals(1, activity.results.size)
        assertTrue(activity.scanner.launch())
    }

    // --- DOC-13: 한 번에 화면 하나 ---

    @Test
    fun `빠르게 두 번 — 준비가 바로 끝나도 두 번째는 false, 화면·결과는 하나`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val launcher = RecordingLauncher()
        val results = mutableListOf<DocumentScanResult>()
        val immediate = ScanIntentSource { a, _, cb -> cb.onReady(IntentSenderRequest.Builder(intentSender(a)).build()) }
        val scan = DocumentScanLauncher(launcher, { activity }, DocumentScanOptions(), immediate) { results += it }
        assertTrue(scan.launch())
        assertFalse(scan.isPreparing)
        assertTrue(scan.isInProgress)
        assertFalse(scan.launch())
        assertFalse(scan.launch())
        assertEquals(1, launcher.launched.size)

        scan.dispatchResult(DocumentScanResult.Cancelled)
        assertEquals(1, results.size)
        assertFalse(scan.isInProgress)
        // 결과 뒤에는 다시 연다
        assertTrue(scan.launch())
        assertEquals(2, launcher.launched.size)
    }

    @Test
    fun `준비 실패·런처 실패 뒤에는 다시 열 수 있다`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val source = ManualSource()
        val launcher = RecordingLauncher()
        val changes = mutableListOf<Boolean>()
        val scan = DocumentScanLauncher(launcher, { activity }, DocumentScanOptions(), source, onAwaitingResultChange = { changes += it }) {}
        assertTrue(scan.launch())
        source.pending.last().onError(DocScanException(DocScanFailureReason.MODULE_DOWNLOAD_REQUIRED, "x"))
        assertFalse(scan.isInProgress)
        assertTrue(scan.launch())
        launcher.failure = IllegalStateException("unregistered")
        source.pending.last().onReady(IntentSenderRequest.Builder(intentSender(activity)).build())
        assertFalse(scan.isInProgress)
        assertTrue(scan.launch())
        launcher.failure = android.content.ActivityNotFoundException("x")
        source.pending.last().onReady(IntentSenderRequest.Builder(intentSender(activity)).build())
        assertFalse(scan.isInProgress)
        // 대기 표시가 켜졌다 꺼졌다를 정확히 알린다
        assertEquals(listOf(true, false, true, false), changes)
    }

    @Test
    fun `실제 등록부 — 결과가 오면 진행 중이 풀린다`() {
        val activity = Robolectric.buildActivity(FieldManualActivity::class.java).setup().get()
        val registry = activity.activityResultRegistry
        assertTrue(activity.scanner.launch())
        activity.source.pending.single().onReady(IntentSenderRequest.Builder(intentSender(activity)).build())
        assertTrue(activity.scanner.isInProgress)
        assertFalse(activity.scanner.launch())
        val code = org.robolectric.Shadows.shadowOf(activity).lastIntentSenderRequest.requestCode
        assertTrue(registry.dispatchResult(code, Activity.RESULT_CANCELED, null))
        assertEquals(listOf<DocumentScanResult>(DocumentScanResult.Cancelled), activity.results)
        assertFalse(activity.scanner.isInProgress)
        assertTrue(activity.scanner.launch())
    }

    // --- 프로세스 종료 뒤 복원 ---

    private class TestRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += requestCode
        }
    }

    /** 액티비티처럼 결과 등록부와 저장된 상태를 가진 주인. */
    private class Host(private val results: ActivityResultRegistry) : ActivityResultCaller, SavedStateRegistryOwner {
        private val life = LifecycleRegistry.createUnsafe(this)
        private val controller = SavedStateRegistryController.create(this)
        private var keys = 0
        override val lifecycle: Lifecycle get() = life
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performAttach()
        }

        fun create(saved: Bundle?) {
            controller.performRestore(saved)
            life.currentState = Lifecycle.State.CREATED
        }

        fun start() {
            life.currentState = Lifecycle.State.STARTED
        }

        fun save(): Bundle = Bundle().also { controller.performSave(it) }

        override fun <I, O> registerForActivityResult(contract: ActivityResultContract<I, O>, callback: ActivityResultCallback<O>) =
            results.register("docscan#${keys++}", this, contract, callback)

        override fun <I, O> registerForActivityResult(
            contract: ActivityResultContract<I, O>,
            registry: ActivityResultRegistry,
            callback: ActivityResultCallback<O>,
        ) = registry.register("docscan#${keys++}", this, contract, callback)
    }

    private val immediate = ScanIntentSource { a, _, cb -> cb.onReady(IntentSenderRequest.Builder(intentSender(a)).build()) }

    /** STARTED 까지만 간 액티비티 — 복원 직후(결과 도착 전) 상태를 흉내 낸다. */
    private fun startedActivity(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).create().start().get()

    @Test
    fun `화면이 떠 있는 동안 프로세스가 죽어도 진행 중이 이어지고 결과로 풀린다`() {
        val activity = startedActivity()
        // 1) 첫 프로세스: 필드처럼 등록(create 전) → 화면을 연다
        val registry1 = TestRegistry()
        val host1 = Host(registry1)
        val first = mutableListOf<DocumentScanResult>()
        val scan1 = registerDocumentScanner(host1, { activity }, DocumentScanOptions(), immediate) { first += it }
        host1.create(null)
        host1.start()
        assertTrue(scan1.launch())
        assertFalse(scan1.launch())
        val code = registry1.launches.single()
        val savedResults = Bundle().also { registry1.onSaveInstanceState(it) }
        val savedState = host1.save()

        // 2) 새 프로세스: 등록부 복원, 필드 등록 시점에는 아직 모른다
        val registry2 = TestRegistry().apply { onRestoreInstanceState(savedResults) }
        val host2 = Host(registry2)
        val second = mutableListOf<DocumentScanResult>()
        val scan2 = registerDocumentScanner(host2, { activity }, DocumentScanOptions(), immediate) { second += it }
        assertFalse(scan2.isInProgress)
        host2.create(savedState)
        // ON_CREATE 에서 복원 — 결과가 오기 전엔 다시 열지 않는다
        assertTrue(scan2.isInProgress)
        assertFalse(scan2.launch())
        assertTrue(registry2.launches.isEmpty())

        // 3) 결과가 도착하고 STARTED 가 되면 콜백으로 오고 풀린다
        assertTrue(registry2.dispatchResult(code, Activity.RESULT_CANCELED, null))
        host2.start()
        assertEquals(listOf<DocumentScanResult>(DocumentScanResult.Cancelled), second)
        assertFalse(scan2.isInProgress)
        assertTrue(scan2.launch())
        assertTrue(first.isEmpty())
    }

    @Test
    fun `복원된 대기가 한 번 더 저장돼도 이어진다(연속 종료)`() {
        val activity = startedActivity()
        val host1 = Host(TestRegistry())
        val scan1 = registerDocumentScanner(host1, { activity }, DocumentScanOptions(), immediate) {}
        host1.create(null)
        host1.start()
        scan1.launch()
        val host2 = Host(TestRegistry())
        registerDocumentScanner(host2, { activity }, DocumentScanOptions(), immediate) {}
        host2.create(host1.save())
        val host3 = Host(TestRegistry())
        val scan3 = registerDocumentScanner(host3, { activity }, DocumentScanOptions(), immediate) {}
        host3.create(host2.save())
        assertTrue(scan3.isInProgress)
    }

    @Test
    fun `준비 중에 죽으면 대기를 저장하지 않는다 — 결과가 안 오니 막히면 안 된다`() {
        val activity = startedActivity()
        val source = ManualSource()
        val host1 = Host(TestRegistry())
        val scan1 = registerDocumentScanner(host1, { activity }, DocumentScanOptions(), source) {}
        host1.create(null)
        host1.start()
        assertTrue(scan1.launch())
        assertTrue(scan1.isPreparing)
        val host2 = Host(TestRegistry())
        val scan2 = registerDocumentScanner(host2, { activity }, DocumentScanOptions(), source) {}
        host2.create(host1.save())
        assertFalse(scan2.isInProgress)
    }

    @Test
    fun `복원된 대기인데 결과 없이 화면이 다시 앞에 오면 낡은 상태로 보고 푼다`() {
        val host1 = Host(TestRegistry())
        val scan1 = registerDocumentScanner(host1, { startedActivity() }, DocumentScanOptions(), immediate) {}
        host1.create(null)
        host1.start()
        scan1.launch()

        val resumed = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val registry2 = TestRegistry()
        val host2 = Host(registry2)
        val scan2 = registerDocumentScanner(host2, { resumed }, DocumentScanOptions(), immediate) {}
        host2.create(host1.save())
        assertTrue(scan2.isInProgress)
        assertTrue(scan2.launch())
        assertEquals(1, registry2.launches.size)
        // 이 인스턴스가 연 대기는 앞에 있어도 풀지 않는다(빠른 두 번 탭)
        assertFalse(scan2.launch())
    }

    @Test
    fun `생성자에 복원 값을 넘기면 대기로 시작하고, 결과로 풀린다`() {
        val activity = startedActivity()
        val changes = mutableListOf<Boolean>()
        val results = mutableListOf<DocumentScanResult>()
        val scan = DocumentScanLauncher(RecordingLauncher(), { activity }, DocumentScanOptions(), immediate, true, { changes += it }) {
            results += it
        }
        assertTrue(scan.isInProgress)
        assertFalse(scan.launch())
        scan.dispatchResult(DocumentScanResult.Cancelled)
        assertEquals(listOf(false), changes)
        assertEquals(1, results.size)
        assertTrue(scan.launch())
    }

    @Test
    fun `한 화면에 둘을 등록해도 키가 겹치지 않고 각자 복원된다`() {
        val activity = startedActivity()
        val host1 = Host(TestRegistry())
        val a1 = registerDocumentScanner(host1, { activity }, DocumentScanOptions(), immediate) {}
        val b1 = registerDocumentScanner(host1, { activity }, DocumentScanOptions(), immediate) {}
        host1.create(null)
        host1.start()
        assertTrue(b1.launch())
        assertFalse(a1.isInProgress)
        val host2 = Host(TestRegistry())
        val a2 = registerDocumentScanner(host2, { activity }, DocumentScanOptions(), immediate) {}
        val b2 = registerDocumentScanner(host2, { activity }, DocumentScanOptions(), immediate) {}
        host2.create(host1.save())
        assertFalse(a2.isInProgress)
        assertTrue(b2.isInProgress)
    }

    @Test
    fun `CREATED 뒤에 등록해도 저장 공급자가 붙는다`() {
        val activity = startedActivity()
        val host1 = Host(TestRegistry())
        host1.create(null)
        val scan1 = registerDocumentScanner(host1, { activity }, DocumentScanOptions(), immediate) {}
        host1.start()
        scan1.launch()
        val host2 = Host(TestRegistry())
        host2.create(host1.save())
        val scan2 = registerDocumentScanner(host2, { activity }, DocumentScanOptions(), immediate) {}
        assertTrue(scan2.isInProgress)
    }
}
