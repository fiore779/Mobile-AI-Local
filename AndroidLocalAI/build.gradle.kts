buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        // AGP 8.13.2 同梱の R8 は Kotlin 2.4 の metadata を読めず警告が出るので、新しい R8 に差し替える
        classpath("com.android.tools:r8:9.5.28")
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
