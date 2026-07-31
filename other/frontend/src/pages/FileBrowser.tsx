import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  NavBar,
  SearchBar,
  Dropdown,
  Checkbox,
  Button,
  SpinLoading,
  Empty,
  Dialog,
  Toast,
  List,
  SafeArea,
} from 'antd-mobile'
import api, { type Folder, type FileItem } from '../api/client'

type ViewMode = 'folders' | 'files' | 'preview'

export default function FileBrowser() {
  const navigate = useNavigate()
  const [viewMode, setViewMode] = useState<ViewMode>('folders')
  const [folders, setFolders] = useState<Folder[]>([])
  const [currentFolder, setCurrentFolder] = useState('')
  const [files, setFiles] = useState<FileItem[]>([])
  const [loading, setLoading] = useState(false)
  const [totalFiles, setTotalFiles] = useState(0)
  const [filePage, setFilePage] = useState(1)
  const [batchMode, setBatchMode] = useState(false)
  const [selectedFiles, setSelectedFiles] = useState<Set<string>>(new Set())
  const [sort, setSort] = useState('time')
  const [order, setOrder] = useState('desc')
  const [search, setSearch] = useState('')
  const [previewContent, setPreviewContent] = useState('')
  const [previewName, setPreviewName] = useState('')

  const loadFolders = useCallback(async () => {
    try {
      const data = await api.getFolders()
      setFolders(data.folders || [])
    } catch (e: unknown) {
      console.error('loadFolders error:', e)
    }
  }, [])

  const loadFiles = useCallback(async (folder: string, page: number, s: string, o: string, keyword: string, append = false) => {
    setLoading(true)
    try {
      const data = await api.getFiles(folder, page, 50, s, o, keyword)
      if (append) {
        setFiles(prev => [...prev, ...(data.files || [])])
      } else {
        setFiles(data.files || [])
      }
      setTotalFiles(data.total || 0)
    } catch (e: unknown) {
      console.error('loadFiles error:', e)
      if (!append) {
        setFiles([])
        setTotalFiles(0)
      }
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadFolders()
  }, [])

  const openFolder = (name: string) => {
    setCurrentFolder(name)
    setFiles([])
    setTotalFiles(0)
    setFilePage(1)
    setBatchMode(false)
    setSelectedFiles(new Set())
    setSearch('')
    setViewMode('files')
    loadFiles(name, 1, sort, order, '')
  }

  const handleSearch = (val: string) => {
    setSearch(val)
    setFilePage(1)
    loadFiles(currentFolder, 1, sort, order, val)
  }

  const handleSortChange = (val: string) => {
    setSort(val)
    setFilePage(1)
    loadFiles(currentFolder, 1, val, order, search)
  }

  const handleOrderChange = (val: string) => {
    setOrder(val)
    setFilePage(1)
    loadFiles(currentFolder, 1, sort, val, search)
  }

  const handleLoadMore = () => {
    const nextPage = filePage + 1
    setFilePage(nextPage)
    loadFiles(currentFolder, nextPage, sort, order, search, true)
  }

  const hasMore = files.length < totalFiles

  const handlePreview = async (file: FileItem) => {
    try {
      const data = await api.previewFile(currentFolder, file.name)
      setPreviewContent(data.content || '')
      setPreviewName(file.name)
      setViewMode('preview')
    } catch {
      Toast.show({ content: '预览失败', icon: 'fail' })
    }
  }

  const handleDownload = (file: FileItem) => {
    const url = api.getFileDownloadUrl(currentFolder, file.name)
    const a = document.createElement('a')
    a.href = url
    a.download = file.name
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    Toast.show({ content: `开始下载: ${file.name}` })
  }

  const handleDelete = async (file: FileItem) => {
    const result = await Dialog.confirm({
      content: `确定要删除 ${file.name} 吗？`,
    })
    if (result) {
      try {
        await api.deleteFile(currentFolder, file.name)
        Toast.show({ content: '删除成功', icon: 'success' })
        loadFiles(currentFolder, filePage, sort, order, search)
      } catch {
        Toast.show({ content: '删除失败', icon: 'fail' })
      }
    }
  }

  const toggleBatchMode = () => {
    setBatchMode(!batchMode)
    if (batchMode) setSelectedFiles(new Set())
  }

  const toggleFileSelection = (name: string) => {
    const newSet = new Set(selectedFiles)
    if (newSet.has(name)) {
      newSet.delete(name)
    } else {
      newSet.add(name)
    }
    setSelectedFiles(newSet)
  }

  const toggleSelectAll = () => {
    if (selectedFiles.size === files.length) {
      setSelectedFiles(new Set())
    } else {
      setSelectedFiles(new Set(files.map(f => f.name)))
    }
  }

  const handleBatchDelete = async () => {
    if (selectedFiles.size === 0) {
      Toast.show({ content: '请先选择文件' })
      return
    }
    const result = await Dialog.confirm({
      content: `确定要删除 ${selectedFiles.size} 个文件吗？`,
    })
    if (result) {
      try {
        Toast.show({ content: '正在删除...', icon: 'loading' })
        await api.batchDeleteFiles(currentFolder, Array.from(selectedFiles))
        Toast.show({ content: `成功删除 ${selectedFiles.size} 个文件`, icon: 'success' })
        setBatchMode(false)
        setSelectedFiles(new Set())
        loadFiles(currentFolder, 1, sort, order, search)
      } catch (e: any) {
        Toast.show({ content: e?.message || '删除失败', icon: 'fail' })
      }
    }
  }

  const fileIcon = (ext: string, isGallery?: boolean) => {
    if (isGallery) return '📚'
    const m: Record<string, string> = {
      txt: '📄', log: '📋', json: '🔧', xml: '🔧', html: '🌐', css: '🎨',
      js: '⚡', java: '☕', kt: '🟣', py: '🐍', sh: '💻', md: '📝',
      jpg: '🖼️', jpeg: '🖼️', png: '🖼️', gif: '🎞️', webp: '🖼️', svg: '🖼️',
      zip: '📦', rar: '📦', '7z': '📦', tar: '📦', gz: '📦',
      pdf: '📕', doc: '📘', xls: '📊', ppt: '📙',
      mp3: '🎵', wav: '🎵', mp4: '🎬', avi: '🎬', mkv: '🎬',
      folder: '📁',
    }
    return m[ext] || '📄'
  }

  const goBack = () => {
    if (viewMode === 'preview') {
      setViewMode('files')
    } else if (viewMode === 'files') {
      setViewMode('folders')
      setBatchMode(false)
      setSelectedFiles(new Set())
    }
  }

  const hasBack = viewMode !== 'folders'
  const title = viewMode === 'folders' ? '文件管理' : viewMode === 'preview' ? previewName : currentFolder

  const sortLabel = sort === 'time' ? '按时间' : sort === 'name' ? '按名称' : '按大小'
  const orderLabel = order === 'desc' ? '降序' : '升序'

  return (
    <div>
      <NavBar
        backArrow={hasBack}
        onBack={goBack}
        right={
          viewMode === 'files' ? (
            <div style={{ display: 'flex', gap: 12 }}>
              <span
                style={{ cursor: 'pointer', fontSize: 18 }}
                onClick={toggleBatchMode}
              >
                {batchMode ? '✓' : '🗑️'}
              </span>
            </div>
          ) : undefined
        }
      >
        {title}
      </NavBar>

      {/* Folders View */}
      {viewMode === 'folders' && (
        <div className="page-content">
          {folders.length === 0 ? (
            <Empty description="暂无文件夹" style={{ padding: '60px 0' }} />
          ) : (
            <List>
              {folders.map((f) => (
                <List.Item
                  key={f.name}
                  description={f.path}
                  onClick={() => openFolder(f.name)}
                  clickable
                  extra={<span style={{ fontSize: 12, color: 'var(--text-light, #666)' }}>{f.fileCount}个文件, {f.totalSizeFormatted}</span>}
                >
                  {f.name}
                </List.Item>
              ))}
            </List>
          )}
        </div>
      )}

      {/* Files View */}
      {viewMode === 'files' && (
        <>
          <SearchBar
            placeholder="搜索文件..."
            value={search}
            onChange={setSearch}
            onSearch={handleSearch}
            onClear={() => handleSearch('')}
            style={{ padding: '8px 12px' }}
          />
          <div style={{ display: 'flex', padding: '0 12px', gap: 8, alignItems: 'center' }}>
            <Dropdown>
              <Dropdown.Item key="sort" title={sortLabel}>
                <List>
                  <List.Item onClick={() => handleSortChange('time')}>按时间</List.Item>
                  <List.Item onClick={() => handleSortChange('name')}>按名称</List.Item>
                  <List.Item onClick={() => handleSortChange('size')}>按大小</List.Item>
                </List>
              </Dropdown.Item>
            </Dropdown>
            <Dropdown>
              <Dropdown.Item key="order" title={orderLabel}>
                <List>
                  <List.Item onClick={() => handleOrderChange('desc')}>降序</List.Item>
                  <List.Item onClick={() => handleOrderChange('asc')}>升序</List.Item>
                </List>
              </Dropdown.Item>
            </Dropdown>
            {batchMode && (
              <Button size="mini" onClick={toggleSelectAll}>
                {selectedFiles.size === files.length ? '取消全选' : '全选'}
              </Button>
            )}
            <span style={{ fontSize: 12, color: 'var(--text-light, #666)', marginLeft: 'auto' }}>
              {files.length}/{totalFiles}
            </span>
          </div>
          <div className="page-content">
            {loading && files.length === 0 ? (
              <div className="loading-container">
                <SpinLoading style={{ '--size': '48px' }} />
              </div>
            ) : !loading && files.length === 0 ? (
              <Empty description="暂无文件" style={{ padding: '60px 0' }} />
            ) : (
              <List>
                {files.map((file) => (
                  <List.Item
                    key={file.name}
                    description={
                      <div style={{ textAlign: 'right' }}>
                        <div>{file.isGallery ? `${file.pages || 0} 页` : file.sizeFormatted}</div>
                        <div style={{ fontSize: 12, color: 'var(--text-light, #666)' }}>
                          {file.isGallery ? `共 ${file.fileCount || 0} 个文件` : file.lastModifiedFormatted}
                        </div>
                      </div>
                    }
                    extra={
                      !batchMode ? (
                        <div style={{ display: 'flex', gap: 8 }}>
                          {file.isGallery ? (
                            <Button size="mini" color="primary" onClick={() => navigate(`/gallery/${file.gid}`)}>
                              查看
                            </Button>
                          ) : (
                            <>
                              <Button size="mini" color="primary" onClick={() => handleDownload(file)}>
                                下载
                              </Button>
                              <Button size="mini" color="danger" onClick={() => handleDelete(file)}>
                                删除
                              </Button>
                            </>
                          )}
                        </div>
                      ) : undefined
                    }
                  >
                    <div style={{ display: 'flex', alignItems: 'center' }}>
                      {batchMode && (
                        <Checkbox
                          checked={selectedFiles.has(file.name)}
                          onChange={() => toggleFileSelection(file.name)}
                          style={{ marginRight: 8 }}
                        />
                      )}
                      <span className="file-icon" style={{ marginRight: 8 }}>
                        {fileIcon(file.extension, file.isGallery)}
                      </span>
                      <span
                        style={{ color: 'var(--primary, #2196f3)', cursor: 'pointer' }}
                        onClick={() => {
                          if (file.isGallery && file.gid) {
                            navigate(`/gallery/${file.gid}`)
                          } else {
                            handlePreview(file)
                          }
                        }}
                      >
                        {file.name}
                      </span>
                    </div>
                  </List.Item>
                ))}
              </List>
            )}
            {hasMore && !loading && (
              <div style={{ padding: '16px', textAlign: 'center' }}>
                <Button onClick={handleLoadMore} loading={loading}>
                  加载更多
                </Button>
              </div>
            )}
          </div>
          {batchMode && selectedFiles.size > 0 && (
            <div style={{ position: 'fixed', bottom: 50, left: 0, right: 0, padding: '8px 16px', background: 'var(--card-bg, #fff)', borderTop: '1px solid var(--border, #e0e0e0)' }}>
              <Button block color="danger" onClick={handleBatchDelete}>
                删除选中 ({selectedFiles.size})
              </Button>
            </div>
          )}
          <SafeArea position="bottom" />
        </>
      )}

      {/* Preview View */}
      {viewMode === 'preview' && (
        <div className="page-content">
          <pre className="preview-text">{previewContent}</pre>
          <div style={{ padding: '16px 0' }}>
            <Button
              block
              color="primary"
              onClick={() => {
                const file = files.find((f) => f.name === previewName)
                if (file) handleDownload(file)
              }}
            >
              下载
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
