package com.hippo.ehviewer.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class WebViewImageFallback(
    private val container: FrameLayout,
    private val webView: WebView,
    private val onDismiss: () -> Unit
) {
    @Volatile
    private var isShowing = false
    private var currentPath: String? = null

    init {
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
        }
        webView.setBackgroundColor(0xFF000000.toInt())

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                container.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {
                hide()
            }
        }
    }

    fun showImage(path: String) {
        if (isShowing && path == currentPath) return
        isShowing = true
        currentPath = path
        val html = buildImageHtml(path)
        webView.loadDataWithBaseURL("file://", html, "text/html", "UTF-8", null)
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        currentPath = null
        container.visibility = View.GONE
        onDismiss()
    }

    fun isShowing(): Boolean = isShowing

    fun destroy() {
        hide()
        webView.destroy()
    }

    private fun buildImageHtml(imagePath: String): String {
        val escapedPath = imagePath
            .replace("\\", "\\\\")
            .replace("'", "\\'")

        return """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=8.0, user-scalable=yes">
    <style>
        * { margin: 0; padding: 0; }
        html, body { width: 100%; height: 100%; background: #000; }
        body { display: flex; align-items: center; justify-content: center; }
        img { max-width: 100%; max-height: 100%; object-fit: contain; }
    </style>
</head>
<body>
    <img src="file://${escapedPath}" />
</body>
</html>
""".trimIndent()
    }
}
