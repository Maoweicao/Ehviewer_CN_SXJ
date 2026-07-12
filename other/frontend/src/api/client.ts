export interface Gallery {
  gid: number;
  token: string;
  title: string;
  titleJpn?: string;
  thumb: string;
  category: string;
  posted: string;
  uploader: string;
  rating: number;
  pages: number;
  language?: string;
  tags?: string[];
  label?: string;
}

export interface GalleryDetail extends Gallery {
  apiUid: number;
  apiKey: string;
  torrentCount: number;
  torrentUrl: string;
  archiveUrl: string;
  parent: string;
  visible: string;
  size: string;
  favoriteCount: number;
  isFavorited: boolean;
  ratingCount: number;
  previewPages: number;
  comments: Comment[];
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
  galleries: Gallery[];
}

export interface Label {
  name: string;
  count: number;
}

export interface Folder {
  name: string;
  path: string;
  fileCount: number;
  totalSize: number;
  totalSizeFormatted: string;
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

  async request<T>(url: string, options: RequestInit = {}): Promise<T> {
    const response = await fetch(this.baseUrl + url, {
      ...options,
      headers: {
        ...this.getHeaders(),
        ...(options.headers as Record<string, string>),
      },
    });

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

  async getGalleries(page = 1, limit = 20, label?: string | null, search?: string | null): Promise<GalleryListResponse> {
    let url = `/api/v1/galleries?page=${page}&limit=${limit}`;
    if (label) url += `&label=${encodeURIComponent(label)}`;
    if (search) url += `&search=${encodeURIComponent(search)}`;
    return this.request<GalleryListResponse>(url);
  }

  async queryGalleries(query: Record<string, unknown> = {}): Promise<GalleryListResponse> {
    return this.request<GalleryListResponse>('/api/v1/galleries', {
      method: 'QUERY',
      body: JSON.stringify(query),
    });
  }

  async getGallery(gid: number): Promise<GalleryDetail> {
    return this.request<GalleryDetail>(`/api/v1/galleries/${gid}`);
  }

  async deleteGallery(gid: number): Promise<void> {
    return this.request(`/api/v1/galleries/${gid}`, { method: 'DELETE' });
  }

  async batchDeleteGalleries(gids: number[]): Promise<{ deleted: number; failed: number }> {
    return this.request('/api/v1/galleries/batch', {
      method: 'DELETE',
      body: JSON.stringify({ gids }),
    });
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

  async batchDeleteFiles(folder: string, filenames: string[]): Promise<{ deleted: number; failed: number }> {
    return this.request(`/api/v1/folders/${encodeURIComponent(folder)}/files/batch`, {
      method: 'DELETE',
      body: JSON.stringify({ files: filenames }),
    });
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
}

export const api = new EhViewerAPI();
export default api;
