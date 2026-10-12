# AutoDora

> 最後更新：2026-10-12（圖像化巨集編輯器與錄製觸控修正）

## 1. 專案概述
Android 轉珠與觸控腳本助手，透過無障礙服務重放觸控、MediaProjection 擷取盤面，支援一般搜尋與 TFLite 解盤。

## 2. 技術架構
- 主畫面處理權限與螢幕擷取；AutoPuzzlerAccessibilityService 管理懸浮視窗及手勢執行。
- MacroRecorderWindow 攔截觸控、擷取動作前畫面並重放；RecordingTouchGate 配對 DOWN / UP，忽略重放後孤立或重複的 UP。
- MacroEditorWindow 管理編輯草稿、歷史與預覽播放；MacroGestureView 依原始畫面座標繪製動畫並處理拖曳。
- MacroStorage 儲存 JSON；MacroImages 管理 App 私有 JPEG，刪除時保留其他腳本仍引用的圖片。

## 3. 目錄結構
- `app/src/main/java/com/stevedaydream/autodora/`：應用程式、辨識與巨集模組。
- `app/src/test/`：觸控配對與秒數轉換等 JVM 測試。
- `app/src/androidTest/`：Android 巨集 JSON 儲存相容性測試。
- `train/`：模型訓練工具；`build/validation/`：本機驗證畫面，不進 Git。

## 4. 核心資料模型
Macro 包含名稱、建立時間與動作清單；動作維持 Tap、Swipe、Wait、AutoBoard、UseSkill、FixedBoard 六種類型。
動作可選帶有 preview（圖片檔名、原始寬高）；舊腳本缺少此欄位仍可使用座標格線編輯，並可補上目前截圖。
時間底層維持毫秒，編輯器顯示與輸入秒，最多三位小數。

## 5. 已完成功能
- 修正錄製觸控配對，完成錄製前等待最後一次重放結束，保留使用者刻意連點。
- 接近全螢幕的圖像編輯器；點擊漣漪、滑動路徑動畫、拖曳座標與起終點、重畫路徑及修改秒數。
- 新增點擊／滑動／等待，複製、刪除、長按把手拖曳排序、復原與重做；儲存才提交草稿，取消捨棄。
- 單步與整份預覽、暫停／繼續、下一步、等待倒數；技能二段依序標示，自動解盤需按下一步確認。
- 錄製點擊／滑動自動附上動作前畫面；新增動作沿用最近截圖，舊動作可手動補圖。

## 6. 已知問題 / 待辦
- 遊戲內完整流程尚未實測；本次 UI 驗證使用 Android Workbench 的測試模擬器。
- 動作預覽不會送出真實觸控；自動解盤的實際路徑仍於執行時依盤面計算。
- 本機 Wrapper 8.13 下載未完成；以 JDK 17 與快取 Gradle 8.14.3 執行上述三項任務通過。快取執行檔位於 `C:\Users\User\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat`。

## 7. 開發歷程
- 2026-10-12：完成圖像化巨集編輯器。6 項 JVM 測試與 2 項 Android 儲存測試通過；在 Workbench 實際驗證錄製、排序、秒數修改、復原／重做、取消、預覽控制與舊腳本補圖。
