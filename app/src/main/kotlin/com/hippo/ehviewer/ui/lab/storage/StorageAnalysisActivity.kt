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

package com.hippo.ehviewer.ui.lab.storage

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.hippo.ehviewer.R

/**
 * 存储感知分析。
 *
 * 三个 Tab：存储概览（环形图 + 明细卡片）、画廊占用分析（按分类 / 占用特征 / 受保护）、
 * 缓存与清理。
 */
class StorageAnalysisActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_analysis)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewPager = findViewById(R.id.view_pager)
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = TAB_COUNT

            override fun createFragment(position: Int): Fragment = when (position) {
                TAB_OVERVIEW -> StorageOverviewFragment()
                TAB_GALLERY -> GalleryUsageFragment()
                else -> CacheCleanupFragment()
            }
        }
        TabLayoutMediator(findViewById<TabLayout>(R.id.tab_layout), viewPager) { tab, position ->
            tab.setText(TAB_TITLES[position])
        }.attach()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.storage_analysis, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }

        R.id.storage_action_refresh -> {
            refreshCurrent()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /** 切到指定 Tab（供卡片里的“按画廊分析”之类的入口使用）。 */
    fun showTab(index: Int) {
        if (index in 0 until TAB_COUNT) {
            viewPager.currentItem = index
        }
    }

    /** 刷新当前 Tab；其他 Tab 在被切到时会自动刷新。 */
    fun refreshCurrent() {
        when (val fragment = fragmentAt(viewPager.currentItem)) {
            is StorageOverviewFragment -> fragment.refresh()
            is GalleryUsageFragment -> fragment.refresh()
            is CacheCleanupFragment -> fragment.refresh()
        }
    }

    private fun fragmentAt(index: Int): Fragment? =
        supportFragmentManager.findFragmentByTag("f$index")

    companion object {
        const val TAB_OVERVIEW = 0
        const val TAB_GALLERY = 1
        const val TAB_CACHES = 2

        private const val TAB_COUNT = 3

        private val TAB_TITLES = intArrayOf(
            R.string.storage_tab_overview,
            R.string.storage_tab_gallery,
            R.string.storage_tab_caches,
        )

        fun start(context: Context) {
            context.startActivity(Intent(context, StorageAnalysisActivity::class.java))
        }
    }
}