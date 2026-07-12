import { useState, useEffect, useCallback, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { NavBar, SpinLoading, Toast } from 'antd-mobile'
import api from '../api/client'

export default function GalleryViewer() {
  const { gid } = useParams<{ gid: string }>()
  const navigate = useNavigate()
  const [totalPages, setTotalPages] = useState(0)
  const [currentPage, setCurrentPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [imageUrl, setImageUrl] = useState('')
  const [autoPlay, setAutoPlay] = useState(false)
  const [showControls, setShowControls] = useState(true)
  const autoPlayRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const touchStartRef = useRef<{ x: number; y: number } | null>(null)
  const controlsTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 加载页面列表
  useEffect(() => {
    if (!gid) return
    const g = parseInt(gid)
    api.getPages(g).then((data) => {
      setTotalPages(data.pages || 0)
      if (data.pages > 0) {
        loadImage(g, 1)
      }
    }).catch(() => {
      Toast.show({ content: '加载页面列表失败', icon: 'fail' })
    })
  }, [gid])

  // 加载指定页面图片
  const loadImage = useCallback((galleryId: number, page: number) => {
    setLoading(true)
    const url = api.getPageUrl(galleryId, page)
    setImageUrl(url)
  }, [])

  // 页面切换
  const goToPage = useCallback((page: number) => {
    if (!gid) return
    const g = parseInt(gid)
    if (page < 1 || page > totalPages) return
    setCurrentPage(page)
    loadImage(g, page)
  }, [gid, totalPages, loadImage])

  // 上一页
  const prevPage = useCallback(() => {
    if (currentPage > 1) {
      goToPage(currentPage - 1)
    }
  }, [currentPage, goToPage])

  // 下一页
  const nextPage = useCallback(() => {
    if (currentPage < totalPages) {
      goToPage(currentPage + 1)
    }
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
        if (gid) loadImage(parseInt(gid), next)
        return next
      })
    }, 3000) // 3秒切换
  }, [gid, totalPages, loadImage])

  const stopAutoPlay = useCallback(() => {
    setAutoPlay(false)
    if (autoPlayRef.current) {
      clearInterval(autoPlayRef.current)
      autoPlayRef.current = null
    }
  }, [])

  const toggleAutoPlay = useCallback(() => {
    if (autoPlay) {
      stopAutoPlay()
    } else {
      startAutoPlay()
    }
  }, [autoPlay, startAutoPlay, stopAutoPlay])

  // 自动隐藏控制栏
  const resetControlsTimer = useCallback(() => {
    setShowControls(true)
    if (controlsTimerRef.current) clearTimeout(controlsTimerRef.current)
    controlsTimerRef.current = setTimeout(() => {
      setShowControls(false)
    }, 4000)
  }, [])

  useEffect(() => {
    resetControlsTimer()
    return () => {
      if (controlsTimerRef.current) clearTimeout(controlsTimerRef.current)
    }
  }, [])

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

  // 图片加载完成
  const handleImageLoad = () => {
    setLoading(false)
  }

  // 图片加载失败
  const handleImageError = () => {
    setLoading(false)
    Toast.show({ content: '图片加载失败', position: 'center' })
  }

  // 点击屏幕中央切换控制栏
  const handleScreenTap = (e: React.MouseEvent) => {
    const rect = (e.target as HTMLElement).getBoundingClientRect()
    const x = e.clientX - rect.left
    const w = rect.width
    const third = w / 3

    if (x < third) {
      prevPage()
    } else if (x > third * 2) {
      nextPage()
    } else {
      setShowControls(!showControls)
    }
  }

  // 清理定时器
  useEffect(() => {
    return () => {
      stopAutoPlay()
      if (controlsTimerRef.current) clearTimeout(controlsTimerRef.current)
    }
  }, [])

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
        >
          <span style={{ color: '#fff' }}>
            {currentPage} / {totalPages}
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
        {imageUrl && (
          <img
            src={imageUrl}
            alt={`Page ${currentPage}`}
            onLoad={handleImageLoad}
            onError={handleImageError}
            style={{
              maxWidth: '100%',
              maxHeight: '100%',
              objectFit: 'contain',
              transition: 'opacity 0.2s',
              opacity: loading ? 0.3 : 1,
            }}
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
            onChange={(e) => goToPage(parseInt(e.target.value))}
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
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 24 }}>
          <button
            onClick={prevPage}
            disabled={currentPage <= 1}
            style={{
              background: 'rgba(255,255,255,0.15)',
              border: 'none',
              borderRadius: 8,
              padding: '10px 20px',
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
              padding: '10px 20px',
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
              padding: '10px 20px',
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
          第 {currentPage} 页 / 共 {totalPages} 页 · 左右滑动切换 · 点击左右两侧切换
        </div>
      </div>
    </div>
  )
}
