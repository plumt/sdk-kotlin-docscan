package net.plumt.docscan.compose

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import net.plumt.docscan.DocScanException
import net.plumt.docscan.DocScanFailureReason
import net.plumt.docscan.DocumentScanLauncher
import net.plumt.docscan.DocumentScanOptions
import net.plumt.docscan.DocumentScanResult
import net.plumt.docscan.ScanIntentCallback
import net.plumt.docscan.ScanIntentSource
import androidx.activity.result.IntentSenderRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RememberDocumentScanLauncherTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class FailingSource : ScanIntentSource {
        val hosts = mutableListOf<Activity>()
        override fun prepare(activity: Activity, options: DocumentScanOptions, callback: ScanIntentCallback) {
            hosts += activity
            callback.onError(DocScanException(DocScanFailureReason.MODULE_DOWNLOAD_REQUIRED, "model"))
        }
    }

    @Test
    fun `현재 액티비티로 준비하고 결과는 최신 콜백으로 온다`() {
        val source = FailingSource()
        val first = mutableListOf<DocumentScanResult>()
        val second = mutableListOf<DocumentScanResult>()
        var useSecond by mutableStateOf(false)
        lateinit var launcher: DocumentScanLauncher
        rule.setContent {
            val sink = if (useSecond) second else first
            launcher = rememberDocumentScanLauncher(source = source) { sink += it }
        }
        rule.runOnIdle { assertTrue(launcher.launch()) }
        assertSame(rule.activity, source.hosts.single())
        assertEquals(DocScanFailureReason.MODULE_DOWNLOAD_REQUIRED, (first.single() as DocumentScanResult.Failure).error.reason)
        val before = launcher
        useSecond = true
        rule.runOnIdle {
            // 콜백만 바뀌면 같은 런처를 유지하고 새 콜백으로 결과를 준다
            assertSame(before, launcher)
            launcher.launch()
        }
        assertEquals(1, first.size)
        assertEquals(1, second.size)
    }

    @Test
    fun `설정이 바뀌면 새 런처, 기본 공급원도 만든다`() {
        var options by mutableStateOf(DocumentScanOptions())
        val seen = mutableListOf<DocumentScanLauncher>()
        rule.setContent { seen += rememberDocumentScanLauncher(options = options) {} }
        rule.runOnIdle { options = DocumentScanOptions(pageLimit = 3) }
        rule.runOnIdle {
            assertNotSame(seen.first(), seen.last())
            assertEquals(3, seen.last().options.pageLimit)
        }
    }

    private class ManualSource : ScanIntentSource {
        val pending = mutableListOf<ScanIntentCallback>()
        override fun prepare(activity: Activity, options: DocumentScanOptions, callback: ScanIntentCallback) {
            pending += callback
        }
    }

    @Test
    fun `진행 중이면 두 번째 launch 는 false, 결과가 오면 풀린다`() {
        val source = ManualSource()
        val results = mutableListOf<DocumentScanResult>()
        lateinit var launcher: DocumentScanLauncher
        rule.setContent { launcher = rememberDocumentScanLauncher(source = source) { results += it } }
        rule.runOnIdle {
            assertTrue(launcher.launch())
            assertFalse(launcher.launch())
            val sender = android.app.PendingIntent.getActivity(
                rule.activity, 0, android.content.Intent("test"), android.app.PendingIntent.FLAG_IMMUTABLE,
            ).intentSender
            source.pending.single().onReady(IntentSenderRequest.Builder(sender).build())
            // 화면이 떠서 결과를 기다리는 동안에도 다시 열지 않는다
            assertTrue(launcher.isInProgress)
            assertFalse(launcher.launch())
            val code = org.robolectric.Shadows.shadowOf(rule.activity).lastIntentSenderRequest.requestCode
            assertTrue(rule.activity.activityResultRegistry.dispatchResult(code, Activity.RESULT_CANCELED, null))
        }
        rule.runOnIdle {
            assertEquals(listOf<DocumentScanResult>(DocumentScanResult.Cancelled), results)
            assertFalse(launcher.isInProgress)
            assertTrue(launcher.launch())
            assertEquals(2, source.pending.size)
        }
    }

    @Test
    fun `결과 대기 상태는 저장된 상태 복원(프로세스 종료) 뒤에도 이어진다`() {
        val source = ManualSource()
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        lateinit var launcher: DocumentScanLauncher
        tester.setContent { launcher = rememberDocumentScanLauncher(source = source) {} }
        rule.runOnIdle {
            assertTrue(launcher.launch())
            val sender = android.app.PendingIntent.getActivity(
                rule.activity, 0, android.content.Intent("test"), android.app.PendingIntent.FLAG_IMMUTABLE,
            ).intentSender
            source.pending.single().onReady(IntentSenderRequest.Builder(sender).build())
        }
        val before = launcher
        tester.emulateSavedInstanceStateRestore()
        rule.runOnIdle {
            assertNotSame(before, launcher)
            assertTrue(launcher.isInProgress)
        }
    }
}
