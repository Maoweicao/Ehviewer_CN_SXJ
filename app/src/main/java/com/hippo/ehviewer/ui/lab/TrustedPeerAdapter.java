package com.hippo.ehviewer.ui.lab;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.lab.TrustedPeer;

import java.util.ArrayList;
import java.util.List;

public class TrustedPeerAdapter extends RecyclerView.Adapter<TrustedPeerAdapter.PeerViewHolder> {

    private final List<TrustedPeer> peers = new ArrayList<>();

    public interface OnPeerActionListener {
        void onPeerDelete(TrustedPeer peer);
    }

    private OnPeerActionListener listener;

    public void setOnPeerActionListener(OnPeerActionListener listener) {
        this.listener = listener;
    }

    public void setPeers(List<TrustedPeer> list) {
        peers.clear();
        if (list != null) peers.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PeerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_trusted_peer, parent, false);
        return new PeerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PeerViewHolder holder, int position) {
        TrustedPeer peer = peers.get(position);
        holder.name.setText(peer.getDeviceName());

        boolean online = peer.getOnline();
        int onlineColor = online
                ? holder.itemView.getContext().getColor(R.color.peer_online)
                : holder.itemView.getContext().getColor(R.color.peer_offline);
        holder.online.setText(online
                ? holder.itemView.getContext().getString(R.string.multi_device_online)
                : holder.itemView.getContext().getString(R.string.multi_device_offline));
        holder.online.setTextColor(onlineColor);

        holder.id.setText(peer.getDeviceId());

        StringBuilder info = new StringBuilder();
        if (peer.getHost() != null && !peer.getHost().isEmpty()) {
            info.append(peer.getHost());
            if (peer.getPort() > 0) info.append(':').append(peer.getPort());
            info.append("  |  ");
        }
        info.append(peer.getDeviceType());
        if (peer.getRttMs() >= 0) {
            info.append("  |  ").append(holder.itemView.getContext()
                    .getString(R.string.multi_device_rtt, peer.getRttMs()));
        }
        holder.info.setText(info.toString());

        StringBuilder caps = new StringBuilder();
        for (String c : peer.getCapabilities()) {
            if (caps.length() > 0) caps.append(" · ");
            caps.append(c);
        }
        holder.caps.setText(caps.length() > 0
                ? holder.itemView.getContext().getString(R.string.multi_device_caps_prefix) + caps
                : "");

        final TrustedPeer current = peer;
        holder.delete.setOnClickListener(v -> {
            if (listener != null) listener.onPeerDelete(current);
        });
    }

    @Override
    public int getItemCount() {
        return peers.size();
    }

    static class PeerViewHolder extends RecyclerView.ViewHolder {
        AppCompatTextView name;
        AppCompatTextView online;
        AppCompatTextView id;
        AppCompatTextView info;
        AppCompatTextView caps;
        Button delete;

        PeerViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.peer_name);
            online = itemView.findViewById(R.id.peer_online);
            id = itemView.findViewById(R.id.peer_id);
            info = itemView.findViewById(R.id.peer_info);
            caps = itemView.findViewById(R.id.peer_caps);
            delete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
