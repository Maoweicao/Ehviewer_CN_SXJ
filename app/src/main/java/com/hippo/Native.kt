package com.hippo

import android.util.Log
import java.io.FileDescriptor

object Native {
    private const val TAG = "Native"

    @Volatile
    private var loaded = false

    @JvmStatic
    fun initialize() {
        if (loaded) return
        try {
            System.loadLibrary("ehviewer")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            // 原生库不可用（例如 JVM/Robolectric 单元测试环境，或设备上加载失败）时降级，
            // 不让 Application 初始化直接崩溃。真正调用 getFd() 时仍会按需抛错。
            Log.w(TAG, "loadLibrary(ehviewer) failed, native features disabled: ${e.message}")
        }
    }

    @JvmStatic
    external fun getFd(fd: FileDescriptor?): Int
}
