import { onMounted, onUnmounted, ref } from 'vue'
import { clawBotApi } from '@/api'
import type { AssistantRuntimeView } from '@/api/generated'
import { setupTerminalStates } from '@/lib/cliSetup'
import { isLoginExpired, loginExpiredMessage } from '@/lib/consoleSession'

export function useAssistantRuntime() {
  const status = ref<AssistantRuntimeView>(),
    loading = ref(false),
    error = ref('')
  let disposed = false
  let controller: AbortController | undefined
  let poll: ReturnType<typeof setTimeout> | undefined
  async function refresh(retry = false) {
    if (loading.value) return
    clearTimeout(poll)
    controller = new AbortController()
    loading.value = true
    error.value = ''
    try {
      const options = { signal: controller.signal }
      const { data } = retry
        ? await clawBotApi.retryClawBotCliSetup(options)
        : await clawBotApi.getClawBotAssistantRuntime(options)
      if (!disposed) {
        status.value = data
        if (
          data.cliSetup?.mode === 'MANAGED' &&
          (!setupTerminalStates.includes(data.cliSetup.state ?? '') ||
            data.cli?.errorCode === 'CLI_PREPARING')
        )
          poll = setTimeout(() => void refresh(), 3000)
      }
    } catch (cause) {
      if (!disposed && !controller.signal.aborted)
        error.value = isLoginExpired(cause) ? loginExpiredMessage : '运行检查失败，请稍后重新检查。'
    } finally {
      if (!disposed) loading.value = false
    }
  }
  onMounted(() => void refresh())
  onUnmounted(() => {
    disposed = true
    clearTimeout(poll)
    controller?.abort()
  })
  return { status, loading, error, refresh: () => refresh(), retryCli: () => refresh(true) }
}
