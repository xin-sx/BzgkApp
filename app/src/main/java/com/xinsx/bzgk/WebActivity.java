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
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
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

/**
 * 二级页面:「细分」。
 * 在 WebView 中打开某个官方库的具体页面。按系统返回键时,站内还有上一级就先在站内回退,
 * 退到该库首页后再按则回到一级「综合」页(HomeActivity)。
 */
public class WebActivity extends android.app.Activity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_HOST = "host";

    private static final long LOAD_TIMEOUT_MS = 18000;          // 单次加载超时
    private static final int  MAX_RETRY = 2;                    // 自动重试次数
    private static final int  PRECONNECT_TIMEOUT_MS = 4000;     // 预连超时
    private static final long CLEANUP_INTERVAL_MS = 20 * 60 * 1000L; // 20分钟定期清理

    private String homeUrl;      // 当前库的入口地址
    private String siteHost;     // 当前库的域名,用于判断链接是否站内
    private String siteTitle;    // 当前库名称,用于加载提示
    private String landingUrl;   // 入口跳转后真正落地的页面(部分站点入口只是一层跳转壳)
    private boolean landingResolved = false; // 落地页是否已确定
    private boolean userNavigated = false;  // 用户是否已主动点击站内链接

    private WebView webView;
    private ScrollBarView scrollBar;
    private FrameLayout root;
    private ProgressBar topProgress;
    private View loadingOverlay;
    private TextView loadingText;
    private View errorOverlay;
    private TextView errorText;
    private TextView retryBtn;
    private TextView netInfoView;                              // 网络信息(类型+速度)
    private TextView cleanHintView;                            // 清理提示

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler cleanupHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable timeoutRunnable = this::onLoadTimeout;
    private final Runnable periodicCleanupRunnable = this::doPeriodicCleanup;
    private final Runnable landingDebounce = () -> landingResolved = true;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean pageLoaded = false;
    private int retryCount = 0;
    private boolean receiverRegistered = false;
    private BroadcastReceiver connectivityReceiver;
    private int lastNetSpeedKbps = -1;
    private String lastNetType = "";
    private OnBackInvokedCallback backCallback;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        siteTitle = intent.getStringExtra(EXTRA_TITLE);
        homeUrl = intent.getStringExtra(EXTRA_URL);
        siteHost = intent.getStringExtra(EXTRA_HOST);
        if (homeUrl == null || siteHost == null) {
            finish();
            return;
        }
        if (siteTitle == null) siteTitle = siteHost;

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
        // 长按滚动条:手动清理 WebView 缓存
        scrollBar.setOnLongClickListener(v -> {
            showCleanHint("正在清理缓存…");
            mainHandler.postDelayed(() -> {
                clearWebViewData();
                showCleanHint("缓存已清理");
                mainHandler.postDelayed(() -> cleanHintView.setVisibility(View.GONE), 1200);
                // 清理后如果当前页未加载成功,重新拉取
                if (!pageLoaded) {
                    preconnectAndLoad();
                } else {
                    webView.reload();
                }
            }, 50);
            return true;
        });

        // 顶部网络信息(类型 + 速度)
        netInfoView = buildNetInfoView();
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoParams.gravity = Gravity.TOP | Gravity.START;
        infoParams.leftMargin = dp(8);
        infoParams.topMargin = dp(6);
        root.addView(netInfoView, infoParams);

        // 中央清理提示(默认隐藏)
        cleanHintView = buildCleanHintView();
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.gravity = Gravity.CENTER;
        root.addView(cleanHintView, hintParams);
        cleanHintView.setVisibility(View.GONE);

        setContentView(root);

        registerNetworkCallback();
        registerConnectivityReceiver();
        registerBackCallback();

        if (isNetworkAvailable()) {
            preconnectAndLoad();
            startNetSpeedMonitor();
        } else {
            showError("当前无网络,请检查连接后重试");
        }

        // 启动 WebView 定期清理(20 分钟)
        schedulePeriodicCleanup();
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
        // 站内大量使用 target="_blank" 打开详情页,需自行接管新窗口
        s.setSupportMultipleWindows(true);

        wv.setWebViewClient(new BzgkWebViewClient());
        wv.setWebChromeClient(new BzgkChromeClient());
        wv.setDownloadListener(this::onDownloadStart);
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
        loadingText.setText("正在加载" + siteTitle + "…");
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
                HttpURLConnection conn = (HttpURLConnection) new URL(homeUrl).openConnection();
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
                        doLoadUrl(homeUrl);
                    } else {
                        doLoadUrl(homeUrl); // 仍尝试加载,失败时由 WebViewClient 处理
                    }
                });
            } catch (IOException e) {
                // 预连失败,直接尝试加载,由 WebView 自行处理
                mainHandler.post(() -> doLoadUrl(homeUrl));
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
            doLoadUrl(homeUrl);
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

    // ============== 网络信息显示 ==============

    private TextView buildNetInfoView() {
        TextView tv = new TextView(this);
        tv.setTextColor(Color.argb(200, 255, 255, 255));
        tv.setTextSize(10);
        tv.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(140, 0, 0, 0));
        bg.setCornerRadius(dp(10));
        tv.setBackground(bg);
        tv.setText(getNetInfoText());
        return tv;
    }

    private String getNetInfoText() {
        String type = getCurrentNetworkTypeName();
        if (lastNetSpeedKbps < 0) {
            return type;
        }
        if (lastNetSpeedKbps >= 1024) {
            return type + "  " + (lastNetSpeedKbps / 1024) + "MB/s";
        }
        return type + "  " + lastNetSpeedKbps + "KB/s";
    }

    private String getCurrentNetworkTypeName() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "离线";
            Network net = cm.getActiveNetwork();
            if (net == null) return "离线";
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return "离线";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "WiFi";
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) {
                        return "WiFi Aware";
                    }
                }
                return "移动网络";
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "有线";
            } else {
                return "在线";
            }
        } catch (Exception e) {
            return "在线";
        }
    }

    private void startNetSpeedMonitor() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                testNetSpeed();
                mainHandler.postDelayed(this, 30 * 1000); // 30秒测一次
            }
        }, 5 * 1000);
    }

    /**
     * 异步测速:下载一个小静态资源,根据耗时估算当前带宽(KB/s)。
     * 用于在网速过慢时给出"慢速"提示。
     */
    private void testNetSpeed() {
        io.execute(() -> {
            long start = System.currentTimeMillis();
            int bytes = 0;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(homeUrl).openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestMethod("HEAD");
                conn.setUseCaches(false);
                conn.connect();
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code >= 200 && code < 400) {
                    // 头请求成功,真正下载小资源
                    HttpURLConnection get = (HttpURLConnection) new URL(
                            "https://" + siteHost + "/favicon.ico").openConnection();
                    get.setConnectTimeout(3000);
                    get.setReadTimeout(3000);
                    get.setUseCaches(false);
                    java.io.InputStream is = get.getInputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    int total = 0;
                    int maxRead = 50 * 1024; // 最多读 50KB
                    start = System.currentTimeMillis();
                    while ((n = is.read(buf)) > 0 && total < maxRead) {
                        total += n;
                    }
                    bytes = total;
                    is.close();
                    get.disconnect();
                }
            } catch (Exception ignored) {}
            long cost = System.currentTimeMillis() - start;
            int kbps = 0;
            if (cost > 0 && bytes > 0) {
                kbps = (int) (bytes * 1000L / cost / 1024L);
            }
            int finalKbps = kbps;
            mainHandler.post(() -> {
                lastNetSpeedKbps = finalKbps;
                String type = getCurrentNetworkTypeName();
                if (!type.equals(lastNetType)) {
                    lastNetType = type;
                }
                if (netInfoView != null) {
                    netInfoView.setText(getNetInfoText());
                    // 网速过慢(<10KB/s)时,把网络信息标红
                    if (finalKbps > 0 && finalKbps < 10) {
                        netInfoView.setTextColor(Color.parseColor("#FF6B6B"));
                    } else {
                        netInfoView.setTextColor(Color.argb(200, 255, 255, 255));
                    }
                }
            });
        });
    }

    // ============== WebView 数据清理 ==============

    private TextView buildCleanHintView() {
        TextView tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setPadding(dp(24), dp(14), dp(24), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(220, 0, 0, 0));
        bg.setCornerRadius(dp(12));
        tv.setBackground(bg);
        return tv;
    }

    private void showCleanHint(String msg) {
        if (cleanHintView == null) return;
        cleanHintView.setText(msg);
        cleanHintView.setVisibility(View.VISIBLE);
    }

    /**
     * 清理 WebView 累积的缓存/历史/表单/cookie/本地存储,
     * 这是"用久了变卡"的主要成因——长期使用后,WebView 内部 DOM 缓存、
     * localStorage、IndexedDB、HTTP 缓存会持续膨胀,占用大量内存和 CPU。
     */
    private void clearWebViewData() {
        if (webView == null) return;
        try {
            webView.stopLoading();
        } catch (Exception ignored) {}
        try {
            // 1. HTTP 缓存(含内存+磁盘)
            webView.clearCache(true);
        } catch (Exception ignored) {}
        try {
            // 2. 浏览历史
            webView.clearHistory();
        } catch (Exception ignored) {}
        try {
            // 3. 表单自动填充数据
            webView.clearFormData();
        } catch (Exception ignored) {}
        try {
            // 4. 内存中保存的密码等 SSL 偏好
            webView.clearMatches();
        } catch (Exception ignored) {}
        try {
            // 5. 全部 Cookie
            CookieManager cm = CookieManager.getInstance();
            cm.removeSessionCookies(null);
            cm.removeAllCookies(null);
            cm.removeExpiredCookie();
            cm.flush();
        } catch (Exception ignored) {}
        try {
            // 6. localStorage / IndexedDB / WebSQL / Application Cache
            WebStorage.getInstance().deleteAllData();
        } catch (Exception ignored) {}
        try {
            // 7. WebView 自身占用的图形缓存
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                webView.freeMemory();
            }
        } catch (Exception ignored) {}
        // 8. 提示 GC 回收
        System.gc();
    }

    private void schedulePeriodicCleanup() {
        cleanupHandler.removeCallbacks(periodicCleanupRunnable);
        cleanupHandler.postDelayed(periodicCleanupRunnable, CLEANUP_INTERVAL_MS);
    }

    private void doPeriodicCleanup() {
        try {
            if (webView == null) return;
            // 页面可见时才执行完整清理,避免影响当前浏览
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.onPause();
            }
            clearWebViewData();
            // 重新激活
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.onResume();
            }
            // 重新拉取当前页(保留浏览历史的位置,reload 即可)
            mainHandler.post(() -> {
                if (webView != null && pageLoaded) {
                    webView.reload();
                }
            });
        } catch (Exception ignored) {}
        // 下一轮
        cleanupHandler.postDelayed(periodicCleanupRunnable, CLEANUP_INTERVAL_MS);
    }

    /**
     * 系统级内存压力回调。系统会在低内存时调用此函数。
     * 我们借此机会主动清理 WebView 缓存,避免 OOM 或被系统强杀。
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (webView == null) return;
        if (level >= TRIM_MEMORY_RUNNING_MODERATE) {
            // 中等压力:释放非关键缓存
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    webView.freeMemory();
                }
            } catch (Exception ignored) {}
        }
        if (level >= TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_COMPLETE) {
            // 高压力或系统即将回收:深度清理
            try {
                webView.clearCache(true);
            } catch (Exception ignored) {}
            try {
                WebStorage.getInstance().deleteAllData();
            } catch (Exception ignored) {}
            try {
                CookieManager.getInstance().flush();
            } catch (Exception ignored) {}
        }
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            // 页面进入后台,主动暂停
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    webView.onPause();
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        // 旧版系统回调:一次性深度清理
        try {
            if (webView != null) clearWebViewData();
        } catch (Exception ignored) {}
    }

    // ============== WebView 客户端 ==============

    private class BzgkWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            // 仅放行当前库的官方域名,其它链接走系统浏览器
            if (isSiteUrl(url)) {
                // 点击链接产生的跳转:落地页已确定,不再被后续地址覆盖
                if (request.hasGesture()) {
                    userNavigated = true;
                }
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

            // 记录落地页:入口地址可能只是一层 JS 跳转壳,落地页才是该库真正的首页
            if (!landingResolved && !userNavigated) {
                landingUrl = url;
                // 站点可能还有后续跳转,静置一小段时间再确认落地页
                mainHandler.removeCallbacks(landingDebounce);
                mainHandler.postDelayed(landingDebounce, 800);
            }

            // 注入 DNS 预取与渲染加速
            view.evaluateJavascript(
                "(function(){" +
                "  try {" +
                "    var s=document.createElement('link');" +
                "    s.rel='dns-prefetch';" +
                "    s.href='//" + siteHost + "';" +
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
                    mainHandler.postDelayed(() -> doLoadUrl(homeUrl), 1500);
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

        /**
         * 接管 target="_blank" / window.open 打开的新窗口:
         * 站内地址直接在本页 WebView 中打开,站外地址交给系统浏览器。
         */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      android.os.Message resultMsg) {
            WebView newWebView = new WebView(WebActivity.this);
            newWebView.setWebViewClient(new NewWindowClient());
            WebSettings ns = newWebView.getSettings();
            ns.setJavaScriptEnabled(true);
            ns.setDomStorageEnabled(true);
            ns.setSupportMultipleWindows(false);
            // 记录临时窗口,便于回收
            destroyPendingPopup();
            pendingPopup = newWebView;
            lastForwardedUrl = null;
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(newWebView);
            resultMsg.sendToTarget();
            return true;
        }
    }

    /**
     * 新窗口里的链接最终都转到主 WebView 或系统浏览器。
     * 临时 WebView 只用于拿到目标 URL,不实际渲染。
     */
    private class NewWindowClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url == null || url.isEmpty() || "about:blank".equals(url)) return false;
            forwardUrl(url);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            // window.open() 未经过 shouldOverrideUrlLoading 时的兜底
            if (url != null && !url.isEmpty() && !"about:blank".equals(url)) {
                forwardUrl(url);
            }
        }
    }

    /** 把新窗口的 URL 转到主 WebView 或系统浏览器,并回收临时 WebView。 */
    private void forwardUrl(String url) {
        // 站内存在 window.open("relative/path") 这类相对地址,先补全成绝对地址
        url = absoluteUrl(url);
        if (url == null) return;
        // 同一地址可能先后经由 shouldOverrideUrlLoading 与 onPageStarted 到达,只转发一次
        if (url.equals(lastForwardedUrl)) {
            mainHandler.post(this::destroyPendingPopup);
            return;
        }
        lastForwardedUrl = url;
        // 用户主动点开的新窗口,落地页就此确定
        userNavigated = true;
        if (isSiteUrl(url)) {
            webView.loadUrl(url);
        } else {
            openInSystemBrowser(url);
        }
        // 临时 WebView 已无用途,在下一个消息循环再销毁,避免在回调内销毁导致崩溃
        mainHandler.post(this::destroyPendingPopup);
    }

    /** 把相对地址补全为基于当前页面的绝对地址;无法补全时返回 null。 */
    private String absoluteUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        if (url.contains("://")) return url;
        String base = webView != null ? webView.getUrl() : null;
        if (base == null) base = homeUrl;
        if (base == null) return null;
        try {
            return new java.net.URL(new java.net.URL(base), url).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private WebView pendingPopup;
    private String lastForwardedUrl;

    private void destroyPendingPopup() {
        if (pendingPopup == null) return;
        try {
            pendingPopup.stopLoading();
            pendingPopup.destroy();
        } catch (Exception ignored) {}
        pendingPopup = null;
    }

    /**
     * 法规库提供"文字版 / 图片版"下载,服务端以附件形式返回 docx / pdf,
     * WebView 无法直接渲染,这里交给系统浏览器或下载器处理。
     */
    private void onDownloadStart(String url, String userAgent, String contentDisposition,
                                 String mimetype, long contentLength) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
            // 没有可处理的应用时退回到系统浏览器
            openInSystemBrowser(url);
        }
    }

    private void openInSystemBrowser(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    /** 判断 URL 是否属于当前库(同域名),用于决定站内打开还是跳系统浏览器。 */
    private boolean isSiteUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        int schemeEnd = lower.indexOf("://");
        if (schemeEnd < 0) return false;
        String hostPart = lower.substring(schemeEnd + 3);
        int slash = hostPart.indexOf('/');
        if (slash >= 0) hostPart = hostPart.substring(0, slash);
        int colon = hostPart.indexOf(':');
        if (colon >= 0) hostPart = hostPart.substring(0, colon);
        return hostPart.equals(siteHost) || hostPart.endsWith("." + siteHost);
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

    /**
     * Android 13+ 的返回键走 OnBackInvokedCallback,
     * 与旧系统的 onBackPressed 保持同一套逻辑。
     */
    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
            if (dispatcher == null) return;
            backCallback = () -> mainHandler.post(this::handleBack);
            dispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        } catch (Exception ignored) {}
    }

    private void unregisterBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
            if (dispatcher != null && backCallback != null) {
                dispatcher.unregisterOnBackInvokedCallback(backCallback);
            }
        } catch (Exception ignored) {}
    }

    /**
     * 系统返回键:站内还有上一级时先在站内回退;退到该库首页后再按则结束本页,
     * 回到一级「综合」页。
     *
     * 部分站点(如国家行政法规库)的入口地址只是一层 JS 跳转壳,
     * 直接按返回键会退到这层壳并被再次跳回,造成"按了没反应"。
     * 这里通过比较落地页与当前页来判断是否已回到该库首页。
     */
    private void handleBack() {
        if (webView != null && webView.canGoBack()) {
            // 已退回到该库首页(或首页的跳转壳),不再在站内回退
            if (isAtSiteHome()) {
                webView.clearHistory();
                finish();
                return;
            }
            webView.goBack();
        } else {
            finish();
        }
    }

    /**
     * 旧系统(API 32 及以下)通过 onBackPressed 接收返回键。
     */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    /**
     * 判断当前是否已经站在该库的首页上。
     * 依据两条:当前页就是该库的落地页(首页),或浏览历史里只剩一条(已在最底端)。
     */
    private boolean isAtSiteHome() {
        if (webView == null) return false;
        String current = webView.getUrl();
        if (current == null) return false;

        // 当前页就是落地页(首页)
        if (landingUrl != null && samePage(current, landingUrl)) return true;

        // 浏览历史里只剩一条,说明已在最底端
        WebBackForwardList list = webView.copyBackForwardList();
        return list != null && list.getSize() <= 1;
    }

    /** 比较两个 URL 是否指向同一个页面(忽略末尾斜杠与锚点)。 */
    private static boolean samePage(String a, String b) {
        if (a == null || b == null) return false;
        String x = stripUrl(a);
        String y = stripUrl(b);
        if (x.equals(y)) return true;
        // 仅当一方带查询参数、另一方不带时,才退化为只比路径;
        // 两边都带参数说明是同一个入口的不同筛选条件,不能视为同一页
        boolean xHasQuery = x.indexOf('?') >= 0;
        boolean yHasQuery = y.indexOf('?') >= 0;
        if (xHasQuery == yHasQuery) return false;
        return pathOf(x).equals(pathOf(y));
    }

    /** 取 URL 的路径部分(不含查询参数与锚点)。 */
    private static String pathOf(String url) {
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        return u;
    }

    private static String stripUrl(String url) {
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        if (u.length() > 1 && u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
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
        cleanupHandler.removeCallbacks(periodicCleanupRunnable);
        unregisterBackCallback();
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
        destroyPendingPopup();
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
