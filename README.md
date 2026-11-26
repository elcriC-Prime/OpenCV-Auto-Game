# OpenCV-Auto-Game

基於 **Kotlin + OpenCV Android SDK (`org.opencv:opencv:4.10.0`)** 製作的 Android 浮窗遊戲自動化腳本（以繁體中文版《貓咪大戰爭》為目標），免 Root 即可透過 `MediaProjection` 擷取畫面、`AccessibilityService` 模擬點擊與系統懸浮窗完成自動刷關。

## 功能特色

- **懸浮控制面板 ＋ 可拖曳雙準星**：可隨時「開始 / 暫停 / 隱藏準星 / 關閉」，並將藍（`#1`）、橘（`#2`）兩個準星拖曳到戰鬥畫面任意位置，啟動後準星會自動設為點擊穿透。
- **跨裝置等比例多尺度辨識**：依畫面高度保持原始比例縮放，搭配多尺度模板比對（`0.52x` ~ `1.20x`）與尺度快取，相容 `19.5:9`、`16:9` 手機與 `4:3` 平板；鎖定尺度後單幀僅對特定 ROI 區域比對，降低過場延遲。
- **亮度比例過濾**：除 `TM_CCOEFF_NORMED` 相似度外，額外檢查命中區域平均亮度比例，避免彈窗出現時誤點背後被壓暗的按鈕。
- **全架構支援**：支援 Android 8.0+（`minSdk = 26`、`targetSdk = 35`），打包 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`。

## 畫面狀態與流程邏輯

程式依下列優先順序判斷畫面：

1. **統率力不足彈窗（圖4）**：偵測「`統率力不足。`」→ 點擊「是」，冷卻後回到關卡畫面重按戰鬥開始。
2. **EX 關卡彈窗（圖6）**：偵測「`確定要挑戰嗎？`」→ 點擊「是」直接進入戰鬥。
3. **過關獎勵畫面（圖3）**：偵測「`破關獎勵 入手啦！！`」→ 等待 `0.5` 秒後點擊 1 次進入結算畫面。
4. **過關結算畫面（圖5）**：偵測右上角圓形黃色返回按鈕 → 點擊回到關卡選擇。
5. **關卡選擇畫面（圖1）**：偵測「`戰鬥開始!!`」→ 點擊進入戰鬥。
6. **戰鬥中（圖2）**：偵測左上角暫停圖示 `||` → 每 `0.2` 秒（可在主畫面調整）輪流點擊 `#1` 與 `#2` 準星座標。

## 使用說明

1. 編譯或安裝 APK 到 Android 裝置。
2. 開啟 App 後依序授權：
   - **顯示在其他應用程式上層（懸浮窗）**
   - **無障礙服務（貓咪大戰爭自動點擊服務）**
     - *Android 13+ 側載提醒*：若無障礙開關呈灰色並提示「受限制的設定」，請至 `設定 → 應用程式 → 貓咪大戰爭自動腳本 → 右上角 ⋮ → 允許受限制的設定`，再回來開啟。
     - *小米 / OPPO / vivo*：建議開啟「自啟動」、「後台彈出視窗」並關閉電池最佳化。
3. 點擊「**啟動懸浮控制面板**」，系統彈出螢幕錄製權限時請選擇「**整個螢幕**」。
4. 切換進遊戲後，在戰鬥畫面將 `#1`、`#2` 準星拖到目標位置，按下浮窗「**開始**」即可運行。

## 本地編譯方式

1. 準備 **JDK 17** 與 **Android SDK**（`platforms;android-35`、`build-tools;35.0.0`）。
2. 在專案根目錄建立 `local.properties`（填入你的 Android SDK 路徑）：
   ```properties
   sdk.dir=C:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
   ```
3. 執行 Gradle 編譯 Debug APK：
   ```bash
   ./gradlew assembleDebug
   ```
   編譯產出位於 `app/build/outputs/apk/debug/app-debug.apk`。

## 專案結構

```text
app/src/main/
├── assets/templates/       # OpenCV 模板圖檔（PNG）
├── java/com/battlecats/autobot/
│   ├── MainActivity.kt             # 權限引導與參數設定介面
│   ├── BotConfig.kt                # SharedPreferences 設定儲存
│   ├── BotForegroundService.kt     # MediaProjection 擷取與狀態機主迴圈
│   ├── BotAccessibilityService.kt  # 無障礙手勢模擬點擊
│   ├── OverlayManager.kt           # 懸浮控制面板與可拖曳準星 UI
│   └── ScreenMatcher.kt            # OpenCV ROI + 多尺度模板匹配與亮度過濾
└── res/                    # 介面佈局、圖示與無障礙設定檔
```

## License

MIT License
