import api from '../api/client'

/**
 * 数据变更订阅（协议 §5.28）
 *
 * 优先使用 SSE（GET /api/v1/events）近实时接收事件；连接失败或不受支持时
 * 自动降级为轮询 GET /api/v1/system/version。
 */

export type DataChangeHandler = (type: string, data: Record<string, unknown>) => void

const EVENT_TYPES = [
  'heartbeat',
  'favorite_added',
  'favorite_removed',
  'download_added',
  'download_changed',
  'download_removed',
  'login_changed',
  'data_changed',
]

/** 触发刷新语义的事件（心跳除外） */
const REFRESH_TYPES = new Set([
  'favorite_added',
  'favorite_removed',
  'download_added',
  'download_changed',
  'download_removed',
  'login_changed',
  'data_changed',
])

const handlers = new Set<DataChangeHandler>()
let es: EventSource | null = null
let pollTimer: ReturnType<typeof setInterval> | null = null
let retryTimer: ReturnType<typeof setTimeout> | null = null
let started = false
let lastVersion = 0

function emit(type: string, data: Record<string, unknown>) {
  handlers.forEach((h) => {
    try {
      h(type, data)
    } catch {
      /* ignore listener errors */
    }
  })
}

function startPolling() {
  if (pollTimer) return
  const tick = async () => {
    if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return
    try {
      const v = await api.getDataVersion(lastVersion)
      const changed = lastVersion !== 0 && v.version !== lastVersion
      lastVersion = v.version
      if (changed) emit('data_changed', { version: v.version })
    } catch {
      /* ignore */
    }
  }
  tick()
  pollTimer = setInterval(tick, 5000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function connect() {
  if (typeof EventSource === 'undefined') {
    startPolling()
    return
  }
  stopPolling()
  try {
    es = new EventSource(api.getEventsUrl())
    EVENT_TYPES.forEach((t) => {
      es!.addEventListener(t, (ev: MessageEvent) => {
        if (t === 'heartbeat') return
        let data: Record<string, unknown> = {}
        try {
          data = JSON.parse(ev.data)
        } catch {
          /* ignore parse errors */
        }
        emit(t, data)
      })
    })
    es.onopen = () => {
      stopPolling()
    }
    es.onerror = () => {
      if (es) {
        es.close()
        es = null
      }
      // 降级轮询，并稍后重试 SSE
      startPolling()
      if (retryTimer) clearTimeout(retryTimer)
      retryTimer = setTimeout(() => {
        if (started && !es) connect()
      }, 15000)
    }
  } catch {
    startPolling()
  }
}

/** 幂等启动（通常在 App 挂载时调用一次） */
export function ensureDataEvents() {
  if (started) return
  started = true
  connect()
}

/** 订阅数据变更；返回取消订阅函数 */
export function subscribeDataChange(handler: DataChangeHandler): () => void {
  handlers.add(handler)
  ensureDataEvents()
  return () => {
    handlers.delete(handler)
  }
}

export { REFRESH_TYPES }
