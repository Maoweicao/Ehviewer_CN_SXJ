package com.hippo.ehviewer.lab.translate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.translate.model.TranslateResult;

import java.util.ArrayList;
import java.util.List;

public class TranslateOverlayView extends View {
    private List<TranslateResult.TextRegion> regions;
    private Paint rectPaint;
    private Paint textPaint;
    private Paint backgroundPaint;
    private boolean visible = true;

    private RectF imageDisplayRect = new RectF();
    private int textureWidth = 1;
    private int textureHeight = 1;
    private boolean hasImageRect = false;

    public TranslateOverlayView(Context context) {
        super(context);
        init();
    }

    public TranslateOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TranslateOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        regions = new ArrayList<>();

        rectPaint = new Paint();
        rectPaint.setColor(Color.argb(180, 0, 150, 255));
        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(2f);

        backgroundPaint = new Paint();
        int alpha = Settings.getAiTranslateOverlayAlpha();
        backgroundPaint.setColor(Color.argb(alpha, 0, 0, 0));

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setAntiAlias(true);
        int fontSize = Settings.getAiTranslateFontSize();
        textPaint.setTextSize(fontSize * getResources().getDisplayMetrics().density);
    }

    public void setRegions(List<TranslateResult.TextRegion> regions) {
        this.regions = regions != null ? regions : new ArrayList<>();
        invalidate();
    }

    public void clearRegions() {
        this.regions.clear();
        invalidate();
    }

    public void setImageDisplayRect(RectF rect) {
        if (rect != null) {
            this.imageDisplayRect.set(rect);
            this.hasImageRect = !rect.isEmpty();
        } else {
            this.imageDisplayRect.setEmpty();
            this.hasImageRect = false;
        }
        invalidate();
    }

    public void setTextureSize(int width, int height) {
        this.textureWidth = width > 0 ? width : 1;
        this.textureHeight = height > 0 ? height : 1;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        setVisibility(visible ? VISIBLE : GONE);
    }

    public boolean isVisible() {
        return visible;
    }

    public void toggleVisibility() {
        setVisible(!visible);
    }

    public void updateDisplaySettings() {
        int alpha = Settings.getAiTranslateOverlayAlpha();
        backgroundPaint.setColor(Color.argb(alpha, 0, 0, 0));

        int fontSize = Settings.getAiTranslateFontSize();
        textPaint.setTextSize(fontSize * getResources().getDisplayMetrics().density);

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!visible || regions == null || regions.isEmpty()) {
            return;
        }

        int position = Settings.getAiTranslatePosition();

        for (TranslateResult.TextRegion region : regions) {
            if (region.translated == null || region.translated.isEmpty()) {
                continue;
            }

            Rect rect = region.rect;
            if (rect == null) continue;

            int drawLeft, drawTop, drawRight, drawBottom;

            if (hasImageRect && imageDisplayRect.width() > 0 && imageDisplayRect.height() > 0) {
                float scaleX = imageDisplayRect.width() / textureWidth;
                float scaleY = imageDisplayRect.height() / textureHeight;

                drawLeft = (int) (imageDisplayRect.left + rect.left * scaleX);
                drawTop = (int) (imageDisplayRect.top + rect.top * scaleY);
                drawRight = (int) (imageDisplayRect.left + rect.right * scaleX);
                drawBottom = (int) (imageDisplayRect.top + rect.bottom * scaleY);
            } else {
                drawLeft = rect.left;
                drawTop = rect.top;
                drawRight = rect.right;
                drawBottom = rect.bottom;
            }

            RectF bgRect = new RectF(drawLeft, drawTop, drawRight, drawBottom);
            canvas.drawRect(bgRect, backgroundPaint);
            canvas.drawRect(bgRect, rectPaint);

            float textX = drawLeft + 4;
            float textY;
            switch (position) {
                case 0:
                    textY = drawTop - 4;
                    break;
                case 1:
                    textY = drawBottom + textPaint.getTextSize() + 4;
                    break;
                case 2:
                default:
                    textY = drawTop + textPaint.getTextSize() + 4;
                    break;
            }

            float maxWidth = drawRight - drawLeft - 8;
            if (maxWidth < textPaint.getTextSize()) {
                maxWidth = getWidth() - drawLeft - 8;
            }

            drawWrappedText(canvas, region.translated, textX, textY,
                    maxWidth, textPaint);
        }
    }

    private void drawWrappedText(Canvas canvas, String text, float x, float y,
                                  float maxWidth, Paint paint) {
        if (text == null || text.isEmpty()) return;

        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String testLine = line.toString() + ch;
            float testWidth = paint.measureText(testLine);

            if (testWidth > maxWidth && line.length() > 0) {
                canvas.drawText(line.toString(), x, y, paint);
                y += paint.getTextSize() + 4;
                line = new StringBuilder(String.valueOf(ch));
            } else {
                line.append(ch);
            }
        }

        if (line.length() > 0) {
            canvas.drawText(line.toString(), x, y, paint);
        }
    }
}
