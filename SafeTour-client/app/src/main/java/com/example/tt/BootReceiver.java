// C:\Users\30858\Desktop\TripTrack\app\src\main\java\com\example\tt\BootReceiver.java
package com.example.tt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "接收到广播: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            // 启动后台定位服务
            Intent serviceIntent = new Intent(context, LocationService.class);
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Log.d(TAG, "开机自启动：LocationService 已启动");
            } catch (Exception e) {
                Log.e(TAG, "启动 LocationService 失败", e);
            }
        }
    }
}