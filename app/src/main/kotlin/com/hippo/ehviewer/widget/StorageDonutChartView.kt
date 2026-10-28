/*
 * Copyright 2025 EhViewer Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt
import androidx.annotation.Px
import com.hippo.ehviewer.R

/**
 * 存储占用的环形图。
 *
 * 环心显示两行不同颜色的数字（通常是「总容量」与「已占用」），
 * 环上按占用比例分段绘制，配色与图例由外部传入，保证图与卡片颜色一致。
 */
class StorageDonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 环形图的一段。 */
    data class Slice(val value: Float, @ColorInt val color: Int)

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val primaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val captionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val arcBounds = RectF()

    private var slices: List<Slice> = emptyList()

    @Px
    private var ringWidth = 0

    /** 相邻分段之间的角度间隙（度）。 */
    private var sliceGap = 0f

    private var animationProgress = 1f
    private var animator: ValueAnimator? = null

    private var centerPrimary: CharSequence = ""
    private var centerSecondary: CharSequence = ""
    private var centerCaption: CharSequence? = null

    init {
        val density = resources.displayMetrics.density
        ringWidth = (22 * density).toInt()
        sliceGap = 2.5f

        val attrsArray = resolveThemeColors()
        primaryTextPaint.color = attrsArray[0]
        secondaryTextPaint.color = attrsArray[1]
        captionTextPaint.color = attrsArray[2]
        primaryTextPaint.textSize = 22 * density
        secondaryTextPaint.textSize = 22 * density
        captionTextPaint.textSize = 12 * density
    }

    private fun resolveThemeColors(): IntArray {
        val array = context.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.textColorPrimary,
                R.attr.textColorThemeAccent,
                android.R.attr.textColorSecondary,
            ),
        )
        val colors = intArrayOf(
            array.getColor(0, 0xFF212121.toInt()),
            array.getColor(1, 0xFF009688.toInt()),
            array.getColor(2, 0xFF757575.toInt()),
        )
        array.recycle()
        return colors
    }

    /**
     * 设置图表数据。
     *
     * @param slices 各分段，按绘制顺序排列
     * @param animate 是否播放入场动画
     */
    fun setData(
        slices: List<Slice>,
        animate: Boolean = false,
        centerPrimary: CharSequence = this.centerPrimary,
        centerSecondary: CharSequence = this.centerSecondary,
        centerCaption: CharSequence? = this.centerCaption,
    ) {
        this.slices = slices
        this.centerPrimary = centerPrimary
        this.centerSecondary = centerSecondary
        this.centerCaption = centerCaption
        animator?.cancel()
        if (animate) {
            animationProgress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 850L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    animationProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animationProgress = 1f
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxSize = (MAX_SIZE_DP * resources.displayMetrics.density).toInt()
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(MeasureSpec.getSize(widthMeasureSpec), maxSize)
            else -> maxSize
        }
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> minOf(MeasureSpec.getSize(heightMeasureSpec), maxSize)
            MeasureSpec.AT_MOST -> minOf(MeasureSpec.getSize(heightMeasureSpec), maxSize)
            else -> maxSize
        }
        // 始终是正方形，并限制在合理的显示尺寸内
        val size = minOf(minOf(width, height), maxSize)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return

        val cx = width / 2f
        val cy = height / 2f
        val stroke = minOf(ringWidth.toFloat(), size / 6f)
        arcPaint.strokeWidth = stroke

        val radius = size / 2f - stroke / 2f - size * 0.04f
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val total = slices.sumOf { it.value.toDouble() }.toFloat()
        if (total > 0f) {
            var startAngle = START_ANGLE
            for (slice in slices) {
                if (slice.value <= 0f) continue
                val ratio = slice.value / total
                val sweep = 360f * ratio * animationProgress
                if (sweep <= 0f) continue
                val gap = if (slices.size > 1) minOf(sliceGap, sweep / 4f) else 0f
                arcPaint.color = slice.color
                canvas.drawArc(arcBounds, startAngle + gap / 2f, (sweep - gap).coerceAtLeast(0.5f), false, arcPaint)
                startAngle += sweep
            }
        }

        drawCenterText(canvas, cx, cy)
    }

    private fun drawCenterText(canvas: Canvas, cx: Float, cy: Float) {
        val hasCaption = !centerCaption.isNullOrEmpty()
        val gap = resources.displayMetrics.density * 6f

        val primaryHalf = primaryTextPaint.fontMetrics.let { it.ascent + it.descent } / 2f
        val secondaryHalf = secondaryTextPaint.fontMetrics.let { it.ascent + it.descent } / 2f
        val captionHalf = captionTextPaint.fontMetrics.let { it.ascent + it.descent } / 2f

        val totalHeight =
            primaryHalf * 2 + gap + secondaryHalf * 2 + if (hasCaption) gap + captionHalf * 2 else 0f
        var baseline = cy - totalHeight / 2f + primaryHalf

        canvas.drawText(centerPrimary.toString(), cx, baseline, primaryTextPaint)
        baseline += primaryHalf * 2 + gap
        canvas.drawText(centerSecondary.toString(), cx, baseline, secondaryTextPaint)
        if (hasCaption) {
            baseline += secondaryHalf * 2 + gap
            canvas.drawText(centerCaption.toString(), cx, baseline, captionTextPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 12 点方向起始，顺时针绘制。 */
        const val START_ANGLE = -90f

        /** 图表最大边长（dp），避免在大屏上过大。 */
        const val MAX_SIZE_DP = 240
    }
}