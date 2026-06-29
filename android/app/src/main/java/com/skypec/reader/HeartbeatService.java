package com.skypec.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Timer;
import java.util.TimerTask;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class HeartbeatService extends Service {

    private static final String CHANNEL_ID = "SkypecReaderChannel";
    private static final String TAG = "HeartbeatService";
    
    private Timer timer;
    private String classTitle;
    private String classUserId;
    private String token;
    private String learningId;

    // Các thành phần kết nối WebSocket Native
    private OkHttpClient okHttpClient;
    private WebSocket webSocket;
    private Timer wsPingTimer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            classTitle = intent.getStringExtra("classTitle");
            classUserId = intent.getStringExtra("classUserId");
            token = intent.getStringExtra("token");
            learningId = intent.getStringExtra("learningId");
            Log.d(TAG, "Dịch vụ khởi chạy với classUserId=" + classUserId + ", learningId=" + learningId);
        }

        // Tạo thông báo hiển thị trên thanh trạng thái (Foreground Notification)
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Đang tự động đọc sách Skypec")
                .setContentText("Lớp học: " + (classTitle != null ? classTitle : "Đang duy trì..."))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .build();

        // Đưa dịch vụ lên chế độ chạy ngầm ưu tiên cao nhất (Foreground Service)
        startForeground(1, notification);

        // 1. Khởi động kết nối WebSocket LRS ở tầng Native
        if (learningId != null && token != null) {
            startWebSocket(learningId, token);
        } else {
            Log.e(TAG, "Không tìm thấy learningId hoặc token để khởi chạy WebSocket LRS.");
        }

        // 2. Bắt đầu vòng lặp nhịp tim gửi tín hiệu HTTP GET tính giờ đọc sách (Dự phòng trạng thái)
        startHeartbeatLoop();

        return START_NOT_STICKY;
    }

    private void startHeartbeatLoop() {
        if (timer != null) {
            timer.cancel();
        }
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                sendHeartbeat();
            }
        }, 0, 60000); // Chạy mỗi 60 giây (60000 ms)
    }

    private void sendHeartbeat() {
        if (classUserId == null || token == null) return;

        HttpURLConnection conn = null;
        try {
            // URL API cập nhật tiến độ đọc sách của Skypec
            URL url = new URL("https://elearning.skypec.com.vn/skypec2.lms.api/api/v1/LmsClassContent/frUserUpdateViewNew/" + classUserId);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "HTTP Heartbeat gửi thành công. Mã phản hồi: " + responseCode);
            if (responseCode == 200) {
                // Đọc phản hồi thành công (để đảm bảo luồng HTTP được đóng hoàn chỉnh)
                InputStream is = conn.getInputStream();
                byte[] buffer = new byte[1024];
                while (is.read(buffer) != -1) { /* Xử lý đọc hết luồng */ }
                is.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi khi gửi HTTP Heartbeat: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // Thiết lập kết nối WebSocket LRS
    private void startWebSocket(final String learningId, final String token) {
        stopWebSocket(); // Đóng kết nối cũ nếu có

        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        String wsUrl = "wss://elearning.skypec.com.vn/skypec2.lms.api/socket?learningId=" + learningId + "&access_token=" + token;
        Log.d(TAG, "Native LRS: Đang kết nối tới " + wsUrl);

        Request request = new Request.Builder()
                .url(wsUrl)
                .addHeader("Origin", "https://elearning.skypec.com.vn")
                .addHeader("Referer", "https://elearning.skypec.com.vn/lop-hoc/chi-tiet/bff53599-c8eb-411b-bb6f-70e3b3791c64")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build();

        webSocket = okHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.d(TAG, "Native LRS: Kết nối WebSocket thành công. Đang gửi Handshake...");
                
                // Gửi handshake của SignalR (kết thúc bằng ký tự 0x1e - ASCII 30)
                String handshake = "{\"protocol\":\"json\",\"version\":1}" + (char) 30;
                ws.send(handshake);

                // Kích hoạt luồng gửi Ping giữ kết nối
                startSignalRPing();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                // Nhận phản hồi từ máy chủ
                if (!text.contains("\"type\":6")) {
                    Log.d(TAG, "Native LRS: Nhận phản hồi: " + text.replace("\u001e", ""));
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                Log.d(TAG, "Native LRS: Máy chủ yêu cầu đóng kết nối. Mã: " + code + ", Lý do: " + reason);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.d(TAG, "Native LRS: Kết nối đã đóng. Mã: " + code + ", Lý do: " + reason);
                stopSignalRPing();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "Native LRS: Kết nối thất bại hoặc bị ngắt đột ngột: " + t.getMessage());
                stopSignalRPing();
                
                // Tự động kết nối lại sau 5 giây nếu dịch vụ vẫn đang chạy
                if (HeartbeatService.this.webSocket != null) {
                    new Timer().schedule(new TimerTask() {
                        @Override
                        public void run() {
                            if (HeartbeatService.this.webSocket != null) {
                                Log.d(TAG, "Native LRS: Đang thử kết nối lại...");
                                startWebSocket(learningId, token);
                            }
                        }
                    }, 5000);
                }
            }
        });
    }

    // Gửi gói tin Ping định kỳ cho SignalR
    private void startSignalRPing() {
        stopSignalRPing();
        wsPingTimer = new Timer();
        wsPingTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (webSocket != null) {
                    // Gói tin Ping của SignalR (kết thúc bằng ký tự 0x1e)
                    String ping = "{\"type\":6}" + (char) 30;
                    webSocket.send(ping);
                    Log.d(TAG, "Native LRS: Đã gửi Ping giữ kết nối.");
                }
            }
        }, 15000, 15000); // Gửi mỗi 15 giây
    }

    private void stopSignalRPing() {
        if (wsPingTimer != null) {
            wsPingTimer.cancel();
            wsPingTimer = null;
        }
    }

    private void stopWebSocket() {
        stopSignalRPing();
        if (webSocket != null) {
            webSocket.close(1000, "Service stopped");
            webSocket = null;
        }
        if (okHttpClient != null) {
            okHttpClient.dispatcher().executorService().shutdown();
            okHttpClient = null;
        }
    }

    @Override
    public void onDestroy() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        stopWebSocket();
        Log.d(TAG, "Dịch vụ chạy ngầm đã dừng.");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Dịch vụ chạy ngầm Skypec Reader",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}

