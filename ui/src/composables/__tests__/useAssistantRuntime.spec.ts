import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { clawBotApi } from '@/api'
import { useAssistantRuntime } from '../useAssistantRuntime'

vi.mock('@/api', () => ({
  clawBotApi: {
    getClawBotAssistantRuntime: vi.fn<typeof clawBotApi.getClawBotAssistantRuntime>(),
    retryClawBotCliSetup: vi.fn<typeof clawBotApi.retryClawBotCliSetup>(),
  },
}))
let wrapper: VueWrapper | undefined
function host() {
  let state!: ReturnType<typeof useAssistantRuntime>
  wrapper = mount(
    defineComponent({
      setup() {
        state = useAssistantRuntime()
        return () => h('div')
      },
    }),
  )
  return state
}
afterEach(() => {
  wrapper?.unmount()
  vi.useRealTimers()
  vi.resetAllMocks()
})
describe('assistant runtime checks', () => {
  it('polls preparation until ready, then stops and cancels on unmount', async () => {
    vi.useFakeTimers()
    vi.mocked(clawBotApi.getClawBotAssistantRuntime)
      .mockResolvedValueOnce({
        data: { cliSetup: { mode: 'MANAGED', state: 'EXTRACTING' } },
      } as never)
      .mockResolvedValueOnce({
        data: { cliSetup: { mode: 'MANAGED', state: 'READY' }, cli: { available: true } },
      } as never)
    const state = host()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(state.status.value?.cli?.available).toBe(true)
    await vi.advanceTimersByTimeAsync(30000)
    expect(clawBotApi.getClawBotAssistantRuntime).toHaveBeenCalledTimes(2)
    vi.mocked(clawBotApi.getClawBotAssistantRuntime).mockResolvedValueOnce({
      data: { cliSetup: { mode: 'MANAGED', state: 'VERIFYING' } },
    } as never)
    await state.refresh()
    wrapper?.unmount()
    await vi.advanceTimersByTimeAsync(30000)
    expect(clawBotApi.getClawBotAssistantRuntime).toHaveBeenCalledTimes(3)
  })
  it('retries failed setup only after the user clicks retry', async () => {
    vi.useFakeTimers()
    vi.mocked(clawBotApi.getClawBotAssistantRuntime).mockResolvedValue({
      data: { cliSetup: { mode: 'MANAGED', state: 'FAILED' } },
    } as never)
    vi.mocked(clawBotApi.retryClawBotCliSetup).mockResolvedValue({
      data: { cliSetup: { mode: 'MANAGED', state: 'EXTRACTING' } },
    } as never)
    const state = host()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(30000)
    expect(clawBotApi.getClawBotAssistantRuntime).toHaveBeenCalledTimes(1)
    expect(clawBotApi.retryClawBotCliSetup).not.toHaveBeenCalled()
    await state.retryCli()
    expect(clawBotApi.retryClawBotCliSetup).toHaveBeenCalledTimes(1)
    expect(state.status.value?.cliSetup?.state).toBe('EXTRACTING')
  })
  it('shows session expiry without repeated automatic requests', async () => {
    vi.useFakeTimers()
    vi.mocked(clawBotApi.getClawBotAssistantRuntime).mockRejectedValue({
      isAxiosError: true,
      response: { status: 401 },
    })
    const state = host()
    await flushPromises()
    expect(state.error.value).toContain('后台登录已失效')
    await vi.advanceTimersByTimeAsync(120000)
    expect(clawBotApi.getClawBotAssistantRuntime).toHaveBeenCalledTimes(1)
  })
  it('prevents concurrent probes and ignores a response after unmount', async () => {
    let resolve!: (value: never) => void
    vi.mocked(clawBotApi.getClawBotAssistantRuntime).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    const state = host()
    void state.refresh()
    expect(clawBotApi.getClawBotAssistantRuntime).toHaveBeenCalledTimes(1)
    wrapper?.unmount()
    resolve({ data: { enabled: true } } as never)
    await flushPromises()
    expect(state.status.value).toBeUndefined()
  })
  it('recovers with a manual check and exposes safe dependency status', async () => {
    vi.mocked(clawBotApi.getClawBotAssistantRuntime)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({
        data: {
          enabled: false,
          model: { available: false, errorCode: 'AI_MODEL_UNAVAILABLE' },
          cli: { available: true },
        },
      } as never)
    const state = host()
    await flushPromises()
    expect(state.error.value).toContain('运行检查失败')
    await state.refresh()
    expect(state.error.value).toBe('')
    expect(state.status.value?.cli?.available).toBe(true)
    expect(state.status.value?.model?.errorCode).toBe('AI_MODEL_UNAVAILABLE')
  })
})
