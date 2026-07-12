import { useState, useEffect } from 'react'
import { NavBar, List, Switch, Toast } from 'antd-mobile'
import { useNavigate } from 'react-router-dom'
import api, { type SystemInfo } from '../api/client'

export default function Settings() {
  const navigate = useNavigate()
  const [darkMode, setDarkMode] = useState(false)
  const [systemInfo, setSystemInfo] = useState<SystemInfo | null>(null)

  useEffect(() => {
    const theme = localStorage.getItem('theme') || 'light'
    setDarkMode(theme === 'dark')
    document.documentElement.setAttribute('data-theme', theme)

    api.getSystemInfo().then(setSystemInfo).catch(console.error)
  }, [])

  const toggleTheme = (checked: boolean) => {
    setDarkMode(checked)
    const theme = checked ? 'dark' : 'light'
    document.documentElement.setAttribute('data-theme', theme)
    localStorage.setItem('theme', theme)
  }

  return (
    <div>
      <NavBar backArrow={false}>设置</NavBar>
      <div className="page-content">
        <List header="显示">
          <List.Item extra={<Switch checked={darkMode} onChange={toggleTheme} />}>
            深色模式
          </List.Item>
        </List>

        <List header="远程管理" style={{ marginTop: 16 }}>
          <List.Item extra={systemInfo?.authMode === 'none' ? '无认证' : systemInfo?.authMode === 'password' ? '密码认证' : 'Token认证'}>
            认证模式
          </List.Item>
          <List.Item extra={<Switch checked={systemInfo?.deleteEnabled ?? true} />}>
            允许远程删除
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
