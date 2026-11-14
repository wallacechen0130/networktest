# 網路健檢 NetCheck

一個用來量測「UDP 延遲與封包遺失」的 Android App。

## 下載安裝

**[⬇ 下載最新版 APK](https://github.com/wallacechen0130/networktest/releases/latest/download/app-debug.apk)**

下載後在手機上點擊該檔案安裝（需允許「安裝未知來源」）。

若上方連結失效，可到 [Releases 頁面](https://github.com/wallacechen0130/networktest/releases) 手動選擇版本。

> **小米 / Redmi / POCO（HyperOS / MIUI）使用者注意**：adb 安裝可能被
> `INSTALL_FAILED_USER_RESTRICTED` 攔截。請到
> 設定 → 更多設定 → 開發人員選項 → 開啟「USB 安裝」，
> 或直接用手機的檔案管理器點擊 APK 安裝。

## 為什麼需要它

Speedtest 這類工具測的是**下載速度平均值**，看不出真正讓遊戲斷線的問題：

- 延遲尖峰（掉包但平均值漂亮）
- 整段斷流（好幾秒完全沒有封包）
- **只有 UDP 掉、TCP 正常**的情況——影片、網頁、下載全部看起來正常，只有遊戲會爆

本 App 逐筆記錄每一次探測的成功／失敗與毫秒數，因此能抓出上述三種 Speedtest 看不到的狀況。

## 設計：五個探測項目是一組「對照實驗」

關鍵不是「測 UDP」，而是**同時測四個不同條件的目標**，把變數鎖在單一因素上。

| 項目 | 目標 | 角色 |
|---|---|---|
| UDP DNS | `8.8.8.8:53`（IP 直連） | **對照組**。幾乎所有網路都必須放行 DNS，它應該永遠通 |
| UDP NTP | `216.239.35.4:123`（IP 直連） | 中立樣本：標準化、固定 48 bytes 的 UDP 小封包 |
| UDP STUN | `162.159.207.0:3478`（IP 直連） | 遊戲代表：WebRTC／VoIP／遊戲 NAT 穿透都用 STUN |
| TCP | `1.1.1.1:443` | 可靠傳輸對照組（TCP 會重傳，所以掉包不易顯現） |
| TCP 遊戲節點 | `43.174.218.121:20001` | **真實遊戲伺服器**（Arena Breakout 實際連線的節點，非標準埠） |

五項在同一循環內**輪替**探測，間隔 600ms、逾時 1500ms。因為是同一秒、同一支手機，所以出現差異時只能歸因於目標本身（埠號／協定）。

> 探測目標故意使用 **IP 直連**而非網域名稱，避免把「DNS 解析時間」混進量測結果。
> 遊戲節點是硬編碼的範例值；若遊戲改版換節點，改 `MainActivity.java` 的 `NAMES` 陣列與 `probe()` 的 `default` 分支即可。

## 歷史紀錄

每次測完會**自動存一筆**（不需要手動儲存），畫面下方「歷史紀錄」區可看到最近 20 筆：

```
•  2026-11-07 13:05:22 ｜ 300秒 ｜ DNS 0.0% / NTP 52.9% / STUN 49.3% / TCP 0.0% / GAME 41.0%
```

點任一筆即可載入該次的完整報告（時間、統計、斷流段、時間軸），可再分享或另存。資料存在 App 內部儲存空間，不上傳、不佔外部空間；可隨時按「清除歷史」清空。

## 判讀方式

| 觀察到的型態 | 意義 |
|---|---|
| UDP 53 正常，其他 UDP 埠失敗率 ≥10%，TCP 正常 | 電信端「只放行 DNS、其他 UDP 丟棄」的政策問題 |
| 全部項目都失敗 | 當下完全沒有網路 |
| 只有 TCP 失敗 | 連線建立層的問題（防火牆／路由） |
| 延遲高但失敗率 0% | 壅塞或路由繞遠，不是掉包 |

報告會自動產生「關鍵型態」判讀，並列出連續失敗的**跨度**（注意：跨度包含輪測間隔，不等於連續中斷時間）。

## 建置

需要 JDK 17、Android SDK（platform 34）、Gradle 8.7。

```bash
gradle assembleDebug
```

產出：`app/build/outputs/apk/debug/app-debug.apk`

專案**零外部依賴**（沒有 AndroidX、沒有第三方函式庫），只有 `INTERNET` 與 `ACCESS_NETWORK_STATE` 兩個一般權限，沒有任何執行期權限。

- compileSdk 34 / minSdk 24 / targetSdk 34
- 支援 Android 7.0 ～ 16（含 Android 13）

雲端編譯： `.github/workflows/build-apk.yml`（push 後自動產出 APK artifact）。

## 隱私

App 只發出探測封包、只讀取網路狀態，**不收集、不上傳任何資料**，也沒有任何分析或廣告 SDK。報告只存在本機。

## 授權

MIT License
