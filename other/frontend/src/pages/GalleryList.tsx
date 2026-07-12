import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  NavBar,
  SearchBar,
  Tabs,
  Tag,
  Image,
  SpinLoading,
  Empty,
  Button,
  Toast,
} from 'antd-mobile'
import api, { type Gallery, type Label } from '../api/client'

export default function GalleryList() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [galleries, setGalleries] = useState<Gallery[]>([])
  const [labels, setLabels] = useState<Label[]>([])
  const [search, setSearch] = useState('')
  const [activeLabel, setActiveLabel] = useState('all')
  const [pageNum, setPageNum] = useState(1)
  const [total, setTotal] = useState(0)
  const pageSize = 20

  const loadLabels = useCallback(async () => {
    try {
      const data = await api.getLabels()
      setLabels(data.labels || [])
    } catch (e: unknown) {
      console.error('loadLabels error:', e)
    }
  }, [])

  const loadGalleries = useCallback(async (page: number, keyword: string, label: string) => {
    setLoading(true)
    try {
      const lbl = label === 'all' ? undefined : label
      const data = await api.getGalleries(page, pageSize, lbl, keyword || undefined)
      setGalleries(data.galleries || [])
      setTotal(data.total || 0)
    } catch (e: unknown) {
      Toast.show({ content: '加载失败', icon: 'fail' })
      console.error('loadGalleries error:', e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadLabels()
    loadGalleries(1, '', 'all')
  }, [])

  const handleSearch = (val: string) => {
    setSearch(val)
    setPageNum(1)
    loadGalleries(1, val, activeLabel)
  }

  const handleLabelChange = (key: string) => {
    setActiveLabel(key)
    setPageNum(1)
    loadGalleries(1, search, key)
  }

  const handlePageChange = (page: number) => {
    setPageNum(page)
    loadGalleries(page, search, activeLabel)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const getThumb = (gid: number) => api.getThumbnailUrl(gid)

  const formatRating = (r: number) => (r ? `★ ${r.toFixed(1)}` : '')

  const categoryColors: Record<string, string> = {
    Doujinshi: '#e91e63',
    Manga: '#ff9800',
    'Artist CG': '#4caf50',
    'Game CG': '#2196f3',
    'Image Set': '#9c27b0',
    Cosplay: '#795548',
  }

  const totalPages = Math.ceil(total / pageSize)

  return (
    <div>
      <NavBar backArrow={false} style={{ '--height': '48px', background: 'var(--card-bg, #fff)' }}>
        EhViewer Remote
      </NavBar>
      <SearchBar
        placeholder="搜索画廊..."
        value={search}
        onChange={setSearch}
        onSearch={handleSearch}
        onClear={() => handleSearch('')}
        style={{ padding: '8px 12px', '--background': 'var(--card-bg, #fff)' }}
      />
      <Tabs
        activeKey={activeLabel}
        onChange={handleLabelChange}
        style={{ '--title-font-size': '13px', background: 'var(--card-bg, #fff)' }}
      >
        <Tabs.Tab title="全部" key="all" />
        {labels.map((l) => (
          <Tabs.Tab title={`${l.name}(${l.count})`} key={l.name} />
        ))}
      </Tabs>

      <div className="page-content">
        {loading && galleries.length === 0 ? (
          <div className="loading-container">
            <SpinLoading style={{ '--size': '48px' }} />
          </div>
        ) : !loading && galleries.length === 0 ? (
          <Empty
            description="暂无画廊数据"
            style={{ padding: '60px 0' }}
          />
        ) : (
          <>
            <div className="gallery-grid">
              {galleries.map((g) => (
                <div
                  key={g.gid}
                  className="gallery-card"
                  onClick={() => navigate(`/gallery/${g.gid}`)}
                >
                  <Image
                    src={getThumb(g.gid)}
                    lazy
                    fit="cover"
                    width="100%"
                    height={200}
                    style={{ borderRadius: 0 }}
                    placeholder={
                      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: 200, background: '#f5f5f5' }}>
                        <SpinLoading style={{ '--size': '24px' }} />
                      </div>
                    }
                    fallback={
                      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: 200, background: '#f5f5f5', color: '#ccc', fontSize: 30 }}>
                        🖼️
                      </div>
                    }
                  />
                  <div className="gallery-info">
                    <div className="gallery-title">{g.title}</div>
                    <div className="gallery-meta">
                      <Tag
                        color="primary"
                        fill="solid"
                        style={{ '--background-color': categoryColors[g.category] || '#2196f3', '--text-color': '#fff', '--border-color': 'transparent', fontSize: 11 }}
                      >
                        {g.category}
                      </Tag>
                      <Tag color="warning" fill="outline" style={{ fontSize: 11 }}>
                        {formatRating(g.rating)}
                      </Tag>
                    </div>
                    <div className="gallery-pages">{g.pages} 页</div>
                  </div>
                </div>
              ))}
            </div>
            {totalPages > 1 && (
              <div className="pagination-box">
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Button
                    size="small"
                    disabled={pageNum <= 1}
                    onClick={() => handlePageChange(pageNum - 1)}
                  >
                    上一页
                  </Button>
                  <span style={{ fontSize: 14, color: 'var(--text, #333)', minWidth: 80, textAlign: 'center' }}>
                    {pageNum} / {totalPages}
                  </span>
                  <Button
                    size="small"
                    disabled={pageNum >= totalPages}
                    onClick={() => handlePageChange(pageNum + 1)}
                  >
                    下一页
                  </Button>
                </div>
                <div style={{ fontSize: 12, color: 'var(--text-light, #666)', marginTop: 8, textAlign: 'center' }}>
                  共 {total} 个画廊
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
