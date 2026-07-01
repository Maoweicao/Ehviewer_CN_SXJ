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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class BarChartView extends View {
    private Paint mPaint;
    private Paint mTextPaint;
    private Paint mLinePaint;
    private List<ChartItem> mItems;
    private long mMaxValue;
    private String mTitle;
    private boolean mIsDarkMode;

    public BarChartView(Context context) {
        super(context);
        init();
    }

    public BarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BarChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setStyle(Paint.Style.FILL);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTextSize(24f);

        mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mLinePaint.setStrokeWidth(2f);

        mItems = new ArrayList<>();
        mTitle = "";

        // Check if dark mode
        int nightModeFlags = getContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        mIsDarkMode = (nightModeFlags == Configuration.UI_MODE_NIGHT_YES);
    }

    public void setData(Map<String, Long> data, String title) {
        mTitle = title;
        mItems.clear();
        mMaxValue = 0;

        List<Map.Entry<String, Long>> entries = new ArrayList<>(data.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return a.getKey().compareTo(b.getKey());
            }
        });

        int count = Math.min(entries.size(), 7);
        for (int i = entries.size() - count; i < entries.size(); i++) {
            Map.Entry<String, Long> entry = entries.get(i);
            mItems.add(new ChartItem(entry.getKey(), entry.getValue()));
            if (entry.getValue() > mMaxValue) {
                mMaxValue = entry.getValue();
            }
        }

        if (mMaxValue == 0) {
            mMaxValue = 1;
        }

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int textColor = mIsDarkMode ? Color.parseColor("#CCCCCC") : Color.parseColor("#333333");
        int secondaryTextColor = mIsDarkMode ? Color.parseColor("#AAAAAA") : Color.parseColor("#666666");
        int hintColor = mIsDarkMode ? Color.parseColor("#888888") : Color.parseColor("#999999");
        int lineColor = mIsDarkMode ? Color.parseColor("#444444") : Color.parseColor("#EEEEEE");
        int barColor = mIsDarkMode ? Color.parseColor("#4ECDC4") : Color.parseColor("#4ECDC4");

        int width = getWidth();
        int height = getHeight();
        int paddingLeft = 80;
        int paddingRight = 20;
        int paddingTop = 60;
        int paddingBottom = 80;

        mTextPaint.setTextSize(32f);
        mTextPaint.setColor(textColor);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(mTitle, width / 2f, 40, mTextPaint);

        if (mItems.isEmpty()) {
            mTextPaint.setTextSize(40f);
            mTextPaint.setColor(hintColor);
            canvas.drawText("暂无数据", width / 2f, height / 2f, mTextPaint);
            return;
        }

        int chartWidth = width - paddingLeft - paddingRight;
        int chartHeight = height - paddingTop - paddingBottom;

        mLinePaint.setColor(lineColor);
        for (int i = 0; i <= 4; i++) {
            float y = paddingTop + chartHeight * (1 - i / 4f);
            canvas.drawLine(paddingLeft, y, width - paddingRight, y, mLinePaint);

            mTextPaint.setTextSize(20f);
            mTextPaint.setColor(hintColor);
            mTextPaint.setTextAlign(Paint.Align.RIGHT);
            long value = mMaxValue * i / 4;
            canvas.drawText(formatValue(value), paddingLeft - 10, y + 7, mTextPaint);
        }

        float barWidth = chartWidth / mItems.size() * 0.6f;
        float gap = chartWidth / mItems.size() * 0.4f;
        float startX = paddingLeft + gap / 2;

        for (int i = 0; i < mItems.size(); i++) {
            ChartItem item = mItems.get(i);
            float barHeight = (float) item.value / mMaxValue * chartHeight;
            float left = startX + i * (barWidth + gap);
            float top = paddingTop + chartHeight - barHeight;
            float right = left + barWidth;
            float bottom = paddingTop + chartHeight;

            RectF rect = new RectF(left, top, right, bottom);
            mPaint.setColor(barColor);
            canvas.drawRect(rect, mPaint);

            mTextPaint.setTextSize(18f);
            mTextPaint.setColor(secondaryTextColor);
            mTextPaint.setTextAlign(Paint.Align.CENTER);

            String label = item.name;
            if (label.length() > 5) {
                label = label.substring(label.length() - 5);
            }
            canvas.drawText(label, left + barWidth / 2, bottom + 25, mTextPaint);

            if (item.value > 0) {
                mTextPaint.setColor(textColor);
                canvas.drawText(formatDuration(item.value), left + barWidth / 2, top - 10, mTextPaint);
            }
        }
    }

    private String formatValue(long value) {
        if (value >= 60 * 60 * 1000) {
            return String.format("%.0fh", value / (60f * 60f * 1000f));
        } else if (value >= 60 * 1000) {
            return String.format("%.0fm", value / (60f * 1000f));
        } else {
            return String.format("%.0fs", value / 1000f);
        }
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return hours + "h" + (minutes % 60) + "m";
        } else if (minutes > 0) {
            return minutes + "m";
        } else {
            return seconds + "s";
        }
    }

    private static class ChartItem {
        String name;
        long value;

        ChartItem(String name, long value) {
            this.name = name;
            this.value = value;
        }
    }
}
