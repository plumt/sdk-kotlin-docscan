# sdk-kotlin-docscan

안드로이드 문서 스캔 라이브러리. Google Play 서비스의 ML Kit 문서 스캐너(`play-services-mlkit-document-scanner`)가
촬영 화면·갤러리 가져오기·모서리 자동 감지와 자르기·필터를 맡고, 이 라이브러리는 그 위에 앱이 매번 다시 만들던 부분을 더한다.

- 타입 있는 결과(`Success`/`Cancelled`/`Failure`)와 Compose·액티비티 결과 진입점. 회전·프로세스 종료에도 결과가 사라지지 않는다
- 실패 분류(Play 서비스 없음·구버전·API 연결 실패, 모듈 내려받기 필요·실패, 저장 공간 부족, RAM 1.7GB 미만 기기 등)와 사전 확인·미리 내려받기
- 여러 번 스캔한 페이지 합치기·순서 바꾸기·삭제 후 PDF 다시 만들기, 페이지 크기(A4·Letter·원본·임의) 선택
- 다운로드 폴더 저장(권한 없음)과 공유 인텐트
- 선택 모듈: 한국어 텍스트 인식으로 검색 가능한 PDF(보이지 않는 텍스트 층)

| 모듈 | 좌표 | 내용 | 상태 |
|---|---|---|---|
| `docscan` | `net.plumt.docscan:docscan` | 스캔 실행·결과·실패 분류·사전 확인·PDF·저장·공유 | 0.0.1 미발행 |
| `docscan-compose` | `net.plumt.docscan:docscan-compose` | `rememberDocumentScanLauncher` | 0.0.1 미발행 |
| `docscan-ocr` | `net.plumt.docscan:docscan-ocr` | 한국어(라틴 포함) 텍스트 인식 → PDF 텍스트 층 | 0.0.1 미발행 |

```kotlin
dependencies {
    implementation("net.plumt.docscan:docscan:0.0.1")
    implementation("net.plumt.docscan:docscan-compose:0.0.1") // Compose 를 쓰면
    implementation("net.plumt.docscan:docscan-ocr:0.0.1")     // 검색 가능한 PDF 가 필요하면
}
```

## 찍기와 갤러리에서 불러오기

스캐너 화면은 Play 서비스가 띄운다. 카메라로 찍는 화면이 열리고, `allowGalleryImport = true`(기본)이면 같은 화면의 갤러리 버튼으로
이미 찍은 사진을 불러온다. 불러온 사진에도 모서리 감지·자르기가 그대로 적용된다. 스캐너를 갤러리부터 여는 방법은 Play 서비스가 제공하지 않는다.

```kotlin
// Compose
var pages by rememberSaveable { mutableStateOf(PageList.Empty) }
val scan = rememberDocumentScanLauncher(
    options = DocumentScanOptions(allowGalleryImport = true, pageLimit = 20, mode = ScannerMode.FULL),
) { result ->
    when (result) {
        is DocumentScanResult.Success -> pages += result.document          // 여러 번 스캔하면 뒤에 붙는다
        DocumentScanResult.Cancelled -> Unit
        is DocumentScanResult.Failure -> showError(result.error.reason)     // DocScanFailureReason 으로 분기
    }
}
Button(onClick = { scan.launch() }) { Text(stringResource(R.string.scan)) }
```

```kotlin
// 액티비티 — 필드로 등록(onCreate 에서 등록해도 된다. 어느 쪽이든 STARTED 전에)
class ScanActivity : ComponentActivity() {
    private val scanner = registerDocumentScanner(DocumentScanOptions(allowGalleryImport = false)) { result -> /* ... */ }
    fun onScanClick() { scanner.launch() }
}
```

기본 공급원 `ScanIntentSource.gms(this)` 는 `launch()` 할 때 처음 컨텍스트를 읽으므로 attach 전인 필드 초기화에서 만들어도 된다.

한 번에 화면 하나만 연다. 준비 중이거나 화면이 떠서 결과를 기다리는 동안(`isInProgress`) `launch()` 는 아무것도 하지 않고
`false` 를 돌려준다 — 버튼을 빠르게 두 번 눌러도 화면·결과는 하나다. 진행 중 상태는 결과(성공·취소·실패)가 오면 풀리고,
액티비티의 저장된 상태에 보관돼 프로세스 종료 뒤에도 이어진다.

