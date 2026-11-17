import { computed, onMounted, onUnmounted, ref } from 'vue'
import { isAxiosError } from 'axios'
import { Toast } from '@halo-dev/components'
import { clawBotApi } from '@/api'
import type { BindingView } from '@/api/generated'
import { errorMessage } from '@/lib/status'
import { isLoginExpired, loginExpiredMessage } from '@/lib/consoleSession'

export function useClawBot() {
  const binding = ref<BindingView>()
  const credentialSaveCount = ref(0)
  const loading = ref(true),
    busy = ref(false)
  const actionError = ref('')
  const requestError = ref('')
  const sessionExpired = ref(false)
  const error = computed(() =>
    sessionExpired.value ? loginExpiredMessage : actionError.value || requestError.value,
  )
  let disposed = false,
    revision = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let refreshing: AbortController | undefined
  const actions = new AbortController()

  function failure(cause: unknown, persistent = false) {
    if (isLoginExpired(cause)) {
      sessionExpired.value = true
      clearTimeout(timer)
      return
    }
    const target = persistent ? actionError : requestError
    if (isAxiosError(cause)) {
      if (cause.code === 'ERR_CANCELED') return
      target.value =
        errorMessage(
          cause.response?.data?.code ??
            (cause.response?.status === 403 ? 'SUPER_ADMIN_REQUIRED' : undefined),
        ) || '无法读取连接状态，请检查网络后刷新。'
    } else target.value = '操作未完成，请刷新状态后重试。'
  }

  function schedule() {
    clearTimeout(timer)
    if (!disposed && !sessionExpired.value)
      timer = setTimeout(() => {
        if (!document.hidden && !busy.value) void refresh()
        else schedule()
      }, 3000)
  }

  async function refresh() {
    clearTimeout(timer)
    refreshing?.abort()
    const controller = new AbortController()
    refreshing = controller
    const version = ++revision
    try {
      const current = await clawBotApi.getClawBotBinding(
        { id: 'primary' },
        { signal: controller.signal },
      )
      if (disposed || version !== revision) return
      binding.value = current.data
      sessionExpired.value = false
      requestError.value = ''
    } catch (cause) {
      if (!disposed && version === revision) failure(cause)
    } finally {
      if (!disposed && version === revision) {
        loading.value = false
        schedule()
      }
    }
  }

  async function run(work: () => Promise<unknown>, success: string) {
    if (busy.value || disposed) return
    busy.value = true
    refreshing?.abort()
    ++revision
    clearTimeout(timer)
    actionError.value = ''
    try {
      await work()
      if (!disposed) {
        Toast.success(success)
        await refresh()
      }
    } catch (cause) {
      if (!disposed) failure(cause, true)
    } finally {
      busy.value = false
      schedule()
    }
  }

  const options = { signal: actions.signal }
  const create = () =>
    run(
      () => clawBotApi.createClawBotBinding({ idempotencyKey: crypto.randomUUID() }, options),
      '已创建扫码会话',
    )
  const verifyCode = (code: string) =>
    run(
      () => clawBotApi.verifyClawBotCode({ id: 'primary', verifyCodeRequest: { code } }, options),
      '验证码已提交',
    )
  const saveCredential = (token: string) =>
    run(async () => {
      await clawBotApi.replaceClawBotHaloCredential(
        { id: 'primary', haloCredentialRequest: { token } },
        options,
      )
      ++credentialSaveCount.value
    }, '身份已验证，令牌已保存')
  const activate = () =>
    run(
      () => clawBotApi.activateClawBotBinding({ id: 'primary' }, options),
      '连接已启用，请给 ClawBot 发送“开始”',
    )
  const disconnect = () =>
    run(
      () => clawBotApi.disconnectClawBotBinding({ id: 'primary' }, options),
      '已停止本地连接并清理凭证',
    )
  const testMessage = () =>
    run(
      () =>
        clawBotApi.createClawBotTestMessage(
          { id: 'primary', idempotencyKey: crypto.randomUUID() },
          options,
        ),
      '测试消息已加入发送队列',
    )
  onMounted(() => void refresh())
  onUnmounted(() => {
    disposed = true
    ++revision
    clearTimeout(timer)
    refreshing?.abort()
    actions.abort()
  })
  return {
    binding,
    loading,
    busy,
    credentialSaveCount,
    error,
    refresh,
    create,
    verifyCode,
    saveCredential,
    activate,
    disconnect,
    testMessage,
  }
}
