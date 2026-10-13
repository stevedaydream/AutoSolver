# 除錯紀錄

**BF-004：錄製重放手勢被自己的遮罩吃掉**
- **問題描述：** 錄製模式點擊螢幕後遊戲沒反應或反應很慢；成員技能配置的重放點擊也可能無效。
- **嘗試過程：** 加入時間日誌，發現 updateViewLayout 設定 FLAG_NOT_TOUCHABLE 後立即 dispatchGesture，注入的 DOWN 約 7ms 後又送回自己的遮罩；模擬器上 34ms 仍可能不夠。
- **根本原因：** 懸浮視窗旗標由系統非同步套用到輸入層，在生效前注入的手勢仍落在自己的視窗。另外重放前會等待截圖存檔，開啟螢幕擷取時多出數百毫秒。
- **最終解法：** 重放前等待 50ms；遮罩以 ReplayGuardLayout 偵測起點吻合的自我命中，立即中止並延長等待後重送（50／100／200ms）；先重放、再等待截圖。
- **牽扯檔案：** MacroRecorderWindow.kt。

**BF-003：全版無障礙懸浮視窗蓋住導覽列**
- **問題描述：** 設定改成 MATCH_PARENT 後，Android 16 的無障礙懸浮視窗延伸至導覽列，遮住系統按鈕。
- **嘗試過程：** 移除 LAYOUT_IN_SCREEN、指定 systemBars fitInsets，截圖仍顯示導覽列被覆蓋；再以 WindowMetrics 扣除系統列高度。
- **根本原因：** 此模擬器的 ACCESSIBILITY_OVERLAY 全高視窗不能僅靠 fitInsets 避開底部導覽列。
- **最終解法：** API 30 以上使用 WindowMetrics 與 getInsetsIgnoringVisibility(systemBars) 明確限制設定視窗高度；關閉還原原視窗參數。
- **牽扯檔案：** FloatingWindow.kt。

**BF-001：本機 Java 25 JBR 無法建置**
- **問題描述：** Gradle Kotlin 設定階段出現 `IllegalArgumentException: 25.0.3`。
- **嘗試過程：** 檢查 Android Studio JBR 版本與本機 Gradle 快取，再改用已安裝的 JDK 17。
- **根本原因：** 目前工具鏈不能解析此 JBR 的 Java 25 版本，尚未進入應用程式編譯。
- **最終解法：** 設定 JAVA_HOME 為 JDK 17；快取 Gradle 8.14.3 建置通過，不修改專案依賴。
- **牽扯檔案：** 建置環境；無應用程式碼變更。

**BF-002：UIAutomator 擷取階層使懸浮視窗消失**
- **問題描述：** 模擬器中錄製與編輯懸浮視窗在擷取 UI 階層後消失。
- **嘗試過程：** 多次重開服務與浮窗，對照服務日誌並改用純截圖檢查。
- **根本原因：** UIAutomator 連線使既有無障礙服務中斷／重連，服務清理時移除了浮窗。
- **最終解法：** 使用指定 Workbench 模擬器的 ADB input 操作與 screencap 截圖，避免 UIAutomator dump。
- **牽扯檔案：** AutoPuzzlerAccessibilityService.kt 的既有服務生命週期；無修補程式碼。
