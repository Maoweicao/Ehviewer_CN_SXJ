package com.hippo.ehviewer.milestone;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PieChartView extends View {
    private Paint mPaint;
    private Paint mTextPaint;
    private List<ChartItem> mItems;
    private boolean mIsDarkMode;
    private int[] mColors = {
            Color.parseColor("#FF6B6B"),
            Color.parseColor("#4ECDC4"),
            Color.parseColor("#45B7D1"),
            Color.parseColor("#96CEB4"),
            Color.parseColor("#FFEAA7"),
            Color.parseColor("#DDA0DD"),
            Color.parseColor("#98D8C8"),
            Color.parseColor("#F7DC6F"),
            Color.parseColor("#BB8FCE"),
            Color.parseColor("#85C1E9")
    };

    public PieChartView(Context context) {
        super(context);
        init();
    }

    public PieChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PieChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setStyle(Paint.Style.FILL);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextSize(32f);
        mTextPaint.setTextAlign(Paint.Align.CENTER);

        mItems = new ArrayList<>();

        // Check if dark mode
        int nightModeFlags = getContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        mIsDarkMode = (nightModeFlags == Configuration.UI_MODE_NIGHT_YES);
    }

    public void setData(Map<String, Long> data) {
        mItems.clear();
        long total = 0;
        for (Map.Entry<String, Long> entry : data.entrySet()) {
            if (entry.getValue() > 0) {
                mItems.add(new ChartItem(entry.getKey(), entry.getValue()));
                total += entry.getValue();
            }
        }
        for (ChartItem item : mItems) {
            item.percentage = (float) item.value / total * 100;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int textColor = mIsDarkMode ? Color.parseColor("#CCCCCC") : Color.parseColor("#333333");
        int hintColor = mIsDarkMode ? Color.parseColor("#888888") : Color.parseColor("#999999");

        if (mItems.isEmpty()) {
            mTextPaint.setTextSize(40f);
            mTextPaint.setColor(hintColor);
            canvas.drawText("暂无数据", getWidth() / 2f, getHeight() / 2f, mTextPaint);
            return;
        }

        int width = getWidth();
        int height = getHeight();
        int size = Math.min(width, height);
        float radius = size * 0.35f;
        float centerX = width / 2f;
        float centerY = height / 2f;

        RectF oval = new RectF(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius
        );

        float startAngle = 0;
        long total = 0;
        for (ChartItem item : mItems) {
            total += item.value;
        }

        for (int i = 0; i < mItems.size(); i++) {
            ChartItem item = mItems.get(i);
            float sweepAngle = (float) item.value / total * 360;

            mPaint.setColor(mColors[i % mColors.length]);
            canvas.drawArc(oval, startAngle, sweepAngle, true, mPaint);

            float midAngle = (float) Math.toRadians(startAngle + sweepAngle / 2);
            float labelRadius = radius * 0.7f;
            float labelX = centerX + (float) Math.cos(midAngle) * labelRadius;
            float labelY = centerY + (float) Math.sin(midAngle) * labelRadius;

            mTextPaint.setTextSize(24f);
            mTextPaint.setColor(Color.WHITE);
            if (item.percentage > 5) {
                canvas.drawText(String.format("%.0f%%", item.percentage), labelX, labelY, mTextPaint);
            }

            startAngle += sweepAngle;
        }

        float legendX = 20;
        float legendY = height - 20 - mItems.size() * 35;
        mTextPaint.setTextSize(28f);
        mTextPaint.setTextAlign(Paint.Align.LEFT);

        for (int i = 0; i < mItems.size(); i++) {
            ChartItem item = mItems.get(i);
            mPaint.setColor(mColors[i % mColors.length]);
            canvas.drawRect(legendX, legendY - 20, legendX + 30, legendY + 10, mPaint);

            mTextPaint.setColor(textColor);
            String label = item.name;
            if (label.length() > 8) {
                label = label.substring(0, 8) + "...";
            }
            canvas.drawText(label + " (" + item.value + ")", legendX + 40, legendY, mTextPaint);
            legendY += 35;
        }
    }

    private static class ChartItem {
        String name;
        long value;
        float percentage;

        ChartItem(String name, long value) {
            this.name = name;
            this.value = value;
        }
    }
}
