# 交接：幫使用者把「網路健檢」App 編譯成 APK

> 這份文件是給**電腦端 AI／開發者**看的。手機端 AI 已把 Android 專案寫完，但手機上沒有任何編譯工具（無 JDK、無 Android SDK、無 clang），所以需要在電腦上編譯。

---

## 一、背景（為什麼要做這個 App）

使用者手機打遊戲會**不斷斷線重連**，但看影片、Speedtest 完全正常。

已完成診斷（手機端 AI 實測），結論：

| 測試條件 | 結果 |
|---|---|
| 同一支手機 + 台灣大哥大熱點 | UDP 200/200 成功（**0% 失敗**） |
| 同一支手機 + 遠傳 4G | UDP 60/96 失敗（**62%**），曾達 91% |
| 遠傳 4G 斷流型態 | 出現 **20 秒級完全斷流**，恢復後延遲立刻回到 38ms |
| 遠傳 4G 的 TCP | 幾乎不失敗（440 次只失敗 1 次），但曾劣化到 541ms |

**判定：問題在遠傳線路／門號的 UDP 處理（疑似 CGNAT 的 UDP 會話表），與手機硬體、SIM 卡槽、App、系統設定無關。**

已排除：APN（FarEasTone internet / default,supl / IPv4/IPv6）、私人 DNS（自動）、VPN（無）、系統時間（與 Google 誤差 1 秒）、DNS 劫持、GMS、MTU、NAT 類型、訊號強度（兩地皆滿格）。

**因此使用者需要一個能自己重複驗證、可出示給電信客服的 App。**

---

## 二、專案位置

已同時複製到**電腦可直接 adb pull 的公開路徑**：

```
/sdcard/Download/netcheck/
```

原始位置（App 私有目錄，adb 一般無權限）：

```
/data/user/0/com.deepseek.harness/files/Deepseek/netcheck/
```

### 專案結構

```
netcheck/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/drawable/ic_launcher.xml
│       └── java/com/dsh/netcheck/MainActivity.java   (574 行)
└── .github/workflows/build-apk.yml                   (GitHub Actions 雲端編譯)
```

---

## 三、技術規格（請勿任意變更）

- 語言：**Java**（不用 Kotlin）
- **零外部依賴**：沒有 AndroidX、沒有第三方函式庫。請勿引入，否則編譯會變慢且可能失敗。
- compileSdk 34、minSdk 24、targetSdk 34
- **相容性：`minSdk 24` = Android 7.0 起，一路支援到 Android 16（API 36）。Android 13（API 33）可直接安裝使用。**
  - 只用 `INTERNET` 與 `ACCESS_NETWORK_STATE` 兩個一般權限，**沒有任何執行期權限**，所以 Android 13 不會跳權限詢問。
  - 只使用 API 11 級別的介面（`getExternalFilesDir`、`ClipboardManager`、`ACTION_SEND`、`FLAG_KEEP_SCREEN_ON`、`setTextIsSelectable`），在 Android 13 上全部可用。
  - 使用平台主題 `@android:style/Theme.DeviceDefault.Light.NoActionBar`，沒有自訂 style，跨版本不會壞。
- AGP 8.5.2、Gradle 8.7、JDK 17
- 只有一個 Activity，UI 全部用程式碼建立（沒有 layout XML，除了 icon）

### App 功能（已實作）

1. 四個探測項目，可勾選：
   - UDP DNS `8.8.8.8:53`
   - UDP NTP `time.google.com:123`
   - UDP STUN `stun.cloudflare.com:3478`
   - TCP `1.1.1.1:443`
2. 輪替探測，間隔 600ms，逾時 1500ms
3. 即時顯示：取樣數、失敗數、失敗率、p50、max
4. 自動標記**斷流段**（連續 ≥2 次失敗，記錄起迄時間與長度）
5. 時間軸（`.` 成功、`F` 失敗）
6. 測完產生文字報告：可存成 txt（外部檔案目錄）或分享／複製

---

## 四、你的任務

### T1 — 取回專案

```bash
adb pull /sdcard/Download/netcheck ./netcheck
```

### T2 — 編譯

需求：**JDK 17**、**Android SDK（platform 34 + build-tools）**、**Gradle 8.7**

```bash
cd netcheck
gradle assembleDebug
```

產出：

```
netcheck/app/build/outputs/apk/debug/app-debug.apk
```

如果 `gradle` 不在 PATH，可用專案附的 GitHub Actions 設定（`.github/workflows/build-apk.yml`）走雲端編譯，或先執行 `gradle wrapper` 產生 wrapper。

**若編譯失敗**：請修 `MainActivity.java` 的編譯錯誤，**但不要改變測試邏輯與功能**（這是為了讓數據與手機端既有的量測一致、可互相對照）。修完請在回報中說明改了什麼。

### T3 — 安裝到手機

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

手機需開啟「USB 偵錯」，並允許安裝未知來源。

### T4 — 選用但很有價值：啟動 Shizuku

```bash
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

啟動後，請在手機上把 Shizuku 權限授權給 **DeepSeek Harness**（`com.deepseek.harness`）。
如此手機端 AI 就能讀取 `dumpsys connectivity`、iptables、各 App 流量、SIM 訊號 dBm 等，可進一步確認是否為遠傳 CGNAT 的 UDP 問題。

### T5 — 回報

請把以下內容貼回 Discord 頻道：

1. APK 的完整路徑（或直接附上檔案）
2. 編譯過程的錯誤與你做的修改（若有）
3. `adb devices` 是否成功辨識手機

---

## 五、注意事項

1. **不要索取或使用任何帳號密碼。** 使用者稍早在對話中貼過學校帳號密碼，已請他立即更改；請勿使用、勿記錄。
2. **不要改動手機的 APN、私人 DNS、VPN 或任何網路設定。** 使用者只是要一個量測工具。
3. **不要引入 AndroidX 或任何第三方依賴。**
4. 這個 App 只讀取網路狀態、只發出探測封包，**不收集、不上傳任何個人資料**，也請維持這個特性。
5. 若 adb 無法 pull（權限問題），替代方案：請使用者用檔案管理員把 `netcheck` 資料夾從手機複製到電腦。

---

## 六、驗收標準

編譯成功並安裝後，打開 App 應可：

- 勾選四項、選擇 60 秒，按「開始測試」
- 看到四個項目即時更新失敗率與 p50
- 在遠傳 4G 上，UDP NTP／STUN 應出現明顯失敗率（手機端實測 62%～91% 變動）
- 測完可按「儲存報告」，產生含斷流段清單的 txt

如果安裝後在遠傳 4G 上跑出「UDP 高失敗率、TCP 幾乎不失敗」，即成功重現問題，可直接作為向電信業者申訴的證據。
