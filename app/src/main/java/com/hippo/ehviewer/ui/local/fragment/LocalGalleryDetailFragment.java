/*
 * Copyright 2016 Hippo Seven
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

package com.hippo.ehviewer.ui.local.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.LocalGalleryInfo;
import com.hippo.ehviewer.cache.GalleryAiCacheManager;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.lab.analyze.AiAnalyzeManager;
import com.hippo.ehviewer.lab.analyze.model.AiGalleryAnalysis;
import com.hippo.ehviewer.lab.analyze.model.AiPageAnalysis;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.unifile.UniFile;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 本地画廊详情Fragment
 */
public class LocalGalleryDetailFragment extends Fragment {
    
    private static final String KEY_GALLERY_INFO = "gallery_info";
    
    private LocalGalleryInfo mGalleryInfo;
    private View mAiAnalysis;
    private TextView mAiSummary;
    private TextView mAiScore;
    private TextView mAiComment;
    private TextView mAiTags;
    private TextView mAiPagesToggle;
    private LinearLayout mAiPagesContainer;
    private TextView mAiAnalyzeButton;
    
    public static LocalGalleryDetailFragment newInstance(LocalGalleryInfo galleryInfo) {
        LocalGalleryDetailFragment fragment = new LocalGalleryDetailFragment();
        Bundle args = new Bundle();
        args.putParcelable(KEY_GALLERY_INFO, galleryInfo);
        fragment.setArguments(args);
        return fragment;
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Bundle args = getArguments();
        if (args != null) {
            mGalleryInfo = args.getParcelable(KEY_GALLERY_INFO);
        }
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_local_gallery_detail, container, false);
        
        if (mGalleryInfo != null) {
            setupViews(view);
        }
        
