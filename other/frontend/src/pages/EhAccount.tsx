import { useState, useEffect } from 'react'
import { NavBar, List, Tag, Toast, Form, Input, Button, Tabs } from 'antd-mobile'
import { useNavigate } from 'react-router-dom'
import api, { type EhAuthStatus } from '../api/client'

/**
 * E-Hentai 账号页（协议 §5.27）
 *
 * 在 Web 端复刻源站登录：账号密码登录或导入 Cookie，登录态写入主机后，
 * 主机的在线浏览（§5.25）与下载能力立即生效。
 */
export default function EhAccount() {
  const navigate = useNavigate()
  const [status, setStatus] = useState<EhAuthStatus | null>(null)
  const [loading, setLoading] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [ipbMemberId, setIpbMemberId] = useState('')
  const [ipbPassHash, setIpbPassHash] = useState('')
  const [igneous, setIgneous] = useState('')

  const loadStatus = () => {
    api.ehAuthStatus().then(setStatus).catch(() => setStatus(null))
  }

  useEffect(() => {
    loadStatus()
  }, [])

  const handleSignin = async () => {
    if (!username || !password) {
      Toast.show({ content: '请输入账号和密码', position: 'center' })
      return
    }
    setLoading(true)
    try {
      const res = await api.ehSignin(username, password)
      Toast.show({ content: `登录成功: ${res.username}`, icon: 'success' })
      setPassword('')
      loadStatus()
    } catch (e: any) {
      Toast.show({ content: e?.message || '登录失败', icon: 'fail' })
    } finally {
      setLoading(false)
    }
  }

  const handleCookieImport = async () => {
    if (!ipbMemberId || !ipbPassHash) {
      Toast.show({ content: '请填写 ipb_member_id 与 ipb_pass_hash', position: 'center' })
      return
    }
    setLoading(true)
    try {
      await api.ehCookieImport(ipbMemberId, ipbPassHash, igneous)
      Toast.show({ content: 'Cookie 已导入', icon: 'success' })
      setIpbPassHash('')
      setIgneous('')
      loadStatus()
    } catch (e: any) {
      Toast.show({ content: e?.message || '导入失败', icon: 'fail' })
    } finally {
      setLoading(false)
    }
  }

  const handleSignout = async () => {
    setLoading(true)
    try {
      await api.ehSignout()
      Toast.show({ content: '已退出登录', icon: 'success' })
      loadStatus()
    } catch (e: any) {
      Toast.show({ content: e?.message || '退出失败', icon: 'fail' })
    } finally {
      setLoading(false)
    }
  }

  return (
    <div>
      <NavBar onBack={() => navigate('/settings')}>E-Hentai 账号</NavBar>
      <div className="page-content">
        <List header="当前状态">
          <List.Item
            extra={
              <Tag color={status?.signedIn ? 'success' : 'warning'} fill="solid" style={{ fontSize: 12 }}>
                {status?.signedIn ? '已登录' : '未登录'}
              </Tag>
            }
          >
            登录状态
          </List.Item>
          <List.Item extra={status?.site || 'none'}>站点</List.Item>
          <List.Item extra={status?.username || '-'}>用户名</List.Item>
        </List>

        <div style={{ marginTop: 16 }}>
          <Tabs>
            <Tabs.Tab title="账号密码" key="password">
              <Form layout="horizontal" style={{ marginTop: 8 }}>
                <Form.Item label="账号">
                  <Input placeholder="E-Hentai 用户名" value={username} onChange={setUsername} />
                </Form.Item>
                <Form.Item label="密码">
                  <Input
                    type="password"
                    placeholder="密码"
                    value={password}
                    onChange={setPassword}
                    onEnterPress={handleSignin}
                  />
                </Form.Item>
              </Form>
              <div style={{ padding: '0 16px' }}>
                <Button block color="primary" loading={loading} onClick={handleSignin} style={{ borderRadius: 8 }}>
                  登录
                </Button>
              </div>
            </Tabs.Tab>

            <Tabs.Tab title="Cookie 导入" key="cookie">
              <div style={{ padding: '8px 16px', fontSize: 12, color: 'var(--text-light)' }}>
                在浏览器登录 forums.e-hentai.org 后，复制 ipb_member_id / ipb_pass_hash（exhentai 还需 igneous）。
              </div>
              <Form layout="horizontal">
                <Form.Item label="ipb_member_id">
                  <Input placeholder="ipb_member_id" value={ipbMemberId} onChange={setIpbMemberId} />
                </Form.Item>
                <Form.Item label="ipb_pass_hash">
                  <Input placeholder="ipb_pass_hash" value={ipbPassHash} onChange={setIpbPassHash} />
                </Form.Item>
                <Form.Item label="igneous">
                  <Input placeholder="可选" value={igneous} onChange={setIgneous} />
                </Form.Item>
              </Form>
              <div style={{ padding: '0 16px' }}>
                <Button block color="primary" loading={loading} onClick={handleCookieImport} style={{ borderRadius: 8 }}>
                  导入 Cookie
                </Button>
              </div>
            </Tabs.Tab>
          </Tabs>
        </div>

        {status?.signedIn && (
          <div style={{ padding: 16 }}>
            <Button block color="danger" fill="outline" loading={loading} onClick={handleSignout} style={{ borderRadius: 8 }}>
              退出登录
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}
