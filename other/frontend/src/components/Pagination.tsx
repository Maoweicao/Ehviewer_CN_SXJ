import { useState, useEffect } from 'react'
import { Button, Picker } from 'antd-mobile'

interface PaginationProps {
  page: number
  totalPages: number
  total: number
  pageSize: number
  viewMode: 'pagination' | 'infinite'
  onPageChange: (page: number) => void
  onPageSizeChange: (size: number) => void
  onViewModeChange: (mode: 'pagination' | 'infinite') => void
}

const PAGE_SIZES = [30, 50, 100, 300, 500]

export default function Pagination({
  page,
  totalPages,
  total,
  pageSize,
  viewMode,
  onPageChange,
  onPageSizeChange,
  onViewModeChange,
}: PaginationProps) {
  const [pageInput, setPageInput] = useState(String(page))
  const [pageSizePickerVisible, setPageSizePickerVisible] = useState(false)

  useEffect(() => {
    setPageInput(String(page))
  }, [page])

  const handleJump = () => {
    const p = parseInt(pageInput) || 1
    const clamped = Math.max(1, Math.min(p, totalPages))
    onPageChange(clamped)
  }

  const getPageNumbers = () => {
    const pages: (number | '...')[] = []
    const maxVisible = 5

    if (totalPages <= maxVisible + 2) {
      for (let i = 1; i <= totalPages; i++) pages.push(i)
    } else {
      pages.push(1)
      if (page > 3) pages.push('...')
      const start = Math.max(2, page - 1)
      const end = Math.min(totalPages - 1, page + 1)
      for (let i = start; i <= end; i++) pages.push(i)
      if (page < totalPages - 2) pages.push('...')
      pages.push(totalPages)
    }
    return pages
  }

  const isMobile = window.innerWidth <= 768

  return (
    <div className="pagination-container">
      <div className="pagination-header">
        <span className="pagination-total">共 {total} 个画廊</span>
        <div className="pagination-mode-switch">
          <Button
            size="mini"
            fill={viewMode === 'pagination' ? 'solid' : 'outline'}
            onClick={() => onViewModeChange('pagination')}
          >
            分页
          </Button>
          <Button
            size="mini"
            fill={viewMode === 'infinite' ? 'solid' : 'outline'}
            onClick={() => onViewModeChange('infinite')}
          >
            无限
          </Button>
        </div>
      </div>

      {viewMode === 'pagination' && (
        <>
          {isMobile ? (
            <div className="pagination-mobile">
              <div className="pagination-nav">
                <Button size="small" disabled={page <= 1} onClick={() => onPageChange(1)}>
                  ⏮
                </Button>
                <Button size="small" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>
                  ◀
                </Button>
                <span className="pagination-current">
                  {page} / {totalPages}
                </span>
                <Button
                  size="small"
                  disabled={page >= totalPages}
                  onClick={() => onPageChange(page + 1)}
                >
                  ▶
                </Button>
                <Button
                  size="small"
                  disabled={page >= totalPages}
                  onClick={() => onPageChange(totalPages)}
                >
                  ⏭
                </Button>
              </div>
              <div className="pagination-jump-mobile">
                <span>跳转到</span>
                <input
                  type="number"
                  inputMode="numeric"
                  value={pageInput}
                  onChange={(e) => setPageInput(e.target.value.replace(/[^0-9]/g, ''))}
                  onKeyDown={(e) => e.key === 'Enter' && handleJump()}
                  placeholder="页码"
                  className="pagination-input"
                />
                <Button size="small" onClick={handleJump}>
                  确定
                </Button>
              </div>
            </div>
          ) : (
            <div className="pagination-desktop">
              <div className="pagination-pages">
                <Button size="small" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>
                  上一页
                </Button>
                {getPageNumbers().map((p, i) =>
                  p === '...' ? (
                    <span key={`ellipsis-${i}`} className="pagination-ellipsis">
                      ...
                    </span>
                  ) : (
                    <Button
                      key={p}
                      size="small"
                      fill={p === page ? 'solid' : 'outline'}
                      color={p === page ? 'primary' : 'default'}
                      onClick={() => onPageChange(p as number)}
                    >
                      {p}
                    </Button>
                  )
                )}
                <Button
                  size="small"
                  disabled={page >= totalPages}
                  onClick={() => onPageChange(page + 1)}
                >
                  下一页
                </Button>
              </div>
              <div className="pagination-controls">
                <span>每页</span>
                <Button
                  size="small"
                  fill="outline"
                  onClick={() => setPageSizePickerVisible(true)}
                >
                  {pageSize}
                </Button>
                <span>条</span>
                <span className="pagination-divider">|</span>
                <span>跳转</span>
                <input
                  type="number"
                  inputMode="numeric"
                  value={pageInput}
                  onChange={(e) => setPageInput(e.target.value.replace(/[^0-9]/g, ''))}
                  onKeyDown={(e) => e.key === 'Enter' && handleJump()}
                  className="pagination-input"
                />
                <Button size="small" onClick={handleJump}>
                  确定
                </Button>
              </div>
            </div>
          )}
        </>
      )}

      <Picker
        columns={[PAGE_SIZES.map((s) => ({ label: `${s} 条/页`, value: String(s) }))]}
        visible={pageSizePickerVisible}
        value={[String(pageSize)]}
        onConfirm={(v) => {
          onPageSizeChange(Number(v[0]))
          setPageSizePickerVisible(false)
        }}
        onClose={() => setPageSizePickerVisible(false)}
      />
    </div>
  )
}
