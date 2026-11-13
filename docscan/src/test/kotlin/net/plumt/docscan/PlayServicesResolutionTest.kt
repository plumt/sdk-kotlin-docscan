package net.plumt.docscan

import android.app.Activity
import androidx.activity.ComponentActivity
import com.google.android.gms.common.ConnectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayServicesResolutionTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private class Dialogs(val resolvable: Boolean) : PlayServicesDialogs {
        val shown = mutableListOf<Pair<Int, Int>>()
        override fun isUserResolvable(code: Int) = resolvable
        override fun show(activity: Activity, code: Int, requestCode: Int): Boolean {
            shown += code to requestCode
            return true
        }
    }

    @Test
    fun `Play 서비스 분류이고 고칠 수 있으면 대화상자`() {
        for (reason in listOf(DocScanFailureReason.PLAY_SERVICES_MISSING, DocScanFailureReason.PLAY_SERVICES_UPDATE_REQUIRED)) {
            val d = Dialogs(true)
            assertTrue(showPlayServicesResolution(DocScanException(reason, "x", statusCode = 2), activity, 9, d))
            assertEquals(listOf(2 to 9), d.shown)
        }
    }

    @Test
    fun `API 연결 실패 — 17 이 아니라 업데이트 코드로 대화상자`() {
        val d = Dialogs(true)
        val error = DocScanException(DocScanFailureReason.PLAY_SERVICES_API_UNAVAILABLE, "x", statusCode = 17)
        assertTrue(showPlayServicesResolution(error, activity, 3, d))
        assertEquals(listOf(ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED to 3), d.shown)
        assertFalse(showPlayServicesResolution(error, activity, 3, Dialogs(false)))
    }

    @Test
    fun `다른 분류·코드 없음·고칠 수 없음은 false`() {
        val d = Dialogs(true)
        assertFalse(showPlayServicesResolution(DocScanException(DocScanFailureReason.NETWORK_ERROR, "x", statusCode = 2), activity, 0, d))
        assertFalse(showPlayServicesResolution(DocScanException(DocScanFailureReason.PLAY_SERVICES_MISSING, "x"), activity, 0, d))
        assertFalse(
            showPlayServicesResolution(DocScanException(DocScanFailureReason.PLAY_SERVICES_MISSING, "x", statusCode = 9), activity, 0, Dialogs(false)),
        )
        assertTrue(d.shown.isEmpty())
    }

    @Test
    fun `기본 구현 — 사용자가 고칠 수 없는 코드는 띄우지 않는다`() {
        // INTERNAL_ERROR(8) 는 사용자가 고칠 수 없는 오류다
        assertFalse(DocScanException(DocScanFailureReason.PLAY_SERVICES_MISSING, "x", statusCode = 8).showPlayServicesResolution(activity))
        assertFalse(DocScanException(DocScanFailureReason.UNKNOWN, "x").showPlayServicesResolution(activity))
    }
}
