@echo off
rem ビルド → エミュレーターへインストール → 起動。エミュレーターが未起動なら先に起動する。
set JAVA_HOME=C:\Program Files\Java\jdk-22
set ADB=D:\Android\Sdk\platform-tools\adb.exe
cd /d %~dp0

"%ADB%" get-state >nul 2>&1 || (
    start "" D:\Android\Sdk\emulator\emulator.exe -avd pixel_mal -no-snapshot
    "%ADB%" wait-for-device
    :wait
    "%ADB%" shell getprop sys.boot_completed | findstr 1 >nul || (timeout /t 3 >nul & goto wait)
)

call gradlew.bat installDebug -Pabis=x86_64 || exit /b 1
"%ADB%" shell am start -n com.mobileailocal/.MainActivity
