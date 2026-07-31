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

package com.hippo.ehviewer.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.XmlRes;

import com.hippo.ehviewer.R;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SettingsSearchIndex {

    public static final class Entry {
        @NonNull public final String sectionTitle;
        @NonNull public final String sectionKey;
        @NonNull public final String title;
        @NonNull public final String summary;
        @Nullable public final String key;
        @NonNull public final Class<?> fragmentClass;
        @NonNull public final String searchableHaystack;

        Entry(@NonNull String sectionTitle, @NonNull String sectionKey,
              @NonNull String title, @NonNull String summary, @Nullable String key,
              @NonNull Class<?> fragmentClass) {
            this.sectionTitle = sectionTitle;
            this.sectionKey = sectionKey;
            this.title = title;
            this.summary = summary;
            this.key = key;
            this.fragmentClass = fragmentClass;
            this.searchableHaystack = buildHaystack(sectionTitle, title, summary, key);
        }
    }

    public static final class Section {
        @NonNull public final String title;
        @NonNull public final String key;
        @NonNull public final Class<?> fragmentClass;
        @NonNull public final List<Entry> entries;

        Section(@NonNull String title, @NonNull String key, @NonNull Class<?> fragmentClass) {
            this.title = title;
            this.key = key;
            this.fragmentClass = fragmentClass;
            this.entries = new ArrayList<>();
        }
    }

    private SettingsSearchIndex() {
    }

    @NonNull
    public static List<Section> buildIndex(@NonNull Context context) {
        List<Section> sections = new ArrayList<>();
        sections.add(parseSection(context, R.xml.eh_settings,
                R.string.settings_eh, "eh",
                com.hippo.ehviewer.ui.fragment.EhFragment.class));
        sections.add(parseSection(context, R.xml.read_settings,
                R.string.settings_read, "read",
                com.hippo.ehviewer.ui.fragment.ReadFragment.class));
        sections.add(parseSection(context, R.xml.download_settings,
                R.string.settings_download, "download",
                com.hippo.ehviewer.ui.fragment.DownloadFragment.class));
        sections.add(parseSection(context, R.xml.privacy_settings,
                R.string.settings_privacy, "privacy",
                com.hippo.ehviewer.ui.fragment.PrivacyFragment.class));
        sections.add(parseSection(context, R.xml.advanced_settings,
                R.string.settings_advanced, "advanced",
                com.hippo.ehviewer.ui.fragment.AdvancedFragment.class));
        sections.add(parseSection(context, R.xml.about_settings,
                R.string.settings_about, "about",
                com.hippo.ehviewer.ui.fragment.AboutFragment.class));
        sections.add(parseSection(context, R.xml.lab_settings,
                R.string.settings_lab, "lab",
                com.hippo.ehviewer.ui.fragment.LabFragment.class));
        sections.add(parseSection(context, R.xml.help_settings,
                R.string.settings_help, "help",
                com.hippo.ehviewer.ui.fragment.HelpFragment.class));
        sections.add(parseSection(context, R.xml.transfer_settings,
                R.string.settings_transfer, "transfer",
                com.hippo.ehviewer.ui.fragment.TransferFragment.class));
        return sections;
    }

    @NonNull
    public static List<Entry> search(@NonNull Context context, @NonNull String query) {
        String needle = query.trim().toLowerCase(Locale.getDefault());
        if (TextUtils.isEmpty(needle)) {
            return Collections.emptyList();
        }
        List<Entry> matched = new ArrayList<>();
        for (Section section : buildIndex(context)) {
            for (Entry entry : section.entries) {
                if (entry.searchableHaystack.toLowerCase(Locale.getDefault()).contains(needle)) {
                    matched.add(entry);
                }
            }
        }
        return matched;
    }

    private static Section parseSection(@NonNull Context context, @XmlRes int xmlRes,
                                        int titleRes, @NonNull String sectionKey,
                                        @NonNull Class<?> fragmentClass) {
        Section section = new Section(context.getString(titleRes), sectionKey, fragmentClass);
        try (XmlResourceParser parser = context.getResources().getXml(xmlRes)) {
            int eventType = parser.getEventType();
            String categoryTitle = "";
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    if ("PreferenceCategory".equals(name)) {
                        categoryTitle = readAttribute(context, parser, "title");
                    } else if (isLeafPreference(name)) {
                        String title = readAttribute(context, parser, "title");
                        if (TextUtils.isEmpty(title)) {
                            title = categoryTitle;
                        }
                        String summary = readAttribute(context, parser, "summary");
                        String key = readAttribute(context, parser, "key");
                        if (!TextUtils.isEmpty(title)) {
                            section.entries.add(new Entry(
                                    section.title, section.key,
                                    title, summary == null ? "" : summary,
                                    TextUtils.isEmpty(key) ? null : key,
                                    fragmentClass));
                        }
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            // Silently skip malformed XML; index remains partial.
        }
        return section;
    }

    private static boolean isLeafPreference(@NonNull String name) {
        if ("Preference".equals(name) || "ListPreference".equals(name)) {
            return true;
        }
        return name.endsWith("Preference");
    }

    @Nullable
    private static String readAttribute(@NonNull Context context, @NonNull XmlResourceParser parser,
                                        @NonNull String attr) {
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            String qualified = parser.getAttributeName(i);
            String localName = qualified;
            int colon = qualified.indexOf(':');
            if (colon >= 0 && colon + 1 < qualified.length()) {
                localName = qualified.substring(colon + 1);
            }
            if (!attr.equals(localName)) {
                continue;
            }
            int resId = parser.getAttributeResourceValue(i, 0);
            if (resId != 0) {
                try {
                    return context.getString(resId);
                } catch (Exception ignore) {
                }
            }
            return parser.getAttributeValue(i);
        }
        return null;
    }

    @NonNull
    private static String buildHaystack(@NonNull String section, @NonNull String title,
                                        @NonNull String summary, @Nullable String key) {
        StringBuilder sb = new StringBuilder();
        sb.append(section).append('\n').append(title).append('\n').append(summary);
        if (!TextUtils.isEmpty(key)) {
            sb.append('\n').append(key);
        }
        return sb.toString();
    }
}