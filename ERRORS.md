# 오류와 실패 신호

세 모듈 모두 같은 규칙이다. 틀린 인자는 `IllegalArgumentException`(받은 값과 허용 범위를 메시지에 담는다), 그 밖의 실패는 `DocScanException` 하나다.
분기는 `DocScanException.reason`(`DocScanFailureReason`)으로 한다 — 메시지 문구는 바뀔 수 있다. 원인 예외는 `cause`, 외부 상태 코드는 `statusCode` 에 남는다.
코루틴 취소는 `CancellationException` 그대로 전파하며 감싸지 않는다. 조용한 대체값(실패를 숨기고 빈 결과를 돌려주기)은 없다.

실패가 오는 길은 셋이다.

| 어디서 | 어떻게 |
|---|---|
| 스캔(`DocumentScanLauncher.launch` → 콜백) | `DocumentScanResult.Failure(error)` |
| 사전 확인(`ModuleAvailability.check`·`install`) | `ScannerAvailability.Unavailable(error)` — 던지지 않는다 |
| suspend 함수(`PdfComposer.compose`·`PdfSaver.save`·`PdfSharing.copyForSharing`·`ScanPageStore.keep`·`KoreanTextRecognizer.recognize`) | 던진다 |

## DocScanFailureReason

| 분류 | 언제 | `statusCode` | 호출부가 할 일 |
|---|---|---|---|
| `PLAY_SERVICES_MISSING` | Play 서비스 없음·꺼짐·정품 아님(`ConnectionResult` 1·3·9), 스캐너 화면 액티비티를 찾지 못함 | 상태 코드 | `showPlayServicesResolution` 이 true 면 Play 가 안내한다. 아니면 기능을 숨긴다 |
| `PLAY_SERVICES_UPDATE_REQUIRED` | Play 서비스 구버전·업데이트 중(2·18), ML Kit 207 | 상태 코드 | `showPlayServicesResolution` 으로 업데이트를 안내한다 |
| `PLAY_SERVICES_API_UNAVAILABLE` | Play 서비스는 정상이라고 답했지만 모듈 확인·설치 API 에 연결하지 못함(`ApiException` 17, `CommonStatusCodes.API_NOT_CONNECTED`). Play 서비스 20.24·21.45 에서 관측. 공식 의미에는 다른 원인도 있다 | 17 | `showPlayServicesResolution` 으로 업데이트를 안내한다. 업데이트 뒤에도 같으면 기능을 숨긴다 |
| `MODULE_DOWNLOAD_REQUIRED` | 스캐너 모듈·인식 모델이 아직 없음(ML Kit 14 `UNAVAILABLE`) | 코드 | `ModuleAvailability.install()` 후 다시 시도한다 |
| `MODULE_DOWNLOAD_FAILED` | 내려받기 실패·취소(설치 리스너의 상태 16 포함), 모듈을 찾지 못함(46000·46001·46002), 모델 해시 불일치(100·102) | 코드 | 네트워크를 확인하고 다시 시도한다 |
| `INSUFFICIENT_STORAGE` | 모듈 설치 공간 부족(46003, ML Kit 101), 파일 쓰기 중 `ENOSPC` | 코드 또는 없음 | 공간 확보를 안내한다 |
| `UNSUPPORTED_DEVICE` | 전체 RAM 1.7GB(1,700,000,000 바이트) 미만, ML Kit 18 `UNSUPPORTED` | ML Kit 코드 또는 없음 | 기능을 숨긴다. 다시 시도해도 같다 |
| `NETWORK_ERROR` | 모듈을 받는 중 네트워크 오류(`ConnectionResult` 7, ML Kit 17) | 코드 | 연결을 확인하고 다시 시도한다 |
| `RESULT_MISSING` | 스캐너가 성공으로 끝났는데 결과 인텐트·페이지·PDF 가 없거나 읽을 수 없음 | 없음 | 다시 스캔한다 |
| `IMAGE_DECODE_FAILED` | 페이지 이미지 크기를 읽지 못함·디코딩 실패(손상·빈 파일·지원하지 않는 형식) | 없음 | 해당 페이지를 빼거나 다시 스캔한다 |
| `IO_ERROR` | 파일·provider 열기·읽기·쓰기 실패, 권한 없음·만료(`SecurityException`), MediaStore 가 항목·스트림을 주지 않음, API 28 이하 저장에서 공유 provider 를 찾지 못함(앱 매니페스트에서 뺌), provider 의 런타임 예외 | 없음 | `cause` 를 본다. 캐시가 지워졌으면 `ScanPageStore` 로 보관했는지 확인한다 |
| `PDF_FAILED` | PDF 인코더가 시작·페이지 추가·마무리에서 실패(직접 만든 `PdfEncoder` 의 예외 포함) | 없음 | `cause` 를 본다. 인코더를 바꿔 본다 |
| `TEXT_RECOGNITION_FAILED` | 텍스트 인식 엔진 실패(ML Kit 의 그 밖의 코드, 직접 만든 `PageTextRecognizer` 의 예외) | ML Kit 코드 또는 없음 | 텍스트 층 없이 다시 만든다 |
| `OUT_OF_MEMORY` | 디코딩·인식·인코딩 중 `OutOfMemoryError` | 없음 | `PdfOptions.imageDpi` 를 낮춘다 |
| `UNKNOWN` | 위에 들지 않음(알 수 없는 결과 코드, Play 서비스 작업 취소(`ApiException` 16, `CommonStatusCodes.CANCELED`), 그 밖의 예외) | 있으면 코드 | `statusCode`·`cause` 를 기록한다 |

