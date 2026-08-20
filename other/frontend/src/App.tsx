import { useState, useEffect, useCallback } from 'react'
import { Routes, Route, Navigate, useNavigate, useLocation } from 'react-router-dom'
import { TabBar, SafeArea } from 'antd-mobile'
import {
  AppOutline,
  UnorderedListOutline,
  SetOutline,
} from 'antd-mobile-icons'
import api from './api/client'
import Login from './pages/Login'
import GalleryList from './pages/GalleryList'
import GalleryDetail from './pages/GalleryDetail'
import GalleryViewer from './pages/GalleryViewer'
import FileBrowser from './pages/FileBrowser'
import Settings from './pages/Settings'
import DataTransfer from './pages/DataTransfer'
import TaskCenter from './pages/TaskCenter'
import DebugPanel from './components/DebugPanel'
import FullScreenLoading from './components/FullScreenLoading'
import { startAutoThemeWatcher, stopAutoThemeWatcher } from './utils/theme'

function MainLayout() {
  const navigate = useNavigate()
  const location = useLocation()

  const tabs = [
    { key: '/', title: '画廊', icon: <AppOutline /> },
    { key: '/files', title: '文件', icon: <UnorderedListOutline /> },
    { key: '/settings', title: '设置', icon: <SetOutline /> },
  ]

  const activeKey = location.pathname.startsWith('/files')
    ? '/files'
    : location.pathname.startsWith('/settings')
      ? '/settings'
      : '/'

  const handleTabChange = (key: string) => {
    navigate(key)
  }

  return (
    <div className="page-container">
      <Routes>
        <Route path="/" element={<GalleryList />} />
        <Route path="/gallery/:gid" element={<GalleryDetail />} />
        <Route path="/gallery/:gid/view" element={<GalleryViewer />} />
        <Route path="/files" element={<FileBrowser />} />
        <Route path="/settings" element={<Settings />} />
        <Route path="/settings/data-transfer" element={<DataTransfer />} />
        <Route path="/settings/tasks" element={<TaskCenter />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      <DebugPanel />
      <div style={{ position: 'fixed', bottom: 0, left: 0, right: 0, zIndex: 100, background: 'var(--card-bg, #fff)' }}>
        <TabBar activeKey={activeKey} onChange={handleTabChange}>
          {tabs.map((item) => (
            <TabBar.Item key={item.key} icon={item.icon} title={item.title} />
          ))}
        </TabBar>
        <SafeArea position="bottom" />
      </div>
    </div>
  )
}

export default function App() {
  const [authenticated, setAuthenticated] = useState<boolean | null>(null)
  const navigate = useNavigate()
  const location = useLocation()

  const checkAuth = useCallback(async () => {
    try {
      const status = await api.getAuthStatus()
      if (status.mode !== 'none' && !status.authenticated && !api.isAuthenticated()) {
        setAuthenticated(false)
        return false
      }
      setAuthenticated(true)
      return true
    } catch {
      setAuthenticated(false)
      return false
    }
  }, [])

  useEffect(() => {
    checkAuth().then((ok) => {
      if (!ok && location.pathname !== '/login') {
        navigate('/login', { replace: true })
      }
    })
  }, [])

  useEffect(() => {
    startAutoThemeWatcher()
    return () => stopAutoThemeWatcher()
  }, [])

  if (authenticated === null) {
    return <FullScreenLoading text="连接中..." />
  }

  if (!authenticated && location.pathname !== '/login') {
    return <Navigate to="/login" replace />
  }

  return (
    <Routes>
      <Route
        path="/login"
        element={
          authenticated ? (
            <Navigate to="/" replace />
          ) : (
            <Login
              onLoginSuccess={() => {
                setAuthenticated(true)
                navigate('/', { replace: true })
              }}
            />
          )
        }
      />
      <Route
        path="/*"
        element={
          !authenticated ? (
            <Navigate to="/login" replace />
          ) : (
            <MainLayout />
          )
        }
      />
    </Routes>
  )
}
