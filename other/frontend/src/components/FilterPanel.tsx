import { useState } from 'react'
import { Button, Selector, Popup } from 'antd-mobile'
import { AddOutline } from 'antd-mobile-icons'
import { SORT_FIELDS, GALLERY_CATEGORIES, getCategoryLabel, getCategoryColor } from '../api/client'

interface SortCondition {
  field: string
  order: 'asc' | 'desc'
}

interface FilterState {
  categories: string[]
  states: number[]
  minRating: number
  minSizeMB: number
}

interface FilterPanelProps {
  visible: boolean
  onClose: () => void
  filters: FilterState
  sortConditions: SortCondition[]
  onFiltersChange: (filters: FilterState) => void
  onSortConditionsChange: (conditions: SortCondition[]) => void
  onApply: () => void
  onReset: () => void
}

const STATE_OPTIONS = [
  { label: '无', value: 0 },
  { label: '等待', value: 1 },
  { label: '下载中', value: 2 },
  { label: '已完成', value: 3 },
  { label: '失败', value: 4 },
  { label: '更新', value: 5 },
]

export default function FilterPanel({
  visible,
  onClose,
  filters,
  sortConditions,
  onFiltersChange,
  onSortConditionsChange,
  onApply,
  onReset,
}: FilterPanelProps) {
  const handleCategoryChange = (v: string[]) => {
    onFiltersChange({ ...filters, categories: v })
  }

  const handleStateChange = (v: number[]) => {
    onFiltersChange({ ...filters, states: v })
  }

  const handleRatingChange = (v: number[]) => {
    onFiltersChange({ ...filters, minRating: v[0] })
  }

  const handleSizeChange = (v: number[]) => {
    onFiltersChange({ ...filters, minSizeMB: v[0] })
  }

  const handleSortFieldChange = (index: number, field: string) => {
    const next = [...sortConditions]
    next[index] = { ...next[index], field }
    onSortConditionsChange(next)
  }

  const handleSortOrderChange = (index: number, order: 'asc' | 'desc') => {
    const next = [...sortConditions]
    next[index] = { ...next[index], order }
    onSortConditionsChange(next)
  }

  const handleRemoveSort = (index: number) => {
    onSortConditionsChange(sortConditions.filter((_, i) => i !== index))
  }

  const handleAddSort = () => {
    onSortConditionsChange([...sortConditions, { field: 'title', order: 'asc' }])
  }

  return (
    <Popup
      visible={visible}
      onMaskClick={onClose}
      position="bottom"
      bodyStyle={{
        borderTopLeftRadius: 12,
        borderTopRightRadius: 12,
        maxHeight: '80vh',
        overflowY: 'auto',
      }}
    >
      <div className="filter-panel">
        <div className="filter-header">
          <strong>筛选与排序</strong>
          <Button size="mini" onClick={onClose}>
            关闭
          </Button>
        </div>

        <div className="filter-section">
          <div className="filter-label">分类</div>
          <div className="category-selector">
            {GALLERY_CATEGORIES.map((c) => {
              const isSelected = filters.categories.includes(c)
              const color = getCategoryColor(c)
              return (
                <button
                  key={c}
                  className={`category-option ${isSelected ? 'selected' : ''}`}
                  style={{
                    '--category-color': color,
                    '--category-bg': `${color}20`,
                  } as React.CSSProperties}
                  onClick={() => {
                    const next = isSelected
                      ? filters.categories.filter((x) => x !== c)
                      : [...filters.categories, c]
                    handleCategoryChange(next)
                  }}
                >
                  <span className="category-dot" style={{ background: color }} />
                  {getCategoryLabel(c)}
                </button>
              )
            })}
          </div>
        </div>

        <div className="filter-section">
          <div className="filter-label">状态</div>
          <Selector
            multiple
            options={STATE_OPTIONS}
            value={filters.states}
            onChange={handleStateChange}
          />
        </div>

        <div className="filter-section">
          <div className="filter-label">评分 {'\u2265'} {filters.minRating}</div>
          <Selector
            options={[0, 1, 2, 3, 4, 5].map((r) => ({
              label: r === 0 ? '不限' : String(r),
              value: r,
            }))}
            value={[filters.minRating]}
            onChange={handleRatingChange}
          />
        </div>

        <div className="filter-section">
          <div className="filter-label">大小 {'\u2265'} {filters.minSizeMB || 0} MB</div>
          <Selector
            options={[0, 1, 5, 10, 50, 100, 500, 1000].map((s) => ({
              label: s === 0 ? '不限' : `${s}MB`,
              value: s,
            }))}
            value={[filters.minSizeMB]}
            onChange={handleSizeChange}
          />
        </div>

        <div className="filter-section">
          <div className="filter-label">排序条件（可叠加，自上而下优先级）</div>
          {sortConditions.map((c, i) => (
            <div key={i} className="sort-row">
              <select
                value={c.field}
                onChange={(e) => handleSortFieldChange(i, e.target.value)}
                className="sort-select"
              >
                {SORT_FIELDS.map((f) => (
                  <option key={f.value} value={f.value}>
                    {f.label}
                  </option>
                ))}
              </select>
              <select
                value={c.order}
                onChange={(e) => handleSortOrderChange(i, e.target.value as 'asc' | 'desc')}
                className="sort-order-select"
              >
                <option value="desc">降序</option>
                <option value="asc">升序</option>
              </select>
              <Button size="mini" onClick={() => handleRemoveSort(i)}>
                删
              </Button>
            </div>
          ))}
          <Button size="small" block onClick={handleAddSort} style={{ marginTop: 4 }}>
            <AddOutline /> 添加排序条件
          </Button>
        </div>

        <div className="filter-actions">
          <Button block fill="outline" onClick={onReset}>
            重置
          </Button>
          <Button block color="primary" onClick={onApply}>
            应用
          </Button>
        </div>
      </div>
    </Popup>
  )
}

export type { FilterState, SortCondition }
