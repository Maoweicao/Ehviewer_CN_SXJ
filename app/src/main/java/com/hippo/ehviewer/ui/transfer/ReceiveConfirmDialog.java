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

package com.hippo.ehviewer.ui.transfer;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.transfer.data.PushTask;

/**
 * 接收确认对话框
 * 前台弹窗确认
 */
public class ReceiveConfirmDialog extends DialogFragment {

    private static final String ARG_TASK_ID = "task_id";
    private static final String ARG_SOURCE_DEVICE = "source_device";
    private static final String ARG_DATA_TYPE = "data_type";
    private static final String ARG_DATA_COUNT = "data_count";

    private String taskId;
    private String sourceDevice;
    private String dataType;
    private int dataCount;

    private OnReceiveConfirmListener listener;

    public static ReceiveConfirmDialog newInstance(PushTask task) {
        ReceiveConfirmDialog dialog = new ReceiveConfirmDialog();
        Bundle args = new Bundle();
        args.putString(ARG_TASK_ID, task.getTaskId());
        args.putString(ARG_SOURCE_DEVICE, task.getSourceDevice());
        args.putString(ARG_DATA_TYPE, task.getType());
        args.putInt(ARG_DATA_COUNT, task.getTotalCount());
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnReceiveConfirmListener) {
            listener = (OnReceiveConfirmListener) context;
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        if (getArguments() != null) {
            taskId = getArguments().getString(ARG_TASK_ID);
            sourceDevice = getArguments().getString(ARG_SOURCE_DEVICE);
            dataType = getArguments().getString(ARG_DATA_TYPE);
            dataCount = getArguments().getInt(ARG_DATA_COUNT);
        }

        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_receive_confirm, null);

        // 初始化视图
        TextView messageText = view.findViewById(R.id.message_text);
        TextView dataTypeText = view.findViewById(R.id.data_type_text);
        TextView sourceDeviceText = view.findViewById(R.id.source_device_text);
        TextView dataCountText = view.findViewById(R.id.data_count_text);
        Button acceptButton = view.findViewById(R.id.accept_button);
        Button rejectButton = view.findViewById(R.id.reject_button);

        // 设置数据
        messageText.setText(getString(R.string.receive_confirm_message,
                sourceDevice, dataCount, getTypeDisplayName(dataType)));
        dataTypeText.setText(getTypeDisplayName(dataType));
        sourceDeviceText.setText(sourceDevice);
        dataCountText.setText(String.valueOf(dataCount));

        // 创建对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setView(view);

        AlertDialog dialog = builder.create();

        // 设置按钮点击
        acceptButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onReceiveConfirmed(taskId, true);
            }
            dialog.dismiss();
        });

        rejectButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onReceiveConfirmed(taskId, false);
            }
            dialog.dismiss();
        });

        return dialog;
    }

    private String getTypeDisplayName(String type) {
        switch (type) {
            case PushTask.TYPE_BOOKMARKS:
                return getString(R.string.bookmarks);
            case PushTask.TYPE_DOWNLOADS:
                return getString(R.string.downloads);
            case PushTask.TYPE_FAVORITES:
                return getString(R.string.favorites);
            default:
                return type;
        }
    }

    /**
     * 接收确认监听器
     */
    public interface OnReceiveConfirmListener {
        void onReceiveConfirmed(String taskId, boolean accepted);
    }
}