프래그먼트 등 다른 `ActivityResultCaller` 는 `registerDocumentScanner(caller, { activity }, options, ScanIntentSource.gms(context)) { }` 를 쓴다.
`DocumentScanLauncher` 생성자를 직접 쓰면 결과 등록 콜백에서 `dispatchResult(result)` 를 불러야 진행 중 상태가 풀린다.
프로세스 종료 뒤에도 이어 가려면 `onAwaitingResultChange` 로 받은 값을 저장했다가 `restoredAwaitingResult` 로 넘긴다.

### 회전·프로세스 종료

- 스캐너 화면이 떠 있는 동안 회전·프로세스 종료가 일어나도 액티비티 결과 등록부가 결과를 보관해 다시 만들어진 화면의 콜백으로 준다
- `DocumentScanResult`·`PageList`·`ScannedDocument` 는 `Parcelable` 이다. `rememberSaveable`·`SavedStateHandle` 에 그대로 담는다.
  `Failure` 를 저장하면 분류·메시지·코드만 남고 `cause` 는 사라진다
- 스캐너가 준 이미지는 앱 **캐시**에 있어 저장 공간이 부족하면 시스템이 지울 수 있다. 여러 번에 걸쳐 모으는 문서는 받자마자 캐시 밖으로 옮긴다

```kotlin
val store = ScanPageStore.files(context, name = "receipt-draft")
pages += store.keep(result.document)       // filesDir/plumt_docscan/receipt-draft/ 로 복사한 페이지
// 다 쓰면
store.clear()
```

- 준비(Play 서비스 확인·인텐트 요청) 중에 액티비티가 끝나면 화면을 열지 않고 결과도 오지 않는다 — 새 화면에서 다시 누른다

## 사용 가능 여부 확인과 미리 내려받기

스캐너 모듈은 처음 쓸 때 Play 서비스가 내려받는다. 문서 메뉴에 들어갈 때 미리 받아 두면 버튼을 눌렀을 때 기다리지 않는다.

```kotlin
val availability = ModuleAvailability.documentScanner(context)
when (val a = availability.install { p -> progress = p.fraction }) {   // 이미 있으면 바로 Available
    ScannerAvailability.Available -> enableScanButton()
    ScannerAvailability.DownloadRequired -> Unit                        // check() 만 이 값을 돌려준다
    is ScannerAvailability.Unavailable -> when (a.error.reason) {
        DocScanFailureReason.UNSUPPORTED_DEVICE -> hideScanFeature()     // RAM 1.7GB 미만 등 — 다시 시도해도 같다
        DocScanFailureReason.PLAY_SERVICES_UPDATE_REQUIRED,
        DocScanFailureReason.PLAY_SERVICES_API_UNAVAILABLE,               // 구버전 Play 서비스에서 관측 — 아래 참고
        DocScanFailureReason.PLAY_SERVICES_MISSING -> a.error.showPlayServicesResolution(activity)
        DocScanFailureReason.INSUFFICIENT_STORAGE -> askToFreeSpace()
        else -> showRetry()
    }
}
```

`check()` 는 내려받지 않고 상태만 본다. 실패는 던지지 않고 `Unavailable` 로 돌려준다. 메모리 하한은 10진 1.7GB(1,700,000,000 바이트)로,
판단이 갈리는 기기를 막지 않도록 낮은 쪽을 쓴다. 최종 판정은 Play 서비스가 한다(`MlKitException.UNSUPPORTED` → `UNSUPPORTED_DEVICE`).

**구버전 Play 서비스.** Play 서비스 자체는 정상이라고 답하는데 모듈 설치 API 가 없는 경우 `check()`·`install()` 이 `PLAY_SERVICES_API_UNAVAILABLE`(`statusCode` 17)을 돌려준다.
17 의 공식 의미는 `CommonStatusCodes.API_NOT_CONNECTED`, 곧 이 API 에 연결하지 못했다는 것이며 구버전만이 원인은 아니다. 관측은 다음과 같다.

- 에뮬레이터의 Play 서비스 20.24·21.45 에서 늘 17 이었고, 24.23·26.43 에서는 나지 않았다
- 21.45 이미지 하나가 스스로 26.43 으로 갱신된 뒤 `DownloadRequired` → `Available` 로 바뀌었다(1회)
- 같은 Play 서비스에서 `launch()` 는 `MODULE_DOWNLOAD_REQUIRED`(ML Kit 14)로 끝났다. 뒤이은 `install()` 이 이 분류를 돌려준다

그래서 앱은 업데이트를 안내하면 된다. `showPlayServicesResolution` 이 Play 서비스 업데이트 대화상자를 띄운다. 업데이트 뒤에도 같으면 이 기기에서 기능을 숨긴다.

