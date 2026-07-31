import { useState, useEffect, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  NavBar,
  Tag,
  Button,
  Empty,
  SpinLoading,
  Toast,
  Dialog,
  Tabs,
} from 'antd-mobile'
import api, {
  type PushTask,
  type CompressTask,
  type RelayTask,
  type RelayDirection,
} from '../api/client'

type UnifiedTaskItem = {
  taskId: string
  type: 'push' | 'compress'
  subType?: string
  status: string
  sourceDevice?: string
  progress: number
  total: number
  completed: number
  createdTime: number
  updatedTime?: number
  completedTime?: number | null
  // Push specific
  mode?: string
  fileName?: string
  fileSize?: number
  // Compress specific
  splitSizeMB?: number
  outputFiles?: Array<{ name: string; path: string; size: number; sizeFormatted: string }>
}

const STATUS_LABELS: Record<string, string> = {
  pending: '等待中',
  accepted: '已接受',
  downloading: '下载中',
  in_progress: '进行中',
  transferring: '传输中',
  completed: '已完成',
  failed: '失败',
  cancelled: '已取消',
  rejected: '已拒绝',
  returned: '已取回',
}

const STATUS_COLORS: Record<string, string> = {
  pending: '#ff9800',
  accepted: '#2196f3',
  downloading: '#4caf50',
  in_progress: '#2196f3',
  transferring: '#2196f3',
  completed: '#4caf50',
  failed: '#f44336',
  cancelled: '#795548',
  rejected: '#f44336',
  returned: '#00bcd4',
}

const TERMINAL_STATUSES = ['completed', 'failed', 'cancelled', 'rejected', 'returned']

