import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  NavBar,
  Image,
  Tag,
  Rate,
  List,
  Button,
  SpinLoading,
  Dialog,
  Toast,
} from 'antd-mobile'
import api, { type GalleryDetail } from '../api/client'

export default function GalleryDetail() {
  const { gid } = useParams<{ gid: string }>()
  const navigate = useNavigate()
  const [detail, setDetail] = useState<GalleryDetail | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!gid) return
    setLoading(true)
    api
      .getGallery(parseInt(gid))
      .then((data) => {
        setDetail(data)
      })
      .catch(() => {
        Toast.show({ content: '加载详情失败', icon: 'fail' })
      })
      .finally(() => setLoading(false))
  }, [gid])

  const handleDelete = async () => {
    if (!detail) return
    const result = await Dialog.confirm({
      content: '确定要删除这个画廊吗？',
    })
    if (result) {
      try {
        await api.deleteGallery(detail.gid)
        Toast.show({ content: '删除成功', icon: 'success' })
        navigate(-1)
      } catch {
        Toast.show({ content: '删除失败', icon: 'fail' })
      }
    }
  }

  const categoryColors: Record<string, string> = {
    Doujinshi: '#e91e63',
    Manga: '#ff9800',
    'Artist CG': '#4caf50',
    'Game CG': '#2196f3',
    'Image Set': '#9c27b0',
    Cosplay: '#795548',
  }

  if (loading) {
    return (
      <div>
        <NavBar onBack={() => navigate(-1)}>画廊详情</NavBar>
        <div className="loading-container" style={{ minHeight: '60vh' }}>
          <SpinLoading style={{ '--size': '48px' }} />
        </div>
      </div>
    )
  }

  if (!detail) {
    return (
      <div>
        <NavBar onBack={() => navigate(-1)}>画廊详情</NavBar>
        <div className="empty-container" style={{ minHeight: '60vh' }}>
          画廊不存在
        </div>
      </div>
    )
  }

  return (
    <div>
      <NavBar onBack={() => navigate(-1)}>画廊详情</NavBar>
      <div className="detail-header">
        <Image
          src={api.getThumbnailUrl(detail.gid)}
          width={120}
          height={160}
          fit="cover"
          style={{ borderRadius: 8, flexShrink: 0 }}
        />
        <div className="detail-info">
          <div className="detail-title">{detail.title}</div>
          {detail.titleJpn && <div className="detail-title-jpn">{detail.titleJpn}</div>}
          <Tag
            color="primary"
            fill="solid"
            style={{
              '--background-color': categoryColors[detail.category] || '#2196f3',
              '--text-color': '#fff',
              '--border-color': 'transparent',
            }}
          >
            {detail.category}
          </Tag>
        </div>
      </div>

      <div style={{ padding: '0 16px' }}>
        <List>
          <List.Item extra={`${detail.pages} 页`}>页数</List.Item>
          <List.Item extra={<Rate allowHalf readOnly value={detail.rating} style={{ '--star-size': '14px' }} />}>评分</List.Item>
          <List.Item extra={detail.uploader || '未知'}>上传者</List.Item>
          <List.Item extra={detail.posted || '未知'}>上传日期</List.Item>
          <List.Item extra={detail.language || '未知'}>语言</List.Item>
          <List.Item extra={detail.size || '未知'}>大小</List.Item>
          {detail.tags && detail.tags.length > 0 && (
            <List.Item>
              <div style={{ marginBottom: 4, fontWeight: 500 }}>标签</div>
              <div className="tags-wrap">
                {detail.tags.map((t) => (
                  <Tag key={t} fill="outline" style={{ '--border-color': '#e0e0e0', '--text-color': '#666', fontSize: 11 }}>
                    {t}
                  </Tag>
                ))}
              </div>
            </List.Item>
          )}
        </List>
      </div>

      <div style={{ padding: 16, display: 'flex', gap: 12 }}>
        <Button block color="primary" size="large" onClick={() => navigate(`/gallery/${gid}/view`)} style={{ borderRadius: 8 }}>
          浏览图片
        </Button>
        <Button block color="danger" size="large" onClick={handleDelete} style={{ borderRadius: 8 }}>
          删除
        </Button>
      </div>
    </div>
  )
}
