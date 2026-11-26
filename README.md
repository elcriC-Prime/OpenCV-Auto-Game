# OpenCV-Auto-Game

專為貓戰刷小精靈關卡的自動腳本

基於 **Kotlin + OpenCV Android SDK (`org.opencv:opencv:4.10.0`)** 製作的 Android 浮窗遊戲自動化腳本（以繁體中文版《貓咪大戰爭》為目標），免 Root 即可透過 `MediaProjection` 擷取畫面、`AccessibilityService` 模擬點擊與系統懸浮窗完成自動刷關。

## 使用說明

1. 編譯或安裝 APK 到 Android 裝置。
2. 開啟 App 後依序授權：
   - **顯示在其他應用程式上層（懸浮窗）**
   - **無障礙服務（貓咪大戰爭自動點擊服務）**
     - *Android 13+ 側載提醒*：若無障礙開關呈灰色並提示「受限制的設定」，請至 `設定 → 應用程式 → 貓咪大戰爭自動腳本 → 右上角 ⋮ → 允許受限制的設定`，再回來開啟。
     - *小米 / OPPO / vivo*：建議開啟「自啟動」、「後台彈出視窗」並關閉電池最佳化。
3. 先啟動遊戲至橫屏畫面，再回腳本點擊「**啟動懸浮控制面板**」，系統彈出螢幕錄製權限時請選擇「**整個螢幕**」。
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
有問題寄信至:experimentalmonster1@gmail.com (不會讀

有小bug 因為我沒丟挖到地圖的圖片，所以挖到地圖要自己點掉才會繼續 嘿嘿

我懶得改了，反正集到4張圖就不會出現了(

## License

MIT License
