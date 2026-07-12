import { useState, useEffect, useRef, useCallback } from 'react'

interface LogEntry {
  id: number
  time: string
  level: string
  message: string
}

type LogLevel = 'ALL' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'

const MAX_LOGS = 500
const logs: LogEntry[] = []
let logIdCounter = 0
const listeners: Array<(entry: LogEntry | null) => void> = []

function addLog(level: string, ...args: unknown[]) {
  const timestamp = new Date()
  const timeStr = timestamp.toLocaleTimeString('zh-CN', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })

  const message = args
    .map((a) => {
      if (typeof a === 'object') {
        try {
          return JSON.stringify(a)
        } catch {
          return String(a)
        }
      }
      return String(a)
    })
    .join(' ')

  const entry: LogEntry = {
    id: ++logIdCounter,
    time: timeStr,
    level,
    message,
  }

  logs.push(entry)
  if (logs.length > MAX_LOGS) logs.shift()

  for (const listener of listeners) {
    try {
      listener(entry)
    } catch {
      // ignore
    }
  }
}

const DebugLogger = {
  d: (...args: unknown[]) => addLog('DEBUG', ...args),
  i: (...args: unknown[]) => addLog('INFO', ...args),
  w: (...args: unknown[]) => addLog('WARN', ...args),
  e: (...args: unknown[]) => addLog('ERROR', ...args),
  getLogs: (level?: LogLevel) => {
    if (!level || level === 'ALL') return [...logs]
    return logs.filter((l) => l.level === level)
  },
  clear: () => {
    logs.length = 0
    for (const listener of listeners) {
      try {
        listener(null)
      } catch {
        // ignore
      }
    }
  },
  addListener: (fn: (entry: LogEntry | null) => void) => {
    listeners.push(fn)
  },
  removeListener: (fn: (entry: LogEntry | null) => void) => {
    const idx = listeners.indexOf(fn)
    if (idx >= 0) listeners.splice(idx, 1)
  },
}

// Expose globally for API client usage
;(window as unknown as Record<string, unknown>).DebugLogger = DebugLogger

export default function DebugPanel() {
  const [open, setOpen] = useState(false)
  const [filterLevel, setFilterLevel] = useState<LogLevel>('ALL')
  const [displayLogs, setDisplayLogs] = useState<LogEntry[]>([])
  const logListRef = useRef<HTMLDivElement>(null)
  const [, setUpdateTick] = useState(0)

  const errorCount = logs.filter((l) => l.level === 'ERROR').length

  const refreshLogs = useCallback(() => {
    setDisplayLogs(DebugLogger.getLogs(filterLevel))
    setUpdateTick((t) => t + 1)
  }, [filterLevel])

  useEffect(() => {
    const handler = (entry: LogEntry | null) => {
      if (entry === null) {
        setDisplayLogs([])
      } else {
        refreshLogs()
      }
    }
    DebugLogger.addListener(handler)
    return () => DebugLogger.removeListener(handler)
  }, [refreshLogs])

  useEffect(() => {
    if (open) {
      refreshLogs()
    }
  }, [open, filterLevel, refreshLogs])

  useEffect(() => {
    if (logListRef.current) {
      logListRef.current.scrollTop = logListRef.current.scrollHeight
    }
  }, [displayLogs])

  const filteredLogs = filterLevel === 'ALL' ? displayLogs : displayLogs.filter((l) => l.level === filterLevel)

  return (
    <>
      {open && (
        <div className="debug-panel">
          <div className="debug-header">
            <span className="debug-title">Debug Logs</span>
            <div className="debug-actions">
              <select
                value={filterLevel}
                onChange={(e) => setFilterLevel(e.target.value as LogLevel)}
                style={{
                  background: '#0f3460',
                  color: '#e0e0e0',
                  border: '1px solid #533483',
                  borderRadius: 4,
                  padding: '2px 6px',
                  fontSize: 11,
                }}
              >
                <option value="ALL">ALL ({logs.length})</option>
                <option value="DEBUG">DEBUG</option>
                <option value="INFO">INFO</option>
                <option value="WARN">WARN</option>
                <option value="ERROR">ERROR ({errorCount})</option>
              </select>
              <button
                onClick={() => {
                  DebugLogger.clear()
                  setDisplayLogs([])
                }}
                style={{
                  background: '#0f3460',
                  color: '#e0e0e0',
                  border: '1px solid #533483',
                  borderRadius: 4,
                  padding: '2px 8px',
                  cursor: 'pointer',
                  fontSize: 11,
                }}
              >
                Clear
              </button>
              <button
                onClick={() => setOpen(false)}
                style={{
                  background: '#e94560',
                  border: '1px solid #e94560',
                  borderRadius: 4,
                  padding: '2px 8px',
                  cursor: 'pointer',
                  fontSize: 11,
                  color: 'white',
                }}
              >
                ×
              </button>
            </div>
          </div>
          <div ref={logListRef} className="debug-logs">
            {filteredLogs.length === 0 ? (
              <div className="debug-empty">No logs</div>
            ) : (
              filteredLogs.map((l) => (
                <div key={l.id} className={`debug-log-item log-${l.level.toLowerCase()}`}>
                  <span className="log-time">{l.time}</span>
                  <span className="log-level">{l.level}</span>
                  <span className="log-msg">{l.message}</span>
                </div>
              ))
            )}
          </div>
        </div>
      )}
      {!open && (
        <button className="debug-fab" onClick={() => setOpen(true)}>
          <span>📋</span>
          {errorCount > 0 && <span className="debug-fab-badge">{errorCount}</span>}
        </button>
      )}
    </>
  )
}
