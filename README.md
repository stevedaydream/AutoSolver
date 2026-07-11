# AutoDora - 神魔之塔自動轉珠與巨集執行助手

AutoDora 是一款高效能、低延遲的 Android 轉珠輔助與觸控自動化腳本工具。本工具透過 AccessibilityService 實現非 root 環境下的觸控重放，並結合 HSV 色相特徵與深度機器學習 (TFLite) 模型來實現秒級的盤面解算與高難度路徑規劃。

---

## 🌟 核心功能特色
1. **極簡深石墨懸浮 UI**：
   * 內建精美圓點狀態卡（待命、擷取、辨識、計算、手勢、成功/錯誤），即時指示執行狀態。
   * 精簡自動執行模式 (↻) 與雙行巨集播放進度條，高穩定性且防止介面跳動。
2. **多功能巨集錄製與編輯**：
   * 支援成員技能（一/二段）配置與手勢/點擊錄製。
   * **巨集動作編輯器**：直接在懸浮視窗中以獨立 Modal 對話框呈現巨集的完整動作序列，並支援逐項刪除 (🗑) 與更新儲存。
3. **多策略計算面板**：
   * 支援手動調整搜尋步數、手勢執行時間、搜尋廣度、斜向移動等參數。
   * 提供一般 Combo、平鋪防黏珠、直排昇華技等多種適應度解算策略。

---

## 🧠 轉珠引擎核心演算法總結

本工具的求解引擎高度整合了**圖像特徵比對**、**啟發式寬度搜尋**與**機器學習智能決策**：

### 1. 盤面辨識 (Board Recognition)
實作於 [BoardAnalyzer.kt](app/src/main/java/com/stevedaydream/autodora/BoardAnalyzer.kt)：
* **HSV 直方圖分類**：裁剪每格中心 65% 的區域，統計飽和及非暗色像素的 Hue 分佈（36 個 Bin，每 10° 統計一次）。依據特徵峰值進行高精度規則分類。
* **SQLite 特徵資料庫 (KNN 查表)**：若規則分類未命中，將特徵與 [RecognitionDatabase.kt](app/src/main/java/com/stevedaydream/autodora/RecognitionDatabase.kt) 做匹配：
  * **D 類（特效）**：識別燃燒、冰凍、風化或腐蝕珠等改變外觀的符石。
  * **A 類（手動覆蓋）**：最優先的覆蓋級特徵，用於儲存使用者修正後的特徵向量。
  * **B 類（規則備援）**：一般特徵資料匹配。
* **加權 SAD 樣板匹配**：若資料庫未命中，則將目標格縮圖與標準樣板進行 **SAD (絕對誤差和)** 與 **Bhattacharyya 直方圖距離** 的融合比對（權重為 `0.35 : 0.65`），並通過置信度門檻進行判定。

### 2. Heuristic 寬度搜尋 (Beam Search)
實作於 [BoardAnalyzer.kt](app/src/main/java/com/stevedaydream/autodora/BoardAnalyzer.kt) 的 `calculateOptimalPath` 函式：
* **共享種子池 (Shared Seed Pool)**：將所有可行的起手位置放進同一個 Beam 優先隊列中全局競爭，集中運算資源在最優的前 $N$ 個分支（預設 `beamWidth = 200`），依步數擴展 4 方向或 8 方向。
* **適應度評估函數 (Fitness Evaluation)**：
  * **一般高 Combo 模式**：極大化總 Combo，並鼓勵同屬珠群聚以利後續消除。
  * **底層平鋪防黏珠模式**：獎勵底層 Row 4、Row 3 進行橫向 3 顆消除，懲罰黏珠（單一 Combo 消除過多同色珠），實現層疊排布。
  * **直排昇華模式**：當特定纵列內出現**剛好 4 顆**同色珠垂直相連時，賦予極高額外分數（排除 5 顆連線），以精確觸發昇華技。
* **天降連鎖模擬**：遞迴進行「偵測消去 $\rightarrow$ 落珠填充 $\rightarrow$ 再消去」的完整遊戲規則模擬，返回包含天降的最終 Combo 數。

### 3. ML 智能解盤演算法 (Reinforcement Learning)
實作於 [MLOrbPlanner.kt](app/src/main/java/com/stevedaydream/autodora/MLOrbPlanner.kt)：
* **策略網絡模型 (TFLite)**：加載客製 TensorFlow Lite 策略模型（包含通用與屬性特化模型），在行動端做低延遲推論。
* **狀態空間編碼 (226 維)**：
  * 5×6 盤面的 7 個符石屬性 One-hot 編碼 ($5 \times 6 \times 7 = 210$ 維)。
  * 當前起手珠/手指位置的行列 One-hot 編碼 ($5 + 6 = 11$ 維)。
  * 上一步採取的行動方向 One-hot 編碼 (5 維：上下左右及無)。
* **單步策略與起手篩選**：
  * 模型輸出 5 個行動 logit（上下左右與停止 `STOP`）做單步決策。
  * 在全盤搜尋下， ML 會分別從四角和中心點五個起手點出發進行軌跡規劃，最終返回模擬計算中 Combo 數最高的像素路徑。

---

## 🛠️ 開發環境與建置
* **編譯工具**：Gradle 8.13+
* **JDK 版本需求**：Java 17 或以上
* **專案建置指令**：
  在專案根目錄下指定 Java 17+ 運作環境進行編譯（例如 Android Studio 的內置 JBR）：
  ```powershell
  $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
  .\gradlew compileDebugKotlin
  ```
