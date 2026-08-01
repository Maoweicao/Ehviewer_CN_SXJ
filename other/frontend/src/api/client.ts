export interface Gallery {
  gid: number;
  token: string;
  title: string;
  titleJpn?: string;
  thumb: string;
  category: string;
  categoryValue?: number;
  posted: string;
  uploader: string;
  rating: number;
  pages: number;
  language?: string;
  tags?: string[];
  label?: string;
  // 增强字段（与文件API对齐）
  state: number;
  time: number;
  createdTime: number;
  createdDate: string;
  size: number;
  sizeFormatted: string;
  fileCount: number;
  isFavorited?: boolean;
}

export interface GalleryDetail extends Gallery {
  apiUid: number;
  apiKey: string;
  torrentCount: number;
  torrentUrl: string;
  archiveUrl: string;
  parent: string;
  visible: string;
  favoriteCount: number;
  isFavorited: boolean;
  ratingCount: number;
  previewPages: number;
  comments: Comment[];
  /** 已下载页数 */
  downloadedPages?: number;
  /** 是否完整下载 */
  isComplete?: boolean;
}

export interface Comment {
  id: number;
  user: string;
  content: string;
  score: number;
  time: string;
}

export interface GalleryListResponse {
  total: number;
  page: number;
  limit: number;
  sort?: string;
  filter?: string;
  galleries: Gallery[];
}

export interface AsyncTaskResponse {
  success: boolean
  accepted: boolean
  taskId: string
  status: string
  total?: number
}

export interface TransferTaskStatus {
  taskId: string
  type: string
  subType?: string
  status: string
  progress: number
  total: number
  completed: number
  failed?: number
  error?: string
}

export interface Label {
  name: string;
  count: number;
}

// 高级排序可选字段
export const SORT_FIELDS = [
  { value: 'title', label: '标题' },
  { value: 'rating', label: '评分' },
  { value: 'pages', label: '页数' },
  { value: 'category', label: '分类' },
  { value: 'state', label: '状态' },
  { value: 'createdDate', label: '创建日期' },
  { value: 'size', label: '大小' },
  { value: 'uploader', label: '上传者' },
] as const;

export type SortField = typeof SORT_FIELDS[number]['value'];

// 画廊下载状态
export const GALLERY_STATES: Record<number, string> = {
  [-1]: '未下载',
  [0]: '无',
  [1]: '等待',
  [2]: '下载中',
  [3]: '已完成',
  [4]: '失败',
  [5]: '更新',
  [6]: '新建',
};

// 分类（与后端 CATEGORY_NAMES 对齐）
export const GALLERY_CATEGORIES = [
  'Misc', 'Doujinshi', 'Manga', 'Artist CG', 'Game CG',
  'Image Set', 'Cosplay', 'Asian Porn', 'Non-H', 'Western',
];

// 分类中文翻译映射
export const CATEGORY_LABELS: Record<string, string> = {
  Misc: '杂项',
  Doujinshi: '同人志',
  Manga: '漫画',
  'Artist CG': '画师CG',
  'Game CG': '游戏CG',
  'Image Set': '图集',
  Cosplay: 'Cosplay',
  'Asian Porn': '亚洲色情',
  'Non-H': '非H',
  Western: '西方',
};

// 分类颜色（e-hentai 标准颜色）
export const CATEGORY_COLORS: Record<string, string> = {
  Misc: '#607d8b',
  Doujinshi: '#e91e63',
  Manga: '#ff9800',
  'Artist CG': '#4caf50',
  'Game CG': '#2196f3',
  'Image Set': '#9c27b0',
  Cosplay: '#795548',
  'Asian Porn': '#ff5722',
  'Non-H': '#00bcd4',
  Western: '#795548',
};

export function getCategoryLabel(category: string): string {
  return CATEGORY_LABELS[category] || category;
}

export function getCategoryColor(category: string): string {
  return CATEGORY_COLORS[category] || '#2196f3';
}

// 可选每页数量
export const PAGE_SIZES = [30, 50, 100, 300, 500];

export interface Folder {
  name: string;
  path: string;
  fileCount: number;
  totalSize: number;
  totalSizeFormatted: string;
  displayName?: string;
  rootType?: string;
  isDirectory?: boolean;
}

export interface FileEntry {
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
  sizeFormatted: string;
  extension: string;
  lastModified: number;
  lastModifiedFormatted: string;
}

export interface EntriesResponse {
  root: string;
  path: string;
  parentPath: string;
  total: number;
  entries: FileEntry[];
}

export interface FileItem {
  name: string;
  path: string;
  size: number;
  sizeFormatted: string;
  lastModified: number;
  lastModifiedFormatted: string;
  extension: string;
  // 画廊相关属性
  isGallery?: boolean;
  gid?: number;
  title?: string;
  titleJpn?: string;
  thumb?: string;
  category?: number;
  pages?: number;
  fileCount?: number;
}

export interface FileListResponse {
  folder: string;
  total: number;
  page: number;
  limit: number;
  sort: string;
  order: string;
  files: FileItem[];
}

export interface FilePreviewResponse {
  name: string;
  size: number;
  sizeFormatted: string;
  type: string;
  content: string;
  truncated: boolean;
}

export interface AuthStatus {
  mode: 'none' | 'password' | 'token';
  authenticated: boolean;
  isLocalNetwork: boolean;
  remoteIp: string;
}

export interface LoginResponse {
  success: boolean;
  token: string;
  expires: number;
  mode: string;
}

export interface SystemInfo {
  deviceName: string;
  deviceManufacturer: string;
  androidVersion: string;
  sdkVersion: number;
  appVersion: string;
  appVersionCode: number;
  downloadLocation: string;
  totalGalleries: number;
  downloadedGalleries: number;
  totalPages: number;
  storageTotal: number;
  storageUsed: number;
  storageFree: number;
  storageTotalFormatted: string;
  storageUsedFormatted: string;
  storageFreeFormatted: string;
  authMode: string;
  deleteEnabled: boolean;
  syncDownloadEnabled: boolean;
  remoteManagementEnabled: boolean;
  /** 远程上传页面（POST /api/v1/galleries/{gid}/pages/{page}/upload）总开关 */
  pageUploadEnabled?: boolean;
}

