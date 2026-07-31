import { useState, useEffect, useCallback, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { NavBar, SpinLoading, Toast, ActionSheet, Badge } from 'antd-mobile'
import api, { type SystemInfo } from '../api/client'

type FetchMode = 'auto' | 'proxy' | 'cache'
type ZoomMode = 'contain' | 'width100' | 'original'

interface PageInfo {
  page: number
  state: 'downloaded' | 'cached' | 'pending'
  pToken: string
}

interface PageListResponse {
  gid: number
  pages: number
  pageList: PageInfo[]
}

const MODE_LABELS: Record<FetchMode, string> = {
  auto: '智能',
  proxy: '实时代理',
  cache: '缓存优先',
}

const ZOOM_LABELS: Record<ZoomMode, string> = {
  contain: '适应',
  width100: '宽度',
  original: '原始',
}

export default function GalleryViewer() {
  const { gid } = useParams<{ gid: string }>()
  const navigate = useNavigate()
  const gidNumber = gid ? parseInt(gid) : 0

  const [totalPages, setTotalPages] = useState(0)
  const [pageList, setPageList] = useState<PageInfo[]>([])
  const [currentPage, setCurrentPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [imageUrl, setImageUrl] = useState('')
  const [autoPlay, setAutoPlay] = useState(false)
  const [showControls, setShowControls] = useState(true)
  const [mode, setMode] = useState<FetchMode>(() => {
    const saved = localStorage.getItem('galleryViewerMode')
    return (saved as FetchMode) || 'auto'
  })
  const [zoom, setZoom] = useState<ZoomMode>(() => {
    const saved = localStorage.getItem('galleryViewerZoom')
    return (saved as ZoomMode) || 'contain'
  })
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [systemInfo, setSystemInfo] = useState<SystemInfo | null>(null)

  const autoPlayRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const touchStartRef = useRef<{ x: number; y: number } | null>(null)
  const controlsTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const preloadRef = useRef<HTMLImageElement | null>(null)
  const retryCountRef = useRef(0)

  // 加载页面列表 + 系统信息
  useEffect(() => {
    if (!gidNumber) return
    setLoading(true)
    setErrorMsg(null)
    Promise.all([
      api.getPages(gidNumber).catch(() => null),
      api.getSystemInfo().catch(() => null),
    ])
      .then(([pagesData, sysInfo]) => {
        if (sysInfo) setSystemInfo(sysInfo)
        if (pagesData) {
          const data = pagesData as PageListResponse
          setTotalPages(data.pages || 0)
          setPageList(data.pageList || [])
          if (data.pages > 0) {
            loadImage(gidNumber, 1)
          } else {
            setLoading(false)
          }
        } else {
          Toast.show({ content: '加载页面列表失败', icon: 'fail' })
          setLoading(false)
        }
      })
  }, [gidNumber])

  // 加载指定页面图片
  const loadImage = useCallback(
    (galleryId: number, page: number) => {
      setLoading(true)
      setErrorMsg(null)
      retryCountRef.current = 0

      // 根据当前页 state 与 mode 决定 fetch 模式
      const info = pageList[page - 1]
      const effective = resolveFetchMode(mode, info)

      const url = api.getPageUrl(galleryId, page, effective)
      preloadImage(url, page)
    },
    [mode, pageList]
  )

  // 实际加载（含重试 + 自动切换 proxy 模式）
  const preloadImage = useCallback(
    (url: string, page: number) => {
      const img = new Image()
      img.onload = () => {
        setImageUrl(url)
        setLoading(false)
        retryCountRef.current = 0
        // 预加载下一页
        if (page < totalPages) {
          const nextInfo = pageList[page] // page is 1-indexed, next index is page
          const nextMode = resolveFetchMode(mode, nextInfo)
          const nextUrl = api.getPageUrl(gidNumber, page + 1, nextMode)
          const nextImg = new Image()
          nextImg.src = nextUrl
          preloadRef.current = nextImg
        }
      }
      img.onerror = () => {
        if (retryCountRef.current < 1 && mode === 'auto') {
          retryCountRef.current += 1
          // 自动模式首试失败 → 切换到 proxy 再试
          const proxyUrl = api.getPageUrl(gidNumber, page, 'proxy')
          preloadImage(proxyUrl, page)
        } else {
          setLoading(false)
          setErrorMsg(`第 ${page} 页加载失败`)
          Toast.show({ content: `第 ${page} 页加载失败`, position: 'center' })
        }
      }
      img.src = url
    },
    [gidNumber, mode, pageList, totalPages]
  )

  // 模式解析：auto 模式下根据 pageList 决定
  const resolveFetchMode = useCallback(
    (m: FetchMode, info?: PageInfo): 'local' | 'proxy' | 'cache' => {
      if (m === 'proxy') return 'proxy'
      if (m === 'cache') return 'local'
      // auto
      if (info?.state === 'downloaded' || info?.state === 'cached') return 'local'
      // pending → 尝试 proxy（自动下载）
      return 'proxy'
    },
    []
  )

  // 页面切换
  const goToPage = useCallback(
    (page: number) => {
      if (!gidNumber) return
      if (page < 1 || page > totalPages) return
      setCurrentPage(page)
      loadImage(gidNumber, page)
    },
    [gidNumber, totalPages, loadImage]
  )

  // 上一页
  const prevPage = useCallback(() => {
    if (currentPage > 1) goToPage(currentPage - 1)
  }, [currentPage, goToPage])

  // 下一页
  const nextPage = useCallback(() => {
    if (currentPage < totalPages) goToPage(currentPage + 1)
  }, [currentPage, totalPages, goToPage])

  // 自动播放
  const startAutoPlay = useCallback(() => {
    setAutoPlay(true)
    autoPlayRef.current = setInterval(() => {
      setCurrentPage((prev) => {
        if (prev >= totalPages) {
          setAutoPlay(false)
          if (autoPlayRef.current) clearInterval(autoPlayRef.current)
          return prev
        }
        const next = prev + 1
        if (gidNumber) loadImage(gidNumber, next)
        return next
      })
    }, 3000)
  }, [gidNumber, totalPages, loadImage])

  const stopAutoPlay = useCallback(() => {
    setAutoPlay(false)
    if (autoPlayRef.current) {
      clearInterval(autoPlayRef.current)
      autoPlayRef.current = null
    }
  }, [])

  const toggleAutoPlay = useCallback(() => {
    if (autoPlay) stopAutoPlay()
    else startAutoPlay()
  }, [autoPlay, startAutoPlay, stopAutoPlay])

  // 切换模式
  const changeMode = useCallback(
    (m: FetchMode) => {
      setMode(m)
      localStorage.setItem('galleryViewerMode', m)
      if (gidNumber && currentPage > 0) loadImage(gidNumber, currentPage)
    },
    [gidNumber, currentPage, loadImage]
  )

  // 切换缩放
  const changeZoom = useCallback((z: ZoomMode) => {
    setZoom(z)
    localStorage.setItem('galleryViewerZoom', z)
  }, [])

  // 自动隐藏控制栏
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

  // 键盘事件
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      resetControlsTimer()
      switch (e.key) {
        case 'ArrowLeft':
        case 'ArrowUp':
          e.preventDefault()
          prevPage()
          break
        case 'ArrowRight':
        case 'ArrowDown':
        case ' ':
          e.preventDefault()
          nextPage()
          break
        case 'Escape':
          navigate(-1)
          break
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [prevPage, nextPage, navigate, resetControlsTimer])

  // 触摸手势
  const handleTouchStart = (e: React.TouchEvent) => {
    touchStartRef.current = {
      x: e.touches[0].clientX,
      y: e.touches[0].clientY,
    }
    resetControlsTimer()
  }

  const handleTouchEnd = (e: React.TouchEvent) => {
    if (!touchStartRef.current) return
    const dx = e.changedTouches[0].clientX - touchStartRef.current.x
    const dy = e.changedTouches[0].clientY - touchStartRef.current.y

    // 水平滑动距离大于50px且大于垂直距离
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) {
      if (dx > 0) {
        prevPage()
      } else {
        nextPage()
      }
    }
    touchStartRef.current = null
  }

  const handleImageLoad = () => {
    setLoading(false)
    setErrorMsg(null)
  }

  const handleImageError = () => {
    setLoading(false)
    setErrorMsg(`第 ${currentPage} 页加载失败`)
  }

  const handleScreenTap = (e: React.MouseEvent) => {
    const rect = (e.target as HTMLElement).getBoundingClientRect()
    const x = e.clientX - rect.left
    const w = rect.width
    const third = w / 3

    if (x < third) prevPage()
    else if (x > third * 2) nextPage()
    else setShowControls(!showControls)
  }

  // 清理定时器
  useEffect(() => {
    return () => {
      stopAutoPlay()
      if (controlsTimerRef.current) clearTimeout(controlsTimerRef.current)
    }
  }, [stopAutoPlay])

  // 进度条点击跳转
  const handleProgressJump = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const p = parseInt(e.target.value)
      if (!isNaN(p)) goToPage(p)
    },
    [goToPage]
  )

  // 模式切换 ActionSheet
  const showModeSheet = useCallback(() => {
    const actions: Array<{ key: string; text: string; onClick?: () => void }> = (Object.keys(MODE_LABELS) as FetchMode[]).map((m) => ({
      key: m,
      text: MODE_LABELS[m] + (m === mode ? ' (当前)' : ''),
      onClick: () => changeMode(m),
    }))
    actions.push({ key: '_cancel', text: '取消' })
    ActionSheet.show({ actions })
  }, [mode, changeMode])

  // 缩放切换
  const showZoomSheet = useCallback(() => {
    const actions: Array<{ key: string; text: string; onClick?: () => void }> = (Object.keys(ZOOM_LABELS) as ZoomMode[]).map((z) => ({
      key: z,
      text: ZOOM_LABELS[z] + (z === zoom ? ' (当前)' : ''),
      onClick: () => changeZoom(z),
    }))
    actions.push({ key: '_cancel', text: '取消' })
    ActionSheet.show({ actions })
  }, [zoom, changeZoom])

  // 计算缩放样式
  const imageStyle: React.CSSProperties = {
    transition: 'opacity 0.2s',
    opacity: loading ? 0.3 : 1,
    objectFit: zoom === 'contain' ? 'contain' : 'cover',
    maxWidth: zoom === 'width100' ? '100%' : zoom === 'original' ? 'none' : '100%',
    maxHeight: zoom === 'contain' ? '100%' : zoom === 'original' ? 'none' : '100%',
    width: zoom === 'width100' ? '100%' : 'auto',
  }

  // 当前页信息
  const currentPageInfo = pageList[currentPage - 1]
  const isCurrentPending = currentPageInfo?.state === 'pending'

  return (
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        background: '#000',
        zIndex: 1000,
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      {/* 顶部导航栏 */}
      <div
        style={{
          position: 'absolute',
          top: 0,
          left: 0,
          right: 0,
          zIndex: 10,
          transition: 'opacity 0.3s',
          opacity: showControls ? 1 : 0,
          pointerEvents: showControls ? 'auto' : 'none',
        }}
      >
        <NavBar
          onBack={() => navigate(-1)}
          style={{
            '--height': '48px',
            background: 'rgba(0,0,0,0.6)',
            color: '#fff',
          }}
          right={
            <div style={{ display: 'flex', gap: 8, color: '#fff' }}>
              <span
                onClick={showModeSheet}
                style={{ fontSize: 12, padding: '4px 8px', background: 'rgba(255,255,255,0.15)', borderRadius: 4, cursor: 'pointer' }}
              >
                {MODE_LABELS[mode]}
              </span>
              <span
                onClick={showZoomSheet}
                style={{ fontSize: 12, padding: '4px 8px', background: 'rgba(255,255,255,0.15)', borderRadius: 4, cursor: 'pointer' }}
              >
                {ZOOM_LABELS[zoom]}
              </span>
            </div>
          }
        >
          <span style={{ color: '#fff' }}>
            {currentPage} / {totalPages}
            {isCurrentPending && (
              <Badge
                content="待下载"
                style={{ '--right': '-50px', background: '#ff9800', marginLeft: 8 }}
              />
            )}
          </span>
        </NavBar>
      </div>

      {/* 图片区域 */}
      <div
        style={{
          flex: 1,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          overflow: 'hidden',
          position: 'relative',
          cursor: 'pointer',
          userSelect: 'none',
          touchAction: 'manipulation',
        }}
        onClick={handleScreenTap}
        onTouchStart={handleTouchStart}
        onTouchEnd={handleTouchEnd}
      >
        {loading && (
          <div style={{ position: 'absolute', zIndex: 5 }}>
            <SpinLoading style={{ '--size': '48px', '--color': '#fff' }} />
          </div>
        )}
        {errorMsg && !loading && (
          <div
            style={{
              position: 'absolute',
              zIndex: 5,
              color: 'rgba(255,255,255,0.85)',
              textAlign: 'center',
              padding: 16,
            }}
          >
            <div style={{ fontSize: 16, marginBottom: 8 }}>{errorMsg}</div>
            <div style={{ fontSize: 12, opacity: 0.7 }}>
              {mode === 'auto'
                ? '已自动尝试实时代理下载，请重试或检查网络'
                : '可在顶部切换模式后重试'}
            </div>
          </div>
        )}
        {imageUrl && !errorMsg && (
          <img
            src={imageUrl}
            alt={`Page ${currentPage}`}
            onLoad={handleImageLoad}
            onError={handleImageError}
            style={imageStyle}
            draggable={false}
          />
        )}
      </div>

      {/* 底部控制栏 */}
      <div
        style={{
          position: 'absolute',
          bottom: 0,
          left: 0,
          right: 0,
          zIndex: 10,
          transition: 'opacity 0.3s',
          opacity: showControls ? 1 : 0,
          pointerEvents: showControls ? 'auto' : 'none',
          background: 'rgba(0,0,0,0.6)',
          padding: '12px 16px',
          paddingBottom: 'max(12px, env(safe-area-inset-bottom))',
        }}
      >
        {/* 进度条 */}
        <div style={{ marginBottom: 12 }}>
          <input
            type="range"
            min={1}
            max={totalPages || 1}
            value={currentPage}
            onChange={handleProgressJump}
            style={{
              width: '100%',
              height: 4,
              appearance: 'none',
              background: `linear-gradient(to right, #2196F3 0%, #2196F3 ${((currentPage - 1) / Math.max(totalPages - 1, 1)) * 100}%, rgba(255,255,255,0.3) ${((currentPage - 1) / Math.max(totalPages - 1, 1)) * 100}%, rgba(255,255,255,0.3) 100%)`,
              borderRadius: 2,
              outline: 'none',
              cursor: 'pointer',
            }}
          />
        </div>

        {/* 控制按钮 */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 16, flexWrap: 'wrap' }}>
          <button
            onClick={prevPage}
            disabled={currentPage <= 1}
            style={{
              background: 'rgba(255,255,255,0.15)',
              border: 'none',
              borderRadius: 8,
              padding: '10px 18px',
              color: currentPage <= 1 ? 'rgba(255,255,255,0.3)' : '#fff',
              fontSize: 14,
              cursor: currentPage <= 1 ? 'not-allowed' : 'pointer',
            }}
          >
            ◀ 上一页
          </button>

          <button
            onClick={toggleAutoPlay}
            style={{
              background: autoPlay ? 'rgba(244,67,54,0.8)' : 'rgba(255,255,255,0.15)',
              border: 'none',
              borderRadius: 8,
              padding: '10px 18px',
              color: '#fff',
              fontSize: 14,
              cursor: 'pointer',
            }}
          >
            {autoPlay ? '⏹ 停止' : '▶ 自动'}
          </button>

          <button
            onClick={nextPage}
            disabled={currentPage >= totalPages}
            style={{
              background: 'rgba(255,255,255,0.15)',
              border: 'none',
              borderRadius: 8,
              padding: '10px 18px',
              color: currentPage >= totalPages ? 'rgba(255,255,255,0.3)' : '#fff',
              fontSize: 14,
              cursor: currentPage >= totalPages ? 'not-allowed' : 'pointer',
            }}
          >
            下一页 ▶
          </button>
        </div>

        {/* 页码信息 */}
        <div style={{ textAlign: 'center', marginTop: 8, color: 'rgba(255,255,255,0.6)', fontSize: 12 }}>
          {currentPageInfo ? (
            <>
              状态：{currentPageInfo.state === 'downloaded' ? '已下载' : currentPageInfo.state === 'cached' ? '缓存' : '待下载'} ·{' '}
              共 {totalPages} 页
            </>
          ) : (
            <>第 {currentPage} 页 / 共 {totalPages} 页</>
          )}
        </div>
      </div>
    </div>
  )
}