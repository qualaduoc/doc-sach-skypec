package com.skypec.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Timer;
import java.util.TimerTask;

public class HeartbeatService extends Service {

    private static final String CHANNEL_ID = "SkypecReaderChannel";
    private Timer timer;
    private String classTitle;
    private String classUserId;
    private String token;

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

        // Bắt đầu vòng lặp nhịp tim gửi tín hiệu tính giờ đọc sách
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
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                // Đọc phản hồi thành công (để đảm bảo luồng HTTP được đóng hoàn chỉnh)
                InputStream is = conn.getInputStream();
                byte[] buffer = new byte[1024];
                while (is.read(buffer) != -1) { /* Xử lý đọc hết luồng */ }
                is.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Override
    public void onDestroy() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
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
