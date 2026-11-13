# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## docscan

### [0.0.1] - 미발행

#### 추가
- `DocumentScanLauncher`·`registerDocumentScanner`·`DocumentScanContract` — Play 서비스 문서 스캐너 실행과 `DocumentScanResult`(`Success`/`Cancelled`/`Failure`) 결과
- `DocumentScanOptions` — 갤러리 가져오기·페이지 수 제한·편집 범위(`ScannerMode`)·결과 형식(`ScanOutput`)
- `DocScanException`·`DocScanFailureReason` — 실패 분류 하나로 통일. `showPlayServicesResolution`
- `ModuleAvailability` — 사용 가능 여부 확인(`check`)과 미리 내려받기(`install`, 진행 상황 `DownloadProgress`)
- `PageList` — 여러 스캔 합치기·끼우기·옮기기·삭제·재배열(Parcelable)
- `PdfComposer`·`PdfOptions`·`PageSize`·`PdfEncoder`(`platform`·`compact`)·`PageTextRecognizer` — PDF 다시 만들기와 텍스트 층
- `PdfSaver.downloads`(API 29+ MediaStore, 28 이하 앱 전용 폴더)·`PdfSharing`(FileProvider 공유)·`ScanPageStore`(캐시 밖 페이지 보관)

#### 실기기 검증 뒤 수정
- 액티비티 필드에서 `registerDocumentScanner(options) {}` 로 등록하면 생성 중 NPE 가 나던 문제 — 기본 공급원 `ScanIntentSource.gms` 가 컨텍스트를 준비 시점에 처음 읽는다
- 버튼을 빠르게 두 번 누르면 스캐너 화면·결과가 두 번 오던 문제 — 화면이 떠서 결과를 기다리는 동안에도 `launch()` 가 false.
  `isInProgress`·`dispatchResult` 추가, 생성자에 `restoredAwaitingResult`·`onAwaitingResultChange`(프로세스 종료 뒤 이어 가기) 추가
- 같은 이름 번호를 `(1)` 부터로 바꿨다(API 29+ MediaStore 형식과 같게). API 28 이하 앱 폴더·공유 파일 이름도 같은 형식
- minSdk 를 23 에서 24 로 올렸다. 스캐너 라이브러리가 API 24 의 `java.util.Optional` 을 써 API 23 에서 `NoClassDefFoundError` 가 났다. 앱에 desugaring 을 요구하지 않는다
- API 28 이하에서 저장한 PDF 를 `shareIntent(saved.uri)` 로 공유하면 `file:` Uri 가 나가 `FileUriExposedException` 이 나던 문제 — `SavedPdf.uri` 가 모든 API 에서 `content:` 다.
  공유 provider 가 API 28 이하에서 앱 전용 저장 폴더도 노출한다. `shareIntent(uri)` 는 `file:` Uri 를 IAE 로 거부한다
- 구버전 Play 서비스의 상태 17 이 `UNKNOWN` 으로 분류돼 업데이트를 안내할 수 없던 문제 — `DocScanFailureReason.PLAY_SERVICES_API_UNAVAILABLE` 추가, `showPlayServicesResolution` 이 업데이트 대화상자를 띄운다
- `ApiException` 16(`CommonStatusCodes.CANCELED`, 작업 취소)을 `ConnectionResult.API_UNAVAILABLE` 로 읽어 `MODULE_DOWNLOAD_REQUIRED` 로 분류하던 문제 — 이제 `UNKNOWN`, 설치 리스너의 16 은 `MODULE_DOWNLOAD_FAILED`
- README·KDoc 의 `compact` 크기 표현을 측정치에 맞췄다

## docscan-compose

### [0.0.1] - 미발행

#### 추가
- `rememberDocumentScanLauncher` — 진행 중이면 `launch()` 가 false, 결과 대기 상태는 `rememberSaveable` 로 보관

#### 실기기 검증 뒤 수정
- minSdk 를 23 에서 24 로 올렸다(`docscan` 과 같은 이유)

## docscan-ocr

### [0.0.1] - 미발행

#### 추가
- `KoreanTextRecognizer` — ML Kit 한국어 텍스트 인식(Play 서비스 모델), 인식 모델 확인·미리 내려받기
