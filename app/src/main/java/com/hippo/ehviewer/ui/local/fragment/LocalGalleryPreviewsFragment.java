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

import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.LocalGalleryInfo;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.LocalGalleryInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 本地画廊预览Fragment
 */
public class LocalGalleryPreviewsFragment extends Fragment {
    
    private static final String KEY_GALLERY_INFO = "gallery_info";

    /** 目录枚举/排序放后台，避免打开预览页时在主线程做磁盘 I/O。 */
    private static final java.util.concurrent.ExecutorService IO_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "LocalPreviewScan");
                t.setPriority(Thread.MIN_PRIORITY);
                t.setDaemon(true);
                return t;
            });

    private final android.os.Handler mMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean mDestroyed;
    
    private LocalGalleryInfo mGalleryInfo;
    private RecyclerView mRecyclerView;
    private LocalGalleryPreviewAdapter mAdapter;
    private List<String> mImagePaths;
    
    public static LocalGalleryPreviewsFragment newInstance(LocalGalleryInfo galleryInfo) {
        LocalGalleryPreviewsFragment fragment = new LocalGalleryPreviewsFragment();
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
        View view = inflater.inflate(R.layout.fragment_local_gallery_previews, container, false);
        
        mRecyclerView = view.findViewById(R.id.recycler_view);
        
        if (mGalleryInfo != null) {
            setupRecyclerView();
            loadImagePaths();
        }
        
        return view;
    }
    
    private void setupRecyclerView() {
        mAdapter = new LocalGalleryPreviewAdapter();
        mRecyclerView.setLayoutManager(new GridLayoutManager(getContext(), 3));
        mRecyclerView.setAdapter(mAdapter);
    }
    
    private void loadImagePaths() {
        final String galleryPath = mGalleryInfo != null ? mGalleryInfo.path : null;
        if (galleryPath == null) {
            mImagePaths = new ArrayList<>();
            if (mAdapter != null) {
                mAdapter.setImagePaths(mImagePaths);
            }
            return;
        }

        mDestroyed = false;
        IO_EXECUTOR.execute(() -> {
            List<String> paths = new ArrayList<>();
            File galleryDir = new File(galleryPath);
            if (galleryDir.isDirectory()) {
                File[] files = galleryDir.listFiles();
                if (files != null) {
                    Arrays.sort(files, (f1, f2) -> {
                        String n1 = f1.getName().toLowerCase();
                        String n2 = f2.getName().toLowerCase();
                        return n1.compareTo(n2);
                    });
                    for (File file : files) {
                        if (file.isFile()) {
                            String name = file.getName().toLowerCase();
                            if (name.endsWith(".jpg") || name.endsWith(".jpeg")
                                    || name.endsWith(".png") || name.endsWith(".gif")
                                    || name.endsWith(".webp")) {
                                paths.add(file.getAbsolutePath());
                            }
                        }
                    }
                }
            }
            mMainHandler.post(() -> {
                if (mDestroyed || !isAdded()) {
                    return;
                }
                mImagePaths = paths;
                if (mAdapter != null) {
                    mAdapter.setImagePaths(mImagePaths);
                }
            });
        });
    }

    @Override
    public void onDestroyView() {
        mDestroyed = true;
        super.onDestroyView();
    }
    
    private class LocalGalleryPreviewAdapter extends RecyclerView.Adapter<LocalGalleryPreviewAdapter.PreviewHolder> {
        
        private List<String> mPaths = new ArrayList<>();
        
        public void setImagePaths(List<String> paths) {
            mPaths = paths;
            notifyDataSetChanged();
        }
        
        @NonNull
        @Override
        public PreviewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_local_gallery_preview, parent, false);
            return new PreviewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull PreviewHolder holder, int position) {
            String path = mPaths.get(position);
            holder.bind(path);
        }
        
        @Override
        public int getItemCount() {
            return mPaths.size();
        }
        
        class PreviewHolder extends RecyclerView.ViewHolder {
            private final ImageView thumb;
            private String boundPath;
            
            public PreviewHolder(@NonNull View itemView) {
                super(itemView);
                thumb = itemView.findViewById(R.id.thumb);
            }
            
            public void bind(String path) {
                boundPath = path;
                thumb.setImageDrawable(null);
                // 后台降采样解码，回调校验 holder 是否已被复用
                com.hippo.ehviewer.ui.local.LocalThumbnailLoader.loadImage(path, bitmap -> {
                    if (path.equals(boundPath)) {
                        thumb.setImageBitmap(bitmap);
                    }
                });
            }
        }
    }
}