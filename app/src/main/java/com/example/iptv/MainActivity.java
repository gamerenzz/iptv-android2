package com.example.iptv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private WebView webView;
    private TextView tvLog;
    private Button btnStart;
    private FrameLayout webviewContainer;

    private static final String BASE_URL = "https://tonkiang.us/";
    private static final String SEARCH_URL = BASE_URL + "iptvmulticast.php";
    private static final String SEARCH_KEYWORD = "湖北";
    private static final int MAX_IP_COUNT = 8;

    // 状态机变量
    private boolean isSearching = false;
    private Queue<String> nodeUrlsToParse = new LinkedList<>();
    private List<String> m3uResults = new ArrayList<>();
    private List<String> successfulIps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        webView = findViewById(R.id.webView);
        tvLog = findViewById(R.id.tvLog);
        btnStart = findViewById(R.id.btnStart);
        webviewContainer = findViewById(R.id.webviewContainer);

        setupWebView();

        btnStart.setOnClickListener(v -> {
            btnStart.setEnabled(false);
            m3uResults.clear();
            successfulIps.clear();
            nodeUrlsToParse.clear();
            isSearching = true;
            log("1. 正在启动无感浏览器访问组播源页面...");
            webView.loadUrl(SEARCH_URL);
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // 抹除 wv 标记，伪装成纯正手机 Chrome
        String fakeUA = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36";
        settings.setUserAgentString(fakeUA);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 抹除 webdriver 特征
                view.evaluateJavascript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined});", null);
                
                // 延迟 3 秒执行，给 CF 盾和网页 JS 渲染留出时间
                new Handler(Looper.getMainLooper()).postDelayed(() -> handlePageFinished(url), 3000);
            }
        });
    }

    private void handlePageFinished(String url) {
        if (url.contains("iptvmulticast.php") && isSearching) {
            log("2. 页面加载完成，注入 JS 执行搜索【" + SEARCH_KEYWORD + "】...");
            // 注入 JS：填入搜索框并点击提交
            String js = "document.getElementById('search').value='" + SEARCH_KEYWORD + "';" +
                        "document.querySelector('input[type=submit]').click();";
            webView.evaluateJavascript(js, null);
            isSearching = false; // 防止死循环
            
            // 搜索提交后，页面会刷新，接下来抓取搜索结果
            new Handler(Looper.getMainLooper()).postDelayed(this::extractSearchResults, 5000);
        } else if (url.contains("channellist.html")) {
            // 当前是在解析某个具体节点的频道列表
            extractNodeChannels(url);
        }
    }

    private void extractSearchResults() {
        log("3. 正在提取搜索结果...");
        webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})();", html -> {
            String cleanHtml = unescapeHtml(html);
            Document doc = Jsoup.parse(cleanHtml);
            Elements results = doc.select("div.result");

            for (Element item : results) {
                Element statusDiv = item.selectFirst("div[style*='float: right']");
                if (statusDiv != null && statusDiv.text().contains("暂时失效")) continue;

                Element aTag = item.selectFirst("div.channel a[href]");
                if (aTag != null && aTag.attr("href").contains("p=2")) {
                    String detailUrl = BASE_URL + aTag.attr("href");
                    nodeUrlsToParse.add(detailUrl);
                }
            }

            log("   => 当前页找到有效节点数：" + nodeUrlsToParse.size());
            processNextNode();
        });
    }

    private void processNextNode() {
        if (successfulIps.size() >= MAX_IP_COUNT) {
            finishAndSaveM3U();
            return;
        }

        String nextUrl = nodeUrlsToParse.poll();
        if (nextUrl != null) {
            log("\n=> 尝试解析节点: " + nextUrl);
            webView.loadUrl(nextUrl);
        } else {
            // 队列空了，结束或考虑翻页（为简化代码，这里仅处理第一页数据。实战中可增加翻页逻辑）
            log("搜索页节点处理完毕。");
            finishAndSaveM3U();
        }
    }

    private void extractNodeChannels(String currentUrl) {
        webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})();", html -> {
            String cleanHtml = unescapeHtml(html);
            Document doc = Jsoup.parse(cleanHtml);
            
            // 解析 IP 用于重组直连
            String currentIp = "";
            Matcher ipMatcher = Pattern.compile("ip=([^&]+)").matcher(currentUrl);
            if(ipMatcher.find()) currentIp = ipMatcher.group(1);

            Elements channelItems = doc.select("div.result");
            int validCount = 0;

            for (Element item : channelItems) {
                Element channelDiv = item.selectFirst("div.channel");
                if (channelDiv == null) continue;
                
                String channelName = channelDiv.text().trim();
                if (channelName.toUpperCase().endsWith("SD")) continue; // 过滤 SD

                // 提取包含 Base64 密文的链接
                String proxyUrl = "";
                Elements aTags = item.select("a[href]");
                for (Element a : aTags) {
                    if (!a.attr("href").contains("channellist")) {
                        proxyUrl = a.attr("href");
                        break;
                    }
                }

                if (!proxyUrl.isEmpty()) {
                    String rawStreamUrl = decodeStreamUrl(proxyUrl, currentIp);
                    if (rawStreamUrl.startsWith("http") && !rawStreamUrl.contains("zqjy.info")) {
                        m3uResults.add(String.format("#EXTINF:-1 group-title=\"湖北频道\",%s\n%s", channelName, rawStreamUrl));
                        validCount++;
                    }
                }
            }

            if (validCount > 0) {
                successfulIps.add(currentIp);
                log("   [成功] 提取了 " + validCount + " 个有效频道。(进度: " + successfulIps.size() + "/" + MAX_IP_COUNT + ")");
            } else {
                log("   [跳过] 未包含有效频道。");
            }

            // 处理下一个节点
            processNextNode();
        });
    }

    // Base64 解码与直连还原逻辑（完美复刻 Python）
    private String decodeStreamUrl(String proxyUrl, String finalIp) {
        try {
            if (!proxyUrl.contains("/tv/")) return proxyUrl;
            String encodedPart = proxyUrl.substring(proxyUrl.lastIndexOf("/tv/") + 4);
            
            String marker = null;
            String scheme = "http";
            if (encodedPart.contains("Gh0dHA6Ly8")) { marker = "Gh0dHA6Ly8"; scheme = "http"; }
            else if (encodedPart.contains("Gh0dHBzOi8v")) { marker = "Gh0dHBzOi8v"; scheme = "https"; }

            if (marker != null) {
                int idx = encodedPart.indexOf(marker);
                String payloadB64 = encodedPart.substring(idx + marker.length() + 1);
                payloadB64 = payloadB64.replaceAll("=$", ""); // 清除末尾等号
                while (payloadB64.length() % 4 != 0) payloadB64 += "="; // 补全等号
                
                byte[] decodedBytes = Base64.decode(payloadB64, Base64.DEFAULT);
                String decodedStr = new String(decodedBytes, "UTF-8").trim();

                if (decodedStr.contains("/")) {
                    String hostPart = decodedStr.substring(0, decodedStr.indexOf("/"));
                    String pathPart = decodedStr.substring(decodedStr.indexOf("/") + 1);
                    String port = hostPart.contains(":") ? hostPart.substring(hostPart.indexOf(":")) : "";
                    return scheme + "://" + finalIp + port + "/" + pathPart;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return proxyUrl;
    }

    private void finishAndSaveM3U() {
        log("\n[完成] 共成功提取 " + successfulIps.size() + " 个节点。");
        if (m3uResults.isEmpty()) {
            log("未提取到任何数据。");
            btnStart.setEnabled(true);
            return;
        }

        try {
            // 保存在内部存储的 Download 目录，无需申请运行时权限
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(dir, "hubei_multicast.m3u");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write("#EXTM3U\n".getBytes());
            for (String line : m3uResults) {
                fos.write((line + "\n").getBytes());
            }
            fos.close();
            log("\n保存成功！文件位置：\n" + file.getAbsolutePath());
            Toast.makeText(this, "文件已导出到 Download 文件夹", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            log("保存失败: " + e.getMessage());
        }
        btnStart.setEnabled(true);
    }

    // 工具方法：清理 evaluateJavascript 返回的多余转义符
    private String unescapeHtml(String html) {
        if (html == null) return "";
        return html.replace("\\u003C", "<")
                   .replace("\\\"", "\"")
                   .replace("\\n", "")
                   .replaceAll("^\"|\"$", "");
    }

    private void log(String msg) {
        runOnUiThread(() -> {
            tvLog.append(msg + "\n");
            // 自动滚动到底部 (简化)
        });
    }
}
