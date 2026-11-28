package ni.cerquita.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.http.SslError;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_FILE = 11;
    private static final int REQ_LOCATION = 12;
    private static final String HOST = "javiher19926-alt.github.io";

    private static final int BIO_AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG
            | BiometricManager.Authenticators.BIOMETRIC_WEAK;

    private WebView web;
    private FrameLayout root;
    private LinearLayout overlay;
    private ProgressBar spinner;
    private TextView msgView;
    private Button retryBtn, browserBtn;
    private boolean mainError;
    private String lastUrl;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable slowCheck = () -> {
        if (overlay != null && overlay.getVisibility() == View.VISIBLE && !mainError) {
            msgView.setText("La conexión está lenta. Seguimos intentando abrir Cerquita…");
            retryBtn.setVisibility(View.VISIBLE);
            browserBtn.setVisibility(View.VISIBLE);
        }
    };
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private String geoOrigin;
    private GeolocationPermissions.Callback geoCallback;

    /* Guarda descargas (PDF, calendario, respaldos) y comparte archivos desde la web. */
    private static final String BRIDGE_JS =
            "(function(){if(window.__cqBridge)return;window.__cqBridge=1;" +
            "var rd=function(b){return new Promise(function(ok,ko){var f=new FileReader();f.onload=function(){ok(String(f.result).split(',')[1]||'')};f.onerror=ko;f.readAsDataURL(b)})};" +
            "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;" +
            "if(!a||!/^(blob:|data:)/.test(a.href))return;e.preventDefault();var n=a.getAttribute('download')||'archivo';" +
            "fetch(a.href).then(function(r){return r.blob()}).then(function(b){return rd(b).then(function(d){CerquitaAndroid.save(d,n,b.type||'application/octet-stream')})})" +
            ".catch(function(){CerquitaAndroid.toast('No se pudo guardar el archivo.')})},true);" +
            "navigator.canShare=function(d){return !!(d&&d.files&&d.files.length)};" +
            "navigator.share=function(d){var f=d&&d.files&&d.files[0];if(!f){CerquitaAndroid.shareText([d&&d.text,d&&d.url].filter(Boolean).join('\\n'));return Promise.resolve()}" +
            "return rd(f).then(function(x){CerquitaAndroid.share(x,f.name||'archivo',f.type||'application/octet-stream',(d.text||d.title||''))})};" +
            "})();";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B2545"));
        setContentView(root);
        buildOverlay();

        try {
            web = new WebView(this);
        } catch (Throwable t) {
            showError("Este teléfono no tiene activo el componente «Android System WebView», que la app necesita para mostrarse.\n\nActualícelo o actívelo en Play Store y vuelva a abrir Cerquita. Mientras tanto puede usar Cerquita en el navegador.", false);
            return;
        }
        web.setBackgroundColor(Color.parseColor("#F7F5F0"));
        root.addView(web, 0, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString() + " CerquitaApp/" + BuildConfig.VERSION_NAME);

        web.addJavascriptInterface(new Bridge(), "CerquitaAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("https".equals(u.getScheme()) && HOST.equalsIgnoreCase(u.getHost())
                        && u.getPath() != null && u.getPath().startsWith(BuildConfig.SCOPE)) {
                    return false;
                }
                openExternal(u);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mainError = false;
                if (url != null && url.startsWith("https://")) lastUrl = url;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(BRIDGE_JS, null);
                if (!mainError) hideOverlay();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (!req.isForMainFrame()) return;
                mainError = true;
                showError(friendlyError(err.getErrorCode()) + "\n\n(Detalle: " + err.getErrorCode() + " · "
                        + err.getDescription() + ")", true);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse res) {
                if (!req.isForMainFrame() || res.getStatusCode() < 400) return;
                mainError = true;
                showError("La página de Cerquita no respondió bien (error " + res.getStatusCode()
                        + "). Puede ser un cambio que se está publicando: espere un minuto y vuelva a intentar.", true);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler h, SslError err) {
                h.cancel();
                Uri u = Uri.parse(err.getUrl() == null ? "" : err.getUrl());
                if (!HOST.equalsIgnoreCase(u.getHost())) return;
                mainError = true;
                showError("El teléfono rechazó la conexión segura con Cerquita.\n\nRevise que la fecha y la hora del teléfono estén en automático y que sean correctas, y vuelva a intentar.\n\n(Detalle SSL: "
                        + err.getPrimaryError() + ")", true);
            }

            @TargetApi(26)
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail d) {
                if (web != null) {
                    root.removeView(web);
                    web.destroy();
                    web = null;
                }
                recreate();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                cameraUri = null;
                try {
                    Intent intent;
                    if (p.isCaptureEnabled()) {
                        File f = new File(getCacheDir(), "foto-" + System.currentTimeMillis() + ".jpg");
                        cameraUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                        intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else {
                        intent = p.createIntent();
                        if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        }
                    }
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    toast("No se pudo abrir la cámara o la galería.");
                    return false;
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, false);
                } else {
                    geoOrigin = origin;
                    geoCallback = cb;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web != null && web.canGoBack()) web.goBack();
                else finish();
            }
        });

        showLoading();
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(BuildConfig.START_URL);
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(dp(32), dp(32), dp(32), dp(32));
        overlay.setBackgroundColor(Color.parseColor("#0B2545"));
        overlay.setClickable(true);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setGravity(Gravity.CENTER);
        overlay.addView(title);

        spinner = new ProgressBar(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(44), dp(44));
        sp.topMargin = dp(24);
        overlay.addView(spinner, sp);

        msgView = new TextView(this);
        msgView.setTextColor(Color.parseColor("#DCE6F0"));
        msgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        msgView.setGravity(Gravity.CENTER);
        msgView.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(20);
        overlay.addView(msgView, mp);

        retryBtn = new Button(this);
        retryBtn.setText("Volver a intentar");
        retryBtn.setAllCaps(false);
        retryBtn.setOnClickListener(v -> retry());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(24);
        overlay.addView(retryBtn, bp);

        browserBtn = new Button(this);
        browserBtn.setText("Abrir en el navegador");
        browserBtn.setAllCaps(false);
        browserBtn.setOnClickListener(v -> openExternal(Uri.parse(BuildConfig.START_URL)));
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bp2.topMargin = dp(10);
        overlay.addView(browserBtn, bp2);

        root.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void showLoading() {
        overlay.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);
        msgView.setText("Abriendo…");
        retryBtn.setVisibility(View.GONE);
        browserBtn.setVisibility(View.GONE);
        handler.removeCallbacks(slowCheck);
        handler.postDelayed(slowCheck, 20000);
    }

    private void hideOverlay() {
        handler.removeCallbacks(slowCheck);
        overlay.setVisibility(View.GONE);
    }

    private void showError(String m, boolean canRetry) {
        handler.removeCallbacks(slowCheck);
        overlay.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE);
        msgView.setText(m);
        retryBtn.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        browserBtn.setVisibility(View.VISIBLE);
    }

    private void retry() {
        if (web == null) {
            recreate();
            return;
        }
        mainError = false;
        showLoading();
        web.loadUrl(lastUrl != null ? lastUrl : BuildConfig.START_URL);
    }

    private String friendlyError(int code) {
        switch (code) {
            case WebViewClient.ERROR_HOST_LOOKUP:
            case WebViewClient.ERROR_CONNECT:
                return "No hay conexión a internet. Revise los datos móviles o el wifi y vuelva a intentar.";
            case WebViewClient.ERROR_TIMEOUT:
                return "La conexión tardó demasiado. Vuelva a intentar cuando tenga mejor señal.";
            case WebViewClient.ERROR_FAILED_SSL_HANDSHAKE:
                return "El teléfono rechazó la conexión segura. Revise que la fecha y la hora estén en automático.";
            default:
                return "No se pudo abrir Cerquita.";
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (cameraUri != null) {
                result = new Uri[]{cameraUri};
            } else if (data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
        cameraUri = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(requestCode, perms, res);
        if (requestCode == REQ_LOCATION && geoCallback != null) {
            boolean ok = false;
            for (int r : res) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
            geoCallback.invoke(geoOrigin, ok, false);
            geoCallback = null;
        }
    }

    private void openExternal(Uri u) {
        try {
            Intent i;
            if ("intent".equals(u.getScheme())) {
                i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
            } else {
                i = new Intent(Intent.ACTION_VIEW, u);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("No hay una aplicación para abrir este enlace.");
        }
    }

    private void toast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    private String safeName(String n) {
        String r = n == null ? "archivo" : n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return r.isEmpty() ? "archivo" : r;
    }

    private Uri saveToDownloads(byte[] bytes, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Cerquita");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("sin uri");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new Exception("sin salida");
                os.write(bytes);
            }
            return uri;
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(bytes);
            }
            return FileProvider.getUriForFile(this, getPackageName() + ".files", f);
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void toast(String m) {
            MainActivity.this.toast(m);
        }

        @JavascriptInterface
        public void save(String b64, String name, String mime) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                String n = safeName(name);
                Uri uri = saveToDownloads(bytes, n, mime);
                if (mime != null && mime.startsWith("text/calendar")) {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "text/calendar");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(i);
                        return;
                    } catch (ActivityNotFoundException ignored) {
                    }
                }
                MainActivity.this.toast("Guardado en Descargas/Cerquita: " + n);
            } catch (Exception e) {
                MainActivity.this.toast("No se pudo guardar el archivo.");
            }
        }

        @JavascriptInterface
        public void share(String b64, String name, String mime, String text) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "compartir");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, safeName(name));
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime == null || mime.isEmpty() ? "application/pdf" : mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                if (text != null && !text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, "Enviar por");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(chooser);
            } catch (Exception e) {
                MainActivity.this.toast("No se pudo compartir el archivo.");
            }
        }

        @JavascriptInterface
        public void shareText(String text) {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
            Intent chooser = Intent.createChooser(send, "Compartir");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
        }

        @JavascriptInterface
        public void shareApk() {
            try {
                File src = new File(getApplicationInfo().sourceDir);
                File dir = new File(getCacheDir(), "compartir");
                if (!dir.exists()) dir.mkdirs();
                String name = getString(R.string.app_name).replace(' ', '-') + ".apk";
                File f = new File(dir, name);
                try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                     FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/vnd.android.package-archive");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_TEXT, "Instale " + getString(R.string.app_name)
                        + ": abra el archivo y permita instalar apps de origen desconocido.");
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, "Enviar la app por");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(chooser);
            } catch (Exception e) {
                MainActivity.this.toast("No se pudo preparar el archivo de la app.");
            }
        }

        @JavascriptInterface
        public boolean bioAvailable() {
            return BiometricManager.from(MainActivity.this).canAuthenticate(BIO_AUTH)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        }

        @JavascriptInterface
        public void bio(String title) {
            runOnUiThread(() -> {
                BiometricPrompt prompt = new BiometricPrompt(MainActivity.this,
                        ContextCompat.getMainExecutor(MainActivity.this),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                                web.evaluateJavascript("window.__bioCb&&window.__bioCb(true)", null);
                            }

                            @Override
                            public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                                web.evaluateJavascript("window.__bioCb&&window.__bioCb(false)", null);
                            }
                        });
                BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title == null || title.isEmpty() ? "Cerquita Equipo" : title)
                        .setSubtitle("Use su huella para continuar")
                        .setNegativeButtonText("Usar la clave")
                        .setAllowedAuthenticators(BIO_AUTH)
                        .build();
                prompt.authenticate(info);
            });
        }
    }
}
