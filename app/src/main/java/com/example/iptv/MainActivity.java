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

    // 内部数据类：用于存放频道，方便后期分类排序
    static class Channel {
        String name;
        String group;
        String url;

        Channel(String name, String group, String url) {
            this.name = name;
            this.group = group;
            this.url = url;
        }
    }

    private WebView webView;
    private TextView tvLog;
    private Button btnStart;
    private EditText etKeyword;

    private static final String BASE_URL = "https://tonkiang.us/";
    private static final String SEARCH_URL = BASE_URL + "iptvmulticast.php";
    private static final int MAX_IP_COUNT = 8;
    private static final int MAX_SEARCH_PAGES = 6; 

    private boolean isSearching = false;
    private Queue<String> nodeUrlsToParse = new LinkedList<>();
    private List<String> successfulIps = new ArrayList<>();
    private String currentKeyword = "";

    // 翻页控制
    private String nextPageUrl = "";
    private int currentPage = 1;

    // 排序“多桶”定义：严格对应用户要求的 1, 2, 3, 4, 5 顺序
    private List<Channel> cctvGroup = new ArrayList<>();     // 1. 央视频道
    private List<Channel> localGroup = new ArrayList<>();    // 2. 湖北/本地频道
    private List<Channel> weishiGroup = new ArrayList<>();   // 3. 卫视频道
    private List<Channel> gmtGroup = new ArrayList<>();      // 4. 港澳台频道
    private List<Channel> movieGroup = new ArrayList<>();    // 5.1 其他：影视频道
    private List<Channel> sportsGroup = new ArrayList<>();   // 5.2 其他：体育频道
    private List<Channel> kidsGroup = new ArrayList<>();     // 5.3 其他：少儿频道
    private List<Channel> otherGroup = new ArrayList<>();    // 5.4 其他：普通频道

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
            successfulIps.clear();
            nodeUrlsToParse.clear();
            
            // 清理所有的“小桶”
            cctvGroup.clear();
            localGroup.clear();
            weishiGroup.clear();
            gmtGroup.clear();
            movieGroup.clear();
            sportsGroup.clear();
            kidsGroup.clear();
            otherGroup.clear();

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

            for (Element item : results) {
                Element statusDiv = item.selectFirst("div[style*='float: right']");
                if (statusDiv != null && statusDiv.text().contains("暂时失效")) continue;

                Element aTag = item.selectFirst("div.channel a[href]");
                if (aTag != null && aTag.attr("href").contains("p=2")) {
                    nodeUrlsToParse.add(aTag.absUrl("href"));
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
                        // 智能进行多桶分类装载
                        dispatchChannel(channelName, rawStreamUrl);
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

    // 智能分类投递模块
    private void dispatchChannel(String name, String url) {
        String nameUpper = name.toUpperCase();

        // 1. 央视频道
        if (nameUpper.contains("CCTV") || nameUpper.contains("CGTN") || nameUpper.contains("CETV")) {
            cctvGroup.add(new Channel(name, "央视频道", url));
            return;
        }

        // 2. 湖北/本地频道 (动态跟随输入框，本地特异词做保底)
        boolean isLocal = false;
        if (!currentKeyword.isEmpty() && name.contains(currentKeyword)) {
            isLocal = true;
        } else {
            String[] localKeywords = {"湖北", "武汉", "房县", "阳新", "蔡甸", "垄上", "经视"};
            for (String kw : localKeywords) {
                if (name.contains(kw)) {
                    isLocal = true;
                    break;
                }
            }
        }
        if (isLocal) {
            localGroup.add(new Channel(name, currentKeyword + "频道", url));
            return;
        }

        // 3. 卫视频道
        if (name.contains("卫视")) {
            weishiGroup.add(new Channel(name, "卫视频道", url));
            return;
        }

        // 4. 港澳台频道
        if (name.contains("凤凰") || nameUpper.contains("TVB") || name.contains("翡翠") || name.contains("明珠") || name.contains("星空")) {
            gmtGroup.add(new Channel(name, "港澳台", url));
            return;
        }

        // 5. 其他频道分类（网络一般规律细分）
        // 5.1 影视频道
        if (nameUpper.contains("CHC") || name.contains("电影") || name.contains("影院") || name.contains("剧场") || name.contains("戏曲") || name.contains("梨园")) {
            movieGroup.add(new Channel(name, "影视频道", url));
            return;
        }
        // 5.2 体育频道
        if (name.contains("体育") || name.contains("足球") || name.contains("高尔夫") || name.contains("台球") || name.contains("网球") || name.contains("垂钓") || name.contains("武术") || name.contains("竞技") || name.contains("风云")) {
            sportsGroup.add(new Channel(name, "体育频道", url));
            return;
        }
        // 5.3 少儿/动漫频道
        if (name.contains("少儿") || name.contains("卡通") || name.contains("动漫") || name.contains("动画") || name.contains("游戏") || name.contains("金鹰")) {
            kidsGroup.add(new Channel(name, "少儿频道", url));
            return;
        }

        // 5.4 实在没有匹配上的，归入其他
        otherGroup.add(new Channel(name, "其他频道", url));
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
        log("\n[结束] 节点处理完毕。正在拼装高精排序M3U列表...");

        // 严格按照用户指定的 1, 2, 3, 4, 5 顺序，进行“大合拢”
        List<Channel> finalSortedList = new ArrayList<>();
        finalSortedList.addAll(cctvGroup);     // 1. 央视
        finalSortedList.addAll(localGroup);    // 2. 湖北/本地
        finalSortedList.addAll(weishiGroup);   // 3. 卫视
        finalSortedList.addAll(gmtGroup);      // 4. 港澳台
        finalSortedList.addAll(movieGroup);    // 5.1 影视
        finalSortedList.addAll(sportsGroup);   // 5.2 体育
        finalSortedList.addAll(kidsGroup);     // 5.3 少儿
        finalSortedList.addAll(otherGroup);    // 5.4 其他

        if (finalSortedList.isEmpty()) {
            btnStart.setEnabled(true);
            return;
        }

        try {
            String fileName = currentKeyword + "_multicast.m3u";
            ContentResolver resolver = getContentResolver();

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                Uri contentUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;

                // 覆盖写入：删掉旧的
                String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
                String[] selectionArgs = new String[]{fileName};
                resolver.delete(contentUri, selection, selectionArgs);

                // 插入新的
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = resolver.insert(contentUri, values);
                if (uri != null) {
                    try (OutputStream os = resolver.openOutputStream(uri)) {
                        if (os != null) {
                            os.write("#EXTM3U\n".getBytes());
                            for (Channel ch : finalSortedList) {
                                String line = String.format("#EXTINF:-1 group-title=\"%s\",%s\n%s", ch.group, ch.name, ch.url);
                                os.write((line + "\n").getBytes());
                            }
                        }
                    }
                    log("\n[大获成功] 已成功保存并【覆盖】公共目录：\n/Download/" + fileName);
                    Toast.makeText(this, "列表已按顺序分类完美导出！", Toast.LENGTH_LONG).show();
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, fileName);
                
                FileOutputStream fos = new FileOutputStream(file);
                fos.write("#EXTM3U\n".getBytes());
                for (Channel ch : finalSortedList) {
                    String line = String.format("#EXTINF:-1 group-title=\"%s\",%s\n%s", ch.group, ch.name, ch.url);
                    fos.write((line + "\n").getBytes());
                }
                fos.close();
                
                log("\n[大获成功] 已成功保存并【覆盖】公共目录：\n" + file.getAbsolutePath());
                Toast.makeText(this, "列表已按顺序分类完美导出！", Toast.LENGTH_LONG).show();
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
                   .replace("\\t", "")  
                   .replace("\\r", "")  
                   .replaceAll("^\"|\"$", "");
    }

    private void log(String msg) {
        runOnUiThread(() -> tvLog.append(msg + "\n"));
    }
}
