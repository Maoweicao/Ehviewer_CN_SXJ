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
  ActionSheet,
  Input,
} from 'antd-mobile'
import api, { type GalleryDetail as GalleryDetailType, type SystemInfo, GALLERY_STATES, getCategoryLabel, getCategoryColor } from '../api/client'

export default function GalleryDetail() {
  const { gid } = useParams<{ gid: string }>()
  const navigate = useNavigate()
  const [detail, setDetail] = useState<GalleryDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [compressing, setCompressing] = useState(false)
  const [deleteEnabled, setDeleteEnabled] = useState(false)
  const [pageUploadEnabled, setPageUploadEnabled] = useState(false)
  const [actionLoading, setActionLoading] = useState<string | null>(null)

  // 上传页面输入状态
  const [uploadUrl, setUploadUrl] = useState('')
  const [uploadPage, setUploadPage] = useState('1')
  const [showUploadDialog, setShowUploadDialog] = useState(false)

  const loadGallery = () => {
    if (!gid) return
    setLoading(true)
    Promise.all([
      api.getGallery(parseInt(gid)),
      api.getSystemInfo().catch(() => null),
    ])
      .then(([galleryData, sysInfo]) => {
        setDetail(galleryData)
        if (sysInfo) {
          setDeleteEnabled(sysInfo.deleteEnabled)
          setPageUploadEnabled(!!sysInfo.pageUploadEnabled)
        }
      })
      .catch(() => {
        Toast.show({ content: '加载详情失败', icon: 'fail' })
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    loadGallery()
    const handleVisibility = () => {
      if (document.visibilityState === 'visible') {
        api.getSystemInfo()
          .then((sysInfo) => {
            setDeleteEnabled(sysInfo.deleteEnabled)
            setPageUploadEnabled(!!sysInfo.pageUploadEnabled)
          })
          .catch(() => {})
      }
    }
    document.addEventListener('visibilitychange', handleVisibility)
    return () => document.removeEventListener('visibilitychange', handleVisibility)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [gid])

  const handleCompressDownload = async () => {
    if (!detail) return

    const actions: Array<{ key: string; text: string; onClick?: () => void }> = [
      { key: 'none', text: '不分卷', onClick: () => startCompress(0) },
      { key: '512', text: '512MB 分卷', onClick: () => startCompress(512) },
      { key: '1024', text: '1GB 分卷 (推荐)', onClick: () => startCompress(1024) },
      { key: '2048', text: '2GB 分卷', onClick: () => startCompress(2048) },
      { key: '_cancel', text: '取消' },
    ]
    ActionSheet.show({ actions })
  }

  const startCompress = async (splitSizeMB: number) => {
    if (!detail) return
    setCompressing(true)
    try {
      const result = await api.createCompressTask([detail.gid], splitSizeMB || 0, true)
      Toast.show({ content: `压缩任务已创建: ${result.taskId}`, icon: 'success' })

      const goTasks = await Dialog.confirm({
        content: '压缩任务已创建，是否前往任务中心查看？',
      })
      if (goTasks) {
        navigate('/settings/tasks')
      }
    } catch {
      Toast.show({ content: '创建压缩任务失败', icon: 'fail' })
    } finally {
      setCompressing(false)
    }
  }

  // ====== 下载管理 ======

  const handleAddDownload = async () => {
    if (!detail) return
    setActionLoading('add')
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
        label: detail.label || '默认',
      }, true)
      Toast.show({ content: res.message || '已添加到下载', icon: 'success' })
      loadGallery()
    } catch (e) {
      Toast.show({ content: '添加下载失败', icon: 'fail' })
    } finally {
      setActionLoading(null)
    }
  }

  const handleStartDownload = async () => {
    if (!detail) return
    setActionLoading('start')
    try {
      await api.startDownload(detail.gid)
      Toast.show({ content: '已开始下载', icon: 'success' })
      loadGallery()
    } catch (e) {
      Toast.show({ content: '启动下载失败', icon: 'fail' })
    } finally {
      setActionLoading(null)
    }
  }

  const handlePauseDownload = async () => {
    if (!detail) return
    setActionLoading('pause')
    try {
      await api.pauseDownload(detail.gid)
      Toast.show({ content: '已暂停下载', icon: 'success' })
      loadGallery()
    } catch (e) {
      Toast.show({ content: '暂停下载失败', icon: 'fail' })
    } finally {
      setActionLoading(null)
    }
  }

  const handleDeleteDownload = async () => {
    if (!detail) return
    if (!deleteEnabled) {
      Toast.show({ content: '远程删除功能未开启', icon: 'fail' })
      return
    }
    const ok = await Dialog.confirm({
      content: `确定删除「${detail.title}」的下载任务？`,
      confirmText: '删除',
    })
    if (!ok) return
    setActionLoading('delete')
    try {
      await api.deleteDownload(detail.gid)
      Toast.show({ content: '已删除下载任务', icon: 'success' })
      loadGallery()
    } catch (e) {
      Toast.show({ content: '删除失败', icon: 'fail' })
    } finally {
      setActionLoading(null)
    }
  }

  const handleRequestRelay = async () => {
    if (!detail) return
    setActionLoading('relay')
    try {
      const ua = navigator.userAgent
      const deviceName = (navigator as any).userAgentData?.platform || (ua.includes('Win') ? 'PC-Web' : 'Web')
      await api.requestRelay(
        {
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
        },
        {
          sourceDevice: deviceName,
          sourceDeviceId: 'web-' + Math.random().toString(36).slice(2, 10),
          priority: 'normal',
          autoReturn: true,
        }
      )
      Toast.show({ content: '接力请求已发送', icon: 'success' })
      const goTasks = await Dialog.confirm({
        content: '接力请求已发送，是否前往任务中心查看？',
      })
      if (goTasks) {
        navigate('/settings/tasks')
      } else {
        loadGallery()
      }
    } catch (e: any) {
      Toast.show({ content: e?.message || '请求接力失败', icon: 'fail' })
    } finally {
      setActionLoading(null)
    }
  }

  // ====== 上传页面 ======

  const handleOpenUploadDialog = () => {
    if (!pageUploadEnabled) {
      Toast.show({ content: 'Android 端未开启远程上传页面功能', icon: 'fail' })
      return
    }
    setUploadUrl('')
    setUploadPage('1')
    setShowUploadDialog(true)
  }

  const handleConfirmUpload = async () => {
    if (!detail) return
    const imageUrl = uploadUrl.trim()
    if (!imageUrl) {
      Toast.show({ content: '请输入图片 URL', icon: 'fail' })
      return
    }
    const pageNum = parseInt(uploadPage)
    if (!pageNum || pageNum < 1 || pageNum > detail.pages) {
      Toast.show({ content: '页码无效', icon: 'fail' })
      return
    }
    setShowUploadDialog(false)
    setActionLoading('upload')
    try {
      const resp = await fetch(imageUrl, { mode: 'cors' })
      if (!resp.ok) throw new Error('拉取图片失败: HTTP ' + resp.status)
      const blob = await resp.blob()
      const result = await api.uploadPage(detail.gid, pageNum, blob, {
        extension: blob.type.split('/')[1] || 'jpg',
        autoHash: true,
        galleryInfo: {
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
        },
      })
      const msg = result.skipped
        ? '文件已存在且一致，未重复写入'
        : result.overwritten
        ? `已覆盖 (旧 ${result.oldHash?.slice(0, 8)}... → 新 ${result.hash?.slice(0, 8)}...)`
        : '上传成功'
      Toast.show({ content: msg, icon: 'success' })
      loadGallery()
    } catch (e: any) {
      Toast.show({ content: e?.message || '上传失败', icon: 'fail' })
    } finally {
      setActionLoading(null)
    }
  }

  // ====== 辅助 ======

  const stateColor = (state: number): 'default' | 'primary' | 'success' | 'danger' | 'warning' => {
    switch (state) {
      case 2: return 'primary'
      case 3: return 'success'
      case 4: return 'danger'
      case 7: return 'warning' // relay_download
      case 1:
      case 5: return 'warning'
      default: return 'default'
    }
  }

  const isDownloaded = detail?.state === 3
  const isDownloading = detail?.state === 2
  const isWaiting = detail?.state === 1
  const isPaused = detail?.state === 0
  const isFailed = detail?.state === 4
  const isRelay = detail?.state === 7
  const hasDownloadRecord = detail?.state !== -1 && detail?.state !== undefined

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
              '--background-color': getCategoryColor(detail.category),
              '--text-color': '#fff',
              '--border-color': 'transparent',
            }}
          >
            {getCategoryLabel(detail.category)}
          </Tag>
        </div>
      </div>

      <div style={{ padding: '0 16px' }}>
        <List>
          <List.Item extra={`${detail.pages} 页`}>页数</List.Item>
          <List.Item extra={detail.downloadedPages != null ? `${detail.downloadedPages} / ${detail.pages}` : '-'}>已下载</List.Item>
          <List.Item extra={detail.isComplete != null ? (detail.isComplete ? '完整' : '不完整') : '-'}>完整度</List.Item>
          <List.Item extra={<Rate allowHalf readOnly value={detail.rating} style={{ '--star-size': '14px' }} />}>评分</List.Item>
          <List.Item extra={detail.uploader || '未知'}>上传者</List.Item>
          <List.Item extra={detail.posted || '未知'}>上传日期</List.Item>
          <List.Item extra={detail.language || '未知'}>语言</List.Item>
          <List.Item extra={detail.sizeFormatted || '未知'}>大小</List.Item>
          <List.Item
            extra={
              <Tag color={stateColor(detail.state)} fill="solid" style={{ fontSize: 11 }}>
                {GALLERY_STATES[detail.state] ?? String(detail.state)}
              </Tag>
            }
          >
            状态
          </List.Item>
          <List.Item extra={detail.createdDate || '未知'}>创建日期</List.Item>
          <List.Item
            extra={
              <Tag
                color={deleteEnabled ? 'success' : 'default'}
                fill="solid"
                style={{ fontSize: 11 }}
              >
                {deleteEnabled ? '允许' : '禁止'}
              </Tag>
            }
          >
            远程删除
          </List.Item>
          <List.Item
            extra={
              <Tag
                color={pageUploadEnabled ? 'success' : 'default'}
                fill="solid"
                style={{ fontSize: 11 }}
              >
                {pageUploadEnabled ? '允许' : '禁止'}
              </Tag>
            }
          >
            远程上传页面
          </List.Item>
          {detail.tags && detail.tags.length > 0 && (
            <List.Item>
              <div style={{ marginBottom: 8, fontWeight: 500 }}>标签</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {detail.tags.map((t) => {
                  const [prefix, ...rest] = t.split(':')
                  const value = rest.join(':')
                  return (
                    <Tag
                      key={t}
                      fill="outline"
                      style={{
                        '--border-color': prefix === 'artist' ? '#e91e63' : prefix === 'language' ? '#2196f3' : prefix === 'female' ? '#9c27b0' : prefix === 'male' ? '#4caf50' : 'var(--border)',
                        '--text-color': prefix === 'artist' ? '#e91e63' : prefix === 'language' ? '#2196f3' : prefix === 'female' ? '#9c27b0' : prefix === 'male' ? '#4caf50' : 'var(--text-light)',
                        fontSize: 11,
                      }}
                    >
                      {value || t}
                    </Tag>
                  )
                })}
              </div>
            </List.Item>
          )}
        </List>
      </div>

      {/* 主操作区 */}
      <div style={{ padding: 16, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        <Button
          block
          color="primary"
          size="large"
          onClick={() => navigate(`/gallery/${gid}/view`)}
          style={{ flex: 1, borderRadius: 8 }}
        >
          浏览图片
        </Button>
        <Button
          block
          color="success"
          size="large"
          loading={compressing}
          onClick={handleCompressDownload}
          style={{ flex: 1, borderRadius: 8 }}
        >
          压缩下载
        </Button>
      </div>

      {/* 下载管理 */}
      <div style={{ padding: '0 16px 16px' }}>
        <div style={{ fontWeight: 500, marginBottom: 8, fontSize: 14 }}>下载管理</div>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {!hasDownloadRecord && (
            <Button size="small" color="primary" loading={actionLoading === 'add'} onClick={handleAddDownload}>
              添加到下载
            </Button>
          )}
          {(isPaused || isFailed || isDownloaded) && (
            <Button size="small" color="primary" loading={actionLoading === 'start'} onClick={handleStartDownload}>
              {isDownloaded ? '重新下载' : '开始下载'}
            </Button>
          )}
          {(isWaiting || isDownloading) && (
            <Button size="small" color="warning" loading={actionLoading === 'pause'} onClick={handlePauseDownload}>
              暂停下载
            </Button>
          )}
          {hasDownloadRecord && (
            <Button size="small" color="danger" disabled={!deleteEnabled} loading={actionLoading === 'delete'} onClick={handleDeleteDownload}>
              删除下载
            </Button>
          )}
          {(isWaiting || isDownloading) && (
            <Button size="small" color="default" loading={actionLoading === 'relay'} onClick={handleRequestRelay}>
              请求接力下载
            </Button>
          )}
          {pageUploadEnabled && hasDownloadRecord && (
            <Button size="small" color="primary" fill="outline" loading={actionLoading === 'upload'} onClick={handleOpenUploadDialog}>
              上传页面
            </Button>
          )}
        </div>
      </div>

      {/* 上传页面输入对话框 */}
      <Dialog
        visible={showUploadDialog}
        title="上传页面"
        content={
          <div style={{ padding: '12px 0' }}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 6, fontSize: 13, color: 'var(--text-light)' }}>源站图片 URL</div>
              <Input
                placeholder="https://...jpg"
                value={uploadUrl}
                onChange={setUploadUrl}
                clearable
              />
            </div>
            <div>
              <div style={{ marginBottom: 6, fontSize: 13, color: 'var(--text-light)' }}>上传到第几页（1 - {detail.pages}）</div>
              <Input
                type="number"
                placeholder="1"
                value={uploadPage}
                onChange={(v) => setUploadPage(v)}
              />
            </div>
          </div>
        }
        onClose={() => setShowUploadDialog(false)}
        actions={[
          [
            {
              key: 'cancel',
              text: '取消',
              onClick: () => setShowUploadDialog(false),
            },
          ],
          [
            {
              key: 'confirm',
              text: '上传',
              bold: true,
              onClick: handleConfirmUpload,
            },
          ],
        ]}
      />
    </div>
  )
}