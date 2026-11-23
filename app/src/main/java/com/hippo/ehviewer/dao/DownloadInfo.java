package com.hippo.ehviewer.dao;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import android.os.Parcel;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import java.util.ArrayList;

/**
 * Entity mapped to table "DOWNLOADS".
 *
 * <p>注意：下面带 {@link Ignore} 的字段是运行时状态，从不入库（旧 greenDAO DAO 只绑定
 * 15 个持久化列），Room 必须显式忽略，否则会试图为它们建列。
 */
@Entity(tableName = "DOWNLOADS")
public class DownloadInfo extends GalleryInfo {

	@ColumnInfo(name = "STATE")
	public int state;
	@ColumnInfo(name = "LEGACY")
	public int legacy;
	@ColumnInfo(name = "TIME")
	public long time;
	@ColumnInfo(name = "LABEL")
	public String label;
	@ColumnInfo(name = "ARCHIVE_URI")
	public String archiveUri; // URI for imported archive files
	public static final Creator<DownloadInfo> CREATOR = new Creator<DownloadInfo>() {
		@Override
		public DownloadInfo createFromParcel(Parcel source) {
			return new DownloadInfo(source);
		}

		@Override
		public DownloadInfo[] newArray(int size) {
			return new DownloadInfo[size];
		}
	};
	public static final int STATE_INVALID = -1;
	public static final int STATE_NONE = 0;
	public static final int STATE_WAIT = 1;
	public static final int STATE_DOWNLOAD = 2;
	public static final int STATE_FINISH = 3;
	public static final int STATE_FAILED = 4;
	public static final int STATE_UPDATE = 5;
	public static final int GOTO_NEW = 6;
	public static final int STATE_RELAY_DOWNLOAD = 7;
	@Ignore
	public long speed;
	@Ignore
	public long remaining;
	@ColumnInfo(name = "FINISHED")
	public int finished;
	@Ignore
	public int downloaded;
	@Ignore
	public int total;
	@Ignore
	public long fileSize = -1; // 文件夹总大小（字节），-1表示未计算

	// incremental download info
	@Ignore
	public boolean incremental;
	@Ignore
	public int copyCount;
	@Ignore
	public int networkCount;

	// download phase indicator
	public static final int PHASE_IDLE = -1;
	public static final int PHASE_COPY = 0;
	public static final int PHASE_DOWNLOAD = 1;
	public static final int PHASE_VERIFY = 2;
	public static final int PHASE_MERGE = 3;
	// 等待列表预碰撞检测阶段：拉取同名等待项的图片 token 集并对比，
	// 区分于 PHASE_MERGE（预下载合并），避免取消按钮误判。
	public static final int PHASE_COLLISION_CHECK = 4;
	@Ignore
	public int phase = PHASE_IDLE;

	// transient UI state: 预下载合并/预碰撞检测的实时步骤说明（不入库）
	@Ignore
	public String mergeDetail;

	// transient: 本次会话中已确认画廊被源站移除（404/410/Pining/Unavailable）。
	// 只有该标记为 true 时，「将已移除画廊视为已完成」设置才会生效。
	@Ignore
	public boolean removedFromSource = false;

	// 本地评分覆盖，0.0 - 5.0 星星刻度，-1 表示未设置。
	// 该字段不入库（本地评分存储在 SharedPreferences，见 LocalRatingManager），
	// 仅作为运行时缓存，并在 DownloadManager 初始化时填充。
	@Ignore
	public float localRating = -1f;


	public DownloadInfo() {
	}

	@Ignore
	public DownloadInfo(long gid) {
		this.gid = gid;
	}

	@Ignore
	public DownloadInfo(long gid, String token, String title, String titleJpn, String thumb, int category,
			String posted, String uploader, float rating, String simpleLanguage, int state, int legacy, long time,
			String label, String archiveUri) {
		this.gid = gid;
		this.token = token;
		this.title = title;
		this.titleJpn = titleJpn;
		this.thumb = thumb;
		this.category = category;
		this.posted = posted;
		this.uploader = uploader;
		this.rating = rating;
		this.simpleLanguage = simpleLanguage;
		this.state = state;
		this.legacy = legacy;
		this.time = time;
		this.label = label;
		this.archiveUri = archiveUri;
	}

	public long getGid() {
		return gid;
	}

