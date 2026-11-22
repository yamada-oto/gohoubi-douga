package io.github.yamadaoto.gohoubi;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * アプリ内の更新。起動したとき1回だけ、公開ページの tv-version.txt(版の番号だけ)を見て、
 * いまの版より新しければ「いれかえる?」と出す。決定を押すと tv.apk を取ってきて入れかえる。
 * 入れかえの最後の確認は Android の画面が出す(アプリだけでは、だまって入れかえられない)。
 * 失敗してもアプリはそのまま使える(いままでどおり Downloader で入れなおせる)。
 */
final class Updater {
    static final String BASE = MainActivity.ALLOWED_PREFIX;   // 公開ページ(https)
    static final String ACTION = "io.github.yamadaoto.gohoubi.INSTALL_DONE";
    static boolean checked = false;

    static void check(final Activity a) {
        if (checked) return;
        checked = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int latest = Integer.parseInt(readText(BASE + "tv-version.txt").trim());
                    int mine = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode;
                    if (latest > mine) ask(a, latest);
                } catch (Exception e) {
                    // しらべられなかったときは何も出さない(ふだんどおり使える)
                }
            }
        }).start();
    }

    static String readText(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
            InputStream in = c.getInputStream();
            byte[] buf = new byte[64];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } finally { c.disconnect(); }
    }

    static void ask(final Activity a, final int latest) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (a.isFinishing()) return;
                AlertDialog d = new AlertDialog.Builder(a)
                        .setTitle("あたらしい版があります")
                        .setMessage("おうちのひとが「いれかえる」を おしてね(" + latest + "ばん)。\nあとで にするときは「あとで」。")
                        .setPositiveButton("いれかえる", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface x, int w) { begin(a); }
                        })
                        .setNegativeButton("あとで", null)
                        .create();
                d.show();
                // まちがって おさないよう、はじめは「あとで」に わくを置く
                d.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
            }
        });
    }

    static void begin(final Activity a) {
        // 「このアプリからの インストールを ゆるす」が まだなら、その設定の画面をひらく
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
                toast(a, "ゆるしてから、アプリをとじて もういちど あけてね");
            } catch (Exception e) {
                toast(a, "せっていの画面がひらけなかったよ。Downloader でいれてね");
            }
            return;
        }
        toast(a, "ダウンロードしているよ");
        new Thread(new Runnable() {
            @Override public void run() { install(a); }
        }).start();
    }

    static void install(final Activity a) {
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        int id = -1;
        try {
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(a.getPackageName());
            id = pi.createSession(p);
            session = pi.openSession(id);

            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "tv.apk").openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(30000);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
            OutputStream out = session.openWrite("gohoubi-tv.apk", 0, -1);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            session.fsync(out);
            out.close(); in.close(); c.disconnect();

            // 結果は、このアプリの受け取り口に返ってくる
            final int sid = id;
            IntentFilter f = new IntentFilter(ACTION);
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent i) {
                    int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                    if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent ask = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);   // Android の確認画面
                        if (ask != null) { ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(ask); }
                    } else if (st != PackageInstaller.STATUS_SUCCESS) {
                        toast(a, "いれかえできなかったよ。いままでのアプリは そのまま つかえるよ");
                        try { ctx.unregisterReceiver(this); } catch (Exception e) {}
                    }
                    // 成功したときは、アプリが入れかわって とじる
                }
            };
            if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            else a.registerReceiver(r, f);
            Intent ni = new Intent(ACTION).setPackage(a.getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;   // システムが結果を書きこむので MUTABLE
            PendingIntent pend = PendingIntent.getBroadcast(a, sid, ni, flags);
            session.commit(pend.getIntentSender());
        } catch (Exception e) {
            try { if (session != null) session.abandon(); } catch (Exception x) {}
            toast(a, "ダウンロードできなかったよ。いままでのアプリは そのまま つかえるよ");
        }
    }

    static void toast(final Activity a, final String t) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() { Toast.makeText(a, t, Toast.LENGTH_LONG).show(); }
        });
    }
}
