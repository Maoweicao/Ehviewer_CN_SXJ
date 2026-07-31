package com.hippo.ehviewer.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DiagnosticEndpoint {

    public enum Site {
        E("E-Hentai", "e-hentai.org"),
        EX("Ex-Hentai", "exhentai.org");

        public final String displayName;
        public final String domain;

        Site(String displayName, String domain) {
            this.displayName = displayName;
            this.domain = domain;
        }

        public String getHost() {
            return "https://" + domain + "/";
        }
    }

    public enum HttpMethod {
        GET, POST, HEAD
    }

    public static class EndpointItem {
        public final String name;
        public final String description;
        public final String url;
        public final HttpMethod method;
        public final boolean requiresAuth;
        public final boolean consumesQuota;
        public final String postBody;
        public Site site;

        public EndpointItem(String name, String description, String url,
                            HttpMethod method, boolean requiresAuth,
                            boolean consumesQuota, String postBody) {
            this.name = name;
            this.description = description;
            this.url = url;
            this.method = method;
            this.requiresAuth = requiresAuth;
            this.consumesQuota = consumesQuota;
            this.postBody = postBody;
        }

        public EndpointItem withSite(Site site) {
            this.site = site;
            return this;
        }
    }

    public static class EndpointCategory {
        public final String name;
        public final String description;
        public final List<EndpointItem> items;

        public EndpointCategory(String name, String description, List<EndpointItem> items) {
            this.name = name;
            this.description = description;
            this.items = items;
        }
    }

    public static class CheckResult {
        public EndpointItem endpoint;
        public int httpCode = -1;
        public long dnsTimeMs = -1;
        public long connectTimeMs = -1;
        public long tlsTimeMs = -1;
        public long totalTimeMs = -1;
        public boolean isReachable;
        public String error;
        public String resolvedIP;

        public CheckResult(EndpointItem endpoint) {
            this.endpoint = endpoint;
        }
    }

    public static List<EndpointCategory> buildCategories() {
        List<EndpointCategory> categories = new ArrayList<>();

        // Group 0: JSON API
        List<EndpointItem> jsonApiItems = new ArrayList<>();
        jsonApiItems.add(new EndpointItem(
                "gdata", "获取画廊元数据",
                "{host}api.php", HttpMethod.POST,
                true, true,
                "{\"method\":\"gdata\",\"gidlist\":[],\"namespace\":1}"));
        jsonApiItems.add(new EndpointItem(
                "gtoken", "获取画廊页面Token",
                "{host}api.php", HttpMethod.POST,
                true, true,
                "{\"method\":\"gtoken\",\"pagelist\":[]}"));
        jsonApiItems.add(new EndpointItem(
                "showpage", "获取页面图片URL",
                "{host}api.php", HttpMethod.POST,
                true, true,
                "{\"method\":\"showpage\",\"gid\":0,\"page\":0,\"imgkey\":\"\",\"showkey\":\"\"}"));
        jsonApiItems.add(new EndpointItem(
                "rategallery", "画廊评分",
                "{host}api.php", HttpMethod.POST,
                true, false,
                "{\"method\":\"rategallery\",\"apiuid\":0,\"apikey\":\"\",\"gid\":0,\"token\":\"\",\"rating\":0}"));
        jsonApiItems.add(new EndpointItem(
                "votecomment", "评论投票",
                "{host}api.php", HttpMethod.POST,
                true, false,
                "{\"method\":\"votecomment\",\"apiuid\":0,\"apikey\":\"\",\"gid\":0,\"token\":\"\",\"comment_id\":0,\"comment_vote\":1}"));
        categories.add(new EndpointCategory("JSON API (api.php)", "REST API 端点", jsonApiItems));

        // Group 1: Gallery Pages
        List<EndpointItem> galleryItems = new ArrayList<>();
        galleryItems.add(new EndpointItem(
                "主页/搜索", "画廊列表主页",
                "{host}", HttpMethod.GET,
                false, false, null));
        galleryItems.add(new EndpointItem(
                "热门", "热门画廊",
                "{host}popular", HttpMethod.GET,
                false, false, null));
        categories.add(new EndpointCategory("画廊页面", "Gallery 页面端点", galleryItems));

        // Group 2: Favorites
        List<EndpointItem> favItems = new ArrayList<>();
        favItems.add(new EndpointItem(
                "收藏夹", "收藏夹列表",
                "{host}favorites.php", HttpMethod.GET,
                true, false, null));
        favItems.add(new EndpointItem(
                "用户配置", "用户设置页",
                "{host}uconfig.php", HttpMethod.GET,
                true, false, null));
        categories.add(new EndpointCategory("收藏夹", "Favorites 端点", favItems));

        // Group 3: User
        List<EndpointItem> userItems = new ArrayList<>();
        userItems.add(new EndpointItem(
                "我的标签", "标签管理",
                "{host}mytags", HttpMethod.GET,
                true, false, null));
        userItems.add(new EndpointItem(
                "用户主页", "图片限额/GP",
                "{host}home.php", HttpMethod.GET,
                true, false, null));
        categories.add(new EndpointCategory("用户功能", "User 端点", userItems));

        // Group 4: Forum/Auth
        List<EndpointItem> forumItems = new ArrayList<>();
        forumItems.add(new EndpointItem(
                "论坛首页", "EH 论坛",
                "https://forums.e-hentai.org/", HttpMethod.GET,
                false, false, null));
        forumItems.add(new EndpointItem(
                "排行榜", "Top List",
                "{host}toplist.php", HttpMethod.GET,
                false, false, null));
        forumItems.add(new EndpointItem(
                "新闻", "EH News",
                "{host}news.php", HttpMethod.GET,
                false, false, null));
        categories.add(new EndpointCategory("论坛/认证", "Forum & Auth 端点", forumItems));

        // Group 5: Special
        List<EndpointItem> specialItems = new ArrayList<>();
        specialItems.add(new EndpointItem(
                "以图搜图", "Image Search",
                "{upld}image_lookup.php", HttpMethod.HEAD,
                true, false, null));
        specialItems.add(new EndpointItem(
                "种子列表", "Torrent List",
                "{host}torrent.php?gid=2231376&t=a7584a5932", HttpMethod.GET,
                true, false, null));
        specialItems.add(new EndpointItem(
                "存档下载", "Archiver",
                "{host}archiver.php?gid=2231376&token=a7584a5932", HttpMethod.GET,
                true, false, null));
        categories.add(new EndpointCategory("特殊功能", "Special 端点", specialItems));

        // Group 6: CDN/External
        List<EndpointItem> cdnItems = new ArrayList<>();
        cdnItems.add(new EndpointItem(
                "表站缩略图 CDN", "ehgt.org",
                "https://ehgt.org/", HttpMethod.HEAD,
                false, false, null));
        cdnItems.add(new EndpointItem(
                "里站静态资源", "s.exhentai.org",
                "https://s.exhentai.org/", HttpMethod.HEAD,
                false, false, null));
        cdnItems.add(new EndpointItem(
                "EHWiki", "标签文档",
                "https://ehwiki.org/wiki/API", HttpMethod.GET,
                false, false, null));
        cdnItems.add(new EndpointItem(
                "DoH", "安全 DNS (Yandex)",
                "https://77.88.8.1/dns-query?name=e-hentai.org&type=A", HttpMethod.GET,
                false, false, null));
        categories.add(new EndpointCategory("CDN/外部资源", "CDN & External 端点", cdnItems));

        return categories;
    }

    public static String resolveUrl(String urlTemplate, Site site) {
        String url = urlTemplate;
        if (url.contains("{host}")) {
            url = url.replace("{host}", site.getHost());
        }
        if (url.contains("{upld}")) {
            String domain = site == Site.E ? "upld.e-hentai.org" : "upld.exhentai.org";
            String prefix = site == Site.E ? "" : "upld/";
            url = url.replace("{upld}", "https://" + domain + "/" + prefix);
        }
        return url;
    }
}
