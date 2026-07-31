import { useState, useEffect, useCallback, useRef } from 'react'
import {
  NavBar,
  SearchBar,
  Tabs,
  Picker,
  Button,
  Toast,
  Empty,
  SpinLoading,
} from 'antd-mobile'
import api, {
  type Gallery,
  type Label,
  PAGE_SIZES,
} from '../api/client'
import Pagination from '../components/Pagination'
import FilterPanel, { type FilterState, type SortCondition } from '../components/FilterPanel'
import GalleryCard from '../components/GalleryCard'

function buildSortExpr(conditions: SortCondition[]): string {
  return conditions.map((c) => `${c.field}:${c.order}`).join(',')
}

function buildFilterExpr(f: FilterState): string {
  const parts: string[] = []
  if (f.categories.length > 0) {
    parts.push(`category in [${f.categories.map((c) => `"${c}"`).join(',')}]`)
  }
  if (f.states.length > 0) {
    parts.push(`state in [${f.states.join(',')}]`)
  }
  if (f.minRating > 0) {
    parts.push(`rating>=${f.minRating}`)
  }
  if (f.minSizeMB > 0) {
    parts.push(`size>=${f.minSizeMB * 1024 * 1024}`)
  }
  return parts.join(';')
}

export default function GalleryList() {
  const [loading, setLoading] = useState(false)
  const [galleries, setGalleries] = useState<Gallery[]>([])
  const [labels, setLabels] = useState<Label[]>([])
  const [search, setSearch] = useState('')
  const [tab, setTab] = useState<'all' | 'favorites'>('all')
  const [label, setLabel] = useState<string | null>(null)
  const [pickerVisible, setPickerVisible] = useState(false)

  const [pageNum, setPageNum] = useState(1)
  const [total, setTotal] = useState(0)
  const [pageSize, setPageSize] = useState(() => {
    const saved = localStorage.getItem('galleryPageSize')
    return saved ? Number(saved) : 30
  })
  const [viewMode, setViewMode] = useState<'pagination' | 'infinite'>(() => {
    return (localStorage.getItem('galleryViewMode') as 'pagination' | 'infinite') || 'pagination'
  })

  const [currentPage, setCurrentPage] = useState(1)
  const [hasMore, setHasMore] = useState(true)

  const [sortConditions, setSortConditions] = useState<SortCondition[]>([
    { field: 'createdDate', order: 'desc' },
  ])
  const [filters, setFilters] = useState<FilterState>({
    categories: [],
    states: [],
    minRating: 0,
    minSizeMB: 0,
  })
  const [filterPopup, setFilterPopup] = useState(false)

  const [showBackToTop, setShowBackToTop] = useState(false)
  const sentinelRef = useRef<HTMLDivElement>(null)
  const observerRef = useRef<IntersectionObserver | null>(null)

  const loadLabels = useCallback(async () => {
    try {
      const data = await api.getLabels()
      setLabels(data.labels || [])
    } catch (e) {
      console.error('loadLabels error:', e)
    }
  }, [])

  const loadData = useCallback(
    async (page: number, keyword: string, currentTab: 'all' | 'favorites', currentLabel: string | null) => {
      setLoading(true)
      try {
        const sortExpr = buildSortExpr(sortConditions)
        const filterExpr = buildFilterExpr(filters)
        let data
        if (currentTab === 'favorites') {
          data = await api.getFavorites(page, pageSize, keyword || null, sortExpr || null, filterExpr || null)
        } else {
          data = await api.getGalleries(page, pageSize, currentLabel, keyword || null, sortExpr || null, filterExpr || null)
        }
        setGalleries(data.galleries || [])
        setTotal(data.total || 0)
      } catch (e) {
        Toast.show({ content: '加载失败', icon: 'fail' })
        console.error('loadData error:', e)
      } finally {
        setLoading(false)
      }
    },
    [pageSize, sortConditions, filters]
  )

  const loadInfinitePage = useCallback(
    async (page: number, keyword: string, currentTab: 'all' | 'favorites', currentLabel: string | null, append = false) => {
      setLoading(true)
      try {
        const sortExpr = buildSortExpr(sortConditions)
        const filterExpr = buildFilterExpr(filters)
        let data
        if (currentTab === 'favorites') {
          data = await api.getFavorites(page, pageSize, keyword || null, sortExpr || null, filterExpr || null)
        } else {
          data = await api.getGalleries(page, pageSize, currentLabel, keyword || null, sortExpr || null, filterExpr || null)
        }
        const newGalleries = data.galleries || []
        setGalleries((prev) => (append ? [...prev, ...newGalleries] : newGalleries))
        setTotal(data.total || 0)
        setCurrentPage(page)
        setHasMore(page * pageSize < (data.total || 0))
      } catch (e) {
        Toast.show({ content: '加载失败', icon: 'fail' })
        console.error('loadInfinitePage error:', e)
      } finally {
        setLoading(false)
      }
    },
    [pageSize, sortConditions, filters]
  )

  useEffect(() => {
    loadLabels()
  }, [loadLabels])

  useEffect(() => {
    if (viewMode === 'pagination') {
      loadData(pageNum, search, tab, label)
    }
  }, [pageNum, pageSize, viewMode])

  useEffect(() => {
    if (viewMode === 'infinite') {
      loadInfinitePage(1, search, tab, label, false)
    }
  }, [pageSize, viewMode])

  useEffect(() => {
    if (viewMode !== 'infinite') return

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasMore && !loading) {
          loadInfinitePage(currentPage + 1, search, tab, label, true)
        }
      },
      { threshold: 0.1 }
    )

    observerRef.current = observer
    const sentinel = sentinelRef.current
    if (sentinel) observer.observe(sentinel)

    return () => {
      if (sentinel) observer.unobserve(sentinel)
      observer.disconnect()
    }
  }, [viewMode, hasMore, loading, currentPage, search, tab, label, loadInfinitePage])

  useEffect(() => {
    const handleScroll = () => {
      setShowBackToTop(window.scrollY > 400)
    }
    window.addEventListener('scroll', handleScroll, { passive: true })
    return () => window.removeEventListener('scroll', handleScroll)
  }, [])

  const reload = () => {
    if (viewMode === 'pagination') {
      setPageNum(1)
      loadData(1, search, tab, label)
    } else {
      setCurrentPage(1)
      setHasMore(true)
      loadInfinitePage(1, search, tab, label, false)
    }
  }

  const handleSearch = (val: string) => {
    setSearch(val)
    setTimeout(() => reload(), 300)
  }

  const handleTabChange = (key: string) => {
    const t = key === 'favorites' ? 'favorites' : 'all'
    setTab(t)
    if (viewMode === 'pagination') {
      setPageNum(1)
      loadData(1, search, t, t === 'all' ? label : null)
    } else {
      setCurrentPage(1)
      setHasMore(true)
      loadInfinitePage(1, search, t, t === 'all' ? label : null, false)
    }
  }

  const handleLabelChange = (value: string | null) => {
    setLabel(value)
    if (viewMode === 'pagination') {
      setPageNum(1)
      loadData(1, search, tab, value)
    } else {
      setCurrentPage(1)
      setHasMore(true)
      loadInfinitePage(1, search, tab, value, false)
    }
  }

  const handlePageChange = (page: number) => {
    setPageNum(page)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const handlePageSizeChange = (size: number) => {
    setPageSize(size)
    localStorage.setItem('galleryPageSize', String(size))
    if (viewMode === 'pagination') {
      setPageNum(1)
    } else {
      setCurrentPage(1)
      setHasMore(true)
    }
  }

  const handleViewModeChange = (mode: 'pagination' | 'infinite') => {
    setViewMode(mode)
    localStorage.setItem('galleryViewMode', mode)
    setGalleries([])
    if (mode === 'pagination') {
      setPageNum(1)
      loadData(1, search, tab, label)
    } else {
      setCurrentPage(1)
      setHasMore(true)
      loadInfinitePage(1, search, tab, label, false)
    }
  }

  const handleFilterApply = () => {
    setFilterPopup(false)
    reload()
  }

  const handleFilterReset = () => {
    setFilters({ categories: [], states: [], minRating: 0, minSizeMB: 0 })
    setSortConditions([{ field: 'createdDate', order: 'desc' }])
  }

  const handleBackToTop = () => {
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const getThumb = (gid: number) => api.getThumbnailUrl(gid)

  const totalPages = Math.max(1, Math.ceil(total / pageSize))

  const activeFilterCount =
    filters.categories.length +
    filters.states.length +
    (filters.minRating > 0 ? 1 : 0) +
    (filters.minSizeMB > 0 ? 1 : 0)

  const labelOptions = [
    { label: '全部', value: '__all__' },
    ...labels.map((l) => ({ label: `${l.name}(${l.count})`, value: l.name })),
  ]

  return (
    <div>
      <NavBar backArrow={false} style={{ '--height': '48px', background: 'var(--card-bg, #fff)' }}>
        EhViewer Remote
      </NavBar>

      <div className="filter-bar">
        <div className="filter-bar-row1">
          <SearchBar
            placeholder="搜索画廊..."
            value={search}
            onChange={setSearch}
            onSearch={handleSearch}
            onClear={() => handleSearch('')}
            style={{ flex: 1, '--background': 'var(--card-bg, #fff)' }}
          />
          <Button
            size="small"
            fill={activeFilterCount > 0 ? 'solid' : 'outline'}
            color={activeFilterCount > 0 ? 'primary' : 'default'}
            onClick={() => setFilterPopup(true)}
          >
            筛选{activeFilterCount > 0 ? `(${activeFilterCount})` : ''}
          </Button>
        </div>
        <div className="filter-bar-row2">
          <Button size="small" fill="outline" onClick={() => setPickerVisible(true)}>
            {label ? `分类: ${label}` : '全部分类'}
          </Button>
          <Picker
            columns={[labelOptions]}
            visible={pickerVisible}
            value={[label ?? '__all__']}
            onConfirm={(val) => {
              const v = (val && val[0]) as string
              handleLabelChange(v === '__all__' ? null : v)
            }}
            onClose={() => setPickerVisible(false)}
          />
          <div style={{ flex: 1, overflow: 'hidden', whiteSpace: 'nowrap', fontSize: 12, color: 'var(--text-light)' }}>
            排序：
            {sortConditions.map((c) => {
              const f = { title: '标题', rating: '评分', pages: '页数', category: '分类', state: '状态', createdDate: '创建日期', size: '大小', uploader: '上传者' }[c.field] || c.field
              return `${f} ${c.order === 'asc' ? '↑' : '↓'}`
            }).join('，')}
          </div>
        </div>
      </div>

      <Tabs
        activeKey={tab}
        onChange={handleTabChange}
        style={{ '--title-font-size': '13px', background: 'var(--card-bg, #fff)' }}
      >
        <Tabs.Tab title="全部" key="all" />
        <Tabs.Tab title="收藏" key="favorites" />
      </Tabs>

      <div className="page-content">
        {loading && galleries.length === 0 ? (
          <div className="loading-container">
            <SpinLoading style={{ '--size': '48px' }} />
          </div>
        ) : !loading && galleries.length === 0 ? (
          <Empty description="暂无画廊数据" style={{ padding: '60px 0' }} />
        ) : (
          <>
            <div className="gallery-grid">
              {galleries.map((g) => (
                <GalleryCard key={g.gid} gallery={g} getThumbUrl={getThumb} />
              ))}
            </div>

            {viewMode === 'infinite' && (
              <>
                <div ref={sentinelRef} className="infinite-sentinel" />
                {loading && (
                  <div className="infinite-loading">
                    <SpinLoading style={{ '--size': '24px' }} />
                    <span>加载中...</span>
                  </div>
                )}
                {!hasMore && galleries.length > 0 && (
                  <div className="infinite-end">── 已显示全部 {total} 个画廊 ──</div>
                )}
              </>
            )}

            {viewMode === 'pagination' && (
              <Pagination
                page={pageNum}
                totalPages={totalPages}
                total={total}
                pageSize={pageSize}
                viewMode={viewMode}
                onPageChange={handlePageChange}
                onPageSizeChange={handlePageSizeChange}
                onViewModeChange={handleViewModeChange}
              />
            )}

            {viewMode === 'infinite' && (
              <div className="pagination-container" style={{ boxShadow: 'none', padding: '8px 0' }}>
                <div className="pagination-header">
                  <span className="pagination-total">共 {total} 个画廊</span>
                  <div className="pagination-mode-switch">
                    <Button
                      size="mini"
                      fill="outline"
                      onClick={() => handleViewModeChange('pagination')}
                    >
                      分页
                    </Button>
                    <Button
                      size="mini"
                      fill="solid"
                      onClick={() => handleViewModeChange('infinite')}
                    >
                      无限
                    </Button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}
      </div>

      <FilterPanel
        visible={filterPopup}
        onClose={() => setFilterPopup(false)}
        filters={filters}
        sortConditions={sortConditions}
        onFiltersChange={setFilters}
        onSortConditionsChange={setSortConditions}
        onApply={handleFilterApply}
        onReset={handleFilterReset}
      />

      <button
        className={`back-to-top ${showBackToTop ? 'visible' : ''}`}
        onClick={handleBackToTop}
        aria-label="回到顶部"
      >
        ↑
      </button>
    </div>
  )
}
