package com.thorium.preview;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;

/** A small browser that remains inside Lucent and routes ordinary web
 * downloads through Android's DownloadManager into the public Downloads
 * directory. It deliberately retains WebView's default TLS validation. */
public final class BrowserActivity extends Activity {
    public static final String EXTRA_URL = "com.thorium.preview.BROWSER_URL";
    private static final String HOME = "https://www.google.com/";
    private static final String PREFS = "lucent_browser";
    private static final String LAST_URL = "last_url";
    private static final String TAG = "LucentBrowser";
    private static final int BAR_COLOR = Color.rgb(11, 14, 20);
    private static final int FIELD_COLOR = Color.rgb(27, 32, 42);

    private WebView webView;
    private EditText address;
    private TextView progress;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Web audio plays on STREAM_MUSIC, the same stream as previews and
        // gameplay. Keep the hardware keys on it here too, so leaving the
        // browser never returns the user to a differently-adjusted volume.
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().setStatusBarColor(BAR_COLOR);
        getWindow().setNavigationBarColor(BAR_COLOR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(7), dp(10), dp(7));
        bar.setBackgroundColor(BAR_COLOR);

        bar.addView(button("CLOSE", view -> finish()), buttonParams(dp(78)));
        bar.addView(button("‹", view -> {
            if (webView.canGoBack()) webView.goBack(); else finish();
        }), buttonParams(dp(48)));
        bar.addView(button("›", view -> {
            if (webView.canGoForward()) webView.goForward();
        }), buttonParams(dp(48)));
        bar.addView(button("↻", view -> webView.reload()), buttonParams(dp(48)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(Color.WHITE);
        address.setHintTextColor(Color.rgb(135, 145, 163));
        address.setHint("Search or enter address");
        address.setTextSize(16);
        address.setPadding(dp(14), 0, dp(14), 0);
        address.setBackgroundColor(FIELD_COLOR);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigate(address.getText().toString());
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(
                0, dp(46), 1f);
        addressParams.setMargins(dp(8), 0, dp(8), 0);
        bar.addView(address, addressParams);
        bar.addView(button("GO", view -> navigate(address.getText().toString())),
                buttonParams(dp(54)));

        progress = new TextView(this);
        progress.setTextColor(Color.WHITE);
        progress.setTextSize(12);
        progress.setGravity(Gravity.CENTER);
        progress.setBackgroundColor(Color.rgb(22, 27, 36));
        progress.setVisibility(View.GONE);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view,
                                                               WebResourceRequest request) {
                return openUrl(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openUrl(Uri.parse(url));
            }

            @Override public void onPageFinished(WebView view, String url) {
                address.setText(url);
                rememberUrl(url);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                if (value >= 100) {
                    progress.setVisibility(View.GONE);
                } else {
                    progress.setVisibility(View.VISIBLE);
                    progress.setText("LOADING  " + value + "%");
                }
            }

            @Override public void onReceivedTitle(WebView view, String title) {
                if (view.getUrl() != null) address.setText(view.getUrl());
            }
        });
        webView.setDownloadListener(new BrowserDownloadListener());

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (state == null || webView.restoreState(state) == null)
            webView.loadUrl(initialUrl(getIntent()));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String requested = initialUrl(intent);
        if (!HOME.equals(requested)) webView.loadUrl(requested);
    }

    private String initialUrl(Intent intent) {
        String requested = intent == null ? null : intent.getStringExtra(EXTRA_URL);
        if (requested != null && !requested.trim().isEmpty()) return requested.trim();
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_URL, HOME);
    }

    private void rememberUrl(String url) {
        if (url == null || url.trim().isEmpty() || "about:blank".equals(url)) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_URL, url).apply();
    }

    private TextView button(String text, View.OnClickListener action) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(text.length() > 2 ? 12 : 27);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.TRANSPARENT);
        view.setOnClickListener(action);
        view.setFocusable(true);
        return view;
    }

    private LinearLayout.LayoutParams buttonParams(int width) {
        return new LinearLayout.LayoutParams(width, dp(46));
    }

    private void navigate(String requested) {
        String value = requested == null ? "" : requested.trim();
        if (value.isEmpty()) return;
        if (!value.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            if (value.contains(".") && !value.contains(" ")) value = "https://" + value;
            else {
                try {
                    value = "https://www.google.com/search?q=" +
                            URLEncoder.encode(value, "UTF-8");
                } catch (Exception ignored) {
                    value = HOME;
                }
            }
        }
        webView.loadUrl(value);
        address.clearFocus();
    }

    private boolean openUrl(Uri uri) {
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
            return false;
        // Keep Lucent's browser in the same package. Downloads are handled by
        // BrowserDownloadListener; arbitrary deep links must not switch into
        // another application or create a second app/task identity.
        Toast.makeText(this, "Only web links and downloads open inside Lucent",
                Toast.LENGTH_SHORT).show();
        return true;
    }

    private final class BrowserDownloadListener implements DownloadListener {
        @Override public void onDownloadStart(String url, String userAgent,
                                               String contentDisposition,
                                               String mimeType, long contentLength) {
            try {
                Uri downloadUri = Uri.parse(url);
                String scheme = downloadUri.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    throw new IllegalArgumentException("Unsupported download scheme: " + scheme);
                String fileName = uniqueDownloadName(
                        sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType)));
                DownloadManager.Request request = new DownloadManager.Request(downloadUri);
                request.setMimeType(mimeType);
                String effectiveUserAgent = userAgent == null || userAgent.isEmpty() ?
                        webView.getSettings().getUserAgentString() : userAgent;
                request.addRequestHeader("User-Agent", effectiveUserAgent);
                String referer = webView == null ? null : webView.getUrl();
                // DownloadManager replays these headers verbatim across
                // redirects, so a session cookie is only safe to attach while
                // the download stays on the page's own host.
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isEmpty() && sameHost(url, referer))
                    request.addRequestHeader("Cookie", cookie);
                if (referer != null && !referer.isEmpty())
                    request.addRequestHeader("Referer", referer);
                request.addRequestHeader("Accept", "*/*");
                request.addRequestHeader("Accept-Language", "en-US,en;q=0.9");
                request.setTitle(fileName);
                request.setDescription("Downloaded from Lucent Browser");
                request.setAllowedOverMetered(true);
                request.setAllowedOverRoaming(true);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        fileName);
                DownloadManager manager = (DownloadManager)
                        getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
                long downloadId = manager.enqueue(request);
                // Download URLs regularly carry signed query tokens; keep them
                // out of the log.
                Log.i(TAG, "Queued background download " + downloadId +
                        " host=" + downloadUri.getHost() + " file=" + fileName);
                Toast.makeText(BrowserActivity.this,
                        "Background download started: " + fileName, Toast.LENGTH_LONG).show();
            } catch (Exception error) {
                Log.e(TAG, "Download could not be started from host "
                        + (url == null ? "?" : Uri.parse(url).getHost()), error);
                Toast.makeText(BrowserActivity.this,
                        "Download failed to start: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static boolean sameHost(String downloadUrl, String pageUrl) {
        try {
            String downloadHost = new URI(downloadUrl).getHost();
            String pageHost = pageUrl == null ? null : new URI(pageUrl).getHost();
            return downloadHost != null && pageHost != null &&
                    downloadHost.equalsIgnoreCase(pageHost);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String sanitizeFileName(String requestedName) {
        String cleaned = requestedName == null ? "download" :
                requestedName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return cleaned.isEmpty() ? "download" : cleaned;
    }

    private String uniqueDownloadName(String requestedName) {
        File directory = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (!directory.exists()) directory.mkdirs();
        if (!new File(directory, requestedName).exists()) return requestedName;

        int dot = requestedName.lastIndexOf('.');
        String stem = dot > 0 ? requestedName.substring(0, dot) : requestedName;
        String extension = dot > 0 ? requestedName.substring(dot) : "";
        for (int suffix = 2; suffix < 10000; suffix++) {
            String candidate = stem + " (" + suffix + ")" + extension;
            if (!new File(directory, candidate).exists()) return candidate;
        }
        return System.currentTimeMillis() + "-" + requestedName;
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() {
        if (webView != null) rememberUrl(webView.getUrl());
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (webView != null) webView.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setDownloadListener(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
