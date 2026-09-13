/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.widget;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RatingBar;
import android.widget.Spinner;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.util.SearchDebugLog;
import com.hippo.lib.yorozuya.NumberUtils;

public class AdvanceSearchTable extends LinearLayout {

    private static final String STATE_KEY_SUPER = "super";
    private static final String STATE_KEY_ADVANCE_SEARCH = "advance_search";
    private static final String STATE_KEY_MIN_RATING = "min_rating";
    private static final String STATE_KEY_PAGE_FROM = "page_from";
    private static final String STATE_KEY_PAGE_TO = "page_to";
    private static final String STATE_KEY_RATING_RANGE_ENABLED = "rating_range_enabled";
    private static final String STATE_KEY_RATING_RANGE_FROM = "rating_range_from";
    private static final String STATE_KEY_RATING_RANGE_TO = "rating_range_to";

    public static final int SNAME = 0x1;
    public static final int STAGS = 0x2;
    public static final int SDESC = 0x4;
    public static final int STORR = 0x8;
    public static final int STO = 0x10;
    public static final int SDT1 = 0x20;
    public static final int SDT2 = 0x40;
    public static final int SH = 0x80;
    public static final int SFL = 0x100;
    public static final int SFU = 0x200;
    public static final int SFT = 0x400;

    private CheckBox mSname;
    private CheckBox mStags;
    private CheckBox mSdesc;
    private CheckBox mStorr;
    private CheckBox mSto;
    private CheckBox mSdt1;
    private CheckBox mSdt2;
    private CheckBox mSh;
    private CheckBox mSr;
    private Spinner mMinRating;
    private CheckBox mSp;
    private EditText mSpf;
    private EditText mSpt;
    private CheckBox mSfl;
    private CheckBox mSfu;
    private CheckBox mSft;

    // Rating range filter (for download search)
    private CheckBox mEnableRatingRange;
    private RatingBar mRatingFromBar;
    private RatingBar mRatingToBar;
    private float mRatingRangeFrom = 0f;
    private float mRatingRangeTo = 5f;

    public AdvanceSearchTable(Context context) {
        super(context);
        init();
    }