## PDF 다시 만들기

```kotlin
val pages = (PageList.Empty + firstScan + secondScan)  // 합치기(ScannedDocument 를 더한다)
    .move(from = 3, to = 0)                             // 순서 바꾸기(끌어서 옮기기 의미)
    .removeAt(2)                                        // 삭제

val report = PdfComposer.create(context).compose(
    pages,
    outputStream,
    PdfOptions(
        pageSize = PageSize.A4,        // A4 · Letter · Original(이미지 비율) · Custom(widthPt, heightPt)
        marginPt = 18f,
        imageDpi = 150,                // 원본이 크면 이 해상도로 줄인다(키우지 않는다)
        encoder = PdfEncoder.compact(jpegQuality = 85),
    ),
)
```

- 고정 크기는 이미지가 가로로 길면 가로 페이지로 돌리고, 여백 안에 비율을 유지해 가운데 맞춘다. `Original` 은 긴 변을 842pt(A4 긴 변)에 맞춘다
- 한 번에 한 페이지만 메모리에 올린다(크기만 읽기 → 축소 디코딩 → 쓰기 → recycle). 취소는 페이지 사이에서 반영된다
- 인코더 두 가지

| 인코더 | 이미지 | 텍스트 층 | 파일 크기 |
|---|---|---|---|
| `PdfEncoder.platform()`(기본) | `android.graphics.pdf.PdfDocument` — 무손실로 담는다 | 글자를 그린 뒤 불투명한 이미지로 덮는다(Skia 가 투명 글자를 버리기 때문) | 크다 |
| `PdfEncoder.compact()` | JPEG 그대로(DCTDecode) | PDF 표준의 보이지 않는 글자(렌더링 모드 3)와 ToUnicode | 더 작다. 합성 시험 페이지 2장에서 1.33~1.62배(기기 2대, `jpegQuality` 85). 실제 문서 사진은 재지 않았다 |

스캐너가 만든 PDF 를 그대로 쓰려면 `result.document.pdf` 를 저장·공유하면 된다(합치기·순서 변경이 필요 없을 때).

## 저장과 공유

```kotlin
val saved = PdfSaver.downloads(context, subdirectory = "Scans").save("영수증 2026-11") { out ->
    PdfComposer.create(context).compose(pages, out)
}
// saved.location == SaveLocation.DOWNLOADS (API 29+) 또는 APP_SPECIFIC (API 28 이하)

startActivity(PdfSharing.shareIntent(saved.uri, saved.displayName))      // saved.uri 는 모든 API 에서 content: 라 바로 공유한다

// 파일로 공유하려면 공유 폴더에 쓰고 FileProvider 로 보낸다
val file = PdfSharing.newShareFile(context, "견적서")
file.outputStream().use { PdfComposer.create(context).compose(pages, it) }
startActivity(PdfSharing.shareIntent(context, file, chooserTitle = getString(R.string.share)))
```

- API 29+: `MediaStore.Downloads` 에 넣는다. 권한이 필요 없고, 다 쓸 때까지 `IS_PENDING` 으로 숨긴다. 실패·취소하면 항목을 지운다
- API 28 이하: 공용 다운로드 폴더는 저장소 권한이 필요하다. 이 SDK 는 권한을 추가하지 않으므로 앱 전용 폴더
  (`getExternalFilesDir(DIRECTORY_DOCUMENTS)`)에 저장하고 `SaveLocation.APP_SPECIFIC` 으로 알린다. 앱을 지우면 함께 지워지므로 공유로 내보내게 안내한다.
  `saved.uri` 는 이 SDK 의 provider 를 거친 `content:` 이고, 파일 경로가 필요하면 `saved.file` 을 쓴다
- 공유 인텐트는 모든 API 에서 선택 화면(`ACTION_CHOOSER`)이다. 받을 수 있는 앱이 하나뿐이면 시스템이 선택 화면 없이 그 앱을 바로 열 수 있다(API 26 에서 관측). `shareIntent` 에 `file:` Uri 를 넘기면 IAE 다
- 같은 이름이 있으면 번호가 붙는다(`영수증 (1).pdf`, 그다음 `영수증 (2).pdf`). API 29+ 는 MediaStore 가 붙이므로 기기에 따라 형식이 다를 수 있고,
  API 28 이하 앱 폴더는 SDK 가 같은 형식으로 붙인다. 실제로 저장된 이름은 `SavedPdf.displayName` 으로 확인한다. 이름에 `/ \ : * ? " < > |`·제어 문자는 쓸 수 없다
