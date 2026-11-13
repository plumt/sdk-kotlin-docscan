package net.plumt.docscan.internal

import android.app.ActivityManager
import android.content.Context
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import net.plumt.docscan.DocumentScanOptions

/** Play 서비스 정적 진입점을 가린다. 판단 로직은 이 인터페이스의 가짜로 시험한다. */
internal interface ScannerPlatform {
    /** 기기 전체 RAM(바이트). 모르면 0 */
    fun totalMemoryBytes(): Long

    /** `GoogleApiAvailability.isGooglePlayServicesAvailable` 결과 */
    fun playServicesStatus(): Int

    fun scanner(options: DocumentScanOptions): GmsDocumentScanner

    fun moduleInstall(): ModuleInstallClient
}

/**
 * 실제 기기 구현. 정적 호출만 있다(Kover 제외 — Robolectric 에 Play 서비스가 없다).
 *
 * [context] 는 처음 쓸 때 읽는다. 액티비티 필드 초기화에서 만들면 그때는 아직 attach 전이라
 * `applicationContext` 가 NPE 를 던진다.
 */
internal class AndroidScannerPlatform(private val context: Context) : ScannerPlatform {
    private val app: Context by lazy(LazyThreadSafetyMode.NONE) { context.applicationContext }

    override fun totalMemoryBytes(): Long {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    override fun playServicesStatus(): Int = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app)

    override fun scanner(options: DocumentScanOptions): GmsDocumentScanner = GmsDocumentScanning.getClient(options.toGms())

    override fun moduleInstall(): ModuleInstallClient = ModuleInstall.getClient(app)
}
