import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mobileailocal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mobileailocal"
        // 既定は Android 10(API 29)。BlueStacks(Android 9)で試すときだけ -PminSdk=28 を付ける
        minSdk = (findProperty("minSdk") as String?)?.toInt() ?: 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // 既定は arm64 のみ(公開用)。x86_64 のエミュレーターで試すときは -Pabis=x86_64 を付ける
        ndk { abiFilters += ((findProperty("abis") as String?)?.split(",") ?: listOf("arm64-v8a")) }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
    }

    // 署名情報はルートの keystore.properties にだけ置く(git に入れない)。無ければ未署名のまま
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keystoreProps != null) {
            // 値が欠けているときは NPE ではなく、足りないキー名の分かるエラーにする
            fun prop(name: String): String = keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }
                ?: throw GradleException("keystore.properties に '$name' がありません(keystore.properties.example を参照)")
            create("release") {
                storeFile = rootProject.file(prop("storeFile"))
                storePassword = prop("storePassword")
                keyAlias = prop("keyAlias")
                keyPassword = prop("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("io.github.dokar3:quickjs-kt:1.0.15")
    testImplementation("junit:junit:4.13.2")
    // JVM 単体テストでは android.jar の org.json が空実装なので、本物を足す
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

// 報告先のメールアドレスがプレースホルダーのまま release を作るときは警告する(ビルドは失敗させない)
gradle.taskGraph.whenReady {
    val releasing = allTasks.any { it.project == project && (it.name == "assembleRelease" || it.name == "bundleRelease") }
    if (releasing) {
        val cfg = file("src/main/res/values/config.xml")
        if (cfg.exists() && cfg.readText().contains("example.invalid")) {
            logger.warn("WARNING: report_email (src/main/res/values/config.xml) is still the placeholder 'report@example.invalid'. Set a real address before publishing.")
        }
    }
}

// Room のスキーマ(移行の検証用)を app/schemas に出力する
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
