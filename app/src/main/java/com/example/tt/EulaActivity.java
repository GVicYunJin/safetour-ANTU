package com.example.tt;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class EulaActivity extends AppCompatActivity {

    private static final String PREF_EULA_ACCEPTED = "eula_accepted";
    private static final String PREFS_NAME = "AppSettings";

    private CheckBox eulaCheckbox;
    private Button enterButton;
    private TextView eulaContent;

    // 第二个界面控件
    private LinearLayout firstLayout;
    private LinearLayout secondLayout;
    private TextView warningContent;
    private TextView countdownText;
    private Button nextButton;
    private Handler countdownHandler;
    private int countdownSeconds = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_eula);

        // 检查是否已经同意过协议，如果已同意则直接跳转到主界面
        if (isEulaAccepted(this)) {
            Intent intent = new Intent(EulaActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        // 绑定控件
        eulaCheckbox = findViewById(R.id.eula_checkbox);
        enterButton = findViewById(R.id.eula_enter_btn);
        eulaContent = findViewById(R.id.eula_content);

        // 绑定第二个界面控件
        firstLayout = findViewById(R.id.eula_first_layout);
        secondLayout = findViewById(R.id.eula_second_layout);
        warningContent = findViewById(R.id.eula_warning_content);
        countdownText = findViewById(R.id.countdown_text);
        nextButton = findViewById(R.id.eula_next_btn);

        // 加载EULA内容
        loadEulaContent();

        // 加载警告内容
        loadWarningContent();

        // 复选框点击事件
        eulaCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                enterButton.setEnabled(isChecked);
            }
        });

        // 进入按钮点击事件 - 切换到第二个界面
        enterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 保存用户同意状态
                saveEulaAccepted();
                // 切换到第二个界面
                switchToSecondLayout();
            }
        });

        // 下一步按钮点击事件 - 跳转到主界面
        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 跳转到主界面
                Intent intent = new Intent(EulaActivity.this, MainActivity.class);
                startActivity(intent);
                finish();
            }
        });
    }

    /**
     * 检查用户是否已经同意过EULA
     */
    public static boolean isEulaAccepted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_EULA_ACCEPTED, false);
    }

    /**
     * 保存用户同意状态
     */
    private void saveEulaAccepted() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_EULA_ACCEPTED, true).apply();
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
            eulaContent.setText(sb.toString());
            reader.close();
            is.close();
        } catch (IOException e) {
            eulaContent.setText("无法加载用户协议内容");
            Toast.makeText(this, "加载协议失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 加载警告内容
     */
    private void loadWarningContent() {
        String warningText = "尊敬的用户您好：\n\n"
                + "本项目开源免费，如果你有偿获得，请收集凭证并提交给我们2582634359@qq.com，我们会依法追究责任\n"
                + "开源仓库地址：https://github.com/GVicYunJin/safetour-ANTU\n\n"
                + "重要内容请知悉：\n"
                + "在您授予完整权限、填写 API 地址与 UUID 参数后，本设备授权数据将实时对该私有服务器所有者完全公开：\n"
                + "对方可以全天候查看你的实时位置、历史行程轨迹、设备电量、WiFi、基站、传感器状态等全部设备数据。\n"
                + "仅你手动点击拍摄的前后置照片会直接上传至对方服务器，对方可无条件随时查看。\n"
                + "你的报备照片与部分设备数据，可能自动推送至对方配置的 QQ 群聊中实时公开展示。\n"
                + "本项目无任何官方中转服务器、官方数据服务。\n"
                + "你填写的服务器地址完全为第三方私有自建服务，所有数据风险由你自行承担。\n"
                + "若你 不了解 本项目的工作原理，不知道这个软件有什么用，会做什么，请立即前往原作者短视频平台主页查看。\n"
                + "请确定你完全知悉、明白这个软件会做什么，完全信任给你接口的人的情况下使用\n"
                + "如果有不法分子尝试通过该开源项目追踪并获取你的隐私数据，请收集凭证并提交给我们，我们会依法追究责任\n\n"
                + "bilibili：https://b23.tv/SLUO74J，抖音：https://v.douyin.com/mbhXkGhxHoA/ 9@7.com \n";
        warningContent.setText(warningText);
    }

    /**
     * 切换到第二个界面并开始倒计时
     */
    private void switchToSecondLayout() {
        firstLayout.setVisibility(View.GONE);
        secondLayout.setVisibility(View.VISIBLE);

        // 初始化倒计时
        countdownSeconds = 60;
        nextButton.setEnabled(false);
        countdownText.setText("请仔细阅读，" + countdownSeconds + "秒后可继续");

        // 开始倒计时
        countdownHandler = new Handler();
        countdownHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                countdownSeconds--;
                if (countdownSeconds > 0) {
                    countdownText.setText("请仔细阅读，" + countdownSeconds + "秒后可继续");
                    countdownHandler.postDelayed(this, 1000);
                } else {
                    countdownText.setText("已可继续");
                    nextButton.setEnabled(true);
                }
            }
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 取消倒计时，避免内存泄漏
        if (countdownHandler != null) {
            countdownHandler.removeCallbacksAndMessages(null);
        }
    }
}