/*
 * Copyright 2026 EhViewer Contributors
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

package com.hippo.ehviewer.preference;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.limit.LimitSession;
import com.hippo.ehviewer.limit.RemoteLimitGuard;
import com.hippo.ehviewer.limit.RemoteLimitStore;
import com.hippo.lib.yorozuya.FileUtils;

import java.util.List;

/**
 * 显示当前限流状态、最近一次下载速率和最近会话记录。
 */
public class DownloadLimitStatusPreference extends Preference {

    public DownloadLimitStatusPreference(Context context) {
        super(context);
    }

    public DownloadLimitStatusPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DownloadLimitStatusPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void onAttachedToHierarchy(@NonNull PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        refreshSummary();
    }

    @Override
    protected void onClick() {
        Context context = getContext();
        if (context == null) return;
        RemoteLimitGuard guard = RemoteLimitGuard.getInstance(context);
        boolean blocked = guard.isBlocked();
        StringBuilder message = new StringBuilder();
        if (blocked) {
            message.append(context.getString(
                    R.string.settings_download_limit_status_blocked,
                    guard.getReason() == null ? "" : guard.getReason(),
                    RemoteLimitGuard.formatRemaining(guard.getRemainingMs())));
        } else {
            message.append(context.getString(R.string.settings_download_limit_status_ok));
        }

        LimitSession last = RemoteLimitStore.getLastSession(context);
        if (last != null) {
            message.append("\n\n")
                    .append(context.getString(R.string.limit_status_last_session))
                    .append("\n")
                    .append(formatSession(context, last));
        }

        List<LimitSession> history = RemoteLimitStore.getHistory(context);
        if (!history.isEmpty()) {
            message.append("\n\n").append(context.getString(R.string.limit_status_history));
            int start = Math.max(0, history.size() - 10);
            for (int i = history.size() - 1; i >= start; i--) {
                message.append("\n").append(formatSession(context, history.get(i)));
            }
        } else {
            message.append("\n\n").append(context.getString(R.string.limit_status_no_history));
        }

        String evidence = guard.getEvidence();
        if (blocked && !TextUtils.isEmpty(evidence)) {
            message.append("\n\n")
                    .append(context.getString(R.string.limit_status_evidence))
                    .append(": ")
                    .append(evidence);
        }

        if (blocked
                || RemoteLimitStore.getForumProbeContent(context) != null
                || RemoteLimitStore.getSettingsProbeContent(context) != null) {
            String lastIp = RemoteLimitStore.getLastIp(context);
            if (!TextUtils.isEmpty(lastIp)) {
                message.append("\n\n")
                        .append(context.getString(R.string.limit_status_ip, lastIp));
            }
            String settingsContent = RemoteLimitStore.getSettingsProbeContent(context);
            if (!TextUtils.isEmpty(settingsContent)) {
                message.append("\n\n")
                        .append(context.getString(R.string.limit_status_settings_probe))
                        .append(": HTTP ")
                        .append(RemoteLimitStore.getSettingsProbeCode(context));
                if (RemoteLimitStore.isSettingsProbeCloudflare(context)) {
                    message.append(context.getString(
                            R.string.limit_status_forum_cloudflare_suffix));
                } else if (RemoteLimitStore.isSettingsProbeBanned(context)) {
                    message.append(context.getString(
                            R.string.limit_status_forum_banned_suffix));
                }
                message.append("\n").append(context.getString(
                        R.string.limit_status_forum_content, truncateProbe(settingsContent)));
            }
            String probeContent = RemoteLimitStore.getForumProbeContent(context);
            if (!TextUtils.isEmpty(probeContent)) {
                if (probeContent.length() > 300) {
                    probeContent = probeContent.substring(0, 300) + "…";
                }
                int probeCode = RemoteLimitStore.getForumProbeCode(context);
                boolean probeCloudflare = RemoteLimitStore.isForumProbeCloudflare(context);
                boolean probeBanned = RemoteLimitStore.isForumProbeBanned(context);
                message.append("\n\n")
                        .append(context.getString(R.string.limit_status_forum_probe))
                        .append(": HTTP ")
                        .append(probeCode);
                if (probeCloudflare) {
                    message.append(context.getString(
                            R.string.limit_status_forum_cloudflare_suffix));
                } else if (probeBanned) {
                    message.append(context.getString(
                            R.string.limit_status_forum_banned_suffix));
                }
                message.append("\n").append(context.getString(
                        R.string.limit_status_forum_content, truncateProbe(probeContent)));
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.limit_status_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.limit_status_close, null);
        if (blocked) {
            builder.setNeutralButton(R.string.limit_status_clear, (dialog, which) -> {
                guard.clearBlock();
                refreshSummary();
                notifyChanged();
                Toast.makeText(context, R.string.limit_status_clear_done,
                        Toast.LENGTH_SHORT).show();
            });
        }
        builder.show();
    }

    private void refreshSummary() {
        Context context = getContext();
        if (context == null) return;
        RemoteLimitGuard guard = RemoteLimitGuard.getInstance(context);
        if (guard.isBlocked()) {
            setSummary(context.getString(
                    R.string.settings_download_limit_status_blocked,
                    guard.getReason() == null ? "" : guard.getReason(),
                    RemoteLimitGuard.formatRemaining(guard.getRemainingMs())));
            return;
        }
        LimitSession last = RemoteLimitStore.getLastSession(context);
        if (last != null) {
            setSummary(context.getString(
                    R.string.settings_download_limit_status_last,
                    formatSpeed(last.getAvgSpeed())));
        } else {
            setSummary(R.string.settings_download_limit_status_ok);
        }
    }

    private static String truncateProbe(String value) {
        if (value == null) return "";
        return value.length() > 300 ? value.substring(0, 300) + "\u2026" : value;
    }

    private static String formatSession(Context context, LimitSession session) {
        String title = session.getTitle();
        String status = stopReasonText(context, session.getStopReason());
        String avg = formatSpeed(session.getAvgSpeed());
        String peak = formatSpeed(session.getPeakSpeed());
        if (!TextUtils.isEmpty(title)) {
            return context.getString(
                    R.string.limit_status_session_line,
                    title, avg, peak, session.getPages(), status);
        }
        return context.getString(
                R.string.limit_status_session_line_no_title,
                session.getGid(), avg, peak, session.getPages(), status);
    }

    private static String stopReasonText(Context context, String reason) {
        if (reason == null) return "";
        switch (reason) {
            case "completed":
                return context.getString(R.string.limit_status_reason_completed);
            case "failed":
                return context.getString(R.string.limit_status_reason_failed);
            case "rate_limited":
                return context.getString(R.string.limit_status_reason_rate_limited);
            case "replaced":
                return context.getString(R.string.limit_status_reason_replaced);
            default:
                return context.getString(R.string.limit_status_reason_stopped);
        }
    }

    private static String formatSpeed(long bytesPerSecond) {
        return FileUtils.humanReadableByteCount(Math.max(0L, bytesPerSecond), false);
    }
}
