package com.hippo.ehviewer.ui.lab;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「附近设备」列表：展示 mDNS 发现的同一局域网设备。
 *
 * 已配对的设备显示「已配对」并提供「使用」，未配对的显示「配对」。
 */
public class NearbyDeviceAdapter extends RecyclerView.Adapter<NearbyDeviceAdapter.Holder> {

    public interface OnNearbyDeviceListener {
        void onNearbyDevice(DiscoveredDevice device);
    }

    private final List<DiscoveredDevice> devices = new ArrayList<>();
    private final Set<String> pairedAddresses = new HashSet<>();
    private OnNearbyDeviceListener listener;

    public void setOnNearbyDeviceListener(OnNearbyDeviceListener listener) {
        this.listener = listener;
    }

    public void setData(List<DiscoveredDevice> list, Set<String> paired) {
        devices.clear();
        if (list != null) devices.addAll(list);
        pairedAddresses.clear();
        if (paired != null) pairedAddresses.addAll(paired);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_nearby_device, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        DiscoveredDevice device = devices.get(position);
        String address = device.getHost() + ":" + device.getPort();
        boolean paired = pairedAddresses.contains(address);

        holder.name.setText(device.getName());
        holder.address.setText(address);
        holder.state.setText(paired
                ? R.string.multi_device_nearby_paired
                : R.string.multi_device_nearby_unpaired);
        holder.state.setTextColor(holder.itemView.getContext().getColor(
                paired ? R.color.peer_online : R.color.peer_offline));
        holder.action.setText(paired
                ? R.string.multi_device_nearby_use
                : R.string.multi_device_nearby_pair);

        View.OnClickListener click = v -> {
            if (listener != null) listener.onNearbyDevice(device);
        };
        holder.itemView.setOnClickListener(click);
        holder.action.setOnClickListener(click);
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView name;
        TextView address;
        TextView state;
        Button action;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.nearby_name);
            address = itemView.findViewById(R.id.nearby_address);
            state = itemView.findViewById(R.id.nearby_state);
            action = itemView.findViewById(R.id.nearby_action);
        }
    }
}
