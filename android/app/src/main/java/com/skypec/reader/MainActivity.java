package com.skypec.reader;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

public class MainActivity extends AppCompatActivity {

    public static MainActivity instance;
    private WebView webView;
    private WebView automationWebView;
    private android.widget.FrameLayout layoutContainer;
    private android.widget.Button btnHideAutomation;
    
    private String autoClassId;
    private String autoUsername;
    private String autoPassword;
    private boolean isAutomating = false;

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        
        // Tạo container FrameLayout chứa cả 2 WebView
        layoutContainer = new android.widget.FrameLayout(this);
        setContentView(layoutContainer);

        // 1. WebView Giao diện chính (Xem danh sách, logs...)
        webView = new WebView(this);
        layoutContainer.addView(webView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // 2. WebView tự động hóa (Mặc định ẩn dưới nền)
        automationWebView = new WebView(this);
        automationWebView.setVisibility(android.view.View.INVISIBLE);
        layoutContainer.addView(automationWebView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // 3. Nút bấm ẩn WebView tự động hóa để quay lại giao diện App chính
        btnHideAutomation = new android.widget.Button(this);
        btnHideAutomation.setText("ẨN TRÌNH DUYỆT (QUAY LẠI APP)");
        btnHideAutomation.setBackgroundColor(android.graphics.Color.parseColor("#ff3333"));
        btnHideAutomation.setTextColor(android.graphics.Color.WHITE);
        btnHideAutomation.setVisibility(android.view.View.GONE);
        android.widget.FrameLayout.LayoutParams btnParams = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                150,
                android.view.Gravity.BOTTOM
        );
        layoutContainer.addView(btnHideAutomation, btnParams);
        btnHideAutomation.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                hideAutomationView();
            }
        });

        // Cấu hình WebView giao diện chính
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        
        // Cho phép lưu trữ và nhận Cookie (kể cả từ tên miền chéo/bên thứ ba khi chạy từ file://)
        android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }
        
        // Cho phép gọi API chéo tên miền từ file:///android_asset
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowFileAccessFromFileURLs(true);
            settings.setAllowUniversalAccessFromFileURLs(true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // Mở mọi link bên trong WebView
            }
        });

        // Hỗ trợ hiển thị hộp thoại cảnh báo alert() từ JavaScript
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final android.webkit.JsResult result) {
                new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("Thông báo")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                result.confirm();
                            }
                        })
                        .setCancelable(false)
                        .create()
                        .show();
                return true;
            }
        });

        // Cấu hình WebView tự động hóa
        WebSettings autoSettings = automationWebView.getSettings();
        autoSettings.setJavaScriptEnabled(true);
        autoSettings.setDomStorageEnabled(true);
        autoSettings.setDatabaseEnabled(true);
        autoSettings.setAllowFileAccess(true);
        autoSettings.setUseWideViewPort(true);
        autoSettings.setLoadWithOverviewMode(true);
        // Giả lập User-Agent là trình duyệt Chrome trên Desktop để giảm thiểu lỗi giao diện mobile của Skypec
        autoSettings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(automationWebView, true);
        }

        automationWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!isAutomating) return;

                android.util.Log.d("Automation", "Page finished loading: " + url);
                sendLogToWeb("Đã tải xong trang: " + url, "info");

                // 1. Tự động điền thông tin và đăng nhập khi gặp trang đăng nhập
                if (url.contains("/signin") || url.equals("https://elearning.skypec.com.vn/")) {
                    sendLogToWeb("Đang tự động thực hiện đăng nhập...", "info");
                    String jsLogin = "javascript:(function() {" +
                            "   var userField = document.getElementById('username') || document.querySelector('input[type=\"text\"]');" +
                            "   var passField = document.getElementById('password') || document.querySelector('input[type=\"password\"]');" +
                            "   var loginBtn = document.querySelector('button[type=\"submit\"]') || document.querySelector('.btn-login');" +
                            "   if (userField && passField) {" +
                            "       userField.value = '" + autoUsername + "';" +
                            "       userField.dispatchEvent(new Event('input', { bubbles: true }));" +
                            "       passField.value = '" + autoPassword + "';" +
                            "       passField.dispatchEvent(new Event('input', { bubbles: true }));" +
                            "       setTimeout(function() {" +
                            "           if (loginBtn) { " +
                            "               AndroidAutomation.sendAutomationLog('Đang click nút Đăng nhập...', 'info');" +
                            "               loginBtn.click(); " +
                            "           }" +
                            "       }, 800);" +
                            "   } else {" +
                            "       AndroidAutomation.sendAutomationLog('Không tìm thấy ô nhập tài khoản mật khẩu.', 'error');" +
                            "   }" +
                            "})()";
                    automationWebView.evaluateJavascript(jsLogin, null);
                }
                
                // 2. Đăng nhập thành công, chuyển hướng tới trang chi tiết khóa học được chọn
                else if (url.equals("https://elearning.skypec.com.vn/trang-chu") || url.contains("/bang-tong-hop-ca-nhan")) {
                    sendLogToWeb("Đăng nhập thành công. Đang chuyển hướng tới trang bài học...", "success");
                    automationWebView.loadUrl("https://elearning.skypec.com.vn/lop-hoc/chi-tiet/" + autoClassId);
                }

                // 3. Tại trang chi tiết khóa học, tự động tìm nút "Vào học" hoặc bài học đầu tiên để kích hoạt LRS
                else if (url.contains("/lop-hoc/chi-tiet/")) {
                    sendLogToWeb("Đang tìm bài học để kích hoạt thời gian...", "info");
                    String jsJoin = "javascript:(function() {" +
                            "   var checkInterval = setInterval(function() {" +
                            "       var buttons = document.querySelectorAll('button, a, div');" +
                            "       for (var i = 0; i < buttons.length; i++) {" +
                            "           var text = buttons[i].innerText || '';" +
                            "           if (text.includes('Vào học') || text.includes('Đọc sách') || text.includes('Xem tài liệu')) {" +
                            "               AndroidAutomation.sendAutomationLog('Đã tìm thấy nút học: ' + text + '. Đang mở...', 'success');" +
                            "               buttons[i].click();" +
                            "               clearInterval(checkInterval);" +
                            "               return;" +
                            "           }" +
                            "       }" +
                            "       // Nếu không có nút chữ, tự click phần tử bài học đầu tiên" +
                            "       var items = document.querySelectorAll('.content-item, .lesson-item, [class*=\"content\"]');" +
                            "       if (items.length > 0) {" +
                            "           AndroidAutomation.sendAutomationLog('Đang click mở chương mục bài học đầu tiên...', 'success');" +
                            "           items[0].click();" +
                            "           clearInterval(checkInterval);" +
                            "       }" +
                            "   }, 1500);" +
                            "   // Hủy kiểm tra sau 10 giây tránh lặp vô hạn" +
                            "   setTimeout(function() { clearInterval(checkInterval); }, 10000);" +
                            "})()";
                    automationWebView.evaluateJavascript(jsJoin, null);
                }

                // 4. Khi đã mở sách học tập thành công (URL chứa /hoc-tap/ hoặc /view)
                else if (url.contains("/hoc-tap/") || url.contains("/view")) {
                    sendLogToWeb("Đã kích hoạt trình đọc sách của Skypec! Thời gian học bắt đầu được tích lũy.", "success");
                    
                    // Khởi chạy bộ giám sát hộp thoại Captcha
                    String jsMonitor = "javascript:(function() {" +
                            "   if (window.captchaMonitorInterval) clearInterval(window.captchaMonitorInterval);" +
                            "   window.captchaMonitorInterval = setInterval(function() {" +
                            "       var captchaModal = document.querySelector('.modal-captcha, #captcha, [class*=\"captcha\"], iframe[src*=\"recaptcha\"]');" +
                            "       var isVisible = false;" +
                            "       if (captchaModal) {" +
                            "           var rect = captchaModal.getBoundingClientRect();" +
                            "           isVisible = rect.width > 0 && rect.height > 0;" +
                            "       }" +
                            "       if (isVisible) {" +
                            "           AndroidAutomation.onCaptchaRequired();" +
                            "       }" +
                            "   }, 4000);" +
                            "})()";
                    automationWebView.evaluateJavascript(jsMonitor, null);
                }
            }
        });

        // Đăng ký các cầu nối giao tiếp JavaScript
        webView.addJavascriptInterface(new WebAppInterface(), "Android");
        automationWebView.addJavascriptInterface(new AutomationInterface(), "AndroidAutomation");

        // Load giao diện HTML chính của App
        webView.loadUrl("file:///android_asset/index.html");
    }

    // Giao diện cầu nối cho WebView tự động hóa
    public class AutomationInterface {
        @JavascriptInterface
        public void sendAutomationLog(String message, String type) {
            sendLogToWeb("[Trình duyệt] " + message, type);
        }

        @JavascriptInterface
        public void onCaptchaRequired() {
            sendLogToWeb("⚠️ PHÁT HIỆN YÊU CẦU XÁC MINH CAPTCHA! Đang hiển thị trình duyệt để Khầy giải...", "error");
            showAutomationView();
            
            // Rung cảnh báo
            try {
                android.os.Vibrator v = (android.os.Vibrator) getSystemService(android.content.Context.VIBRATOR_SERVICE);
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(android.os.VibrationEffect.createOneShot(1000, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(1000);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            // Đẩy thông báo khẩn cấp bằng hàm dùng chung
            showNotification("Yêu cầu giải Captcha", "Vui lòng mở App và tích chọn xác minh Captcha để duy trì học tập.", "SkypecReaderCaptchaChannel", "Yêu cầu giải Captcha");
        }
    }

    // Cầu nối giao tiếp giữa giao diện chính (JavaScript) và Java
    public class WebAppInterface {

        // Bắt đầu chế độ tự động hóa WebView
        @JavascriptInterface
        public void startAutomation(String classId, String username, String password) {
            autoClassId = classId;
            autoUsername = username;
            autoPassword = password;
            isAutomating = true;
            
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    sendLogToWeb("Đang khởi động tiến trình giả lập trình duyệt...", "info");
                    automationWebView.loadUrl("https://elearning.skypec.com.vn/signin");
                }
            });
        }

        // Dừng chế độ tự động hóa WebView
        @JavascriptInterface
        public void stopAutomation() {
            isAutomating = false;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    sendLogToWeb("Đang dừng trình duyệt tự động và dọn dẹp bộ nhớ...", "info");
                    automationWebView.loadUrl("about:blank");
                    hideAutomationView();
                }
            });
        }

        // Bắt đầu dịch vụ chạy ngầm Foreground Service để giữ CPU hoạt động (WakeLock)
        @JavascriptInterface
        public void startForegroundService(String classTitle, String classUserId, String token, String learningId, String classId) {
            Intent serviceIntent = new Intent(MainActivity.this, HeartbeatService.class);
            serviceIntent.putExtra("classTitle", classTitle);
            serviceIntent.putExtra("classUserId", classUserId);
            serviceIntent.putExtra("token", token);
            serviceIntent.putExtra("learningId", learningId);
            serviceIntent.putExtra("classId", classId);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                MainActivity.this.startForegroundService(serviceIntent);
            } else {
                MainActivity.this.startService(serviceIntent);
            }
        }

        // Dừng dịch vụ chạy ngầm
        @JavascriptInterface
        public void stopForegroundService() {
            Intent serviceIntent = new Intent(MainActivity.this, HeartbeatService.class);
            MainActivity.this.stopService(serviceIntent);
        }

        // Thực hiện cuộc gọi HTTP trực tiếp từ mã Java Native để Bypass CORS (Bỏ qua chặn tên miền chéo)
        @JavascriptInterface
        public void makeRequest(final String optionsJson) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject options = new JSONObject(optionsJson);
                        String urlStr = options.getString("url");
                        String method = options.optString("method", "GET");
                        JSONObject headers = options.optJSONObject("headers");
                        String body = options.optString("body", null);
                        final String callback = options.getString("callback");

                        URL url = new URL(urlStr);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod(method);
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(10000);

                        // Giả lập User-Agent của trình duyệt di động để tránh bị WAF chặn hoặc trả về 404
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36");
                        conn.setRequestProperty("Accept", "application/json, text/plain, */*");

                        // Thêm headers từ JavaScript gửi qua
                        if (headers != null) {
                            Iterator<String> keys = headers.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                conn.setRequestProperty(key, headers.getString(key));
                            }
                        }

                        // Gửi body nếu có (dành cho POST/PUT)
                        if (body != null && !body.isEmpty()) {
                            conn.setDoOutput(true);
                            try (OutputStream os = conn.getOutputStream()) {
                                byte[] input = body.getBytes("utf-8");
                                os.write(input, 0, input.length);
                            }
                        }

                        // Đọc phản hồi
                        int responseCode = conn.getResponseCode();
                        BufferedReader br = null;
                        if (responseCode >= 200 && responseCode < 300) {
                            br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
                        } else {
                            InputStream errorStream = conn.getErrorStream();
                            if (errorStream != null) {
                                br = new BufferedReader(new InputStreamReader(errorStream, "utf-8"));
                            }
                        }

                        String responseStr = "";
                        if (br != null) {
                            StringBuilder response = new StringBuilder();
                            String responseLine;
                            while ((responseLine = br.readLine()) != null) {
                                response.append(responseLine.trim());
                            }
                            responseStr = response.toString();
                        }

                        // Nếu thành công nhưng phản hồi rỗng, trả về {} để tránh lỗi JSON
                        if (responseCode >= 200 && responseCode < 300 && responseStr.isEmpty()) {
                            responseStr = "{}";
                        }

                        // Nếu thất bại (non-2xx) - đóng gói chi tiết lỗi để chẩn đoán
                        if (responseCode < 200 || responseCode >= 300) {
                            JSONObject errObj = new JSONObject();
                            errObj.put("error", "Lỗi HTTP " + responseCode);
                            errObj.put("statusCode", responseCode);
                            errObj.put("url", urlStr);
                            errObj.put("method", method);
                            
                            // Ghi lại danh sách header gửi đi (rút gọn Token bảo mật)
                            JSONObject reqHeaders = new JSONObject();
                            if (headers != null) {
                                Iterator<String> keys = headers.keys();
                                while (keys.hasNext()) {
                                    String key = keys.next();
                                    String val = headers.getString(key);
                                    if (key.equalsIgnoreCase("Authorization") && val.length() > 25) {
                                        val = val.substring(0, 15) + "..." + val.substring(val.length() - 10);
                                    }
                                    reqHeaders.put(key, val);
                                }
                            }
                            errObj.put("requestHeaders", reqHeaders);
                            
                            if (!responseStr.isEmpty()) {
                                errObj.put("serverDetail", responseStr);
                            }
                            responseStr = errObj.toString();
                        }

                        final String finalResponse = responseStr;

                        // Gửi kết quả ngược lại cho JavaScript qua hàm callback
                        webView.post(new Runnable() {
                            @Override
                            public void run() {
                                // Escape ký tự đặc biệt để truyền vào chuỗi JS an toàn
                                String escaped = finalResponse.replace("\\", "\\\\")
                                                             .replace("'", "\\'")
                                                             .replace("\n", "\\n")
                                                             .replace("\r", "\\r");
                                webView.evaluateJavascript("window." + callback + "('" + escaped + "')", null);
                            }
                        });

                    } catch (final Exception e) {
                        e.printStackTrace();
                        // Trả về lỗi kết nối
                        try {
                            final String callback = new JSONObject(optionsJson).getString("callback");
                            final JSONObject errObj = new JSONObject();
                            errObj.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
                            webView.post(new Runnable() {
                                @Override
                                public void run() {
                                    webView.evaluateJavascript("window." + callback + "('" + errObj.toString().replace("'", "\\'") + "')", null);
                                }
                            });
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            }).start();
        }

        // Hiển thị thông báo đẩy rung và kêu chuông khi hoàn thành mục tiêu
        @JavascriptInterface
        public void showCompletionNotification(String title, String message) {
            showNotification(title, message, "SkypecReaderCompletionChannel", "Thông báo hoàn thành đọc sách");
        }
    }

    @Override
    public void onBackPressed() {
        // Nếu WebView tự động hóa đang hiện, bấm quay lại sẽ ẩn nó đi thay vì đóng app
        if (automationWebView != null && automationWebView.getVisibility() == android.view.View.VISIBLE) {
            hideAutomationView();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // Hiển thị WebView tự động hóa lên trên cùng (dùng khi cần giải Captcha)
    public void showAutomationView() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (automationWebView != null && btnHideAutomation != null) {
                    automationWebView.setVisibility(android.view.View.VISIBLE);
                    btnHideAutomation.setVisibility(android.view.View.VISIBLE);
                    automationWebView.bringToFront();
                    btnHideAutomation.bringToFront();
                }
            }
        });
    }

    // Ẩn WebView tự động hóa về chế độ chạy nền
    public void hideAutomationView() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (automationWebView != null && btnHideAutomation != null) {
                    automationWebView.setVisibility(android.view.View.INVISIBLE);
                    btnHideAutomation.setVisibility(android.view.View.GONE);
                    webView.bringToFront();
                }
            }
        });
    }

    // Gửi nhật ký từ tầng Native Java lên giao diện WebView
    public void sendLogToWeb(final String message, final String type) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    try {
                        String quotedMsg = org.json.JSONObject.quote("[Native] " + message);
                        String quotedType = org.json.JSONObject.quote(type);
                        webView.evaluateJavascript("addLog(" + quotedMsg + ", " + quotedType + ");", null);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    // Hàm hiển thị thông báo dùng chung cho toàn bộ ứng dụng
    public void showNotification(String title, String message, String channelId, String channelName) {
        android.app.NotificationManager notificationManager = (android.app.NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    channelId,
                    channelName,
                    android.app.NotificationManager.IMPORTANCE_HIGH
            );
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 500, 250, 500});
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
        
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );
        
        androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setDefaults(android.app.Notification.DEFAULT_ALL)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent);
                
        if (notificationManager != null) {
            notificationManager.notify(2, builder.build());
        }
    }
}