export interface SystemStats {
  totalGalleries: number;
  downloading: number;
  waiting: number;
  finished: number;
  failed: number;
  none: number;
}

export interface PushTask {
  id: string;
  type: string;
  status: string;
  mode: string;
  createdTime: number;
  fromDevice: string;
  progress: number;
  total: number;
  transferred: number;
}

export interface ReceiveSettings {
  autoReceiveBookmarks: boolean;
  autoReceiveDownloads: boolean;
  autoReceiveFavorites: boolean;
  pageSize: number;
}

// ==================== Data Export/Import Types ====================

export interface ExportFile {
  name: string;
  path: string;
  size: number;
  sizeFormatted: string;
  lastModified: number;
  lastModifiedFormatted: string;
  type: 'db' | 'csv';
}

export interface ExportItem {
  gid: number;
  token?: string;
  title?: string;
  titleJpn?: string;
  thumb?: string;
  category?: string | number;
  posted?: string;
  uploader?: string;
  rating?: number;
  pages?: number;
  simpleLanguage?: string;
  simpleTags?: string[];
  state?: number;
  legacy?: number;
  time?: number;
  label?: string;
  favCat?: string;
  favNote?: string;
}

export interface ExportData {
  type: string;
  exportTime: number;
  total: number;
  catNames?: string[];
  catCounts?: number[];
  items: ExportItem[];
}

export interface ImportResult {
  success: boolean;
  imported: number;
  skipped: number;
  failed: number;
  conflicts?: Array<{
    gid: number;
    existingTitle: string;
    newTitle: string;
  }>;
}

// ==================== Compress Types ====================

export interface CompressOutputFile {
  name: string;
  path: string;
  size: number;
  sizeFormatted: string;
}

export interface CompressTask {
  taskId: string;
  status: 'pending' | 'in_progress' | 'completed' | 'failed' | 'cancelled';
  totalGalleries: number;
  completedGalleries: number;
  progress: number;
  splitSizeMB?: number;
  outputFiles: CompressOutputFile[];
  createdTime: number;
  completedTime: number | null;
}

export interface CompressGalleryScan {
  folderName: string;
  gid: number | null;
  title: string;
  hasMetadata: boolean;
  fileCount: number;
  totalSize: number;
  isDuplicate: boolean;
}

export interface CompressImportScan {
  importId: string;
  fileName: string;
  fileSize: number;
  totalGalleries: number;
  galleries: CompressGalleryScan[];
  duplicates: Array<{
    gid: number;
    existingTitle: string;
    newTitle: string;
  }>;
}

export interface CompressImportResult {
  success: boolean;
  imported: number;
  skipped: number;
  failed: number;
  details: Array<{
    gid: number | null;
    status: string;
    message: string;
  }>;
}

// ==================== Unified Task Types ====================

export interface UnifiedTaskItem {
  taskId: string;
  type: 'push' | 'compress';
  subType?: string;
  status: string;
  sourceDevice?: string;
  progress: number;
  total: number;
  completed: number;
  createdTime: number;
  updatedTime?: number;
  completedTime?: number | null;
  // Push specific
  mode?: string;
  fileName?: string;
  fileSize?: number;
  totalChunks?: number;
  receivedChunks?: number;
  receivedBytes?: number;
  // Compress specific
  splitSizeMB?: number;
  outputFiles?: Array<{ name: string; path: string; size: number; sizeFormatted: string }>;
}

// ==================== Integrity Check Types ====================

export interface FileHashInfo {
  filename: string;
  path: string;
  size: number;
  sizeFormatted: string;
  hash: string;
  algorithm: string;
  lastModified: number;
  lastModifiedFormatted: string;
}

export interface GalleryIntegrityFile {
  filename: string;
  size: number;
  sizeFormatted: string;
  hash: string;
}

export interface GalleryIntegrity {
  gid: number;
  title: string;
  folderName: string;
  totalFiles: number;
  totalSize: number;
  totalSizeFormatted: string;
  algorithm: string;
  files: GalleryIntegrityFile[];
}

export interface VerifyDetail {
  filename: string;
  status: 'match' | 'mismatch' | 'extra';
  remoteHash?: string;
  localHash?: string;
  remoteSize?: number;
  localSize?: number;
}

export interface GalleryVerifyResult {
  gid: number;
  totalFiles: number;
  verifiedFiles: number;
  match: number;
  mismatch: number;
  missing: number;
  details: VerifyDetail[];
  missingFiles: string[];
}

// ==================== Download Management Types ====================

export interface DownloadItem {
  gid: number;
  token: string;
  title: string;
  titleJpn?: string;
  thumb?: string;
  category: number;
  posted?: string;
  uploader?: string;
  rating: number;
  language?: string;
  pages: number;
  state: number;
  stateName: string;
  label?: string;
  time: number;
  createdTime: number;
  createdDate: string;
  finished: number;
  total: number;
  downloaded: number;
  speed?: number;
  speedFormatted?: string;
  remaining?: number;
  progress: number;
  legacy?: number;
  fileSize?: number;
  simpleTags?: string[];
}

export interface DownloadListResponse {
  total: number;
  page: number;
  limit: number;
  downloads: DownloadItem[];
}

// ==================== Relay Download Types ====================

export type RelayStatus =
  | 'pending'
  | 'accepted'
  | 'downloading'
  | 'completed'
  | 'returned'
  | 'rejected'
  | 'cancelled'
  | 'failed'

export type RelayDirection = 'incoming' | 'outgoing'

