import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.docscan"
    compileSdk = 36
    // 소비 앱의 리소스(공유 경로 xml)와 이름이 겹치지 않게 접두사를 강제한다
    resourcePrefix = "plumt_docscan_"

    defaultConfig {
        // 스캐너 라이브러리(16.0.0)가 매니페스트에는 23 을 적지만 옵션 빌더에서 API 24 의 java.util.Optional 을 쓴다.
        // API 23 에서 NoClassDefFoundError 가 나므로, 소비 앱에 desugaring 을 강요하지 않고 하한을 24 로 둔다
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // 코루틴 디버그 스택 복구가 예외를 복사해 cause 가 달라지는 것을 막아 cause 보존을 정확히 검증한다
            it.systemProperty("kotlinx.coroutines.stacktrace.recovery", "false")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // suspend 공개 API 와 디스패처 지정
    api(libs.kotlinx.coroutines.core)
    // ActivityResultLauncher·IntentSenderRequest·ActivityResultContract 가 공개 API 에 나온다
    api(libs.androidx.activity)
    // FileProvider(공유)
    implementation(libs.androidx.core)
    // 촬영 UI·갤러리 가져오기·모서리 감지·자르기·필터. 공개 API 에 GMS 타입을 드러내지 않는다(ModuleAvailability.of 한 곳만 예외 — README)
    implementation(libs.mlkit.document.scanner)
    // 문서 스캐너가 이미 끌어오는 버전 — 모듈 설치(ModuleInstallClient)·Task·MlKitException 을 직접 쓰므로 명시한다
    // ModuleAvailability.of 가 OptionalModuleApi 를 받으므로 api 로 노출한다
    api(libs.play.services.base)
    implementation(libs.play.services.tasks)
    implementation(libs.mlkit.common)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(artifactId = "docscan", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("docscan")
        description.set("ML Kit 문서 스캐너를 감싼 안드로이드 라이브러리 — 타입 있는 결과·실패 분류·사전 설치 확인·페이지 합치기와 PDF 재생성·다운로드 저장·공유")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        filters {
            excludes {
                // 유닛 테스트로 실행할 수 없는 접착 코드만 뺀다. 판단 로직(분류·결과 변환·배치·PDF 직렬화·저장)은 모두 테스트한다.
                classes(
                    // Google Play 서비스 정적 진입점(GoogleApiAvailability·ModuleInstall.getClient·GmsDocumentScanning.getClient) 호출만 있다 — Robolectric 에 Play 서비스가 없다
                    "net.plumt.docscan.internal.AndroidScannerPlatform*",
                    // android.graphics.pdf.PdfDocument 의 네이티브(Skia PDF) 백엔드 — Robolectric 에 구현이 없다. 실기기 계획으로 검증한다
                    "net.plumt.docscan.pdf.PlatformPdfEncoder*",
                )
            }
        }
        verify {
            rule {
                // 라인 커버리지 90% 미만이면 check(build) 가 실패한다
                minBound(90)
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}
