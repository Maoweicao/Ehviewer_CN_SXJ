import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { SearchBar, Button, Toast, SpinLoading, Empty, Tag, Segmented } from 'antd-mobile'
import api, { getCategoryLabel, type Gallery, type EhAuthStatus } from '../api/client'

/**
 * 在线画廊页（协议 §5.25）
 *
 * 通过主机在线搜索源站并把结果列出，支持直接「加入下载 / 收藏」。
 * 需要主机已登录 E-Hentai（§5.27）。
 */
export default function OnlineSearch() {
  const navigate = useNavigate()
  const [keyword, setKeyword] = useState('')
  const [mode, setMode] = useState('normal')
  const [page, setPage] = useState(0)
  const [galleries, setGalleries] = useState<Gallery[]>([])
  const [hasNext, setHasNext] = useState(false)
  const [hasPrev, setHasPrev] = useState(false)
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState<EhAuthStatus | null>(null)
  const [busy, setBusy] = useState<number | null>(null)

  const load = useCallback(async (kw: string, p: number, m: string) => {
    setLoading(true)
    try {
      const res = await api.onlineSearch({ keyword: kw, page: p, mode: m, thumb: true })
      setGalleries(res.galleries || [])
      setHasNext(!!res.hasNext)
      setHasPrev(!!res.hasPrev)
      setPage(typeof res.page === 'number' ? res.page : p)
    } catch (e: any) {
      Toast.show({ content: e?.message || '搜索失败', icon: 'fail' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    api.ehAuthStatus().then(setStatus).catch(() => setStatus(null))
    load('', 0, 'normal')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleAddDownload = async (g: Gallery) => {
    setBusy(g.gid)
    try {
      const res = await api.createDownload({
        gid: g.gid,
        token: g.token,
        title: g.title,
        titleJpn: g.titleJpn,
        thumb: g.thumb,
        category: g.categoryValue ?? 0,
        posted: g.posted,
        uploader: g.uploader,
        rating: g.rating,
        pages: g.pages,
        label: '默认',
      }, true)
      Toast.show({ content: res.message || '已添加到下载', icon: 'success' })
    } catch (e: any) {
      Toast.show({ content: e?.message || '添加下载失败', icon: 'fail' })
    } finally {
      setBusy(null)
    }
  }

  const handleFavorite = async (g: Gallery) => {
    setBusy(g.gid)
    try {
      await api.addFavorite({
        gid: g.gid,
        token: g.token,
        title: g.title,
        titleJpn: g.titleJpn,
        category: g.categoryValue ?? 0,
        posted: g.posted,
        uploader: g.uploader,
        rating: g.rating,
        pages: g.pages,
        thumb: g.thumb,
      })
      Toast.show({ content: '已加入收藏', icon: 'success' })
    } catch (e: any) {
      const msg = e?.message || ''
      if (msg.includes('already exists')) {
        Toast.show({ content: '已在收藏中', icon: 'info' })
      } else {
        Toast.show({ content: msg || '收藏失败', icon: 'fail' })
      }
    } finally {
      setBusy(null)
    }
  }

  return (
    <div>
      <SearchBar
        placeholder="搜索 E-Hentai（支持 artist: / language: 等语法）"
        value={keyword}
        onChange={setKeyword}
        onSearch={(v) => load(v, 0, mode)}
      />
      <div style={{ padding: '8px 12px' }}>
        <Segmented
          value={mode}
          onChange={(v) => {
            const m = v as string
            setMode(m)
            load(keyword, 0, m)
          }}
          options={[
            { label: '常规', value: 'normal' },
            { label: '热门', value: 'popular' },
            { label: 'Top', value: 'top' },
          ]}
        />
      </div>

      {status && !status.signedIn && (
        <div style={{ padding: 16 }}>
          <Empty description="主机未登录 E-Hentai，请到 设置 → E-Hentai 账号 登录" />
        </div>
      )}

      {loading && (
        <div style={{ textAlign: 'center', padding: 24 }}>
          <SpinLoading style={{ '--size': '32px' }} />
        </div>
      )}

      {!loading && status?.signedIn && galleries.length === 0 && (
        <Empty description="没有结果" />
      )}

      <div style={{ padding: '0 12px' }}>
        {galleries.map((g) => (
          <div
            key={g.gid}
            onClick={() => navigate(`/online/${g.gid}?token=${encodeURIComponent(g.token || '')}`)}
            style={{
              display: 'flex',
              gap: 10,
              padding: '10px 0',
              borderBottom: '1px solid var(--border, #eee)',
              cursor: 'pointer',
            }}
          >
            {g.thumb && (
              <img
                src={g.thumb}
                alt=""
                referrerPolicy="no-referrer"
                style={{ width: 60, height: 84, objectFit: 'cover', borderRadius: 4, flexShrink: 0 }}
              />
            )}
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 14, fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical' }}>
                {g.titleJpn || g.title}
              </div>
              <div style={{ margin: '4px 0', fontSize: 12, color: 'var(--text-light)' }}>
                <Tag color="primary" fill="outline" style={{ fontSize: 11 }}>{getCategoryLabel(g.category)}</Tag>
                <span style={{ marginLeft: 6 }}>★{g.rating?.toFixed?.(1) ?? g.rating}</span>
                {g.pages ? <span style={{ marginLeft: 6 }}>{g.pages}P</span> : null}
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <Button size="mini" color="primary" loading={busy === g.gid} onClick={(e) => { e.stopPropagation(); handleAddDownload(g) }}>
                  加入下载
                </Button>
                <Button size="mini" color="warning" fill="outline" loading={busy === g.gid} onClick={(e) => { e.stopPropagation(); handleFavorite(g) }}>
                  收藏
                </Button>
              </div>
            </div>
          </div>
        ))}
      </div>

      {status?.signedIn && (hasPrev || hasNext) && (
        <div style={{ display: 'flex', gap: 12, justifyContent: 'center', padding: 16 }}>
          <Button size="small" disabled={!hasPrev} onClick={() => load(keyword, page - 1, mode)}>
            上一页
          </Button>
          <span style={{ lineHeight: '32px', fontSize: 13, color: 'var(--text-light)' }}>第 {page + 1} 页</span>
          <Button size="small" disabled={!hasNext} onClick={() => load(keyword, page + 1, mode)}>
            下一页
          </Button>
        </div>
      )}
    </div>
  )
}