`statusCode` 는 출처가 여럿이다 — Play 서비스 `ConnectionResult`, `ApiException` 의 `CommonStatusCodes`, 모듈 설치 상태 코드(`ModuleInstallStatusCodes`), ML Kit `MlKitException` 코드, 액티비티 결과 코드.
`Failure` 를 Parcel 로 저장하면 `reason`·메시지·`statusCode` 만 남고 `cause` 는 사라진다.

### 그대로 전달하는 것

- 직접 만든 `PageTextRecognizer`·`PdfEncoder` 가 던진 `DocScanException`·`IllegalArgumentException` 은 감싸지 않는다
- `PdfSaver.save` 의 쓰기 블록이 던진 예외는 그대로 전달한다. 단 `IOException` 은 `IO_ERROR`·`INSUFFICIENT_STORAGE` 로 분류한다. 어느 쪽이든 쓰다 만 파일·MediaStore 항목은 지운다

## IllegalArgumentException

| 언제 | 호출부가 할 일 |
|---|---|
| `DocumentScanOptions` 의 `pageLimit` 이 1 미만, `outputs` 가 빔 | 값을 바로잡는다 |
| `PdfOptions` 의 `marginPt` 가 0..144 밖·유한수 아님, `imageDpi` 가 50..600 밖 | 값을 바로잡는다 |
| `PageSize.Custom` 너비·높이가 72..14400 pt 밖·유한수 아님 | 값을 바로잡는다 |
| `PdfEncoder.compact` 의 `jpegQuality` 가 1..100 밖 | 값을 바로잡는다 |
| `PdfComposer.compose` 의 페이지가 0장 또는 500장 초과 | 나눠서 만든다 |
| `PageList` 의 위치가 범위 밖, `reorder` 가 순열이 아님 | 위치를 바로잡는다 |
| 표시 이름이 비었음·120자 초과·`. ..`·`/ \ : * ? " < > |`·제어 문자, 하위 폴더에 `.`·`..`·빈 조각·금지 문자 | 이름을 바로잡는다 |
| `ScanPageStore.files` 의 이름이 폴더 하나가 아님 | 이름을 바로잡는다 |
| `PdfSharing.shareIntent(context, file)` 의 파일이 공유 폴더 밖이거나 없음 | `copyForSharing`·`newShareFile` 을 쓴다 |
| `PdfSharing.shareIntent(uri)` 의 Uri 가 `file:` | `SavedPdf.uri` 를 넘기거나 `copyForSharing` 을 먼저 쓴다 |
| `TextLine` 좌표가 유한수가 아니거나 오른쪽·아래가 왼쪽·위보다 작음 | 인식기 구현을 고친다 |
| `KoreanTextRecognizer.recognize` 에 recycle 된 Bitmap | 살아 있는 Bitmap 을 넘긴다 |

## 값으로 알리는 신호

| 신호 | 의미 |
|---|---|
| `DocumentScanResult.Cancelled` | 사용자가 스캐너 화면을 닫았다. 실패가 아니다 |
| `DocumentScanLauncher.launch()` 가 false | 이미 진행 중(준비 중이거나 화면이 떠서 결과 대기 — `isInProgress`)이거나 액티비티가 없음·끝남. 이번 호출로는 화면을 열지 않았고 결과도 오지 않는다(진행 중이던 요청의 결과는 한 번 온다) |
| 준비 중 액티비티가 끝남 | 화면을 열지 않고 결과도 보내지 않는다 |
| `ScannerAvailability.DownloadRequired` | 지원 기기지만 모듈이 아직 없다(`check` 만 돌려준다) |
| `ScannedDocument.pages` 가 빔 | `outputs` 에 JPEG 를 넣지 않았다 — 합치기·PDF 재생성 불가 |
| `SavedPdf.location == APP_SPECIFIC` | API 28 이하 — 공용 다운로드가 아니라 앱 전용 폴더에 저장했다 |
| `SavedPdf.displayName` 이 요청과 다름 | 같은 이름이 있어 번호가 붙었다 |
| `PdfReport.textLineCount == 0` | 인식기를 주지 않았거나 글자를 찾지 못했다 |
| `ScanPageStore.delete` 의 반환값이 요청 수보다 작음 | 보관소 밖 URI 는 지우지 않았다 |
