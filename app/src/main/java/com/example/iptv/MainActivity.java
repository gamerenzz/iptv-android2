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
    private EditText etIpFilter; // 新增过滤器控件

    private static final String BASE_URL = "https://tonkiang.us/";
    private static final String SEARCH_URL = BASE_URL + "iptvmulticast.php";
    private static final int MAX_IP_COUNT = 8;
    private static final int MAX_SEARCH_PAGES = 6; 

    private boolean isSearching = false;
    private Queue<String> nodeUrlsToParse = new LinkedList<>();
    private List<String> m3uResults = new ArrayList<>();
    private List<String> successfulIps = new ArrayList<>();
    private String currentKeyword = "";
    private String ipFilterInput = ""; // 存放过滤器输入

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
        etIpFilter = findViewById(R.id.etIpFilter); // 初始化

        setupWebView();

        btnStart.setOnClickListener(v -> {
            currentKeyword = etKeyword.getText().toString().trim();
            ipFilterInput = etIpFilter.getText().toString().trim(); // 获取过滤条件
            
            if (currentKeyword.isEmpty()) {
                Toast.makeText(this, "请输入关键字", Toast.LENGTH_SHORT).show();
                return;
            }

            btnStart.setEnabled(false);
            m3uResults.clear();
            successfulIps.clear();
            nodeUrlsToParse.clear();
            
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
        String fakeUA = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, Gecko) Chrome/119.0.0.0 Mobile Safari/537.36";
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
            extractNodeChannels(url);
        } else if (isSearching) {
            if (currentPage == 1) {
                log("2. 注入JS搜索:【" + currentKeyword + "】...");
                String js = "document.getElementById('search').value='" + currentKeyword + "';" +
                            "document.querySelector('input[type=submit]').click();";
                webView.evaluateJavascript(js, null);
                isSearching = false; 
                new Handler(Looper.getMainLooper()).postDelayed(this::extractSearchResults, 5000);
            } else {
                isSearching = false;
                log("   => 正在等待第 " + currentPage + " 页数据渲染...");
                new Handler(Looper.getMainLooper()).postDelayed(this::extractSearchResults, 3000);
            }
        }
    }

    private void extractSearchResults() {
        log("3. 提取第 " + currentPage + " 页的搜索结果...");
        webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})();", html -> {
            String cleanHtml = unescapeHtml(html);
            Document doc = Jsoup.parse(cleanHtml, webView.getUrl()); 
            Elements results = doc.select("div.result");

            // 解析过滤器规则
            boolean isWhitelistMode = false;
            List<String> whitelistIps = new ArrayList<>();
            List<String> blacklistIps = new ArrayList<>();

            if (!ipFilterInput.isEmpty()) {
                String[] parts = ipFilterInput.split(",");
                for (String p : parts) {
                    String cleanIp = p.trim();
                    if (cleanIp.startsWith("-")) {
                        blacklistIps.add(cleanIp.substring(1).trim()); // 排除名单
                    } else {
                        isWhitelistMode = true;
                        whitelistIps.add(cleanIp); // 仅保留名单
                    }
                }
            }

            for (Element item : results) {
                Element statusDiv = item.selectFirst("div[style*='float: right']");
                if (statusDiv != null && statusDiv.text().contains("暂时失效")) continue;

                Element aTag = item.selectFirst("div.channel a[href]");
                if (aTag != null && aTag.attr("href").contains("p=2")) {
                    String absUrl = aTag.absUrl("href");
                    String nodeIp = extractIpFromUrl(absUrl);

                    // 执行 IP 智能过滤
                    if (isWhitelistMode) {
                        if (!whitelistIps.contains(nodeIp)) {
                            continue; // 跳过不在白名单里的 IP
                        }
                    } else {
                        if (blacklistIps.contains(nodeIp)) {
                            log("   [排除] 黑名单跳过 IP: " + nodeIp);
                            continue; // 跳过黑名单里的 IP
                        }
                    }

                    nodeUrlsToParse.add(absUrl);
                }
            }

            nextPageUrl = ""; 
            Elements aLinks = doc.select("a[href]");
            for (Element a : aLinks) {
                if (a.text().contains(">>")) {
                    nextPageUrl = a.absUrl("href");
                    break;
                }
            }

            log("   => 第 " + currentPage + " 页导入了: " + nodeUrlsToParse.size() + " 个候选节点");
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
            if (currentPage < MAX_SEARCH_PAGES && !nextPageUrl.isEmpty()) {
                currentPage++;
                log("\n=== 成功节点数未满 " + MAX_IP_COUNT + " 个，正在跳转至第 " + currentPage + " 页 ===");
                log("目标页地址: " + nextPageUrl);
                isSearching = true; 
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
            Document doc = Jsoup.parse(cleanHtml, webView.getUrl());
            String currentIp = extractIpFromUrl(currentUrl);

            Elements channelItems = doc.select("div.result");

            int validCount = 0;
            java.util.HashSet<String> uniqueKeys = new java.util.HashSet<>(); 

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

                if (proxyUrl.isEmpty()) {
                    Pattern urlPattern = Pattern.compile("https?://[a-zA-Z0-9+&@#/%?=~_|!:,.;]*[a-zA-Z0-9+&@#/%=~_|]");
                    Matcher urlMatcher = urlPattern.matcher(item.outerHtml()); 
                    if (urlMatcher.find()) {
                        proxyUrl = urlMatcher.group();
                    }
                }

                if (!proxyUrl.isEmpty()) {
                    String rawStreamUrl = decodeStreamUrl(proxyUrl, currentIp);
                    if (rawStreamUrl.startsWith("http") && !rawStreamUrl.contains("zqjy.info")) {
                        
                        String uniqueKey = channelName + "_" + rawStreamUrl;
                        if (uniqueKeys.contains(uniqueKey)) continue;
                        uniqueKeys.add(uniqueKey);

                        String groupName = determineGroup(channelName, currentKeyword);
                        m3uResults.add(String.format("#EXTINF:-1 group-title=\"%s\",%s\n%s", groupName, channelName, rawStreamUrl));
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

    private String determineGroup(String name, String keyword) {
        String nameUpper = name.toUpperCase();

        if (nameUpper.contains("4K")) {
            return "4K频道";
        }
        
        if (nameUpper.contains("CCTV") || nameUpper.contains("CGTN") || nameUpper.contains("CETV")) {
            return "央视频道";
        }
        
        if (name.contains(keyword) || name.contains("武汉") || name.contains("长沙") || name.contains("经视") || name.contains("垄上") || name.contains("综合") || name.contains("新闻")) {
            return keyword + "频道";
        }
        
        if (name.contains("卫视")) {
            return "卫视频道";
        }
        
        String[] sports = {"体育", "足球", "高尔夫", "台球", "网球", "垂钓", "羽", "乒", "武术", "世界", "风云足球"};
        for (String sp : sports) {
            if (name.contains(sp)) return "体育频道";
        }
        
        String[] movies = {"CHC", "电影", "剧场", "影迷", "故事", "戏曲", "梨园", "第一剧场"};
        for (String mv : movies) {
            if (nameUpper.contains(mv)) return "影视频道";
        }
        
        String[] kids = {"动漫", "少儿", "卡通", "卡酷", "游戏", "金鹰卡通"};
        for (String kd : kids) {
            if (name.contains(kd)) return "少儿频道";
        }
        
        return "其他频道";
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

            try {
                String padded = encodedPart.trim().replaceAll("=$", "");
                while (padded.length() % 4 != 0) padded += "=";
                byte[] decodedBytes = Base64.decode(padded, Base64.DEFAULT);
                String decodedStr = new String(decodedBytes, "UTF-8");
                if (decodedStr.contains("http")) {
                    int httpIdx = decodedStr.indexOf("http");
                    return decodedStr.substring(httpIdx).trim();
                }
            } catch (Exception e) {}

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

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                Uri contentUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;

                String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
                String[] selectionArgs = new String[]{fileName};
                resolver.delete(contentUri, selection, selectionArgs);

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
                   .replace("\\/", "/")   
                   .replace("\\n", "")
                   .replace("\\t", "")  
                   .replace("\\r", "")  
                   .replaceAll("^\"|\"$", "");
    }

    private void log(String msg) {
        runOnUiThread(() -> tvLog.append(msg + "\n"));
    }
}
