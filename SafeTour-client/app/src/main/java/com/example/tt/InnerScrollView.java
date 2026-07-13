package com.example.tt;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * 自定义ScrollView，解决嵌套滚动冲突问题
 */
public class InnerScrollView extends ScrollView {

    public InnerScrollView(Context context) {
        super(context);
    }

    public InnerScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public InnerScrollView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // 请求父级不要拦截触摸事件
        getParent().requestDisallowInterceptTouchEvent(true);
        return super.onTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // 请求父级不要拦截触摸事件
        getParent().requestDisallowInterceptTouchEvent(true);
        return super.onInterceptTouchEvent(ev);
    }
}