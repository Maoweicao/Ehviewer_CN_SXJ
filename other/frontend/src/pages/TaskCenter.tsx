import { useState, useEffect, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  NavBar,
  Tag,
  Button,
  Empty,
  Toast,
  Dialog,
  Tabs,
  Popup,
} from 'antd-mobile'
import api, {
  type PushTask,
  type CompressTask,
  type RelayTask,
  type RelayDirection,
  type BackgroundTaskInfo,
  type BackgroundTaskType,
} from '../api/client'
import FullScreenLoading from '../components/FullScreenLoading'

type UnifiedTaskItem = {
  taskId: string
  type: 'push' | 'compress'
  subType?: string
  status: string
  paused?: boolean
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
  const [bgTasks, setBgTasks] = useState<{ active: BackgroundTaskInfo[]; completed: BackgroundTaskInfo[] }>({
    active: [],
    completed: [],
  })
  const [bgLoading, setBgLoading] = useState(false)
  const [taskTypes, setTaskTypes] = useState<BackgroundTaskType[]>([])
  const [createOpen, setCreateOpen] = useState(false)
  const [createLoading, setCreateLoading] = useState(false)
  const [logDialog, setLogDialog] = useState<{ task: BackgroundTaskInfo; logs: string[] } | null>(null)
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
          paused: t.paused,
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

  const loadBackgroundTasks = useCallback(async () => {
    setBgLoading(true)
    try {
      const res = await api.getBackgroundTasks()
      setBgTasks({ active: res.active || [], completed: res.completed || [] })
    } catch (e) {
      console.error('Failed to load background tasks:', e)
    } finally {
      setBgLoading(false)
    }
  }, [])

  const loadTaskTypes = useCallback(async () => {
    try {
      const res = await api.getBackgroundTaskTypes()
      setTaskTypes(res.taskTypes || [])
    } catch (e) {
      console.error('Failed to load task types:', e)
    }
  }, [])

  useEffect(() => {
    loadTasks()
    loadRelayTasks()

    // Poll every 3 seconds while on unified tabs
    timerRef.current = setInterval(() => {
      if (activeTab === 'background') {
        loadBackgroundTasks()
      } else if (activeTab.startsWith('relay')) {
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

  const handleCancel = async (task: UnifiedTaskItem) => {
    const result = await Dialog.confirm({ content: '确定要取消此任务吗？' })
    if (!result) return

    try {
      if (task.type === 'compress') {
        await api.request(`/api/v1/compress/tasks/${encodeURIComponent(task.taskId)}`, { method: 'DELETE' })
      } else {
        await api.request(`/api/v1/tasks/${task.taskId}`, { method: 'DELETE' })
      }
      Toast.show({ content: '已取消', icon: 'success' })
      loadTasks()
    } catch {
      Toast.show({ content: '操作失败', icon: 'fail' })
    }
  }

  const handlePauseCompress = async (taskId: string) => {
    try {
      await api.request(`/api/v1/compress/tasks/${encodeURIComponent(taskId)}/pause`, { method: 'POST' })
      Toast.show({ content: '已暂停', icon: 'success' })
      loadTasks()
    } catch (e: any) {
      Toast.show({ content: e?.message || '操作失败', icon: 'fail' })
    }
  }

  const handleResumeCompress = async (taskId: string) => {
    try {
      await api.request(`/api/v1/compress/tasks/${encodeURIComponent(taskId)}/resume`, { method: 'POST' })
      Toast.show({ content: '已恢复', icon: 'success' })
      loadTasks()
    } catch (e: any) {
      Toast.show({ content: e?.message || '操作失败', icon: 'fail' })
    }
  }

  const handleStopCompress = async (taskId: string) => {
    const ok = await Dialog.confirm({ content: '确定要停止此压缩任务吗？' })
    if (!ok) return
    try {
      await api.request(`/api/v1/compress/tasks/${encodeURIComponent(taskId)}/stop`, { method: 'POST' })
      Toast.show({ content: '已停止', icon: 'success' })
      loadTasks()
    } catch (e: any) {
      Toast.show({ content: e?.message || '操作失败', icon: 'fail' })
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

  // ===== 后台任务操作 =====

  const handleCreateTask = async (className: string) => {
    setCreateLoading(true)
    try {
      const res = await api.createBackgroundTask(className)
      Toast.show({ content: '任务已创建', icon: 'success' })
      setCreateOpen(false)
      loadBackgroundTasks()
    } catch (e: any) {
      Toast.show({ content: e?.message || '创建失败', icon: 'fail' })
    } finally {
      setCreateLoading(false)
    }
  }

  const handlePauseBg = async (taskId: string) => {
    try {
      await api.pauseBackgroundTask(taskId)
      Toast.show({ content: '已暂停', icon: 'success' })
      loadBackgroundTasks()
    } catch (e: any) {
      Toast.show({ content: e?.message || '操作失败', icon: 'fail' })
    }
  }

  const handleResumeBg = async (taskId: string) => {
    try {
      await api.resumeBackgroundTask(taskId)
      Toast.show({ content: '已恢复', icon: 'success' })
      loadBackgroundTasks()
    } catch (e: any) {
      Toast.show({ content: e?.message || '操作失败', icon: 'fail' })
    }
  }

  const handleStopBg = async (taskId: string) => {
    const ok = await Dialog.confirm({ content: '确定要停止此任务吗？' })
    if (!ok) return
    try {
      await api.stopBackgroundTask(taskId)
      Toast.show({ content: '已停止', icon: 'success' })
      loadBackgroundTasks()
    } catch (e: any) {
      Toast.show({ content: e?.message || '操作失败', icon: 'fail' })
    }
  }

  const handleDeleteBg = async (task: BackgroundTaskInfo) => {
    const ok = await Dialog.confirm({ content: `确定删除「${task.taskName}」吗？` })
    if (!ok) return
    try {
      await api.deleteBackgroundTask(task.taskId)
      Toast.show({ content: '已删除', icon: 'success' })
      loadBackgroundTasks()
    } catch (e: any) {
      Toast.show({ content: e?.message || '操作失败', icon: 'fail' })
    }
  }

  const handleViewLogs = async (task: BackgroundTaskInfo) => {
    try {
      const res = await api.getBackgroundTaskLogs(task.taskId)
      setLogDialog({ task, logs: res.logs || [] })
    } catch (e: any) {
      Toast.show({ content: e?.message || '获取日志失败', icon: 'fail' })
    }
  }

  const BG_STATE_LABELS: Record<string, string> = {
    PENDING: '等待中',
    RUNNING: '运行中',
    PAUSED: '已暂停',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
  }

  const BG_STATE_COLORS: Record<string, string> = {
    PENDING: '#ff9800',
    RUNNING: '#2196f3',
    PAUSED: '#ff9800',
    COMPLETED: '#4caf50',
    FAILED: '#f44336',
    CANCELLED: '#795548',
  }

  const renderBackgroundTask = (task: BackgroundTaskInfo) => {
    const isTerminal = task.isCompleted || task.isCancelled
    return (
      <div
        key={task.taskId}
        style={{
          background: 'var(--card-bg, #fff)',
          borderRadius: 8,
          padding: 16,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
          borderLeft: `4px solid ${BG_STATE_COLORS[task.state] || '#9e9e9e'}`,
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 8, gap: 8 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontWeight: 600, fontSize: 14, wordBreak: 'break-all' }}>{task.taskName}</div>
            {task.taskDescription && (
              <div style={{ fontSize: 12, color: 'var(--text-light)', marginTop: 2 }}>{task.taskDescription}</div>
            )}
          </div>
          <Tag
            color="primary"
            fill="solid"
            style={{
              '--background-color': BG_STATE_COLORS[task.state] || '#9e9e9e',
              '--text-color': '#fff',
              '--border-color': 'transparent',
              fontSize: 11,
              flexShrink: 0,
            }}
          >
            {BG_STATE_LABELS[task.state] || task.state}
          </Tag>
        </div>

        {!isTerminal && (
          <div style={{ marginBottom: 8 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--text-light)', marginBottom: 4 }}>
              <span>
                {task.progressPercentage >= 0
                  ? `${task.currentProgress} / ${task.totalProgress}`
                  : '进度未知'}
              </span>
              {task.estimatedRemainingMs && task.estimatedRemainingMs > 0 && (
                <span>剩余约 {(task.estimatedRemainingMs / 60000).toFixed(1)} 分钟</span>
              )}
            </div>
            <div style={{ height: 6, background: 'var(--progress-bg)', borderRadius: 3, overflow: 'hidden' }}>
              <div
                style={{
                  height: '100%',
                  width: `${task.progressPercentage >= 0 ? task.progressPercentage : 100}%`,
                  background: task.state === 'PAUSED' ? '#ff9800' : '#2196f3',
                  borderRadius: 3,
                  transition: 'width 0.3s',
                  opacity: task.progressPercentage >= 0 ? 1 : 0.3,
                }}
              />
            </div>
            {task.progressDetail && (
              <div style={{ fontSize: 11, color: 'var(--text-light)', marginTop: 4 }}>{task.progressDetail}</div>
            )}
          </div>
        )}

        {task.errorMessage && (
          <div style={{ fontSize: 12, color: '#f44336', marginBottom: 8 }}>⚠️ {task.errorMessage}</div>
        )}

        <div style={{ fontSize: 12, color: 'var(--text-light)', marginBottom: 8 }}>
          <span>类型: {task.taskType}</span>
          {task.isPausable && <span style={{ marginLeft: 8 }}>· 支持暂停</span>}
          <div>开始: {formatTime(task.startTime)}</div>
        </div>

        <div style={{ display: 'flex', gap: 8, marginTop: 4, flexWrap: 'wrap' }}>
          <Button size="small" color="default" onClick={() => handleViewLogs(task)}>日志</Button>
          {!isTerminal && task.state === 'RUNNING' && task.isPausable && (
            <Button size="small" color="primary" onClick={() => handlePauseBg(task.taskId)}>暂停</Button>
          )}
          {task.state === 'PAUSED' && (
            <Button size="small" color="primary" onClick={() => handleResumeBg(task.taskId)}>继续</Button>
          )}
          {!isTerminal && (
            <Button size="small" color="warning" onClick={() => handleStopBg(task.taskId)}>停止</Button>
          )}
          {isTerminal && (
            <Button size="small" color="default" onClick={() => handleDeleteBg(task)}>删除</Button>
          )}
        </div>
      </div>
    )
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
        <Tabs.Tab title={`后台 (${bgTasks.active.length + bgTasks.completed.length})`} key="background" />
        <Tabs.Tab title={`接力 (${relayTasks.length})`} key="relay-all" />
        <Tabs.Tab title={`失败 (${tasks.filter(t => ['failed', 'cancelled', 'rejected'].includes(t.status)).length})`} key="failed" />
      </Tabs>

      <div className="page-content">
        {activeTab === 'background' ? (
          <>
            <div style={{ padding: '8px 12px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderBottom: '1px solid var(--border)' }}>
              <span style={{ fontSize: 13, color: 'var(--text-light)' }}>
                后台任务 {bgTasks.active.length + bgTasks.completed.length} 个
              </span>
              <Button size="small" color="primary" onClick={() => { loadTaskTypes(); setCreateOpen(true) }}>
                新建任务
              </Button>
            </div>
            {bgLoading && bgTasks.active.length === 0 && bgTasks.completed.length === 0 ? (
              <FullScreenLoading text="加载后台任务中..." />
            ) : bgTasks.active.length === 0 && bgTasks.completed.length === 0 ? (
              <Empty description="暂无后台任务" style={{ padding: '60px 0' }} />
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: 12 }}>
                {bgTasks.active.length > 0 && (
                  <div style={{ fontSize: 12, color: 'var(--text-light)', paddingLeft: 4 }}>运行中 ({bgTasks.active.length})</div>
                )}
                {bgTasks.active.map(renderBackgroundTask)}
                {bgTasks.completed.length > 0 && (
                  <div style={{ fontSize: 12, color: 'var(--text-light)', paddingLeft: 4, marginTop: 8 }}>已完成 ({bgTasks.completed.length})</div>
                )}
                {bgTasks.completed.map(renderBackgroundTask)}
              </div>
            )}
          </>
        ) : activeTab.startsWith('relay') ? (
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
              <FullScreenLoading text="加载接力任务中..." />
            ) : relayFiltered.length === 0 ? (
              <Empty description="暂无接力任务" style={{ padding: '60px 0' }} />
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: 12 }}>
                {relayFiltered.map(renderRelayTask)}
              </div>
            )}
          </>
        ) : unifiedLoading && tasks.length === 0 ? (
          <FullScreenLoading text="加载任务中..." />
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

                <div style={{ display: 'flex', gap: 8, marginTop: 4, flexWrap: 'wrap' }}>
                  {task.status === 'pending' && task.type === 'push' && (
                    <>
                      <Button size="small" color="primary" onClick={() => handleAccept(task.taskId)}>接受</Button>
                      <Button size="small" color="danger" onClick={() => handleReject(task.taskId)}>拒绝</Button>
                    </>
                  )}
                  {task.type === 'compress' && task.status === 'in_progress' && !task.paused && (
                    <Button size="small" color="primary" onClick={() => handlePauseCompress(task.taskId)}>暂停</Button>
                  )}
                  {task.type === 'compress' && task.status === 'in_progress' && task.paused && (
                    <Button size="small" color="primary" onClick={() => handleResumeCompress(task.taskId)}>继续</Button>
                  )}
                  {!isTerminal(task.status) && (
                    <>
                      <Button size="small" color="warning" onClick={() => handleCancel(task)}>取消</Button>
                      {task.type === 'compress' && (
                        <Button size="small" color="danger" onClick={() => handleStopCompress(task.taskId)}>停止</Button>
                      )}
                    </>
                  )}
                  {isTerminal(task.status) && (
                    <Button size="small" color="default" onClick={() => {
                      const url = task.type === 'compress'
                        ? `/api/v1/compress/tasks/${task.taskId}`
                        : `/api/v1/tasks/${task.taskId}`
                      api.request(url, { method: 'DELETE' }).then(loadTasks)
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

      {/* 新建任务弹层 */}
      <Popup
        visible={createOpen}
        onMaskClick={() => setCreateOpen(false)}
        onClose={() => setCreateOpen(false)}
        bodyStyle={{ maxHeight: '70vh', overflow: 'auto', borderTopLeftRadius: 12, borderTopRightRadius: 12 }}
      >
        <div style={{ padding: 16 }}>
          <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 12 }}>新建后台任务</div>
          {taskTypes.length === 0 ? (
            <Empty description="加载任务类型中..." style={{ padding: '40px 0' }} />
          ) : (
            <>
              <div style={{ fontSize: 12, color: 'var(--text-light)', marginBottom: 8 }}>
                以下为无需参数、可一键创建的任务：
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {taskTypes
                  .filter((t) => !t.requiresParams)
                  .map((t) => (
                    <div
                      key={t.taskClassName}
                      onClick={() => !createLoading && handleCreateTask(t.taskClassName)}
                      style={{
                        background: 'var(--card-bg, #fff)',
                        border: '1px solid var(--border, #eee)',
                        borderRadius: 8,
                        padding: '10px 12px',
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        gap: 8,
                      }}
                    >
                      <div style={{ minWidth: 0 }}>
                        <div style={{ fontSize: 14, fontWeight: 500 }}>{t.displayName}</div>
                        {t.description && (
                          <div style={{ fontSize: 12, color: 'var(--text-light)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {t.description}
                          </div>
                        )}
                      </div>
                      <Tag color="primary" fill="outline" style={{ fontSize: 10, flexShrink: 0 }}>
                        {t.taskType}
                      </Tag>
                    </div>
                  ))}
              </div>
              <Button
                block
                color="default"
                style={{ marginTop: 12 }}
                onClick={() => setCreateOpen(false)}
              >
                关闭
              </Button>
            </>
          )}
        </div>
      </Popup>

      {/* 任务日志弹层 */}
      {logDialog && (
        <Popup
          visible={!!logDialog}
          onMaskClick={() => setLogDialog(null)}
          onClose={() => setLogDialog(null)}
          bodyStyle={{ maxHeight: '70vh', overflow: 'auto', borderTopLeftRadius: 12, borderTopRightRadius: 12 }}
        >
          <div style={{ padding: 16 }}>
            <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 4 }}>{logDialog.task.taskName}</div>
            <div style={{ fontSize: 12, color: 'var(--text-light)', marginBottom: 12 }}>任务日志（{logDialog.logs.length} 条）</div>
            {logDialog.logs.length === 0 ? (
              <Empty description="暂无日志" style={{ padding: '30px 0' }} />
            ) : (
              <pre
                style={{
                  background: 'rgba(0,0,0,0.04)',
                  borderRadius: 8,
                  padding: 12,
                  fontSize: 11,
                  lineHeight: 1.6,
                  maxHeight: 320,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                  fontFamily: 'monospace',
                  margin: 0,
                }}
              >
                {logDialog.logs.join('\n')}
              </pre>
            )}
            <Button block color="default" style={{ marginTop: 12 }} onClick={() => setLogDialog(null)}>
              关闭
            </Button>
          </div>
        </Popup>
      )}
    </div>
  )
}