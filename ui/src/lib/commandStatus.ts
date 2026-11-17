import type { CommandView } from '@/api/generated'

export const commandTypes: Record<string, string> = {
  AI: 'AI 对话',
  AI_RESET: '开始新对话',
  AI_MEDIA: '接收微信图片',
  AI_PREVIEW: '查看文章预览',
  AI_CONFIRM: '确认内容操作',
  AI_APPROVE: '确认审核并回复',
  AI_CANCEL: '取消预览',
  START: '开始',
  HELP: '帮助',
  PENDING: '待处理列表',
  VIEW: '查看评论',
  REPLY: '发布回复',
  APPROVE_REPLY: '审核并回复',
  MARK_HANDLED: '标记已处理',
  IGNORE: '忽略评论',
  QUOTE_UNRESOLVED: '引用无法定位',
  INVALID: '无效指令',
  UNSUPPORTED: '不支持的指令',
}
export const processingStates: Record<string, string> = {
  OPEN: '待处理',
  ANSWERED: '已处理',
  IGNORED: '已忽略',
  DELETED: '已删除',
}
const writeResults: Record<string, string> = {
  READY: '等待处理',
  EXECUTING: '执行结果待核查',
  SUCCEEDED: '回复已发布',
  AWAITING_APPROVAL: '等待审核确认',
  REJECTED: '未执行成功',
  UNKNOWN: '网站结果未知',
  PARTIAL: '部分结果待核查',
}
export function commandTone(record: CommandView) {
  if (
    record.reviewRequired ||
    ['REJECTED', 'UNKNOWN', 'PARTIAL'].includes(record.writeState ?? '') ||
    ['INVALID', 'UNSUPPORTED', 'QUOTE_UNRESOLVED'].includes(record.type ?? '')
  )
    return 'danger'
  if (record.writeState === 'AWAITING_APPROVAL') return 'attention'
  if (record.writeState === 'SUCCEEDED') return 'success'
  return undefined
}
export function commandResult(record: CommandView) {
  if (record.writeState === 'AWAITING_APPROVAL' && record.type === 'AI') return '等待预览确认'
  if (record.writeState === 'SUCCEEDED' && (record.type ?? '').startsWith('AI')) return '处理完成'
  if (record.type === 'QUOTE_UNRESOLVED') return '引用未定位'
  if (record.type === 'INVALID') return '指令格式错误'
  if (record.type === 'UNSUPPORTED') return '指令暂不支持'
  if (record.writeState === 'SUCCEEDED' && record.type === 'IGNORE') return '已忽略'
  if (record.writeState === 'SUCCEEDED' && record.type === 'MARK_HANDLED') return '已标记处理'
  return (
    writeResults[record.writeState ?? ''] ??
    (record.state === 'QUEUED' ? '已接收读取指令' : '等待处理')
  )
}
const explanations: Record<string, string> = {
  AI_PREVIEW_ALREADY_USED: '这份预览已经确认、取消或被修改，请使用最新预览。',
  AI_PREVIEW_REQUIRED: '请引用最新的待确认预览，再发送其中的确认指令。',
  AI_PREVIEW_UNAVAILABLE: '预览不属于当前连接或已不可读取，请重新生成。',
  AI_PREVIEW_EXPIRED: '预览已过期，请重新生成后确认。',
  AI_CONFIRMATION_MISMATCH: '确认指令与预览操作不一致，请使用预览中给出的确认指令。',
  AI_PREVIEW_TOO_LONG: '文章正文需为 1 到 30,000 字；瞬间和评论最多 2,000 字。',
  AI_PREVIEW_PAGE_INVALID: '这个预览没有该分段，请按实际段数查看。',
  AI_EDIT_TARGET_AMBIGUOUS: '要替换的片段不存在或出现多次，请提供唯一的精确原文。',
  AI_PREVIEW_REVISION_UNSUPPORTED: '更换图片或封面目标时，请取消当前预览后重新选择。',
  MEDIA_EXPIRED: '微信图片已过期或不属于当前连接，请重新发送。',
  MEDIA_PERMISSION_DENIED: '绑定 PAT 缺少附件、文章或瞬间操作权限，请补充权限后重新发起。',
  MEDIA_TARGET_CHANGED: '目标文章在预览后发生变化，请重新生成封面预览。',
  MEDIA_TARGET_UNAVAILABLE: '无法读取目标内容，请检查资源是否存在及相关插件是否启用。',
  MEDIA_TOO_LARGE: '每张图片最多 10 MiB，请压缩后重新发送。',
  MEDIA_IMAGE_INVALID: '图片无法读取或像素过大，请发送有效图片。',
  MEDIA_IMAGE_UNSUPPORTED: '当前支持 PNG、JPEG、GIF 图片。',
  MEDIA_COUNT_LIMIT: '一次最多接收 9 张图片，请分批发送。',
  MEDIA_CACHE_FULL: '图片暂存空间已满，请稍后重试。',
  MEDIA_RESULT_UNKNOWN: '图片操作结果未确认，请先到附件或内容管理核查。',
  AI_PROPOSAL_ALREADY_STAGED: '每条消息只支持一份预览，请拆分操作。',
  AI_DRAFT_CHANGED: '草稿在预览后已被改动，请重新读取和生成预览。',
  AI_POST_NOT_DRAFT: '该文章已发布或不再是可修改的草稿，未执行。',
  AI_SUMMARY_POST_UNAVAILABLE: '总结仅支持已发布的公开文章；请检查文章是否被删除或存在未发布修改。',
  AI_SUMMARY_LINK_UNAVAILABLE: '未能读取有效的原文链接，可关闭附带链接后重新生成预览。',
  AI_SUMMARY_FORMAT_INVALID: 'AI 未返回完整的摘要和寄语，本次没有生成预览，可重新发起。',
  AI_SUMMARY_SOURCE_INVALID: '文章数据或发布时间不完整，定时总结已停止，请检查数据或重新发起。',
  AI_SUMMARY_SCHEDULE_DISABLED: '定时总结设置已关闭或变更，本次任务已停止。',
  AI_SUMMARY_INTERRUPTED: '定时总结被重启中断，未自动重试，可在微信手动发起。',
  AI_DRAFT_CONTENT_UNAVAILABLE: '无法完整读取草稿正文，请先到文章管理检查。',
  AI_COMMENT_UNAVAILABLE: '被引用的评论已删除或暂时无法读取。',
  AI_STEP_LIMIT: '旧版指令在工具阶段停止，未生成最终答复；请重新发送请求。',
  AI_CONTEXT_LIMIT: '请求达到设置的上下文上限，可调高容量或新建对话。',
  AI_CONTEXT_INVALID: '上下文数据无法读取，请新建对话后重试。',
  AI_OUTPUT_LIMIT: '旧版指令达到输出上限，答复未完成；请重新发送请求。',
  AI_PROVIDER_OUTPUT_LIMIT: '模型供应商截断了输出，可以要求继续生成。',
  AI_GENERATION_INCOMPLETE: '模型提前结束，本次答复未完成。',
  AI_EMPTY_RESULT: '模型未生成最终文字答复，请检查模型配置。',
  AI_MODEL_UNAVAILABLE: '没有可用的语言模型，请到 AI Foundation 配置模型。',
  HISTORY_CLEANED: '正文已按保留期限自动清理，执行状态和防重复记录保留。',
  AI_DISABLED: '请在插件设置中启用智能助手。',
  AI_WRITES_DISABLED: '当前仅允许对话与查询，请在插件设置中开启 AI 写操作。',
  AI_SETTINGS_INVALID: '智能助手配置读取失败，请检查插件设置。',
  AI_BODY_INVALID: 'AI 消息不能为空。',
  AI_REQUEST_FAILED: '模型请求失败，请检查 AI Foundation 的模型配置。',
  HALO_MOMENT_AUTHOR_REQUIRED: '请先在智能助手设置中选择瞬间发布用户，再重新生成发布预览。',
  HALO_MOMENT_AUTHOR_UNAVAILABLE: '所选发布用户已不存在或正在删除，请重新选择用户并生成预览。',
  HALO_BOT_AUTHOR_UNCONFIRMED: '瞬间作者尚未核实，请到瞬间管理核查，避免重复发布。',
  AI_FOUNDATION_UNAVAILABLE: 'AI Foundation 未启用，请先安装并配置模型。',
  AI_TOOL_FAILED: '部分工具调用失败，请核查答复和站点内容。',
  AI_BATCH_READ_ONLY: '批量查询只允许读取，请把写操作单独发起预览。',
  CLI_NOT_AVAILABLE: 'Halo 运行环境中未找到 CLI，请检查安装路径。',
  CLI_SITE_MISMATCH: 'CLI 专用 profile 与当前站点地址不一致，未执行操作。',
  CLI_RESULT_UNKNOWN: 'CLI 操作结果未确认，请到对应管理页面核查，避免重复执行。',
  CLI_FAILED: 'CLI 执行失败，请检查具体命令、专用 profile、账号权限及依赖插件。',
  CLI_HELP_UNAVAILABLE: '无法读取已安装 CLI 的命令帮助，请检查 CLI 环境。',
  CLI_COMMAND_INVALID: '当前 CLI 没有这个命令，请重新查询命令帮助。',
  CLI_OPTION_INVALID: '命令参数与当前 CLI 不符，请按帮助重新生成。',
  CLI_PROFILE_MISMATCH: '站点操作必须使用本站专用 profile。',
  CLI_CREDENTIAL_PLACEHOLDER: '敏感参数已隐藏，无法用占位符重建命令，请重新生成预览。',
  CLI_CONTRACT_CHANGED: 'CLI 命令定义已变化，请重新生成预览。',
  CLI_INPUT_TOO_LARGE: 'CLI 参数和标准输入合计最多 262,144 个字符。',
  CLI_TIMEOUT: 'CLI 执行超时，请核查站点内容后再决定是否重新发送。',
  HALO_WRITE_FORBIDDEN: 'PAT 缺少评论管理权限或已失效，请更新令牌后重新发起指令。',
  HALO_READ_FORBIDDEN: 'PAT 无法读取目标评论，请检查评论查看权限。',
  HALO_WRITE_CONTENT_REJECTED: 'Halo 拒绝了正文格式或内容，未发布回复。',
  HALO_WRITE_FAILED: 'Halo 拒绝了发布请求，未发布回复。',
  HALO_WRITE_UNKNOWN: '网站可能已保存回复，请先在评论管理中核查，避免重复发布。',
  STOPPED_DURING_WRITE: '执行期间连接停止，无法确认网站结果，请先核查。',
  COMMAND_EXPIRED: '指令已超过有效期，未执行。请重新引用评论操作。',
  REPLY_BODY_INVALID: '回复正文不能为空且最多 2,000 字。',
  DRAFT_ALREADY_CONFIRMED: '这份草稿已经确认过，未重复发布。',
  COMMENT_DELETED: '评论已删除或不在当前连接范围内。',
  APPROVAL_REQUIRED: '正文已保存，等待在微信引用确认提示发送“审核并回复”。',
  BINDING_CHANGED: '连接已经更换，此指令未执行。',
  BINDING_DISABLED: '连接已停用，无法读取站点内容。',
  HISTORICAL_GENERATION: '这是之前连接的记录，仅展示执行状态，不使用当前连接读取旧正文。',
  BODY_UNAVAILABLE: '保存的指令正文暂时无法解密或读取，执行记录仍保留。',
  HALO_CONTENT_UNAVAILABLE: '暂时无法读取站点内容，请检查 PAT 权限及站点连接。',
  REPLY_DELETED: '记录中的回复目前无法找到，原执行结果保留。',
  HALO_ADMIN_REQUIRED: '绑定账号已不具备管理员身份。',
  HALO_AUTH_FAILED: '无法验证绑定的访问令牌，请检查有效期及站点连接。',
}
export function commandExplanation(code?: string) {
  return code ? (explanations[code] ?? '操作未完成，请检查访问权限和站点状态。') : ''
}
