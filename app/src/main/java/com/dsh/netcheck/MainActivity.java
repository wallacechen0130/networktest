package com.dsh.netcheck;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 網路健檢：量測 UDP / TCP 的延遲與「封包遺失（含整段斷流）」。
 *
 * 設計理念：Speedtest 只測平均下載速度，看不到 UDP 掉包與斷流。
 * 本程式逐一送出可辨識的請求並記錄每一次成功/失敗與毫秒數，
 * 因此能抓出「好的時候很快、壞的時候整段沒有回應」這種遊戲最怕的狀況。
 */
public class MainActivity extends Activity {

    static final String[] NAMES = {
            "UDP DNS   8.8.8.8:53",
            "UDP NTP   216.239.35.4:123",
            "UDP STUN  162.159.207.0:3478",
            "TCP       1.1.1.1:443",
            "TCP 遊戲  43.174.218.121:20001"
    };

    static final String[] SHORT = {"DNS", "NTP", "STUN", "TCP", "GAME"};
    static final int[] DURATIONS = {60, 180, 300, 600};
    static final String[] DURATION_LABELS = {"60 秒（1 分鐘）", "180 秒（3 分鐘）", "300 秒（5 分鐘）", "600 秒（10 分鐘）"};
    static final int INTERVAL_MS = 600;
    static final int TIMEOUT_MS = 1500;

    static class Stat {
        final String name;
        int total, fail;
        final List<Long> lat = new ArrayList<>();
        final List<long[]> bursts = new ArrayList<>(); // {start, end, count}
        long streakStart;
        int streak;

        Stat(String n) { name = n; }

        void reset() { total = 0; fail = 0; lat.clear(); bursts.clear(); streak = 0; streakStart = 0; }

        double lossPct() { return total == 0 ? 0d : 100d * fail / total; }

        long pct(double p) {
            if (lat.isEmpty()) return -1;
            List<Long> c = new ArrayList<>(lat);
            Collections.sort(c);
            int i = (int) Math.floor(p * (c.size() - 1));
            if (i < 0) i = 0;
            if (i >= c.size()) i = c.size() - 1;
            return c.get(i);
        }

        long maxMs() { return lat.isEmpty() ? -1 : Collections.max(lat); }
    }

    final Stat[] stats = new Stat[NAMES.length];
    final CheckBox[] checks = new CheckBox[NAMES.length];
    final TextView[] rows = new TextView[NAMES.length];

    LinearLayout root;
    Spinner durationSpinner;
    Button runButton, exportButton, shareButton;
    TextView progressView, timelineView, reportView;
    LinearLayout historyBox;

    final Handler ui = new Handler(Looper.getMainLooper());
    final StringBuilder timeline = new StringBuilder();
    final Random rnd = new Random();

