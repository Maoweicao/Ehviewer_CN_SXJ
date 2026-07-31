import { useState, useEffect } from 'react'
import { NavBar, List, Tag, Toast, Radio } from 'antd-mobile'
import { useNavigate } from 'react-router-dom'
import api, { type SystemInfo } from '../api/client'
import { type ThemeMode, getThemeMode, setThemeMode, resolveTheme, applyTheme } from '../utils/theme'

export default function Settings() {
  const navigate = useNavigate()
  const [themeMode, setThemeModeState] = useState<ThemeMode>(getThemeMode)
  const [systemInfo, setSystemInfo] = useState<SystemInfo | null>(null)

  useEffect(() => {
    const loadSystemInfo = () => {
      api.getSystemInfo().then(setSystemInfo).catch(console.error)
    }
    loadSystemInfo()

    const handleVisibility = () => {
      if (document.visibilityState === 'visible') {
        loadSystemInfo()
      }
    }
    document.addEventListener('visibilitychange', handleVisibility)
    return () => document.removeEventListener('visibilitychange', handleVisibility)
  }, [])

  const handleThemeModeChange = (val: ThemeMode) => {
    setThemeModeState(val)
    setThemeMode(val)
    applyTheme(resolveTheme(val))
  }

  return (
    <div>
      <NavBar backArrow={false}>设置</NavBar>
      <div className="page-content">
        <List header="显示">
          <List.Item>
            <div style={{ marginBottom: 8, fontWeight: 500 }}>主题模式</div>
            <Radio.Group
              value={themeMode}
              onChange={(val) => handleThemeModeChange(val as ThemeMode)}
            >
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                <Radio value="light">浅色模式</Radio>
                <Radio value="dark">深色模式</Radio>
                <Radio value="auto">
                  自动
                  <span style={{ fontSize: 12, color: 'var(--text-light)', marginLeft: 8 }}>
                    (18:00 - 06:00 深色)
                  </span>
                </Radio>
              </div>
            </Radio.Group>
          </List.Item>
        </List>

        <List header="远程管理" style={{ marginTop: 16 }}>
          <List.Item
            extra={
              <Tag
                color={systemInfo?.remoteManagementEnabled ? 'success' : 'warning'}
                fill="solid"
                style={{ fontSize: 12 }}
              >
                {systemInfo?.remoteManagementEnabled ? '已开启' : '已关闭'}
              </Tag>
            }
          >
            远程管理
          </List.Item>
          <List.Item extra={systemInfo?.authMode === 'none' ? '无认证' : systemInfo?.authMode === 'password' ? '密码认证' : 'Token认证'}>
            认证模式
          </List.Item>
          <List.Item
            extra={
              <Tag
                color={systemInfo?.deleteEnabled ? 'success' : 'default'}
                fill="solid"
                style={{ fontSize: 12 }}
              >
                {systemInfo?.deleteEnabled ? '允许' : '禁止'}
              </Tag>
            }
          >
            远程删除
          </List.Item>
        </List>

        <List header="数据管理" style={{ marginTop: 16 }}>
          <List.Item
            clickable
            onClick={() => navigate('/settings/data-transfer')}
            extra={<span style={{ color: 'var(--text-light)' }}>&#8250;</span>}
          >
            数据导入导出
          </List.Item>
          <List.Item
            clickable
            onClick={() => navigate('/settings/tasks')}
            extra={<span style={{ color: 'var(--text-light)' }}>&#8250;</span>}
          >
            任务中心
          </List.Item>
        </List>

        <List header="关于" style={{ marginTop: 16 }}>
          <List.Item extra={systemInfo?.appVersion || '2.0.2.2'}>版本</List.Item>
          <List.Item extra={systemInfo ? `${systemInfo.deviceName} (${systemInfo.deviceManufacturer})` : '加载中...'}>
            设备
          </List.Item>
          <List.Item extra={systemInfo ? `${systemInfo.storageUsedFormatted} / ${systemInfo.storageTotalFormatted}` : '加载中...'}>
            存储空间
          </List.Item>
          <List.Item extra={systemInfo ? `${systemInfo.downloadedGalleries} / ${systemInfo.totalGalleries}` : '加载中...'}>
            画廊统计
          </List.Item>
          <List.Item clickable onClick={() => window.open('/docs', '_blank')}>
            API 文档
          </List.Item>
          <List.Item clickable onClick={() => window.open('/api/v1/debug', '_blank')}>
            API Debug
          </List.Item>
        </List>
      </div>
    </div>
  )
}
