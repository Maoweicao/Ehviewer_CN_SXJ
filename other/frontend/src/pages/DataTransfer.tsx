import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  NavBar,
  List,
  Button,
  Toast,
  Dialog,
  SpinLoading,
  Empty,
  TextArea,
  Popup,
} from 'antd-mobile'
import api, { type ExportFile, type ExportData, type ImportResult, type SystemCacheResponse } from '../api/client'
import FullScreenLoading from '../components/FullScreenLoading'

export default function DataTransfer() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [exportFiles, setExportFiles] = useState<{ dbFiles: ExportFile[]; csvFiles: ExportFile[] }>({ dbFiles: [], csvFiles: [] })
  const [exportData, setExportData] = useState<ExportData | null>(null)
  const [importResult, setImportResult] = useState<ImportResult | null>(null)
  const [jsonInput, setJsonInput] = useState('')
  const dbFileRef = useRef<HTMLInputElement>(null)
  const csvFileRef = useRef<HTMLInputElement>(null)
  const jsonFileRef = useRef<HTMLInputElement>(null)

  // === Web服务器缓存 ===
  const [cacheData, setCacheData] = useState<SystemCacheResponse | null>(null)
  const [cacheLoading, setCacheLoading] = useState(false)
  const [cachePopup, setCachePopup] = useState(false)

  useEffect(() => {
    loadExportFiles()
  }, [])

  const loadExportFiles = async () => {
    try {
      const data = await api.getExportFiles()
      setExportFiles(data)
    } catch (e) {
      console.error('Failed to load export files:', e)
    }
  }

  // ==================== Export Handlers ====================

  const handleExportBookmarks = async () => {
    setLoading(true)
    try {
      const data = await api.exportBookmarks()
      setExportData(data)
      Toast.show({ content: `导出 ${data.total} 条书签`, icon: 'success' })
    } catch {
      Toast.show({ content: '导出失败', icon: 'fail' })
    } finally {
      setLoading(false)
    }
  }

  const handleExportFavorites = async () => {
    setLoading(true)
    try {
      const data = await api.exportFavorites()
      setExportData(data)
      Toast.show({ content: `导出 ${data.total} 条收藏`, icon: 'success' })
    } catch {
      Toast.show({ content: '导出失败', icon: 'fail' })
    } finally {
      setLoading(false)
    }
  }

  const handleExportDownloads = async () => {
    setLoading(true)
    try {
      const data = await api.exportDownloads()
      setExportData(data)
      Toast.show({ content: `导出 ${data.total} 条下载`, icon: 'success' })
    } catch {
      Toast.show({ content: '导出失败', icon: 'fail' })
    } finally {
      setLoading(false)
    }
  }

  const handleDownloadDB = () => {
    const url = api.getExportDBUrl()
    const a = document.createElement('a')
    a.href = url
    a.download = 'ehviewer_export.db'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    Toast.show({ content: '开始下载数据库文件' })
  }

  const handleDownloadCSV = () => {
    const url = api.getExportCSVUrl()
    const a = document.createElement('a')
    a.href = url
    a.download = 'ehviewer_export.csv'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    Toast.show({ content: '开始下载CSV文件' })
  }

  const handleCopyJson = () => {
    if (!exportData) return
    const json = JSON.stringify(exportData, null, 2)
    navigator.clipboard.writeText(json).then(() => {
      Toast.show({ content: '已复制到剪贴板', icon: 'success' })
    }).catch(() => {
      Toast.show({ content: '复制失败', icon: 'fail' })
    })
  }

  const handleDownloadJson = () => {
    if (!exportData) return
    const json = JSON.stringify(exportData, null, 2)
    const blob = new Blob([json], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `ehviewer_${exportData.type}_${new Date().toISOString().slice(0, 10)}.json`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  // ==================== Import Handlers ====================

  const handleImportDB = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    setLoading(true)
    try {
      const result = await api.importDB(file)
      Toast.show({ content: result.message, icon: result.success ? 'success' : 'fail' })
      loadExportFiles()
    } catch {
      Toast.show({ content: '导入失败', icon: 'fail' })
    } finally {
      setLoading(false)
      if (dbFileRef.current) dbFileRef.current.value = ''
    }
  }

  const handleImportCSV = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    setLoading(true)
    try {
      const result = await api.importCSV(file)
      setImportResult(result)
      Toast.show({ content: `导入 ${result.imported} 条，跳过 ${result.skipped} 条`, icon: 'success' })
    } catch {
      Toast.show({ content: '导入失败', icon: 'fail' })
    } finally {
      setLoading(false)
      if (csvFileRef.current) csvFileRef.current.value = ''
    }
  }

  const handleImportJsonFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    try {
      const text = await file.text()
      setJsonInput(text)
    } catch {
      Toast.show({ content: '读取文件失败', icon: 'fail' })
    }
    if (jsonFileRef.current) jsonFileRef.current.value = ''
  }

  const handleImportJson = async (type: 'bookmarks' | 'downloads' | 'favorites') => {
    if (!jsonInput.trim()) {
      Toast.show({ content: '请输入或粘贴JSON数据' })
      return
    }

    setLoading(true)
    try {
      const data = JSON.parse(jsonInput)
      const items = data.items || []
      if (items.length === 0) {
        Toast.show({ content: '没有找到可导入的数据' })
        return
      }

      let result: ImportResult
      switch (type) {
        case 'bookmarks':
          result = await api.importBookmarks(items)
          break
        case 'favorites':
          result = await api.importFavorites(items)
          break
        case 'downloads':
          result = await api.importDownloads(items)
          break
      }

      setImportResult(result!)
      Toast.show({ content: `导入 ${result!.imported} 条，跳过 ${result!.skipped} 条`, icon: 'success' })
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '导入失败'
      Toast.show({ content: msg, icon: 'fail' })
    } finally {
      setLoading(false)
    }
  }

  // ==================== Web服务器缓存 Handlers ====================

  const handleViewCache = async () => {
    setCachePopup(true)
    setCacheLoading(true)
    try {
      const data = await api.getSystemCache()
      setCacheData(data)
    } catch {
      setCacheData(null)
      Toast.show({ content: '获取缓存列表失败', icon: 'fail' })
    } finally {
      setCacheLoading(false)
    }
  }

  const handleClearCache = () => {
    if (!cacheData || cacheData.total === 0) {
      Toast.show({ content: '当前没有可清空的缓存' })
      return
    }
    Dialog.confirm({
      content: `确定要清空 Web 服务器缓存吗？（共 ${cacheData.total} 条）`,
      onConfirm: async () => {
        try {
          const result = await api.clearSystemCache()
          Toast.show({ content: result.message || `已清空 ${result.cleared} 条缓存`, icon: 'success' })
          await handleViewCache()
        } catch {
          Toast.show({ content: '清空缓存失败', icon: 'fail' })
        }
      },
    })
  }

  const formatRemaining = (ms: number): string => {
    if (!ms || ms <= 0) return '0s'
    const sec = Math.floor(ms / 1000)
    if (sec < 60) return `${sec}s`
    const min = Math.floor(sec / 60)
    if (min < 60) return `${min}分`
    const hour = Math.floor(min / 60)
    return `${hour}小时`
  }

  return (
    <div>
      <NavBar onBack={() => navigate(-1)}>数据导入导出</NavBar>
      <div className="page-content">

        {/* Export Section */}
        <List header="导出数据">
          <List.Item
            extra={<Button size="mini" color="primary" onClick={handleExportBookmarks}>导出</Button>}
          >
            书签数据 (JSON)
          </List.Item>
          <List.Item
            extra={<Button size="mini" color="primary" onClick={handleExportFavorites}>导出</Button>}
          >
            收藏数据 (JSON)
          </List.Item>
          <List.Item
            extra={<Button size="mini" color="primary" onClick={handleExportDownloads}>导出</Button>}
          >
            下载列表 (JSON)
          </List.Item>
          <List.Item
            extra={<Button size="mini" color="primary" onClick={handleDownloadDB}>下载</Button>}
          >
            数据库文件 (.db)
          </List.Item>
          <List.Item
            extra={<Button size="mini" color="primary" onClick={handleDownloadCSV}>下载</Button>}
          >
            CSV文件 (.csv)
          </List.Item>
        </List>

        {/* Export Result */}
        {exportData && (
          <List header={`导出结果 (${exportData.type})`} style={{ marginTop: 16 }}>
            <List.Item extra={exportData.total}>
              数据条数
            </List.Item>
            <List.Item extra={new Date(exportData.exportTime).toLocaleString()}>
              导出时间
            </List.Item>
            <List.Item>
              <div style={{ display: 'flex', gap: 8 }}>
                <Button size="small" color="primary" onClick={handleCopyJson}>复制JSON</Button>
                <Button size="small" color="success" onClick={handleDownloadJson}>下载JSON</Button>
              </div>
            </List.Item>
          </List>
        )}

        {/* Import Section */}
        <List header="导入数据" style={{ marginTop: 16 }}>
          <List.Item
            extra={
              <>
                <input ref={dbFileRef} type="file" accept=".db" style={{ display: 'none' }} onChange={handleImportDB} />
                <Button size="mini" color="primary" onClick={() => dbFileRef.current?.click()}>选择文件</Button>
              </>
            }
          >
            导入数据库 (.db)
          </List.Item>
          <List.Item
            extra={
              <>
                <input ref={csvFileRef} type="file" accept=".csv" style={{ display: 'none' }} onChange={handleImportCSV} />
                <Button size="mini" color="primary" onClick={() => csvFileRef.current?.click()}>选择文件</Button>
              </>
            }
          >
            导入CSV文件 (.csv)
          </List.Item>
        </List>

        {/* JSON Import */}
        <List header="导入JSON数据" style={{ marginTop: 16 }}>
          <List.Item>
            <div style={{ width: '100%' }}>
              <div style={{ marginBottom: 8, fontSize: 13, color: 'var(--text-light)' }}>
                粘贴导出的JSON数据，或
                <>
                  <input ref={jsonFileRef} type="file" accept=".json" style={{ display: 'none' }} onChange={handleImportJsonFile} />
                  <span
                    style={{ color: '#2196F3', cursor: 'pointer' }}
                    onClick={() => jsonFileRef.current?.click()}
                  >
                    选择JSON文件
                  </span>
                </>
              </div>
              <TextArea
                placeholder='粘贴JSON数据...'
                value={jsonInput}
                onChange={setJsonInput}
                rows={6}
                style={{ fontSize: 12, fontFamily: 'monospace' }}
              />
              <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                <Button size="small" color="primary" onClick={() => handleImportJson('bookmarks')}>导入为书签</Button>
                <Button size="small" color="success" onClick={() => handleImportJson('downloads')}>导入为下载</Button>
                <Button size="small" color="warning" onClick={() => handleImportJson('favorites')}>导入为收藏</Button>
              </div>
            </div>
          </List.Item>
        </List>

        {/* Import Result */}
        {importResult && (
          <List header="导入结果" style={{ marginTop: 16 }}>
            <List.Item extra={importResult.imported}>成功导入</List.Item>
            <List.Item extra={importResult.skipped}>跳过</List.Item>
            <List.Item extra={importResult.failed}>失败</List.Item>
            {importResult.conflicts && importResult.conflicts.length > 0 && (
              <List.Item>
                <div style={{ fontSize: 12, color: '#ff9800' }}>
                  冲突: {importResult.conflicts.map(c => c.existingTitle).join(', ')}
                </div>
              </List.Item>
            )}
          </List>
        )}

        {/* Web服务器缓存 */}
        <List header="Web服务器缓存" style={{ marginTop: 16 }}>
          <List.Item
            extra={
              <div style={{ display: 'flex', gap: 8 }}>
                <Button size="mini" color="primary" onClick={handleViewCache}>查看缓存</Button>
                <Button
                  size="mini"
                  color="danger"
                  onClick={handleClearCache}
                  disabled={!cacheData || cacheData.total === 0}
                >
                  清空缓存
                </Button>
              </div>
            }
          >
            <div style={{ fontSize: 13 }}>
              {cacheData
                ? `${cacheData.total} 条 · ${cacheData.totalBytesFormatted || cacheData.totalBytes}`
                : '点击查看服务器端响应缓存'}
            </div>
          </List.Item>
        </List>

        {/* 缓存列表弹窗 */}
        <Popup
          visible={cachePopup}
          onMaskClick={() => setCachePopup(false)}
          position="bottom"
          bodyStyle={{
            borderTopLeftRadius: 12,
            borderTopRightRadius: 12,
            maxHeight: '80vh',
            overflowY: 'auto',
          }}
        >
          <div className="cache-popup">
            <div className="filter-header">
              <strong>Web服务器缓存</strong>
              <Button size="mini" onClick={() => setCachePopup(false)}>
                关闭
              </Button>
            </div>
            {cacheLoading ? (
              <div style={{ display: 'flex', justifyContent: 'center', padding: 24 }}>
                <SpinLoading style={{ '--size': '28px' }} />
              </div>
            ) : cacheData && cacheData.entries.length > 0 ? (
              <>
                <div style={{ padding: '8px 12px', fontSize: 13, color: 'var(--text-light)' }}>
                  共 {cacheData.total} 条缓存，总大小 {cacheData.totalBytesFormatted || cacheData.totalBytes}
                </div>
                <List>
                  {cacheData.entries.map((entry) => (
                    <List.Item
                      key={entry.key}
                      description={
                        <span style={{ fontSize: 12 }}>
                          {entry.sizeFormatted || entry.size} · 剩余 {formatRemaining(entry.remainingMs)}
                        </span>
                      }
                    >
                      <span style={{ fontFamily: 'monospace', fontSize: 13, wordBreak: 'break-all' }}>{entry.key}</span>
                    </List.Item>
                  ))}
                </List>
              </>
            ) : (
              <Empty description="暂无缓存条目" style={{ padding: '24px 0' }} />
            )}
          </div>
        </Popup>

        {/* Existing Export Files */}
        <List header="已有导出文件" style={{ marginTop: 16 }}>
          {exportFiles.dbFiles.length === 0 && exportFiles.csvFiles.length === 0 ? (
            <List.Item>
              <Empty description="暂无导出文件" style={{ padding: '20px 0' }} />
            </List.Item>
          ) : (
            <>
              {exportFiles.dbFiles.map((f) => (
                <List.Item
                  key={f.name}
                  description={`${f.sizeFormatted} | ${f.lastModifiedFormatted}`}
                  extra={
                    <Button
                      size="mini"
                      color="primary"
                      onClick={() => {
                        const a = document.createElement('a')
                        a.href = `/api/v1/data/export/db?file=${encodeURIComponent(f.name)}`
                        a.download = f.name
                        document.body.appendChild(a)
                        a.click()
                        document.body.removeChild(a)
                      }}
                    >
                      下载
                    </Button>
                  }
                >
                  {f.name}
                </List.Item>
              ))}
              {exportFiles.csvFiles.map((f) => (
                <List.Item
                  key={f.name}
                  description={`${f.sizeFormatted} | ${f.lastModifiedFormatted}`}
                  extra={
                    <Button
                      size="mini"
                      color="primary"
                      onClick={() => {
                        const a = document.createElement('a')
                        a.href = `/api/v1/data/export/csv?file=${encodeURIComponent(f.name)}`
                        a.download = f.name
                        document.body.appendChild(a)
                        a.click()
                        document.body.removeChild(a)
                      }}
                    >
                      下载
                    </Button>
                  }
                >
                  {f.name}
                </List.Item>
              ))}
            </>
          )}
        </List>

        {loading && (
          <FullScreenLoading text="处理中..." />
        )}
      </div>
    </div>
  )
}