- 공유 provider 는 이 SDK 전용 클래스(`DocScanFileProvider`)와 authority `<applicationId>.plumt.docscan.files` 다.
  앱이 자기 `androidx.core.content.FileProvider` 를 선언해도, authority 를 다르게 두는 한 병합·설치 충돌이 없다. 내보낼 수 있는 곳은 캐시 안 `plumt_docscan/share/` 이고,
  API 28 이하는 앱 전용 저장 폴더(`Documents/`)도 더한다. 다른 위치의 파일은 `PdfSharing.copyForSharing` 으로 먼저 복사한다. 공유가 끝나면(다음 실행 등) `PdfSharing.clearShared` 로 비운다

## 검색 가능한 PDF (docscan-ocr)

```kotlin
val recognizer = KoreanTextRecognizer()                      // 한글·라틴 문자
recognizer.availability(context).install()                   // 인식 모델 미리 받기(선택)
PdfComposer.create(context).compose(
    pages, out,
    PdfOptions(encoder = PdfEncoder.compact(), textRecognizer = recognizer),
)
recognizer.close()
```

- 의존 모델: `com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.2` — Play 서비스가 모델을 내려받는 방식이라 앱에 모델이 실리지 않는다.
  모델을 앱에 싣고 싶으면(오프라인 첫 실행 등) 앱에서 바꾼다. 두 라이브러리의 API 는 같다

```kotlin
implementation("net.plumt.docscan:docscan-ocr:0.0.1") {
    exclude(group = "com.google.android.gms", module = "play-services-mlkit-text-recognition-korean")
}
implementation("com.google.mlkit:text-recognition-korean:16.0.1")   // 모델 번들(앱이 수 MB 커진다)
```

- minSdk 24(ML Kit 한국어 인식의 요구). `docscan` 도 같다
- 다른 엔진을 쓰려면 `PageTextRecognizer` 를 직접 구현한다(좌표는 넘겨받은 Bitmap 의 픽셀 기준)

## 확장 지점

| 인터페이스 | 역할 | 기본 구현 |
|---|---|---|
| `ScanIntentSource` | 스캐너 화면 준비(확인 → 인텐트) | `ScanIntentSource.gms(context)` |
| `ModuleAvailability` | 선택 모듈 확인·내려받기 | `documentScanner(context)`, `of(context, api)` |
| `PageTextRecognizer` | 페이지 글자 인식 | `KoreanTextRecognizer`(docscan-ocr) |
| `PdfEncoder` | PDF 바이트 쓰기 | `platform()`, `compact()` |
| `PdfComposer` | 페이지 → PDF | `PdfComposer.create(context)` |
| `PdfSaver` | 저장소 | `PdfSaver.downloads(context)` |
| `ScanPageStore` | 페이지 보관 | `ScanPageStore.files(context)` |

GMS 타입이 공개 API 에 나오는 곳은 `ModuleAvailability.of(context, OptionalModuleApi)` 하나다 — 텍스트 인식기처럼 다른 Play 서비스 선택 모듈도
같은 분류·진행 보고로 확인하기 위해서다. 그 밖의 결과·설정·실패는 모두 이 SDK 의 타입이다(`IntentSenderRequest`·`ActivityResultLauncher` 같은 androidx 타입은 쓴다).

## 오류

틀린 인자는 `IllegalArgumentException`(받은 값과 허용 범위 포함). 그 밖의 실패는 `DocScanException` 하나이며 `reason` 으로 분기하고 원인은 `cause` 에 보존한다.
스캔 결과에서는 `DocumentScanResult.Failure`, 사전 확인에서는 `ScannerAvailability.Unavailable` 로 오고, suspend 함수(PDF·저장·복사)는 던진다.
코루틴 취소는 감싸지 않는다. 전부 [ERRORS.md](ERRORS.md).

## 앱 용량 영향

빈 앱(AndroidX Activity 만) 대비 R8(전체 모드) release APK 증가분. 시험 앱에서 잰 값이며 앱이 이미 쓰는 의존(코루틴·Play 서비스 기반 등)에 따라 줄어든다.

| 추가한 모듈 | APK 증가 | 비고 |
|---|---|---|
| `docscan` | 약 +0.9 MB | 대부분 Play 서비스 기반·ML Kit 공통·코루틴. 스캐너 화면과 모델은 Play 서비스 안에 있어 앱에 실리지 않는다 |
| `docscan-ocr` 추가 | 약 +0.1 MB | 인식 모델은 Play 서비스가 내려받는다(번들 판으로 바꾸면 수 MB) |
| `docscan-compose` | 거의 없음 | Compose 를 이미 쓰는 앱 기준 |

