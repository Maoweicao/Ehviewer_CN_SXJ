package com.hippo.ehviewer.ui.scene.download;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;

import java.util.List;

public class DownloadLabelAdapter extends ArrayAdapter<DownloadLabelItem> {

    private List<DownloadLabelItem> mData;

    private int mSelectedPosition = -1;
    private int mAccentColor = 0;
    private int mDefaultColor = 0;

    public DownloadLabelAdapter(@NonNull Context context, int resource, @NonNull List<DownloadLabelItem> objects) {
        super(context, resource, objects);
        this.mData = objects;
    }

    /**
     * 高亮当前生效的分类（书签分类范围）。
     *
     * @param position    列表位置，-1 表示不高亮
     * @param accentColor 强调色
     */
    public void setSelection(int position, int accentColor) {
        mSelectedPosition = position;
        mAccentColor = accentColor;
        notifyDataSetChanged();
    }

    public void clearSelection() {
        setSelection(-1, 0);
    }

    /**
     * 更新数据并刷新视图
     * @param newData 新的标签列表
     */
    public void updateData(List<DownloadLabelItem> newData) {
        // newData 可能与适配器内部的列表是同一个对象（调用方复用了字段列表），
        // 先复制再 clear，否则会把自己清空成空列表。
        List<DownloadLabelItem> copy = new java.util.ArrayList<>(newData);
        this.mData = copy;
        clear();
        addAll(copy);
        notifyDataSetChanged();
    }


    @SuppressLint("ViewHolder")
    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View view;
        ViewHolder viewHolder;
        DownloadLabelItem item = getItem(position);
        if (convertView==null){
            view = LayoutInflater.from(getContext()).inflate(R.layout.item_download_label_list, parent, false);
            viewHolder = new ViewHolder();
            viewHolder.textView1 = (TextView)view.findViewById(R.id.text1);
            viewHolder.textView2 = (TextView)view.findViewById(R.id.text2);
            view.setTag(viewHolder);
        }else {
            view = convertView;
            viewHolder = (ViewHolder) view.getTag();
        }

        if (mDefaultColor == 0) {
            mDefaultColor = viewHolder.textView1.getCurrentTextColor();
        }
        boolean selected = position == mSelectedPosition;
        viewHolder.textView1.setText(item.label);
        viewHolder.textView2.setText(item.count());
        viewHolder.textView1.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        viewHolder.textView1.setTextColor(selected ? mAccentColor : mDefaultColor);

        return view;
    }

    private static class ViewHolder{
        TextView textView1;
        TextView textView2;
        ViewHolder(){}
    }
}
