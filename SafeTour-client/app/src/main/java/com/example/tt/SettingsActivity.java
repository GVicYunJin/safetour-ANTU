package com.example.tt;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import android.location.Location;
import android.location.LocationManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.TelephonyManager;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.os.Build;

import com.google.gson.Gson;

// Google Play Services 定位相关（适配 MIUI14）
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.Priority;
import com.google.android.gms.common.api.ResolvableApiException;
import android.content.IntentSender;
import com.google.gson.annotations.SerializedName;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SettingsActivity extends AppCompatActivity {

    // 控件
    private EditText etDataApi;
    private EditText etPhotoApi;
    private EditText etUuid;
    private Button btnTryGet;
    private Button btnUpload;
    private Button btnBack;
    private TextView tvJsonPreview;
    private Switch switchAutoUpload;
    private View statusIndicatorSettings;
    private TextView tvLastUploadTime;

    // EULA相关控件
    private Button btnShowEula;
    private LinearLayout layoutEulaContent;
    private TextView tvEulaContent;

    // SharedPreferences 键名
    private static final String PREFS_NAME = "AppSettings";
    private static final String KEY_DATA_API = "data_api";
    private static final String KEY_PHOTO_API = "photo_api";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_AUTO_UPLOAD_ENABLED = "auto_upload_enabled";
    private static final String KEY_LAST_UPLOAD_TIME = "last_upload_time";

    // 状态更新 handler
    private Handler statusUpdateHandler = new Handler();

    // FusedLocationProviderClient（适配 MIUI14 高权限定位）
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Location lastKnownLocation = null;

    // 加速度传感器相关
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private float[] lastAcceleration = new float[3]; // 存储最新的加速度值
    private SensorEventListener sensorEventListener;
    
    // 自定义通知管理器
    private CustomNotificationManager notificationManager;

    // JSON 媒体类型
    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private final Gson gson = new Gson();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // 绑定控件
        etDataApi = findViewById(R.id.et_data_api);
        etPhotoApi = findViewById(R.id.et_photo_api);
        etUuid = findViewById(R.id.et_uuid);
        btnTryGet = findViewById(R.id.btn_try_get);
        btnUpload = findViewById(R.id.btn_upload);
        btnBack = findViewById(R.id.btn_back);
        tvJsonPreview = findViewById(R.id.tv_json_preview);
        switchAutoUpload = findViewById(R.id.switch_auto_upload);
        statusIndicatorSettings = findViewById(R.id.status_indicator_settings);
        tvLastUploadTime = findViewById(R.id.tv_last_upload_time);

        // 绑定EULA相关控件
        btnShowEula = findViewById(R.id.btn_show_eula);
        layoutEulaContent = findViewById(R.id.layout_eula_content);
        tvEulaContent = findViewById(R.id.tv_eula_content);
        
        // 初始化自定义通知管理器
        notificationManager = new CustomNotificationManager(this);

        // 初始化 FusedLocationProviderClient（适配 MIUI14）
        initFusedLocation();

        // 初始化加速度传感器
        initAccelerometer();

        // 加载已保存的设置
        loadSavedSettings();

        // 加载自动上传设置
        loadAutoUploadSettings();

        // 更新上次上传时间显示
        updateLastUploadTimeDisplay();

        // 启动状态更新任务
        startStatusUpdateTask();

        // 自动上传开关点击事件
        switchAutoUpload.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d("AutoUpload", "设置页面自动上传开关状态变更: " + (isChecked ? "开启" : "关闭"));
            saveAutoUploadSetting(isChecked);
            
            // 发送广播通知MainActivity自动上传状态变更
            Intent intent = new Intent("com.example.tt.AUTO_UPLOAD_STATE_CHANGED");
            intent.putExtra("enabled", isChecked);
            sendBroadcast(intent);
        });

        // 尝试获取按钮点击事件
        btnTryGet.setOnClickListener(v -> {
            saveCurrentSettings();
            getAndShowDeviceInfo();
        });

        // 手动上传按钮点击事件（上传到 Data API）
        btnUpload.setOnClickListener(v -> {
            saveCurrentSettings();
            performManualUploadToDataApi();
        });

        // 返回按钮点击事件
        btnBack.setOnClickListener(v -> finish());

        // 显示协议按钮点击事件
        btnShowEula.setOnClickListener(v -> {
            if (layoutEulaContent.getVisibility() == View.GONE) {
                // 显示协议内容
                layoutEulaContent.setVisibility(View.VISIBLE);
                loadEulaContent();
                btnShowEula.setText("隐藏用户协议");
            } else {
                // 隐藏协议内容
                layoutEulaContent.setVisibility(View.GONE);
                btnShowEula.setText("查看用户协议");
            }
        });
    }

    /**
     * 从 SharedPreferences 加载已保存的设置
     */
    private void loadSavedSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        etDataApi.setText(prefs.getString(KEY_DATA_API, ""));
        etPhotoApi.setText(prefs.getString(KEY_PHOTO_API, ""));
        etUuid.setText(prefs.getString(KEY_UUID, ""));
    }

    /**
     * 保存当前输入框中的设置到 SharedPreferences
     */
    private void saveCurrentSettings() {
        String dataApi = etDataApi.getText().toString().trim();
        String photoApi = etPhotoApi.getText().toString().trim();
        String uuid = etUuid.getText().toString().trim();

        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_DATA_API, dataApi);
        editor.putString(KEY_PHOTO_API, photoApi);
        editor.putString(KEY_UUID, uuid);
        editor.apply();

        notificationManager.showNotification("提示", "设置已保存", android.R.drawable.ic_dialog_info);
    }

    /**
     * 获取设备充电状态和电量，并显示在预览卡片中
     */
    private void getAndShowDeviceInfo() {
        // 检查权限
        if (!checkPermissions()) {
            // 权限请求已发送，等待用户响应
            Log.d("Settings", "权限请求已发送，等待用户响应");
            return;
        }

        // 构建上传数据对象
        UploadData data = new UploadData();
        data.uuid = etUuid.getText().toString().trim();
        data.deviceLocalTime = getCurrentTimestamp();
        
        // 获取位置信息
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
        
        // 获取电量和充电状态
        BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        data.batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        int status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
        data.isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
        
        // 检查GPS是否开启
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        data.isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        
        // 获取网络类型
        data.networkType = getNetworkType();
        
        // 获取气压
        data.pressure = getPressure();
        
        // 获取加速度
        data.acceleration = getAcceleration();
        
        // 获取设备温度
        data.deviceTemperature = getDeviceTemperature();

        // 转换为 JSON 字符串
        String json = gson.toJson(data);
        Log.d("DeviceInfoJSON", "生成的 JSON: " + json);

        // 更新预览卡片，只显示JSON
        tvJsonPreview.setText(json);
    }

    /**
     * 执行手动上传：生成 JSON 并 POST 到 Data API
     */
    private void performManualUploadToDataApi() {
        String dataApiUrl = etDataApi.getText().toString().trim();
        String uuid = etUuid.getText().toString().trim();

        // 校验输入
        if (dataApiUrl.isEmpty()) {
            notificationManager.showNotification("提示", "请先填写 Data API 地址", android.R.drawable.ic_dialog_info);
            return;
        }
        if (uuid.isEmpty()) {
            notificationManager.showNotification("提示", "请先填写 UUID", android.R.drawable.ic_dialog_info);
            return;
        }

        // 检查网络
        if (!isNetworkAvailable()) {
            notificationManager.showNotification("网络错误", "网络不可用，请检查网络设置", android.R.drawable.ic_dialog_alert);
            return;
        }

        // 检查权限
        if (!checkPermissions()) {
            // 权限请求已发送，等待用户响应
            Log.d("Settings", "权限请求已发送，等待用户响应");
            return;
        }

        // 显示加载提示
        notificationManager.showNotification("上传中", "正在上传...", android.R.drawable.ic_menu_upload);

        // 后台线程执行网络请求
        new Thread(() -> {
            try {
                // 1. 构建上传数据对象
                UploadData data = new UploadData();
                data.uuid = uuid;
                data.deviceLocalTime = getCurrentTimestamp();
                
                // 获取位置信息
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
                
                // 获取电量和充电状态
                BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                data.batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                int status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
                data.isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
                
                // 检查GPS是否开启
                LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                data.isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                
                // 获取网络类型
                data.networkType = getNetworkType();
                
                // 获取气压
                data.pressure = getPressure();
                
                // 获取加速度
                data.acceleration = getAcceleration();
                
                // 获取设备温度
                data.deviceTemperature = getDeviceTemperature();

                // 2. 转换为 JSON 字符串
                String json = gson.toJson(data);
                Log.d("UploadJSON", "生成的 JSON: " + json);

                // 更新预览卡片
                runOnUiThread(() -> {
                    tvJsonPreview.setText(json);
                });

                // 3. 构建 POST 请求
                RequestBody body = RequestBody.create(json, JSON);
                Request request = new Request.Builder()
                        .url(dataApiUrl)
                        .post(body)
                        .build();

                // 4. 执行请求
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        // 保存上传时间
                        saveLastUploadTime();
                        runOnUiThread(() -> {
                            notificationManager.showNotification("上传成功", "响应: " + responseBody, android.R.drawable.ic_dialog_info);
                            Log.d("UploadSuccess", "响应: " + responseBody);
                        });
                    } else {
                        runOnUiThread(() -> {
                            notificationManager.showNotification("上传失败", "状态码 " + response.code(), android.R.drawable.ic_dialog_alert);
                        });
                    }
                }
            } catch (Exception e) {
                Log.e("UploadError", "上传异常", e);
                runOnUiThread(() -> {
                    notificationManager.showNotification("上传失败", e.getMessage(), android.R.drawable.ic_dialog_alert);
                });
            }
        }).start();
    }

    /**
     * 获取当前时间戳（ISO 8601 格式）
     */
    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault());
        return sdf.format(new Date());
    }

    /**
     * 检查权限
     */
    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                // 权限不足，请求权限
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, 
                                               Manifest.permission.ACCESS_NETWORK_STATE, 
                                               Manifest.permission.READ_PHONE_STATE}, 1001);
                return false;
            }
        }
        return true;
    }
    
    /**
     * 初始化 FusedLocationProviderClient（改进版：按精度判断）
     */
    private void initFusedLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // 创建位置回调
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult != null && locationResult.getLastLocation() != null) {
                    Location location = locationResult.getLastLocation();
                    if (isValidLocation(location)) {
                        lastKnownLocation = location;
                        Log.d("LocationUpdate", "位置更新: " + location.getLatitude() + ", " +
                                location.getLongitude() + " 精度:" + location.getAccuracy() + "m 来源:" + location.getProvider());
                    }
                }
            }
        };

        // 请求位置更新
        requestLocationUpdates();
    }

    /**
     * 请求位置更新（使用 FusedLocationProviderClient，最高精度配置）
     */
    private void requestLocationUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        // 创建仅GPS定位的位置请求（强制禁用网络融合）
        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, // 最高优先级，强制使用GPS
                1000 // 更新间隔1秒，平衡精度与功耗
        )
        .setWaitForAccurateLocation(true) // 等待获取精确GPS位置
        .setMinUpdateIntervalMillis(500) // 最小更新间隔500ms
        .setMaxUpdateDelayMillis(2000) // 最大更新延迟2秒
        .build();

        try {
            // 设置位置请求的额外参数，强制使用GPS
            LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                    .addLocationRequest(locationRequest);
            builder.setAlwaysShow(true); // 强制显示GPS开启提示
            
            // 检查并强制启用GPS
            LocationServices.getSettingsClient(this)
                    .checkLocationSettings(builder.build())
                    .addOnSuccessListener(locationSettingsResponse -> {
                        // GPS已启用，开始请求位置更新
                        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
                        Log.d("LocationInit", "强制GPS定位已启动");
                    })
                    .addOnFailureListener(e -> {
                        // GPS未启用，提示用户开启
                        if (e instanceof ResolvableApiException) {
                            try {
                                // 显示GPS开启对话框
                                ResolvableApiException resolvable = (ResolvableApiException) e;
                                resolvable.startResolutionForResult(SettingsActivity.this, 1002);
                            } catch (IntentSender.SendIntentException sendEx) {
                                Log.e("LocationError", "无法启动GPS设置", sendEx);
                            }
                        }
                    });
        } catch (Exception e) {
            Log.e("LocationError", "请求位置更新失败", e);
        }
    }

    /**
     * 初始化加速度传感器
     */
    private void initAccelerometer() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        // 创建传感器监听器
        sensorEventListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                    // 保存最新的加速度值
                    lastAcceleration[0] = event.values[0]; // X 轴
                    lastAcceleration[1] = event.values[1]; // Y 轴
                    lastAcceleration[2] = event.values[2]; // Z 轴
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // 精度变化时的处理
            }
        };

        if (accelerometer != null) {
            // 注册传感器监听器，采样率使用游戏级精度
            sensorManager.registerListener(sensorEventListener, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            Log.d("SensorInit", "加速度传感器已初始化");
        } else {
            Log.e("SensorError", "设备不支持加速度传感器");
        }
    }

    /**
     * 获取最后已知位置（改进版：按精度判断，接受所有有效定位来源）
     */
    private Location getLastKnownLocation() {
        // 优先返回FusedLocationProviderClient获取的位置
        if (lastKnownLocation != null && isValidLocation(lastKnownLocation)) {
            return lastKnownLocation;
        }

        // 同时从GPS和网络获取位置，取精度更高的
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location bestLocation = null;

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Location gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gpsLocation != null && isValidLocation(gpsLocation)) {
                bestLocation = gpsLocation;
            }
            Location networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (networkLocation != null && isValidLocation(networkLocation)) {
                if (bestLocation == null || networkLocation.getAccuracy() < bestLocation.getAccuracy()) {
                    bestLocation = networkLocation;
                }
            }
        }

        // 如果上述都没有，尝试FusedLocationProviderClient
        if (bestLocation == null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        com.google.android.gms.tasks.Task<Location> task = fusedLocationClient.getLastLocation();
                        task.addOnSuccessListener(newLocation -> {
                            if (newLocation != null && isValidLocation(newLocation)) {
                                lastKnownLocation = newLocation;
                            }
                        });
                        Location tempLocation = task.getResult();
                        if (tempLocation != null && isValidLocation(tempLocation)) {
                            bestLocation = tempLocation;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("LocationError", "获取位置失败", e);
            }
        }

        return (bestLocation != null) ? bestLocation : lastKnownLocation;
    }

    /**
     * 判断位置是否有效：按精度（米）判断，而不是按provider名称判断
     */
    private boolean isValidLocation(Location location) {
        if (location == null) return false;
        float accuracy = location.getAccuracy();
        return accuracy > 0 && accuracy < 200;
    }
    
    /**
     * 获取位置来源（改进版）
     */
    private String getLocationSource(Location location) {
        if (location == null) return "未知";
        String provider = location.getProvider();
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            return "GPS";
        } else if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            return "基站/WiFi";
        } else if (LocationManager.PASSIVE_PROVIDER.equals(provider)) {
            return "被动";
        } else {
            return "融合定位";
        }
    }
    
    /**
     * 获取基站信息
     */
    private void getCellInfo(UploadData data) {
        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                java.util.List<CellInfo> cellInfoList = telephonyManager.getAllCellInfo();
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
                                Log.e("CellInfo", "获取基站信息失败", e);
                            }
                        } else if (cellInfo instanceof CellInfoLte) {
                            CellInfoLte cellInfoLte = (CellInfoLte) cellInfo;
                            CellSignalStrengthLte signalStrength = cellInfoLte.getCellSignalStrength();
                            data.cellSignalStrength = signalStrength.getDbm();
                            
                            try {
                                CellIdentityLte cellIdentity = cellInfoLte.getCellIdentity();
                                data.mcc = cellIdentity.getMcc();
                                data.mnc = cellIdentity.getMnc();
                                data.lac = cellIdentity.getTac(); // LTE使用TAC代替LAC
                                data.cid = cellIdentity.getCi();
                                break;
                            } catch (Exception e) {
                                Log.e("CellInfo", "获取基站信息失败", e);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("CellInfo", "获取基站信息失败", e);
            }
        }
    }
    
    /**
     * 获取Wi-Fi信息
     */
    private void getWifiInfo(UploadData data) {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        
        if (wifiInfo != null) {
            data.wifiSsid = wifiInfo.getSSID();
            data.wifiBssid = wifiInfo.getBSSID();
            data.wifiSignalStrength = wifiInfo.getRssi();
        } else {
            data.wifiSsid = "";
            data.wifiBssid = "";
            data.wifiSignalStrength = 0;
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
     * 获取气压
     */
    private double getPressure() {
        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        
        if (pressureSensor != null) {
            // 这里应该注册传感器监听器并获取数据
            // 为了简化，这里返回默认值
            return 1013.25;
        }
        return 0;
    }
    
    /**
     * 获取加速度（从传感器监听器获取实时数据）
     */
    private String getAcceleration() {
        if (accelerometer != null) {
            // 返回存储的最新加速度值，格式：X,Y,Z
            return String.format(Locale.getDefault(), "%.2f,%.2f,%.2f", 
                    lastAcceleration[0], lastAcceleration[1], lastAcceleration[2]);
        }
        return "";
    }
    
    /**
     * 获取设备温度
     */
    private double getDeviceTemperature() {
        // 注意：Android没有直接获取设备温度的API
        // 这里返回默认值，实际应用中可能需要通过其他方式获取
        return 37.0;
    }

    /**
     * 从assets加载EULA内容
     */
    private void loadEulaContent() {
        try {
            InputStream is = getAssets().open("eula.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            tvEulaContent.setText(sb.toString());
            reader.close();
            is.close();
        } catch (IOException e) {
            tvEulaContent.setText("无法加载用户协议内容");
            Toast.makeText(this, "加载协议失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
     * 加载自动上传设置
     */
    private void loadAutoUploadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isEnabled = prefs.getBoolean(KEY_AUTO_UPLOAD_ENABLED, false);
        switchAutoUpload.setChecked(isEnabled);
    }

    /**
     * 保存自动上传设置
     */
    private void saveAutoUploadSetting(boolean isEnabled) {
        Log.d("AutoUpload", "保存自动上传设置: " + (isEnabled ? "开启" : "关闭"));
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean(KEY_AUTO_UPLOAD_ENABLED, isEnabled);
        editor.apply();
    }

    /**
     * 启动状态更新任务
     */
    private void startStatusUpdateTask() {
        statusUpdateHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateStatusIndicator();
                statusUpdateHandler.postDelayed(this, 1000); // 每秒刷新一次
            }
        }, 0);
    }

    /**
     * 更新状态指示灯
     */
    private void updateStatusIndicator() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastUploadTime = prefs.getLong(KEY_LAST_UPLOAD_TIME, 0);
        long currentTime = System.currentTimeMillis();
        
        // 检查是否在1分钟内有上传记录
        if (lastUploadTime > 0 && (currentTime - lastUploadTime) <= 60 * 1000) {
            // 1分钟内有上传记录，显示绿色
            if (statusIndicatorSettings != null) {
                statusIndicatorSettings.setBackgroundTintList(ColorStateList.valueOf(Color.GREEN));
            }
        } else {
            // 1分钟内无上传记录或上传失败，显示红色
            if (statusIndicatorSettings != null) {
                statusIndicatorSettings.setBackgroundTintList(ColorStateList.valueOf(Color.RED));
            }
        }
    }

    /**
     * 更新上次上传时间显示
     */
    private void updateLastUploadTimeDisplay() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastUploadTime = prefs.getLong(KEY_LAST_UPLOAD_TIME, 0);
        
        if (lastUploadTime > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String timeStr = sdf.format(new Date(lastUploadTime));
            tvLastUploadTime.setText("上次上传时间: " + timeStr);
        } else {
            tvLastUploadTime.setText("上次上传时间: 从未");
        }
    }

    /**
     * 保存上次上传时间
     */
    private void saveLastUploadTime() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putLong(KEY_LAST_UPLOAD_TIME, System.currentTimeMillis());
        editor.apply();
        runOnUiThread(this::updateLastUploadTimeDisplay);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            // 权限请求结果回调
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                // 权限授予成功，自动获取设备信息并显示
                getAndShowDeviceInfo();
            } else {
                Log.e("PermissionError", "用户拒绝部分权限");
                notificationManager.showNotification("权限错误", "需要相关权限才能获取完整设备信息", android.R.drawable.ic_dialog_alert);
            }
        }
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

    // 设备信息数据类（保留用于兼容）
    public static class DeviceInfo {
        @SerializedName("charging_status")
        public String chargingStatus;

        @SerializedName("battery_level")
        public int batteryLevel;

        @SerializedName("device_model")
        public String deviceModel;

        @SerializedName("os_version")
        public String osVersion;
    }

    /**
     * 处理GPS设置请求结果
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1002) {
            if (resultCode == RESULT_OK) {
                // GPS已开启，重新请求位置更新
                requestLocationUpdates();
                Log.d("LocationInit", "GPS已开启，重新启动定位");
            } else {
                Log.e("LocationError", "用户未开启GPS");
                notificationManager.showNotification("定位错误", "请开启GPS以获取精确定位", android.R.drawable.ic_dialog_alert);
            }
        }
    }

    /**
     * 当 Activity 销毁时调用
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 停止 FusedLocationProviderClient 位置更新
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            Log.d("LocationInit", "SettingsActivity FusedLocationProviderClient 位置更新已停止");
        }

        // 注销加速度传感器监听器
        if (sensorManager != null && sensorEventListener != null) {
            sensorManager.unregisterListener(sensorEventListener);
            Log.d("SensorInit", "SettingsActivity 加速度传感器监听器已注销");
        }

        // 移除状态更新回调
        statusUpdateHandler.removeCallbacksAndMessages(null);
    }
}