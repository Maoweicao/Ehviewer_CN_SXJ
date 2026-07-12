package com.hippo.ehviewer.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.util.MarkdownParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class MarkdownViewerActivity extends ToolbarActivity {

    @Nullable
    private WebView mWebView;

    @Nullable
    private ProgressBar mProgressBar;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_markdown_viewer);

        mWebView = findViewById(R.id.markdown_webview);
        mProgressBar = findViewById(R.id.markdown_progress);

        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);

        String filePath = getIntent().getStringExtra("markdown_file");
        String title = getIntent().getStringExtra("markdown_title");
        if (title != null) {
            setTitle(title);
        }

        if (filePath != null) {
            loadMarkdown(filePath);
        } else {
            if (mProgressBar != null) {
                mProgressBar.setVisibility(View.GONE);
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void loadMarkdown(String filePath) {
        try {
            InputStream is = getAssets().open(filePath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();

            String html = MarkdownParser.toHtml(sb.toString());

            if (mWebView != null) {
                mWebView.getSettings().setJavaScriptEnabled(false);
                mWebView.getSettings().setBuiltInZoomControls(true);
                mWebView.getSettings().setDisplayZoomControls(false);
                mWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (mProgressBar != null) {
                            mProgressBar.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        return false;
                    }
                });

                String baseUrl = "file:///android_asset/" + filePath.substring(0, filePath.lastIndexOf('/') + 1);
                mWebView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null);
            }
        } catch (IOException e) {
            if (mProgressBar != null) {
                mProgressBar.setVisibility(View.GONE);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mWebView != null) {
            mWebView.destroy();
            mWebView = null;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
