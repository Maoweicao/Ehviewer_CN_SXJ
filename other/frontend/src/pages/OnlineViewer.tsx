import { useState, useEffect, useCallback, useRef } from 'react'
import { useParams, useSearchParams, useNavigate } from 'react-router-dom'
import { NavBar, SpinLoading, Toast } from 'antd-mobile'
import api from '../api/client'

/**
 * 在线阅读器（协议 §5.25.4）
 *
 * 图片经由主机源站代理（mode=proxy），逐页懒加载 + 预读下一页。
 */
export default function OnlineViewer() {
  const { gid } = useParams<{ gid: string }>()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const gidNumber = gid ? parseInt(gid) : 0
  const token = searchParams.get('token') || ''

  const [totalPages, setTotalPages] = useState(0)
  const [currentPage, setCurrentPage] = useState(1)
  const [imageUrl, setImageUrl] = useState('')
  const [loading, setLoading] = useState(true)
  const [showControls, setShowControls] = useState(true)

  const touchStartRef = useRef<{ x: number; y: number } | null>(null)
  const controlsTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const preloadRef = useRef<HTMLImageElement | null>(null)

  // 加载页列表
  useEffect(() => {
    if (!gidNumber || !token) {
      setLoading(false)
      return
    }
    setLoading(true)
    api.onlinePages(gidNumber, token)
      .then((res) => {
        setTotalPages(res.pages || 0)
        if ((res.pages || 0) > 0) {
          loadImage(1)
        } else {
          setLoading(false)
        }
      })
      .catch((e) => {
        Toast.show({ content: e?.message || '加载失败', icon: 'fail' })
        setLoading(false)
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [gidNumber, token])

  const preload = useCallback((page: number) => {
    if (!gidNumber || !token || page > totalPages) return
    const img = new Image()
    img.src = api.getOnlinePageUrl(gidNumber, page, token, 'proxy')
    preloadRef.current = img
  }, [gidNumber, token, totalPages])

  const loadImage = useCallback((page: number) => {
    if (!gidNumber || !token) return
    setLoading(true)
    setImageUrl(api.getOnlinePageUrl(gidNumber, page, token, 'proxy'))
    preload(page + 1)
  }, [gidNumber, token, preload])

  const goToPage = useCallback((page: number) => {
    if (page < 1 || (totalPages > 0 && page > totalPages)) return
    setCurrentPage(page)
    loadImage(page)
  }, [totalPages, loadImage])

  const prevPage = useCallback(() => { if (currentPage > 1) goToPage(currentPage - 1) }, [currentPage, goToPage])
  const nextPage = useCallback(() => { if (currentPage < totalPages) goToPage(currentPage + 1) }, [currentPage, totalPages, goToPage])

  const resetControlsTimer = useCallback(() => {
    setShowControls(true)
    if (controlsTimerRef.current) clearTimeout(controlsTimerRef.current)
    controlsTimerRef.current = setTimeout(() => setShowControls(false), 4000)
  }, [])

  useEffect(() => {
    resetControlsTimer()
    return () => {
      if (controlsTimerRef.current) clearTimeout(controlsTimerRef.current)
    }
  }, [resetControlsTimer])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      resetControlsTimer()
      if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); prevPage() }
      else if (e.key === 'ArrowRight' || e.key === 'ArrowDown' || e.key === ' ') { e.preventDefault(); nextPage() }
      else if (e.key === 'Escape') navigate(-1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [prevPage, nextPage, navigate, resetControlsTimer])

  const handleTouchStart = (e: React.TouchEvent) => {
    touchStartRef.current = { x: e.touches[0].clientX, y: e.touches[0].clientY }
    resetControlsTimer()
  }
  const handleTouchEnd = (e: React.TouchEvent) => {
    if (!touchStartRef.current) return
    const dx = e.changedTouches[0].clientX - touchStartRef.current.x
    const dy = e.changedTouches[0].clientY - touchStartRef.current.y
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) {
      if (dx > 0) prevPage(); else nextPage()
    } else {
      setShowControls((v) => !v)
    }
    touchStartRef.current = null
  }

  const handleScreenTap = (e: React.MouseEvent) => {
    const rect = (e.target as HTMLElement).getBoundingClientRect()
    const third = rect.width / 3
    const x = e.clientX - rect.left
    if (x < third) prevPage()
    else if (x > third * 2) nextPage()
    else setShowControls((v) => !v)
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: '#000', zIndex: 1000, display: 'flex', flexDirection: 'column' }}>
      <div style={{ position: 'absolute', top: 0, left: 0, right: 0, zIndex: 10, transition: 'opacity 0.3s', opacity: showControls ? 1 : 0, pointerEvents: showControls ? 'auto' : 'none' }}>
        <NavBar onBack={() => navigate(-1)} style={{ '--height': '48px', background: 'rgba(0,0,0,0.6)', color: '#fff' }}>
          <span style={{ color: '#fff' }}>{currentPage} / {totalPages}</span>
        </NavBar>
      </div>

      <div
        style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', position: 'relative', cursor: 'pointer', userSelect: 'none' }}
        onClick={handleScreenTap}
        onTouchStart={handleTouchStart}
        onTouchEnd={handleTouchEnd}
      >
        {loading && (
          <div style={{ position: 'absolute', zIndex: 5 }}>
            <SpinLoading style={{ '--size': '48px', '--color': '#fff' }} />
          </div>
        )}
        {imageUrl && (
          <img
            src={imageUrl}
            alt={`Page ${currentPage}`}
            onLoad={() => setLoading(false)}
            onError={() => { setLoading(false); Toast.show({ content: `第 ${currentPage} 页加载失败`, position: 'center' }) }}
            style={{ maxWidth: '100%', maxHeight: '100%', opacity: loading ? 0.3 : 1, transition: 'opacity 0.2s' }}
            draggable={false}
          />
        )}
      </div>

      <div style={{ position: 'absolute', bottom: 0, left: 0, right: 0, zIndex: 10, transition: 'opacity 0.3s', opacity: showControls ? 1 : 0, pointerEvents: showControls ? 'auto' : 'none', background: 'rgba(0,0,0,0.6)', padding: '12px 16px', paddingBottom: 'max(12px, env(safe-area-inset-bottom))' }}>
        <input
          type="range"
          min={1}
          max={totalPages || 1}
          value={currentPage}
          onChange={(e) => goToPage(parseInt(e.target.value))}
          style={{ width: '100%', height: 4, appearance: 'none', background: 'rgba(255,255,255,0.3)', borderRadius: 2, outline: 'none', cursor: 'pointer' }}
        />
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 16, marginTop: 12 }}>
          <button onClick={prevPage} disabled={currentPage <= 1} style={btnStyle(currentPage <= 1)}>◀ 上一页</button>
          <button onClick={nextPage} disabled={currentPage >= totalPages} style={btnStyle(currentPage >= totalPages)}>下一页 ▶</button>
        </div>
      </div>
    </div>
  )
}

function btnStyle(disabled: boolean): React.CSSProperties {
  return {
    background: 'rgba(255,255,255,0.15)',
    border: 'none',
    borderRadius: 8,
    padding: '10px 18px',
    color: disabled ? 'rgba(255,255,255,0.3)' : '#fff',
    fontSize: 14,
    cursor: disabled ? 'not-allowed' : 'pointer',
  }
}
