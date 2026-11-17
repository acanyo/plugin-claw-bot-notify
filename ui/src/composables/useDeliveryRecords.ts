import { onMounted, onUnmounted, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { clawBotApi } from '@/api'
import type { DeliveryView } from '@/api/generated'
import { isLoginExpired, loginExpiredMessage } from '@/lib/consoleSession'

export function useDeliveryRecords() {
  const items = ref<DeliveryView[]>([]),
    page = ref(1),
    size = ref(20),
    total = ref(0),
    loading = ref(true),
    error = ref('')
  let sessionExpired = false
  let disposed = false,
    revision = 0
  let request: AbortController | undefined
  let timer: ReturnType<typeof setTimeout> | undefined

  function schedule() {
    clearTimeout(timer)
    if (!disposed && !sessionExpired)
      timer = setTimeout(() => {
        if (!document.hidden) void refresh()
        else schedule()
      }, 10000)
  }

  async function refresh() {
    clearTimeout(timer)
    request?.abort()
    const controller = new AbortController()
    request = controller
    const current = ++revision
    loading.value = true
    try {
      const { data } = await clawBotApi.listClawBotDeliveries(
        { page: page.value, size: size.value },
        { signal: controller.signal },
      )
      if (disposed || current !== revision) return
      sessionExpired = false
      items.value = data.items ?? []
      total.value = data.total ?? 0
      error.value = ''
    } catch (cause) {
      if (!disposed && current === revision && !controller.signal.aborted) {
        if (isLoginExpired(cause)) sessionExpired = true
        error.value = sessionExpired
          ? loginExpiredMessage
          : isAxiosError(cause) && cause.response?.status === 403
            ? '发送记录仅超级管理员可查看。'
            : '暂时无法读取发送记录，请检查网络后重试。'
      }
    } finally {
      if (!disposed && current === revision) {
        loading.value = false
        schedule()
      }
    }
  }

  watch(
    size,
    () => {
      page.value = 1
    },
    { flush: 'sync' },
  )
  watch([page, size], () => void refresh())
  onMounted(() => void refresh())
  onUnmounted(() => {
    disposed = true
    ++revision
    clearTimeout(timer)
    request?.abort()
  })
  return { items, page, size, total, loading, error, refresh }
}
