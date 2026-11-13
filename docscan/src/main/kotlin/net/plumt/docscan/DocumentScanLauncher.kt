package net.plumt.docscan

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 스캐너 화면을 연다. 결과는 생성할 때 준 콜백으로 [DocumentScanResult] 하나가 온다.
 *
 * - [launch] 는 메인 스레드에서 부른다. 한 번에 화면 하나만 연다 — 준비(Play 서비스 확인·인텐트 요청) 중이거나
 *   화면이 떠서 결과를 기다리는 동안([isInProgress]) 다시 부르면 무시하고 false 를 돌려준다
 * - 진행 중 상태는 결과(성공·취소·실패)가 콜백으로 전달될 때, 또는 준비 단계에서 끝날 때 풀린다
 * - 준비 단계에서 실패하면 화면을 열지 않고 콜백으로 [DocumentScanResult.Failure] 가 온다
 * - 준비하는 동안 액티비티가 끝났으면(회전 등) 화면을 열지 않는다 — 결과도 오지 않으니 새 화면에서 다시 누른다.
 *   화면이 열린 뒤의 회전·프로세스 종료는 액티비티 결과 등록부가 처리해 새 콜백으로 결과가 온다
 *
 * 액티비티에서는 [registerDocumentScanner], Compose 에서는 docscan-compose 의 `rememberDocumentScanLauncher` 로 만든다.
 * 생성자를 직접 쓰면 [launcher] 의 결과 콜백에서 [dispatchResult] 를 불러야 한다 — 부르지 않으면 진행 중 상태가 풀리지 않는다.
 *
 * @param launcher [DocumentScanContract] 로 등록한 런처
 * @param activity 화면을 띄울 현재 액티비티. null 이면 [launch] 가 false
 * @param restoredAwaitingResult 다시 만들어진 화면에서, 이전 인스턴스가 화면을 열고 결과를 기다리던 중이었으면 true.
 *   [onAwaitingResultChange] 로 받은 값을 저장했다가 넘긴다
 * @param onAwaitingResultChange "화면이 떠서 결과를 기다리는 중" 이 바뀔 때마다 불린다. 저장된 상태(`SavedStateHandle`·
 *   `rememberSaveable` 등)에 보관해 프로세스 종료 뒤 [restoredAwaitingResult] 로 돌려준다
 * @param onResult 결과 콜백. 메인 스레드에서 불린다
 */
