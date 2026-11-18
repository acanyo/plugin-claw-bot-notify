export const setupStages: Record<string, string> = {
  IDLE: '等待检测',
  DETECTING: '正在检测服务器环境',
  REQUIRED: '等待安装',
  UNSUPPORTED: '环境需要调整',
  DOWNLOADING_NODE: '下载 Node.js 运行版',
  VERIFYING_NODE: '校验 Node.js 文件',
  INSTALLING_NODE: '安装 Node.js',
  CHECKING_NODE: '检查 Node.js 是否可用',
  DOWNLOADING_CLI: '下载 Halo CLI',
  VERIFYING_CLI: '校验 Halo CLI 文件',
  CHECKING_CLI: '检查 Halo CLI 是否可用',
  READY: '准备完成',
  FAILED: '安装未完成',
  EXTERNAL: '使用外部 CLI',
}
export const setupTerminalStates = ['REQUIRED', 'UNSUPPORTED', 'READY', 'FAILED', 'EXTERNAL']
export function downloadSize(bytes?: number) {
  if (!bytes) return '0 B'
  return bytes >= 1024 * 1024
    ? `${(bytes / 1024 / 1024).toFixed(1)} MB`
    : `${Math.ceil(bytes / 1024)} KB`
}