export interface RelayTask {
  taskId: string;
  gid: number;
  token?: string;
  title?: string;
  titleJpn?: string;
  thumb?: string;
  category?: number;
  posted?: string;
  uploader?: string;
  rating?: number;
  pages?: number;
  status: RelayStatus;
  statusName: string;
  direction: RelayDirection;
  directionName: string;
  sourceDevice?: string;
  sourceDeviceId?: string;
  targetDevice?: string;
  targetDeviceId?: string;
  acceptedDevice?: string;
  acceptedDeviceId?: string;
  acceptedTime?: number;
  acceptedDate?: string;
  priority: string;
  autoReturn: boolean;
  finished: number;
  total: number;
  speed: number;
  speedFormatted?: string;
  downloadedSize: number;
  downloadedSizeFormatted?: string;
  totalSize: number;
  totalSizeFormatted?: string;
  progress: number;
  createdTime: number;
  updatedTime: number;
  completedTime?: number | null;
  returnedTime?: number | null;
  createdDate?: string;
  updatedDate?: string;
  completedDate?: string | null;
  returnedDate?: string | null;
  zipFileSize?: number;
  zipFileSizeFormatted?: string;
  errorMessage?: string;
}

export interface RelayTaskListResponse {
  tasks: RelayTask[];
  total: number;
}

export interface RelayCreateOptions {
  sourceDevice?: string;
  sourceDeviceId?: string;
  sourcePort?: number;
  targetDevice?: string;
  targetDeviceId?: string;
  priority?: 'low' | 'normal' | 'high';
  autoReturn?: boolean;
}

export interface RelayAcceptOptions {
  acceptedDevice?: string;
  acceptedDeviceId?: string;
}

// ==================== Page Upload Types ====================

export interface PageUploadOptions {
  extension?: string;
  hash?: string;
  algorithm?: 'md5' | 'sha1' | 'sha256';
  /** 自动计算 hash 并附带（默认 true） */
  autoHash?: boolean;
  /** 当下载列表中无此 gid 时，附带 GalleryInfo 自动创建 */
  galleryInfo?: {
    gid: number;
    token?: string;
    title: string;
    titleJpn?: string;
    thumb?: string;
    category?: number;
    posted?: string;
    uploader?: string;
    rating?: number;
    pages?: number;
  };
}

export interface PageUploadResult {
  success: boolean;
  existed: boolean;
  skipped: boolean;
  overwritten: boolean;
  autoCreated?: boolean;
  gid: number;
  page: number;
  filename: string;
  size: number;
  sizeFormatted: string;
  downloadedPages: number;
  total: number;
  progress: number;
  state: number;
  stateName: string;
  hash?: string;
  algorithm?: string;
  oldHash?: string;
  message?: string;
}

// ==================== Page Upload Settings Types ====================

export interface PageUploadSettings {
  enabled: boolean;
  defaultAlgorithm: string;
}

class EhViewerAPI {
  private baseUrl: string;
  private token: string;

  constructor(baseUrl: string = '') {
    this.baseUrl = baseUrl;
    this.token = localStorage.getItem('authToken') || '';
  }

  private getHeaders(contentType: string = 'application/json'): Record<string, string> {
    const headers: Record<string, string> = {};
    if (contentType) headers['Content-Type'] = contentType;
    if (this.token) headers['Authorization'] = 'Bearer ' + this.token;
    return headers;
  }

