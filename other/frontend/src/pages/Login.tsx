import { useState } from 'react'
import { Form, Input, Button, Toast } from 'antd-mobile'
import api from '../api/client'

interface LoginProps {
  onLoginSuccess: () => void
}

export default function Login({ onLoginSuccess }: LoginProps) {
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)

  const handleLogin = async () => {
    if (!password) {
      Toast.show({ content: '请输入密码或Token', position: 'center' })
      return
    }
    setLoading(true)
    try {
      await api.login(password)
      Toast.show({ content: '登录成功', icon: 'success' })
      onLoginSuccess()
    } catch {
      Toast.show({ content: '登录失败', icon: 'fail' })
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24,
        background: 'var(--bg, #f5f5f5)',
      }}
    >
      <div
        style={{
          background: 'var(--card-bg, #fff)',
          borderRadius: 12,
          padding: '40px 32px',
          boxShadow: '0 2px 12px rgba(0,0,0,0.1)',
          maxWidth: 400,
          width: '100%',
          textAlign: 'center',
        }}
      >
        <div style={{ fontSize: 64, marginBottom: 16 }}>📱</div>
        <h2 style={{ marginBottom: 8, color: 'var(--text, #333)' }}>EhViewer Remote</h2>
        <p style={{ color: 'var(--text-light, #666)', marginBottom: 24, fontSize: 14 }}>
          请输入密码或Token登录
        </p>
        <Form layout="horizontal">
          <Form.Item label="密码">
            <Input
              type="password"
              placeholder="密码或Token"
              value={password}
              onChange={setPassword}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleLogin()
              }}
            />
          </Form.Item>
        </Form>
        <Button
          block
          color="primary"
          size="large"
          loading={loading}
          onClick={handleLogin}
          style={{ marginTop: 16, borderRadius: 8 }}
        >
          连接
        </Button>
      </div>
    </div>
  )
}
