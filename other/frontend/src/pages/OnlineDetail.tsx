import { useState, useEffect } from 'react'
import { useParams, useSearchParams, useNavigate } from 'react-router-dom'
import { NavBar, List, Tag, Toast, Button, SpinLoading } from 'antd-mobile'
import api, { getCategoryLabel, type GalleryDetail as GalleryDetailType } from '../api/client'

/**
 * 在线画廊详情页（协议 §5.25.2）
 */
export default function OnlineDetail() {
  const { gid } = useParams<{ gid: string }>()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const gidNumber = gid ? parseInt(gid) : 0
  const token = searchParams.get('token') || ''

  const [detail, setDetail] = useState<GalleryDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState<string | null>(null)
  const [favorited, setFavorited] = useState(false)
  const [dl, setDl] = useState<any>(null)

  const load = () => {
    if (!gidNumber || !token) {
      setLoading(false)
      return
    }
    setLoading(true)
    api.onlineGalleryDetail(gidNumber, token)
      .then((d) => {
        setDetail(d)
        if (typeof d.isFavorited === 'boolean') setFavorited(d.isFavorited)
      })
      .catch((e) => Toast.show({ content: e?.message || '加载失败', icon: 'fail' }))
      .finally(() => setLoading(false))
    // 本地下载进度（可能未下载 → 404）
    api.getDownload(gidNumber).then(setDl).catch(() => setDl(null))
  }

  useEffect(load, [gidNumber, token])

  const handleAddDownload = async () => {
    if (!detail) return
    setBusy('download')
    try {
      const res = await api.createDownload({
        gid: detail.gid,
        token: detail.token,
        title: detail.title,
        titleJpn: detail.titleJpn,
        thumb: detail.thumb,
        category: detail.categoryValue ?? 0,
        posted: detail.posted,
        uploader: detail.uploader,
        rating: detail.rating,
        pages: detail.pages,
        label: '默认',
      }, true)
      Toast.show({ content: res.message || '已添加到下载', icon: 'success' })
    } catch (e: any) {
      Toast.show({ content: e?.message || '添加下载失败', icon: 'fail' })
    } finally {
      setBusy(null)
    }
  }

  const handleStartDownload = async () => {
    setBusy('start')
    try {
      await api.startDownload(gidNumber)
      Toast.show({ content: '已开始下载', icon: 'success' })
      api.getDownload(gidNumber).then(setDl).catch(() => {})
    } catch (e: any) {
      Toast.show({ content: e?.message || '启动下载失败', icon: 'fail' })
    } finally {
      setBusy(null)
    }
  }

  const handleFavorite = async () => {
    if (!detail) return
    setBusy('fav')
    try {
      if (favorited) {
        await api.removeFavorite(detail.gid)
        setFavorited(false)
        Toast.show({ content: '已取消收藏', icon: 'success' })
      } else {
        await api.addFavorite({
          gid: detail.gid,
          token: detail.token,
          title: detail.title,
          titleJpn: detail.titleJpn,
          category: detail.categoryValue ?? 0,
          posted: detail.posted,
          uploader: detail.uploader,
          rating: detail.rating,
          pages: detail.pages,
          thumb: detail.thumb,
        })
        setFavorited(true)
        Toast.show({ content: '已加入收藏', icon: 'success' })
      }
    } catch (e: any) {
      const msg = e?.message || ''
      if (msg.includes('already exists')) {
        setFavorited(true)
        Toast.show({ content: '已在收藏中', icon: 'info' })
      } else {
        Toast.show({ content: msg || '收藏失败', icon: 'fail' })
      }
    } finally {
      setBusy(null)
    }
  }

  if (loading) {
    return (
      <div>
        <NavBar onBack={() => navigate(-1)}>在线画廊</NavBar>
        <div style={{ textAlign: 'center', padding: 48 }}>
          <SpinLoading style={{ '--size': '36px' }} />
        </div>
      </div>
    )
  }

  if (!detail) {
    return (
      <div>
        <NavBar onBack={() => navigate(-1)}>在线画廊</NavBar>
        <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-light)' }}>
          无法加载，可能未登录或 token 缺失
        </div>
      </div>
    )
  }

  const title = detail.titleJpn || detail.title

  return (
    <div>
      <NavBar onBack={() => navigate(-1)}>在线画廊</NavBar>
      <div className="page-content">
        <div style={{ display: 'flex', gap: 12, padding: 12 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 16, fontWeight: 600 }}>{title}</div>
            {detail.titleJpn && detail.title && detail.titleJpn !== detail.title && (
              <div style={{ fontSize: 12, color: 'var(--text-light)', marginTop: 4 }}>{detail.title}</div>
            )}
            <div style={{ marginTop: 8 }}>
              <Tag color="primary" fill="outline" style={{ fontSize: 11 }}>{getCategoryLabel(detail.category)}</Tag>
              <span style={{ marginLeft: 8, fontSize: 12 }}>★{detail.rating?.toFixed?.(1) ?? detail.rating}</span>
              <span style={{ marginLeft: 8, fontSize: 12 }}>{detail.pages} 页</span>
            </div>
          </div>
        </div>

        <div style={{ padding: '0 16px 8px', display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <Button size="small" color="primary" onClick={() => navigate(`/online/${gidNumber}/view?token=${encodeURIComponent(token)}`)}>
            浏览图片
          </Button>
          <Button size="small" color="success" disabled={!!dl} loading={busy === 'download'} onClick={handleAddDownload}>
            {dl ? '已加入下载' : '加入下载'}
          </Button>
          <Button
            size="small"
            color={favorited ? 'warning' : 'primary'}
            fill={favorited ? 'outline' : 'solid'}
            loading={busy === 'fav'}
            onClick={handleFavorite}
          >
            {favorited ? '取消收藏' : '加入收藏'}
          </Button>
        </div>

        {dl && (
          <div style={{ padding: '0 16px 8px' }}>
            <div style={{ fontSize: 13, marginBottom: 4 }}>
              下载进度 {dl.downloaded ?? dl.finished ?? 0}/{dl.total ?? detail.pages} · {dl.stateName || dl.state}
            </div>
            <div style={{ height: 6, background: 'var(--border,#eee)', borderRadius: 3 }}>
              <div
                style={{
                  height: 6,
                  width: `${Math.max(0, Math.min(100, Number(dl.progress) || 0))}%`,
                  background: '#2196F3',
                  borderRadius: 3,
                }}
              />
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 6 }}>
              <span style={{ fontSize: 12, color: 'var(--text-light)' }}>
                {dl.isComplete || dl.state === 3
                  ? '已完成'
                  : `缺 ${Math.max(0, (detail.pages || 0) - (dl.downloaded ?? dl.finished ?? 0))} 页`}
              </span>
              {dl.state !== 2 && dl.state !== 3 && (
                <Button size="mini" color="primary" loading={busy === 'start'} onClick={handleStartDownload}>
                  继续下载
                </Button>
              )}
            </div>
          </div>
        )}

        <List header="信息">
          <List.Item extra={detail.uploader || '-'}>上传者</List.Item>
          <List.Item extra={detail.posted || '-'}>发布日期</List.Item>
          <List.Item extra={detail.language || '-'}>语言</List.Item>
          <List.Item extra={detail.favoriteCount ?? '-'}>收藏数</List.Item>
          <List.Item extra={detail.ratingCount ?? '-'}>评分人数</List.Item>
          <List.Item extra={detail.torrentCount ?? 0}>种子数</List.Item>
        </List>

        {detail.tags && detail.tags.length > 0 && (
          <div style={{ padding: 16 }}>
            <div style={{ fontWeight: 500, marginBottom: 8, fontSize: 14 }}>标签</div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {detail.tags.map((t) => (
                <Tag key={t} style={{ fontSize: 11 }}>{t}</Tag>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
