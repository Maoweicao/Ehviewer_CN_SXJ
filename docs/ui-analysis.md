# EHViewer CN SXJ — UI 组件分析文档

> 本文档基于源码扫描自动生成，涵盖主界面、画廊列表、阅读器、下载管理、收藏、历史记录等核心 UI 组件。
> 可作为中英双语文档的素材来源。

---

## 1. 主界面 (MainActivity) — 导航架构

**源码**: `app/src/main/java/com/hippo/ehviewer/ui/MainActivity.java`  
**布局**: `app/src/main/res/layout/activity_main.xml`  
**菜单**: `app/src/main/res/menu/nav_drawer_main.xml`

### 1.1 整体布局结构

```
SafeCoordinatorLayout
  └── EhDrawerLayout (双侧抽屉)
        ├── EhStageLayout (fragment_container) — Scene 容器
        ├── EhNavigationView (左侧导航抽屉)
        │     ├── NavigationView (nav_view) — 导航菜单
        │     ├── LimitsCountView — 配额显示
        │     └── TextView (change_theme) — 主题切换按钮
        └── EhDrawerView (right_drawer) — 右侧抽屉 (各 Scene 自定义)
```

### 1.2 左侧导航菜单项

| 菜单 ID | 图标 | 标题 | 对应 Scene | 说明 |
|---------|------|------|-----------|------|
| `nav_homepage` | homepage_black_x24 | 首页 | GalleryListScene (ACTION_HOMEPAGE) | 主页画廊列表 |
| `nav_subscription` | eh_subscription_black_x24 | 订阅 | GalleryListScene (ACTION_SUBSCRIPTION) | 订阅内容 |
| `nav_whats_hot` | fire_black_x24 | 热门 | GalleryListScene (ACTION_WHATS_HOT) | 热门画廊 |
| `nav_top_lists` | top_lists_x24 | 排行榜 | EhTopListScene | 画廊排行榜 |
| `nav_favourite` | heart_black_x24 | 收藏 | FavoritesScene | 收藏管理 |
| `nav_history` | history_black_x24 | 历史 | HistoryScene | 浏览历史 |
| `nav_downloads` | download_black_x24 | 下载 | DownloadsScene | 下载管理 |
| `nav_settings` | settings_black_x24 | 设置 | SettingsActivity | 应用设置 |

### 1.3 启动流程

启动时依次检查：
1. 安全锁 (SecurityScene) → 2. 警告页 (WarningScene) → 3. 分析同意 (AnalyticsScene) → 4. 登录 (SignInScene) → 5. 站点选择 (SelectSiteScene) → 6. **GalleryListScene** (默认首页)

### 1.4 主题系统

支持三种主题，可通过导航栏底部按钮循环切换：
- **浅色** (THEME_LIGHT) — `AppTheme_Main`
- **深色** (THEME_DARK) — `AppTheme_Main_Dark`
- **纯黑** (THEME_BLACK) — `AppTheme_Main_Black`

### 1.5 其他功能

- **剪贴板监听**: 自动检测剪贴板中的画廊 URL，弹出 Snackbar 提示跳转
- **头像/壁纸自定义**: 点击导航头可更换头像，点击背景可更换壁纸 (支持 GIF 动态壁纸)
- **配额显示**: 打开导航抽屉时自动加载 LimitsCountView 显示 EH 配额

---

## 2. 画廊列表 (GalleryListScene) — 搜索与浏览

**源码**: `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/GalleryListScene.java`  
**布局**: `app/src/main/res/layout/scene_gallery_list.xml`

### 2.1 界面状态

| 状态 | 常量 | 说明 |
|------|------|------|
| NORMAL | STATE_NORMAL (0) | 正常列表显示 |
| SIMPLE_SEARCH | STATE_SIMPLE_SEARCH (1) | 简单搜索模式 |
| SEARCH | STATE_SEARCH (2) | 展开搜索面板 |
| SEARCH_SHOW_LIST | STATE_SEARCH_SHOW_LIST (3) | 搜索面板+列表同时显示 |

### 2.2 搜索功能

**搜索栏 (SearchBar)**: 位于顶部，支持以下搜索模式：

