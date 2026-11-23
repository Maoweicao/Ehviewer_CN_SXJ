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

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把机场订阅的 Clash YAML 转换成内置 Mihomo 内核可直接运行的精简配置。
 *
 * 只保留真实节点，去掉“剩余流量/套餐到期/导航页”等虚拟节点，并重建一个
 * 选择组，规则统一为 MATCH，避免依赖 GeoIP/GeoSite 数据下载。
 */
public final class ClashProfileBuilder {

    public static final String GROUP_SELECT = "🔰 节点选择";
    public static final String GROUP_AUTO = "♻️ 自动选择";

    private static final Pattern VIRTUAL_NAME = Pattern.compile(
            "(?i)(剩余流量|距离下次重置|套餐到期|到期时间|过期时间|导航页|剩余|流量|重置|到期|" +
                    "官网|群组|客服|订阅|expire|traffic|website|remaining|reset)");

    private static final Set<String> SUPPORTED_TYPES = new HashSet<>(Arrays.asList(
            "ss", "ssr", "vmess", "vless", "trojan", "http", "socks5",
            "hysteria", "hysteria2", "tuic", "wireguard", "snell",
            "shadowtls", "anytls", "ssh", "mieru"
    ));

    private ClashProfileBuilder() {
    }

    public static class Result {
        public final String yaml;
        public final List<String> nodes;
        public final int total;
        public final int removed;

        Result(String yaml, List<String> nodes, int total, int removed) {
            this.yaml = yaml;
            this.nodes = nodes;
            this.total = total;
            this.removed = removed;
        }
    }

    @SuppressWarnings("unchecked")
    public static Result build(String rawYaml, int mixedPort, int controllerPort) throws Exception {
        if (rawYaml == null || rawYaml.trim().isEmpty()) {
            throw new IllegalArgumentException("订阅内容为空");
        }
        Object loaded = new Yaml().load(rawYaml);
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException("不是有效的 Clash 配置");
        }
        Map<String, Object> data = (Map<String, Object>) loaded;
        Object proxiesObj = data.get("proxies");
        if (!(proxiesObj instanceof List)) {
            throw new IllegalArgumentException("Clash 配置中没有 proxies 节点");
        }

        List<Map<String, Object>> source = (List<Map<String, Object>>) proxiesObj;
        List<Map<String, Object>> proxies = new ArrayList<>();
        List<String> nodes = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int removed = 0;

        for (Object item : source) {
            if (!(item instanceof Map)) {
                removed++;
                continue;
            }
            Map<String, Object> proxy = (Map<String, Object>) item;
            String name = stringValue(proxy.get("name"));
            String type = stringValue(proxy.get("type")).toLowerCase(Locale.US);
            String server = stringValue(proxy.get("server"));
            int port = parseInt(proxy.get("port"), 0);

            if (name.isEmpty() || names.contains(name)) {
                removed++;
                continue;
            }
            if (VIRTUAL_NAME.matcher(name).find()) {
                removed++;
                continue;
            }
            if (!SUPPORTED_TYPES.contains(type)) {
                removed++;
                continue;
            }
            if (server.isEmpty() || isLoopback(server) || port <= 0 || port > 65535) {
                removed++;
                continue;
            }

            proxies.add(proxy);
            nodes.add(name);
            names.add(name);
        }

        if (nodes.isEmpty()) {
            throw new IllegalStateException(
                    "订阅里没有可用节点（已移除 " + removed + " 个虚拟/无效节点）");
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mixed-port", mixedPort);
        config.put("allow-lan", false);
        config.put("bind-address", "127.0.0.1");
        config.put("mode", "rule");
        config.put("log-level", "warning");
        config.put("ipv6", false);
        config.put("external-controller", "127.0.0.1:" + controllerPort);
        config.put("unified-delay", true);
        config.put("tcp-concurrent", true);
        config.put("find-process-mode", "off");

        Map<String, Object> dns = new LinkedHashMap<>();
        dns.put("enable", true);
        dns.put("ipv6", false);
        dns.put("enhanced-mode", "normal");
        dns.put("nameserver", Arrays.asList(
                "223.5.5.5", "119.29.29.29", "8.8.8.8", "1.1.1.1"));
        config.put("dns", dns);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("store-selected", true);
        config.put("profile", profile);

        config.put("proxies", proxies);

        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, Object> select = new LinkedHashMap<>();
        select.put("name", GROUP_SELECT);
        select.put("type", "select");
        List<Object> selectProxies = new ArrayList<>();
        selectProxies.add(GROUP_AUTO);
        selectProxies.addAll(nodes);
        select.put("proxies", selectProxies);
        groups.add(select);

        Map<String, Object> auto = new LinkedHashMap<>();
        auto.put("name", GROUP_AUTO);
        auto.put("type", "url-test");
        // 用实际下载站点做健康检查，避免“能连 gstatic 但连不上 e-hentai”的节点。
        auto.put("url", "https://e-hentai.org/favicon.ico");
        auto.put("interval", 600);
        auto.put("tolerance", 50);
        auto.put("proxies", new ArrayList<>(nodes));
        groups.add(auto);

        config.put("proxy-groups", groups);
        config.put("rules", java.util.Collections.singletonList("MATCH," + GROUP_SELECT));

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setPrettyFlow(true);
        String yaml = new Yaml(options).dump(config);

        return new Result(yaml, nodes, source.size(), removed);
    }

    static boolean isLoopback(String server) {
        String s = server.trim().toLowerCase(Locale.US);
        return s.equals("127.0.0.1") || s.equals("0.0.0.0") || s.equals("localhost")
                || s.equals("::1") || s.startsWith("127.");
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int parseInt(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
