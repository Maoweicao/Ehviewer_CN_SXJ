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

package com.hippo.ehviewer.ui.transfer.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.transfer.data.ConnectedDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * 已连接设备列表适配器
 */
public class ConnectedDeviceAdapter extends RecyclerView.Adapter<ConnectedDeviceAdapter.ViewHolder> {

    private Context context;
    private List<ConnectedDevice> devices = new ArrayList<>();
    private OnPushListener pushListener;
    private OnDisconnectListener disconnectListener;

    public ConnectedDeviceAdapter(Context context) {
        this.context = context;
    }

    public void setDevices(List<ConnectedDevice> devices) {
        this.devices.clear();
        this.devices.addAll(devices);
        notifyDataSetChanged();
    }

    public void setOnPushListener(OnPushListener listener) {
        this.pushListener = listener;
    }

    public void setOnDisconnectListener(OnDisconnectListener listener) {
        this.disconnectListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_connected_device, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ConnectedDevice device = devices.get(position);

        holder.name.setText(device.getName());
        holder.duration.setText(device.getHost() + ":" + device.getPort() + " · " + device.getConnectedDuration());

        holder.pushButton.setOnClickListener(v -> {
            if (pushListener != null) {
                pushListener.onPush(device);
            }
        });

        holder.disconnectButton.setOnClickListener(v -> {
            if (disconnectListener != null) {
                disconnectListener.onDisconnect(device);
            }
        });
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name;
        TextView duration;
        Button pushButton;
        Button disconnectButton;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.device_name);
            duration = itemView.findViewById(R.id.device_duration);
            pushButton = itemView.findViewById(R.id.device_push_button);
            disconnectButton = itemView.findViewById(R.id.device_disconnect_button);
        }
    }

    public interface OnPushListener {
        void onPush(ConnectedDevice device);
    }

    public interface OnDisconnectListener {
        void onDisconnect(ConnectedDevice device);
    }
}
