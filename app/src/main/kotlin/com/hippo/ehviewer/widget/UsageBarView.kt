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
import androidx.core.graphics.ColorUtils

/**
 * 细长的圆角占比条，用于卡片里展示“这一项占了多少”。
 *
 * 与 [StorageDonutChartView] 搭配使用：颜色取自分类色，轨道色跟随主题。
 */
class UsageBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // 轨道色跟随主题文字色（低透明度），深浅色模式下都协调
        color = resolveTrackColor(context)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    private var progress = 0f
    private var displayed = 0f
    private var animator: ValueAnimator? = null

    /** 设置占比（0f~1f），超过 3% 才画出圆头，避免看起来像 0。 */
    fun setProgress(fraction: Float, animate: Boolean = false, @ColorInt color: Int? = null) {
        progress = fraction.coerceIn(0f, 1f)
        color?.let { progressPaint.color = it }
        animator?.cancel()
        if (animate) {
            animator = ValueAnimator.ofFloat(displayed, progress).apply {
                duration = 600L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    displayed = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            displayed = progress
            invalidate()
        }
    }

    /** 设置进度条颜色。 */
    fun setBarColor(@ColorInt color: Int) {
        progressPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = h / 2f

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        if (displayed > 0f) {
            val minWidth = if (displayed * w < h) h else displayed * w
            rect.set(0f, 0f, minWidth, h)
            canvas.drawRoundRect(rect, radius, radius, progressPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 轨道 = 主题主文字色 12% 透明。 */
        fun resolveTrackColor(context: Context): Int {
            val array = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            val color = array.getColor(0, 0xFF212121.toInt())
            array.recycle()
            return ColorUtils.setAlphaComponent(color, 0x1F)
        }
    }
}