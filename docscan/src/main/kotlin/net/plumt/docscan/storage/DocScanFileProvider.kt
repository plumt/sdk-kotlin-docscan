package net.plumt.docscan.storage

import androidx.core.content.FileProvider

/**
 * 공유용 FileProvider. androidx [FileProvider] 를 그대로 선언하면 앱이 선언한 같은 클래스와 매니페스트 병합에서 충돌하므로
 * 이 SDK 전용 하위 클래스로 둔다. 노출 경로는 캐시 안 `plumt_docscan/share/` 이고, API 28 이하는 앱 전용 저장 폴더(`Documents/`)도 더한다.
 * 내보내지 않는 provider 라 앱이 인텐트로 권한을 준 Uri 만 다른 앱이 읽는다.
 */
public class DocScanFileProvider : FileProvider()
