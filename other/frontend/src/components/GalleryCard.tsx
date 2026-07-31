import { Tag, Image, SpinLoading } from 'antd-mobile'
import { useNavigate } from 'react-router-dom'
import { GALLERY_STATES, getCategoryLabel, getCategoryColor } from '../api/client'
import type { Gallery } from '../api/client'

interface GalleryCardProps {
  gallery: Gallery
  getThumbUrl: (gid: number) => string
}

const STATE_COLORS: Record<number, string> = {
  [-1]: 'default',
  [0]: 'default',
  [1]: 'warning',
  [2]: 'primary',
  [3]: 'success',
  [4]: 'danger',
  [5]: 'warning',
}

export default function GalleryCard({ gallery, getThumbUrl }: GalleryCardProps) {
  const navigate = useNavigate()

  const formatRating = (r: number) => (r ? `★ ${r.toFixed(1)}` : '')

  return (
    <div className="gallery-card" onClick={() => navigate(`/gallery/${gallery.gid}`)}>
      <Image
        src={getThumbUrl(gallery.gid)}
        lazy
        fit="cover"
        width="100%"
        height={200}
        style={{ borderRadius: 0 }}
        placeholder={
          <div className="gallery-card-placeholder">
            <SpinLoading style={{ '--size': '24px' }} />
          </div>
        }
        fallback={
          <div className="gallery-card-fallback">🖼️</div>
        }
      />
      <div className="gallery-info">
        <div className="gallery-title">{gallery.title}</div>
        <div className="gallery-meta">
          <Tag
            color="primary"
            fill="solid"
            style={{
              '--background-color': getCategoryColor(gallery.category),
              '--text-color': '#fff',
              '--border-color': 'transparent',
              fontSize: 11,
            }}
          >
            {getCategoryLabel(gallery.category)}
          </Tag>
          <Tag color="warning" fill="outline" style={{ fontSize: 11 }}>
            {formatRating(gallery.rating)}
          </Tag>
        </div>
        <div className="gallery-meta" style={{ marginTop: 4 }}>
          <Tag
            color={STATE_COLORS[gallery.state] || 'default'}
            fill="outline"
            style={{ fontSize: 11 }}
          >
            {GALLERY_STATES[gallery.state] ?? String(gallery.state)}
          </Tag>
          {gallery.createdDate && (
            <span className="gallery-date">{gallery.createdDate}</span>
          )}
        </div>
        <div className="gallery-pages">
          {gallery.pages} 页 · {gallery.sizeFormatted}
        </div>
      </div>
    </div>
  )
}
