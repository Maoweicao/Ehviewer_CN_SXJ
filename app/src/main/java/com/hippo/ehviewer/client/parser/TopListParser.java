package com.hippo.ehviewer.client.parser;

import com.hippo.ehviewer.client.data.EhTopListDetail;
import com.hippo.ehviewer.client.data.topList.TopListInfo;
import com.hippo.ehviewer.client.data.topList.TopListItem;
import com.hippo.ehviewer.client.data.topList.TopListItemArray;
import com.hippo.ehviewer.client.exception.EhException;
import com.hippo.ehviewer.client.exception.OffensiveException;
import com.hippo.ehviewer.client.exception.PiningException;
import com.hippo.util.JsoupUtils;

import android.util.Log;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TopListParser {

    /**
     * 排行榜跳转调试日志的统一 tag，与 {@code EhTopListScene.JUMP_LOG_TAG} 保持一致，
     * 配合 `adb logcat -s TopListJump:V` 使用。
     */
    private static final String JUMP_LOG_TAG = "TopListJump";

    private static final String OFFENSIVE_STRING =
            "<p>(And if you choose to ignore this warning, you lose all rights to complain about it in the future.)</p>";
    private static final String PINING_STRING =
            "<p>This gallery is pining for the fjords.</p>";

    private static final Pattern PATTERN_ERROR = Pattern.compile("<div class=\"d\">\n<p>([^<]+)</p>");


    public static EhTopListDetail parse(String body) throws EhException {

        if (body.contains(OFFENSIVE_STRING)) {
            throw new OffensiveException();
        }

        if (body.contains(PINING_STRING)) {
            throw new PiningException();
        }

        // Error info
        Matcher m = PATTERN_ERROR.matcher(body);
        if (m.find()) {
            throw new EhException(m.group(1));
        }

        EhTopListDetail ehTopListDetail = new EhTopListDetail();

        Document document = Jsoup.parse(body);


        Elements elements = document.getElementsByClass("ido").get(0).children();

        ehTopListDetail.title = elements.get(0).text();

        ehTopListDetail.galleryTopListInfo = parseInfo(elements.get(1), EhTopListDetail.ListType.GALLERY);
        ehTopListDetail.uploaderTopListInfo = parseInfo(elements.get(3), EhTopListDetail.ListType.UPLOADER);
        ehTopListDetail.taggingTopListInfo = parseInfo(elements.get(5), EhTopListDetail.ListType.TAGGING);
        ehTopListDetail.hentaiHomeTopListInfo = parseInfo(elements.get(7), EhTopListDetail.ListType.HENTAI_HOME);
        ehTopListDetail.ehTrackerTopListInfo = parseInfo(elements.get(9), EhTopListDetail.ListType.EH_TRACKER);
        ehTopListDetail.cleanUpTopListInfo = parseInfo(elements.get(11), EhTopListDetail.ListType.CLEANUP);
        ehTopListDetail.ratingAndReviewingTopListInfo = parseInfo(elements.get(13), EhTopListDetail.ListType.RATING_AND_REVIEWING);


        return ehTopListDetail;
    }

    private static TopListInfo parseInfo(Element element, EhTopListDetail.ListType type) {
        TopListInfo topListInfo = new TopListInfo();
        topListInfo.type = type;
        Elements elements = element.children();

        topListInfo.allTimeTopList = parseArray(elements.get(1).child(1).child(0), type + "/allTime");
        topListInfo.pastYearTopList = parseArray(elements.get(2).child(1).child(0), type + "/pastYear");
        topListInfo.pastMonthTopList = parseArray(elements.get(3).child(1).child(0), type + "/pastMonth");
        topListInfo.yesterdayTopList = parseArray(elements.get(4).child(1).child(0), type + "/yesterday");

        return topListInfo;
    }

    private static TopListItemArray parseArray(Element ele, String category){
        TopListItemArray topListItemArray = new TopListItemArray();
        TopListItem[] topListItems = new TopListItem[10];
        Elements minItems = JsoupUtils.getElementsByClass(ele,"tun");
        for (int i = 0; i<minItems.size();i++){
            Element element = minItems.get(i).child(0);
            TopListItem topListItem = new TopListItem();
            topListItem.value = element.text();
            topListItem.href = element.attr("href");
            // 画廊榜单的 href 形如 /g/1234567/a1b2c3d4e5，从中取出 gid/token，
            // 这样画廊排行榜才能拉缩略图并支持多选下载；关键词榜单解析不出就留空。
            GalleryDetailUrlParser.Result result = GalleryDetailUrlParser.parse(topListItem.href, false);
            if (result != null) {
                topListItem.gid = String.valueOf(result.gid);
                topListItem.token = result.token;
                // pt 链接没有 token，gallery 榜单用不到；其他榜单的 tag 也一并留空
                topListItem.tag = null;
            }
            // 调试：确认 gid/token 到底有没有解析出来。配合 EhTopListScene 的
            // TopListJump tag 一起看，能完整反推"解析 → 点击 → 跳转"整条链路。
            Log.i(JUMP_LOG_TAG, "parse[" + category + "] #" + (i + 1)
                    + " value=" + topListItem.value
                    + " href=" + topListItem.href
                    + " -> gid=" + topListItem.gid
                    + " token=" + topListItem.token
                    + (result != null ? " (gallery)" : " (keyword)"));
            topListItems[i] = topListItem;
        }
        topListItemArray.itemArray = topListItems;

        return topListItemArray;
    }

}
