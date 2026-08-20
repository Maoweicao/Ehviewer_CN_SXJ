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
import com.hippo.ehviewer.transfer.data.DiscoveredDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * 已发现设备列表适配器
 */
public class DiscoveredDeviceAdapter extends RecyclerView.Adapter<DiscoveredDeviceAdapter.ViewHolder> {

    private Context context;
    private List<DiscoveredDevice> devices = new ArrayList<>();
    private OnConnectListener connectListener;

    public DiscoveredDeviceAdapter(Context context) {
        this.context = context;
    }

    public void setDevices(List<DiscoveredDevice> devices) {
        this.devices.clear();
        this.devices.addAll(devices);
        notifyDataSetChanged();
    }

    public void setOnConnectListener(OnConnectListener listener) {
        this.connectListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_discovered_device, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DiscoveredDevice device = devices.get(position);

        holder.name.setText(device.getName());
        holder.address.setText(device.getFullAddress());

        holder.connectButton.setOnClickListener(v -> {
            if (connectListener != null) {
                connectListener.onConnect(device);
            }
        });
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name;
        TextView address;
        Button connectButton;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.device_name);
            address = itemView.findViewById(R.id.device_address);
            connectButton = itemView.findViewById(R.id.device_connect_button);
        }
    }

    public interface OnConnectListener {
        void onConnect(DiscoveredDevice device);
    }
}