| 搜索模式 | ListUrlBuilder 常量 | 说明 |
|---------|-------------------|------|
| 普通搜索 | MODE_NORMAL (0x0) | 关键词搜索 |
| 上传者搜索 | MODE_UPLOADER (0x1) | 按上传者名搜索 |
| 标签搜索 | MODE_TAG (0x2) | 按标签搜索 |
| 热门 | MODE_WHATS_HOT (0x3) | 热门画廊 |
| 以图搜图 | MODE_IMAGE_SEARCH (0x4) | 图片相似度搜索 |
| 订阅 | MODE_SUBSCRIPTION (0x5) | 订阅内容 |
| 过滤 | MODE_FILTER (0x6) | 过滤搜索 |
| 排行榜 | MODE_TOP_LIST (0x7) | 排行榜 |

**SearchLayout 面板** (`widget/SearchLayout.java`):
- **普通搜索模式** (SEARCH_MODE_NORMAL):
  - 分类选择表 (CategoryTable): 11 种分类复选框
  - 搜索模式切换 (RadioGridGroup): 普通搜索 / 订阅搜索
  - 高级搜索开关: 展开高级搜索选项 (AdvanceSearchTable)
- **以图搜图模式** (SEARCH_MODE_IMAGE):
  - 图片选择器 (ImageSearchLayout)
  - 相似度扫描开关

**高级搜索** (AdvanceSearchTable): 包含 `SNAME`(搜索名称) 和 `STAGS`(搜索标签) 等选项。

### 2.3 列表显示模式

通过 `Settings.getListMode()` 控制，值域 `KEY_LIST_MODE`:
- **模式 0** (默认): 缩略图网格模式 (详细尺寸由 `Settings.getDetailSize()` 控制)
- 列表使用 `AutoStaggeredGridLayoutManager` 实现瀑布流布局
- 每个列表项显示: 缩略图、标题、上传者、评分、分类标签、发布时间、语言

### 2.4 排序与筛选

画廊列表排序由 `ListUrlBuilder` 构建 URL 参数实现，支持:
- 分类筛选 (11 种 EH 分类)
- 关键词搜索 (支持标签语法)
- 日期范围 (`jump=` 参数: 年/月/周/日)
- 评分筛选 (最低评分)
- 高级搜索选项

### 2.5 FAB 浮动按钮

- **主 FAB**: 长按进入多选模式
- **展开 FAB**: 收藏/下载等快捷操作
- 滚动时自动隐藏/显示

### 2.6 右侧抽屉

- 书签抽屉 (BookmarksDraw): 快速搜索管理
- 订阅抽屉 (SubscriptionDraw): 订阅管理

### 2.7 多选模式

支持多选下载:
- `mMultiSelectMode`: 多选开关
- `mSelectedGalleryList`: 选中画廊列表
- 长按 FAB 进入多选模式

---

## 3. 画廊详情 (GalleryDetailScene)

**源码**: `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/detail/GalleryDetailScene.java`  
**布局**: `app/src/main/res/layout/scene_gallery_detail.xml`

### 3.1 页面结构

```
ScrollView
  └── below_header
        ├── header (头部区域)
        │     ├── color_bg (分类颜色背景)
        │     ├── thumb (缩略图)
        │     ├── title (标题 — 可点击)
        │     ├── uploader (上传者 — 可点击/长按)
        │     ├── category (分类标签 — 可点击)
        │     ├── other_actions (更多操作按钮)
        │     ├── new_version (新版本提示)
        │     ├── archiver_download_progress (Archiver 下载进度)
        │     └── action_card (操作按钮组)
        │           ├── download (下载按钮 — 可点击/长按)
        │           └── read (阅读按钮)
        ├── info (信息区域)
        │     ├── language (语言)
        │     ├── pages (页数)
        │     ├── size (大小)
        │     ├── posted (发布时间)
        │     └── favoredTimes (收藏次数)
        ├── actions (操作区域)
        │     ├── rating_text (评分文字)
        │     ├── rating (评分星级)
        │     └── ... (更多操作按钮)
        ├── tags (标签区域)
        ├── comments (评论区域)
        └── previews (预览图区域)
```

### 3.2 进入方式

