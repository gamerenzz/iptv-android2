package com.example.iptv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
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
    private EditText etKeyword;

    private static final String BASE_URL = "https://tonkiang.us/";
    private static final String SEARCH_URL = BASE_URL + "iptvmulticast.php";
    private static final int MAX_IP_COUNT = 8;
    private static final int MAX_SEARCH_PAGES = 6; // 最大搜寻页数限制

    private boolean isSearching = false;
    private Queue<String> nodeUrlsToParse = new LinkedList<>();
    private List<String> m3uResults = new ArrayList<>();
    private List<String> successfulIps = new ArrayList<>();
    private String currentKeyword = "";

    // 翻页控制变量
    private String nextPageUrl = "";
    private int currentPage = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        tvLog = findViewById(R.id.tvLog);
        btnStart = findViewById(R.id.btnStart);
        etKeyword = findViewById(R.id.etKeyword);

        setupWebView();

        btnStart.setOnClickListener(v -> {
            currentKeyword = etKeyword.getText().toString().trim();
            if (currentKeyword.isEmpty()) {
                Toast.makeText(this, "请输入关键字", Toast.LENGTH_SHORT).show();
                return;
            }

            btnStart.setEnabled(false);
            m3uResults.clear();
            successfulIps.clear();
            nodeUrlsToParse.clear();
            
            // 重置翻页状态
            currentPage = 1;
            nextPageUrl = "";
            isSearching = true;
            
            log("1. 正在启动浏览器...");
            webView.loadUrl(SEARCH_URL);
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        String fakeUA = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36";
        settings.setUserAgentString(fakeUA);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined});", null);
                new Handler(Looper.getMainLooper()).postDelayed(() -> handlePageFinished(url), 3000);
            }
        });
    }

    private void handlePageFinished(String url) {
        if (url.contains("channellist.html")) {
            // 只要包含 channellist，就去解析通道
            extractNodeChannels(url);
        } else if (isSearching) {
            // 只要是在搜索/翻页状态中，且加载完的不是通道，就一律判定为“搜索结果页”
            if (currentPage == 1) {
                log("2. 注入JS搜索:【" + currentKeyword + "】...");
                String js = "document.getElementById('search').value='" + currentKeyword + "';" +
                            "document.querySelector('input[type=submit]').click();";
                webView.evaluateJavascript(js, null);
                isSearching = false; 
                new Handler(Looper.getMainLooper()).postDelayed(this::extractSearchResults, 5000);
            } else {
                // 第二页及后续页面，不需要注入JS搜索，直接提取节点！
                isSearching = false;
                extractSearchResults();
            }
        }
    }

    private void extractSearchResults() {
        log("3. 提取第 " + currentPage + " 页的搜索结果...");
        webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})();", html -> {
            String cleanHtml = unescapeHtml(html);
            Document doc = Jsoup.parse(cleanHtml);
            Elements results = doc.select("div.result");

            for (Element item : results) {
                Element statusDiv = item.selectFirst("div[style*='float: right']");
                if (statusDiv != null && statusDiv.text().contains("暂时失效")) continue;

                Element aTag = item.selectFirst("div.channel a[href]");
                if (aTag != null && aTag.attr("href").contains("p=2")) {
                    nodeUrlsToParse.add(BASE_URL + aTag.attr("href"));
                }
            }

            // 提取翻页链接：寻找带有 ">>" 的 a 标签
            nextPageUrl = ""; 
            Elements aLinks = doc.select("a[href]");
            for (Element a : aLinks) {
                if (a.text().contains(">>")) {
                    nextPageUrl = BASE_URL + a.attr("href");
                    break;
                }
            }

            log("   => 第 " + currentPage + " 页导入了: " + results.size() + " 个有效候选节点");
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
            log("\n=> 解析节点: " + extractIpFromUrl(nextUrl));
            webView.loadUrl(nextUrl);
        } else {
            // 当前页的候选节点队列空了，但有效节点还没凑够，尝试翻页
            if (currentPage < MAX_SEARCH_PAGES && !nextPageUrl.isEmpty()) {
                currentPage++;
                log("\n=== 成功节点数未满 " + MAX_IP_COUNT + " 个，正在跳转至第 " + currentPage + " 页 ===");
                log("目标页地址: " + nextPageUrl);
                isSearching = true; // 设为 true，告诉 WebView 下次加载完后去提取结果
                webView.loadUrl(nextPageUrl);
            } else {
                log("\n已搜寻至设定上限页数或最末页。");
                finishAndSaveM3U();
            }
        }
    }

    private void extractNodeChannels(String currentUrl) {
        webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})();", html -> {
            String cleanHtml = unescapeHtml(html);
            Document doc = Jsoup.parse(cleanHtml);
            String currentIp = extractIpFromUrl(currentUrl);

            Elements channelItems = doc.select("div.result");
            int validCount = 0;

            for (Element item : channelItems) {
                Element channelDiv = item.selectFirst("div.channel");
                if (channelDiv == null) continue;
                
                String channelName = channelDiv.text().replaceAll("\\s+", "");
                if (channelName.isEmpty()) continue;
                if (channelName.toUpperCase().endsWith("SD")) continue;

                String proxyUrl = "";
                for (Element a : item.select("a[href]")) {
                    if (!a.attr("href").contains("channellist")) {
                        proxyUrl = a.attr("href");
                        break;
                    }
                }

                if (!proxyUrl.isEmpty()) {
                    String rawStreamUrl = decodeStreamUrl(proxyUrl, currentIp);
                    if (rawStreamUrl.startsWith("http") && !rawStreamUrl.contains("zqjy.info")) {
                        m3uResults.add(String.format("#EXTINF:-1 group-title=\"%s频道\",%s\n%s", currentKeyword, channelName, rawStreamUrl));
                        validCount++;
                    }
                }
            }

            if (validCount > 0) {
                successfulIps.add(currentIp);
                log("   [成功] 提取 " + validCount + " 个源。进度: " + successfulIps.size() + "/" + MAX_IP_COUNT);
            } else {
                log("   [跳过] 无有效源。");
            }
            processNextNode();
        });
    }

    private String extractIpFromUrl(String url) {
        Matcher matcher = Pattern.compile("ip=([^&]+)").matcher(url);
        return matcher.find() ? matcher.group(1) : "未知IP";
    }

    private String decodeStreamUrl(String proxyUrl, String finalIp) {
        try {
            if (!proxyUrl.contains("/tv/")) return proxyUrl;
            String encodedPart = proxyUrl.substring(proxyUrl.lastIndexOf("/tv/") + 4);
            
            String marker = null, scheme = "http";
            if (encodedPart.contains("Gh0dHA6Ly8")) { marker = "Gh0dHA6Ly8"; scheme = "http"; }
            else if (encodedPart.contains("Gh0dHBzOi8v")) { marker = "Gh0dHBzOi8v"; scheme = "https"; }

            if (marker != null) {
                String payloadB64 = encodedPart.substring(encodedPart.indexOf(marker) + marker.length() + 1);
                payloadB64 = payloadB64.replaceAll("=$", ""); 
                while (payloadB64.length() % 4 != 0) payloadB64 += "="; 
                
                String decodedStr = new String(Base64.decode(payloadB64, Base64.DEFAULT), "UTF-8").trim();
                if (decodedStr.contains("/")) {
                    String hostPart = decodedStr.substring(0, decodedStr.indexOf("/"));
                    String pathPart = decodedStr.substring(decodedStr.indexOf("/") + 1);
                    String port = hostPart.contains(":") ? hostPart.substring(hostPart.indexOf(":")) : "";
                    return scheme + "://" + finalIp + port + "/" + pathPart;
                }
            }
        } catch (Exception e) {}
        return proxyUrl;
    }

    private void finishAndSaveM3U() {
        log("\n[结束] 节点处理完毕。");
        if (m3uResults.isEmpty()) {
            btnStart.setEnabled(true);
            return;
        }

        try {
            String fileName = currentKeyword + "_multicast.m3u";
            ContentResolver resolver = getContentResolver();

            // 如果是 Android Q (Android 10) 及以上系统，使用 MediaStore 写入公共 Download
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                Uri contentUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;

                // 【修复：覆盖逻辑】在插入前先尝试删除同名的旧文件
                String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
                String[] selectionArgs = new String[]{fileName};
                resolver.delete(contentUri, selection, selectionArgs);

                // 配置新文件信息
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = resolver.insert(contentUri, values);
                if (uri != null) {
                    try (OutputStream os = resolver.openOutputStream(uri)) {
                        if (os != null) {
                            os.write("#EXTM3U\n".getBytes());
                            for (String line : m3uResults) {
                                os.write((line + "\n").getBytes());
                            }
                        }
                    }
                    log("\n已成功保存并【覆盖】公共目录：\n/Download/" + fileName);
                    Toast.makeText(this, "保存并覆盖成功！在 内部存储/Download 中查看", Toast.LENGTH_LONG).show();
                } else {
                    log("保存失败：创建媒体流失败");
                }
            } else {
                // 兼容 Android 10 以下的设备
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, fileName);
                
                FileOutputStream fos = new FileOutputStream(file);
                fos.write("#EXTM3U\n".getBytes());
                for (String line : m3uResults) fos.write((line + "\n").getBytes());
                fos.close();
                
                log("\n已成功保存并【覆盖】公共目录：\n" + file.getAbsolutePath());
                Toast.makeText(this, "保存并覆盖成功！", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            log("保存失败: " + e.getMessage());
            e.printStackTrace();
        }
        btnStart.setEnabled(true);
    }

    private String unescapeHtml(String html) {
        if (html == null) return "";
        return html.replace("\\u003C", "<")
                   .replace("\\\"", "\"")
                   .replace("\\n", "")
                   .replace("\\t", "")  // 清除 JS 传回的字面量制表符
                   .replace("\\r", "")  // 清除回车符
                   .replaceAll("^\"|\"$", "");
    }

    private void log(String msg) {
        runOnUiThread(() -> tvLog.append(msg + "\n"));
    }
}