    public AdvanceSearchTable(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void init() {
        setOrientation(LinearLayout.VERTICAL);

        LayoutInflater inflater = LayoutInflater.from(getContext());
         inflater.inflate(R.layout.widget_advance_search_table, this);

//        ViewGroup row0 = (ViewGroup) getChildAt(0);
//        mSname = (CheckBox) row0.getChildAt(0);
//        mStags = (CheckBox) row0.getChildAt(1);
        mSname = (CheckBox)findViewById(R.id.search_gallery);

        mStags = (CheckBox) findViewById(R.id.search_gallery_tags);

//        ViewGroup row1 = (ViewGroup) getChildAt(1);
//        mSdesc = (CheckBox) row1.getChildAt(0);
//        mStorr = (CheckBox) row1.getChildAt(1);
        mSdesc = (CheckBox) findViewById(R.id.search_gallery_description);

        mStorr = (CheckBox) findViewById(R.id.search_torrent_filenames);

//        ViewGroup row2 = (ViewGroup) getChildAt(2);
//        mSto = (CheckBox) row2.getChildAt(0);
//        mSdt1 = (CheckBox) row2.getChildAt(1);
        mSto = (CheckBox) findViewById(R.id.only_show_galleries_with_torrents);
        mSdt1 = (CheckBox) findViewById(R.id.search_low_power_tags);

//        ViewGroup row3 = (ViewGroup) getChildAt(3);
//        mSdt2 = (CheckBox) row3.getChildAt(0);
//        mSh = (CheckBox) row3.getChildAt(1);
        mSdt2 = (CheckBox) findViewById(R.id.search_downvoted_tags);
        mSh = (CheckBox) findViewById(R.id.search_expunged_galleries);

//        ViewGroup row4 = (ViewGroup) getChildAt(4);
//        mSr = (CheckBox) row4.getChildAt(0);
//        mMinRating = (Spinner) row4.getChildAt(1);
        mSr = (CheckBox) findViewById(R.id.minimum_rating);
        mMinRating = (Spinner)findViewById(R.id.search_min_rating);

//        ViewGroup row5 = (ViewGroup) getChildAt(5);
//        mSp = (CheckBox) row5.getChildAt(0);
//        mSpf = (EditText) row5.getChildAt(1);
//        mSpt = (EditText) row5.getChildAt(3);
        mSp = (CheckBox) findViewById(R.id.pages_setting);
        mSpf = (EditText) findViewById(R.id.spf);
        mSpt = (EditText) findViewById(R.id.spt);

//        ViewGroup row7 = (ViewGroup) getChildAt(7);
//        mSfl = (CheckBox) row7.getChildAt(0);
//        mSfu = (CheckBox) row7.getChildAt(1);
//        mSft = (CheckBox) row7.getChildAt(2);
        mSfl = (CheckBox) findViewById(R.id.disable_default_filter_language);
        mSfu = (CheckBox) findViewById(R.id.disable_default_filter_uploader);
        mSft = (CheckBox) findViewById(R.id.disable_default_filter_tags);

        // Rating range filter (optional, may not exist in layout)
        mEnableRatingRange = findViewById(R.id.enable_rating_range_filter);
        mRatingFromBar = findViewById(R.id.rating_range_from_bar);
        mRatingToBar = findViewById(R.id.rating_range_to_bar);

        // Setup rating range bars if they exist
        if (mEnableRatingRange != null && mRatingFromBar != null && mRatingToBar != null) {
            mRatingFromBar.setNumStars(5);
            mRatingFromBar.setMax(10);
            mRatingFromBar.setStepSize(1);
            mRatingFromBar.setRating(0);

            mRatingToBar.setNumStars(5);
            mRatingToBar.setMax(10);
            mRatingToBar.setStepSize(1);
            mRatingToBar.setRating(10);

            mEnableRatingRange.setOnCheckedChangeListener((buttonView, isChecked) -> {
                updateRatingBarsEnabled();
            });

            RatingBar.OnRatingBarChangeListener ratingListener = (bar, rating, fromUser) -> {
                if (fromUser) {
                    if (bar == mRatingFromBar) {
                        mRatingRangeFrom = rating / 2f;
                        if (mRatingRangeFrom > mRatingRangeTo) {
                            mRatingRangeTo = mRatingRangeFrom;
                            mRatingToBar.setRating(mRatingRangeTo * 2);
                        }
                    } else if (bar == mRatingToBar) {
                        mRatingRangeTo = rating / 2f;
                        if (mRatingRangeTo < mRatingRangeFrom) {
                            mRatingRangeFrom = mRatingRangeTo;
                            mRatingFromBar.setRating(mRatingRangeFrom * 2);
                        }
                    }
                }
            };
            mRatingFromBar.setOnRatingBarChangeListener(ratingListener);
            mRatingToBar.setOnRatingBarChangeListener(ratingListener);

            updateRatingBarsEnabled();
        }

        // Avoid java.lang.IllegalStateException: focus search returned a view that wasn't able to take focus!
        mSpt.setOnEditorActionListener((v, actionId, event) -> {
            View nextView = v.focusSearch(View.FOCUS_DOWN);
            if (nextView != null) {
                nextView.requestFocus(View.FOCUS_DOWN);
            }
            return true;
        });
    }

    private void updateRatingBarsEnabled() {
        if (mEnableRatingRange != null && mRatingFromBar != null && mRatingToBar != null) {
            boolean enabled = mEnableRatingRange.isChecked();
            mRatingFromBar.setEnabled(enabled);
            mRatingToBar.setEnabled(enabled);
        }
    }

    public int getAdvanceSearch() {
        int advanceSearch = 0;
        if (mSname.isChecked()) advanceSearch |= SNAME;
        if (mStags.isChecked()) advanceSearch |= STAGS;
        if (mSdesc.isChecked()) advanceSearch |= SDESC;
        if (mStorr.isChecked()) advanceSearch |= STORR;
        if (mSto.isChecked()) advanceSearch |= STO;
        if (mSdt1.isChecked()) advanceSearch |= SDT1;
        if (mSdt2.isChecked()) advanceSearch |= SDT2;
        if (mSh.isChecked()) advanceSearch |= SH;
        if (mSfl.isChecked()) advanceSearch |= SFL;
        if (mSfu.isChecked()) advanceSearch |= SFU;
        if (mSft.isChecked()) advanceSearch |= SFT;
        SearchDebugLog.d("GallerySearch", "getAdvanceSearch() -> 0x" + Integer.toHexString(advanceSearch)
                + "  [advsearch=1" + (advanceSearch != 0 ? "&" + flagsToString(advanceSearch) : "") + "]");
        return advanceSearch;
    }

    private static String flagsToString(int flags) {
        StringBuilder sb = new StringBuilder();
        if ((flags & SNAME) != 0) appendFlag(sb, "f_sname");
        if ((flags & STAGS) != 0) appendFlag(sb, "f_stags");
        if ((flags & SDESC) != 0) appendFlag(sb, "f_sdesc");
        if ((flags & STORR) != 0) appendFlag(sb, "f_storr");
        if ((flags & STO) != 0) appendFlag(sb, "f_sto");
        if ((flags & SDT1) != 0) appendFlag(sb, "f_sdt1");
        if ((flags & SDT2) != 0) appendFlag(sb, "f_sdt2");
        if ((flags & SH) != 0) appendFlag(sb, "f_sh");
        if ((flags & SFL) != 0) appendFlag(sb, "f_sfl");
        if ((flags & SFU) != 0) appendFlag(sb, "f_sfu");
        if ((flags & SFT) != 0) appendFlag(sb, "f_sft");
        return sb.toString();
    }

    private static void appendFlag(StringBuilder sb, String flag) {
        if (sb.length() > 0) sb.append('&');
        sb.append(flag).append("=on");
    }

    public int getMinRating() {
        int position = mMinRating.getSelectedItemPosition();
        if (mSr.isChecked() && position >= 0) {
            int rating = position + 2;
            SearchDebugLog.d("GallerySearch", "minRating=" + rating + "  [f_sr=on&f_srdd=" + rating + "]");
            return rating;
        } else {
            return -1;
        }
    }

    public int getPageFrom() {
        if (mSp.isChecked()) {
            int from = NumberUtils.parseIntSafely(mSpf.getText().toString(), -1);
            SearchDebugLog.d("GallerySearch", "pageFrom=" + from + "  [f_sp=on&f_spf=" + from + "]");
            return from;
        }
        return -1;
    }

    public int getPageTo() {
        if (mSp.isChecked()) {
            int to = NumberUtils.parseIntSafely(mSpt.getText().toString(), -1);
            SearchDebugLog.d("GallerySearch", "pageTo=" + to + "  [f_sp=on&f_spt=" + to + "]");
            return to;
        }
        return -1;
    }

    public void setAdvanceSearch(int advanceSearch) {
        mSname.setChecked(NumberUtils.int2boolean(advanceSearch & SNAME));
        mStags.setChecked(NumberUtils.int2boolean(advanceSearch & STAGS));
        mSdesc.setChecked(NumberUtils.int2boolean(advanceSearch & SDESC));
        mStorr.setChecked(NumberUtils.int2boolean(advanceSearch & STORR));
        mSto.setChecked(NumberUtils.int2boolean(advanceSearch & STO));
        mSdt1.setChecked(NumberUtils.int2boolean(advanceSearch & SDT1));
        mSdt2.setChecked(NumberUtils.int2boolean(advanceSearch & SDT2));
        mSh.setChecked(NumberUtils.int2boolean(advanceSearch & SH));
        mSfl.setChecked(NumberUtils.int2boolean(advanceSearch & SFL));
        mSfu.setChecked(NumberUtils.int2boolean(advanceSearch & SFU));
        mSft.setChecked(NumberUtils.int2boolean(advanceSearch & SFT));
    }

    public void setMinRating(int minRating) {
        if (minRating >= 2 && minRating <= 5) {
            mSr.setChecked(true);
            mMinRating.setSelection(minRating - 2);
        } else {
            mSr.setChecked(false);
        }
    }

    public void setPageFrom(int pageFrom) {
        if (pageFrom > 0) {
//            mSpf.setText(Integer.toString(pageFrom));
            String setS = Integer.toString(pageFrom);
            mSpf.setText(setS);
            mSp.setChecked(true);
        } else {
            mSp.setChecked(false);
            mSpf.setText(null);
        }
    }

    public void setPageTo(int pageTo) {
        if (pageTo > 0) {
//            mSpt.setText(Integer.toString(pageTo));
            String setS = Integer.toString(pageTo);
            mSpt.setText(setS);
            mSp.setChecked(true);
        } else {
            mSp.setChecked(false);
            mSpt.setText(null);
        }
    }

    // Rating range methods (for download search)
    public boolean isRatingRangeEnabled() {
        return mEnableRatingRange != null && mEnableRatingRange.isChecked();
    }

    public void setRatingRangeEnabled(boolean enabled) {
        if (mEnableRatingRange != null) {
            mEnableRatingRange.setChecked(enabled);
            updateRatingBarsEnabled();
        }
    }

    public float getRatingRangeFrom() {
        return mRatingRangeFrom;
    }

    public float getRatingRangeTo() {
        return mRatingRangeTo;
    }

    public void setRatingRange(float from, float to) {
        mRatingRangeFrom = Math.max(0f, Math.min(5f, from));
        mRatingRangeTo = Math.max(0f, Math.min(5f, to));
        if (mRatingFromBar != null) {
            mRatingFromBar.setRating(mRatingRangeFrom * 2);
        }
        if (mRatingToBar != null) {
            mRatingToBar.setRating(mRatingRangeTo * 2);
        }
    }

    // ---------------------------------------------------------------------
    // 标签组检索功能已迁移至 SearchBar 高级关键词编辑器，此处不再保留。
    // ---------------------------------------------------------------------

    public void reset() {
        setAdvanceSearch(0);
        setMinRating(-1);
        setPageFrom(-1);
        setPageTo(-1);
        if (mEnableRatingRange != null) {
            mEnableRatingRange.setChecked(false);
            updateRatingBarsEnabled();
        }
        mRatingRangeFrom = 0f;
        mRatingRangeTo = 5f;
        if (mRatingFromBar != null) {
            mRatingFromBar.setRating(0);
        }
        if (mRatingToBar != null) {
            mRatingToBar.setRating(10);
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        final Bundle state = new Bundle();
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState());
        state.putInt(STATE_KEY_ADVANCE_SEARCH, getAdvanceSearch());
        state.putInt(STATE_KEY_MIN_RATING, getMinRating());
        state.putInt(STATE_KEY_PAGE_FROM, getPageFrom());
        state.putInt(STATE_KEY_PAGE_TO, getPageTo());
        if (mEnableRatingRange != null) {
            state.putBoolean(STATE_KEY_RATING_RANGE_ENABLED, mEnableRatingRange.isChecked());
            state.putFloat(STATE_KEY_RATING_RANGE_FROM, mRatingRangeFrom);
            state.putFloat(STATE_KEY_RATING_RANGE_TO, mRatingRangeTo);
        }
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            final Bundle savedState = (Bundle) state;
            super.onRestoreInstanceState(savedState.getParcelable(STATE_KEY_SUPER));
            setAdvanceSearch(savedState.getInt(STATE_KEY_ADVANCE_SEARCH));
            setMinRating(savedState.getInt(STATE_KEY_MIN_RATING));
            setPageFrom(savedState.getInt(STATE_KEY_PAGE_FROM));
            setPageTo(savedState.getInt(STATE_KEY_PAGE_TO));
            if (mEnableRatingRange != null && savedState.containsKey(STATE_KEY_RATING_RANGE_ENABLED)) {
                mEnableRatingRange.setChecked(savedState.getBoolean(STATE_KEY_RATING_RANGE_ENABLED));
                mRatingRangeFrom = savedState.getFloat(STATE_KEY_RATING_RANGE_FROM, 0f);
                mRatingRangeTo = savedState.getFloat(STATE_KEY_RATING_RANGE_TO, 5f);
                if (mRatingFromBar != null) {
                    mRatingFromBar.setRating(mRatingRangeFrom * 2);
                }
                if (mRatingToBar != null) {
                    mRatingToBar.setRating(mRatingRangeTo * 2);
                }
                updateRatingBarsEnabled();
            }
        }
    }
}
