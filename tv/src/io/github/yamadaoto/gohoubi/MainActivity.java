package io.github.yamadaoto.gohoubi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * ごほうび動画 テレビ版。
 * GitHub Pages のページを全画面の WebView で開き、リモコンのキーをページに渡すだけの うすいアプリ。
 * GAS の URL と合言葉はこのコードに書かない(はじめの画面か adb で入れ、テレビの中にだけ保存する)。
 */
public class MainActivity extends Activity {
    static final String ALLOWED_PREFIX = "https://yamada-oto.github.io/gohoubi-douga/";
    static final String VERSION = "__VERSION__";
    static final int REQ_VOICE = 1;

    SharedPreferences prefs;
    WebView web;
    boolean navOn = false;      // ページがリモコンの操作を受けられる状態か
    boolean setupShown = false;
    String voiceField = null;
    TextView keyView;

    // ゆび(プレーヤーの中のボタンを、リモコンで自分で押すための まる)
    View cursor;
    boolean ptrOn = false, ptrOkDown = false, ptrBackDown = false;
    float px, py, pl, pt, pr, pb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE);
        handleIntent(getIntent());
        start();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (handleIntent(i)) start();
    }

    /** adb からの設定: --es url '…' で保存、--ez reset true で消す。変わったら true。 */
    boolean handleIntent(Intent i) {
        if (i == null) return false;
        if (i.getBooleanExtra("reset", false)) {
            prefs.edit().remove("url").apply();
            return true;
        }
        String u = i.getStringExtra("url");
        if (u != null && valid(u.trim())) {
            prefs.edit().putString("url", u.trim()).apply();
            return true;
        }
        return false;
    }

    static boolean valid(String u) {
        return u.startsWith(ALLOWED_PREFIX) && u.contains("#") && u.contains("gas=") && u.contains("pass=");
    }

    void start() {
        String u = prefs.getString("url", null);
        if (u == null) showSetup(); else showWeb(u);
    }

    // ---------------- はじめの設定(親向け) ----------------
    void showSetup() {
        destroyWeb();
        setupShown = true;
        int pad = dp(32);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad * 2, pad, pad * 2, pad);

        TextView title = new TextView(this);
        title.setText("ごほうび動画 テレビ版 のせってい(おうちのかた向け)");
        title.setTextSize(26);
        box.addView(title);

        TextView info = new TextView(this);
        info.setText("iPad の ProSurf に入れたのと同じ URL(…/gohoubi-douga/#gas=…&pass=…)を入れてください。\n"
                + "長いので、スマホの Google TV アプリ(または Google Home アプリ)のリモコンのキーボードから貼り付けるか、"
                + "iMac から adb で入れるのがらくです。\n"
                + "入れた URL はこのテレビの中にだけ保存します。");
        info.setTextSize(18);
        info.setPadding(0, dp(12), 0, dp(12));
        box.addView(info);

        final EditText edit = new EditText(this);
        edit.setHint("https://yamada-oto.github.io/gohoubi-douga/#gas=…&pass=…");
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setTextSize(18);
        box.addView(edit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView err = new TextView(this);
        err.setTextSize(18);
        err.setTextColor(0xFFFF8A80);

        Button save = new Button(this);
        save.setText("ほぞんして ひらく");
        save.setTextSize(22);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String u = edit.getText().toString().trim();
                if (!valid(u)) {
                    err.setText("URL の形がちがいます。" + ALLOWED_PREFIX + "#gas=…&pass=… の形で入れてください。");
                    return;
                }
                prefs.edit().putString("url", u).apply();
                start();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        box.addView(save, lp);
        box.addView(err);

        keyView = new TextView(this);
        keyView.setTextSize(16);
        keyView.setPadding(0, dp(24), 0, 0);
        keyView.setText("たしかめ用: リモコンのボタンを押すと、ここに名前が出ます(マイクのボタンも試せます)\nアプリの版: " + VERSION);
        box.addView(keyView);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
        edit.requestFocus();
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ---------------- ページを開く ----------------
    void showWeb(String url) {
        destroyWeb();
        setupShown = false;
        keyView = null;
        navOn = false;
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // 「はじめる」をリモコンで押して再生できるように
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(0xFFFFF7ED);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                // 画面全体が ほかのサイトに変わるのは止める(プレーヤーの中のリンクで迷子にならないように)
                return !r.getUrl().toString().startsWith(ALLOWED_PREFIX);
            }

            @Override
            public void onPageStarted(WebView v, String u, Bitmap icon) {
                navOn = false;
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                // ページがリモコンを受けられるか、読みこみのたびに確かめる
                v.evaluateJavascript("window.gohoubiTv?1:0", new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String r) { navOn = "1".equals(r); }
                });
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!r.isForMainFrame()) return;
                String html = "<html><body style='background:#fff7ed;color:#374151;font-family:sans-serif;"
                        + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;font-size:6vh;text-align:center'>"
                        + "つながらないよ<br>もどるボタンで とじて、もういちど あけてね</body></html>";
                v.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            }
        });
        web.addJavascriptInterface(new Bridge(), "GohoubiTV");
        FrameLayout root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cursor = new CursorView(this);
        cursor.setVisibility(View.GONE);
        root.addView(cursor, new FrameLayout.LayoutParams(dp(44), dp(44)));
        setContentView(root);
        web.requestFocus();
        web.loadUrl(url);
    }

    void destroyWeb() {
        if (web == null) return;
        ViewGroup p = (ViewGroup) web.getParent();
        if (p != null) p.removeView(web);
        web.destroy();
        web = null;
        ptrOn = false;
        cursor = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    /** ページ(index.html)から呼ばれる。秘密は渡さない。 */
    class Bridge {
        @JavascriptInterface
        public String version() { return VERSION; }

        @JavascriptInterface
        public void setNav(final boolean on) {
            runOnUiThread(new Runnable() { @Override public void run() { navOn = on; } });
        }

        @JavascriptInterface
        public void playing(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        /** ゆびを出す・消す。場所は画面に対する割合(プレーヤーの四角の中だけ動く) */
        @JavascriptInterface
        public void pointer(final boolean on, final double l, final double t, final double r, final double b) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (web == null || cursor == null) return;
                    if (!on) { ptrOn = false; cursor.setVisibility(View.GONE); return; }
                    int w = web.getWidth(), h = web.getHeight();
                    pl = (float) (l * w); pt = (float) (t * h); pr = (float) (r * w); pb = (float) (b * h);
                    px = pl + (pr - pl) * 0.86f;   // こうこくのスキップが出やすい 右下から
                    py = pt + (pb - pt) * 0.80f;
                    ptrOkDown = false; ptrBackDown = false;
                    ptrOn = true;
                    placeCursor();
                    cursor.setVisibility(View.VISIBLE);
                }
            });
        }

        @JavascriptInterface
        public void input(final String id, final String title, final String value) {
            runOnUiThread(new Runnable() { @Override public void run() { showInput(id, title, value); } });
        }

        @JavascriptInterface
        public void listen(final String id) {
            runOnUiThread(new Runnable() { @Override public void run() { startVoice(id); } });
        }
    }

    // ---------------- 文字を入れる(テレビのキーボード) ----------------
    void showInput(final String id, String title, String value) {
        if (web == null || isFinishing()) return;
        final EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        edit.setTextSize(26);
        edit.setText(value == null ? "" : value);
        edit.selectAll();
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(edit)
                .setPositiveButton("さがす", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) { sendText(id, edit.getText().toString()); }
                })
                .setNegativeButton("やめる", null)
                .create();
        edit.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || enter) {
                    sendText(id, edit.getText().toString());
                    dlg.dismiss();
                    return true;
                }
                return false;
            }
        });
        if (dlg.getWindow() != null) {
            dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dlg.show();
        edit.requestFocus();
        edit.post(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    void sendText(String id, String text) {
        js("window.gohoubiTv&&gohoubiTv.text(" + JSONObject.quote(id) + "," + JSONObject.quote(text) + ",true)");
    }

    // ---------------- こえで入れる(リモコンのマイク) ----------------
    void startVoice(String id) {
        if (web == null) return;
        voiceField = id;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "who".equals(id) ? "だれの?" : "なにをみる?");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            js("window.gohoubiTv&&gohoubiTv.voiceError()");
        } catch (SecurityException e) {
            js("window.gohoubiTv&&gohoubiTv.voiceError()");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE || voiceField == null) return;
        String id = voiceField;
        voiceField = null;
        if (res != RESULT_OK || data == null) return;   // やめたときは なにもしない
        ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        String text = (r != null && !r.isEmpty() && r.get(0) != null) ? r.get(0) : "";
        js("window.gohoubiTv&&gohoubiTv.voice(" + JSONObject.quote(id) + "," + JSONObject.quote(text) + ")");
    }

    // ---------------- ゆび ----------------
    static class CursorView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        CursorView(Context c) { super(c); }
        @Override
        protected void onDraw(Canvas c) {
            float r = getWidth() / 2f;
            p.setStyle(Paint.Style.FILL); p.setColor(0x66000000); c.drawCircle(r, r, r, p);
            p.setColor(0xFFFFFFFF); c.drawCircle(r, r, r * 0.80f, p);
            p.setColor(0xFFEA580C); c.drawCircle(r, r, r * 0.52f, p);
        }
    }

    void placeCursor() {
        if (cursor == null) return;
        px = Math.max(pl, Math.min(pr, px));
        py = Math.max(pt, Math.min(pb, py));
        cursor.setTranslationX(px - cursor.getLayoutParams().width / 2f);
        cursor.setTranslationY(py - cursor.getLayoutParams().height / 2f);
    }

    void endPointer() {
        ptrOn = false;
        if (cursor != null) cursor.setVisibility(View.GONE);
        js("window.gohoubiTv&&gohoubiTv.pointerOff&&gohoubiTv.pointerOff()");
    }

    /** ゆびの場所を、指でさわったのと同じように押す */
    void tap(final float x, final float y) {
        if (web == null) return;
        final long t0 = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0);
        d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(d);
        d.recycle();
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (web == null) return;
                MotionEvent u = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
                u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                web.dispatchTouchEvent(u);
                u.recycle();
            }
        }, 70);
    }

    /** ゆびが出ている間のリモコン。受けたら true */
    boolean pointerKey(KeyEvent e) {
        int c = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int step = dp(14) + Math.min(e.getRepeatCount(), 12) * dp(4);   // 押しつづけると速くなる
        switch (c) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (down) { px -= step; placeCursor(); } return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) { px += step; placeCursor(); } return true;
            case KeyEvent.KEYCODE_DPAD_UP: if (down) { py -= step; placeCursor(); } return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) { py += step; placeCursor(); } return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // 「ゆびで おす」を押した指をはなしたときには 押さない
                if (down) { if (e.getRepeatCount() == 0) ptrOkDown = true; }
                else if (ptrOkDown) { ptrOkDown = false; tap(px, py); }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) ptrBackDown = true;
                else if (ptrBackDown) { ptrBackDown = false; endPointer(); }
                return true;
            default:
                return false;
        }
    }

    // ---------------- リモコン ----------------
    static String keyName(int c) {
        switch (c) {
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return "ok";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "playpause";
            case KeyEvent.KEYCODE_MEDIA_PLAY: return "play";
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return "pause";
            case KeyEvent.KEYCODE_MEDIA_REWIND: return "rew";
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return "ff";
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_VOICE_ASSIST:
            case KeyEvent.KEYCODE_ASSIST: return "mic";
            default: return null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int c = e.getKeyCode();
        if (setupShown) {
            if (keyView != null && e.getAction() == KeyEvent.ACTION_DOWN) {
                keyView.setText("たしかめ用: 押したボタン = " + KeyEvent.keyCodeToString(c) + "\nアプリの版: " + VERSION);
            }
            return super.dispatchKeyEvent(e);
        }
        if (web == null) return super.dispatchKeyEvent(e);
        if (ptrOn) return pointerKey(e) || super.dispatchKeyEvent(e);
        if (c == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) handleBack();
            return true;
        }
        String k = keyName(c);
        if (k != null && navOn) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) js("window.gohoubiTv&&gohoubiTv.key('" + k + "')");
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    void handleBack() {
        if (web == null) { finish(); return; }
        if (!navOn) { nativeBack(); return; }
        web.evaluateJavascript("(window.gohoubiTv&&gohoubiTv.back())?1:0", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (!"1".equals(v)) nativeBack();
            }
        });
    }

    void nativeBack() {
        if (web != null && web.canGoBack()) web.goBack(); else finish();
    }

    // ---------------- ホームボタンなどで見えなくなったとき ----------------
    @Override
    protected void onPause() {
        if (web != null) {
            if (ptrOn) endPointer();
            js("window.gohoubiTv&&gohoubiTv.hidden()");   // 止めて、見た分を保存する
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        destroyWeb();
        super.onDestroy();
    }
}
