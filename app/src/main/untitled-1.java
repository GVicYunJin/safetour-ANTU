// C:\Users\30858\Desktop\TripTrack\app\src\main\java\com\example\tt\LocationService.java
package com.example.tt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.Manifest;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.Priority;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LocationService extends Service {

    private static final String TAG = "LocationService";
    private static final String CHANNEL_ID = "location_service_channel";
    private static final int NOTIFICATION_ID = 1001;

    // 定位相关
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Location lastKnownLocation = null;

    // 传感器
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private float[] lastAcceleration = new float[3];

    // 后台保活
    private PowerManager.WakeLock wakeLock;
    private Handler autoUploadHandler;
    private OkHttpClient client;
    private Gson gson;

    // 标记服务是否正在运行
    private static boolean isServiceRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate - 服务创建");

        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        gson = new Gson();

        // 创建前台通知渠道
        createNotificationChannel();

        // 启动前台服务（必须在5秒内调用 startForeground，否则会被系统杀死）
        startForeground(NOTIFICATION_ID, buildNotification("定位服务运行中", "正在获取位置信息..."));
        Log.d(TAG, "已启动为前台服务");

        // 获取唤醒锁，防止CPU休眠
        acquireWakeLock();

        // 初始化定位
        initFusedLocation();

        // 初始化加速度传感器
        initAccelerometer();

        // 启动自动上传任务
        startAutoUploadTask();

        isServiceRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand - 服务启动命令");
        // START_STICKY：服务被杀死后自动重启
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy - 服务销毁");

        // 停止位置更新
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        // 注销传感器
        if (sensorManager != null) {
            sensorManager.unregisterListener(sensorEventListener);
        }

        // 移除Handler回调
        if (autoUploadHandler != null) {
            autoUploadHandler.removeCallbacksAndMessages(null);
        }

        // 释放唤醒锁
        releaseWakeLock();

        isServiceRunning = false;

        // Android 5.0+ 在某些情况下服务被杀死后通过广播重启
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 尝试重启服务作为额外的保护
            Intent restartIntent = new Intent(this, LocationService.class);
            startService(restartIntent);
        }

        super.onDestroy();
    }

    /**
     * 获取唤醒锁，防止CPU进入深度休眠
     */
    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                        "TripTrack:LocationWakeLock"
                );
                // 设置引用计数为false，直接锁
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
                Log.d(TAG, "WakeLock 已获取，CPU不会休眠");
            }
        } catch (Exception e) {
            Log.e(TAG, "获取 WakeLock 失败", e);
        }
    }

    /**
     * 释放唤醒锁
     */
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "WakeLock 已释放");
            }
        } catch (Exception e) {
            Log.e(TAG, "释放 WakeLock 失败", e);
        }
    }

    /**
     * 创建通知渠道（Android 8.0+ 必须）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "定位服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("TripTrack 后台定位和数据上传服务");
            channel.setShowBadge(false);
            channel.enableLights(false);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 构建前台通知
     */
    private Notification buildNotification(String title, String message) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        // Android 5.0+ 必须使用白色图标
        int iconRes = android.R.drawable.ic_menu_mylocation;

        builder.setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(iconRes)
                .setOngoing(true)           // 常驻通知，不可被用户删除
                .setPriority(Notification.PRIORITY_LOW)
                .setWhen(System.currentTimeMillis());

        return builder.build();
    }

    /**
     * 更新通知内容
     */
    private void updateNotification(String message) {
        try {
            Notification notification = buildNotification("定位服务运行中", message);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "更新通知失败", e);
        }
    }

    /**
     * 初始化 FusedLocationProviderClient
     */
    private void initFusedLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult != null && locationResult.getLastLocation() != null) {
                    Location location = locationResult.getLastLocation();
                    // 关键修复：不再只接受GPS provider，按精度判断
                    if (isValidLocation(location)) {
                        lastKnownLocation = location;
                        Log.d(TAG, "位置更新: " + location.getLatitude() + ", " +
                                location.getLongitude() + " 精度:" + location.getAccuracy() + "m 来源:" + location.getProvider());
                    }
                }
            }
        };

        requestLocationUpdates();
    }

    /**
     * 判断位置是否有效：按精度判断，而不是按 provider 名称判断
     */
    private boolean isValidLocation(Location location) {
        if (location == null) return false;
        // 精度小于200米即认为有效（GPS通常<50m，网络定位通常50-200m）
        float accuracy = location.getAccuracy();
        return accuracy > 0 && accuracy < 200;
    }

    /**
     * 请求位置更新（最高精度配置）
     */
    private void requestLocationUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "缺少定位权限，无法请求位置更新");
                return;
            }
        }

        // 创建最高精度的位置请求
        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                1000  // 更新间隔1秒
        )
        .setWaitForAccurateLocation(true)
        .setMinUpdateIntervalMillis(500)
        .setMaxUpdateDelayMillis(2000)
        .build();

        try {
            LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                    .addLocationRequest(locationRequest);
            builder.setAlwaysShow(true);

            LocationServices.getSettingsClient(this)
                    .checkLocationSettings(builder.build())
                    .addOnSuccessListener(locationSettingsResponse -> {
                        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
                        Log.d(TAG, "位置更新请求已发送");
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "位置设置检查失败", e));
        } catch (Exception e) {
            Log.e(TAG, "请求位置更新失败", e);
        }
    }

    /**
     * 初始化加速度传感器
     */
    private void initAccelerometer() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                sensorManager.registerListener(sensorEventListener, accelerometer, SensorManager.SENSOR_DELAY_GAME);
                Log.d(TAG, "加速度传感器已初始化");
            }
        }
    }

    private SensorEventListener sensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                lastAcceleration[0] = event.values[0];
                lastAcceleration[1] = event.values[1];
                lastAcceleration[2] = event.values[2];
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    /**
     * 获取最后已知位置（改进版：优先使用高精度，接受所有有效来源）
     */
    private Location getLastKnownLocation() {
        // 优先返回 FusedLocationProviderClient 获取的位置（如果有效）
        if (lastKnownLocation != null && isValidLocation(lastKnownLocation)) {
            return lastKnownLocation;
        }

        // 直接使用 LocationManager 获取 GPS 位置
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location bestLocation = null;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    // 同时检查GPS和网络定位，取精度更高的
                    Location gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    Location networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

                    if (gpsLocation != null && isValidLocation(gpsLocation)) {
                        bestLocation = gpsLocation;
                    }
                    if (networkLocation != null && isValidLocation(networkLocation)) {
                        if (bestLocation == null || networkLocation.getAccuracy() < bestLocation.getAccuracy()) {
                            bestLocation = networkLocation;
                        }
                    }

                    // 最后尝试 FusedLocation
                    if (bestLocation == null) {
                        Location fusedLocation = fusedLocationClient.getLastLocation().getResult();
                        if (fusedLocation != null && isValidLocation(fusedLocation)) {
                            bestLocation = fusedLocation;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取最后已知位置失败", e);
        }

        return bestLocation != null ? bestLocation : lastKnownLocation;
    }

    /**
     * 获取位置来源描述
     */
    private String getLocationSource(Location location) {
        if (location == null) return "未知";
        String provider = location.getProvider();
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            return "GPS";
        } else if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            return "基站";
        } else if (LocationManager.PASSIVE_PROVIDER.equals(provider)) {
            return "被动";
        } else {
            return "融合(" + provider + ")";
        }
    }

    /**
     * 启动自动上传任务（整点上传）
     */
    private void startAutoUploadTask() {
        autoUploadHandler = new Handler();

        // 每秒检查是否到达整点
        autoUploadHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Calendar calendar = Calendar.getInstance();
                int second = calendar.get(Calendar.SECOND);

                if (second == 0) {
                    Log.d(TAG, "检测到整点，执行自动上传");
                    performAutoUpload();
                }

                autoUploadHandler.postDelayed(this, 1000);
            }
        }, 1000);

        Log.d(TAG, "自动上传任务已启动");
    }

    /**
     * 执行自动上传
     */
    private void performAutoUpload() {
        new Thread(() -> {
            try {
                // 读取配置
                SharedPreferences prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
                String dataApiUrl = prefs.getString("data_api", "").trim();
                String uuid = prefs.getString("uuid", "").trim();
                boolean autoUploadEnabled = prefs.getBoolean("auto_upload_enabled", false);

                if (!autoUploadEnabled) {
                    Log.d(TAG, "自动上传已关闭，跳过");
                    return;
                }

                if (dataApiUrl.isEmpty() || uuid.isEmpty()) {
                    Log.w(TAG, "Data API 或 UUID 未设置");
                    return;
                }

                if (!isNetworkAvailable()) {
                    Log.w(TAG, "网络不可用");
                    return;
                }

                // 构建数据
                UploadData data = buildUploadData(uuid);
                String json = gson.toJson(data);
                Log.d(TAG, "上传 JSON: " + json);

                // 发送请求
                RequestBody body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
                Request request = new Request.Builder()
                        .url(dataApiUrl)
                        .post(body)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "上传成功，状态码: " + response.code());
                        updateNotification("已上传位置: " + data.coordinates);
                        saveLastUploadTime();
                    } else {
                        Log.e(TAG, "上传失败，状态码: " + response.code());
                        updateNotification("上传失败，状态码: " + response.code());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "自动上传异常", e);
                updateNotification("上传异常: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 构建上传数据对象
     */
    private UploadData buildUploadData(String uuid) {
        UploadData data = new UploadData();
        data.uuid = uuid;
        data.deviceLocalTime = getCurrentTimestamp();

        // 获取位置
        Location location = getLastKnownLocation();
        if (location != null) {
            data.coordinates = location.getLongitude() + "," + location.getLatitude();
            data.locationAccuracy = location.getAccuracy();
            data.altitude = location.getAltitude();
            data.speed = location.getSpeed();
            data.locationSource = getLocationSource(location);
        } else {
            data.coordinates = "";
            data.locationAccuracy = 0;
            data.altitude = 0;
            data.speed = 0;
            data.locationSource = "未获取到位置";
        }

        // 获取基站信息
        getCellInfo(data);
        // 获取Wi-Fi信息
        getWifiInfo(data);

        // 获取电量
        BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        data.batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        int status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
        data.isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);

        // GPS状态
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        data.isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);

        // 网络类型
        data.networkType = getNetworkType();

        // 气压（返回标准大气压）
        data.pressure = 1013.25;

        // 加速度
        data.acceleration = String.format(Locale.getDefault(), "%.2f,%.2f,%.2f",
                lastAcceleration[0], lastAcceleration[1], lastAcceleration[2]);

        // 设备温度
        data.deviceTemperature = 37.0;

        return data;
    }

    /**
     * 获取基站信息
     */
    private void getCellInfo(UploadData data) {
        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    List<CellInfo> cellInfoList = telephonyManager.getAllCellInfo();
                    if (cellInfoList != null && !cellInfoList.isEmpty()) {
                        for (CellInfo cellInfo : cellInfoList) {
                            if (cellInfo instanceof CellInfoGsm) {
                                CellInfoGsm cellInfoGsm = (CellInfoGsm) cellInfo;
                                CellSignalStrengthGsm signalStrength = cellInfoGsm.getCellSignalStrength();
                                data.cellSignalStrength = signalStrength.getDbm();
                                try {
                                    CellIdentityGsm cellIdentity = cellInfoGsm.getCellIdentity();
                                    data.mcc = cellIdentity.getMcc();
                                    data.mnc = cellIdentity.getMnc();
                                    data.lac = cellIdentity.getLac();
                                    data.cid = cellIdentity.getCid();
                                    break;
                                } catch (Exception e) {
                                    Log.e(TAG, "获取GSM基站信息失败", e);
                                }
                            } else if (cellInfo instanceof CellInfoLte) {
                                CellInfoLte cellInfoLte = (CellInfoLte) cellInfo;
                                CellSignalStrengthLte signalStrength = cellInfoLte.getCellSignalStrength();
                                data.cellSignalStrength = signalStrength.getDbm();
                                try {
                                    CellIdentityLte cellIdentity = cellInfoLte.getCellIdentity();
                                    data.mcc = cellIdentity.getMcc();
                                    data.mnc = cellIdentity.getMnc();
                                    data.lac = cellIdentity.getTac();
                                    data.cid = cellIdentity.getCi();
                                    break;
                                } catch (Exception e) {
                                    Log.e(TAG, "获取LTE基站信息失败", e);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "获取基站信息失败", e);
                }
            }
        }
    }

    /**
     * 获取Wi-Fi信息
     */
    private void getWifiInfo(UploadData data) {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo != null) {
                data.wifiSsid = wifiInfo.getSSID();
                data.wifiBssid = wifiInfo.getBSSID();
                data.wifiSignalStrength = wifiInfo.getRssi();
            }
        }
    }

    /**
     * 获取网络类型
     */
    private String getNetworkType() {
        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        int networkType = telephonyManager.getNetworkType();
        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "2G";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "3G";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "4G";
            case TelephonyManager.NETWORK_TYPE_NR:
                return "5G";
            default:
                return "未知";
        }
    }

    /**
     * 获取当前时间戳
     */
    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault());
        return sdf.format(new Date());
    }

    /**
     * 检查网络是否可用
     */
    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    /**
     * 保存上传时间
     */
    private void saveLastUploadTime() {
        SharedPreferences prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong("last_upload_time", System.currentTimeMillis());
        editor.apply();
    }

    /**
     * 上传数据类
     */
    public static class UploadData {
        @SerializedName("UUID")
        public String uuid;
        @SerializedName("设备本地时间")
        public String deviceLocalTime;
        @SerializedName("经纬度(东经,北纬)")
        public String coordinates;
        @SerializedName("定位精度")
        public double locationAccuracy;
        @SerializedName("定位来源")
        public String locationSource;
        @SerializedName("MCC")
        public int mcc;
        @SerializedName("MNC")
        public int mnc;
        @SerializedName("LAC")
        public int lac;
        @SerializedName("CID")
        public int cid;
        @SerializedName("基站信号强度")
        public int cellSignalStrength;
        @SerializedName("Wi-Fi 名称 (SSID)")
        public String wifiSsid;
        @SerializedName("Wi-Fi MAC 地址 (BSSID)")
        public String wifiBssid;
        @SerializedName("Wi-Fi 信号强度")
        public int wifiSignalStrength;
        @SerializedName("剩余电量百分比")
        public int batteryLevel;
        @SerializedName("是否充电中")
        public boolean isCharging;
        @SerializedName("GPS是否开启")
        public boolean isGpsEnabled;
        @SerializedName("网络类型")
        public String networkType;
        @SerializedName("海拔高度")
        public double altitude;
        @SerializedName("气压")
        public double pressure;
        @SerializedName("速度")
        public double speed;
        @SerializedName("加速度(X,Y,Z)")
        public String acceleration;
        @SerializedName("设备温度")
        public double deviceTemperature;
    }

    /**
     * 检查服务是否正在运行
     */
    public static boolean isRunning() {
        return isServiceRunning;
    }
}
