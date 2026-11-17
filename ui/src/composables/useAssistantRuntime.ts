import { onMounted, onUnmounted, ref } from 'vue'
import { clawBotApi } from '@/api'
import type { AssistantRuntimeView } from '@/api/generated'
import { isLoginExpired, loginExpiredMessage } from '@/lib/consoleSession'

export function useAssistantRuntime() {
  const status = ref<AssistantRuntimeView>(),
    loading = ref(false),
    error = ref('')
  let disposed = false
  let controller: AbortController | undefined
  async function refresh() {
    if (loading.value) return
    controller = new AbortController()
    loading.value = true
    error.value = ''
    try {
      const { data } = await clawBotApi.getClawBotAssistantRuntime({ signal: controller.signal })
      if (!disposed) status.value = data
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
    controller?.abort()
  })
  return { status, loading, error, refresh }
}
