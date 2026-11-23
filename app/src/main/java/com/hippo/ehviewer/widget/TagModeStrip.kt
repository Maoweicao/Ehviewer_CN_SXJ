package com.hippo.ehviewer.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 右滑面板顶部的一级模式条。
 *
 * 每一格用自己的强调色：选中时填色，未选中时只保留文字与一条透明底线；
 * 默认模式由调用方传入主题主色，书签/收藏传入各自的固定色。
 */
class TagModeStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    data class Mode(val title: CharSequence, val accent: Int)

    fun interface OnModeSelectedListener {
        fun onModeSelected(index: Int)
    }

    private class Chip(val root: LinearLayout, val label: TextView, val bar: View, val defaultColor: Int)

    private val chips = ArrayList<Chip>()
    private var modes: List<Mode> = emptyList()
    private var selectedIndex = 0

    /** 点击某一格（不会因为重复点击同一格而回调） */
    var onModeSelected: OnModeSelectedListener? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = dp(8)
        setPadding(padH, dp(6), padH, 0)
    }

    fun setModes(newModes: List<Mode>, selected: Int = 0) {
        removeAllViews()
        chips.clear()
        modes = newModes
        for ((index, mode) in newModes.withIndex()) {
            val chip = createChip(mode)
            chip.root.setOnClickListener { select(index) }
            val lp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) lp.marginStart = dp(6)
            addView(chip.root, lp)
            chips.add(chip)
        }
        select(selected.coerceIn(0, (newModes.size - 1).coerceAtLeast(0)), notify = false)
    }

    fun select(index: Int, notify: Boolean = true) {
        if (modes.isEmpty()) return
        val target = index.coerceIn(0, modes.size - 1)
        val changed = target != selectedIndex
        selectedIndex = target
        for ((i, chip) in chips.withIndex()) {
            val accent = modes[i].accent
            val isSelected = i == target
            chip.label.setTextColor(if (isSelected) contrastOn(accent) else chip.defaultColor)
            chip.label.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            chip.root.background = if (isSelected) {
                GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(6).toFloat()
                }
            } else {
                null
            }
            chip.bar.setBackgroundColor(if (isSelected) accent else Color.TRANSPARENT)
        }
        if (notify && changed) {
            onModeSelected?.onModeSelected(target)
        }
    }

    fun getSelectedIndex(): Int = selectedIndex

    private fun createChip(mode: Mode): Chip {
        val label = TextView(context).apply {
            text = mode.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            maxLines = 1
        }
        val bar = View(context)
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = mode.title
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(2)).apply {
                topMargin = dp(3)
            })
        }
        return Chip(root, label, bar, label.currentTextColor)
    }

    private fun contrastOn(color: Int): Int {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        return if (luminance > 0.62) Color.BLACK else Color.WHITE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
