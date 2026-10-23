buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        // 理由: AGP 8.13.2 同梱の R8 は Kotlin 2.4 の metadata を読めず、release ビルドで警告が約 45 件出る。
        // 新しい R8(9.5.28)に差し替えると消える。
        // AGP 9 系に上げるときは、この差し替えを外して(AGP 同梱の R8 で足りるか)確認すること。
        classpath("com.android.tools:r8:9.5.28")
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
