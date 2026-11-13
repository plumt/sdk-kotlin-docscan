package net.plumt.docscan.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.plumt.docscan.DocScanException
import net.plumt.docscan.DocScanFailureReason
import net.plumt.docscan.ScanPage
import net.plumt.docscan.ScannedDocument
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class StorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bytes = "%PDF-1.4 test".toByteArray()

    @Before
    fun setUp() {
        FakeMediaProvider.reset()
        // FileProvider 는 경로표를 정적으로 캐시하는데 Robolectric 은 시험마다 데이터 폴더가 바뀐다
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
    }

    private fun saver(sub: String? = null) = PdfSaver.downloads(context, sub, UnconfinedTestDispatcher())

    // --- 이름 규칙 ---

    @Test
    fun `표시 이름 — pdf 확장자를 붙이고 금지 문자·빈 값·긴 이름은 IAE`() {
        assertEquals("영수증.pdf", FileNames.pdfName(" 영수증 "))
        assertEquals("a.PDF", FileNames.pdfName("a.PDF"))
        assertEquals("a.txt.pdf", FileNames.pdfName("a.txt"))
        assertEquals("x".repeat(120) + ".pdf", FileNames.pdfName("x".repeat(120)))
        for (bad in listOf("", "   ", ".", "..", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\u0000b", "a\nb", "x".repeat(121))) {
            assertThrows("'$bad'", IllegalArgumentException::class.java) { FileNames.pdfName(bad) }
        }
    }

    @Test
    fun `하위 폴더 — 슬래시 정리, 상위 이동 금지`() {
        assertNull(FileNames.subdirectory(null))
        assertNull(FileNames.subdirectory(""))
        assertNull(FileNames.subdirectory(" / "))
        assertEquals("Scans", FileNames.subdirectory("/Scans/"))
        assertEquals("Scans/2026", FileNames.subdirectory("Scans/2026"))
        for (bad in listOf("a//b", "../x", "a/../b", "./a", "a:b", "a/b*", "x".repeat(121))) {
            assertThrows("'$bad'", IllegalArgumentException::class.java) { FileNames.subdirectory(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { PdfSaver.downloads(context, "..") }
    }

    @Test
    fun `같은 이름이 있으면 번호`() {
        val taken = setOf("a.pdf", "a (1).pdf", "noext", "noext (1)")
        assertEquals("b.pdf", FileNames.unique("b.pdf") { it in taken })
        assertEquals("a (2).pdf", FileNames.unique("a.pdf") { it in taken })
        assertEquals("noext (2)", FileNames.unique("noext") { it in taken })
        assertEquals(".hidden (1)", FileNames.unique(".hidden") { it == ".hidden" })
    }

    // --- API 29+: MediaStore Downloads ---

    @Test
    @Config(sdk = [29, 34])
    fun `다운로드 저장 — 보류 상태로 쓰고 공개, 상대 경로·형식·크기`() = runTest {
        val saved = saver("Scans").save("영수증") { it.write(bytes) }
        assertEquals(SaveLocation.DOWNLOADS, saved.location)
        assertEquals("영수증.pdf", saved.displayName)
        assertEquals(bytes.size.toLong(), saved.sizeBytes)
        assertNull(saved.file)
        val row = FakeMediaProvider.rows.values.single()
        assertEquals("Download/Scans", row.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("application/pdf", row.values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(0, row.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertArrayEquals(bytes, row.file.readBytes())
        assertTrue(saved.uri.toString().startsWith("content://media/external_primary/downloads/"))
        assertTrue(saved.toString().contains("DOWNLOADS"))
        // 하위 폴더 없이
        saver().save("b") { it.write(1) }
        assertEquals("Download", FakeMediaProvider.rows.values.last().values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
    }

    @Test
    @Config(sdk = [29])
    fun `다운로드 저장 — MediaStore 가 바꾼 이름을 돌려준다`() = runTest {
        FakeMediaProvider.renameOnInsert = "영수증 (1).pdf"
        assertEquals("영수증 (1).pdf", saver().save("영수증") { it.write(1) }.displayName)
    }

    @Test
    @Config(sdk = [29])
    fun `다운로드 저장 실패 — 항목을 지우고 분류`() = runTest {
        // 쓰기 블록의 IO 실패
        val e1 = runCatching { saver().save("a") { throw IOException("pipe") } }.exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.IO_ERROR, e1.reason)
        assertEquals(1, FakeMediaProvider.deleted.size)
        assertTrue(FakeMediaProvider.rows.isEmpty())
        // 저장 공간 부족
        val e2 = runCatching { saver().save("a") { throw IOException("write failed: ENOSPC") } }.exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.INSUFFICIENT_STORAGE, e2.reason)
        // 블록이 던진 SDK 예외·다른 예외는 그대로
        val own = DocScanException(DocScanFailureReason.PDF_FAILED, "x")
        assertSame(own, runCatching { saver().save("a") { throw own } }.exceptionOrNull())
        val iae = IllegalArgumentException("x")
        assertSame(iae, runCatching { saver().save("a") { throw iae } }.exceptionOrNull())
        assertTrue(FakeMediaProvider.rows.isEmpty())
    }

    @Test
    @Config(sdk = [29])
    fun `다운로드 저장 실패 — provider 단계별`() = runTest {
        FakeMediaProvider.insertReturnsNull = true
        assertEquals(DocScanFailureReason.IO_ERROR, (runCatching { saver().save("a") {} }.exceptionOrNull() as DocScanException).reason)
        FakeMediaProvider.insertReturnsNull = false

        FakeMediaProvider.openThrows = SecurityException("denied")
        val e = runCatching { saver().save("a") {} }.exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.IO_ERROR, e.reason)
        assertTrue(FakeMediaProvider.rows.isEmpty())
        FakeMediaProvider.openThrows = null

        FakeMediaProvider.updateThrows = IllegalStateException("volume gone")
        assertEquals(DocScanFailureReason.IO_ERROR, (runCatching { saver().save("a") {} }.exceptionOrNull() as DocScanException).reason)
        FakeMediaProvider.updateThrows = UnsupportedOperationException("ro")
        assertEquals(DocScanFailureReason.IO_ERROR, (runCatching { saver().save("a") {} }.exceptionOrNull() as DocScanException).reason)
        assertTrue(FakeMediaProvider.rows.isEmpty())
    }

    @Test
    @Config(sdk = [29])
    fun `다운로드 저장 중 취소 — 항목을 지우고 취소 전파`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val job = async(start = CoroutineStart.UNDISPATCHED) { saver().save("a") { gate.await() } }
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(FakeMediaProvider.rows.isEmpty())
        assertEquals(1, FakeMediaProvider.deleted.size)
    }

    // --- API 28 이하: 앱 전용 폴더 ---

    @Test
    @Config(sdk = [24, 28])
    fun `API 28 이하는 앱 전용 폴더 — 권한 없이, 이름 충돌은 번호`() = runTest {
        val first = saver("Scans").save("문서") { it.write(bytes) }
        assertEquals(SaveLocation.APP_SPECIFIC, first.location)
        val file = first.file!!
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("문서.pdf", file.name)
        assertEquals("Scans", file.parentFile!!.name)
        assertTrue(file.path.startsWith(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)!!.path))
        assertEquals("content", first.uri.scheme)
        val second = saver("Scans").save("문서") { it.write(bytes) }
        assertEquals("문서 (1).pdf", second.displayName)
        assertTrue(FakeMediaProvider.rows.isEmpty())
    }

    @Test
    @Config(sdk = [28])
    fun `API 28 이하 실패 — 임시 파일을 남기지 않는다`() = runTest {
        val e = runCatching { saver().save("x") { it.write(1); throw IOException("disk") } }.exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.IO_ERROR, e.reason)
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)!!
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        // 폴더를 만들 수 없는 경우(같은 이름의 파일이 있다)
        File(dir, "blocked").writeText("x")
        val e2 = runCatching { saver("blocked").save("x") {} }.exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.IO_ERROR, e2.reason)
    }

    // --- 저장한 PDF 공유: README 첫 사용법이 모든 지원 API 에서 동작해야 한다 ---

    private fun assertSharesContentUri(saved: SavedPdf) {
        val chooser = PdfSharing.shareIntent(saved.uri, saved.displayName)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        assertEquals("content", send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!.scheme)
        assertEquals("content", send.clipData!!.getItemAt(0).uri.scheme)
    }

    @Test
    @Config(sdk = [24, 26, 28])
    fun `API 28 이하 저장 — uri 는 FileProvider content 이고 선택 화면으로 공유, 읽을 수 있다`() = runTest {
        val saved = saver("Scans/2026").save("영수증 2026 11") { it.write(bytes) }
        assertEquals(PdfSharing.authority(context), saved.uri.authority)
        assertEquals("content", saved.uri.scheme)
        assertEquals("영수증 2026 11.pdf", saved.file!!.name)
        assertSharesContentUri(saved)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() })
    }

    @Test
    @Config(sdk = [29, 34])
    fun `API 29+ 저장 — MediaStore content 를 선택 화면으로 공유`() = runTest {
        val saved = saver().save("문서") { it.write(bytes) }
        assertEquals("content", saved.uri.scheme)
        assertSharesContentUri(saved)
    }

    @Test
    @Config(sdk = [28])
    fun `API 28 이하 저장 — 외부 저장소가 없으면 내부 폴더도 content`() = runTest {
        val noExternal = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File? = null
        }
        val saved = DownloadsPdfSaver(noExternal, null, UnconfinedTestDispatcher(testScheduler)).save("내부") { it.write(bytes) }
        assertEquals(File(context.filesDir, "Documents/내부.pdf").canonicalFile, saved.file!!.canonicalFile)
        assertEquals(PdfSharing.authority(context), saved.uri.authority)
        assertSharesContentUri(saved)
    }

    @Test
    @Config(sdk = [28])
    fun `API 28 이하 저장 — provider 를 찾지 못하면 파일을 남기지 않고 IO_ERROR`() = runTest {
        val noProvider = object : ContextWrapper(context) {
            override fun getPackageName(): String = "other.package"
        }
        val e = runCatching {
            DownloadsPdfSaver(noProvider, "np", UnconfinedTestDispatcher(testScheduler)).save("x") { it.write(bytes) }
        }.exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.IO_ERROR, e.reason)
        assertFalse(File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "np/x.pdf").exists())
    }

    @Test
    @Config(sdk = [28])
    fun `API 28 이하 — 노출 범위 밖 파일은 Uri 로 만들지 않는다`() {
        val e = assertThrows(DocScanException::class.java) { PdfSharing.savedFileUri(context, File(context.filesDir, "secret.pdf")) }
        assertEquals(DocScanFailureReason.IO_ERROR, e.reason)
    }

    @Test
    @Config(sdk = [29, 34])
    fun `API 29+ — 앱 전용 저장 폴더는 노출하지 않는다`() {
        val inDocuments = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "x.pdf")
        val e = assertThrows(DocScanException::class.java) { PdfSharing.savedFileUri(context, inDocuments) }
        assertEquals(DocScanFailureReason.IO_ERROR, e.reason)
    }

    @Test
    fun `공유 — file Uri 는 IAE, 대소문자 무관`() {
        assertThrows(IllegalArgumentException::class.java) { PdfSharing.shareIntent(Uri.fromFile(File(context.filesDir, "a.pdf"))) }
        assertThrows(IllegalArgumentException::class.java) { PdfSharing.shareIntent(Uri.parse("FILE:///sdcard/a.pdf")) }
    }

    // --- 공유 ---

    @Test
    fun `공유 — 공유 폴더 파일을 FileProvider 로, 읽기 권한만`() {
        val file = PdfSharing.newShareFile(context, "보낼 문서").apply { writeBytes(bytes) }
        assertEquals("보낼 문서.pdf", file.name)
        assertEquals(PdfSharing.shareDirectory(context).canonicalFile, file.parentFile!!.canonicalFile)
        val chooser = PdfSharing.shareIntent(context, file, "공유")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals(context.packageName + ".plumt.docscan.files", uri.authority)
        assertEquals(PdfSharing.authority(context), uri.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals("보낼 문서.pdf", send.getStringExtra(Intent.EXTRA_TITLE))
        // 같은 이름이면 번호
        assertEquals("보낼 문서 (1).pdf", PdfSharing.newShareFile(context, "보낼 문서").name)
    }

    @Test
    fun `공유 — 공유 폴더 밖·없는 파일은 IAE`() {
        val outside = File(context.filesDir, "secret.pdf").apply { writeText("x") }
        assertThrows(IllegalArgumentException::class.java) { PdfSharing.shareIntent(context, outside) }
        val sneaky = File(PdfSharing.shareDirectory(context), "../../files/secret.pdf")
        assertThrows(IllegalArgumentException::class.java) { PdfSharing.shareIntent(context, sneaky) }
        assertThrows(IllegalArgumentException::class.java) { PdfSharing.shareIntent(context, File(PdfSharing.shareDirectory(context), "none.pdf")) }
        assertThrows(IllegalArgumentException::class.java) { PdfSharing.newShareFile(context, "a/b") }
    }

    @Test
    fun `공유 — MediaStore Uri 판, 제목 없이`() {
        val uri = Uri.parse("content://media/external/downloads/5")
        val chooser = PdfSharing.shareIntent(uri)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION")
        assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertFalse(send.hasExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun `공유용 복사와 정리`() = runTest {
        val src = File(context.filesDir, "doc.pdf").apply { writeBytes(bytes) }
        val copy = PdfSharing.copyForSharing(context, Uri.fromFile(src), "doc", UnconfinedTestDispatcher(testScheduler))
        assertArrayEquals(bytes, copy.readBytes())
        // FileProvider 는 경로 설정을 프로세스 단위로 캐시한다 — 시험마다 데이터 폴더가 바뀌므로 Uri 변환은 한 시험에서만 확인한다
        val missing = runCatching {
            PdfSharing.copyForSharing(context, Uri.fromFile(File(context.filesDir, "none.pdf")), "x", UnconfinedTestDispatcher(testScheduler))
        }.exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.IO_ERROR, missing.reason)
        assertFalse(File(PdfSharing.shareDirectory(context), "x.pdf").exists())
        assertEquals(1, PdfSharing.clearShared(context, UnconfinedTestDispatcher(testScheduler)))
        assertEquals(0, PdfSharing.clearShared(context, UnconfinedTestDispatcher(testScheduler)))
    }

    @Test
    fun `공유용 복사 — 권한 거부는 IO_ERROR`() = runTest {
        Robolectric.setupContentProvider(net.plumt.docscan.pdf.PdfComposerTest.DenyingProvider::class.java, "deny.share")
        val e = runCatching {
            PdfSharing.copyForSharing(context, Uri.parse("content://deny.share/a"), "a", UnconfinedTestDispatcher(testScheduler))
        }.exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.IO_ERROR, e.reason)
        assertTrue(e.cause is SecurityException)
    }

    // --- 페이지 보관 ---

    @Test
    fun `페이지 보관 — 캐시 밖으로 복사, 보관소 안만 지운다`() = runTest {
        val cache = File(context.cacheDir, "scan").apply { mkdirs() }
        val p1 = File(cache, "1.jpg").apply { writeBytes(byteArrayOf(1)) }
        val p2 = File(cache, "2.jpg").apply { writeBytes(byteArrayOf(2)) }
        var n = 0
        val dir = File(context.filesDir, "plumt_docscan/test")
        val store = FileScanPageStore(context, dir, UnconfinedTestDispatcher(testScheduler)) { "id${n++}" }
        val kept = store.keep(ScannedDocument(listOf(ScanPage(Uri.fromFile(p1)), ScanPage(Uri.fromFile(p2))), null))
        assertEquals(listOf("id0.jpg", "id1.jpg"), kept.map { File(it.uri.path!!).name })
        assertArrayEquals(byteArrayOf(2), File(kept[1].uri.path!!).readBytes())
        // 원본(캐시)이 지워져도 보관본은 남는다
        cache.deleteRecursively()
        assertTrue(File(kept[0].uri.path!!).exists())
        // 보관소 밖·다른 스킴은 지우지 않는다
        val outside = File(context.filesDir, "keep.jpg").apply { writeText("x") }
        assertEquals(1, store.delete(listOf(kept[0], ScanPage(Uri.fromFile(outside)), ScanPage(Uri.parse("content://x/1")), ScanPage(Uri.parse("file:")))))
        assertTrue(outside.exists())
        assertEquals(1, store.clear())
        assertEquals(0, store.clear())
    }

    @Test
    fun `페이지 보관 실패 — 이번에 복사한 것을 지우고 분류`() = runTest {
        val good = File(context.cacheDir, "g.jpg").apply { writeBytes(byteArrayOf(1)) }
        val dir = File(context.filesDir, "plumt_docscan/fail")
        var n = 0
        val store = FileScanPageStore(context, dir, UnconfinedTestDispatcher(testScheduler)) { "f${n++}" }
        val e = runCatching {
            store.keep(ScannedDocument(listOf(ScanPage(Uri.fromFile(good)), ScanPage(Uri.fromFile(File(context.cacheDir, "none.jpg")))), null))
        }.exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.IO_ERROR, e.reason)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        // 폴더를 만들 수 없음
        val blocked = File(context.filesDir, "plumt_docscan/blockedfile").apply { parentFile!!.mkdirs(); writeText("x") }
        val e2 = runCatching {
            FileScanPageStore(context, blocked, UnconfinedTestDispatcher(testScheduler)) { "z" }.keep(ScannedDocument(listOf(ScanPage(Uri.fromFile(good))), null))
        }.exceptionOrNull() as DocScanException
        assertEquals(DocScanFailureReason.IO_ERROR, e2.reason)
        // 권한 거부
        Robolectric.setupContentProvider(net.plumt.docscan.pdf.PdfComposerTest.DenyingProvider::class.java, "deny.store")
        val e3 = runCatching { store.keep(ScannedDocument(listOf(ScanPage(Uri.parse("content://deny.store/a"))), null)) }.exceptionOrNull() as DocScanException
        assertTrue(e3.cause is SecurityException)
    }

    @Test
    fun `페이지 보관소 이름 검사와 기본 생성`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { ScanPageStore.files(context, "a/b") }
        assertThrows(IllegalArgumentException::class.java) { ScanPageStore.files(context, "..") }
        assertThrows(IllegalArgumentException::class.java) { ScanPageStore.files(context, "") }
        val store = ScanPageStore.files(context, "receipts", UnconfinedTestDispatcher(testScheduler))
        val src = File(context.cacheDir, "s.jpg").apply { writeBytes(byteArrayOf(9)) }
        val kept = store.keep(ScannedDocument(listOf(ScanPage(Uri.fromFile(src))), null)).single()
        assertTrue(kept.uri.path!!.contains("/plumt_docscan/receipts/"))
        assertEquals(36 + 4, File(kept.uri.path!!).name.length)
    }
}
