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

package com.hippo.ehviewer.transfer.api;

import android.content.Context;
import android.util.Log;
import android.webkit.CookieManager;

import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCookieStore;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.core.DataChangeBus;

import java.util.List;
import java.util.Locale;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Cookie;
import okhttp3.HttpUrl;

/**
 * E-Hentai 账号 API 处理器（协议 §5.27）
 *
 * <p>使远端 Web 端能够复刻源站登录：通过账号密码或导入 Cookie，把登录态写入主机的
 * Cookie 库。主机内嵌 OkHttpClient 的 CookieJar 即主机的 {@link EhCookieStore}，
 * 登录成功后主机的在线能力（§5.25）与下载能力立即生效，无需额外同步。
 *
 * <ul>
 *   <li>GET  /api/v1/eh/auth/status</li>
 *   <li>POST /api/v1/eh/auth/signin</li>
 *   <li>POST /api/v1/eh/auth/cookie</li>
 *   <li>POST /api/v1/eh/auth/signout</li>
 * </ul>
 */
public class EhAuthApiHandler extends BaseApiHandler {

    private static final String TAG = "EhAuthApiHandler";

    public EhAuthApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri, session);
        String path = uri.split("\\?")[0];
        if (path.equals("/api/v1/eh/auth/status")) {
            return handleStatus();
        }
        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("POST", uri, session);
        String path = uri.split("\\?")[0];
        switch (path) {
            case "/api/v1/eh/auth/signin":
                return handleSignIn(session);
            case "/api/v1/eh/auth/cookie":
                return handleCookie(session);
            case "/api/v1/eh/auth/signout":
                return handleSignOut();
            default:
                return ResponseBuilder.notFound("Endpoint");
        }
    }

    // ==================== 状态 ====================

    private NanoHTTPD.Response handleStatus() {
        try {
            EhCookieStore store = EhApplication.getEhCookieStore(context);
            boolean signedIn = store.hasSignedIn();

            String site = "none";
            if (signedIn) {
                HttpUrl exUrl = HttpUrl.parse(EhUrl.HOST_EX);
                boolean hasIgneous = exUrl != null
                        && store.contains(exUrl, EhCookieStore.KEY_IGNEOUS);
                site = hasIgneous ? "ex" : "e";
            }

            String username = Settings.getDisplayName();
            if (username == null) {
                username = "";
            }

            JSONObject resp = new JSONObject();
            resp.put("signedIn", signedIn);
            resp.put("site", site);
            resp.put("username", username);
            return ResponseBuilder.jsonSuccess(resp.toJSONString());
        } catch (Exception e) {
            Log.e(TAG, "status failed", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    // ==================== 账号密码登录 ====================

    private NanoHTTPD.Response handleSignIn(NanoHTTPD.IHTTPSession session) {
        String username;
        String password;
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSONObject.parseObject(body);
            if (json == null) {
                return ResponseBuilder.jsonError(
                        NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid JSON body", 400);
            }
            username = json.getString("username");
            password = json.getString("password");
        } catch (Exception e) {
            return ResponseBuilder.jsonError(
                    NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid request body", 400);
        }

        if (username == null || username.trim().isEmpty()
                || password == null || password.isEmpty()) {
            return ResponseBuilder.jsonError(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    "username and password are required", 400);
        }

        try {
            // 先清除原登录态，避免残留 Cookie 干扰
            EhUtils.signOut(context);
            String signedInName = EhEngine.signIn(null,
                    EhApplication.getOkHttpClient(context), username.trim(), password);

            Settings.putNeedSignIn(false);
            if (Settings.getDisplayName() == null || Settings.getDisplayName().isEmpty()) {
                Settings.putDisplayName(username.trim());
            }

            DataChangeBus.publish(DataChangeBus.TYPE_LOGIN_CHANGED, 0L, null, Boolean.TRUE);
            logSuccess("eh signin", signedInName);

            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("username", signedInName != null ? signedInName : username.trim());
            resp.put("signedIn", true);
            return ResponseBuilder.jsonSuccess(resp.toJSONString());
        } catch (Throwable t) {
            // 注意：绝不记录 password
            Log.w(TAG, "eh signin failed: " + t.getMessage());
            String msg = t.getMessage() != null ? t.getMessage() : "Sign-in failed";
            return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.UNAUTHORIZED, msg, 401);
        }
    }

    // ==================== Cookie 导入（论坛辅助登录） ====================

    private NanoHTTPD.Response handleCookie(NanoHTTPD.IHTTPSession session) {
        String ipbMemberId;
        String ipbPassHash;
        String igneous;
        try {
            String body = RequestParser.readBody(session);
            JSONObject json = JSONObject.parseObject(body);
            if (json == null) {
                return ResponseBuilder.jsonError(
                        NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid JSON body", 400);
            }
            ipbMemberId = json.getString("ipb_member_id");
            ipbPassHash = json.getString("ipb_pass_hash");
            igneous = json.getString("igneous");
        } catch (Exception e) {
            return ResponseBuilder.jsonError(
                    NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid request body", 400);
        }

        if (isBlank(ipbMemberId) || isBlank(ipbPassHash)) {
            return ResponseBuilder.jsonError(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    "ipb_member_id and ipb_pass_hash are required", 400);
        }

        try {
            writeCookies(ipbMemberId.trim(), ipbPassHash.trim(),
                    igneous != null ? igneous.trim() : "");
            DataChangeBus.publish(DataChangeBus.TYPE_LOGIN_CHANGED, 0L, null, Boolean.TRUE);
            logSuccess("eh cookie import", "id=" + mask(ipbMemberId));

            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("signedIn", true);
            return ResponseBuilder.jsonSuccess(resp.toJSONString());
        } catch (Exception e) {
            Log.e(TAG, "cookie import failed", e);
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    /**
     * 复刻 App 内 CookieSignInScene.storeCookie 的写入逻辑：
     * 写入 EhCookieStore 的 E/EX/FORUMS 三域，并同步到 WebView 的 CookieManager。
     */
    private void writeCookies(String id, String hash, String igneous) {
        EhUtils.signOut(context);

        EhCookieStore store = EhApplication.getEhCookieStore(context);
        store.addCookie(newCookie(EhCookieStore.KEY_IPD_MEMBER_ID, id, EhUrl.DOMAIN_E));
        store.addCookie(newCookie(EhCookieStore.KEY_IPD_MEMBER_ID, id, EhUrl.DOMAIN_EX));
        store.addCookie(newCookie(EhCookieStore.KEY_IPD_MEMBER_ID, id, EhUrl.DOMAIN_FORUMS));
        store.addCookie(newCookie(EhCookieStore.KEY_IPD_PASS_HASH, hash, EhUrl.DOMAIN_E));
        store.addCookie(newCookie(EhCookieStore.KEY_IPD_PASS_HASH, hash, EhUrl.DOMAIN_EX));
        store.addCookie(newCookie(EhCookieStore.KEY_IPD_PASS_HASH, hash, EhUrl.DOMAIN_FORUMS));
        if (!isBlank(igneous)) {
            store.addCookie(newCookie(EhCookieStore.KEY_IGNEOUS, igneous, EhUrl.DOMAIN_E));
            store.addCookie(newCookie(EhCookieStore.KEY_IGNEOUS, igneous, EhUrl.DOMAIN_EX));
            store.addCookie(newCookie(EhCookieStore.KEY_IGNEOUS, igneous, EhUrl.DOMAIN_FORUMS));
        }

        Settings.putNeedSignIn(false);

        // 同步到 App 内 WebView（论坛），失败不影响 OkHttp Cookie
        try {
            HttpUrl httpUrl = HttpUrl.parse(EhUrl.URL_FORUMS);
            if (httpUrl != null) {
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                List<Cookie> cookies = store.loadForRequest(httpUrl);
                for (Cookie cookie : cookies) {
                    cookieManager.setCookie(EhUrl.DOMAIN_FORUMS, formatCookieForWebView(cookie));
                }
                cookieManager.flush();
            }
        } catch (Throwable t) {
            Log.w(TAG, "WebView CookieManager unavailable; OkHttp cookies still stored", t);
        }
    }

    // ==================== 退出登录 ====================

    private NanoHTTPD.Response handleSignOut() {
        try {
            EhUtils.signOut(context);
            DataChangeBus.publish(DataChangeBus.TYPE_LOGIN_CHANGED, 0L, null, Boolean.FALSE);
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("message", "Signed out");
            return ResponseBuilder.jsonSuccess(resp.toJSONString());
        } catch (Exception e) {
            return ResponseBuilder.internalError(e.getMessage());
        }
    }

    // ==================== 工具 ====================

    private static Cookie newCookie(String name, String value, String domain) {
        return new Cookie.Builder().name(name).value(value)
                .domain(domain).path("/").expiresAt(Long.MAX_VALUE).build();
    }

    private static String formatCookieForWebView(Cookie cookie) {
        StringBuilder builder = new StringBuilder();
        builder.append(cookie.name()).append("=").append(cookie.value());
        if (cookie.domain() != null && !cookie.domain().isEmpty()) {
            builder.append("; Domain=").append(cookie.domain());
        }
        builder.append("; Path=").append(cookie.path().isEmpty() ? "/" : cookie.path());
        if (cookie.persistent() && cookie.expiresAt() > 0) {
            builder.append("; Expires=").append(formatDate(cookie.expiresAt()));
        }
        if (cookie.secure()) {
            builder.append("; Secure");
        }
        if (cookie.httpOnly()) {
            builder.append("; HttpOnly");
        }
        return builder.toString();
    }

    private static String formatDate(long millis) {
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return sdf.format(new java.util.Date(millis));
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 2) {
            return "***";
        }
        return s.charAt(0) + "***" + s.charAt(s.length() - 1);
    }
}