## 권한과 매니페스트

- 이 SDK 의 매니페스트에는 `uses-permission` 이 없다. 카메라는 Play 서비스 스캐너 화면이 자기 권한으로 연다 — 앱이 `CAMERA` 를 선언할 필요가 없다
- 다만 Google 의 의존(`transport-backend-cct`·`transport-runtime`, ML Kit 이 끌어온다)이 `INTERNET`·`ACCESS_NETWORK_STATE` 를 선언해 병합 매니페스트에 들어간다.
  앱이 둘 다 쓰지 않는다면 앱 매니페스트에서 `tools:node="remove"` 로 뺄 수 있다(ML Kit 사용 기록 전송만 멈춘다 — 모듈 내려받기는 Play 서비스가 한다)
- 추가 컴포넌트: 공유 provider `DocScanFileProvider`(exported=false) 하나. ML Kit 이 초기화 provider 를 따로 더한다

## R8

`consumer-rules.pro` 가 자동 적용된다. ML Kit 이 리플렉션으로 만드는 등록자(`ComponentRegistrar` 구현)의 기본 생성자를 보존한다 —
ML Kit 이 끌어오는 firebase-components 16.1.0 의 규칙만으로는 R8 전체 모드에서 생성자가 지워져 release 빌드에서 초기화가 실패한다.
넓은 `-keep`·`-dontwarn com.google.**` 은 두지 않는다. 시험 앱의 release 빌드(R8 전체 모드)에서 세 등록자 생성자가 dex 에 남는 것을 확인했다.

## 위협 모델

- 공유 provider 는 내보내지 않으며(`exported=false`) 캐시 안 공유 폴더만 노출한다. API 28 이하는 앱 전용 저장 폴더(`Documents/`)도 노출하므로 앱이 그 폴더의 다른 파일에 직접 권한을 주면 읽힌다. 받는 앱에는 읽기 권한만 일시적으로 준다. `shareIntent(context, file)` 에 공유 폴더 밖 파일을 넘기면 IAE 로 거부한다(`..` 경로 포함)
- 페이지 보관소는 앱 내부 저장소(`filesDir`)라 다른 앱이 읽을 수 없다. `delete` 는 보관소 안 파일만 지운다
- 다운로드 폴더(API 29+)에 저장한 PDF 는 기기의 다른 앱·사용자가 볼 수 있다. 민감한 문서는 저장 대신 공유만 하거나 앱 전용 저장소를 쓴다
- 이 SDK 는 문서 내용을 로그에 남기지 않고 네트워크로 보내지 않는다. 인식은 기기 안 ML Kit 이 한다

## 요구사항

- 세 모듈 모두 minSdk 24, compileSdk 36, Java 11 바이트코드. 스캐너 라이브러리 16.0.0 이 API 24 의 `java.util.Optional` 을 쓰므로 앱에 desugaring 을 요구하지 않도록 24 로 뒀다
- 기기: Google Play 서비스, 전체 RAM 1.7GB 이상. 그 밖의 기기는 `UNSUPPORTED_DEVICE`·`PLAY_SERVICES_*` 로 알린다
- 런타임 의존: `kotlinx-coroutines-core`·`androidx.activity`(공개 API), `androidx.core`(FileProvider),
  `play-services-mlkit-document-scanner` 16.0.0, `play-services-base` 18.5.0·`play-services-tasks` 18.2.0·`com.google.mlkit:common` 18.11.0(스캐너가 끌어오는 버전 그대로).
  `docscan-compose` 는 Compose BOM 2025.02.00 `runtime`·`activity-compose`, `docscan-ocr` 는 위 인식 모델
- 스레드: `launch()`·`registerDocumentScanner` 는 메인 스레드. suspend 함수는 지정한 디스패처(기본 `Dispatchers.IO`)에서 블로킹 작업을 한다.
  `ModuleAvailability.install` 의 진행 콜백은 Play 서비스가 알리는 스레드에서 불린다

## 버전

모듈마다 [SemVer](https://semver.org/lang/ko/) 를 따로 쓰고 태그는 `<모듈>-v<버전>`(예: `docscan-v0.0.1`)이다. 1.0.0 전까지는 마이너 버전에서도 공개 API 가 바뀔 수 있다.
공개 API 는 `*/api/*.api` 로 고정하고 CI 가 대조한다. 라인 커버리지 90% 미만이면 빌드가 실패한다(Kover).

## 라이선스

[Apache License 2.0](LICENSE)
