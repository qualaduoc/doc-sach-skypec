package com.skypec.reader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // BroadcastReceiver này dùng để đăng ký nhận sự kiện khởi động thiết bị
        // Giúp nâng cao độ ổn định hệ thống trong tương lai nếu cần tự động chạy lại
    }
}
