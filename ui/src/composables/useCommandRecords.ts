import { onMounted, onUnmounted, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { clawBotApi } from '@/api'
import type { CommandDetail, CommandView } from '@/api/generated'
import { isLoginExpired, loginExpiredMessage } from '@/lib/consoleSession'

export function useCommandRecords() {
  const records = ref<CommandView[]>([]),
    page = ref(1),
    size = ref(20),
    total = ref(0)
  const type = ref(''),
    result = ref(''),
    loading = ref(true),
    error = ref('')
  const selectedId = ref(''),
    detail = ref<CommandDetail>(),
    detailLoading = ref(false),
    detailError = ref('')
  let sessionExpired = false
  let disposed = false,
    revision = 0,
    detailRevision = 0
  let listRequest: AbortController | undefined, detailRequest: AbortController | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  function message(cause: unknown) {
    if (isLoginExpired(cause)) {
      sessionExpired = true
      clearTimeout(timer)
      return loginExpiredMessage
    }
    if (isAxiosError(cause) && cause.response?.status === 403)
      return '此内容仅当前绑定的管理员可查看。'
    if (isAxiosError(cause) && cause.response?.status === 404) return '这条指令记录已不存在。'
    return '暂时无法读取记录，请检查网络后重试。'
  }
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
    listRequest?.abort()
    const controller = new AbortController()
    listRequest = controller
    const current = ++revision
    loading.value = true
    try {
      const { data } = await clawBotApi.listClawBotCommands(
        {
          page: page.value,
          size: size.value,
          type: type.value || undefined,
          result: result.value || undefined,
        },
        { signal: controller.signal },
      )
      if (disposed || current !== revision) return
      sessionExpired = false
      records.value = data.items ?? []
      total.value = data.total ?? 0
      error.value = ''
    } catch (cause) {
      if (!disposed && current === revision && !controller.signal.aborted)
        error.value = message(cause)
    } finally {
      if (!disposed && current === revision) {
        loading.value = false
        schedule()
      }
    }
  }
  async function openDetail(id: string) {
    detailRequest?.abort()
    selectedId.value = id
    detail.value = undefined
    detailError.value = ''
    detailLoading.value = true
    const controller = new AbortController()
    detailRequest = controller
    const current = ++detailRevision
    try {
      const { data } = await clawBotApi.getClawBotCommand(
        { name: id },
        { signal: controller.signal },
      )
      if (!disposed && current === detailRevision) detail.value = data
    } catch (cause) {
      if (!disposed && current === detailRevision && !controller.signal.aborted)
        detailError.value = message(cause)
    } finally {
      if (!disposed && current === detailRevision) detailLoading.value = false
    }
  }
  function closeDetail() {
    ++detailRevision
    detailRequest?.abort()
    selectedId.value = ''
    detail.value = undefined
    detailError.value = ''
    detailLoading.value = false
  }
  // Reset before the batched request watcher when Halo emits size and page together.
  watch(
    [size, type, result],
    () => {
      page.value = 1
    },
    { flush: 'sync' },
  )
  watch([page, size, type, result], () => void refresh())
  onMounted(() => void refresh())
  onUnmounted(() => {
    disposed = true
    ++revision
    ++detailRevision
    clearTimeout(timer)
    listRequest?.abort()
    detailRequest?.abort()
  })
  return {
    records,
    page,
    size,
    total,
    type,
    result,
    loading,
    error,
    selectedId,
    detail,
    detailLoading,
    detailError,
    refresh,
    openDetail,
    closeDetail,
  }
}