| Action | 说明 |
|--------|------|
| ACTION_GALLERY_INFO | 从 GalleryInfo 对象进入 (列表点击) |
| ACTION_DOWNLOAD_GALLERY_INFO | 从下载列表进入 |
| ACTION_GID_TOKEN | 从 GID + Token 进入 (URL 跳转) |

### 3.3 交互功能

- **下载按钮**: 点击开始下载，长按显示下载选项 (分类选择)
- **阅读按钮**: 进入阅读器
- **上传者**: 点击搜索该上传者的画廊，长按复制
- **分类标签**: 点击按分类筛选
- **标题**: 点击复制标题
- **其他操作**: 分享、收藏、查看信息、查看评论、查看预览图

---

## 4. 画廊阅读器 (GalleryActivity)

**源码**: `app/src/main/java/com/hippo/ehviewer/ui/GalleryActivity.java`  
**布局**: `app/src/main/res/layout/activity_gallery.xml`

### 4.1 阅读方向 (LayoutMode)

| 常量 | 值 | 说明 |
|------|---|------|
| LAYOUT_LEFT_TO_RIGHT | 0 | 左→右翻页 |
| LAYOUT_RIGHT_TO_LEFT | 1 | 右→左翻页 (默认，日漫习惯) |
| LAYOUT_TOP_TO_BOTTOM | 2 | 上→下滚动 |

**默认**: `LAYOUT_RIGHT_TO_LEFT` (右→左)

### 4.2 缩放模式 (ScaleMode)

| 模式 | 说明 |
|------|------|
| SCALE_ORIGIN | 原始尺寸 |
| SCALE_FIT | 适应屏幕 |
| SCALE_FIT_WIDTH | 适应宽度 |
| SCALE_FIT_HEIGHT | 适应高度 |

### 4.3 翻页方式

| 方式 | 说明 |
|------|------|
| 手势滑动 | 左右/上下滑动翻页 |
| 点击翻页 | 点击屏幕边缘翻页 (可禁用) |
| 音量键翻页 | 音量+/- 翻页 (可启用/反转) |
| 键盘翻页 | Page Up/Down、方向键翻页 |
| 鼠标滚轮 | 滚轮翻页 (支持方向反转) |

### 4.4 自动翻页 (Auto Transfer)

- **开关**: 底部面板播放按钮切换
- **时间控制**: 静态图片翻页间隔 (默认 4 秒)、动态图片翻页间隔 (默认 8 秒)
- **快速阅读模式**: 可设置快速翻页时间 (0.1-1.0 秒)
- **等待动画**: 等待当前页加载/动画完成后再翻页
- **倒计时显示**: 可选显示翻页倒计时 (最后 15 秒)

### 4.5 阅读器菜单 (GalleryMenuHelper)

点击屏幕中央区域弹出设置对话框:

| 设置项 | 类型 | 说明 |
|--------|------|------|
| 屏幕旋转 | Spinner | 自动/竖屏/横屏/传感器 |
| 阅读方向 | Spinner | 左→右/右→左/上→下 |
| 页面缩放 | Spinner | 原始/适应/适应宽度/适应高度 |
| 起始位置 | Spinner | 左上/右上/左下/右下 |
| 快速翻页时间 | 数值 (0.1-1.0s) | 步进 0.5s |
| 静态图片翻页时间 | 数值 (秒) | 自动翻页间隔 |
| 动态图片翻页时间 | 数值 (秒) | GIF/APNG 翻页间隔 |
| 等待动画完成 | Switch | 等待加载完成再翻页 |
| 显示翻页倒计时 | Switch | 显示倒计时 |
| 禁用点击翻页 | Switch | 禁用点击屏幕翻页 |
| 禁用手势翻页 | Switch | 禁用手势滑动翻页 |
| 保持屏幕常亮 | Switch | 阅读时屏幕不熄灭 |
| 显示时钟 | Switch | 顶部状态栏时钟 |
| 显示进度 | Switch | 页码进度文字 |
| 显示电量 | Switch | 电量指示器 |
| 显示翻页间隔 | Switch | 页间分隔线 |
| 音量键翻页 | Switch | 音量键控制翻页 |
| 反转音量键 | Switch | 反转音量键方向 |
| 全屏阅读 | Switch | 隐藏系统 UI |
| 自定义屏幕亮度 | Switch | 0-200 亮度调节 |

