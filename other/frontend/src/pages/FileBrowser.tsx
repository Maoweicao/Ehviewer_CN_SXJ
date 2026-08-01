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
import api, { type Folder, type FileEntry } from '../api/client'

type ViewMode = 'roots' | 'entries' | 'preview'

export default function FileBrowser() {
  const navigate = useNavigate()
  const [viewMode, setViewMode] = useState<ViewMode>('roots')
  const [folders, setFolders] = useState<Folder[]>([])
  const [currentRoot, setCurrentRoot] = useState('')
  const [currentPath, setCurrentPath] = useState('')
  const [entries, setEntries] = useState<FileEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [batchMode, setBatchMode] = useState(false)
  const [selectedPaths, setSelectedPaths] = useState<Set<string>>(new Set())
  const [sort, setSort] = useState('name')
  const [order, setOrder] = useState('asc')
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

  const loadEntries = useCallback(async (root: string, path: string, s: string, o: string) => {
    setLoading(true)
    try {
      const data = await api.getEntries(root, path, s, o)
      setEntries(data.entries || [])
    } catch (e: unknown) {
      console.error('loadEntries error:', e)
      setEntries([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadFolders()
  }, [loadFolders])

  const openRoot = (root: string) => {
    setCurrentRoot(root)
    setCurrentPath('')
    setEntries([])
    setBatchMode(false)
    setSelectedPaths(new Set())
    setSearch('')
    setViewMode('entries')
    loadEntries(root, '', sort, order)
  }

  const openEntry = (entry: FileEntry) => {
    if (entry.isDirectory) {
      setCurrentPath(entry.path)
      setEntries([])
      setBatchMode(false)
      setSelectedPaths(new Set())
      setSearch('')
      loadEntries(currentRoot, entry.path, sort, order)
    } else {
      handlePreview(entry)
    }
  }

  const handleSearch = (val: string) => {
    setSearch(val)
  }

  const handleSortChange = (val: string) => {
    setSort(val)
    loadEntries(currentRoot, currentPath, val, order)
  }

  const handleOrderChange = (val: string) => {
    setOrder(val)
    loadEntries(currentRoot, currentPath, sort, val)
  }

  const handlePreview = async (entry: FileEntry) => {
    try {
      const data = await api.previewFile(currentRoot, entry.path)
      setPreviewContent(data.content || '')
      setPreviewName(entry.name)
      setViewMode('preview')
    } catch {
      Toast.show({ content: '预览失败', icon: 'fail' })
    }
  }

  const handleDownload = async (entry: FileEntry) => {
    if (entry.isDirectory) {
      Toast.show({ content: '正在创建目录归档...', icon: 'loading' })
      try {
        const task = await api.archivePath(currentRoot, entry.path)
        await api.waitForTask(task.taskId)
        const ok = await Dialog.confirm({
          content: '目录归档已完成，是否下载 ZIP？',
          confirmText: '下载',
        })
        if (ok) {
          const a = document.createElement('a')
          a.href = api.getTaskDownloadUrl(task.taskId)
          a.download = `${entry.name}.zip`
          document.body.appendChild(a)
          a.click()
          document.body.removeChild(a)
        }
      } catch (e: any) {
        Toast.show({ content: e?.message || '归档失败', icon: 'fail' })
      }
      return
    }
    const url = api.getEntryDownloadUrl(currentRoot, entry.path)
    const a = document.createElement('a')
    a.href = url
    a.download = entry.name
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    Toast.show({ content: `开始下载: ${entry.name}` })
  }

  const handleDelete = async (entry: FileEntry) => {
    const result = await Dialog.confirm({
      content: `确定要删除${entry.isDirectory ? '文件夹' : '文件'} ${entry.name} 吗？${entry.isDirectory ? '（将递归删除其中所有内容）' : ''}`,
    })
    if (!result) return
    try {
      const task = await api.deleteEntry(currentRoot, entry.path)
      await api.waitForTask(task.taskId)
      Toast.show({ content: '删除成功', icon: 'success' })
      loadEntries(currentRoot, currentPath, sort, order)
    } catch (e: any) {
      Toast.show({ content: e?.message || '删除失败', icon: 'fail' })
    }
  }

  const toggleBatchMode = () => {
    setBatchMode(!batchMode)
    if (batchMode) setSelectedPaths(new Set())
  }

  const toggleSelection = (path: string) => {
    const newSet = new Set(selectedPaths)
    if (newSet.has(path)) {
      newSet.delete(path)
    } else {
      newSet.add(path)
    }
    setSelectedPaths(newSet)
  }

  const toggleSelectAll = () => {
    if (selectedPaths.size === entries.length) {
      setSelectedPaths(new Set())
    } else {
      setSelectedPaths(new Set(entries.map((e) => e.path)))
    }
  }

  const handleBatchDelete = async () => {
    if (selectedPaths.size === 0) {
      Toast.show({ content: '请先选择文件或文件夹' })
      return
    }
    const result = await Dialog.confirm({
      content: `确定要删除选中的 ${selectedPaths.size} 个文件/文件夹吗？`,
    })
    if (!result) return
    try {
      Toast.show({ content: '正在删除...', icon: 'loading' })
      for (const path of Array.from(selectedPaths)) {
        const task = await api.deleteEntry(currentRoot, path)
        await api.waitForTask(task.taskId)
      }
      Toast.show({ content: '删除完成', icon: 'success' })
      setBatchMode(false)
      setSelectedPaths(new Set())
      loadEntries(currentRoot, currentPath, sort, order)
    } catch (e: any) {
      Toast.show({ content: e?.message || '删除失败', icon: 'fail' })
    }
  }

  const fileIcon = (entry: FileEntry) => {
    if (entry.isDirectory) return '📁'
    const m: Record<string, string> = {
      txt: '📄', log: '📋', json: '🔧', xml: '🔧', html: '🌐', css: '🎨',
      js: '⚡', java: '☕', kt: '🟣', py: '🐍', sh: '💻', md: '📝',
      jpg: '🖼️', jpeg: '🖼️', png: '🖼️', gif: '🎞️', webp: '🖼️', svg: '🖼️',
      zip: '📦', rar: '📦', '7z': '📦', tar: '📦', gz: '📦',
      pdf: '📕', doc: '📘', xls: '📊', ppt: '📙',
      mp3: '🎵', wav: '🎵', mp4: '🎬', avi: '🎬', mkv: '🎬',
    }
    return m[entry.extension] || '📄'
  }

  const goBack = () => {
    if (viewMode === 'preview') {
      setViewMode('entries')
    } else if (viewMode === 'entries') {
      if (currentPath) {
        const idx = currentPath.lastIndexOf('/')
        const parent = idx > 0 ? currentPath.substring(0, idx) : ''
        setCurrentPath(parent)
        setBatchMode(false)
        setSelectedPaths(new Set())
        setSearch('')
        loadEntries(currentRoot, parent, sort, order)
      } else {
        setViewMode('roots')
        setBatchMode(false)
        setSelectedPaths(new Set())
      }
    }
  }

  const hasBack = viewMode !== 'roots'
  const title = viewMode === 'roots'
    ? '文件管理'
    : viewMode === 'preview'
      ? previewName
      : currentPath ? `${currentPath}` : (folders.find((f) => f.name === currentRoot)?.displayName || currentRoot)

  const sortLabel = sort === 'time' ? '按时间' : sort === 'name' ? '按名称' : '按大小'
  const orderLabel = order === 'desc' ? '降序' : '升序'

  const filteredEntries = search
    ? entries.filter((e) => e.name.toLowerCase().includes(search.toLowerCase()))
    : entries

  return (
    <div>
      <NavBar
        backArrow={hasBack}
        onBack={goBack}
        right={
          viewMode === 'entries' ? (
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

      {/* Roots View */}
      {viewMode === 'roots' && (
        <div className="page-content">
          {folders.length === 0 ? (
            <Empty description="暂无可用根目录" style={{ padding: '60px 0' }} />
          ) : (
            <List>
              {folders.map((f) => (
                <List.Item
                  key={f.name}
                  description={f.path}
                  onClick={() => openRoot(f.name)}
                  clickable
                  extra={<span style={{ fontSize: 12, color: 'var(--text-light, #666)' }}>{f.displayName || ''}</span>}
                >
                  {f.displayName || f.name}
                </List.Item>
              ))}
            </List>
          )}
        </div>
      )}

      {/* Entries View */}
      {viewMode === 'entries' && (
        <>
          <SearchBar
            placeholder="搜索当前目录..."
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
                {selectedPaths.size === entries.length ? '取消全选' : '全选'}
              </Button>
            )}
            <span style={{ fontSize: 12, color: 'var(--text-light, #666)', marginLeft: 'auto' }}>
              {filteredEntries.length} 项
            </span>
          </div>
          <div className="page-content">
            {loading && entries.length === 0 ? (
              <div className="loading-container">
                <SpinLoading style={{ '--size': '48px' }} />
              </div>
            ) : !loading && filteredEntries.length === 0 ? (
              <Empty description="目录为空" style={{ padding: '60px 0' }} />
            ) : (
              <List>
                {filteredEntries.map((entry) => (
                  <List.Item
                    key={entry.path}
                    description={
                      <div style={{ textAlign: 'right' }}>
                        {entry.isDirectory ? <div>文件夹</div> : <div>{entry.sizeFormatted}</div>}
                        {!entry.isDirectory && (
                          <div style={{ fontSize: 12, color: 'var(--text-light, #666)' }}>
                            {entry.lastModifiedFormatted}
                          </div>
                        )}
                      </div>
                    }
                    extra={
                      !batchMode ? (
                        <div style={{ display: 'flex', gap: 8 }}>
                          {entry.isDirectory ? (
                            <>
                              <Button size="mini" color="primary" onClick={() => handleDownload(entry)}>
                                下载ZIP
                              </Button>
                              <Button size="mini" color="danger" onClick={() => handleDelete(entry)}>
                                删除
                              </Button>
                            </>
                          ) : (
                            <>
                              <Button size="mini" color="primary" onClick={() => handleDownload(entry)}>
                                下载
                              </Button>
                              <Button size="mini" color="danger" onClick={() => handleDelete(entry)}>
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
                          checked={selectedPaths.has(entry.path)}
                          onChange={() => toggleSelection(entry.path)}
                          style={{ marginRight: 8 }}
                        />
                      )}
                      <span className="file-icon" style={{ marginRight: 8 }}>
                        {fileIcon(entry)}
                      </span>
                      <span
                        style={{ color: 'var(--primary, #2196f3)', cursor: 'pointer' }}
                        onClick={() => openEntry(entry)}
                      >
                        {entry.name}
                      </span>
                    </div>
                  </List.Item>
                ))}
              </List>
            )}
          </div>
          {batchMode && selectedPaths.size > 0 && (
            <div style={{ position: 'fixed', bottom: 50, left: 0, right: 0, padding: '8px 16px', background: 'var(--card-bg, #fff)', borderTop: '1px solid var(--border, #e0e0e0)' }}>
              <Button block color="danger" onClick={handleBatchDelete}>
                删除选中 ({selectedPaths.size})
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
        </div>
      )}
    </div>
  )
}