	public void setGid(long gid) {
		this.gid = gid;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getTitleJpn() {
		return titleJpn;
	}

	public void setTitleJpn(String titleJpn) {
		this.titleJpn = titleJpn;
	}

	public String getThumb() {
		return thumb;
	}

	public void setThumb(String thumb) {
		this.thumb = thumb;
	}

	public int getCategory() {
		return category;
	}

	public void setCategory(int category) {
		this.category = category;
	}

	public String getPosted() {
		return posted;
	}

	public void setPosted(String posted) {
		this.posted = posted;
	}

	public String getUploader() {
		return uploader;
	}

	public void setUploader(String uploader) {
		this.uploader = uploader;
	}

	public float getRating() {
		return rating;
	}

	public void setRating(float rating) {
		this.rating = rating;
	}

	public String getSimpleLanguage() {
		return simpleLanguage;
	}

	public void setSimpleLanguage(String simpleLanguage) {
		this.simpleLanguage = simpleLanguage;
	}

	public int getState() {
		return state;
	}

	public void setState(int state) {
		this.state = state;
	}

	public int getLegacy() {
		return legacy;
	}

	public void setLegacy(int legacy) {
		this.legacy = legacy;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public boolean isIncremental() {
		return incremental;
	}

	public void setIncremental(boolean incremental) {
		this.incremental = incremental;
	}

	public int getCopyCount() {
		return copyCount;
	}

	public void setCopyCount(int copyCount) {
		this.copyCount = copyCount;
	}

	public int getNetworkCount() {
		return networkCount;
	}

	public void setNetworkCount(int networkCount) {
		this.networkCount = networkCount;
	}

	public String getArchiveUri() {
		return archiveUri;
	}

	public void setArchiveUri(String archiveUri) {
		this.archiveUri = archiveUri;
	}

	public float getLocalRating() {
		return localRating;
	}

	public void setLocalRating(float localRating) {
		this.localRating = localRating;
	}

	public boolean hasLocalRating() {
		return localRating > 0f;
	}

	/**
	 * 有效评分：优先本地评分，其次在线评分。
	 */
	public float getEffectiveRating() {
		return localRating > 0f ? localRating : rating;
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		super.writeToParcel(dest, flags);
		dest.writeInt(this.state);
		dest.writeInt(this.legacy);
		dest.writeLong(this.time);
		dest.writeString(this.label);
		dest.writeString(this.archiveUri);
		dest.writeFloat(this.localRating);
	}

	protected DownloadInfo(Parcel in) {
		super(in);
		this.state = in.readInt();
		this.legacy = in.readInt();
		this.time = in.readLong();
		this.label = in.readString();
		this.archiveUri = in.readString();
		try {
			this.localRating = in.readFloat();
		} catch (RuntimeException e) {
			this.localRating = -1f;
		}
	}

	@Ignore
	public DownloadInfo(GalleryInfo galleryInfo) {
		this.gid = galleryInfo.gid;
		this.token = galleryInfo.token;
		this.title = galleryInfo.title;
		this.titleJpn = galleryInfo.titleJpn;
		this.thumb = galleryInfo.thumb;
		this.category = galleryInfo.category;
		this.posted = galleryInfo.posted;
		this.uploader = galleryInfo.uploader;
		this.rating = galleryInfo.rating;
		this.simpleTags = galleryInfo.simpleTags;
		this.simpleLanguage = galleryInfo.simpleLanguage;
		// 页数是持久化字段：从历史/磁盘恢复时必须在建记录时就带上，
		// 否则列表里会出现「0 页」，也无法算出完成度。
		this.pages = galleryInfo.pages;
	}

	public void updateInfo(GalleryInfo galleryInfo) {
		this.token = galleryInfo.token;
		this.title = galleryInfo.title;
		this.titleJpn = galleryInfo.titleJpn;
		this.thumb = galleryInfo.thumb;
		this.category = galleryInfo.category;
		this.posted = galleryInfo.posted;
		this.uploader = galleryInfo.uploader;
		this.rating = galleryInfo.rating;
		this.simpleTags = galleryInfo.simpleTags;
		this.simpleLanguage = galleryInfo.simpleLanguage;
		if (galleryInfo.pages > 0) {
			this.pages = galleryInfo.pages;
		}
	}

	public JSONObject toJson() {
		JSONObject jsonObject = super.toJson();
		jsonObject.put("finished", finished);
		jsonObject.put("legacy", legacy);
		jsonObject.put("label", label);
		jsonObject.put("downloaded", downloaded);
		jsonObject.put("remaining", remaining);
		jsonObject.put("speed", speed);
		jsonObject.put("state", state);
		jsonObject.put("time", time);
		jsonObject.put("total", total);
		jsonObject.put("archiveUri", archiveUri);
		jsonObject.put("localRating", localRating);
		return jsonObject;
	}

	public static DownloadInfo downloadInfoFromJson(JSONObject object) throws ClassCastException {
		DownloadInfo downloadInfo = (DownloadInfo) GalleryInfo.galleryInfoFromJson(object);
		downloadInfo.finished = object.getIntValue("finished");
		downloadInfo.legacy = object.getIntValue("legacy");
		downloadInfo.label = object.getString("label");
		downloadInfo.downloaded = object.getIntValue("downloaded");
		downloadInfo.remaining = object.getLongValue("remaining");
		downloadInfo.speed = object.getLongValue("speed");
		downloadInfo.state = object.getIntValue("state");
		downloadInfo.time = object.getLongValue("time");
		downloadInfo.total = object.getIntValue("total");
		downloadInfo.archiveUri = object.getString("archiveUri");
		downloadInfo.localRating = object.getFloatValue("localRating");
		return downloadInfo;
	}

}
