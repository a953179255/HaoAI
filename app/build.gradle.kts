plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.haoai.agent"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.haoai.agent"
        minSdk = 26
        targetSdk = 36
        versionCode = 35
        versionName = "0.18.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    environment("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8")
}

dependencies {
    // OCR 中文捆绑版（2.4 ocr_image）：模型打进 APK（约 4MB/ABI），Apache-2.0
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.backdrop)
    implementation(libs.androidx.work)
    implementation(libs.jetbrains.markdown)
    implementation(libs.jlatexmath.android)
    implementation(libs.jlatexmath.android.font.greek)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
}
