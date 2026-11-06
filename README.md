# 網路健檢 NetCheck

一個**零第三方依賴**的 Android 網路品質量測 App，專門抓出 Speedtest 看不到的問題：
**UDP 掉包**與**整段斷流**。

看影片、跑 Speedtest 一切正常，但玩遊戲卻不斷斷線重連？這類問題通常不出在頻寬，
而是線路對 UDP 的處理。Speedtest 只量平均下載速度，看不到這件事。

---

## 這個 App 在量什麼

四個探測項目輪替量測，間隔 **600 ms**、逾時 **1500 ms**：

| 項目 | 目標 | 協定 |
|---|---|---|
| UDP DNS | `8.8.8.8:53` | DNS 查詢 |
| UDP NTP | `216.239.35.4:123` | NTP 請求 |
| UDP STUN | `162.159.207.0:3478` | STUN Binding Request |
| TCP | `1.1.1.1:443` | TCP 連線 |

NTP 與 STUN 採 **IP 直連**而非網域名稱，永久排除「DNS 解析卡住」對量測的干擾，
確保失敗率反映的是線路本身，不是解析問題。

### 為什麼要分 UDP / TCP 並看埠別

如果 UDP 53（DNS）正常、其他 UDP 埠卻大量失敗，而 TCP 幾乎不掉——
這不是訊號、APN 或手機問題，而是**電信端按埠別丟棄非 DNS 的 UDP**，或 CGNAT
的 UDP 會話表問題。這個型態正是本 App 設計要捕捉的目標。

---

## 功能

- **四項探測輪替量測**，可個別勾選
- **即時統計**：取樣數、失敗數、失敗率、p50、max
- **自動標記斷流段**：連續 ≥2 次失敗即記錄起迄時間與跨度
- **時間軸視覺化**：`.` 成功、`F` 失敗，連續 `F` 就是斷流
- **產生文字報告**：可存成 txt 或分享／複製
- **自動判讀**：依失敗型態給出結論提示（見下方「關鍵型態」）
- 可選 **60 / 180 / 300 / 600 秒** 量測長度

### 關鍵型態自動判讀

當報告偵測到「UDP 53 失敗率 < 3%，但其他 UDP 埠失敗率 ≥ 10%」時，會直接印出：

```
【關鍵型態】UDP 53（DNS）正常，但其他 UDP 埠失敗率 XX%：只放行 DNS、其他 UDP 被丟棄。
這是電信端政策／CGNAT 的 UDP 問題，與手機、訊號強度、APN 設定無關。
```

這段輸出可直接作為向電信業者申訴的依據。

---

## 建置

需求：**JDK 17**、**Android SDK platform 34**、**Gradle 8.7**、AGP 8.5.2

```bash
gradle assembleDebug
```

產出：

```
app/build/outputs/apk/debug/app-debug.apk
```

專案**零外部依賴**，沒有 AndroidX、沒有任何第三方函式庫。

### 雲端編譯

`.github/workflows/build-apk.yml` 已設定 GitHub Actions，推送後可自動產出 APK artifact。

---

## 安裝

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **小米 / Redmi / POCO（HyperOS / MIUI）使用者注意**：adb 安裝可能被
> `INSTALL_FAILED_USER_RESTRICTED` 攔截。若遇到，請到
> 設定 → 更多設定 → 開發人員選項 → 開啟「USB 安裝」，或直接用手機檔案管理器點擊 APK 安裝。

---

## 使用方式

1. 開啟 App，勾選要測的項目（預設全選）
2. 選擇量測長度（建議至少 180 秒，較能抓到偶發斷流）
3. 按下「開始測試」，保持螢幕開啟
4. 測完按「儲存報告」或「分享／複製」

要作為申訴證據時，建議：

- 在**實際發生問題的同一時段**量測
- 使用 300 或 600 秒，讓統計更有份量
- 同時錄下遊戲斷線重連畫面，與報告時間戳對齊

---

## 技術規格

| 項目 | 值 |
|---|---|
| 語言 | Java |
| compileSdk / targetSdk | 34 |
| minSdk | 24（Android 7.0 起，支援至 Android 16 / API 36） |
| 依賴 | 無 |
| 權限 | `INTERNET`、`ACCESS_NETWORK_STATE`（皆為一般權限，無執行期權限） |
| UI | 全部以程式碼建立，無 layout XML |

### 隱私

本 App **只讀取網路狀態、只發出探測封包**。
不收集、不儲存、不上傳任何個人資料，也沒有任何分析或追蹤 SDK。

---

## 專案結構

```
netcheck/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── .gitignore
├── README.md
├── .github/workflows/build-apk.yml    # GitHub Actions 雲端編譯
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/drawable/ic_launcher.xml
        └── java/com/dsh/netcheck/MainActivity.java
```

---

## 授權

本專案未指定開源授權條款。
