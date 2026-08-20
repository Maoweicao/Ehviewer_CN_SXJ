import { SpinLoading } from 'antd-mobile'

interface FullScreenLoadingProps {
  text?: string
}

export default function FullScreenLoading({ text = '加载中...' }: FullScreenLoadingProps) {
  return (
    <div className="fullscreen-loading">
      <SpinLoading style={{ '--size': '48px', '--color': 'var(--primary)' }} />
      <span>{text}</span>
    </div>
  )
}