### 4.6 底部控制面板

- **SeekBar 进度条**: 拖动快速跳页，显示当前页/总页数
- **自动翻书面板**: 播放/暂停按钮，带倒计时显示
- **底部指示器**: 点击展开控制面板
- **标题栏**: 顶部显示画廊标题，带返回按钮和菜单按钮

### 4.7 长按操作

长按页面弹出对话框:
- **分享图片**: 分享当前页图片
- **保存图片**: 保存到本地相册
- **另存为**: 选择保存位置
- **AI 翻译**: (实验功能) 翻译图片中的文字

### 4.8 画中画 (PiP) 模式

支持画中画小窗模式:
- 缩略图、标题、进度显示
- 上一页/下一页/首页/播放按钮
- 恢复全屏按钮

### 4.9 数据源

| Action | Provider | 说明 |
|--------|----------|------|
| ACTION_EH | EhGalleryProvider | 在线 EH 画廊 |
| ACTION_DIR | DirGalleryProvider | 本地文件夹 |
| ACTION_VIEW | ArchiveGalleryProvider | ZIP/CBR 压缩包 |

### 4.10 AI 翻译 (实验功能)

- 通过 `AiTranslateManager` 实现
- 使用 `TranslateOverlayView` 叠加翻译结果
- 可跳过动态图片

---

## 5. 下载管理 (DownloadsScene)

**源码**: `app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsScene.java`  
**布局**: `app/src/main/res/layout/scene_download.xml`  
**菜单**: `app/src/main/res/menu/scene_download.xml`

### 5.1 界面结构

- **顶部**: 工具栏 (标题显示当前标签名 + 数量)
- **分类筛选**: Spinner 下拉选择 (全部/同人志/漫画/画师CG/游戏CG/西方/非H/图集/Cosplay/亚洲/杂项)
- **标签系统**: 左侧抽屉显示下载标签列表 (DownloadLabelDraw)
- **列表**: RecyclerView 瀑布流布局，支持拖拽排序
- **分页**: PaginationIndicator 分页指示器 (可选 50/100/200/300/500 条/页)
- **搜索栏**: SearchBar 支持搜索下载内容

### 5.2 工具栏菜单功能

| 菜单项 | 说明 |
|--------|------|
| 排序/筛选 (子菜单) | 筛选 + 排序选项 |
| 全部开始 | 启动所有下载任务 |
| 全部暂停 | 暂停所有下载任务 |
| 重置阅读进度 | 清除阅读进度记录 |
| 搜索下载画廊 | 在下载列表中搜索 |
| 导入本地压缩包 | 导入 ZIP/CBR 文件 |

### 5.3 筛选功能

**按状态筛选**:
- 全部 / 已完成 / 未开始 / 等待中 / 下载中 / 失败

**按分类筛选**:
- 全部 / 杂项 / 同人志 / 漫画 / 画师CG / 游戏CG / 图集 / Cosplay / 亚洲 / 非H / 西方 / 未知

### 5.4 排序选项

| 排序方式 | 说明 |
|----------|------|
| 默认排序 | 添加顺序 |
| 画廊 ID 升序/降序 | 按 GID 排序 |
| 创建时间 升序/降序 | 按添加时间排序 |
| 评分 升序/降序 | 按评分排序 |
| 名称 升序/降序 | 按标题字母排序 |
| 文件大小 升序/降序 | 按文件大小排序 |

### 5.5 列表项操作

- **点击**: 进入画廊详情 / 继续阅读
- **长按**: 弹出操作菜单 (带缩略图预览 + 标签 Chips)
- **拖拽**: 拖拽调整下载顺序 (DragDropManager)
- **FAB**: 搜索 / 筛选快捷操作

### 5.6 高级功能

- **分页控制**: 大量下载时自动分页 (可配置每页数量)
- **标签管理**: 支持自定义下载标签分类
- **压缩导出**: 可选压缩画廊 (CompressSelectedGalleriesTask)
- **进度追踪**: SpiderInfo 追踪下载进度
- **未读标记**: 支持显示未读下载数量

---

