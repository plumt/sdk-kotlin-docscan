package net.plumt.docscan.compose

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import net.plumt.docscan.DocumentScanContract
import net.plumt.docscan.DocumentScanLauncher
import net.plumt.docscan.DocumentScanOptions
import net.plumt.docscan.DocumentScanResult
import net.plumt.docscan.ScanIntentSource

/**
 * 컴포지션에 스캐너 런처를 등록한다. 버튼의 `onClick` 에서 [DocumentScanLauncher.launch] 를 부른다.
 *
 * 결과 등록은 `rememberLauncherForActivityResult` 를 쓴다 — 스캐너 화면이 떠 있는 동안 회전·프로세스 종료가 일어나도
 * 다시 만들어진 컴포지션의 [onResult] 로 결과가 온다. 받은 결과([DocumentScanResult]·[net.plumt.docscan.PageList])는
 * Parcelable 이므로 `rememberSaveable` 로 보관한다.
 *
 * 한 번에 화면 하나만 연다 — 준비 중이거나 화면이 떠 있는 동안 [DocumentScanLauncher.launch] 는 false 를 돌려준다.
 * "결과 대기 중" 상태는 `rememberSaveable` 로 보관해 프로세스 종료 뒤에도 이어진다.
 *
 * [DocumentScanLauncher.isPreparing]·[DocumentScanLauncher.isInProgress] 는 Compose 상태가 아니다. 버튼 비활성화가 필요하면
 * launch 가 true 를 돌려준 직후 앱 상태로 표시하고 [onResult] 에서 해제한다.
 *
 * @param source 화면 준비 방법. null 이면 Google Play 서비스 문서 스캐너
 */
@Composable
public fun rememberDocumentScanLauncher(
    options: DocumentScanOptions = DocumentScanOptions(),
    source: ScanIntentSource? = null,
    onResult: (DocumentScanResult) -> Unit,
): DocumentScanLauncher {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val awaiting = rememberSaveable { mutableStateOf(false) }
    // 결과 콜백이 지금의 런처를 거쳐 진행 중 상태를 풀도록 참조를 둔다(런처는 결과 등록보다 나중에 만들어진다)
    val current = remember { arrayOfNulls<DocumentScanLauncher>(1) }
    // 결과는 등록(컴포지션 적용 뒤)·STARTED 이후에만 오므로 그때는 런처가 이미 만들어져 있다
    val launcher = rememberLauncherForActivityResult(DocumentScanContract()) { result ->
        checkNotNull(current[0]) { "스캐너 런처가 아직 없다" }.dispatchResult(result)
    }
    val resolved = source ?: remember(context) { ScanIntentSource.gms(context) }
    return remember(launcher, activity, options, resolved) {
        DocumentScanLauncher(
            launcher = launcher,
            activity = { activity },
            options = options,
            source = resolved,
            restoredAwaitingResult = awaiting.value,
            onAwaitingResultChange = { awaiting.value = it },
        ) { currentOnResult(it) }.also { current[0] = it }
    }
}
