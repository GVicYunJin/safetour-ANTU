package com.example.tt;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class CustomNotificationManager {

    private Activity activity;
    private ViewGroup rootView;
    private LinearLayout notificationContainer;
    private List<View> notifications = new ArrayList<>();

    public CustomNotificationManager(Activity activity) {
        this.activity = activity;
        this.rootView = activity.findViewById(android.R.id.content);
        initNotificationContainer();
    }

    private void initNotificationContainer() {
        // 创建通知容器
        notificationContainer = new LinearLayout(activity);
        notificationContainer.setOrientation(LinearLayout.VERTICAL);
        notificationContainer.setGravity(Gravity.TOP | Gravity.END);
        notificationContainer.setPadding(0, 0, 0, 0);

        // 添加到根视图
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rootView.addView(notificationContainer, params);
    }

    /**
     * 显示自定义通知
     */
    public void showNotification(String title, String message, int iconResource) {
        //  Inflate 通知布局
        View notificationView = LayoutInflater.from(activity).inflate(
                R.layout.custom_notification, notificationContainer, false
        );

        // 设置通知内容
        TextView titleTextView = notificationView.findViewById(R.id.notification_title);
        TextView messageTextView = notificationView.findViewById(R.id.notification_message);

        titleTextView.setText(title);
        messageTextView.setText(message);

        // 计算通知尺寸
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int notificationWidth = screenWidth / 4; // 屏幕宽度的16.7%（原来的一半）
        int notificationHeight = (int) (notificationWidth * 0.3); // 宽度的30%

        // 设置通知尺寸
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                notificationWidth,
                notificationHeight
        );
        notificationView.setLayoutParams(params);

        // 添加到容器
        notificationContainer.addView(notificationView, 0); // 添加到顶部
        notifications.add(0, notificationView); // 添加到列表顶部

        // 执行进入动画
        animateNotificationIn(notificationView);

        // 3秒后自动消失
        notificationView.postDelayed(() -> dismissNotification(notificationView), 3000);
    }

    /**
     * 关闭通知
     */
    private void dismissNotification(View notificationView) {
        if (!notifications.contains(notificationView)) return;

        // 执行消失动画
        animateNotificationOut(notificationView, () -> {
            // 从容器和列表中移除
            notificationContainer.removeView(notificationView);
            notifications.remove(notificationView);
            // 重新排列剩余通知
            repositionNotifications();
        });
    }

    /**
     * 通知进入动画
     */
    private void animateNotificationIn(View notificationView) {
        // 初始位置：屏幕右上角外
        notificationView.setTranslationX(notificationView.getWidth());
        notificationView.setTranslationY(-notificationView.getHeight());
        notificationView.setAlpha(0f);

        // 动画：从右上角滑入并淡入
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(
                ObjectAnimator.ofFloat(notificationView, "translationX", 0f),
                ObjectAnimator.ofFloat(notificationView, "translationY", 0f),
                ObjectAnimator.ofFloat(notificationView, "alpha", 1f)
        );
        animatorSet.setDuration(300);
        animatorSet.start();
    }

    /**
     * 通知消失动画
     */
    private void animateNotificationOut(View notificationView, Runnable onComplete) {
        // 动画：淡出
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(notificationView, "alpha", 0f);
        fadeOut.setDuration(300);
        fadeOut.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {}

            @Override
            public void onAnimationEnd(Animator animation) {
                if (onComplete != null) {
                    onComplete.run();
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {}

            @Override
            public void onAnimationRepeat(Animator animation) {}
        });
        fadeOut.start();
    }

    /**
     * 重新排列通知
     */
    private void repositionNotifications() {
        // 为每个通知设置位置动画
        for (int i = 0; i < notifications.size(); i++) {
            View notificationView = notifications.get(i);
            float targetY = 0;
            for (int j = 0; j < i; j++) {
                targetY += notifications.get(j).getHeight() + 4; // 4dp 间距
            }

            ObjectAnimator animator = ObjectAnimator.ofFloat(
                    notificationView, "translationY", targetY
            );
            animator.setDuration(300);
            animator.start();
        }
    }

    /**
     * 清除所有通知
     */
    public void clearAllNotifications() {
        for (View notification : new ArrayList<>(notifications)) {
            dismissNotification(notification);
        }
    }

}