## 6. 收藏管理 (FavoritesScene)

**源码**: `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/FavoritesScene.kt`  
**布局**: `app/src/main/res/layout/scene_favorites.xml`

### 6.1 收藏夹类型

| 类型 | FavListUrlBuilder 常量 | 说明 |
|------|----------------------|------|
| 云端收藏夹 0-9 | FAV_CAT (0-9) | 10 个自定义命名的云端收藏夹 |
| 本地收藏夹 | FAV_CAT_LOCAL | 本地存储的收藏 |
| 全部云端 | (特殊值) | 显示所有云端收藏 |

### 6.2 界面结构

- **顶部**: SearchBar (显示当前收藏夹名称 + 搜索框)
- **列表**: RecyclerView，支持多种布局模式 (由 Settings.getListMode() 控制)
- **右侧抽屉**: 收藏夹选择器 (FavDrawerAdapter)
  - 10 个云端收藏夹 (带数量显示)
  - 1 个本地收藏夹
  - 默认收藏夹设置菜单

### 6.3 搜索功能

- 支持在当前收藏夹内搜索关键词
- SearchBar 标题格式: `收藏夹名 — 关键词`
- 提示文字: `搜索 收藏夹名`

### 6.4 操作功能

- **点击**: 进入画廊详情
- **长按**: 弹出操作菜单
- **FAB**: 展开后显示:
  - 添加到收藏夹
  - 移动到其他收藏夹
  - 从收藏夹移除
- **长按 FAB**: 进入多选模式 (CustomChoiceListener)
- **多选操作**: 批量移动/删除/下载

### 6.5 默认收藏夹

通过右侧抽屉菜单设置默认收藏夹:
- "让我选择" (每次弹出选择)
- 本地收藏夹
- 10 个云端收藏夹

### 6.6 新手引导

首次进入时显示 ShowcaseView 引导用户打开右侧抽屉管理收藏夹。

---

## 7. 浏览历史 (HistoryScene)

**源码**: `app/src/main/java/com/hippo/ehviewer/ui/scene/history/HistoryScene.java`  
**布局**: `app/src/main/res/layout/scene_history.xml`

### 7.1 界面结构

- **顶部**: 工具栏 (标题 "历史记录"，返回按钮)
- **列表**: RecyclerView 瀑布流布局 (AutoStaggeredGridLayoutManager)
- **空状态**: 大图标 + 提示文字

### 7.2 列表项内容

每个历史记录项显示:
- **缩略图** (LoadImageView)
- **标题** (TextView)
- **上传者** (TextView)
- **评分** (SimpleRatingView)
- **分类标签** (TextView，带分类颜色背景)
- **发布时间** (TextView)
- **语言标记** (TextView)

### 7.3 交互功能

| 操作 | 说明 |
|------|------|
| 点击 | 进入画廊详情 (带缩略图转场动画) |
| 长按 | 弹出菜单: 下载 / 添加到收藏 |
| 左滑删除 | 滑动删除单条历史记录 |
| 菜单 → 清除全部 | 确认后清除所有历史记录 |

### 7.4 技术实现

- 使用 GreenDAO `LazyList<HistoryInfo>` 懒加载数据库记录
- 支持 `RecyclerViewSwipeManager` 滑动删除
- `SwipeDismissItemAnimator` 滑动删除动画
- `FastScroller` 快速滚动
- `TransitionNameFactory` 共享元素转场

---

## 8. 画廊评论 (GalleryCommentsScene)

**源码**: `app/src/main/java/com/hippo/ehviewer/ui/scene/GalleryCommentsScene.java`  
**布局**: `app/src/main/res/layout/scene_gallery_comments.xml`

### 8.1 功能

- 查看画廊评论列表
- 发表新评论
- 评论投票 (赞/踩)
- 显示评论者信息、时间、评分

---

## 9. 画廊预览 (GalleryPreviewsScene)

**源码**: `app/src/main/java/com/hippo/ehviewer/ui/scene/GalleryPreviewsScene.java`  
**布局**: `app/src/main/res/layout/scene_gallery_previews.xml`

### 9.1 功能

- 网格显示画廊所有页面的缩略图
- 点击跳转到对应页面
- 显示页码

