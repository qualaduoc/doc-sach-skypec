package com.skypec.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class HeartbeatService extends Service {

    private static final String CHANNEL_ID = "SkypecReaderChannel";
    private static final String TAG = "HeartbeatService";
    private static final String RECORD_SEPARATOR = "\u001e";

    private String classTitle;
    private String classUserId;
    private String token;
    private String learningId;
    private String classId;
    private String contentId; // ID của bài học đầu tiên trong lớp

    private OkHttpClient client;
    private WebSocket webSocket;
    private Timer pingTimer;
    private Timer videoTimer;
    private int videoTimeSeconds = 10;
    private int invocationId = 1;
    private boolean isDestroyed = false;

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        // Kích hoạt WakeLock để CPU không bị ngủ đông khi tắt màn hình
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SkypecReader::WakeLock");
            wakeLock.acquire(10 * 60 * 1000L /* Giữ 10 phút, nhưng thực tế sẽ được giữ tới khi dừng service */);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            classTitle = intent.getStringExtra("classTitle");
            classUserId = intent.getStringExtra("classUserId");
            token = intent.getStringExtra("token");
            learningId = intent.getStringExtra("learningId");
            classId = intent.getStringExtra("classId");
            Log.d(TAG, "Dịch vụ chạy ngầm khởi động: classUserId=" + classUserId + ", learningId=" + learningId + ", classId=" + classId);
        }

        // Giao diện thông báo thanh trạng thái
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Đang tự động học tập Skypec (Native)")
                .setContentText("Lớp học: " + (classTitle != null ? classTitle : "Đang kết nối..."))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);

        // Bắt đầu tiến trình kết nối và gửi nhịp tim
        startAutomationProcess();

        return START_NOT_STICKY;
    }

    private void startAutomationProcess() {
        if (token == null || classUserId == null) {
            Log.e(TAG, "Thiếu token hoặc classUserId. Không thể chạy.");
            return;
        }

        // 1. Gọi API frUserUpdateViewNew trước (chạy một lần duy nhất)
        callUpdateViewAPI();

        // 2. Lấy thông tin bài học của lớp học để tìm contentId
        if (classId != null) {
            fetchClassContent();
        } else {
            // Nếu không có classId, dùng WebSocket trực tiếp không gửi video_time_update
            connectWebSocket();
        }
    }

    private void callUpdateViewAPI() {
        Request request = new Request.Builder()
                .url("https://elearning.skypec.com.vn/skypec2.lms.api/api/v1/LmsClassContent/frUserUpdateViewNew/" + classUserId)
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Gọi API frUserUpdateViewNew thất bại: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "Gọi API frUserUpdateViewNew thành công. Code: " + response.code());
                response.close();
            }
        });
    }

    private void fetchClassContent() {
        Request request = new Request.Builder()
                .url("https://elearning.skypec.com.vn/skypec2.lms.api/api/v1/LmsClassContent/frGetByClassId/" + classId)
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Lấy nội dung lớp học thất bại: " + e.getMessage() + ". Sử dụng mặc định.");
                connectWebSocket();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseBody = response.body().string();
                        JSONObject json = new JSONObject(responseBody);
                        if (json.getBoolean("status")) {
                            JSONArray dataArray = json.getJSONArray("data");
                            if (dataArray.length() > 0) {
                                // Lấy ID của bài học đầu tiên trong danh sách
                                JSONObject firstContent = dataArray.getJSONObject(0);
                                contentId = firstContent.getString("id");
                                Log.d(TAG, "Đã tìm thấy bài học ID: " + contentId);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Lỗi phân tích nội dung lớp học: " + e.getMessage());
                    }
                }
                response.close();
                // Bắt đầu kết nối WebSocket LRS
                connectWebSocket();
            }
        });
    }

    private void connectWebSocket() {
        if (isDestroyed || learningId == null) return;

        Log.d(TAG, "Đang kết nối WebSocket LRS Hub tại /socket/hubs/lrs...");
        String encodedToken = "";
        try {
            encodedToken = URLEncoder.encode(token, "UTF-8");
        } catch (Exception e) {
            encodedToken = token;
        }

        String wsUrl = "wss://elearning.skypec.com.vn/skypec2.lms.api/socket/hubs/lrs" +
                "?learningId=" + learningId +
                "&clientProtocol=1.5" +
                "&access_token=" + encodedToken;

        Request request = new Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.d(TAG, "WebSocket LRS đã kết nối thành công! Gửi handshake...");
                // Gửi tin nhắn bắt tay của SignalR
                webSocket.send("{\"protocol\":\"json\",\"version\":1}" + RECORD_SEPARATOR);
                
                // Khởi động các vòng lặp gửi tin nhắn duy trì
                startTimers();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.d(TAG, "WebSocket nhận: " + text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                Log.d(TAG, "WebSocket đang đóng: " + reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.d(TAG, "WebSocket đã đóng. Kết nối lại sau 5 giây...");
                stopTimers();
                reconnectLater();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "WebSocket lỗi: " + t.getMessage() + ". Kết nối lại sau 5 giây...");
                stopTimers();
                reconnectLater();
            }
        });
    }

    private void reconnectLater() {
        if (isDestroyed) return;
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                connectWebSocket();
            }
        }, 5000);
    }

    private void startTimers() {
        stopTimers();

        pingTimer = new Timer();
        pingTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (webSocket != null) {
                    // Gửi tin nhắn Ping của SignalR (type = 6)
                    webSocket.send("{\"type\":6}" + RECORD_SEPARATOR);
                    Log.d(TAG, "[SignalR] Gửi Ping");
                }
            }
        }, 15000, 15000);

        if (contentId != null && learningId != null) {
            videoTimer = new Timer();
            videoTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    if (webSocket != null) {
                        try {
                            String innerPayload = "{\\\"eventName\\\":\\\"VIDEO_TIME_UPDATE\\\",\\\"learningId\\\":\\\"" + learningId + "\\\",\\\"id\\\":\\\"" + contentId + "\\\",\\\"data\\\":" + videoTimeSeconds + "}";
                            String message = "{\"type\":1,\"invocationId\":\"" + invocationId + "\",\"target\":\"Handshake\",\"arguments\":[\"" + innerPayload + "\"]}" + RECORD_SEPARATOR;
                            
                            webSocket.send(message);
                            Log.d(TAG, "[SignalR] Gửi VIDEO_TIME_UPDATE - Giây video: " + videoTimeSeconds + "s");
                            
                            videoTimeSeconds += 10;
                            invocationId++;
                        } catch (Exception e) {
                            Log.e(TAG, "Lỗi tạo tin nhắn VIDEO_TIME_UPDATE: " + e.getMessage());
                        }
                    }
                }
            }, 10000, 10000);
        }
    }

    private void stopTimers() {
        if (pingTimer != null) {
            pingTimer.cancel();
            pingTimer = null;
        }
        if (videoTimer != null) {
            videoTimer.cancel();
            videoTimer = null;
        }
    }

    @Override
    public void onDestroy() {
        isDestroyed = true;
        stopTimers();
        if (webSocket != null) {
            webSocket.close(1000, "Service stopped");
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        Log.d(TAG, "Dịch vụ chạy ngầm đã dừng và dọn dẹp kết nối.");
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
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
