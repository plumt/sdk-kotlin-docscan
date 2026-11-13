import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.docscan.ocr"
    compileSdk = 36

    defaultConfig {
        // ML Kit 한국어 텍스트 인식(play-services-mlkit-text-recognition-korean)의 최소 요구도 24 다. docscan 과 같다
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
            it.systemProperty("kotlinx.coroutines.stacktrace.recovery", "false")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    explicitApi()
}

dependencies {
    // PageTextRecognizer·TextLine·ModuleAvailability 가 공개 API 에 나온다
    api(project(":docscan"))
    // 한국어 인식 모델(라틴 문자 포함). Play 서비스가 모델을 내려받는 방식이라 앱 용량이 거의 늘지 않는다.
    // 모델을 앱에 싣고 싶으면 앱에서 com.google.mlkit:text-recognition-korean 으로 바꾼다(README)
    implementation(libs.mlkit.text.recognition.korean)
    implementation(libs.play.services.tasks)
    implementation(libs.mlkit.common)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(artifactId = "docscan-ocr", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("docscan-ocr")
        description.set("docscan 의 선택 모듈 — ML Kit 한국어 텍스트 인식으로 검색 가능한 PDF 텍스트 층을 만든다")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}