---

## 10. 核心 UI 组件

### 10.1 SearchBar (搜索栏)

**源码**: `app/src/main/java/com/hippo/ehviewer/widget/SearchBar.java`

- 左侧图标: 导航箭头 (DrawerArrowDrawable)
- 右侧图标: 搜索/清除
- 支持搜索建议 (标签数据库)
- 状态变化监听 (展开/收起)

### 10.2 FabLayout (浮动按钮布局)

**源码**: `com.hippo.widget.FabLayout`

- 主 FAB + 展开 FAB 列表
- 支持自动取消
- 可显示功能名称
- 长按支持

### 10.3 ContentLayout (内容布局)

**源码**: `com.hippo.widget.ContentLayout`

- 三态视图: 内容 / 进度 / 空状态
- 下拉刷新 (RefreshLayout)
- 快速滚动 (FastScroller)

### 10.4 EasyRecyclerView

**源码**: `com.hippo.easyrecyclerview.EasyRecyclerView`

- 封装 RecyclerView
- 支持单选/多选模式
- 项目点击/长按监听
- Ripple 水波纹效果

### 10.5 GalleryView (画廊视图)

**源码**: `app/src/main/java/com/hippo/lib/glgallery/GalleryView.java`

- OpenGL ES 渲染的画廊视图
- 支持三种布局模式 (LTR/RTL/TTB)
- 手势识别: 点击/长按/滑动/缩放
- 区域划分: 菜单区域 / 滑块区域 / 翻页区域

### 10.6 AutoStaggeredGridLayoutManager

- 自适应瀑布流布局
- 按最小尺寸策略排列
- 支持列数自动调整

---

## 11. 跳转与深度链接

### 11.1 URL 处理

`EhUrlOpener.parseUrl()` 支持解析的 URL 类型:
- 画廊详情页 (`/g/{gid}/{token}/`)
- 画廊列表页 (搜索 URL)
- 画廊页面 (`/s/{token}/{gid}-{page}`)

### 11.2 Intent 处理

| Intent Action | 处理方式 |
|---------------|---------|
| ACTION_VIEW | 解析 URL 跳转到对应页面 |
| ACTION_SEND (text/plain) | 将文本作为搜索关键词 |
| ACTION_SEND (image/*) | 以图搜图 |

### 11.3 Scene 启动模式

| 模式 | 说明 | 用于 |
|------|------|------|
| SINGLE_TASK | 单实例，重复使用 | 登录、下载、收藏、历史 |
| SINGLE_TOP | 栈顶复用 | 画廊列表 |
| STANDARD | 标准新建 | 画廊详情、评论、预览 |

---

## 附录: 关键源码路径

| 组件 | 路径 |
|------|------|
| 主 Activity | `app/src/main/java/com/hippo/ehviewer/ui/MainActivity.java` |
| 画廊列表 | `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/GalleryListScene.java` |
| 画廊详情 | `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/detail/GalleryDetailScene.java` |
| 阅读器 | `app/src/main/java/com/hippo/ehviewer/ui/GalleryActivity.java` |
| 下载管理 | `app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsScene.java` |
| 收藏管理 | `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/FavoritesScene.kt` |
| 历史记录 | `app/src/main/java/com/hippo/ehviewer/ui/scene/history/HistoryScene.java` |
| 评论 | `app/src/main/java/com/hippo/ehviewer/ui/scene/GalleryCommentsScene.java` |
| 预览图 | `app/src/main/java/com/hippo/ehviewer/ui/scene/GalleryPreviewsScene.java` |
| 搜索栏 | `app/src/main/java/com/hippo/ehviewer/widget/SearchBar.java` |
| 搜索布局 | `app/src/main/java/com/hippo/ehviewer/widget/SearchLayout.java` |
| 画廊视图 | `app/src/main/java/com/hippo/lib/glgallery/GalleryView.java` |
| URL 构建 | `app/src/main/java/com/hippo/ehviewer/client/data/ListUrlBuilder.java` |
| 设置 | `app/src/main/java/com/hippo/ehviewer/Settings.java` |
| 布局目录 | `app/src/main/res/layout/` |
| 菜单目录 | `app/src/main/res/menu/` |
