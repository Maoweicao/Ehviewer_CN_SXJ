package com.hippo.ehviewer.ui.lab;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.lab.TrustedPeer;
import com.hippo.ehviewer.lab.TrustedPeerStore;

import java.util.ArrayList;
import java.util.List;

public class TrustedPeerAdapter extends RecyclerView.Adapter<TrustedPeerAdapter.PeerViewHolder> {

    private final List<TrustedPeer> peers = new ArrayList<>();

    public interface OnPeerActionListener {
        void onPeerDelete(TrustedPeer peer);

        void onPeerEdit(TrustedPeer peer);
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
        Context context = holder.itemView.getContext();
        TrustedPeerStore store = TrustedPeerStore.getInstance(context);

        holder.name.setText(peer.getDeviceName());

        boolean paired = peer.getPresharedKey() != null && !peer.getPresharedKey().isEmpty();
        boolean online = store.isEffectivelyOnline(peer);

        int onlineColor = online
                ? context.getColor(R.color.peer_online)
                : context.getColor(R.color.peer_offline);
        holder.online.setText(online
                ? context.getString(R.string.multi_device_online)
                : context.getString(R.string.multi_device_offline));
        holder.online.setTextColor(onlineColor);

        holder.badge.setVisibility(paired ? View.GONE : View.VISIBLE);
        holder.badge.setText(R.string.multi_device_unpaired);

        holder.id.setText(peer.getDeviceId());

        StringBuilder info = new StringBuilder();
        if (peer.getHost() != null && !peer.getHost().isEmpty()) {
            info.append(peer.getHost());
            if (peer.getPort() > 0) info.append(':').append(peer.getPort());
            info.append("  |  ");
        }
        info.append(peer.getDeviceType());
        if (peer.getRttMs() >= 0 && online) {
            info.append("  |  ").append(context
                    .getString(R.string.multi_device_rtt, peer.getRttMs()));
        }
        if (!online && peer.getLastSeen() > 0) {
            CharSequence lastSeen = DateUtils.getRelativeTimeSpanString(
                    peer.getLastSeen(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            info.append("  |  ").append(context
                    .getString(R.string.multi_device_last_seen, lastSeen));
        }
        holder.info.setText(info.toString());

        StringBuilder caps = new StringBuilder();
        for (String c : peer.getCapabilities()) {
            if (caps.length() > 0) caps.append(" · ");
            caps.append(c);
        }
        holder.caps.setText(caps.length() > 0
                ? context.getString(R.string.multi_device_caps_prefix) + caps
                : "");

        final TrustedPeer current = peer;
        holder.delete.setOnClickListener(v -> {
            if (listener != null) listener.onPeerDelete(current);
        });
        holder.edit.setOnClickListener(v -> {
            if (listener != null) listener.onPeerEdit(current);
        });
    }

    @Override
    public int getItemCount() {
        return peers.size();
    }

    static class PeerViewHolder extends RecyclerView.ViewHolder {
        AppCompatTextView name;
        AppCompatTextView badge;
        AppCompatTextView online;
        AppCompatTextView id;
        AppCompatTextView info;
        AppCompatTextView caps;
        Button edit;
        Button delete;

        PeerViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.peer_name);
            badge = itemView.findViewById(R.id.peer_badge);
            online = itemView.findViewById(R.id.peer_online);
            id = itemView.findViewById(R.id.peer_id);
            info = itemView.findViewById(R.id.peer_info);
            caps = itemView.findViewById(R.id.peer_caps);
            edit = itemView.findViewById(R.id.btn_edit);
            delete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
