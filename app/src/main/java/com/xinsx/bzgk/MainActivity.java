package com.xinsx.bzgk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends android.app.Activity {

    private static final String HOME_URL = "https://openstd.samr.gov.cn/bzgk/std/";
    private static final long LOAD_TIMEOUT_MS = 18000;          // 单次加载超时
    private static final int  MAX_RETRY = 2;                    // 自动重试次数
    private static final int  PRECONNECT_TIMEOUT_MS = 4000;     // 预连超时

    private WebView webView;
    private ScrollBarView scrollBar;
    private FrameLayout root;
    private ProgressBar topProgress;
    private View loadingOverlay;
    private TextView loadingText;
    private View errorOverlay;
    private TextView errorText;
    private TextView retryBtn;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable timeoutRunnable = this::onLoadTimeout;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean pageLoaded = false;
    private int retryCount = 0;
    private boolean receiverRegistered = false;
    private BroadcastReceiver connectivityReceiver;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = buildWebView();
        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(webView, webParams);

        // 顶部进度条
        topProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        topProgress.setMax(100);
        topProgress.setProgress(0);
        FrameLayout.LayoutParams pbParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        pbParams.gravity = Gravity.TOP;
        root.addView(topProgress, pbParams);

        // 加载中遮罩
        loadingOverlay = buildLoadingOverlay();
        root.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 错误重试遮罩(默认隐藏)
        errorOverlay = buildErrorOverlay();
        root.addView(errorOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        errorOverlay.setVisibility(View.GONE);

        // 右侧细窄滚动条
        scrollBar = new ScrollBarView(this, webView);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                dp(6), ViewGroup.LayoutParams.MATCH_PARENT);
        barParams.gravity = Gravity.RIGHT;
        barParams.rightMargin = dp(2);
        root.addView(scrollBar, barParams);

        setContentView(root);

        registerNetworkCallback();
        registerConnectivityReceiver();

        if (isNetworkAvailable()) {
            preconnectAndLoad();
        } else {
            showError("当前无网络,请检查连接后重试");
        }
    }

    private WebView buildWebView() {
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // 优先使用缓存,无网时仍可访问最近页面
        s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 开启硬件加速
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // 关闭 WebView 自带滚动条(用自定义的)
        wv.setVerticalScrollBarEnabled(false);
        wv.setHorizontalScrollBarEnabled(false);
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // 离线优先 + 性能相关
        s.setSaveFormData(false);
        s.setNeedInitialFocus(true);
        s.setLoadsImagesAutomatically(true);
        // 视口与文本编码
        s.setDefaultTextEncodingName("UTF-8");

        wv.setWebViewClient(new BzgkWebViewClient());
        wv.setWebChromeClient(new BzgkChromeClient());
        return wv;
    }

    private View buildLoadingOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.argb(230, 0, 0, 0));

        // 转圈
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(Color.argb(40, 255, 255, 255));
        pb.setBackground(gd);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        pbLp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(pb, pbLp);

        loadingText = new TextView(this);
        loadingText.setText("正在加载国家标准全文公开系统…");
        loadingText.setTextColor(Color.WHITE);
        loadingText.setTextSize(14);
        loadingText.setPadding(0, dp(16), 0, 0);
        box.addView(loadingText);

        return box;
    }

    private View buildErrorOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.argb(245, 0, 0, 0));
        box.setPadding(dp(32), 0, dp(32), 0);

        TextView title = new TextView(this);
        title.setText("⚠  网络连接异常");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        errorText = new TextView(this);
        errorText.setTextColor(Color.argb(220, 255, 255, 255));
        errorText.setTextSize(14);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(0, dp(16), 0, dp(24));
        errorText.setText("页面加载失败,请稍后重试");
        box.addView(errorText);

        retryBtn = new TextView(this);
        retryBtn.setText("  重新加载  ");
        retryBtn.setTextColor(Color.parseColor("#8B0000"));
        retryBtn.setTextSize(16);
        retryBtn.setPadding(dp(28), dp(12), dp(28), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(24));
        retryBtn.setBackground(bg);
        retryBtn.setOnClickListener(v -> {
            retryCount = 0;
            if (isNetworkAvailable()) {
                hideError();
                preconnectAndLoad();
            } else {
                showError("当前无网络,请检查连接后重试");
            }
        });
        box.addView(retryBtn);
        return box;
    }

    /**
     * DNS / TCP / TLS 预热:在主请求发起前用子线程做一次轻量 HEAD/OPTIONS,
     * 命中 CDN 节点后能显著缩短后续 HTTPS 的 TLS 握手耗时。
     */
    private void preconnectAndLoad() {
        showLoading();
        startLoadTimeout();
        io.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(HOME_URL).openConnection();
                conn.setConnectTimeout(PRECONNECT_TIMEOUT_MS);
                conn.setReadTimeout(PRECONNECT_TIMEOUT_MS);
                conn.setRequestMethod("HEAD");
                conn.setInstanceFollowRedirects(true);
                conn.setUseCaches(false);
                conn.connect();
                int code = conn.getResponseCode();
                conn.disconnect();
                mainHandler.post(() -> {
                    if (code >= 200 && code < 400) {
                        doLoadUrl(HOME_URL);
                    } else {
                        doLoadUrl(HOME_URL); // 仍尝试加载,失败时由 WebViewClient 处理
                    }
                });
            } catch (IOException e) {
                // 预连失败,直接尝试加载,由 WebView 自行处理
                mainHandler.post(() -> doLoadUrl(HOME_URL));
            }
        });
    }

    private void doLoadUrl(String url) {
        pageLoaded = false;
        retryCount++;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.loadUrl(url);
        } else {
            webView.loadUrl(url);
        }
    }

    private void onLoadTimeout() {
        if (pageLoaded) return;
        if (retryCount < MAX_RETRY) {
            // 自动重试一次
            doLoadUrl(HOME_URL);
        } else {
            cancelLoadTimeout();
            webView.stopLoading();
            showError("网络响应超时,请检查网络后重试");
        }
    }

    private void startLoadTimeout() {
        cancelLoadTimeout();
        mainHandler.postDelayed(timeoutRunnable, LOAD_TIMEOUT_MS);
    }

    private void cancelLoadTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable);
    }

    private void showLoading() {
        loadingOverlay.setVisibility(View.VISIBLE);
        errorOverlay.setVisibility(View.GONE);
        topProgress.setVisibility(View.VISIBLE);
        topProgress.setProgress(0);
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
        topProgress.setVisibility(View.GONE);
    }

    private void showError(String msg) {
        errorText.setText(msg);
        errorOverlay.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.GONE);
        topProgress.setVisibility(View.GONE);
    }

    private void hideError() {
        errorOverlay.setVisibility(View.GONE);
    }

    // ============== WebView 客户端 ==============

    private class BzgkWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            // 仅放行官方域名,其它链接走系统浏览器
            if (url.contains("openstd.samr.gov.cn") || url.startsWith("https://openstd.samr.gov.cn/")) {
                return false;
            }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {}
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            pageLoaded = false;
            startLoadTimeout();
            topProgress.setProgress(10);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            pageLoaded = true;
            cancelLoadTimeout();
            topProgress.setProgress(100);
            hideLoading();
            hideError();
            mainHandler.postDelayed(() -> topProgress.setVisibility(View.GONE), 300);

            // 注入 DNS 预取与渲染加速
            view.evaluateJavascript(
                "(function(){" +
                "  try {" +
                "    var s=document.createElement('link');" +
                "    s.rel='dns-prefetch';" +
                "    s.href='//openstd.samr.gov.cn';" +
                "    document.head.appendChild(s);" +
                "  } catch(e){}" +
                "})();", null);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    android.webkit.WebResourceError error) {
            super.onReceivedError(view, request, error);
            // 仅处理主框架错误,资源错误不弹窗
            if (request.isForMainFrame()) {
                if (retryCount < MAX_RETRY) {
                    mainHandler.postDelayed(() -> doLoadUrl(HOME_URL), 1500);
                } else {
                    showError("网络异常,无法访问官网 (" + error.getErrorCode() + ")");
                }
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            super.onReceivedHttpError(view, request, errorResponse);
            if (request.isForMainFrame() && errorResponse != null) {
                int code = errorResponse.getStatusCode();
                if (code >= 500) {
                    showError("服务器繁忙(" + code + "),请稍后再试");
                }
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // 渲染进程崩溃时重建,避免整页白屏
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!detail.didCrash()) {
                    return true; // 系统级回收,由系统处理
                }
            }
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.destroy();
                }
                WebView nv = buildWebView();
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
                root.removeView(webView);
                webView = nv;
                root.addView(nv, 0, lp);
                scrollBar.attachWebView(nv);
                preconnectAndLoad();
            });
            return true;
        }
    }

    private class BzgkChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress < 100) {
                topProgress.setVisibility(View.VISIBLE);
                // 平滑过渡
                int cur = topProgress.getProgress();
                if (newProgress > cur) {
                    topProgress.setProgress(newProgress);
                }
            }
        }
    }

    // ============== 网络监听 ==============

    @SuppressWarnings("deprecation")
    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NetworkRequest req = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        mainHandler.post(() -> {
                            if (!pageLoaded && errorOverlay.getVisibility() == View.VISIBLE) {
                                hideError();
                                preconnectAndLoad();
                            }
                        });
                    }
                    @Override
                    public void onLost(Network network) {
                        mainHandler.post(() -> {
                            if (!pageLoaded) {
                                showError("网络已断开");
                            }
                        });
                    }
                };
                cm.registerNetworkCallback(req, networkCallback);
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void registerConnectivityReceiver() {
        try {
            connectivityReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    boolean connected = isNetworkAvailable();
                    if (connected && errorOverlay.getVisibility() == View.VISIBLE) {
                        hideError();
                        preconnectAndLoad();
                    } else if (!connected && !pageLoaded) {
                        showError("当前无网络,请检查连接后重试");
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(connectivityReceiver, filter);
            receiverRegistered = true;
        } catch (Exception ignored) {}
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network net = cm.getActiveNetwork();
                if (net == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            }
        } catch (Exception e) {
            return true; // 拿不到状态时按有网处理,失败时再弹错误页
        }
    }

    // ============== 生命周期 ==============

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        cancelLoadTimeout();
        try {
            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) {}
        try {
            if (receiverRegistered && connectivityReceiver != null) {
                unregisterReceiver(connectivityReceiver);
            }
        } catch (Exception ignored) {}
        if (webView != null) {
            webView.stopLoading();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    // ============== 自定义滚动条 ==============

    static class ScrollBarView extends View {
        private WebView webView;
        private final Paint trackPaint;
        private final Paint thumbPaint;
        private float thumbTop;
        private float thumbHeight;
        private float lastTouchY;
        private boolean dragging = false;

        ScrollBarView(Context context, WebView webView) {
            super(context);
            this.webView = webView;
            trackPaint = new Paint();
            trackPaint.setColor(Color.argb(40, 255, 255, 255));
            trackPaint.setAntiAlias(true);
            thumbPaint = new Paint();
            thumbPaint.setColor(Color.argb(220, 255, 255, 255));
            thumbPaint.setAntiAlias(true);
        }

        void attachWebView(WebView wv) {
            this.webView = wv;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (h <= 0 || w <= 0) return;
            canvas.drawRoundRect(0, 0, w, h, w / 2f, w / 2f, trackPaint);
            if (thumbHeight < 30) thumbHeight = 30;
            if (thumbTop < 0) thumbTop = 0;
            if (thumbTop + thumbHeight > h) thumbTop = h - thumbHeight;
            canvas.drawRoundRect(0, thumbTop, w, thumbTop + thumbHeight, w / 2f, w / 2f, thumbPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (webView == null) return false;
            float h = getHeight();
            int contentHeight = (int) (webView.getContentHeight() * webView.getScale());
            int viewHeight = webView.getHeight();
            if (contentHeight <= viewHeight || viewHeight == 0) return false;
            int scrollable = Math.max(1, contentHeight - viewHeight);
            float ratio = viewHeight / (float) contentHeight;
            thumbHeight = Math.max(30, h * ratio);
            float scrollableTrack = h - thumbHeight;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchY = event.getY();
                    float center = thumbTop + thumbHeight / 2f;
                    if (Math.abs(event.getY() - center) <= thumbHeight) {
                        dragging = true;
                    } else {
                        float targetThumb = event.getY() - thumbHeight / 2f;
                        if (targetThumb < 0) targetThumb = 0;
                        if (targetThumb > scrollableTrack) targetThumb = scrollableTrack;
                        thumbTop = targetThumb;
                        float percent = thumbTop / scrollableTrack;
                        webView.scrollTo(0, (int) (scrollable * percent));
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        float dy = event.getY() - lastTouchY;
                        lastTouchY = event.getY();
                        thumbTop += dy;
                        if (thumbTop < 0) thumbTop = 0;
                        if (thumbTop > scrollableTrack) thumbTop = scrollableTrack;
                        float percent = thumbTop / scrollableTrack;
                        webView.scrollTo(0, (int) (scrollable * percent));
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
            }
            return super.onTouchEvent(event);
        }
    }
}
