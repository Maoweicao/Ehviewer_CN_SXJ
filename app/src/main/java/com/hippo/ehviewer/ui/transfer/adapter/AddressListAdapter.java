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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.transfer.data.NetworkAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * 地址列表适配器
 */
public class AddressListAdapter extends RecyclerView.Adapter<AddressListAdapter.ViewHolder> {

    private Context context;
    private List<NetworkAddress> addresses = new ArrayList<>();
    private int port = 8080;

    public AddressListAdapter(Context context) {
        this.context = context;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setAddresses(List<NetworkAddress> addresses) {
        this.addresses.clear();
        this.addresses.addAll(addresses);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_address, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        NetworkAddress address = addresses.get(position);
        address.setPort(port);

        holder.displayName.setText(address.getDisplayName());
        holder.ipPort.setText(address.getFullAddress());

        holder.copyButton.setOnClickListener(v -> {
            String url = "http://" + address.getFullAddress();
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Address", url);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public int getItemCount() {
        return addresses.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView displayName;
        TextView ipPort;
        Button copyButton;

        ViewHolder(View itemView) {
            super(itemView);
            displayName = itemView.findViewById(R.id.address_display_name);
            ipPort = itemView.findViewById(R.id.address_ip_port);
            copyButton = itemView.findViewById(R.id.address_copy_button);
        }
    }
}
