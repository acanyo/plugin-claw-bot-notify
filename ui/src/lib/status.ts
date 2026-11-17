export const connectionLabels: Record<string, string> = {
  UNBOUND: '尚未绑定',
  BINDING: '正在绑定',
  AWAITING_ACTIVATION: '等待确认启用',
  AWAITING_MESSAGE: '等待微信互动',
  ACTIVE: '连接可用',
  DEGRADED: '连接需要处理',
  REAUTH_REQUIRED: '需要重新绑定',
  DISABLED: '已在本地断开',
}

export const qrLabels: Record<string, string> = {
  WAITING_SCAN: '请用本人微信扫码',
  SCANNED: '已扫码，请在微信确认',
  NEEDS_CODE: '请输入手机微信显示的数字',
  VERIFYING: '正在核对验证码',
  CONFIRMED: '微信已确认绑定',
  EXPIRED: '二维码已过期，请重新绑定',
  VERIFY_BLOCKED: '验证码尝试过多，请稍后重新绑定',
  ALREADY_BOUND: '这个机器人已有连接，请确认其他客户端已停止使用',
  UNSUPPORTED: '微信返回了尚未支持的绑定状态，请先检查联调记录',
  FAILED: '绑定未完成，请检查连接后重新绑定',
}

export const deliveryLabels: Record<string, string> = {
  PENDING: '等待发送',
  SENDING: '正在发送',
  WAITING_CONTEXT: '等待微信互动',
  ACCEPTED: '接口已接收',
  UNKNOWN: '结果未知，请在微信核查',
  DEAD: '微信拒绝接收',
  CANCELLED: '已取消',
}

const errorMessages: Record<string, string> = {
  CREDENTIAL_RESTORE_FAILED:
    '无法恢复已保存的凭证，收发已停止。请检查存储及原有加密密钥，或重新绑定。',
  CREDENTIAL_KEY_REQUIRED: '服务端尚未配置凭证加密密钥，请按安装说明配置后重启 Halo。',
  HALO_COMMENT_READ_FAILED:
    '暂时无法读取评论。请检查 PAT 有效期及“评论查看”权限；保留任务，恢复权限后重试。',
  SOURCE_UNAVAILABLE: '源评论已删除或已不符合当前通知范围，任务已取消。',
  HALO_AUTH_FAILED: '无法验证 Halo 访问令牌，请检查有效期、权限和本站访问地址。',
  HALO_IDENTITY_MISMATCH: '令牌对应的 Halo 用户与发起绑定的用户不一致，请使用本人令牌。',
  HALO_CREDENTIAL_REQUIRED: '请先保存并验证本人的 Halo 访问令牌。',
  HALO_ADDRESS_INVALID: 'Halo 本站访问地址无效，请检查 Halo 的外部访问地址配置。',
  SUPER_ADMIN_REQUIRED: '此页面仅超级管理员可用。',
  BINDING_OWNER_REQUIRED: '请使用发起绑定的 Halo 账号完成操作。',
  BINDING_TRANSITION_IN_PROGRESS: '连接正在更新，请稍后重试。',
  BINDING_DISABLED: '连接已停用，请重新绑定并启用。',
  QR_SESSION_REQUIRED: '验证会话已失效，请重新绑定。',
  INVALID_VERIFY_CODE: '请输入手机微信显示的数字。',
  WECHAT_UNSAFE_DESTINATION: '微信返回的接口地址尚未通过验证，连接已停止。请核对联调文档。',
  WECHAT_INVALID_RESPONSE: '微信响应格式与已支持的协议不符，连接已暂停。',
  WECHAT_HTTP_REJECTED: '微信接口未接受请求，请检查网络或稍后重新绑定。',
  WECHAT_BUSINESS_REJECTED: '微信拒绝了请求，请在手机检查账号状态后重试。',
  WECHAT_NETWORK: '暂时无法连接微信，请检查 Halo 的网络。',
  WECHAT_TIMEOUT: '微信请求超时，请稍后查看连接状态。',
  DELIVERY_STORAGE_FAILED: '发送记录无法保存，发送已暂停。请检查 Halo 存储，恢复后重新启用连接。',
}

export function errorMessage(code?: string) {
  return code
    ? (errorMessages[code] ?? '连接或记录存储失败。请检查 Halo 运行状态，恢复后重新启用连接。')
    : ''
}

export function deliveryErrorMessage(code?: string) {
  if (code === 'WECHAT_BUSINESS_REJECTED')
    return '微信明确拒绝了此次发送。任务已保留，请给 ClawBot 发送新的私聊后重试。'
  if (code === 'WECHAT_INVALID_RESPONSE')
    return '当时未能确认发送响应，请先在微信核查；此记录不会自动重发。'
  return errorMessage(code)
}

export function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString() : '暂无'
}
export function formatDuration(milliseconds?: number) {
  if (milliseconds == null) return '—'
  return milliseconds < 1000 ? `${milliseconds} ms` : `${(milliseconds / 1000).toFixed(2)} s`
}
