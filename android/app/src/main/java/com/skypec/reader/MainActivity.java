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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Tạo WebView toàn màn hình
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        
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

        // Đăng ký cầu nối JavascriptInterface tên là "Android"
        webView.addJavascriptInterface(new WebAppInterface(), "Android");

        // Load giao diện HTML từ thư mục assets
        webView.loadUrl("file:///android_asset/index.html");
    }

    // Cầu nối giao tiếp giữa JavaScript và Java
    public class WebAppInterface {

        // Bắt đầu dịch vụ chạy ngầm Foreground Service để gửi tín hiệu nhịp tim đọc sách
        @JavascriptInterface
        public void startForegroundService(String classTitle, String classUserId, String token) {
            Intent serviceIntent = new Intent(MainActivity.this, HeartbeatService.class);
            serviceIntent.putExtra("classTitle", classTitle);
            serviceIntent.putExtra("classUserId", classUserId);
            serviceIntent.putExtra("token", token);
            
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

                        // Thêm headers
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

                        // Nếu thất bại (non-2xx)
                        if (responseCode < 200 || responseCode >= 300) {
                            if (responseStr.isEmpty()) {
                                responseStr = "{\"error\":\"Lỗi HTTP " + responseCode + "\",\"statusCode\":" + responseCode + "}";
                            } else {
                                // Nếu có error stream nhưng không phải định dạng JSON, bọc nó lại
                                if (!responseStr.startsWith("{") && !responseStr.startsWith("[")) {
                                    JSONObject errObj = new JSONObject();
                                    errObj.put("error", responseStr);
                                    errObj.put("statusCode", responseCode);
                                    responseStr = errObj.toString();
                                }
                            }
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
            android.app.NotificationManager notificationManager = (android.app.NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            String channelId = "SkypecReaderCompletionChannel";
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        channelId,
                        "Thông báo hoàn thành đọc sách",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Thông báo khi hoàn thành đủ số phút đọc sách");
                channel.enableVibration(true);
                channel.setVibrationPattern(new long[]{0, 500, 250, 500}); // Rung 2 lần
                if (notificationManager != null) {
                    notificationManager.createNotificationChannel(channel);
                }
            }
            
            Intent intent = new Intent(MainActivity.this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    MainActivity.this, 0, intent,
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
            );
            
            androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(MainActivity.this, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setAutoCancel(true)
                    .setDefaults(android.app.Notification.DEFAULT_ALL) // Dùng âm thanh và rung mặc định
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent);
                    
            if (notificationManager != null) {
                notificationManager.notify(2, builder.build());
            }
        }
    }

    @Override
    public void onBackPressed() {
        // Cho phép WebView quay lại trang trước nếu có
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