  async request<T>(url: string, options: RequestInit = {}, timeout = 30000): Promise<T> {
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), timeout)

    try {
      const response = await fetch(this.baseUrl + url, {
        ...options,
        headers: {
          ...this.getHeaders(),
          ...(options.headers as Record<string, string>),
        },
        signal: controller.signal,
      })
      clearTimeout(timeoutId)

      if (response.status === 401) {
        this.token = '';
        localStorage.removeItem('authToken');
        window.location.hash = '#/login';
        throw new Error('Unauthorized');
      }

      const contentType = response.headers.get('content-type') || '';
      if (contentType.includes('application/json')) {
        const data = await response.json();
        if (!response.ok) {
          throw new Error(data.error || `HTTP ${response.status}`);
        }
        return data as T;
      }

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      return response.text() as unknown as T;
    } catch (error: any) {
      clearTimeout(timeoutId)
      if (error.name === 'AbortError') {
        throw new Error('请求超时，请稍后重试')
      }
      throw error
    }
  }

  async getAuthStatus(): Promise<AuthStatus> {
    return this.request<AuthStatus>('/api/v1/auth/status');
  }

  async login(password: string, token?: string): Promise<LoginResponse> {
    const body: Record<string, string> = {};
    if (password) body.password = password;
    if (token) body.token = token;

    const data = await this.request<LoginResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(body),
    });

    if (data.token) {
      this.token = data.token;
      localStorage.setItem('authToken', this.token);
    }

    return data;
  }

  async logout(): Promise<void> {
    await this.request('/api/v1/auth/logout', { method: 'POST' });
    this.token = '';
    localStorage.removeItem('authToken');
  }

  async getGalleries(page = 1, limit = 30, label?: string | null, search?: string | null, sort?: string | null, filter?: string | null): Promise<GalleryListResponse> {
    const params = new URLSearchParams();
    params.set('page', String(page));
    params.set('limit', String(limit));
    if (label) params.set('label', label);
    if (search) params.set('search', search);
    if (sort) params.set('sort', sort);
    if (filter) params.set('filter', filter);
    return this.request<GalleryListResponse>(`/api/v1/galleries?${params.toString()}`);
  }

  async queryGalleries(query: Record<string, unknown> = {}): Promise<GalleryListResponse> {
    return this.request<GalleryListResponse>('/api/v1/galleries', {
      method: 'QUERY',
      body: JSON.stringify(query),
    });
  }

  async getFavorites(page = 1, limit = 30, search?: string | null, sort?: string | null, filter?: string | null): Promise<GalleryListResponse> {
    const params = new URLSearchParams();
    params.set('page', String(page));
    params.set('limit', String(limit));
    if (search) params.set('search', search);
    if (sort) params.set('sort', sort);
    if (filter) params.set('filter', filter);
    return this.request<GalleryListResponse>(`/api/v1/favorites?${params.toString()}`);
  }

  async queryFavorites(query: Record<string, unknown> = {}): Promise<GalleryListResponse> {
    return this.request<GalleryListResponse>('/api/v1/favorites', {
      method: 'QUERY',
      body: JSON.stringify(query),
    });
  }

  async getGallery(gid: number): Promise<GalleryDetail> {
    return this.request<GalleryDetail>(`/api/v1/galleries/${gid}`);
  }

  async deleteGallery(gid: number): Promise<void> {
    const task = await this.request<AsyncTaskResponse>(`/api/v1/galleries/${gid}`, { method: 'DELETE' });
    await this.waitForTask(task.taskId)
  }

  async batchDeleteGalleries(gids: number[]): Promise<{ deleted: number; failed: number }> {
    const task = await this.request<AsyncTaskResponse>('/api/v1/galleries/batch', {
      method: 'DELETE',
      body: JSON.stringify({ gids }),
    });
    const result = await this.waitForTask(task.taskId)
    return { deleted: result.completed - (result.failed || 0), failed: result.failed || 0 }
  }

  getThumbnailUrl(gid: number): string {
    return `${this.baseUrl}/api/v1/galleries/${gid}/thumbnail`;
  }

  async getPages(gid: number): Promise<{ gid: number; pages: number; pageList: Array<{ page: number; state: string; pToken: string }> }> {
    return this.request(`/api/v1/galleries/${gid}/pages`);
  }

  getPageUrl(gid: number, page: number, mode = 'local'): string {
    let url = `${this.baseUrl}/api/v1/galleries/${gid}/pages/${page}`;
    const params = new URLSearchParams();
    if (mode !== 'local') params.set('mode', mode);
    if (this.token) params.set('token', this.token);
    const qs = params.toString();
    return qs ? `${url}?${qs}` : url;
  }

  async getLabels(): Promise<{ labels: Label[] }> {
    return this.request('/api/v1/labels');
  }

  async getLabelGalleries(label: string, page = 1, limit = 20): Promise<GalleryListResponse> {
    return this.request(`/api/v1/labels/${encodeURIComponent(label)}/galleries?page=${page}&limit=${limit}`);
  }

  async getFolders(): Promise<{ folders: Folder[] }> {
    return this.request('/api/v1/folders');
  }

  async getFiles(folder: string, page = 1, limit = 50, sort = 'time', order = 'desc', search = ''): Promise<FileListResponse> {
    let url = `/api/v1/folders/${encodeURIComponent(folder)}/files?page=${page}&limit=${limit}&sort=${sort}&order=${order}`;
    if (search) url += `&search=${encodeURIComponent(search)}`;
    return this.request<FileListResponse>(url);
  }

  async getEntries(root: string, path = '', sort = 'name', order = 'asc'): Promise<EntriesResponse> {
    const params = new URLSearchParams();
    params.set('path', path);
    params.set('sort', sort);
    params.set('order', order);
    return this.request<EntriesResponse>(`/api/v1/folders/${encodeURIComponent(root)}/entries?${params.toString()}`);
  }

  /** 目录归档：返回 202 + taskId */
  async archivePath(root: string, path: string): Promise<AsyncTaskResponse> {
    return this.request<AsyncTaskResponse>(`/api/v1/folders/${encodeURIComponent(root)}/archive?path=${encodeURIComponent(path)}`);
  }

  /** 删除文件或递归删除目录：返回 202 + taskId */
  async deleteEntry(root: string, path: string): Promise<AsyncTaskResponse> {
    return this.request<AsyncTaskResponse>(`/api/v1/folders/${encodeURIComponent(root)}/entries?path=${encodeURIComponent(path)}`, { method: 'DELETE' });
  }

  /** 任务归档 ZIP 下载链接 */
  getTaskDownloadUrl(taskId: string): string {
    let url = `${this.baseUrl}/api/v1/tasks/${encodeURIComponent(taskId)}/download`;
    if (this.token) url += `?token=${this.token}`;
    return url;
  }

  /** 文件直接下载（走 archive 端点，支持嵌套路径与 Range） */
  getEntryDownloadUrl(root: string, path: string): string {
    let url = `${this.baseUrl}/api/v1/folders/${encodeURIComponent(root)}/archive?path=${encodeURIComponent(path)}`;
    if (this.token) url += `&token=${this.token}`;
    return url;
  }

  getFileDownloadUrl(folder: string, filename: string): string {
    let url = `${this.baseUrl}/api/v1/folders/${encodeURIComponent(folder)}/files/${encodeURIComponent(filename)}`;
    if (this.token) url += `?token=${this.token}`;
    return url;
  }

  async previewFile(folder: string, filename: string): Promise<FilePreviewResponse> {
    return this.request(`/api/v1/folders/${encodeURIComponent(folder)}/files/${encodeURIComponent(filename)}/preview`);
  }

  async deleteFile(folder: string, filename: string): Promise<void> {
    return this.request(`/api/v1/folders/${encodeURIComponent(folder)}/files/${encodeURIComponent(filename)}`, {
      method: 'DELETE',
    });
  }

  async getTaskStatus(taskId: string): Promise<TransferTaskStatus> {
    return this.request<TransferTaskStatus>(`/api/v1/tasks/${encodeURIComponent(taskId)}`)
  }

  async waitForTask(taskId: string, maxWait = 10 * 60 * 1000): Promise<TransferTaskStatus> {
    const started = Date.now()
    while (Date.now() - started < maxWait) {
      const task = await this.getTaskStatus(taskId)
      if (task.status === 'completed') return task
      if (task.status === 'failed' || task.status === 'cancelled') {
        throw new Error(task.error || `任务${task.status}`)
      }
      await new Promise(resolve => setTimeout(resolve, 1000))
    }
    throw new Error('任务处理超时，请稍后在任务中心查看状态')
  }

  async batchDeleteFiles(folder: string, filenames: string[]): Promise<{ deleted: number; failed: number }> {
    return this.request(`/api/v1/folders/${encodeURIComponent(folder)}/files/batch`, {
      method: 'DELETE',
      body: JSON.stringify({ files: filenames }),
    }, 60000);
  }

  async getSystemInfo(): Promise<SystemInfo> {
    return this.request('/api/v1/system/info');
  }

  async getSystemStats(): Promise<SystemStats> {
    return this.request('/api/v1/system/stats');
  }

  async getPushTasks(): Promise<{ tasks: PushTask[] }> {
    return this.request('/api/v1/push/tasks');
  }

  async getReceiveSettings(): Promise<ReceiveSettings> {
    return this.request('/api/v1/settings/receive');
  }

  async updateReceiveSettings(settings: Partial<ReceiveSettings>): Promise<void> {
    return this.request('/api/v1/settings/receive', {
      method: 'PUT',
      body: JSON.stringify(settings),
    });
  }

  // ==================== Data Export/Import ====================

  async getExportFiles(): Promise<{ dbFiles: ExportFile[]; csvFiles: ExportFile[] }> {
    return this.request('/api/v1/data/export/files');
  }

  async exportBookmarks(): Promise<ExportData> {
    return this.request('/api/v1/data/export/bookmarks');
  }

  async exportFavorites(): Promise<ExportData> {
    return this.request('/api/v1/data/export/favorites');
  }

  async exportDownloads(): Promise<ExportData> {
    return this.request('/api/v1/data/export/downloads');
  }

  getExportDBUrl(): string {
    let url = `${this.baseUrl}/api/v1/data/export/db`;
    if (this.token) url += `?token=${this.token}`;
    return url;
  }

  getExportCSVUrl(): string {
    let url = `${this.baseUrl}/api/v1/data/export/csv`;
    if (this.token) url += `?token=${this.token}`;
    return url;
  }

  async importBookmarks(items: ExportItem[], mergeMode = 'skip'): Promise<ImportResult> {
    return this.request('/api/v1/data/import/bookmarks', {
      method: 'POST',
      body: JSON.stringify({ items, mergeMode }),
    });
  }

  async importFavorites(items: ExportItem[], mergeMode = 'skip'): Promise<ImportResult> {
    return this.request('/api/v1/data/import/favorites', {
      method: 'POST',
      body: JSON.stringify({ items, mergeMode }),
    });
  }

  async importDownloads(items: ExportItem[], mergeMode = 'skip'): Promise<ImportResult> {
    return this.request('/api/v1/data/import/downloads', {
      method: 'POST',
      body: JSON.stringify({ items, mergeMode }),
    });
  }

  async importDB(file: File): Promise<{ success: boolean; message: string; tables?: Record<string, number> }> {
    const formData = new FormData();
    formData.append('file', file);
    return this.request('/api/v1/data/import/db', {
      method: 'POST',
      body: formData,
      headers: {},
    });
  }

  async importCSV(file: File, mergeMode = 'skip'): Promise<ImportResult> {
    const formData = new FormData();
    formData.append('file', file);
    return this.request(`/api/v1/data/import/csv?mergeMode=${mergeMode}`, {
      method: 'POST',
      body: formData,
      headers: {},
    });
  }

  // ==================== Compress ====================

  async createCompressTask(gids: number[], splitSizeMB = 1024, includeMetadata = true): Promise<CompressTask> {
    return this.request('/api/v1/compress/create', {
      method: 'POST',
      body: JSON.stringify({ gids, splitSizeMB, includeMetadata }),
    });
  }

  async getCompressTasks(): Promise<{ tasks: CompressTask[] }> {
    return this.request('/api/v1/compress/tasks');
  }

  async getCompressTaskStatus(taskId: string): Promise<CompressTask> {
    return this.request(`/api/v1/compress/tasks/${taskId}`);
  }

  getCompressDownloadUrl(taskId: string, part = 1): string {
    let url = `${this.baseUrl}/api/v1/compress/tasks/${taskId}/download?part=${part}`;
    if (this.token) url += `&token=${this.token}`;
    return url;
  }

  async cancelCompressTask(taskId: string): Promise<void> {
    return this.request(`/api/v1/compress/tasks/${taskId}`, { method: 'DELETE' });
  }

  async scanCompressImport(file: File): Promise<CompressImportScan> {
    const formData = new FormData();
    formData.append('file', file);
    return this.request('/api/v1/compress/import', {
      method: 'POST',
      body: formData,
      headers: {},
    });
  }

  async confirmCompressImport(importId: string, skipGids: number[] = [], overwriteGids: number[] = [], unknownAction = 'import'): Promise<CompressImportResult> {
    return this.request('/api/v1/compress/import/confirm', {
      method: 'POST',
      body: JSON.stringify({ importId, skipGids, overwriteGids, unknownAction }),
    });
  }

  // ==================== Unified Tasks ====================

  async getAllTasks(type = 'all', status = 'all'): Promise<{ tasks: UnifiedTaskItem[]; total: number }> {
    return this.request(`/api/v1/tasks?type=${type}&status=${status}`);
  }

  async getTask(taskId: string): Promise<UnifiedTaskItem> {
    return this.request(`/api/v1/tasks/${taskId}`);
  }

  async cancelTask(taskId: string): Promise<void> {
    return this.request(`/api/v1/tasks/${taskId}`, { method: 'DELETE' });
  }

  // ==================== Integrity Check ====================

  async getFileHash(folder: string, filename: string, algorithm = 'md5'): Promise<FileHashInfo> {
    return this.request(`/api/v1/folders/${encodeURIComponent(folder)}/files/${encodeURIComponent(filename)}/hash?algorithm=${algorithm}`);
  }

  async getGalleryIntegrity(gid: number, algorithm = 'md5'): Promise<GalleryIntegrity> {
    return this.request(`/api/v1/folders/downloads/files/${gid}/integrity?algorithm=${algorithm}`);
  }

  async verifyGallery(gid: number, files: Array<{ filename: string; size: number; hash: string }>, algorithm = 'md5'): Promise<GalleryVerifyResult> {
    return this.request(`/api/v1/folders/downloads/files/${gid}/verify`, {
      method: 'POST',
      body: JSON.stringify({ algorithm, files }),
    });
  }

  isAuthenticated(): boolean {
    return !!this.token;
  }

  setToken(token: string): void {
    this.token = token;
    localStorage.setItem('authToken', token);
  }

  clearToken(): void {
    this.token = '';
    localStorage.removeItem('authToken');
  }

  // ==================== Download Management ====================

  async getDownloads(
    state: string = 'all',
    page: number = 1,
    limit: number = 30,
    label?: string,
    search?: string
  ): Promise<DownloadListResponse> {
    const params = new URLSearchParams()
    params.set('state', state)
    params.set('page', String(page))
    params.set('limit', String(limit))
    if (label) params.set('label', label)
    if (search) params.set('search', search)
    return this.request<DownloadListResponse>(`/api/v1/downloads?${params.toString()}`)
  }

  async getDownload(gid: number): Promise<DownloadItem> {
    return this.request<DownloadItem>(`/api/v1/downloads/${gid}`)
  }

  async createDownload(
    info: {
      gid: number
      token?: string
      title?: string
      titleJpn?: string
      thumb?: string
      category?: number
      posted?: string
      uploader?: string
      rating?: number
      pages?: number
      label?: string
    },
    startImmediately: boolean = false
  ): Promise<{ success: boolean; message: string; gid: number; state: string }> {
    return this.request('/api/v1/downloads', {
      method: 'POST',
      body: JSON.stringify({ ...info, startImmediately }),
    })
  }

  async startDownload(gid: number): Promise<{ success: boolean; message: string; gid: number; state: string }> {
    return this.request(`/api/v1/downloads/${gid}/start`, { method: 'POST' })
  }

  async pauseDownload(gid: number): Promise<{ success: boolean; message: string; gid: number; state: string }> {
    return this.request(`/api/v1/downloads/${gid}/pause`, { method: 'POST' })
  }

  async deleteDownload(gid: number): Promise<{ success: boolean; message: string; gid: number }> {
    const task = await this.request<AsyncTaskResponse>(`/api/v1/downloads/${gid}`, { method: 'DELETE' })
    await this.waitForTask(task.taskId)
    return { success: true, message: 'Download deleted', gid }
  }

  async batchStartDownloads(gids: number[]): Promise<{ success: boolean; message: string; count: number }> {
    return this.request('/api/v1/downloads/batch/start', {
      method: 'POST',
      body: JSON.stringify({ gids }),
    })
  }

  async batchPauseDownloads(gids: number[]): Promise<{ success: boolean; message: string; count: number }> {
    return this.request('/api/v1/downloads/batch/pause', {
      method: 'POST',
      body: JSON.stringify({ gids }),
    })
  }

  async batchDeleteDownloads(gids: number[]): Promise<{ success: boolean; message: string; count: number }> {
    const task = await this.request<AsyncTaskResponse>('/api/v1/downloads/batch', {
      method: 'DELETE',
      body: JSON.stringify({ gids }),
    })
    await this.waitForTask(task.taskId)
    return { success: true, message: 'Batch delete completed', count: gids.length }
  }

  // ==================== Relay Download ====================

  async getRelayTasks(
    status: string = 'all',
    direction: string = 'all'
  ): Promise<RelayTaskListResponse> {
    const params = new URLSearchParams()
    params.set('status', status)
    params.set('direction', direction)
    return this.request<RelayTaskListResponse>(`/api/v1/relay/tasks?${params.toString()}`)
  }

  async getRelayTaskStatus(taskId: string): Promise<RelayTask> {
    return this.request<RelayTask>(`/api/v1/relay/${taskId}/status`)
  }

  async createRelayTask(
    info: {
      gid: number
      token?: string
      title?: string
      titleJpn?: string
      thumb?: string
      category?: number
      posted?: string
      uploader?: string
      rating?: number
      pages?: number
    },
    options: RelayCreateOptions = {}
  ): Promise<{ success: boolean; taskId: string; gid: number; status: string; createdTime: number }> {
    return this.request('/api/v1/relay/create', {
      method: 'POST',
      body: JSON.stringify({ ...info, ...options }),
    })
  }

  async batchCreateRelayTasks(
    gids: number[],
    options: RelayCreateOptions = {}
  ): Promise<{ success: boolean; tasks: Array<{ taskId: string; gid: number; status: string }>; total: number }> {
    return this.request('/api/v1/relay/batch', {
      method: 'POST',
      body: JSON.stringify({ gids, ...options }),
    })
  }

  async acceptRelayTask(
    taskId: string,
    options: RelayAcceptOptions = {}
  ): Promise<{ success: boolean; message: string; taskId: string; acceptedDevice?: string }> {
    return this.request(`/api/v1/relay/${taskId}/accept`, {
      method: 'POST',
      body: JSON.stringify(options),
    })
  }

  async rejectRelayTask(taskId: string): Promise<{ success: boolean; message: string; taskId: string }> {
    return this.request(`/api/v1/relay/${taskId}/reject`, { method: 'POST' })
  }

  async cancelRelayTask(taskId: string): Promise<{ success: boolean; message: string; taskId: string }> {
    return this.request(`/api/v1/relay/${taskId}/cancel`, { method: 'POST' })
  }

  async deleteRelayTask(taskId: string): Promise<{ success: boolean; message: string; taskId: string }> {
    return this.request(`/api/v1/relay/${taskId}`, { method: 'DELETE' })
  }

  /** 返回可直接通过 <a href> 触发下载的完整 URL */
  getRelayDownloadUrl(taskId: string): string {
    let url = `${this.baseUrl}/api/v1/relay/${taskId}/download`
    if (this.token) url += `?token=${this.token}`
    return url
  }

  async requestRelay(
    info: {
      gid: number
      token?: string
      title?: string
      titleJpn?: string
      thumb?: string
      category?: number
      posted?: string
      uploader?: string
      rating?: number
      pages?: number
    },
    options: Omit<RelayCreateOptions, 'targetDevice' | 'targetDeviceId'> = {}
  ): Promise<{ success: boolean; taskId: string; gid: number; state: number; stateName: string; status: string; createdTime: number }> {
    return this.request('/api/v1/relay/request', {
      method: 'POST',
      body: JSON.stringify({ ...info, ...options }),
    })
  }

  // ==================== Page Upload (新接口 5.4.3) ====================

  /**
   * 上传单页图片二进制到 Android 端画廊下载目录
   * - 默认会自动计算文件 MD5 一起发送（推荐，覆盖前校验避免冗余写入）
   * - 若 gid 在下载列表中不存在，可传入 galleryInfo 自动创建 DownloadInfo
   */
  async uploadPage(
    gid: number,
    page: number,
    file: Blob | File,
    options: PageUploadOptions = {}
  ): Promise<PageUploadResult> {
    const formData = new FormData()
    formData.append('file', file, `page_${page}${options.extension ? '.' + options.extension : ''}`)
    if (options.extension) formData.append('extension', options.extension)
    const autoHash = options.autoHash !== false
    if (options.hash) {
      formData.append('hash', options.hash)
      formData.append('algorithm', options.algorithm || 'md5')
    } else if (autoHash && file instanceof Blob) {
      try {
        const buf = await file.arrayBuffer()
        const hash = await computeFileHash(new Uint8Array(buf), options.algorithm || 'md5')
        formData.append('hash', hash)
        formData.append('algorithm', options.algorithm || 'md5')
      } catch {
        // hash 计算失败也不阻塞上传
      }
    }
    if (options.galleryInfo) {
      const gi = options.galleryInfo
      formData.append('gid', String(gi.gid))
      if (gi.token) formData.append('token', gi.token)
      formData.append('title', gi.title)
      if (gi.titleJpn) formData.append('titleJpn', gi.titleJpn)
      if (gi.thumb) formData.append('thumb', gi.thumb)
      if (gi.category !== undefined) formData.append('category', String(gi.category))
      if (gi.posted) formData.append('posted', gi.posted)
      if (gi.uploader) formData.append('uploader', gi.uploader)
      if (gi.rating !== undefined) formData.append('rating', String(gi.rating))
      if (gi.pages !== undefined) formData.append('pages', String(gi.pages))
    }
    return this.request<PageUploadResult>(`/api/v1/galleries/${gid}/pages/${page}/upload`, {
      method: 'POST',
      body: formData,
      headers: {},
    })
  }

  // ==================== Page Upload Settings ====================

  async getPageUploadSettings(): Promise<PageUploadSettings> {
    return this.request<PageUploadSettings>('/api/v1/settings/page-upload')
  }

  async updatePageUploadSettings(enabled: boolean): Promise<{ success: boolean; message: string; enabled: boolean }> {
    return this.request('/api/v1/settings/page-upload', {
      method: 'PUT',
      body: JSON.stringify({ enabled }),
    })
  }
}