export default function TaskCenter() {
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState('all')
  const [unifiedLoading, setUnifiedLoading] = useState(false)
  const [tasks, setTasks] = useState<UnifiedTaskItem[]>([])
  const [relayTasks, setRelayTasks] = useState<RelayTask[]>([])
  const [relayLoading, setRelayLoading] = useState(false)
  const [relayDirection, setRelayDirection] = useState<'all' | RelayDirection>('all')
  const [relayStatus, setRelayStatus] = useState<'all' | string>('all')
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const loadTasks = useCallback(async () => {
    setUnifiedLoading(true)
    try {
      // Load push tasks
      const pushRes = await api.getPushTasks()
      const pushTasks: UnifiedTaskItem[] = (pushRes.tasks || []).map((t: PushTask) => ({
        taskId: t.id,
        type: 'push' as const,
        subType: t.type,
        status: t.status,
        sourceDevice: t.fromDevice,
        progress: t.progress,
        total: t.total,
        completed: t.transferred,
        createdTime: t.createdTime,
      }))

      // Load compress tasks
      let compressTasks: UnifiedTaskItem[] = []
      try {
        const compressRes = await api.getCompressTasks()
        compressTasks = (compressRes.tasks || []).map((t: CompressTask) => ({
          taskId: t.taskId,
          type: 'compress' as const,
          status: t.status,
          progress: t.progress,
          total: t.totalGalleries,
          completed: t.completedGalleries,
          createdTime: t.createdTime,
          completedTime: t.completedTime,
          splitSizeMB: t.splitSizeMB,
          outputFiles: t.outputFiles,
        }))
      } catch {
        // compress tasks endpoint might not exist yet
      }

      // Merge and sort by createdTime descending
      const allTasks = [...pushTasks, ...compressTasks].sort((a, b) => b.createdTime - a.createdTime)
      setTasks(allTasks)
    } catch (e) {
      console.error('Failed to load tasks:', e)
    } finally {
      setUnifiedLoading(false)
    }
  }, [])

  const loadRelayTasks = useCallback(async () => {
    setRelayLoading(true)
    try {
      const res = await api.getRelayTasks(relayStatus, relayDirection)
      setRelayTasks(res.tasks || [])
    } catch (e) {
      console.error('Failed to load relay tasks:', e)
    } finally {
      setRelayLoading(false)
    }
  }, [relayStatus, relayDirection])

  useEffect(() => {
    loadTasks()
    loadRelayTasks()

    // Poll every 3 seconds while on unified tabs
    timerRef.current = setInterval(() => {
      if (activeTab.startsWith('relay')) {
        loadRelayTasks()
      } else {
        loadTasks()
      }
    }, 3000)

    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab])

  useEffect(() => {
    // 切换 relay 子筛选时重新加载
    if (activeTab.startsWith('relay')) loadRelayTasks()
  }, [relayStatus, relayDirection, activeTab, loadRelayTasks])

  const filteredTasks = activeTab === 'all'
    ? tasks
    : activeTab === 'active'
      ? tasks.filter(t => !TERMINAL_STATUSES.includes(t.status))
      : activeTab === 'completed'
        ? tasks.filter(t => t.status === 'completed')
        : tasks.filter(t => ['failed', 'cancelled', 'rejected'].includes(t.status))

  const handleAccept = async (taskId: string) => {
    try {
      await api.request(`/api/v1/push/tasks/${taskId}/accept`, { method: 'POST' })
      Toast.show({ content: '已接受', icon: 'success' })
      loadTasks()
    } catch {
      Toast.show({ content: '操作失败', icon: 'fail' })
    }
  }

  const handleReject = async (taskId: string) => {
    const result = await Dialog.confirm({ content: '确定要拒绝此任务吗？' })
    if (!result) return

    try {
      await api.request(`/api/v1/push/tasks/${taskId}/reject`, { method: 'POST' })
      Toast.show({ content: '已拒绝', icon: 'success' })
      loadTasks()
    } catch {
      Toast.show({ content: '操作失败', icon: 'fail' })
    }
  }

  const handleCancel = async (taskId: string) => {
    const result = await Dialog.confirm({ content: '确定要取消此任务吗？' })
    if (!result) return

    try {
      await api.request(`/api/v1/tasks/${taskId}`, { method: 'DELETE' })
      Toast.show({ content: '已取消', icon: 'success' })
      loadTasks()
    } catch {
      Toast.show({ content: '操作失败', icon: 'fail' })
    }
  }

  const handleDownload = (taskId: string, part = 1) => {
    const url = api.getCompressDownloadUrl(taskId, part)
    const a = document.createElement('a')
    a.href = url
    a.download = ''
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    Toast.show({ content: '开始下载' })
  }

  // ===== Relay 操作 =====

  const handleRelayAccept = async (task: RelayTask) => {
    try {
      const ua = navigator.userAgent
      const deviceName = (navigator as any).userAgentData?.platform || (ua.includes('Win') ? 'PC-Windows' : 'PC-Web')
      await api.acceptRelayTask(task.taskId, {
        acceptedDevice: deviceName,
        acceptedDeviceId: 'web-' + Math.random().toString(36).slice(2, 10),
      })
      Toast.show({ content: '已接受接力任务', icon: 'success' })
      loadRelayTasks()
    } catch (e) {
      Toast.show({ content: '接受失败', icon: 'fail' })
    }
  }

  const handleRelayReject = async (task: RelayTask) => {
    const ok = await Dialog.confirm({ content: `确定拒绝「${task.title || task.gid}」的接力任务？` })
    if (!ok) return
    try {
      await api.rejectRelayTask(task.taskId)
      Toast.show({ content: '已拒绝', icon: 'success' })
      loadRelayTasks()
    } catch {
      Toast.show({ content: '操作失败', icon: 'fail' })
    }
  }

  const handleRelayCancel = async (task: RelayTask) => {
    const ok = await Dialog.confirm({ content: `确定取消「${task.title || task.gid}」的接力任务？` })
    if (!ok) return
    try {
      await api.cancelRelayTask(task.taskId)
      Toast.show({ content: '已取消', icon: 'success' })
      loadRelayTasks()
    } catch {
      Toast.show({ content: '操作失败', icon: 'fail' })
    }
  }

  const handleRelayRetrieve = (task: RelayTask) => {
    const url = api.getRelayDownloadUrl(task.taskId)
    const a = document.createElement('a')
    a.href = url
    a.download = `relay_${task.gid}.zip`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    Toast.show({ content: '开始下载接力 ZIP' })
  }

  const handleRelayDelete = async (task: RelayTask) => {
    const ok = await Dialog.confirm({ content: `确定删除「${task.title || task.gid}」的接力任务记录？` })
    if (!ok) return
    try {
      await api.deleteRelayTask(task.taskId)
      Toast.show({ content: '已删除', icon: 'success' })
      loadRelayTasks()
    } catch {
      Toast.show({ content: '操作失败', icon: 'fail' })
    }
  }

  const formatTime = (timestamp?: number | null) => {
    if (!timestamp) return '-'
    return new Date(timestamp).toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  const isTerminal = (status: string) => TERMINAL_STATUSES.includes(status)

  const getTaskIcon = (type: string) => (type === 'compress' ? '🗜️' : '📤')

  const getSubTypeLabel = (subType?: string) => {
    const labels: Record<string, string> = {
      bookmarks: '书签',
      downloads: '下载',
      favorites: '收藏',
      export_db: '数据库',
      export_csv: 'CSV',
      gallery: '画廊压缩',
    }
    return labels[subType || ''] || subType || ''
  }

  // 渲染接力任务卡片
  const renderRelayTask = (task: RelayTask) => {
    const dirColor = task.direction === 'incoming' ? '#2196f3' : '#9c27b0'
    const dirLabel = task.direction === 'incoming' ? '收到的' : '发出的'
    return (
      <div
        key={task.taskId}
        style={{
          background: 'var(--card-bg, #fff)',
          borderRadius: 8,
          padding: 16,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
          borderLeft: `4px solid ${STATUS_COLORS[task.status] || '#9e9e9e'}`,
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 8, gap: 8 }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, flex: 1, minWidth: 0 }}>
            {task.thumb && (
              <img
                src={task.thumb}
                alt=""
                style={{ width: 48, height: 64, objectFit: 'cover', borderRadius: 4, flexShrink: 0 }}
                onError={(e) => ((e.target as HTMLImageElement).style.display = 'none')}
              />
            )}
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontWeight: 600, fontSize: 14, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {task.title || `GID: ${task.gid}`}
              </div>
              {task.titleJpn && (
                <div style={{ fontSize: 12, color: 'var(--text-light)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {task.titleJpn}
                </div>
              )}
              <div style={{ fontSize: 11, color: 'var(--text-light)', marginTop: 4 }}>
                {task.direction === 'incoming' ? '来自' : '委托给'}：
                {task.direction === 'incoming' ? task.sourceDevice || '未知' : task.targetDevice || '未知'}
                {task.acceptedDevice && (
                  <span style={{ marginLeft: 6, color: dirColor }}>· 由 {task.acceptedDevice} 接受</span>
                )}
              </div>
            </div>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, alignItems: 'flex-end' }}>
            <Tag
              color="primary"
              fill="solid"
              style={{
                '--background-color': STATUS_COLORS[task.status] || '#9e9e9e',
                '--text-color': '#fff',
                '--border-color': 'transparent',
                fontSize: 11,
              }}
            >
              {STATUS_LABELS[task.status] || task.status}
            </Tag>
            <Tag
              fill="outline"
              style={{
                '--border-color': dirColor,
                '--text-color': dirColor,
                fontSize: 10,
              }}
            >
              {dirLabel}
            </Tag>
          </div>
        </div>

        {/* 进度条（下载中） */}
        {task.status === 'downloading' && (
          <div style={{ marginBottom: 8 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--text-light)', marginBottom: 4 }}>
              <span>{task.finished}/{task.total} 页</span>
              <span>{task.speedFormatted || ''} {task.progress.toFixed(1)}%</span>
            </div>
            <div style={{ height: 6, background: 'var(--progress-bg)', borderRadius: 3, overflow: 'hidden' }}>
              <div
                style={{
                  height: '100%',
                  width: `${task.progress}%`,
                  background: STATUS_COLORS[task.status],
                  borderRadius: 3,
                  transition: 'width 0.3s',
                }}
              />
            </div>
            {task.totalSizeFormatted && (
              <div style={{ fontSize: 11, color: 'var(--text-light)', marginTop: 4 }}>
                {(task.downloadedSizeFormatted || '0 B')} / {task.totalSizeFormatted}
              </div>
            )}
          </div>
        )}

        {/* 错误信息 */}
        {task.status === 'failed' && task.errorMessage && (
          <div style={{ fontSize: 12, color: '#f44336', marginBottom: 8 }}>⚠️ {task.errorMessage}</div>
        )}

        {/* 信息 */}
        <div style={{ fontSize: 12, color: 'var(--text-light)', marginBottom: 8 }}>
          <div>创建：{formatTime(task.createdTime)}</div>
          {task.acceptedDate && <div>接受：{formatTime(task.acceptedTime)}</div>}
          {task.completedDate && <div>完成：{formatTime(task.completedTime)}</div>}
          {task.returnedDate && <div>取回：{formatTime(task.returnedTime)}</div>}
          {task.zipFileSizeFormatted && <div>文件大小：{task.zipFileSizeFormatted}</div>}
        </div>

        {/* 操作 */}
        <div style={{ display: 'flex', gap: 8, marginTop: 4, flexWrap: 'wrap' }}>
          {task.status === 'pending' && task.direction === 'incoming' && (
            <>
              <Button size="small" color="primary" onClick={() => handleRelayAccept(task)}>接受</Button>
              <Button size="small" color="danger" onClick={() => handleRelayReject(task)}>拒绝</Button>
            </>
          )}
          {(task.status === 'pending' || task.status === 'accepted' || task.status === 'downloading') && (
            <Button size="small" color="warning" onClick={() => handleRelayCancel(task)}>取消</Button>
          )}
          {task.status === 'returned' && task.direction === 'outgoing' && (
            <Button size="small" color="primary" onClick={() => handleRelayRetrieve(task)}>取回 ZIP</Button>
          )}
          {isTerminal(task.status) && (
            <Button size="small" color="default" onClick={() => handleRelayDelete(task)}>删除</Button>
          )}
        </div>
      </div>
    )
  }

  const relayFiltered = relayTasks

  return (
    <div>
      <NavBar onBack={() => navigate(-1)} right={
        <span style={{ fontSize: 14, cursor: 'pointer' }} onClick={() => { loadTasks(); loadRelayTasks() }}>刷新</span>
      }>
        任务中心
      </NavBar>

      <Tabs activeKey={activeTab} onChange={setActiveTab} style={{ background: 'var(--card-bg, #fff)' }}>
        <Tabs.Tab title={`全部 (${tasks.length})`} key="all" />
        <Tabs.Tab title={`进行中 (${tasks.filter(t => !isTerminal(t.status)).length})`} key="active" />
        <Tabs.Tab title={`已完成 (${tasks.filter(t => t.status === 'completed').length})`} key="completed" />
        <Tabs.Tab title={`接力 (${relayTasks.length})`} key="relay-all" />
        <Tabs.Tab title={`失败 (${tasks.filter(t => ['failed', 'cancelled', 'rejected'].includes(t.status)).length})`} key="failed" />
      </Tabs>

      <div className="page-content">
        {activeTab.startsWith('relay') ? (
          // ===== 接力下载 Tab =====
          <>
            {/* 子筛选 */}
            <div style={{ padding: '8px 12px', display: 'flex', gap: 8, fontSize: 12, flexWrap: 'wrap', borderBottom: '1px solid var(--border)' }}>
              <span style={{ alignSelf: 'center', color: 'var(--text-light)' }}>方向：</span>
              {(['all', 'incoming', 'outgoing'] as const).map((d) => (
                <span
                  key={d}
                  onClick={() => setRelayDirection(d)}
                  style={{
                    padding: '4px 10px',
                    borderRadius: 12,
                    cursor: 'pointer',
                    background: relayDirection === d ? '#2196f3' : 'rgba(0,0,0,0.05)',
                    color: relayDirection === d ? '#fff' : 'inherit',
                  }}
                >
                  {d === 'all' ? '全部' : d === 'incoming' ? '收到的' : '发出的'}
                </span>
              ))}
              <span style={{ width: 12 }} />
              <span style={{ alignSelf: 'center', color: 'var(--text-light)' }}>状态：</span>
              {(['all', 'pending', 'downloading', 'completed', 'returned', 'failed', 'cancelled'] as const).map((s) => (
                <span
                  key={s}
                  onClick={() => setRelayStatus(s)}
                  style={{
                    padding: '4px 10px',
                    borderRadius: 12,
                    cursor: 'pointer',
                    background: relayStatus === s ? '#2196f3' : 'rgba(0,0,0,0.05)',
                    color: relayStatus === s ? '#fff' : 'inherit',
                  }}
                >
                  {s === 'all' ? '全部' : STATUS_LABELS[s] || s}
                </span>
              ))}
            </div>

            {relayLoading && relayTasks.length === 0 ? (
              <div className="loading-container">
                <SpinLoading style={{ '--size': '48px' }} />
              </div>
            ) : relayFiltered.length === 0 ? (
              <Empty description="暂无接力任务" style={{ padding: '60px 0' }} />
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: 12 }}>
                {relayFiltered.map(renderRelayTask)}
              </div>
            )}
          </>
        ) : unifiedLoading && tasks.length === 0 ? (
          <div className="loading-container">
            <SpinLoading style={{ '--size': '48px' }} />
          </div>
        ) : filteredTasks.length === 0 ? (
          <Empty description="暂无任务" style={{ padding: '60px 0' }} />
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: 12 }}>
            {filteredTasks.map((task) => (
              <div
                key={task.taskId}
                style={{
                  background: 'var(--card-bg, #fff)',
                  borderRadius: 8,
                  padding: 16,
                  boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
                  borderLeft: `4px solid ${STATUS_COLORS[task.status] || '#9e9e9e'}`,
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontSize: 20 }}>{getTaskIcon(task.type)}</span>
                    <span style={{ fontWeight: 600, fontSize: 14 }}>
                      {task.type === 'compress' ? '压缩任务' : `推送 - ${getSubTypeLabel(task.subType)}`}
                    </span>
                  </div>
                  <Tag
                    color="primary"
                    fill="solid"
                    style={{
                      '--background-color': STATUS_COLORS[task.status],
                      '--text-color': '#fff',
                      '--border-color': 'transparent',
                      fontSize: 11,
                    }}
                  >
                    {STATUS_LABELS[task.status] || task.status}
                  </Tag>
                </div>

                {!isTerminal(task.status) && (
                  <div style={{ marginBottom: 8 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--text-light)', marginBottom: 4 }}>
                      <span>进度</span>
                      <span>{task.completed} / {task.total}</span>
                    </div>
                    <div style={{ height: 6, background: 'var(--progress-bg)', borderRadius: 3, overflow: 'hidden' }}>
                      <div
                        style={{
                          height: '100%',
                          width: `${task.progress}%`,
                          background: STATUS_COLORS[task.status] || '#2196f3',
                          borderRadius: 3,
                          transition: 'width 0.3s',
                        }}
                      />
                    </div>
                  </div>
                )}

                <div style={{ fontSize: 12, color: 'var(--text-light)', marginBottom: 8 }}>
                  {task.sourceDevice && <div>来源: {task.sourceDevice}</div>}
                  <div>创建: {formatTime(task.createdTime)}</div>
                  {task.completedTime && <div>完成: {formatTime(task.completedTime)}</div>}
                  {task.type === 'compress' && task.splitSizeMB && (
                    <div>分卷: {task.splitSizeMB}MB</div>
                  )}
                  {task.fileName && <div>文件: {task.fileName}</div>}
                </div>

                {task.type === 'compress' && task.status === 'completed' && task.outputFiles && task.outputFiles.length > 0 && (
                  <div style={{ marginBottom: 8 }}>
                    <div style={{ fontSize: 12, color: 'var(--text-light)', marginBottom: 4 }}>输出文件:</div>
                    {task.outputFiles.map((f, i) => (
                      <div key={i} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '4px 0' }}>
                        <span style={{ fontSize: 12 }}>{f.name} ({f.sizeFormatted})</span>
                        <Button size="mini" color="primary" onClick={() => handleDownload(task.taskId, i + 1)}>
                          下载
                        </Button>
                      </div>
                    ))}
                  </div>
                )}

                <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
                  {task.status === 'pending' && task.type === 'push' && (
                    <>
                      <Button size="small" color="primary" onClick={() => handleAccept(task.taskId)}>接受</Button>
                      <Button size="small" color="danger" onClick={() => handleReject(task.taskId)}>拒绝</Button>
                    </>
                  )}
                  {!isTerminal(task.status) && (
                    <Button size="small" color="warning" onClick={() => handleCancel(task.taskId)}>取消</Button>
                  )}
                  {isTerminal(task.status) && (
                    <Button size="small" color="default" onClick={() => {
                      api.request(`/api/v1/tasks/${task.taskId}`, { method: 'DELETE' }).then(loadTasks)
                    }}>
                      移除
                    </Button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}