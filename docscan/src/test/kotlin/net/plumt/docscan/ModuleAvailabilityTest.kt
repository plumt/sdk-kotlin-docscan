package net.plumt.docscan

import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.moduleinstall.ModuleAvailabilityResponse
import com.google.android.gms.common.moduleinstall.ModuleInstallResponse
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import net.plumt.docscan.internal.ModuleAvailabilityImpl
import net.plumt.docscan.internal.toDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModuleAvailabilityTest {
    private val api = FakeOptionalApi()

    private fun availability(platform: FakePlatform, checkMemory: Boolean = true) =
        ModuleAvailabilityImpl(platform, checkMemory) { api }

    private fun update(state: Int, done: Long? = null, total: Long? = null, error: Int = 0) =
        ModuleInstallStatusUpdate(1, state, done, total, error)

    private fun reason(a: ScannerAvailability) = (a as ScannerAvailability.Unavailable).error.reason

    @Test
    fun `check — 있음·내려받기 필요`() = runTest {
        val platform = FakePlatform()
        assertSame(ScannerAvailability.Available, availability(platform).check())
        platform.install.availability = { Tasks.forResult(ModuleAvailabilityResponse(false, 1)) }
        assertSame(ScannerAvailability.DownloadRequired, availability(platform).check())
    }

    @Test
    fun `check — 메모리·Play 서비스 사전 확인`() = runTest {
        assertEquals(DocScanFailureReason.UNSUPPORTED_DEVICE, reason(availability(FakePlatform(memory = 1_600_000_000L)).check()))
        // 메모리 확인을 끈 모듈(텍스트 인식 등)은 막지 않는다
        assertSame(ScannerAvailability.Available, availability(FakePlatform(memory = 1_600_000_000L), checkMemory = false).check())
        assertEquals(
            DocScanFailureReason.PLAY_SERVICES_MISSING,
            reason(availability(FakePlatform(status = ConnectionResult.SERVICE_MISSING)).check()),
        )
        assertEquals(
            DocScanFailureReason.PLAY_SERVICES_UPDATE_REQUIRED,
            reason(availability(FakePlatform(status = ConnectionResult.SERVICE_UPDATING)).install()),
        )
        assertEquals(DocScanFailureReason.UNSUPPORTED_DEVICE, reason(availability(FakePlatform(memory = 1)).install()))
    }

    @Test
    fun `check — Play 서비스 오류는 분류하고 cause 보존`() = runTest {
        val platform = FakePlatform()
        val api = ApiException(Status(46003))
        platform.install.availability = { Tasks.forException(api) }
        val a = availability(platform).check() as ScannerAvailability.Unavailable
        assertEquals(DocScanFailureReason.INSUFFICIENT_STORAGE, a.error.reason)
        assertSame(api, a.error.cause)
        assertTrue(a.toString().startsWith("Unavailable("))
    }

    @Test
    fun `구버전 Play 서비스의 17 — check·install 모두 API 연결 실패`() = runTest {
        val platform = FakePlatform()
        val api = ApiException(Status(17))
        platform.install.availability = { Tasks.forException(api) }
        platform.install.install = { Tasks.forException(api) }
        for (a in listOf(availability(platform).check(), availability(platform).install())) {
            val error = (a as ScannerAvailability.Unavailable).error
            assertEquals(DocScanFailureReason.PLAY_SERVICES_API_UNAVAILABLE, error.reason)
            assertEquals(17, error.statusCode)
            assertSame(api, error.cause)
        }
    }

    @Test
    fun `check 의 ApiException 16 은 취소라 모듈 받기 필요가 아니다`() = runTest {
        val platform = FakePlatform()
        val api = ApiException(Status(CommonStatusCodes.CANCELED))
        platform.install.availability = { Tasks.forException(api) }
        val error = (availability(platform).check() as ScannerAvailability.Unavailable).error
        assertEquals(DocScanFailureReason.UNKNOWN, error.reason)
        assertEquals(16, error.statusCode)
        assertSame(api, error.cause)
    }

    @Test
    fun `check — 코루틴 취소는 그대로 전파`() = runTest {
        val platform = FakePlatform()
        val never = TaskCompletionSource<ModuleAvailabilityResponse>()
        platform.install.availability = { never.task }
        val job = async(start = CoroutineStart.UNDISPATCHED) { availability(platform).check() }
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun `install — 이미 설치돼 있으면 바로 Available, 리스너 해제`() = runTest {
        val platform = FakePlatform()
        assertSame(ScannerAvailability.Available, availability(platform).install())
        assertEquals(platform.install.listeners, platform.install.unregistered)
        assertNotNull(platform.install.lastRequest!!.listenerExecutor)
        assertEquals(listOf(api), platform.install.lastRequest!!.apis)
    }

    @Test
    fun `install — 진행 상황을 알리고 완료되면 Available`() = runTest {
        val platform = FakePlatform()
        val started = TaskCompletionSource<ModuleInstallResponse>()
        platform.install.install = { started.task }
        val progress = mutableListOf<DownloadProgress>()
        val job = async(start = CoroutineStart.UNDISPATCHED) { availability(platform).install { progress += it } }
        started.setResult(ModuleInstallResponse(7, false))
        val listener = platform.install.listeners.single()
        listener.onInstallStatusUpdated(update(InstallState.STATE_PENDING))
        listener.onInstallStatusUpdated(update(InstallState.STATE_DOWNLOADING, 50, 200))
        listener.onInstallStatusUpdated(update(InstallState.STATE_INSTALLING, 200, 200))
        listener.onInstallStatusUpdated(update(InstallState.STATE_COMPLETED, 200, 200))
        // 끝난 뒤의 알림은 무시한다
        listener.onInstallStatusUpdated(update(InstallState.STATE_FAILED))
        assertSame(ScannerAvailability.Available, job.await())
        assertEquals(
            listOf(
                DownloadProgress(DownloadState.PENDING, 0, 0),
                DownloadProgress(DownloadState.DOWNLOADING, 50, 200),
                DownloadProgress(DownloadState.INSTALLING, 200, 200),
                DownloadProgress(DownloadState.COMPLETED, 200, 200),
                DownloadProgress(DownloadState.FAILED, 0, 0),
            ),
            progress,
        )
        assertEquals(0.25f, progress[1].fraction)
        assertNull(progress[0].fraction)
        assertEquals(listOf(listener), platform.install.unregistered)
    }

    @Test
    fun `install — 실패 상태 코드별 분류`() = runTest {
        val cases = listOf(
            46003 to DocScanFailureReason.INSUFFICIENT_STORAGE,
            ConnectionResult.NETWORK_ERROR to DocScanFailureReason.NETWORK_ERROR,
            0 to DocScanFailureReason.MODULE_DOWNLOAD_FAILED,
            ConnectionResult.INTERNAL_ERROR to DocScanFailureReason.MODULE_DOWNLOAD_FAILED,
            46002 to DocScanFailureReason.MODULE_DOWNLOAD_FAILED,
            CommonStatusCodes.CANCELED to DocScanFailureReason.MODULE_DOWNLOAD_FAILED,
        )
        for ((code, expected) in cases) {
            val platform = FakePlatform()
            platform.install.install = { Tasks.forResult(ModuleInstallResponse(7, false)) }
            val job = async(start = CoroutineStart.UNDISPATCHED) { availability(platform).install() }
            platform.install.listeners.single().onInstallStatusUpdated(update(InstallState.STATE_FAILED, error = code))
            val result = job.await() as ScannerAvailability.Unavailable
            assertEquals("code $code", expected, result.error.reason)
            assertEquals(code, result.error.statusCode)
        }
    }

    @Test
    fun `install — 사용자가 취소하면 MODULE_DOWNLOAD_FAILED`() = runTest {
        val platform = FakePlatform()
        platform.install.install = { Tasks.forResult(ModuleInstallResponse(7, false)) }
        val job = async(start = CoroutineStart.UNDISPATCHED) { availability(platform).install() }
        platform.install.listeners.single().onInstallStatusUpdated(update(InstallState.STATE_CANCELED))
        assertEquals(DocScanFailureReason.MODULE_DOWNLOAD_FAILED, reason(job.await()))
    }

    @Test
    fun `install — 요청 Task 실패·취소`() = runTest {
        val platform = FakePlatform()
        val err = ApiException(Status(CommonStatusCodes.CANCELED))
        platform.install.install = { Tasks.forException(err) }
        val a = availability(platform).install() as ScannerAvailability.Unavailable
        assertEquals(DocScanFailureReason.UNKNOWN, a.error.reason)
        assertSame(err, a.error.cause)
        assertEquals(platform.install.listeners, platform.install.unregistered)

        val tokens = CancellationTokenSource()
        val canceled = TaskCompletionSource<ModuleInstallResponse>(tokens.token)
        tokens.cancel()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        platform.install.install = { canceled.task }
        assertEquals(DocScanFailureReason.UNKNOWN, reason(availability(platform).install()))
    }

    @Test
    fun `install — API 를 만들다 실패하면 분류`() = runTest {
        val boom = IllegalStateException("no context")
        val a = ModuleAvailabilityImpl(FakePlatform(), true) { throw boom }.install() as ScannerAvailability.Unavailable
        assertSame(boom, a.error.cause)
        assertEquals(DocScanFailureReason.UNKNOWN, a.error.reason)
    }

    @Test
    fun `install — 코루틴을 취소하면 리스너를 해제한다`() = runTest {
        val platform = FakePlatform()
        platform.install.install = { Tasks.forResult(ModuleInstallResponse(7, false)) }
        val job = async(start = CoroutineStart.UNDISPATCHED) { availability(platform).install() }
        val listener = platform.install.listeners.single()
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(listener in platform.install.unregistered)
        // 취소 뒤의 완료 알림은 무시된다(재개 없음)
        listener.onInstallStatusUpdated(update(InstallState.STATE_COMPLETED))
    }

    @Test
    fun `설치 단계 번호 변환`() {
        assertEquals(DownloadState.UNKNOWN, InstallState.STATE_UNKNOWN.toDownloadState())
        assertEquals(DownloadState.PENDING, InstallState.STATE_PENDING.toDownloadState())
        assertEquals(DownloadState.DOWNLOADING, InstallState.STATE_DOWNLOADING.toDownloadState())
        assertEquals(DownloadState.PAUSED, InstallState.STATE_DOWNLOAD_PAUSED.toDownloadState())
        assertEquals(DownloadState.INSTALLING, InstallState.STATE_INSTALLING.toDownloadState())
        assertEquals(DownloadState.COMPLETED, InstallState.STATE_COMPLETED.toDownloadState())
        assertEquals(DownloadState.FAILED, InstallState.STATE_FAILED.toDownloadState())
        assertEquals(DownloadState.CANCELED, InstallState.STATE_CANCELED.toDownloadState())
        assertEquals(DownloadState.UNKNOWN, 99.toDownloadState())
    }

    @Test
    fun `진행률 경계와 값 동등성`() {
        assertEquals(1f, DownloadProgress(DownloadState.DOWNLOADING, 300, 200).fraction)
        assertEquals(0f, DownloadProgress(DownloadState.DOWNLOADING, -1, 200).fraction)
        assertNull(DownloadProgress(DownloadState.DOWNLOADING, 5, -1).fraction)
        val a = DownloadProgress(DownloadState.PAUSED, 1, 2)
        assertEquals(a, DownloadProgress(DownloadState.PAUSED, 1, 2))
        assertEquals(a.hashCode(), DownloadProgress(DownloadState.PAUSED, 1, 2).hashCode())
        assertTrue(a != DownloadProgress(DownloadState.PAUSED, 1, 3))
        assertTrue(a != DownloadProgress(DownloadState.PAUSED, 2, 2))
        assertTrue(a != DownloadProgress(DownloadState.PENDING, 1, 2))
        assertTrue(a != Any())
        assertEquals("DownloadProgress(PAUSED, 1/2)", a.toString())
    }

    @Test
    fun `공개 팩토리`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNotNull(ModuleAvailability.documentScanner(context))
        assertNotNull(ModuleAvailability.of(context, api))
    }
}
