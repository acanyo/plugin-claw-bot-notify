import { computed, onMounted, onUnmounted, ref } from 'vue'
import { clawBotApi } from '@/api'
import type { Status } from '@/api/generated'
import { setupTerminalStates } from '@/lib/cliSetup'
import { isLoginExpired, loginExpiredMessage } from '@/lib/consoleSession'

export function useCliSetup() {
  const status = ref<Status>(),
    loading = ref(false),
    error = ref('')
  let disposed = false
  let poll: ReturnType<typeof setTimeout> | undefined
  let controller: AbortController | undefined
  const working = computed(
    () => !!status.value && !setupTerminalStates.includes(status.value.state ?? ''),
  )
  async function request(action: 'status' | 'detect' | 'install' = 'status') {
    if (loading.value) return
    clearTimeout(poll)
    controller = new AbortController()
    loading.value = true
    error.value = ''
    try {
      const options = { signal: controller.signal }
      const { data } =
        action === 'install'
          ? await clawBotApi.installClawBotTools(options)
          : action === 'detect'
            ? await clawBotApi.detectClawBotEnvironment(options)
            : await clawBotApi.getClawBotSetup(options)
      if (!disposed) {
        status.value = data
        if (!setupTerminalStates.includes(data.state ?? ''))
          poll = setTimeout(() => void request(), 1500)
      }
    } catch (cause) {
      if (!disposed && !controller.signal.aborted)
        error.value = isLoginExpired(cause)
          ? loginExpiredMessage
          : '暂时无法读取安装状态，请重新检测。'
    } finally {
      if (!disposed) loading.value = false
    }
  }
  onMounted(() => void request())
  onUnmounted(() => {
    disposed = true
    clearTimeout(poll)
    controller?.abort()
  })
  return {
    status,
    loading,
    working,
    error,
    refresh: () => request(),
    detect: () => request('detect'),
    install: () => request('install'),
  }
}
