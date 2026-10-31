package com.dsh.whalepet;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 还原原版泡泡的文本排布：
 * .dshwv-text 位于泡泡的 (44.25%, 36%) 中心，尺寸 66% × 64%。
 * 泡泡自身按原版比例绘制（宽:高 = 1026:700）。
 */
public class BubbleBox extends FrameLayout {

    private static final float TEXT_CENTER_X = 0.4425f;
    private static final float TEXT_CENTER_Y = 0.36f;
    private static final float TEXT_WIDTH_RATIO = 0.66f;
    private static final float TEXT_HEIGHT_RATIO = 0.64f;

    /** 气泡自身的绝对尺寸（px）。必须显式给出：背景是矢量图，固有尺寸 1026x700dp，
     *  若依赖 getSuggestedMinimumWidth() 会被量成 3000+ px。 */
    private int bubbleWidth;
    private int bubbleHeight;

    public BubbleBox(Context context) {
        super(context);
    }

    public BubbleBox(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BubbleBox(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** 指定气泡尺寸（px）。 */
    public void setBubbleSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (bubbleWidth != width || bubbleHeight != height) {
            bubbleWidth = width;
            bubbleHeight = height;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width;
        int height;
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            width = MeasureSpec.getSize(widthMeasureSpec);
        } else {
            width = bubbleWidth;
        }
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            height = MeasureSpec.getSize(heightMeasureSpec);
        } else {
            height = bubbleHeight;
        }
        int childWidth = Math.round(width * TEXT_WIDTH_RATIO);
        int childHeight = Math.round(height * TEXT_HEIGHT_RATIO);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        int centerX = Math.round(width * TEXT_CENTER_X);
        int centerY = Math.round(height * TEXT_CENTER_Y);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            int childLeft = centerX - childWidth / 2;
            int childTop = centerY - childHeight / 2;
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
        }
    }
}