public class DocumentScanLauncher(
    private val launcher: ActivityResultLauncher<IntentSenderRequest>,
    private val activity: () -> Activity?,
    public val options: DocumentScanOptions,
    private val source: ScanIntentSource,
    restoredAwaitingResult: Boolean = false,
    private val onAwaitingResultChange: (Boolean) -> Unit = {},
    private val onResult: (DocumentScanResult) -> Unit,
) {
    /** 준비 중이면 true. 버튼을 잠시 비활성화하는 데 쓴다. */
    public var isPreparing: Boolean = false
        private set

    /** 준비 중이거나 화면이 떠서 결과를 기다리는 중이면 true. 이 동안 [launch] 는 false 를 돌려준다. */
    public val isInProgress: Boolean get() = isPreparing || awaitingResult

    private var awaitingResult = restoredAwaitingResult

    /** 복원된 대기 상태다. 이 인스턴스가 직접 화면을 연 것이 아니라 결과가 끝내 안 올 수도 있다. */
    private var restored = restoredAwaitingResult

    /** 화면 준비를 시작했으면 true, 이미 진행 중이거나 액티비티가 없으면 false. */
    public fun launch(): Boolean {
        val host = activity()?.takeUnless { it.isGone() } ?: return false
        if (restored && awaitingResult && host.isResumed()) {
            // 복원된 "결과 대기" 인데 화면이 다시 앞에 있다 — 결과는 STARTED 에서 이미 왔어야 한다. 낡은 상태로 보고 푼다
            setAwaiting(false)
        }
        if (isInProgress) return false
        isPreparing = true
        source.prepare(host, options, object : ScanIntentCallback {
            private var done = false

            override fun onReady(request: IntentSenderRequest) {
                if (!finish()) return
                if (host.isGone()) return
                try {
                    setAwaiting(true)
                    launcher.launch(request)
                } catch (e: IllegalStateException) {
                    setAwaiting(false)
                    // 등록이 풀린 런처(액티비티 종료 직후) — 화면을 열지 않는다
                    if (!host.isGone()) onResult(DocumentScanResult.Failure(DocScanException(DocScanFailureReason.UNKNOWN, "스캐너 화면을 열지 못했다", e)))
                } catch (e: android.content.ActivityNotFoundException) {
                    setAwaiting(false)
                    onResult(DocumentScanResult.Failure(DocScanException(DocScanFailureReason.PLAY_SERVICES_MISSING, "스캐너 화면이 없다", e)))
                }
            }

            override fun onError(error: DocScanException) {
                if (!finish()) return
                if (!host.isGone()) onResult(DocumentScanResult.Failure(error))
            }

            private fun finish(): Boolean {
                if (done) return false
                done = true
                isPreparing = false
                return true
            }
        })
        return true
    }

    /**
     * 스캐너 화면의 결과를 전달한다. 진행 중 상태를 풀고 결과 콜백을 부른다.
     * [registerDocumentScanner]·`rememberDocumentScanLauncher` 가 대신 부르므로 생성자를 직접 쓸 때만 부른다.
     */
    public fun dispatchResult(result: DocumentScanResult) {
        setAwaiting(false)
        onResult(result)
    }

    /**
     * 저장된 상태에서 "결과 대기 중" 을 늦게 복원한다. 복원 값은 등록부가 복원된 뒤(ON_CREATE)에야 읽을 수 있는데,
     * 필드 등록에서는 그 전에 런처가 만들어진다.
     */
    internal fun restoreAwaiting() {
        if (isInProgress) return
        awaitingResult = true
        restored = true
        onAwaitingResultChange(true)
    }

    private fun setAwaiting(value: Boolean) {
        restored = false
        if (awaitingResult == value) return
        awaitingResult = value
        onAwaitingResultChange(value)
    }

    private fun Activity.isGone(): Boolean = isFinishing || isDestroyed

    private fun Activity.isResumed(): Boolean =
        (this as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: false
}

/**
 * 이 액티비티에 스캐너 런처를 등록한다. 다른 액티비티 결과 등록과 같이 필드 초기화나 `onCreate` 에서(STARTED 전에) 부른다.
 *
 * 기본 [source] 는 [launch][DocumentScanLauncher.launch] 할 때 처음 컨텍스트를 읽으므로 필드에서 만들어도 된다.
 * "결과 대기 중" 상태는 액티비티의 저장된 상태에 보관돼 프로세스 종료 뒤에도 이어진다.
 *
 * @param source 화면 준비 방법. 기본은 Google Play 서비스 문서 스캐너
 */
public fun ComponentActivity.registerDocumentScanner(
    options: DocumentScanOptions = DocumentScanOptions(),
    source: ScanIntentSource = ScanIntentSource.gms(this),
    onResult: (DocumentScanResult) -> Unit,
): DocumentScanLauncher = registerDocumentScanner(this, { this }, options, source, onResult)

/**
 * 임의의 [ActivityResultCaller](프래그먼트 등)에 스캐너 런처를 등록한다. 필드 초기화나 `onCreate` 에서 부른다.
 *
 * [caller] 가 [SavedStateRegistryOwner](액티비티·프래그먼트)이면 "결과 대기 중" 상태를 그 저장된 상태에 보관한다.
 *
 * @param activity 화면을 띄울 때의 현재 액티비티(프래그먼트면 `{ activity }`)
 */
public fun registerDocumentScanner(
    caller: ActivityResultCaller,
    activity: () -> Activity?,
    options: DocumentScanOptions,
    source: ScanIntentSource,
    onResult: (DocumentScanResult) -> Unit,
): DocumentScanLauncher {
    // 결과는 등록 직후가 아니라 STARTED 이후에만 오므로 그때는 이미 만들어져 있다
    lateinit var scan: DocumentScanLauncher
    val launcher = caller.registerForActivityResult(DocumentScanContract()) { scan.dispatchResult(it) }
    val owner = caller as? SavedStateRegistryOwner
    if (owner == null) {
        return DocumentScanLauncher(launcher, activity, options, source, onResult = onResult).also { scan = it }
    }
    val state = AwaitingState()
    val created = DocumentScanLauncher(launcher, activity, options, source, onAwaitingResultChange = { state.value = it }, onResult = onResult)
    scan = created
    state.attach(owner) { created.restoreAwaiting() }
    return created
}

/** "결과 대기 중" 하나를 [SavedStateRegistryOwner] 의 저장된 상태에 보관한다. */
private class AwaitingState {
    var value = false

    fun attach(owner: SavedStateRegistryOwner, onRestored: () -> Unit) {
        owner.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event != Lifecycle.Event.ON_CREATE) return
                source.lifecycle.removeObserver(this)
                val registry = owner.savedStateRegistry
                // 같은 화면에 여러 개를 등록해도 등록 순서가 같으면 같은 키를 받는다
                var index = 0
                while (registry.getSavedStateProvider(key(index)) != null) index++
                val key = key(index)
                val restored = registry.consumeRestoredStateForKey(key)?.getBoolean(AWAITING) ?: false
                registry.registerSavedStateProvider(key) { Bundle().apply { putBoolean(AWAITING, value) } }
                if (restored && !value) onRestored()
            }
        })
    }

    private fun key(index: Int) = "net.plumt.docscan.launcher#$index"

    private companion object {
        const val AWAITING = "awaiting"
    }
}
