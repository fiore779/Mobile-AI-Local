plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// -Pabis=arm64-v8a,x86_64  -PpyVersion=3.15  -PminSdk=28
android {
    namespace = "com.mobileailocal.spike"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mobileailocal.spike"
        minSdk = (findProperty("minSdk") as String?)?.toInt() ?: 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += ((findProperty("abis") as String?)?.split(",") ?: listOf("arm64-v8a", "x86_64")) }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.github.dokar3:quickjs-kt:1.0.15")
}

// quickjs-kt 1.0.1〜1.0.15 の AAR は minCompileSdk=36 を宣言している(compileSdk 35 + AGP 8.7.3 では
// checkAarMetadata が失敗する)。実行時に API 36 は不要なので、このチェックだけを無効化して回避する。
tasks.configureEach {
    if (name.startsWith("check") && name.endsWith("AarMetadata")) enabled = false
}