// ==================== 工具函数 ====================

/**
 * 计算文件哈希（浏览器端）
 */
async function computeFileHash(bytes: Uint8Array, algorithm: string): Promise<string> {
  let algo = algorithm.toLowerCase().replace('-', '')
  if (algo === 'sha1') algo = 'SHA-1'
  if (algo === 'sha256') algo = 'SHA-256'
  if (algo === 'md5') algo = 'MD5'
  // 浏览器 Web Crypto API 不支持 MD5，使用 MD5 纯 JS 实现
  if (algo === 'MD5') {
    return md5Hex(bytes)
  }
  const subtle = (globalThis.crypto && (globalThis.crypto as Crypto).subtle) as SubtleCrypto | undefined
  if (!subtle) return ''
  const hashBuf = await subtle.digest(algo, bytes as BufferSource)
  return bufferToHex(new Uint8Array(hashBuf))
}

function bufferToHex(buf: Uint8Array): string {
  let s = ''
  for (let i = 0; i < buf.length; i++) {
    s += buf[i].toString(16).padStart(2, '0')
  }
  return s
}

/**
 * MD5 纯 JS 实现（避免依赖）
 */
function md5Hex(bytes: Uint8Array): string {
  const x: number[] = []
  for (let i = 0; i < bytes.length; i++) {
    x[i] = bytes[i]
  }
  let a = 1732584193
  let b = -271733879
  let c = -1732584194
  let d = 271733878

  const safeAdd = (v: number, w: number) => (((v & 0xffff) + (w & 0xffff)) | ((v >>> 16) + (w >>> 16)) << 16) | 0
  const rol = (n: number, c: number) => (n << c) | (n >>> (32 - c))
  const cmn = (q: number, a2: number, b2: number, x2: number, s: number, t: number) =>
    safeAdd(rol(safeAdd(safeAdd(a2, q), safeAdd(x2, t)), s), b2)
  const ff = (a2: number, b2: number, c2: number, d2: number, x2: number, s: number, t: number) =>
    cmn((b2 & c2) | (~b2 & d2), a2, b2, x2, s, t)
  const gg = (a2: number, b2: number, c2: number, d2: number, x2: number, s: number, t: number) =>
    cmn((b2 & d2) | (c2 & ~d2), a2, b2, x2, s, t)
  const hh = (a2: number, b2: number, c2: number, d2: number, x2: number, s: number, t: number) =>
    cmn(b2 ^ c2 ^ d2, a2, b2, x2, s, t)
  const ii = (a2: number, b2: number, c2: number, d2: number, x2: number, s: number, t: number) =>
    cmn(c2 ^ (b2 | ~d2), a2, b2, x2, s, t)

  const len = bytes.length
  const bitLen = len * 8
  x[len >> 2] = x[len >> 2] | (0x80 << ((len & 3) * 8))
  x[(((len + 8) >> 6) << 4) + 14] = bitLen

  for (let i = 0; i < x.length; i += 16) {
    const oa = a, ob = b, oc = c, od = d
    a = ff(a, b, c, d, x[i] || 0, 7, -680876936)
    d = ff(d, a, b, c, x[i + 1] || 0, 12, -389564586)
    c = ff(c, d, a, b, x[i + 2] || 0, 17, 606105819)
    b = ff(b, c, d, a, x[i + 3] || 0, 22, -1044525330)
    a = ff(a, b, c, d, x[i + 4] || 0, 7, -176418897)
    d = ff(d, a, b, c, x[i + 5] || 0, 12, 1200080426)
    c = ff(c, d, a, b, x[i + 6] || 0, 17, -1473231341)
    b = ff(b, c, d, a, x[i + 7] || 0, 22, -45705983)
    a = ff(a, b, c, d, x[i + 8] || 0, 7, 1770035416)
    d = ff(d, a, b, c, x[i + 9] || 0, 12, -1958414417)
    c = ff(c, d, a, b, x[i + 10] || 0, 17, -42063)
    b = ff(b, c, d, a, x[i + 11] || 0, 22, -1990404162)
    a = ff(a, b, c, d, x[i + 12] || 0, 7, 1804603682)
    d = ff(d, a, b, c, x[i + 13] || 0, 12, -40341101)
    c = ff(c, d, a, b, x[i + 14] || 0, 17, -1502002290)
    b = ff(b, c, d, a, x[i + 15] || 0, 22, 1236535329)

    a = gg(a, b, c, d, x[i + 1] || 0, 5, -165796510)
    d = gg(d, a, b, c, x[i + 6] || 0, 9, -1069501632)
    c = gg(c, d, a, b, x[i + 11] || 0, 14, 643717713)
    b = gg(b, c, d, a, x[i] || 0, 20, -373897302)
    a = gg(a, b, c, d, x[i + 5] || 0, 5, -701558691)
    d = gg(d, a, b, c, x[i + 10] || 0, 9, 38016083)
    c = gg(c, d, a, b, x[i + 15] || 0, 14, -660478335)
    b = gg(b, c, d, a, x[i + 4] || 0, 20, -405537848)
    a = gg(a, b, c, d, x[i + 9] || 0, 5, 568446438)
    d = gg(d, a, b, c, x[i + 14] || 0, 9, -1019803690)
    c = gg(c, d, a, b, x[i + 3] || 0, 14, -187363961)
    b = gg(b, c, d, a, x[i + 8] || 0, 20, 1163531501)
    a = gg(a, b, c, d, x[i + 13] || 0, 5, -1444681467)
    d = gg(d, a, b, c, x[i + 2] || 0, 9, -51403784)
    c = gg(c, d, a, b, x[i + 7] || 0, 14, 1735328473)
    b = gg(b, c, d, a, x[i + 12] || 0, 20, -1926607734)

    a = hh(a, b, c, d, x[i + 5] || 0, 4, -378558)
    d = hh(d, a, b, c, x[i + 8] || 0, 11, -2022574463)
    c = hh(c, d, a, b, x[i + 11] || 0, 16, 1839030562)
    b = hh(b, c, d, a, x[i + 14] || 0, 23, -35309556)
    a = hh(a, b, c, d, x[i + 1] || 0, 4, -1530992060)
    d = hh(d, a, b, c, x[i + 4] || 0, 11, 1272893353)
    c = hh(c, d, a, b, x[i + 7] || 0, 16, -155497632)
    b = hh(b, c, d, a, x[i + 10] || 0, 23, -1094730640)
    a = hh(a, b, c, d, x[i + 13] || 0, 4, 681279174)
    d = hh(d, a, b, c, x[i] || 0, 11, -358537222)
    c = hh(c, d, a, b, x[i + 3] || 0, 16, -722521979)
    b = hh(b, c, d, a, x[i + 6] || 0, 23, 76029189)
    a = hh(a, b, c, d, x[i + 9] || 0, 4, -640364487)
    d = hh(d, a, b, c, x[i + 12] || 0, 11, -421815835)
    c = hh(c, d, a, b, x[i + 15] || 0, 16, 530742520)
    b = hh(b, c, d, a, x[i + 2] || 0, 23, -995338651)

    a = ii(a, b, c, d, x[i] || 0, 6, -198630844)
    d = ii(d, a, b, c, x[i + 7] || 0, 10, 1126891415)
    c = ii(c, d, a, b, x[i + 14] || 0, 15, -1416354905)
    b = ii(b, c, d, a, x[i + 5] || 0, 21, -57434055)
    a = ii(a, b, c, d, x[i + 12] || 0, 6, 1700485571)
    d = ii(d, a, b, c, x[i + 3] || 0, 10, -1894986606)
    c = ii(c, d, a, b, x[i + 10] || 0, 15, -1051523)
    b = ii(b, c, d, a, x[i + 1] || 0, 21, -2054922799)
    a = ii(a, b, c, d, x[i + 8] || 0, 6, 1873313359)
    d = ii(d, a, b, c, x[i + 15] || 0, 10, -30611744)
    c = ii(c, d, a, b, x[i + 6] || 0, 15, -1560198380)
    b = ii(b, c, d, a, x[i + 13] || 0, 21, 1309151649)
    a = ii(a, b, c, d, x[i + 4] || 0, 6, -145523070)
    d = ii(d, a, b, c, x[i + 11] || 0, 10, -1120210379)
    c = ii(c, d, a, b, x[i + 2] || 0, 15, 718787259)
    b = ii(b, c, d, a, x[i + 9] || 0, 21, -343485551)

    a = safeAdd(a, oa)
    b = safeAdd(b, ob)
    c = safeAdd(c, oc)
    d = safeAdd(d, od)
  }

  return [a, b, c, d]
    .map((v) => (v < 0 ? v + 0x100000000 : v).toString(16).padStart(8, '0'))
    .join('')
}

export const api = new EhViewerAPI();
export default api;
