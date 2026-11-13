import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.docscan.compose"
    compileSdk = 36

    defaultConfig {
        // docscan 과 같다 — 스캐너 라이브러리가 API 24 의 java.util.Optional 을 쓴다
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    explicitApi()
}

dependencies {
    // 런처·결과 타입이 공개 API 에 나온다. 앱은 docscan-compose 하나만 추가해도 된다
    api(project(":docscan"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    // rememberLauncherForActivityResult·LocalActivity
    implementation(libs.androidx.activity.compose)
    // "결과 대기 중" 상태를 rememberSaveable 로 보관한다
    implementation(libs.androidx.compose.runtime.saveable)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(artifactId = "docscan-compose", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("docscan-compose")
        description.set("docscan 의 Compose 진입점 — rememberDocumentScanLauncher")
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
