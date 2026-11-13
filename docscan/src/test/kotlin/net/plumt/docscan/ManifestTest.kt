package net.plumt.docscan

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import net.plumt.docscan.storage.DocScanFileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ManifestTest {
    private fun rootFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return File(requireNotNull(dir), path)
    }

    private fun moduleFile(path: String): File = rootFile("docscan/$path")

    @Test
    fun `SDK 매니페스트는 권한을 선언하지 않는다`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("<uses-permission"))
        assertFalse(manifest.contains("<permission"))
    }

    @Test
    fun `공유 provider — 앱 패키지 기반 authority, 내보내지 않음, 권한 위임`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getProviderInfo(
            ComponentName(context, DocScanFileProvider::class.java), PackageManager.GET_META_DATA,
        )
        assertEquals(context.packageName + ".plumt.docscan.files", info.authority)
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
    }

    @Test
    fun `API 29+ 공유 경로는 캐시 안 전용 폴더 하나`() {
        val xml = moduleFile("src/main/res/xml-v29/plumt_docscan_file_paths.xml").readText()
        assertEquals(1, xml.split("<cache-path").size - 1)
        assertTrue(xml.contains("path=\"plumt_docscan/share/\""))
        for (other in listOf("root-path", "external-path", "files-path", "external-files-path", "external-cache-path", "external-media-path")) {
            assertFalse(other, xml.contains("<$other"))
        }
    }

    @Test
    fun `API 28 이하 공유 경로는 공유 폴더와 앱 전용 저장 폴더뿐`() {
        val xml = moduleFile("src/main/res/xml/plumt_docscan_file_paths.xml").readText()
        assertEquals(1, xml.split("<cache-path").size - 1)
        assertEquals(1, xml.split("<external-files-path").size - 1)
        assertEquals(1, xml.split("<files-path").size - 1)
        assertEquals(1, xml.split("path=\"plumt_docscan/share/\"").size - 1)
        assertEquals(2, xml.split("path=\"Documents/\"").size - 1)
        for (other in listOf("root-path", "external-path", "external-cache-path", "external-media-path")) {
            assertFalse(other, xml.contains("<$other"))
        }
    }

    @Test
    fun `세 모듈 모두 minSdk 24 — 스캐너 라이브러리가 API 24 의 Optional 을 쓴다`() {
        for (module in listOf("docscan", "docscan-compose", "docscan-ocr")) {
            val found = Regex("minSdk = ([0-9]+)").find(rootFile("$module/build.gradle.kts").readText())
            assertEquals(module, "24", found?.groupValues?.get(1))
        }
    }
}