        return view;
    }
    
    private void setupViews(View view) {
        TextView titleView = view.findViewById(R.id.title);
        TextView pathView = view.findViewById(R.id.path);
        TextView sizeView = view.findViewById(R.id.size);
        TextView pagesView = view.findViewById(R.id.pages);
        TextView categoryView = view.findViewById(R.id.category);
        TextView modifiedView = view.findViewById(R.id.modified);
        TextView progressView = view.findViewById(R.id.progress);
        
        titleView.setText(mGalleryInfo.getDisplayTitle());
        pathView.setText(mGalleryInfo.path);
        sizeView.setText(formatFileSize(mGalleryInfo.size));
        pagesView.setText(String.valueOf(mGalleryInfo.pageCount));
        
        if (mGalleryInfo.category != null) {
            categoryView.setText(mGalleryInfo.category);
            categoryView.setVisibility(View.VISIBLE);
        } else {
            categoryView.setVisibility(View.GONE);
        }
        
        if (mGalleryInfo.timestamp > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            modifiedView.setText(sdf.format(new Date(mGalleryInfo.timestamp)));
            modifiedView.setVisibility(View.VISIBLE);
        } else {
            modifiedView.setVisibility(View.GONE);
        }
        
        if (mGalleryInfo.pageCount > 0) {
            progressView.setText("已读 " + 0 + "/" + mGalleryInfo.pageCount);
            progressView.setVisibility(View.VISIBLE);
        } else {
            progressView.setVisibility(View.GONE);
        }
        
        setupAiViews(view);
    }

    private void setupAiViews(View view) {
        mAiAnalysis = view.findViewById(R.id.ai_analysis);
        mAiSummary = view.findViewById(R.id.ai_summary);
        mAiScore = view.findViewById(R.id.ai_score);
        mAiComment = view.findViewById(R.id.ai_comment);
        mAiTags = view.findViewById(R.id.ai_tags);
        mAiPagesToggle = view.findViewById(R.id.ai_pages_toggle);
        mAiPagesContainer = view.findViewById(R.id.ai_pages_container);
        mAiAnalyzeButton = view.findViewById(R.id.ai_analyze_button);

        if (mAiPagesToggle != null) {
            mAiPagesToggle.setOnClickListener(v -> toggleAiPages());
        }
        if (mAiAnalyzeButton != null) {
            mAiAnalyzeButton.setOnClickListener(v -> startAiAnalysis());
        }

        bindAiAnalysis();
    }

    private AiGalleryAnalysis readAiAnalysis() {
        if (mGalleryInfo == null || mGalleryInfo.path == null) {
            return null;
        }
        try {
            UniFile dir = UniFile.fromFile(new File(mGalleryInfo.path));
            return GalleryAiCacheManager.getInstance(requireContext()).readAnalysisFromDir(dir);
        } catch (Exception e) {
            return null;
        }
    }

    private void bindAiAnalysis() {
        if (mAiAnalysis == null) {
            return;
        }
        new Thread(() -> {
            final AiGalleryAnalysis analysis = readAiAnalysis();
            if (getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> applyAiAnalysis(analysis));
        }).start();
    }

    private void applyAiAnalysis(AiGalleryAnalysis analysis) {
        if (mAiAnalysis == null) {
            return;
        }
        if (analysis != null && (analysis.hasGalleryAnalysis() || analysis.hasPageAnalysis())) {
            showAiAnalysis(analysis);
        } else {
            // 无分析结果：仅显示"开始分析"按钮
            setAnalysisTextViewsGone();
            if (mAiAnalyzeButton != null) {
                mAiAnalyzeButton.setVisibility(View.VISIBLE);
            }
            mAiAnalysis.setVisibility(View.VISIBLE);
        }
    }

    private void setAnalysisTextViewsGone() {
        if (mAiSummary != null) {
            mAiSummary.setVisibility(View.GONE);
        }
        if (mAiScore != null) {
            mAiScore.setVisibility(View.GONE);
        }
        if (mAiComment != null) {
            mAiComment.setVisibility(View.GONE);
        }
        if (mAiTags != null) {
            mAiTags.setVisibility(View.GONE);
        }
        if (mAiPagesToggle != null) {
            mAiPagesToggle.setVisibility(View.GONE);
        }
        if (mAiPagesContainer != null) {
            mAiPagesContainer.removeAllViews();
            mAiPagesContainer.setVisibility(View.GONE);
        }
    }

    private void showAiAnalysis(AiGalleryAnalysis analysis) {
        if (mAiAnalysis == null) {
            return;
        }
        mAiAnalysis.setVisibility(View.VISIBLE);
        if (mAiAnalyzeButton != null) {
            mAiAnalyzeButton.setVisibility(View.GONE);
        }

        if (analysis.hasGalleryAnalysis()) {
            if (mAiSummary != null) {
                mAiSummary.setVisibility(View.VISIBLE);
                mAiSummary.setText(analysis.summary);
            }
            if (mAiScore != null) {
                mAiScore.setVisibility(View.VISIBLE);
                mAiScore.setText(analysis.aestheticScore >= 0
                        ? getString(R.string.ai_analysis_score, String.format(Locale.US, "%.1f", analysis.aestheticScore))
                        : getString(R.string.ai_analysis_score_na));
            }
            if (mAiComment != null) {
                mAiComment.setVisibility(View.VISIBLE);
                mAiComment.setText(analysis.aestheticComment);
            }
            if (mAiTags != null && analysis.tags != null && !analysis.tags.isEmpty()) {
                mAiTags.setVisibility(View.VISIBLE);
                mAiTags.setText(joinTags(analysis.tags));
            } else if (mAiTags != null) {
                mAiTags.setVisibility(View.GONE);
            }
        } else {
            if (mAiSummary != null) {
                mAiSummary.setVisibility(View.GONE);
            }
            if (mAiScore != null) {
                mAiScore.setVisibility(View.GONE);
            }
            if (mAiComment != null) {
                mAiComment.setVisibility(View.GONE);
            }
            if (mAiTags != null) {
                mAiTags.setVisibility(View.GONE);
            }
        }

        if (mAiPagesContainer != null) {
            mAiPagesContainer.removeAllViews();
        }
        if (analysis.hasPageAnalysis()) {
            if (mAiPagesToggle != null) {
                mAiPagesToggle.setVisibility(View.VISIBLE);
                mAiPagesToggle.setText(R.string.ai_analysis_page_details);
            }
        } else {
            if (mAiPagesToggle != null) {
                mAiPagesToggle.setVisibility(View.GONE);
            }
        }
    }

    private void toggleAiPages() {
        if (mAiPagesContainer == null) {
            return;
        }
        boolean visible = mAiPagesContainer.getVisibility() == View.VISIBLE;
        if (visible) {
            mAiPagesContainer.setVisibility(View.GONE);
            if (mAiPagesToggle != null) {
                mAiPagesToggle.setText(R.string.ai_analysis_page_details);
            }
            return;
        }

        AiGalleryAnalysis analysis = readAiAnalysis();
        mAiPagesContainer.removeAllViews();
        if (analysis == null || analysis.pages == null) {
            return;
        }
        LayoutInflater inflater = getLayoutInflater();
        for (AiPageAnalysis page : analysis.pages) {
            View item = inflater.inflate(R.layout.item_page_analysis, mAiPagesContainer, false);
            TextView pageLabel = item.findViewById(R.id.page_label);
            TextView pageDescription = item.findViewById(R.id.page_description);
            TextView pageTags = item.findViewById(R.id.page_tags);
            TextView pageScore = item.findViewById(R.id.page_score);
            pageLabel.setText(getString(R.string.ai_analysis_page_label, page.pageIndex + 1));
            if (page.description != null && !page.description.isEmpty()) {
                pageDescription.setText(page.description);
                pageDescription.setVisibility(View.VISIBLE);
            } else {
                pageDescription.setVisibility(View.GONE);
            }
            if (page.tags != null && !page.tags.isEmpty()) {
                pageTags.setText(joinTags(page.tags));
                pageTags.setVisibility(View.VISIBLE);
            } else {
                pageTags.setVisibility(View.GONE);
            }
            if (page.aestheticScore >= 0) {
                pageScore.setText(getString(R.string.ai_analysis_score,
                        String.format(Locale.US, "%.1f", page.aestheticScore)));
                pageScore.setVisibility(View.VISIBLE);
            } else {
                pageScore.setVisibility(View.GONE);
            }
            mAiPagesContainer.addView(item);
        }
        mAiPagesContainer.setVisibility(View.VISIBLE);
        if (mAiPagesToggle != null) {
            mAiPagesToggle.setText(R.string.ai_analysis_page_details_collapse);
        }
    }

    private void startAiAnalysis() {
        if (mGalleryInfo == null) {
            return;
        }
        if (!Settings.getAiAnalyzeEnabled()) {
            Toast.makeText(requireContext(), R.string.ai_analysis_not_enabled, Toast.LENGTH_LONG).show();
            return;
        }

        long gid;
        try {
            gid = Long.parseLong(mGalleryInfo.gid);
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.ai_analysis_no_download, Toast.LENGTH_LONG).show();
            return;
        }

        DownloadManager dm = EhApplication.getDownloadManager(requireContext());
        com.hippo.ehviewer.dao.DownloadInfo downloadInfo = dm != null ? dm.getDownloadInfo(gid) : null;
        if (downloadInfo == null) {
            Toast.makeText(requireContext(), R.string.ai_analysis_no_download, Toast.LENGTH_LONG).show();
            return;
        }

        android.app.ProgressDialog dialog = new android.app.ProgressDialog(requireContext());
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        dialog.setMessage(getString(R.string.ai_analysis_starting));
        dialog.setCancelable(false);
        dialog.setMax(100);
        dialog.show();

        AiAnalyzeManager.getInstance().analyzeGallery(downloadInfo, new AiAnalyzeManager.AnalyzeCallback() {
            @Override
            public void onProgress(int current, int total, String detail) {
                if (dialog != null && total > 0) {
                    dialog.setProgress(current * 100 / total);
                    dialog.setMessage(getString(R.string.ai_analysis_progress, current, total, detail));
                }
            }

            @Override
            public void onSuccess(AiGalleryAnalysis analysis) {
                if (dialog != null) {
                    dialog.dismiss();
                }
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        bindAiAnalysis();
                        Toast.makeText(requireContext(), R.string.ai_analysis_done, Toast.LENGTH_SHORT).show();
                    });
                }
            }

            @Override
            public void onError(String error) {
                if (dialog != null) {
                    dialog.dismiss();
                }
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> Toast.makeText(requireContext(),
                            getString(R.string.ai_analysis_failed) + ": " + error, Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    private String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String tag : tags) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(tag);
        }
        return sb.toString();
    }
    
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.getDefault(), "%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
}