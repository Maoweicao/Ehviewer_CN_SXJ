/*
 * Copyright 2019 Hippo Seven
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

package com.hippo.ehviewer.client;

import android.content.Context;
import android.util.Base64;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.Analytics;
import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.Tag;
import com.hippo.util.ExceptionUtils;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.util.TextUrl;
import com.hippo.lib.yorozuya.FileUtils;
import com.hippo.lib.yorozuya.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

public class EhTagDatabase {

    private final String name;
    private final byte[] tags;
    private final List<Tag> tagList;

    public EhTagDatabase(String name, BufferedSource source) throws IOException {
        this.name = name;
        int totalBytes = source.readInt();

        tags = new byte[totalBytes];
        source.readFully(tags);
        byte[] newByte = tags.clone();
        String sourceString = new String(newByte, StandardCharsets.UTF_8);

        tagList = initTagList(sourceString);
    }

    public String getTranslation(String tag) {
        return search(tags, tag.getBytes(TextUrl.UTF_8));
    }

    private List<Tag> initTagList(String sourceString) {
        String[] tagStrings = sourceString.split("\n");

        List<Tag> initList = new ArrayList<>();

        for (String tagString : tagStrings) {
            initList.add(parseTag(tagString));
        }

        return initList;
    }

    private Tag parseTag(String source) {
        String[] cArray = source.split("\r");
        if (cArray.length == 2) {
            String english;
            String chinese = new String(Base64.decode(cArray[1], Base64.DEFAULT), StandardCharsets.UTF_8);
            String[] eArray = cArray[0].split(":");
            if (eArray.length == 2) {
                String key = eArray[0] + ":";
                english = (PREFIX_TO_NAMESPACE.containsKey(key) ? PREFIX_TO_NAMESPACE.get(key) : cArray[0]) + ":" + eArray[1];
            } else {
                english = cArray[0];
            }
            return new Tag(english, chinese);
        }

        return new Tag(source, "null");
    }

    @Nullable
    private String search(byte[] tags, byte[] tag) {
        int low = 0;
        int high = tags.length;
        while (low < high) {
            int start = (low + high) / 2;
            // Look for the starting '\n'
            while (start > -1 && tags[start] != '\n') {
                start--;
            }
            start++;

            // Look for the middle '\r'.
            int middle = 1;
            while (tags[start + middle] != '\r') {
                middle++;
            }

            // Look for the ending '\n'
            int end = middle + 1;
            while (tags[start + end] != '\n') {
                end++;
            }

            int compare;
            int tagIndex = 0;
            int curIndex = start;

            for (; ; ) {
                int tagByte = tag[tagIndex] & 0xff;
                int curByte = tags[curIndex] & 0xff;
                compare = tagByte - curByte;
                if (compare != 0) {
                    break;
                }

                tagIndex++;
                curIndex++;
                if (tagIndex == tag.length && curIndex == start + middle) {
                    break;
                }
                if (tagIndex == tag.length) {
                    compare = -1;
                    break;
                }
                if (curIndex == start + middle) {
                    compare = 1;
                    break;
                }
            }

            if (compare < 0) {
                high = start - 1;
            } else if (compare > 0) {
                low = start + end + 1;
            } else {
                byte[] bytes = Base64.decode(tags, start + middle + 1, end - middle - 1, Base64.DEFAULT);
                return new String(bytes, TextUrl.UTF_8);
            }
        }
        return null;
    }


    public static final Map<String, String> NAMESPACE_TO_PREFIX = new HashMap<>();
    public static final Map<String, String> PREFIX_TO_NAMESPACE = new HashMap<>();

    static {
        NAMESPACE_TO_PREFIX.put("rows", "n:");
        NAMESPACE_TO_PREFIX.put("artist", "a:");
        NAMESPACE_TO_PREFIX.put("cosplayer", "cos:");
        NAMESPACE_TO_PREFIX.put("character", "c:");
        NAMESPACE_TO_PREFIX.put("female", "f:");
        NAMESPACE_TO_PREFIX.put("group", "g:");
        NAMESPACE_TO_PREFIX.put("language", "l:");
        NAMESPACE_TO_PREFIX.put("male", "m:");
        NAMESPACE_TO_PREFIX.put("misc", "");
        NAMESPACE_TO_PREFIX.put("mixed", "x:");
        NAMESPACE_TO_PREFIX.put("other", "o:");
        NAMESPACE_TO_PREFIX.put("parody", "p:");
        NAMESPACE_TO_PREFIX.put("reclass", "r:");
        PREFIX_TO_NAMESPACE.put("n:", "rows");
        PREFIX_TO_NAMESPACE.put("a:", "artist");
        PREFIX_TO_NAMESPACE.put("cos:", "cosplayer");
        PREFIX_TO_NAMESPACE.put("c:", "character");
        PREFIX_TO_NAMESPACE.put("f:", "female");
        PREFIX_TO_NAMESPACE.put("g:", "group");
        PREFIX_TO_NAMESPACE.put("l:", "language");
        PREFIX_TO_NAMESPACE.put("m:", "male");
        PREFIX_TO_NAMESPACE.put("", "misc");
        PREFIX_TO_NAMESPACE.put("x:", "mixed");
        PREFIX_TO_NAMESPACE.put("o:", "other");
        PREFIX_TO_NAMESPACE.put("p:", "parody");
        PREFIX_TO_NAMESPACE.put("r:", "reclass");
    }

    private static volatile EhTagDatabase instance;
    // TODO more lock for different language
    private static final Lock lock = new ReentrantLock();

    @Nullable
    public static EhTagDatabase getInstance(Context context) {
        if (isPossible(context)) {
            return instance;
        } else {
            instance = null;
            return null;
        }
    }

    @Nullable
    public static String namespaceToPrefix(String namespace) {
        String prefix =  NAMESPACE_TO_PREFIX.get(namespace);
        if (prefix!=null){
            return prefix;
        }
        if (PREFIX_TO_NAMESPACE.containsKey(namespace+":")){
            return namespace;
        }
        return null;
    }

    @Nullable
    public static String prefixToNamespace(String prefix) {
        return PREFIX_TO_NAMESPACE.get(prefix);
    }

    private static String[] getMetadata(Context context) {
        String[] metadata = context.getResources().getStringArray(R.array.tag_translation_metadata);
        if (metadata.length == 4) {
            return metadata;
        } else {
            return null;
        }
    }

    public static boolean isPossible(Context context) {
        return getMetadata(context) != null;
    }

    @Nullable
    private static byte[] getFileContent(File file, int length) {
        try (BufferedSource source = Okio.buffer(Okio.source(file))) {
            byte[] content = new byte[length];
            source.readFully(content);
            return content;
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    private static byte[] getFileSha1(File file) {
        try (InputStream is = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            int n;
            byte[] buffer = new byte[4 * 1024];
            while ((n = is.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
            return digest.digest();
        } catch (IOException e) {
            return null;
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static boolean equals(byte[] b1, byte[] b2) {
        if (b1 == null && b2 == null) {
            return true;
        }
        if (b1 == null || b2 == null) {
            return false;
        }

        if (b1.length != b2.length) {
            return false;
        }

        for (int i = 0; i < b1.length; i++) {
            if (b1[i] != b2[i]) {
                return false;
            }
        }

        return true;
    }

    private static boolean checkData(File sha1File, File dataFile) {
        byte[] s1 = getFileContent(sha1File, 20);
        if (s1 == null) {
            return false;
        }

        byte[] s2 = getFileSha1(dataFile);
        if (s2 == null) {
            return false;
        }

        return equals(s1, s2);
    }

    private static boolean save(OkHttpClient client, String url, File file) {
        Request request = new Request.Builder().url(url).build();
        Call call = client.newCall(request);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                return false;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return false;
            }

            try (InputStream is = body.byteStream(); OutputStream os = new FileOutputStream(file)) {
                IOUtils.copy(is, os);
            }

            return true;
        } catch (Throwable t) {
            ExceptionUtils.throwIfFatal(t);
            Analytics.recordException(t);
            return false;
        }
    }

    public static void update(Context context) {
        String[] urls = getMetadata(context);
        if (urls == null || urls.length != 4) {
            // Clear tags if it's not possible
            instance = null;
            return;
        }

        String sha1Name = urls[0];
        String sha1Url = urls[1];
        String dataName = urls[2];
        String dataUrl = urls[3];

        // Clear tags if name if different
        EhTagDatabase tmp = instance;
        if (tmp != null && !tmp.name.equals(dataName)) {
            instance = null;
        }

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            if (!lock.tryLock()) {
                return;
            }

            try {
                File dir = AppConfig.getFilesDir("tag-translations");
                if (dir == null) {
                    return;
                }

                // Check current sha1 and current data
                File sha1File = new File(dir, sha1Name);
                File dataFile = new File(dir, dataName);
                if (!checkData(sha1File, dataFile)) {
                    FileUtils.delete(sha1File);
                    FileUtils.delete(dataFile);
                }

                // Read current EhTagDatabase
                if (instance == null && dataFile.exists()) {
                    try (BufferedSource source = Okio.buffer(Okio.source(dataFile))) {
                        instance = new EhTagDatabase(dataName, source);
                    } catch (IOException e) {
                        FileUtils.delete(sha1File);
                        FileUtils.delete(dataFile);
                    }
                }

                OkHttpClient client = EhApplication.getOkHttpClient(EhApplication.getInstance());

                // Save new sha1
                File tempSha1File = new File(dir, sha1Name + ".tmp");
                if (!save(client, sha1Url, tempSha1File)) {
                    FileUtils.delete(tempSha1File);
                    return;
                }

                // Check new sha1 and current data
                if (checkData(tempSha1File, dataFile)) {
                    // The data is the same
                    FileUtils.delete(tempSha1File);
                    return;
                }

                // Save new data
                File tempDataFile = new File(dir, dataName + ".tmp");
                if (!save(client, dataUrl, tempDataFile)) {
                    FileUtils.delete(tempDataFile);
                    return;
                }

                // Check new sha1 and new data
                if (!checkData(tempSha1File, tempDataFile)) {
                    FileUtils.delete(tempSha1File);
                    FileUtils.delete(tempDataFile);
                    return;
                }

                // Replace current sha1 and current data with new sha1 and new data
                FileUtils.delete(sha1File);
                FileUtils.delete(dataFile);
                tempSha1File.renameTo(sha1File);
                tempDataFile.renameTo(dataFile);

                // Read new EhTagDatabase
                try (BufferedSource source = Okio.buffer(Okio.source(dataFile))) {
                    instance = new EhTagDatabase(dataName, source);
                } catch (IOException e) {
                    // Ignore
                }
            } finally {
                lock.unlock();
            }
        });
    }

    public List<Pair<String, String>> suggest(String keyword) {
        return searchTag(tagList, keyword);
    }

    private boolean containsCJK(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isCjkCodePoint(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code c} belongs to a CJK-related Unicode block.
     * Covers Han ideographs (incl. recent extensions), kana, hangul, fullwidth ASCII,
     * and CJK compatibility forms so that ja/ko/zh/east-asian punctuation inputs are
     * all treated as CJK for suggestion ordering.
     */
    private static boolean isCjkCodePoint(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A
                || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
                || block == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS;
    }

    private List<Pair<String, String>> searchTag(List<Tag> tags, String keyword) {
        boolean isCJK = containsCJK(keyword);
        List<Pair<String, String>> chineseMatchList = new ArrayList<>();
        List<Pair<String, String>> englishMatchList = new ArrayList<>();
        int total = 0;
        for (Tag tag : tags) {
            if (total >= 80) {
                break;
            }
            // Tag.involve() is "english contains OR chinese contains", so if it returns
            // true at least one of chineseMatch / englishMatch will also be true.
            if (tag.involve(keyword)) {
                boolean chineseMatch = tag.chinese.contains(keyword);
                boolean englishMatch = tag.english.contains(keyword);
                if (chineseMatch && !"null".equals(tag.chinese)) {
                    chineseMatchList.add(new Pair<>(tag.chinese, tag.english));
                } else if (englishMatch) {
                    englishMatchList.add(new Pair<>(tag.chinese, tag.english));
                }
                total++;
            }
        }
        // For CJK queries we surface Chinese-translation matches first, followed by
        // English fallbacks. For non-CJK we still want Chinese translations that happen
        // to contain the ASCII keyword to lead (rare but useful, e.g. "scanlation").
        List<Pair<String, String>> searchList = new ArrayList<>(chineseMatchList.size() + englishMatchList.size());
        if (isCJK) {
            searchList.addAll(chineseMatchList);
            searchList.addAll(englishMatchList);
        } else {
            searchList.addAll(chineseMatchList);
            searchList.addAll(englishMatchList);
        }
        return searchList.subList(0, Math.min(searchList.size(), 40));
    }
    public List<Tag> getTagList() {
        return tagList;
    }

    // ---------------------------------------------------------------------
    // Status / management helpers exposed for the Settings page.
    // ---------------------------------------------------------------------

    /** Returns true when {@link #getInstance(Context)} could yield a usable instance. */
    public static boolean isLoaded() {
        return instance != null;
    }

    /** Returns whether the database file currently exists on disk, regardless of load state. */
    public static boolean hasLocalData(Context context) {
        File f = getLocalDataFile(context);
        return f != null && f.exists() && f.length() > 0;
    }

    /** Returns the size in bytes of the local data file, or {@code -1} if missing/unreadable. */
    public static long getLocalDataSize(Context context) {
        File f = getLocalDataFile(context);
        if (f == null || !f.exists()) return -1L;
        return f.length();
    }

    /** Returns the last-modified time of the local data file in millis, or {@code 0} if missing. */
    public static long getLocalDataModified(Context context) {
        File f = getLocalDataFile(context);
        if (f == null || !f.exists()) return 0L;
        return f.lastModified();
    }

    /** Returns the metadata array {@code [sha1Name, sha1Url, dataName, dataUrl]} for the active locale. */
    @Nullable
    public static String[] getCurrentMetadata(Context context) {
        return getMetadata(context);
    }

    @Nullable
    public static File getLocalSha1File(Context context) {
        String[] meta = getMetadata(context);
        if (meta == null) return null;
        File dir = AppConfig.getFilesDir("tag-translations");
        if (dir == null) return null;
        return new File(dir, meta[0]);
    }

    @Nullable
    public static File getLocalDataFile(Context context) {
        String[] meta = getMetadata(context);
        if (meta == null) return null;
        File dir = AppConfig.getFilesDir("tag-translations");
        if (dir == null) return null;
        return new File(dir, meta[2]);
    }

    /**
     * Reads the locally cached SHA-1 reference (20 raw bytes) into an uppercase hex string.
     * Returns {@code null} if the file is missing or unreadable.
     */
    @Nullable
    public static String readLocalSha1Hex(Context context) {
        File f = getLocalSha1File(context);
        if (f == null || !f.exists()) return null;
        byte[] bytes = getFileContent(f, 20);
        if (bytes == null) return null;
        return toHex(bytes);
    }

    /**
     * Computes the SHA-1 of the local data file and returns it as an uppercase hex string.
     * Returns {@code null} if the data file is missing or unreadable.
     */
    @Nullable
    public static String computeLocalDataSha1Hex(Context context) {
        File f = getLocalDataFile(context);
        if (f == null || !f.exists()) return null;
        byte[] sha1 = getFileSha1(f);
        if (sha1 == null) return null;
        return toHex(sha1);
    }

    /**
     * Verifies that the local data file matches the cached SHA-1 reference.
     * Returns:
     * <ul>
     *     <li>{@code true} – both files exist and the digest matches</li>
     *     <li>{@code false} – any file missing or the digest doesn't match (corrupt/tampered)</li>
     * </ul>
     */
    public static boolean verifyIntegrity(Context context) {
        File sha1File = getLocalSha1File(context);
        File dataFile = getLocalDataFile(context);
        if (sha1File == null || dataFile == null) return false;
        if (!sha1File.exists() || !dataFile.exists()) return false;
        return checkData(sha1File, dataFile);
    }

    /**
     * Drops the in-memory instance and deletes the cached SHA-1 and data files.
     * The next call to {@link #update(Context)} will re-download from scratch.
     */
    public static void clearLocalCache(Context context) {
        instance = null;
        File sha1File = getLocalSha1File(context);
        if (sha1File != null && sha1File.exists()) {
            FileUtils.delete(sha1File);
        }
        File dataFile = getLocalDataFile(context);
        if (dataFile != null && dataFile.exists()) {
            FileUtils.delete(dataFile);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }
}
