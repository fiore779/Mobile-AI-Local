@echo off
rem 実機(USB 接続)へ MobileAILocal-arm64.apk をインストールして起動する。
rem 事前: 端末の「開発者向けオプション」で「USB デバッグ」をオン → USB 接続 → 端末の確認ダイアログで許可。
chcp 65001 >nul
setlocal
cd /d %~dp0
set ADB=adb
where adb >nul 2>&1 || set ADB=D:\Android\Sdk\platform-tools\adb.exe
if not exist "%ADB%" if /i not "%ADB%"=="adb" (
    echo adb が見つかりません。Android SDK Platform-Tools を入れて PATH に通してください。
    pause & exit /b 1
)

"%ADB%" devices
"%ADB%" get-state >nul 2>&1 || (
    echo 端末が見つかりません。USB デバッグと USB 接続、端末側の許可を確認してください。
    pause & exit /b 1
)

echo インストールします(同じ署名の旧版があれば上書き。モデルと履歴は残ります)...
"%ADB%" install -r MobileAILocal-arm64.apk || (
    echo.
    echo 失敗しました。「INSTALL_FAILED_UPDATE_INCOMPATIBLE」の場合は、署名の違う旧版が入っています。
    echo 旧版を消してから再実行してください(モデルと履歴は消えます): adb uninstall com.mobileailocal
    pause & exit /b 1
)
"%ADB%" shell am start -n com.mobileailocal/.MainActivity
echo 完了しました。
pause
