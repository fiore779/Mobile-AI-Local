import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.mobileailocal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mobileailocal"
        // 既定は Android 10(API 29)。BlueStacks(Android 9)で試すときだけ -PminSdk=28 を付ける
        minSdk = (findProperty("minSdk") as String?)?.toInt() ?: 29
        targetSdk = 35
        // 1.0.0(初の公開版)。内部配布した 0.1.0 は versionCode 1 なので、更新インストールできるよう 2 から始める
        versionCode = 2
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // アプリ内で言語を切り替える(Locale を自前で設定する)ので、AAB を言語ごとに分割しない
    // (分割すると、端末の言語以外の文字列が配信されず、アプリ内で切り替えた言語が表示できなくなる)
    bundle { language { enableSplit = false } }

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
    implementation("androidx.documentfile:documentfile:1.0.1") // 作業フォルダ(SAF)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Google サインイン(Credential Manager + Google ID)と Firebase(Auth / Firestore)。クラウド連携を使うときだけ動く
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("io.github.dokar3:quickjs-kt:1.0.15")
    testImplementation("junit:junit:4.13.2")
    // 端末・エミュレーターで動く計装テスト(Android Keystore の動作確認用)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
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

// ストア掲載と同じプライバシーポリシーを、アプリに同梱する(assets/privacy/)。公開 URL が無い間の画面表示に使う
val copyPrivacyPolicies by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile.resolve("store/privacy")) { include("privacy-policy.*.md") }
    into(layout.buildDirectory.dir("generated/policyAssets/privacy"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/policyAssets"))
tasks.named("preBuild") { dependsOn(copyPrivacyPolicies) }

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