    volatile boolean running = false;
    long testStartMs, testEndMs, lastUiMs;
    int durationSec = 180;
    String reportText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        for (int i = 0; i < NAMES.length; i++) stats[i] = new Stat(NAMES[i]);
        buildUi();
        renderStats();
        refreshHistory();
    }

    // ---------------------------------------------------------------- UI

    void buildUi() {
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("網路健檢");
        title.setTextSize(26f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("量測 UDP／TCP 的延遲、掉包率與「整段斷流」，抓出 Speedtest 看不到的問題。");
        sub.setTextSize(13f);
        sub.setTextColor(Color.parseColor("#666666"));
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        root.addView(sectionTitle("測試項目"));
        for (int i = 0; i < NAMES.length; i++) {
            CheckBox cb = new CheckBox(this);
            cb.setText(NAMES[i]);
            cb.setChecked(true);
            cb.setTextSize(14f);
            checks[i] = cb;
            root.addView(cb);
        }

        root.addView(sectionTitle("持續時間"));
        durationSpinner = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, DURATION_LABELS);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        durationSpinner.setAdapter(ad);
        durationSpinner.setSelection(1);
        root.addView(durationSpinner);

        runButton = new Button(this);
        runButton.setText("開始測試");
        runButton.setTextSize(17f);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        runButton.setLayoutParams(rlp);
        runButton.setOnClickListener(v -> {
            if (running) stopTest(); else startTest();
        });
        root.addView(runButton);

        progressView = new TextView(this);
        progressView.setTextSize(14f);
        progressView.setTypeface(Typeface.MONOSPACE);
        progressView.setPadding(0, dp(12), 0, dp(4));
        progressView.setText("");
        root.addView(progressView);

        root.addView(sectionTitle("即時統計"));
        for (int i = 0; i < NAMES.length; i++) {
            TextView tv = new TextView(this);
            tv.setTextSize(13f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setPadding(0, dp(3), 0, dp(3));
            rows[i] = tv;
            root.addView(tv);
        }

        root.addView(sectionTitle("時間軸"));
        timelineView = new TextView(this);
        timelineView.setTextSize(13f);
        timelineView.setTypeface(Typeface.MONOSPACE);
        timelineView.setText("");
        root.addView(timelineView);

        TextView hint = new TextView(this);
        hint.setText("每格 = 一次探測；「.」成功、「F」失敗。連續 F 就是斷流。");
        hint.setTextSize(11f);
        hint.setTextColor(Color.parseColor("#888888"));
        hint.setPadding(0, dp(4), 0, dp(6));
        root.addView(hint);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(14), 0, 0);

        exportButton = new Button(this);
        exportButton.setText("儲存報告");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(v -> saveReport());
        btnRow.addView(exportButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        shareButton = new Button(this);
        shareButton.setText("分享／複製");
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(v -> shareReport());
        btnRow.addView(shareButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(btnRow);

        root.addView(sectionTitle("歷史紀錄（每次測完自動儲存）"));
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(historyBox);

        Button clearBtn = new Button(this);
        clearBtn.setText("清除歷史");
        clearBtn.setOnClickListener(v -> clearHistory());
        root.addView(clearBtn);

        reportView = new TextView(this);
        reportView.setTextSize(12f);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(0, dp(14), 0, 0);
        reportView.setText("");
        root.addView(reportView);

        setContentView(sv);
    }

    TextView sectionTitle(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(15f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(18), 0, dp(6));
        return tv;
    }

    int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ---------------------------------------------------------------- 測試流程

    void startTest() {
        boolean any = false;
        for (CheckBox cb : checks) if (cb.isChecked()) any = true;
        if (!any) {
            Toast.makeText(this, "請至少勾選一個測試項目", Toast.LENGTH_SHORT).show();
            return;
        }

        durationSec = DURATIONS[durationSpinner.getSelectedItemPosition()];
        for (Stat s : stats) s.reset();
        timeline.setLength(0);
        reportText = "";
        reportView.setText("");
        exportButton.setEnabled(false);
        shareButton.setEnabled(false);

        final boolean[] enabled = new boolean[NAMES.length];
        for (int i = 0; i < checks.length; i++) enabled[i] = checks[i].isChecked();

        testStartMs = System.currentTimeMillis();
        testEndMs = testStartMs + durationSec * 1000L;
        running = true;
        runButton.setText("停止測試");
        renderStats();

        new Thread(() -> {
            int i = 0;
            while (running && System.currentTimeMillis() < testEndMs) {
                int k = -1;
                for (int n = 0; n < NAMES.length; n++) {
                    int idx = (i + n) % NAMES.length;
                    if (enabled[idx]) { k = idx; break; }
                }
                if (k < 0) break;
                // 從「剛用完那一項的下一格」繼續，否則停用的項目會讓第一項被重複選中
                i = (k + 1) % NAMES.length;

                Long ms = probe(k);
                record(k, ms);

                long now = System.currentTimeMillis();
                if (now - lastUiMs > 250) {
                    lastUiMs = now;
                    ui.post(this::renderStats);
                }

                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
            running = false;
            ui.post(this::finishTest);
        }, "netcheck-worker").start();
    }

    void stopTest() {
        running = false;
        runButton.setText("停止中…");
    }

    void finishTest() {
        runButton.setText("重新測試");
        buildReport();
        reportView.setText(reportText);
        saveRun();
        refreshHistory();
        exportButton.setEnabled(true);
        shareButton.setEnabled(true);
        renderStats();
    }

    void record(int kind, Long ms) {
        Stat s = stats[kind];
        long now = System.currentTimeMillis();
        s.total++;
        if (ms == null) {
            s.fail++;
            if (s.streak == 0) s.streakStart = now;
            s.streak++;
        } else {
            s.lat.add(ms);
            if (s.streak > 0) {
                if (s.streak >= 2) s.bursts.add(new long[]{s.streakStart, now, s.streak});
                s.streak = 0;
            }
        }
        if (timeline.length() > 300) timeline.delete(0, timeline.length() - 300);
        timeline.append(ms == null ? 'F' : '.');
    }

    // ---------------------------------------------------------------- 探測

    Long probe(int kind) {
        switch (kind) {
            case 0: return probeUdpDns();
            case 1: return probeUdpNtp();
            case 2: return probeUdpStun();
            case 3: return probeTcp("1.1.1.1", 443);
            default: return probeTcp("43.174.218.121", 20001);
        }
    }

    Long probeUdpDns() {
        DatagramSocket s = null;
        try {
            byte[] q = buildDnsQuery();
            s = new DatagramSocket();
            s.setSoTimeout(TIMEOUT_MS);
            byte[] buf = new byte[1024];
            DatagramPacket p = new DatagramPacket(q, q.length, InetAddress.getByName("8.8.8.8"), 53);
            long t0 = System.nanoTime();
            s.send(p);
            s.receive(new DatagramPacket(buf, buf.length));
            return (System.nanoTime() - t0) / 1000000L;
        } catch (Exception e) {
            return null;
        } finally {
            if (s != null) s.close();
        }
    }

    Long probeUdpNtp() {
        DatagramSocket s = null;
        try {
            byte[] req = new byte[48];
            req[0] = 0x1B;
            s = new DatagramSocket();
            s.setSoTimeout(TIMEOUT_MS);
            byte[] buf = new byte[1024];
            DatagramPacket p = new DatagramPacket(req, req.length, InetAddress.getByName("216.239.35.4"), 123);
            long t0 = System.nanoTime();
            s.send(p);
            s.receive(new DatagramPacket(buf, buf.length));
            return (System.nanoTime() - t0) / 1000000L;
        } catch (Exception e) {
            return null;
        } finally {
            if (s != null) s.close();
        }
    }

    Long probeUdpStun() {
        DatagramSocket s = null;
        try {
            byte[] req = new byte[20];
            req[0] = 0x00; req[1] = 0x01;      // Binding Request
            req[2] = 0x00; req[3] = 0x00;      // length 0
            req[4] = 0x21; req[5] = 0x12; req[6] = (byte) 0xA4; req[7] = 0x42; // magic
            byte[] tid = new byte[12];
            rnd.nextBytes(tid);
            System.arraycopy(tid, 0, req, 8, 12);

            s = new DatagramSocket();
            s.setSoTimeout(TIMEOUT_MS);
            byte[] buf = new byte[1024];
            DatagramPacket p = new DatagramPacket(req, req.length, InetAddress.getByName("162.159.207.0"), 3478);
            long t0 = System.nanoTime();
            s.send(p);
            s.receive(new DatagramPacket(buf, buf.length));
            return (System.nanoTime() - t0) / 1000000L;
        } catch (Exception e) {
            return null;
        } finally {
            if (s != null) s.close();
        }
    }

    Long probeTcp(String host, int port) {
        Socket s = new Socket();
        try {
            long t0 = System.nanoTime();
            s.connect(new InetSocketAddress(host, port), 3000);
            return (System.nanoTime() - t0) / 1000000L;
        } catch (Exception e) {
            return null;
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    byte[] buildDnsQuery() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] id = new byte[2];
        rnd.nextBytes(id);
        b.write(id, 0, 2);
        b.write(0x01); b.write(0x00);        // flags
        b.write(0x00); b.write(0x01);        // qdcount
        for (int i = 0; i < 6; i++) b.write(0x00); // an/ns/ar

        String label = "t" + Long.toHexString(rnd.nextLong()).replace("-", "").substring(0, 8);
        byte[] l = label.getBytes();
        b.write(l.length); b.write(l, 0, l.length);
        b.write(7); b.write("example".getBytes(), 0, 7);
        b.write(3); b.write("com".getBytes(), 0, 3);
        b.write(0x00);
        b.write(0x00); b.write(0x01);        // type A
        b.write(0x00); b.write(0x01);        // class IN
        return b.toByteArray();
    }

    // ---------------------------------------------------------------- 顯示

    void renderStats() {
        for (int i = 0; i < NAMES.length; i++) {
            Stat s = stats[i];
            String txt;
            if (s.total == 0) {
                txt = String.format(Locale.US, "%-6s 等待中…", SHORT[i]);
            } else {
                long p50 = s.pct(0.5);
                long mx = s.maxMs();
                txt = String.format(Locale.US, "%-5s %3d次 失敗%3d (%5.1f%%)  p50 %4s  max %4s",
                        SHORT[i], s.total, s.fail, s.lossPct(),
                        p50 < 0 ? "-" : p50 + "ms",
                        mx < 0 ? "-" : mx + "ms");
            }
            rows[i].setText(txt);
            if (s.total > 0 && s.lossPct() >= 10) rows[i].setTextColor(Color.parseColor("#C62828"));
            else if (s.total > 0 && s.lossPct() >= 3) rows[i].setTextColor(Color.parseColor("#EF6C00"));
            else rows[i].setTextColor(Color.parseColor("#2E7D32"));
        }

        if (running) {
            long left = Math.max(0, (testEndMs - System.currentTimeMillis()) / 1000);
            int total = 0, fail = 0;
            for (Stat s : stats) { total += s.total; fail += s.fail; }
            progressView.setText(String.format(Locale.US, "進行中… 剩餘 %d 秒 ｜ 已測 %,d 次、失敗 %,d", left, total, fail));
        } else {
            progressView.setText("");
        }

        timelineView.setText(timeline.toString());
    }

    // ---------------------------------------------------------------- 報告

    void buildReport() {
        StringBuilder b = new StringBuilder();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm:ss", Locale.US);
        SimpleDateFormat full = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        b.append("網路健檢報告\n");
        b.append("==============================\n");
        b.append("開始：").append(full.format(new Date(testStartMs))).append("\n");
        b.append("結束：").append(full.format(new Date())).append("\n");
        b.append("設定持續時間：").append(durationSec).append(" 秒\n");
        b.append("探測間隔：").append(INTERVAL_MS).append(" ms，逾時：").append(TIMEOUT_MS).append(" ms\n\n");

        b.append("【各項統計】\n");
        for (Stat s : stats) {
            if (s.total == 0) continue;
            long p50 = s.pct(0.5), p90 = s.pct(0.9), mx = s.maxMs();
            b.append(String.format(Locale.US, "%s\n", s.name));
            b.append(String.format(Locale.US, "  取樣 %d 次，失敗 %d 次（%.1f%%）\n", s.total, s.fail, s.lossPct()));
            if (mx >= 0) {
                b.append(String.format(Locale.US, "  延遲 p50 %d ms、p90 %d ms、max %d ms\n", p50, p90, mx));
            } else {
                b.append("  全部失敗，無延遲資料\n");
            }
        }

        b.append("\n【斷流段】（連續 2 次以上失敗；跨度含輪測間隔，非連續中斷）\n");
        boolean anyBurst = false;
        for (Stat s : stats) {
            if (s.bursts.isEmpty()) continue;
            List<long[]> list = new ArrayList<>(s.bursts);
            Collections.sort(list, (x, y) -> Long.compare(y[1] - y[0], x[1] - x[0]));
            b.append(s.name).append("\n");
            int n = Math.min(12, list.size());
            for (int i = 0; i < n; i++) {
                long[] v = list.get(i);
                b.append(String.format(Locale.US, "  %s 起，連續 %d 次失敗，跨度約 %.1f 秒\n",
                        hm.format(new Date(v[0])), v[2], (v[1] - v[0]) / 1000.0));
            }
            if (list.size() > n) b.append("  …另有 ").append(list.size() - n).append(" 段\n");
            anyBurst = true;
        }
        if (!anyBurst) b.append("  無\n");

        b.append("\n【時間軸】（. = 成功，F = 失敗）\n");
        String tl = timeline.toString();
        for (int i = 0; i < tl.length(); i += 60) {
            b.append(tl, i, Math.min(tl.length(), i + 60)).append("\n");
        }

        b.append("\n【判讀提示】\n");
        double dnsLoss = stats[0].total > 0 ? stats[0].lossPct() : -1;
        double udpHi = Math.max(stats[1].lossPct(), stats[2].lossPct());
        if (dnsLoss >= 0 && dnsLoss < 3 && udpHi >= 10) {
            b.append("  【關鍵型態】UDP 53（DNS）正常，但其他 UDP 埠失敗率 ");
            b.append(String.format(Locale.US, "%.0f", udpHi));
            b.append("%：只放行 DNS、其他 UDP 被丟棄。\n");
            b.append("  這是電信端政策／CGNAT 的 UDP 問題，與手機、訊號強度、APN 設定無關。\n");
        }
        if (stats[4].total > 0 && stats[4].lossPct() >= 10) {
            b.append("  【遊戲節點】遊戲實際伺服器失敗率 ");
            b.append(String.format(Locale.US, "%.0f", stats[4].lossPct()));
            b.append("%，這是遊戲斷線重連的直接原因；對照其他項目即可判斷是線路還是伺服器端。\n");
        }
        double maxLoss = 0;
        for (Stat s : stats) maxLoss = Math.max(maxLoss, s.lossPct());
        if (maxLoss >= 10) {
            b.append("  有項目的失敗率超過 10%，已達會讓遊戲斷線重連的程度。\n");
            b.append("  若 UDP 明顯失敗但 TCP 正常，請檢查是否為電信端 CGNAT 的 UDP 會話問題。\n");
        } else if (maxLoss >= 3) {
            b.append("  失敗率 3–10%，屬於輕微不穩，仍可能造成遊戲偶發卡頓。\n");
        } else {
            b.append("  各項失敗率皆低於 3%，連線品質良好。\n");
        }
        reportText = b.toString();
    }

    void saveReport() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File f = new File(dir, "netcheck_" + stamp + ".txt");
            FileOutputStream fos = new FileOutputStream(f);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(reportText);
            w.close();
            fos.close();
            Toast.makeText(this, "已儲存：\n" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "儲存失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void shareReport() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("網路健檢報告", reportText));
        } catch (Exception ignored) { }

        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "網路健檢報告");
        i.putExtra(Intent.EXTRA_TEXT, reportText);
        startActivity(Intent.createChooser(i, "分享報告（已同時複製到剪貼簿）"));
    }

    // ---------------------------------------------------------------- 歷史紀錄

    File historyDir() {
        File d = new File(getFilesDir(), "history");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    String shortSummary() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < stats.length; i++) {
            if (i > 0) b.append(" / ");
            Stat s = stats[i];
            b.append(SHORT[i]).append(' ');
            if (s.total == 0) b.append("--");
            else b.append(String.format(Locale.US, "%.1f%%", s.lossPct()));
        }
        return b.toString();
    }

    void saveRun() {
        try {
            String summary = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(testStartMs))
                    + " ｜ " + durationSec + "秒 ｜ " + shortSummary();
            File f = new File(historyDir(), String.format(Locale.US, "%013d.txt", testStartMs));
            FileOutputStream fos = new FileOutputStream(f);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(summary + "\n\n" + reportText);
            w.close();
            fos.close();
        } catch (Exception ignored) { }
    }

    String readFirstLine(File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String l = br.readLine();
            return l == null ? f.getName() : l;
        } catch (Exception e) {
            return f.getName();
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) { }
        }
    }

    void refreshHistory() {
        if (historyBox == null) return;
        historyBox.removeAllViews();
        File[] fs = historyDir().listFiles();
        if (fs == null || fs.length == 0) {
            TextView tv = new TextView(this);
            tv.setText("尚無紀錄（每次測完會自動存一筆）");
            tv.setTextSize(13f);
            tv.setTextColor(Color.parseColor("#888888"));
            historyBox.addView(tv);
            return;
        }
        Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
        int n = Math.min(fs.length, 20);
        for (int i = 0; i < n; i++) {
            final File f = fs[i];
            TextView tv = new TextView(this);
            tv.setText("•  " + readFirstLine(f));
            tv.setTextSize(12f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setPadding(0, dp(9), 0, dp(9));
            tv.setOnClickListener(v -> loadRecord(f));
            historyBox.addView(tv);
        }
        if (fs.length > n) {
            TextView more = new TextView(this);
            more.setText("…另有 " + (fs.length - n) + " 筆");
            more.setTextSize(12f);
            more.setTextColor(Color.parseColor("#888888"));
            historyBox.addView(more);
        }
    }

    void loadRecord(File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            reportText = sb.toString();
            reportView.setText(reportText);
            exportButton.setEnabled(true);
            shareButton.setEnabled(true);
            Toast.makeText(this, "已載入該次紀錄（下方報告區）", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "讀取失敗", Toast.LENGTH_SHORT).show();
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) { }
        }
    }

    void clearHistory() {
        File[] fs = historyDir().listFiles();
        int n = 0;
        if (fs != null) for (File f : fs) if (f.delete()) n++;
        Toast.makeText(this, "已清除 " + n + " 筆紀錄", Toast.LENGTH_SHORT).show();
        refreshHistory();
    }
}
