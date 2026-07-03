/*
 * Copyright 2016 Hippo Seven
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

package com.hippo.ehviewer.client.parser;

import android.text.TextUtils;
import android.util.Log;
import com.hippo.ehviewer.client.exception.ParseException;
import com.hippo.lib.yorozuya.StringUtils;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GalleryPageParser {

    private static final String TAG = "GalleryPageParser";

    private static final Pattern PATTERN_IMAGE_URL = Pattern.compile("<img[^>]*src=\"([^\"]+)\" style");
    private static final Pattern PATTERN_SKIP_HATH_KEY = Pattern.compile("onclick=\"return nl\\('([^\\)]+)'\\)");
    private static final Pattern PATTERN_ORIGIN_IMAGE_URL = Pattern.compile("<a href=\"([^\"]+)fullimg([^\"]+)\">");
    // TODO Not sure about the size of show keys
    private static final Pattern PATTERN_SHOW_KEY = Pattern.compile("var showkey=\"([0-9a-z]+)\";");

    public static Result parse(String body) throws ParseException {
        Matcher m;
        Result result = new Result();

        // Diagnostic: log body info
        int bodyLen = body != null ? body.length() : 0;
        Log.d(TAG, "parse: body length=" + bodyLen);

        if (bodyLen == 0) {
            Log.e(TAG, "parse: empty body");
            throw new ParseException("Empty body", body);
        }

        // Check for common error indicators
        if (body.contains("This gallery has been removed")) {
            Log.e(TAG, "parse: gallery removed");
            throw new ParseException("Gallery removed", body);
        }
        if (body.contains("404 Not Found") || body.contains("410 Gone")) {
            Log.e(TAG, "parse: page not found (404/410)");
            throw new ParseException("Page not found", body);
        }

        m = PATTERN_IMAGE_URL.matcher(body);
        if (m.find()) {
            result.imageUrl = StringUtils.unescapeXml(StringUtils.trim(m.group(1)));
            Log.d(TAG, "parse: imageUrl found=" + result.imageUrl.substring(0, Math.min(80, result.imageUrl.length())));
        } else {
            Log.w(TAG, "parse: imageUrl NOT found");
        }

        m = PATTERN_SKIP_HATH_KEY.matcher(body);
        if (m.find()) {
            result.skipHathKey = StringUtils.unescapeXml(StringUtils.trim(m.group(1)));
            Log.d(TAG, "parse: skipHathKey found=" + result.skipHathKey);
        }

        m = PATTERN_ORIGIN_IMAGE_URL.matcher(body);
        if (m.find()) {
            result.originImageUrl = StringUtils.unescapeXml(m.group(1)) + "fullimg" + StringUtils.unescapeXml(m.group(2));
            Log.d(TAG, "parse: originImageUrl found");
        }

        m = PATTERN_SHOW_KEY.matcher(body);
        if (m.find()) {
            result.showKey = m.group(1);
            Log.d(TAG, "parse: showKey found=" + result.showKey);
        } else {
            Log.w(TAG, "parse: showKey NOT found");
            // Log a snippet of body to help debug
            if (bodyLen > 200) {
                Log.d(TAG, "parse: body snippet: " + body.substring(0, Math.min(500, bodyLen)));
            }
        }

        if (!TextUtils.isEmpty(result.imageUrl) && !TextUtils.isEmpty(result.showKey)) {
            Log.i(TAG, "parse: SUCCESS - imageUrl and showKey both found");
            return result;
        } else {
            Log.e(TAG, "parse: FAILED - imageUrl=" + (result.imageUrl != null ? "found" : "null") +
                    ", showKey=" + (result.showKey != null ? "found" : "null"));
            throw new ParseException("Parse image url and show error", body);
        }
    }

    public static class Result {
        public String imageUrl;
        public String skipHathKey;
        public String originImageUrl;
        public String showKey;
    }
}
