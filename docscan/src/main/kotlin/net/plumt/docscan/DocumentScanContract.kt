package net.plumt.docscan

import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import net.plumt.docscan.internal.ScanResultReader
import net.plumt.docscan.internal.parseScanResult

/**
 * 스캐너 화면 결과를 [DocumentScanResult] 로 바꾸는 액티비티 결과 계약.
 *
 * 보통은 [registerDocumentScanner]·`rememberDocumentScanLauncher`(docscan-compose) 가 감싸 쓴다. 직접 쓰려면
 * `registerForActivityResult(DocumentScanContract())` 로 등록하고 [ScanIntentSource] 가 준 요청을 launch 한다.
 * 액티비티 결과 등록부가 결과를 보관하므로 화면이 떠 있는 동안 회전·프로세스 종료가 일어나도 다시 등록된 콜백으로 전달된다.
 */
public class DocumentScanContract internal constructor(
    private val reader: ScanResultReader,
) : ActivityResultContract<IntentSenderRequest, DocumentScanResult>() {

    public constructor() : this(ScanResultReader.Gms)

    private val delegate = ActivityResultContracts.StartIntentSenderForResult()

    override fun createIntent(context: Context, input: IntentSenderRequest): Intent = delegate.createIntent(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): DocumentScanResult = parseScanResult(resultCode, intent, reader)
}
