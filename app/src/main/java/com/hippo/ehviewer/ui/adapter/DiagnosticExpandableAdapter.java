package com.hippo.ehviewer.ui.adapter;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.TextView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.util.DiagnosticEndpoint;
import com.hippo.ehviewer.util.NetworkDiagnosticTool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DiagnosticExpandableAdapter extends BaseExpandableListAdapter {

    private final Context context;
    private final List<DiagnosticEndpoint.EndpointCategory> categories;
    private final Map<Integer, List<DiagnosticEndpoint.CheckResult>> resultsMap = new HashMap<>();
    private final boolean isExEnabled;

    public DiagnosticExpandableAdapter(Context context,
                                       List<DiagnosticEndpoint.EndpointCategory> categories,
                                       boolean isExEnabled) {
        this.context = context;
        this.categories = categories;
        this.isExEnabled = isExEnabled;
    }

    public void setResult(int groupPosition, int childPosition, DiagnosticEndpoint.CheckResult result) {
        List<DiagnosticEndpoint.CheckResult> list = resultsMap.get(groupPosition);
        if (list == null) {
            list = new ArrayList<>();
            resultsMap.put(groupPosition, list);
        }
        while (list.size() <= childPosition) {
            list.add(null);
        }
        list.set(childPosition, result);
        notifyDataSetChanged();
    }

    public void clearResults() {
        resultsMap.clear();
        notifyDataSetChanged();
    }

    public List<DiagnosticEndpoint.CheckResult> getGroupResults(int groupPosition) {
        return resultsMap.get(groupPosition);
    }

    @Override
    public int getGroupCount() {
        return categories.size();
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        DiagnosticEndpoint.EndpointCategory cat = categories.get(groupPosition);
        if (isExEnabled) {
            return cat.items.size() * 2;
        }
        return cat.items.size();
    }

    @Override
    public DiagnosticEndpoint.EndpointCategory getGroup(int groupPosition) {
        return categories.get(groupPosition);
    }

    @Override
    public DiagnosticEndpoint.EndpointItem getChild(int groupPosition, int childPosition) {
        DiagnosticEndpoint.EndpointCategory cat = categories.get(groupPosition);
        if (isExEnabled) {
            int itemIndex = childPosition / 2;
            return cat.items.get(itemIndex);
        }
        return cat.items.get(childPosition);
    }

    private DiagnosticEndpoint.Site getChildSite(int groupPosition, int childPosition) {
        if (isExEnabled) {
            return childPosition % 2 == 0 ? DiagnosticEndpoint.Site.E : DiagnosticEndpoint.Site.EX;
        }
        return DiagnosticEndpoint.Site.E;
    }

    private DiagnosticEndpoint.CheckResult getChildResult(int groupPosition, int childPosition) {
        List<DiagnosticEndpoint.CheckResult> list = resultsMap.get(groupPosition);
        if (list == null || childPosition >= list.size()) return null;
        return list.get(childPosition);
    }

    @Override
    public long getGroupId(int groupPosition) {
        return groupPosition;
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        return childPosition;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded,
                             View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(
                    R.layout.item_diagnostic_category, parent, false);
        }

        TextView indicator = convertView.findViewById(R.id.group_indicator);
        TextView name = convertView.findViewById(R.id.group_name);
        TextView status = convertView.findViewById(R.id.group_status);

        DiagnosticEndpoint.EndpointCategory cat = getGroup(groupPosition);
        indicator.setText(isExpanded ? "▾" : "▸");
        name.setText(cat.name);

        // Count results
        int total = getChildrenCount(groupPosition);
        int reachable = 0;
        List<DiagnosticEndpoint.CheckResult> results = resultsMap.get(groupPosition);
        if (results != null) {
            for (DiagnosticEndpoint.CheckResult r : results) {
                if (r != null && r.isReachable) reachable++;
            }
        }

        if (reachable == 0 && results == null) {
            status.setText(String.format("%d 项", total));
            status.setTextColor(0xFF757575);
        } else if (reachable == total) {
            status.setText(String.format("%d/%d ✓", reachable, total));
            status.setTextColor(Color.parseColor("#FF4CAF50"));
        } else if (reachable > 0) {
            status.setText(String.format("%d/%d ⚠", reachable, total));
            status.setTextColor(Color.parseColor("#FFFF9800"));
        } else {
            status.setText(String.format("0/%d ✗", total));
            status.setTextColor(Color.parseColor("#FFF44336"));
        }

        return convertView;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition,
                             boolean isLastChild, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(
                    R.layout.item_diagnostic_endpoint, parent, false);
        }

        TextView siteBadge = convertView.findViewById(R.id.site_badge);
        TextView name = convertView.findViewById(R.id.endpoint_name);
        TextView url = convertView.findViewById(R.id.endpoint_url);
        TextView result = convertView.findViewById(R.id.endpoint_result);
        TextView quotaBadge = convertView.findViewById(R.id.quota_badge);

        DiagnosticEndpoint.EndpointItem item = getChild(groupPosition, childPosition);
        DiagnosticEndpoint.Site site = getChildSite(groupPosition, childPosition);

        siteBadge.setText(site == DiagnosticEndpoint.Site.E ? "[E]" : "[EX]");
        siteBadge.setTextColor(site == DiagnosticEndpoint.Site.E ?
                Color.parseColor("#FF4CAF50") : Color.parseColor("#FF9C27B0"));
        siteBadge.getBackground().setAlpha(34);

        name.setText(item.name);
        String resolvedUrl = DiagnosticEndpoint.resolveUrl(item.url, site);
        url.setText(resolvedUrl);

        if (item.consumesQuota) {
            quotaBadge.setVisibility(View.VISIBLE);
            quotaBadge.setText("RED");
        } else {
            quotaBadge.setVisibility(View.GONE);
        }

        DiagnosticEndpoint.CheckResult checkResult = getChildResult(groupPosition, childPosition);
        if (checkResult == null) {
            result.setText("⏳ 等待测试...");
            result.setTextColor(0xFF757575);
        } else if (checkResult.isReachable) {
            String text = String.format("✓ %d (%dms)", checkResult.httpCode, checkResult.totalTimeMs);
            if (checkResult.httpCode >= 200 && checkResult.httpCode < 400) {
                result.setText(text);
                result.setTextColor(Color.parseColor("#FF4CAF50"));
            } else {
                result.setText(text + " (需登录)");
                result.setTextColor(Color.parseColor("#FFFF9800"));
            }
        } else {
            String text = "✗ 不可达";
            if (checkResult.error != null) {
                text += " (" + checkResult.error + ")";
            }
            result.setText(text);
            result.setTextColor(Color.parseColor("#FFF44336"));
        }

        return convertView;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return false;
    }

    public boolean hasAnyResults() {
        return !resultsMap.isEmpty();
    }
}
