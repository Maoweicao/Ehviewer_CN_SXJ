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

package com.hippo.ehviewer.lab.ip.core;

import android.content.Context;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.ip.IpPoolManager;
import com.hippo.ehviewer.lab.ip.model.IpInfo;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 内置内核配置文件的读写与节点列表同步。 */
public final class ClashProfileStore {

    private static final String TAG = "ClashProfileStore";
    public static final int MIXED_PORT = 17890;
    public static final int CONTROLLER_PORT = 19090;

    private ClashProfileStore() {
    }

    public static File dir(Context context) {
        File dir = new File(context.getFilesDir(), "ip_switch");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static File workDir(Context context) {
        File dir = new File(dir(context), "work");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static File configFile(Context context) {
        return new File(dir(context), "config.yaml");
    }

    public static File logFile(Context context) {
        return new File(dir(context), "core.log");
    }

    public static boolean hasProfile(Context context) {
        File file = configFile(context);
        return file.isFile() && file.length() > 0;
    }

    public static void save(Context context, ClashProfileBuilder.Result result, String source) {
        try {
            File file = configFile(context);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(result.yaml.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            JSONArray array = new JSONArray();
            for (String node : result.nodes) {
                array.add(node);
            }
            Settings.putIpProfileNodes(array.toJSONString());
            Settings.putIpProfileUpdated(System.currentTimeMillis());
            Settings.putIpProfileSource(source == null ? "" : source);

            // 同步一份节点列表给“节点列表/状态”页面展示。
            List<IpInfo> infos = new ArrayList<>(result.nodes.size());
            for (String node : result.nodes) {
                IpInfo info = new IpInfo("clash", 0, "clash");
                info.name = node;
                info.actualProtocol = "clash";
                info.source = source == null ? "" : source;
                infos.add(info);
            }
            IpPoolManager.getInstance().replaceAll(infos);
        } catch (IOException e) {
            Log.e(TAG, "Failed to save profile", e);
        }
    }

    public static List<String> getNodes() {
        List<String> nodes = new ArrayList<>();
        try {
            JSONArray array = JSONArray.parseArray(Settings.getIpProfileNodes());
            if (array != null) {
                for (int i = 0; i < array.size(); i++) {
                    String name = array.getString(i);
                    if (name != null && !name.isEmpty()) {
                        nodes.add(name);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read profile nodes", e);
        }
        return nodes;
    }

    /**
     * 让 IP 池与当前内核配置的节点保持一致；保留已有节点的启用/封禁状态。
     * 避免“内核有 27 个节点，但节点列表为空/旧列表”的状态错位。
     */
    public static void syncPool(Context context) {
        List<String> nodes = getNodes();
        if (nodes.isEmpty() && hasProfile(context)) {
            // 兼容旧配置：只有 config.yaml，没有 ip_profile_nodes。
            try {
                Map<String, Object> data;
                try (java.io.FileInputStream in = new java.io.FileInputStream(configFile(context))) {
                    data = new Yaml().load(in);
                }
                Object proxies = data == null ? null : data.get("proxies");
                if (proxies instanceof List) {
                    JSONArray array = new JSONArray();
                    for (Object item : (List<?>) proxies) {
                        if (!(item instanceof Map)) continue;
                        Object name = ((Map<?, ?>) item).get("name");
                        if (name != null && !String.valueOf(name).isEmpty()) {
                            nodes.add(String.valueOf(name));
                            array.add(String.valueOf(name));
                        }
                    }
                    if (!nodes.isEmpty()) {
                        Settings.putIpProfileNodes(array.toJSONString());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read nodes from config.yaml", e);
            }
        }
        if (nodes.isEmpty()) return;
        List<IpInfo> existing = IpPoolManager.getInstance().getAllIps();
        java.util.Map<String, IpInfo> byName = new java.util.HashMap<>();
        for (IpInfo info : existing) {
            if (info.name != null && !info.name.isEmpty()) {
                byName.put(info.name, info);
            }
        }
        List<IpInfo> result = new ArrayList<>(nodes.size());
        String source = Settings.getIpProfileSource();
        for (String name : nodes) {
            IpInfo info = byName.get(name);
            if (info == null) {
                info = new IpInfo("clash", 0, "clash");
                info.name = name;
                info.actualProtocol = "clash";
            }
            info.source = source;
            result.add(info);
        }
        IpPoolManager.getInstance().replaceAll(result);
    }

    public static void clear(Context context) {
        File file = configFile(context);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        Settings.putIpProfileNodes("[]");
        Settings.putIpProfileUpdated(0L);
        Settings.putIpProfileSource("");
        IpPoolManager.getInstance().replaceAll(new ArrayList<>());
    }
}
