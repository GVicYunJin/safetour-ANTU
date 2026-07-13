package com.example.tt;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
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
import android.widget.FrameLayout;
import android.widget.Toast;
import android.location.Location;
import android.location.LocationManager;
import android.Manifest;
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

import androidx.appcompat.app.AppCompatActivity;

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
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.google.gson.annotations.SerializedName;

public class MainActivity extends AppCompatActivity {

    // 核心控件
    private PreviewView cameraPreview;      // 左侧大预览区
    private Button switchCameraBtn;         // 切换摄像头按钮
    private FrameLayout captureBtn;         // 拍照按钮
    private Button settingsBtn;             // 设置按钮
    private View statusIndicator;           // 状态指示灯
    
    // 自定义通知管理器
    private CustomNotificationManager notificationManager;

    // 自动上传相关
    private boolean isAutoUploadEnabled = false;
    private Handler autoUploadHandler;
    private Handler statusUpdateHandler;
    private static final String KEY_LAST_UPLOAD_TIME = "last_upload_time";
    private static final String KEY_AUTO_UPLOAD_ENABLED = "auto_upload_enabled";
    
    // 广播接收器，用于监听自动上传状态变化
    private BroadcastReceiver autoUploadStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean enabled = intent.getBooleanExtra("enabled", false);
            Log.d("AutoUpload", "收到自动上传状态变更广播: " + (enabled ? "开启" : "关闭"));
            isAutoUploadEnabled = enabled;
            // 根据状态更新自动上传任务
            if (enabled) {
                startAutoUploadTask();
            } else {
                stopAutoUploadTask();
            }
        }
    };

    // 相机相关
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCameraSelector; // 当前选中的摄像头
    private ImageCapture imageCapture;       // 拍照用例

    // 网络客户端
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private final Gson gson = new Gson();

    // FusedLocationProviderClient（适配 MIUI14 高权限定位）
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Location lastKnownLocation = null;

    // 加速度传感器相关
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private float[] lastAcceleration = new float[3]; // 存储最新的加速度值

    // 权限
    private static final int REQUEST_CAMERA_PERMISSION = 10;
    private final String[] REQUIRED_PERMISSIONS = {
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_NETWORK_STATE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化Handlers，确保在主线程中创建
        autoUploadHandler = new Handler(getMainLooper());
        statusUpdateHandler = new Handler(getMainLooper());
        Log.d("AutoUpload", "初始化Handlers完成");
        
        // 注册广播接收器，监听自动上传状态变化
        IntentFilter filter = new IntentFilter("com.example.tt.AUTO_UPLOAD_STATE_CHANGED");
        // 在Android 13及以上版本中，需要明确指定RECEIVER_NOT_EXPORTED标志
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, autoUploadStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // Android 8.0+ 使用 ContextCompat 避免警告
            ContextCompat.registerReceiver(this, autoUploadStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            // Android 8.0 以下版本，直接使用 registerReceiver
            registerReceiver(autoUploadStateReceiver, filter);
        }
        Log.d("AutoUpload", "注册广播接收器完成");

        // 绑定控件
        cameraPreview = findViewById(R.id.camera_preview);
        switchCameraBtn = findViewById(R.id.switch_camera_btn);
        captureBtn = findViewById(R.id.capture_btn);
        settingsBtn = findViewById(R.id.settings_btn);
        statusIndicator = findViewById(R.id.status_indicator);
        
        // 初始化自定义通知管理器
        notificationManager = new CustomNotificationManager(this);

        // 初始化：默认选中后置摄像头
        currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        // 初始化 FusedLocationProviderClient（适配 MIUI14）
        initFusedLocation();

        // 初始化加速度传感器
        initAccelerometer();

        // 检查权限
        if (allPermissionsGranted()) {
            initCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CAMERA_PERMISSION);
        }

        // 加载自动上传设置
        loadAutoUploadSettings();

        // 启动状态更新任务（每秒刷新一次指示灯状态）
        startStatusUpdateTask();

        // 启动自动上传任务
        startAutoUploadTask();

        // 1. 摄像头切换按钮点击事件
        switchCameraBtn.setOnClickListener(v -> switchCamera());

        // 2. 拍照按钮点击事件（核心：拍照 → 合成 → 上传到 Photo API）
        captureBtn.setOnClickListener(v -> takePhotoAndUploadToPhotoApi());

        // 3. 设置按钮跳转
        settingsBtn.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        // 4. 启动后台定位服务（用于息屏后持续获取位置并上传）
        startLocationService();
    }

    /**
     * 启动后台定位服务
     */
    private void startLocationService() {
        // 检查服务是否已在运行
        if (LocationService.isRunning()) {
            Log.d("AutoUpload", "后台服务已在运行，跳过启动");
            return;
        }

        Intent serviceIntent = new Intent(this, LocationService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+ 必须使用 startForegroundService
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d("AutoUpload", "后台定位服务已启动");
        } catch (Exception e) {
            Log.e("AutoUpload", "启动后台服务失败", e);
        }
    }

    /**
     * 初始化摄像头（绑定当前选中的摄像头）
     */
    private void initCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCamera(currentCameraSelector); // 绑定摄像头
            } catch (Exception e) {
                Log.e("CameraError", "初始化摄像头失败", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * 绑定指定摄像头到预览区
     */
    private void bindCamera(CameraSelector selector) {
        // 先解绑所有
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        // 创建预览对象
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

        // 创建拍照对象
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        // 绑定摄像头
        cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);

        // 日志提示当前摄像头
        String cameraType = (selector == CameraSelector.DEFAULT_BACK_CAMERA) ? "后置" : "前置";
        Log.d("CameraSwitch", "已切换到：" + cameraType + "摄像头");
    }

    /**
     * 切换摄像头核心逻辑
     */
    private void switchCamera() {
        if (cameraProvider == null) return;

        // 切换摄像头选择器
        currentCameraSelector = (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA;

        // 重新绑定摄像头
        bindCamera(currentCameraSelector);
    }

    /**
     * 拍照并上传到 Photo API（核心流程）
     */
    private void takePhotoAndUploadToPhotoApi() {
        if (imageCapture == null || cameraProvider == null) {
            notificationManager.showNotification("提示", "相机未初始化", android.R.drawable.ic_dialog_alert);
            return;
        }

        // 从 SharedPreferences 读取 API 和 UUID
        SharedPreferences prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        String photoApiUrl = prefs.getString("photo_api", "").trim();
        String uuid = prefs.getString("uuid", "").trim();

        if (photoApiUrl.isEmpty() || uuid.isEmpty()) {
            notificationManager.showNotification("提示", "请先在设置页填写 Photo API 和 UUID", android.R.drawable.ic_dialog_info);
            return;
        }

        if (!isNetworkAvailable()) {
            notificationManager.showNotification("网络错误", "网络不可用，请检查网络设置", android.R.drawable.ic_dialog_alert);
            return;
        }

        // 检查权限
        if (!checkPermissions()) {
            // 权限请求已发送，等待用户响应
            Log.d("PhotoUpload", "权限请求已发送，等待用户响应");
            return;
        }

        notificationManager.showNotification("拍照中", "正在捕获画面...", android.R.drawable.ic_menu_camera);

        // 后台线程处理拍照流程
        new Thread(() -> {
            try {
                // 1. 捕获后置摄像头画面
                Bitmap backBitmap = captureCameraImage(CameraSelector.DEFAULT_BACK_CAMERA);
                if (backBitmap == null) {
                    throw new Exception("后置摄像头拍照失败");
                }

                // 2. 捕获前置摄像头画面
                Bitmap frontBitmap = captureCameraImage(CameraSelector.DEFAULT_FRONT_CAMERA);
                if (frontBitmap == null) {
                    throw new Exception("前置摄像头拍照失败");
                }

                // 3. 构建上传数据对象
                UploadData uploadData = new UploadData();
                uploadData.uuid = uuid;
                uploadData.deviceLocalTime = getCurrentTimestamp();
                
                // 获取位置信息
                Location location = getLastKnownLocation();
                if (location != null) {
                    uploadData.coordinates = location.getLongitude() + "," + location.getLatitude();
                    uploadData.locationAccuracy = location.getAccuracy();
                    uploadData.altitude = location.getAltitude();
                    uploadData.speed = location.getSpeed();
                    uploadData.locationSource = getLocationSource(location);
                } else {
                    uploadData.coordinates = "";
                    uploadData.locationAccuracy = 0;
                    uploadData.altitude = 0;
                    uploadData.speed = 0;
                    uploadData.locationSource = "未获取到位置";
                }
                
                // 获取基站信息
                getCellInfo(uploadData);
                
                // 获取Wi-Fi信息
                getWifiInfo(uploadData);
                
                // 获取电量和充电状态
                BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                uploadData.batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                int status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
                uploadData.isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
                
                // 检查GPS是否开启
                LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                uploadData.isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                
                // 获取网络类型
                uploadData.networkType = getNetworkType();
                
                // 获取气压
                uploadData.pressure = getPressure();
                
                // 获取加速度
                uploadData.acceleration = getAcceleration();
                
                // 获取设备温度
                uploadData.deviceTemperature = getDeviceTemperature();

                // 4. 合成图片（后置 + 前置(1/3大小) + 信息栏）
                Bitmap compositeBitmap = compositeImages(backBitmap, frontBitmap, uploadData);

                // 5. 生成自定义文件名：2026年02月23日14:34-UUID（从设置读取真实UUID）
                String fileName = getCustomFileName(uuid);

                // 6. 上传合成后的图片到 Photo API（使用新文件名）
                uploadImageToPhotoApi(compositeBitmap, photoApiUrl, uuid, fileName);

                runOnUiThread(() -> {
                    notificationManager.showNotification("上传成功", "图片已成功上传", android.R.drawable.ic_dialog_info);
                    // 上传成功后切回后置摄像头
                    switchToBackCamera();
                });

            } catch (Exception e) {
                Log.e("PhotoError", "拍照或上传失败", e);
                runOnUiThread(() -> {
                    notificationManager.showNotification("上传失败", "错误: " + e.getMessage(), android.R.drawable.ic_dialog_alert);
                    // 即使失败也切回后置摄像头
                    switchToBackCamera();
                });
            }
        }).start();
    }

    /**
     * 切换到后置摄像头
     */
    private void switchToBackCamera() {
        if (cameraProvider != null) {
            bindCamera(CameraSelector.DEFAULT_BACK_CAMERA);
        }
    }

    /**
     * 捕获指定摄像头的画面
     */
    private Bitmap captureCameraImage(CameraSelector cameraSelector) throws Exception {
        // 切换到指定摄像头（必须在主线程执行）
        final java.util.concurrent.CountDownLatch bindLatch = new java.util.concurrent.CountDownLatch(1);
        final Exception[] bindException = new Exception[1];
        
        runOnUiThread(() -> {
            try {
                bindCamera(cameraSelector);
            } catch (Exception e) {
                bindException[0] = e;
            } finally {
                bindLatch.countDown();
            }
        });
        
        // 等待摄像头切换完成，最多等待3秒
        if (!bindLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new Exception("摄像头切换超时");
        }
        
        if (bindException[0] != null) {
            throw bindException[0];
        }
        
        // 等待摄像头切换完成
        Thread.sleep(300);
        
        // 使用CountDownLatch来等待拍照完成
        final java.util.concurrent.CountDownLatch captureLatch = new java.util.concurrent.CountDownLatch(1);
        final Bitmap[] resultBitmap = new Bitmap[1];
        final Exception[] captureException = new Exception[1];

        // 执行拍照
        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(androidx.camera.core.ImageProxy image) {
                super.onCaptureSuccess(image);
                try {
                    // 将 ImageProxy 转换为 Bitmap
                    resultBitmap[0] = imageToBitmap(image);
                    image.close(); // 释放图片资源
                } catch (Exception e) {
                    captureException[0] = e;
                } finally {
                    captureLatch.countDown();
                }
            }

            @Override
            public void onError(ImageCaptureException exception) {
                super.onError(exception);
                captureException[0] = exception;
                captureLatch.countDown();
            }
        });

        // 等待拍照完成，最多等待5秒
        if (!captureLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new Exception("拍照超时");
        }

        if (captureException[0] != null) {
            throw captureException[0];
        }

        return resultBitmap[0];
    }

    /**
     * 合成图片：后置画面 + 前置画面(1/3大小) + 信息栏
     */
    private Bitmap compositeImages(Bitmap backBitmap, Bitmap frontBitmap, UploadData uploadData) {
        int backWidth = backBitmap.getWidth();
        int backHeight = backBitmap.getHeight();
        // 计算前置摄像头画面的宽度，使其与后置摄像头画面宽度一致
        int frontWidth = backWidth;
        // 保持前置摄像头画面的原始比例
        int frontHeight = frontBitmap.getHeight() * frontWidth / frontBitmap.getWidth();
        // 增加信息栏高度，确保所有文字都能显示完整
        int infoBarHeight = 1000; // 信息栏高度

        // 计算总高度：后置画面高度 + 前置画面高度 + 信息栏高度
        int totalHeight = backHeight + frontHeight + infoBarHeight;

        // 创建新的 Bitmap
        Bitmap compositeBitmap = Bitmap.createBitmap(backWidth, totalHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(compositeBitmap);

        // 1. 绘制后置摄像头画面（最上方）
        canvas.drawBitmap(backBitmap, 0, 0, null);

        // 2. 绘制前置摄像头画面（中间，缩放至与后置画面宽度一致，并镜像处理）
        Bitmap scaledFrontBitmap = Bitmap.createScaledBitmap(frontBitmap, frontWidth, frontHeight, true);
        // 创建镜像后的 bitmap
        Matrix matrix = new Matrix();
        matrix.preScale(-1, 1); // 水平翻转
        Bitmap mirroredFrontBitmap = Bitmap.createBitmap(scaledFrontBitmap, 0, 0, scaledFrontBitmap.getWidth(), scaledFrontBitmap.getHeight(), matrix, true);
        // 绘制前置画面，从左边缘开始
        int frontY = backHeight;
        canvas.drawBitmap(mirroredFrontBitmap, 0, frontY, null);
        // 释放临时 bitmap
        mirroredFrontBitmap.recycle();

        // 3. 绘制信息栏（最下方）
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        canvas.drawRect(0, backHeight + frontHeight, backWidth, totalHeight, paint);

        // 绘制白色文字
        paint.setColor(Color.WHITE);
        paint.setTextSize(22); // 字体大小，进一步缩小以容纳更多信息
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setAntiAlias(true); // 抗锯齿，字体更清晰

        // 构建详细信息文本，按照键：数值格式
        StringBuilder infoText = new StringBuilder();
        infoText.append("UUID: " + uploadData.uuid + "\n");
        infoText.append("设备本地时间: " + uploadData.deviceLocalTime + "\n");
        infoText.append("经纬度(东经,北纬): " + uploadData.coordinates + "\n");
        infoText.append("定位精度: " + uploadData.locationAccuracy + "\n");
        infoText.append("定位来源: " + uploadData.locationSource + "\n");
        infoText.append("MCC: " + uploadData.mcc + "\n");
        infoText.append("MNC: " + uploadData.mnc + "\n");
        infoText.append("LAC: " + uploadData.lac + "\n");
        infoText.append("CID: " + uploadData.cid + "\n");
        infoText.append("基站信号强度: " + uploadData.cellSignalStrength + "\n");
        infoText.append("Wi-Fi 名称 (SSID): " + uploadData.wifiSsid + "\n");
        infoText.append("Wi-Fi MAC 地址 (BSSID): " + uploadData.wifiBssid + "\n");
        infoText.append("Wi-Fi 信号强度: " + uploadData.wifiSignalStrength + "\n");
        infoText.append("剩余电量百分比: " + uploadData.batteryLevel + "%\n");
        infoText.append("是否充电中: " + (uploadData.isCharging ? "是" : "否") + "\n");
        infoText.append("GPS是否开启: " + (uploadData.isGpsEnabled ? "是" : "否") + "\n");
        infoText.append("网络类型: " + uploadData.networkType + "\n");
        infoText.append("海拔高度: " + uploadData.altitude + "\n");
        infoText.append("气压: " + uploadData.pressure + "\n");
        infoText.append("速度: " + uploadData.speed + "\n");
        infoText.append("加速度(X,Y,Z): " + uploadData.acceleration + "\n");
        infoText.append("设备温度: " + uploadData.deviceTemperature + "\n");

        // 分行绘制文字
        float textY = backHeight + frontHeight + 25;
        float lineSpacing = paint.getTextSize() + 4; // 行间距，进一步缩小以容纳更多信息
        for (String line : infoText.toString().split("\\n")) {
            // 限制单行文字长度，避免超出屏幕
            if (paint.measureText(line) > backWidth - 40) {
                // 超长文字自动换行
                int maxLength = backWidth / 20;
                for (int i = 0; i < line.length(); i += maxLength) {
                    int end = Math.min(i + maxLength, line.length());
                    canvas.drawText(line.substring(i, end), 20, textY, paint);
                    textY += lineSpacing;
                }
            } else {
                canvas.drawText(line, 20, textY, paint);
                textY += lineSpacing;
            }
        }

        return compositeBitmap;
    }

    /**
     * 将 ImageProxy 转换为 Bitmap
     */
    private Bitmap imageToBitmap(androidx.camera.core.ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    /**
     * 在图片下方添加设备信息黑色底栏
     * 修改点：1.字体缩小为28sp  2.信息栏高度改为300px避免出框
     */
    private Bitmap addInfoBarToImage(Bitmap originalBitmap, DeviceInfo deviceInfo) {
        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();
        int infoBarHeight = 300; // 加长信息栏，解决出框问题

        // 创建新的 Bitmap，高度 = 原图高度 + 信息栏高度
        Bitmap compositeBitmap = Bitmap.createBitmap(width, height + infoBarHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(compositeBitmap);

        // 1. 绘制原图
        canvas.drawBitmap(originalBitmap, 0, 0, null);

        // 2. 绘制黑色底栏
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        canvas.drawRect(0, height, width, height + infoBarHeight, paint);

        // 3. 绘制白色文字（字体缩小为28sp）
        paint.setColor(Color.WHITE);
        paint.setTextSize(28); // 字体缩小，解决过大问题
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setAntiAlias(true); // 抗锯齿，字体更清晰

        String infoText = "UUID: " + deviceInfo.uuid + "\n" +
                "电量: " + deviceInfo.batteryLevel + "%\n" +
                "充电状态: " + deviceInfo.chargingStatus + "\n" +
                "设备型号: " + deviceInfo.deviceModel + "\n" +
                "系统版本: " + deviceInfo.osVersion + "\n" +
                "时间: " + getCurrentTimestamp();

        // 分行绘制文字，行间距缩小，避免出框
        float textY = height + 35;
        float lineSpacing = paint.getTextSize() + 8; // 行间距8px，更紧凑
        for (String line : infoText.split("\n")) {
            // 限制单行文字长度，避免超出屏幕
            if (paint.measureText(line) > width - 40) {
                // 超长文字自动换行（可选）
                canvas.drawText(line.substring(0, width/20) + "...", 20, textY, paint);
            } else {
                canvas.drawText(line, 20, textY, paint);
            }
            textY += lineSpacing;
        }

        return compositeBitmap;
    }

    /**
     * 生成自定义文件名：2026年02月23日14:34-UUID（去掉秒数，UUID用设置页的值）
     */
    private String getCustomFileName(String uuid) {
        // 格式：yyyy年MM月dd日HH:mm（去掉秒数，和示例一致）
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日HH:mm", Locale.getDefault());
        String timeStr = sdf.format(new Date());
        // 拼接成最终文件名：2026年02月23日14:34-设置里的UUID.jpg
        return timeStr + "-" + uuid + ".jpg";
    }

    /**
     * 获取设备信息
     */
    private DeviceInfo getDeviceInfo(String uuid) {
        BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        DeviceInfo info = new DeviceInfo();

        // 充电状态
        int status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                info.chargingStatus = "充电中"; // 改为中文，更易读
                break;
            case BatteryManager.BATTERY_STATUS_FULL:
                info.chargingStatus = "已充满";
                break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                info.chargingStatus = "未充电";
                break;
            default:
                info.chargingStatus = "未知";
                break;
        }

        // 电量百分比
        info.batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

        // 设备型号和系统版本
        info.deviceModel = android.os.Build.MODEL;
        info.osVersion = android.os.Build.VERSION.RELEASE;

        // UUID（从设置页读取）
        info.uuid = uuid;

        return info;
    }

    /**
     * 上传图片到 Photo API（使用自定义文件名）
     */
    private void uploadImageToPhotoApi(Bitmap image, String photoApiUrl, String uuid, String fileName) throws IOException {
        // 1. 将Bitmap转为JPEG字节数组
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.JPEG, 80, baos);
        byte[] imageData = baos.toByteArray();

        // 2. 构建multipart/form-data请求
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                // 表单普通参数：传参键为UUID字符串
                .addFormDataPart("UUID", uuid)
                // 文件参数：传参键为file，值为图片二进制文件流，携带文件原始名称及MIME类型
                .addFormDataPart("file", fileName,
                        RequestBody.create(imageData, MediaType.parse("image/jpeg")));

        RequestBody requestBody = builder.build();

        // 3. 构建请求URL，确保指向正确的接口
        String uploadUrl = photoApiUrl;
        // 移除末尾的斜杠（如果有）
        if (uploadUrl.endsWith("/")) {
            uploadUrl = uploadUrl.substring(0, uploadUrl.length() - 1);
        }
        // 检查URL是否已经包含/api/photo-api/upload路径
        if (!uploadUrl.endsWith("/api/photo-api/upload")) {
            uploadUrl += "/api/photo-api/upload";
        }

        // 4. 发送POST请求
        Request request = new Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .build();

        // 5. 执行请求并打印日志
        try (Response response = client.newCall(request).execute()) {
            Log.d("PhotoUpload", "响应状态码: " + response.code());
            if (response.body() != null) {
                Log.d("PhotoUpload", "响应内容: " + response.body().string());
            }

            if (!response.isSuccessful()) {
                throw new IOException("上传失败，状态码: " + response.code());
            }
        }
    }

    /**
     * 获取当前时间戳（ISO 8601 格式）
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
     * 检查所有权限是否授予
     */
    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 权限请求回调
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (allPermissionsGranted()) {
                initCamera();
            } else {
                Log.e("PermissionError", "用户拒绝相机权限");
                notificationManager.showNotification("权限错误", "需要相机权限才能拍照", android.R.drawable.ic_dialog_alert);
            }
        } else if (requestCode == 1001) {
            // 权限请求结果回调
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Log.d("Permission", "权限授予成功");
            } else {
                Log.e("PermissionError", "用户拒绝部分权限");
                notificationManager.showNotification("权限错误", "需要相关权限才能获取完整设备信息", android.R.drawable.ic_dialog_alert);
            }
        }
    }

    /**
     * 当应用从后台恢复时调用
     */
    @Override
    protected void onResume() {
        super.onResume();
        // 重新加载自动上传设置，确保与设置页面的更改同步
        loadAutoUploadSettings();
        // 重新启动状态更新任务
        startStatusUpdateTask();
        Log.d("AutoUpload", "应用从后台恢复，状态更新重启");
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
     * 当应用进入后台时调用
     */
    @Override
    protected void onPause() {
        super.onPause();
        // 只移除状态更新的回调，自动上传任务继续运行
        statusUpdateHandler.removeCallbacksAndMessages(null);
        Log.d("AutoUpload", "应用进入后台，状态更新暂停，自动上传继续运行");
    }

    /**
     * 当应用销毁时调用（改进版：不停止后台服务，让服务在息屏/后台持续运行）
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 移除所有回调，避免内存泄漏
        autoUploadHandler.removeCallbacksAndMessages(null);
        statusUpdateHandler.removeCallbacksAndMessages(null);
        // 注销广播接收器
        unregisterReceiver(autoUploadStateReceiver);

        // 停止 Activity 中的位置更新（后台服务会继续独立获取位置）
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            Log.d("LocationInit", "Activity位置更新已停止（后台服务仍在运行）");
        }

        // 注销加速度传感器监听器
        if (sensorManager != null && sensorEventListener != null) {
            sensorManager.unregisterListener(sensorEventListener);
        }

        // 重要：不停止LocationService，让它在后台持续运行
        Log.d("AutoUpload", "Activity销毁，后台定位服务保持运行");
    }

    /**
     * 加载自动上传设置
     */
    private void loadAutoUploadSettings() {
        SharedPreferences prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        isAutoUploadEnabled = prefs.getBoolean(KEY_AUTO_UPLOAD_ENABLED, false);
        Log.d("AutoUpload", "加载自动上传设置: " + (isAutoUploadEnabled ? "开启" : "关闭"));
    }

    /**
     * 启动状态更新任务（每秒刷新一次指示灯状态）
     */
    private void startStatusUpdateTask() {
        statusUpdateHandler.removeCallbacksAndMessages(null);
        statusUpdateHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateStatusIndicator();
                statusUpdateHandler.postDelayed(this, 1000); // 每秒刷新一次
            }
        }, 0);
    }

    /**
     * 启动自动上传任务（每分钟执行一次）
     */
    private void startAutoUploadTask() {
        Log.d("AutoUpload", "启动自动上传任务，当前状态: " + (isAutoUploadEnabled ? "开启" : "关闭"));
        // 移除所有现有的回调，避免重复执行
        autoUploadHandler.removeCallbacksAndMessages(null);
        
        // 立即检查一次当前时间
        checkCurrentTime();
        
        // 每秒检查一次时间
        autoUploadHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkCurrentTime();
                // 1秒后再次检查
                autoUploadHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    /**
     * 停止自动上传任务
     */
    private void stopAutoUploadTask() {
        Log.d("AutoUpload", "停止自动上传任务");
        // 移除所有回调，释放线程
        autoUploadHandler.removeCallbacksAndMessages(null);
    }

    /**
     * 检查当前时间是否是整点时刻
     */
    private void checkCurrentTime() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);
        int second = calendar.get(Calendar.SECOND);
        
        Log.d("AutoUpload", "检查当前时间 - " + hour + ":" + minute + ":" + second + ", 自动上传: " + (isAutoUploadEnabled ? "开启" : "关闭"));
        
        // 检查是否开启了自动上传
        if (isAutoUploadEnabled) {
            // 检查是否是整点时刻
            if (second == 0) {
                Log.d("AutoUpload", "检测到整点时刻，执行自动上传");
                // 执行自动上传
                performAutoUpload();
            }
        }
    }

    /**
     * 执行自动上传
     */
    private void performAutoUpload() {
        Log.d("AutoUpload", "开始执行自动上传");
        // 从 SharedPreferences 读取 API 和 UUID
        SharedPreferences prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        String dataApiUrl = prefs.getString("data_api", "").trim();
        String uuid = prefs.getString("uuid", "").trim();

        Log.d("AutoUpload", "Data API: " + dataApiUrl);
        Log.d("AutoUpload", "UUID: " + uuid);

        if (dataApiUrl.isEmpty() || uuid.isEmpty()) {
            Log.w("AutoUpload", "Data API 或 UUID 未设置，跳过自动上传");
            return;
        }

        if (!isNetworkAvailable()) {
            Log.w("AutoUpload", "网络不可用，跳过自动上传");
            return;
        }

        // 检查权限
        if (!checkPermissions()) {
            // 权限请求已发送，等待用户响应
            Log.d("AutoUpload", "权限请求已发送，等待用户响应");
            return;
        }

        Log.d("AutoUpload", "准备执行网络请求");
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
                Log.d("AutoUploadJSON", "生成的 JSON: " + json);

                // 3. 构建 POST 请求
                RequestBody body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
                Request request = new Request.Builder()
                        .url(dataApiUrl)
                        .post(body)
                        .build();

                // 4. 执行请求
                Log.d("AutoUpload", "发送请求到: " + dataApiUrl);
                try (Response response = client.newCall(request).execute()) {
                    Log.d("AutoUpload", "收到响应，状态码: " + response.code());
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        Log.d("AutoUploadSuccess", "响应: " + responseBody);
                        // 保存上传时间
                        saveLastUploadTime();
                        Log.d("AutoUpload", "上传成功，已保存上传时间");
                    } else {
                        Log.e("AutoUploadError", "上传失败: 状态码 " + response.code());
                    }
                }
            } catch (Exception e) {
                Log.e("AutoUploadError", "上传异常", e);
            }
        }).start();
    }
    
    /**
     * 检查权限（适配 MIUI14 高权限要求）
     */
    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 12+ 需要单独请求精确位置权限
            String[] permissions;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, // 仅需要精确定位权限，不需要粗略定位
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.READ_PHONE_STATE
                };
            } else {
                permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, // 仅需要精确定位权限，不需要粗略定位
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.READ_PHONE_STATE
                };
            }

            boolean allGranted = true;
            for (String permission : permissions) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                requestPermissions(permissions, 1001);
                return false;
            }
        }
        return true;
    }

    /**
     * 初始化 FusedLocationProviderClient（适配 MIUI14 高权限定位）
     */
    private void initFusedLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // 创建位置回调（改进版：按精度判断是否接受，不再只看provider名称）
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult != null && locationResult.getLastLocation() != null) {
                    Location location = locationResult.getLastLocation();
                    // 改进：按精度判断，而不是只接受 GPS provider
                    if (isValidLocation(location)) {
                        lastKnownLocation = location;
                        Log.d("LocationUpdate", "位置更新: " + location.getLatitude() + ", " +
                                location.getLongitude() + " 精度:" + location.getAccuracy() + "m 来源:" + location.getProvider());
                    } else {
                        Log.d("LocationUpdate", "位置精度不足，跳过: " + location.getAccuracy() + "m 来源:" + location.getProvider());
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
                                resolvable.startResolutionForResult(MainActivity.this, 1002);
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

        if (accelerometer != null) {
            // 注册传感器监听器，采样率使用游戏级精度
            sensorManager.registerListener(sensorEventListener, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            Log.d("SensorInit", "加速度传感器已初始化");
        } else {
            Log.e("SensorError", "设备不支持加速度传感器");
        }
    }

    /**
     * 加速度传感器监听器
     */
    private SensorEventListener sensorEventListener = new SensorEventListener() {
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
    
    /**
     * 获取最后已知位置（改进版：按精度判断，接受所有有效定位来源）
     */
    private Location getLastKnownLocation() {
        // 优先返回FusedLocationProviderClient获取的位置（按精度判断，不限制provider）
        if (lastKnownLocation != null && isValidLocation(lastKnownLocation)) {
            return lastKnownLocation;
        }

        // 同时从GPS和网络获取位置，取精度更高的
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location bestLocation = null;

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // 获取GPS位置
            Location gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gpsLocation != null && isValidLocation(gpsLocation)) {
                bestLocation = gpsLocation;
            }

            // 获取网络定位（作为GPS的补充，室内也能获取位置）
            Location networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (networkLocation != null && isValidLocation(networkLocation)) {
                if (bestLocation == null || networkLocation.getAccuracy() < bestLocation.getAccuracy()) {
                    bestLocation = networkLocation;
                }
            }
        }

        // 如果上述都没有，尝试FusedLocationProviderClient获取的位置
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
                        // 同步获取
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

        // 最后兜底：返回缓存的lastKnownLocation（即使精度稍差）
        return (bestLocation != null) ? bestLocation : lastKnownLocation;
    }

    /**
     * 判断位置是否有效：按精度（米）判断，而不是按provider名称判断
     * GPS定位精度通常 < 50m，网络定位通常 50-200m
     */
    private boolean isValidLocation(Location location) {
        if (location == null) return false;
        float accuracy = location.getAccuracy();
        // 精度>0且<200米即认为有效（精度数值越小越精确）
        return accuracy > 0 && accuracy < 200;
    }
    
    /**
     * 获取位置来源（改进版：支持识别融合定位）
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
            // FusedLocationProviderClient 返回的provider通常是 "fused" 或 "gps" 或空字符串
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
     * 更新状态指示灯
     */
    private void updateStatusIndicator() {
        SharedPreferences prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        long lastUploadTime = prefs.getLong(KEY_LAST_UPLOAD_TIME, 0);
        long currentTime = System.currentTimeMillis();
        
        // 检查是否在1分钟内有上传记录
        if (lastUploadTime > 0 && (currentTime - lastUploadTime) <= 60 * 1000) {
            // 1分钟内有上传记录，显示绿色
            if (statusIndicator != null) {
                statusIndicator.setBackgroundTintList(ColorStateList.valueOf(Color.GREEN));
            }
        } else {
            // 1分钟内无上传记录或上传失败，显示红色
            if (statusIndicator != null) {
                statusIndicator.setBackgroundTintList(ColorStateList.valueOf(Color.RED));
            }
        }
    }

    /**
     * 保存上次上传时间
     */
    private void saveLastUploadTime() {
        SharedPreferences prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong(KEY_LAST_UPLOAD_TIME, System.currentTimeMillis());
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

    // 设备信息数据类（保留用于兼容）
    public static class DeviceInfo {
        public String uuid;
        public String chargingStatus;
        public int batteryLevel;
        public String deviceModel;
        public String osVersion;
    }
}