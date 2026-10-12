# AutoDora — Android 轉珠與巨集助手

## 技術棧
- Kotlin、Android 原生懸浮視窗與 Compose 主畫面、AccessibilityService、MediaProjection、TensorFlow Lite。
- compileSdk / targetSdk 36，minSdk 26；建置使用 JDK 17。

## 常用指令
- PowerShell：`$env:JAVA_HOME='C:\Program Files\Java\jdk-17'`
- 建置與單元測試：`.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain`
- Wrapper 首次需要下載 Gradle；本機已用快取 Gradle 8.14.3 驗證，見 project.md。

## 驗證方式
- 上述建置與單元測試通過。
- 巨集資料測試：在指定模擬器執行 `MacroPersistenceTest`。
- UI 修改須實際操作並以截圖檢查；不可只依賴編譯。

## 專案禁忌
- 本次測試只使用 `I:\project\android_emulator` 的 Android Workbench 所建立模擬器；每次先核對 AVD 名稱，ADB 指令必須帶 `-s`，不得操作實體平板。
- 懸浮視窗驗證用 ADB 截圖；UIAutomator 會中斷無障礙服務（BF-002）。
- 勿用目前 Android Studio 的 Java 25 JBR 建置（BF-001）。
- 簽章金鑰與 local.properties 不進 Git；版本號在 app/build.gradle.kts。

## Git 設定
- origin：`https://github.com/stevedaydream/AutoSolver.git`。
- 預設分支：`main`；新功能用 `feature/<名稱>`，合併須經使用者同意。
- 僅在使用者要求時推送，不 force push、不略過 hooks。

## 敏感資訊
見 `secret.md`（不進 Git）。
