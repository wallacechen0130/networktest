# 提示詞：重新編譯「網路健檢」APK

> 把下面整段貼給電腦端 AI。

---

【任務】重新編譯「網路健檢」APK（原始碼已由手機端更新，請勿重複修改）

**重要：你電腦上的舊副本已作廢。** 手機端 AI 已在手機上改了四處，必鬚重新取回，否則會編到舊碼。

## 1. 取回最新原始碼

```
adb pull /sdcard/Download/netcheck ./netcheck
```

若 `netcheck` 已存在，請先刪掉再 pull，避免新舊檔混在一起。

## 2. 先驗證四項修正都在（缺任一項請回報，不要自己猜）

檔案：`netcheck/app/src/main/java/com/dsh/netcheck/MainActivity.java`

| 檢查 | 期望 |
|---|---|
| NTP／STUN 改用 IP 直連 | 檔案內有 `216.239.35.4` 與 `162.159.207.0`，且**不應再出現** `time.google.com`、`stun.cloudflare.com` |
| 斷流段文案 | 含「跨度含輪測間隔」與「連續 %d 次失敗，跨度約」 |
| 自動判讀 | 含「關鍵型態」字樣 |
| STUN 位元組轉型 | 含 `(byte) 0xA4` |

> 注意：部分 shell 的 grep 對中文（多位元組）支援不佳，建議用 Python 讀檔比對，例如：
> `python -c "s=open('MainActivity.java',encoding='utf-8').read(); print('關鍵型態' in s)"`

## 3. 直接編譯，不要改任何程式碼

上次唯一需要修的編譯錯誤（`req[6] = 0xA4` 需 `(byte)` 轉型）**已由手機端修好**，這次應該可以一次編過。

```
cd netcheck
gradle assembleDebug
```

## 4. 驗證產出

- 路徑：`netcheck/app/build/outputs/apk/debug/app-debug.apk`
- 回報檔案大小與 `sha256sum`
- 確認 APK 內的 dex 已含 `216.239.35.4`、**不含** `time.google.com`

## 5. 安裝並回傳到手機

```
adb install -r netcheck/app/build/outputs/apk/debug/app-debug.apk
adb push netcheck/app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/netcheck-new.apk
```

（推到 Download 是為了讓使用者能直接傳給另一支手機測試。）

## 6. 回報格式

1. APK 路徑、大小、SHA-256
2. 四項檢查結果（逐項 ✅／❌）
3. 編譯錯誤（若有）與你做的任何修改
4. 手機安裝是否成功

## 注意事項

- 不要索取或使用任何帳號密碼
- 不要改動手機的 APN／私人 DNS／VPN／網路設定
- 不要引入 AndroidX 或任何第三方依賴（目前專案零依賴）
- **測試邏輯不得變動**：四項探測目標、600ms 輪測間隔、1500ms 逾時、輪替順序都要保持原樣，這樣數據才能與既有量測對照

## 這次修改的背景（給你判斷用）

手機端已用這個 App 在遠傳 4G 上測到：UDP 53（DNS）失敗率 0%，但 UDP 123 失敗 83%、UDP 3478 失敗 73%，TCP 失敗 0%，且三項是**同一秒內交替量測**。因此判定為電信端「按埠別丟棄非 DNS 的 UDP」的政策問題。

這次四個修正的目的：

1. **IP 直連**：永久排除「DNS 解析卡住」這個干擾變數（先前已用網域/IP 對照排除，但改成直連可以讓報告更乾淨）
2. **斷流段文案**：原本寫「持續 44.3 秒」會讓人誤以為連續中斷，實際是輪測跨度
3. **自動判讀**：UDP 53 正常而其他 UDP ≥10% 失敗時，報告直接印出「只放行 DNS、其他 UDP 被丟棄，屬電信端政策」
4. **`(byte)` 轉型**：修正編譯錯誤
