import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { clawBotApi } from '@/api'
import { useCliSetup } from '../useCliSetup'
vi.mock('@/api', () => ({
  clawBotApi: {
    getClawBotSetup: vi.fn<typeof clawBotApi.getClawBotSetup>(),
    detectClawBotEnvironment: vi.fn<typeof clawBotApi.detectClawBotEnvironment>(),
    installClawBotTools: vi.fn<typeof clawBotApi.installClawBotTools>(),
  },
}))
let wrapper: VueWrapper | undefined
function host() {
  let state!: ReturnType<typeof useCliSetup>
  wrapper = mount(
    defineComponent({
      setup() {
        state = useCliSetup()
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
describe('environment setup', () => {
  it('only reads status on entry and stops polling while waiting for the install click', async () => {
    vi.useFakeTimers()
    vi.mocked(clawBotApi.getClawBotSetup).mockResolvedValue({
      data: { state: 'REQUIRED' },
    } as never)
    host()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(60000)
    expect(clawBotApi.getClawBotSetup).toHaveBeenCalledTimes(1)
    expect(clawBotApi.installClawBotTools).not.toHaveBeenCalled()
  })
  it('polls installation progress and stops at ready', async () => {
    vi.useFakeTimers()
    vi.mocked(clawBotApi.getClawBotSetup)
      .mockResolvedValueOnce({ data: { state: 'REQUIRED' } } as never)
      .mockResolvedValueOnce({
        data: { state: 'DOWNLOADING_NODE', progress: { downloadedBytes: 12 } },
      } as never)
      .mockResolvedValueOnce({ data: { state: 'READY' } } as never)
    vi.mocked(clawBotApi.installClawBotTools).mockResolvedValue({
      data: { state: 'DETECTING' },
    } as never)
    const state = host()
    await flushPromises()
    await state.install()
    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(state.status.value?.progress?.downloadedBytes).toBe(12)
    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(state.status.value?.state).toBe('READY')
    await vi.advanceTimersByTimeAsync(60000)
    expect(clawBotApi.getClawBotSetup).toHaveBeenCalledTimes(3)
    expect(clawBotApi.installClawBotTools).toHaveBeenCalledTimes(1)
  })
  it('does not retry failed downloads or expired sessions automatically', async () => {
    vi.useFakeTimers()
    vi.mocked(clawBotApi.getClawBotSetup).mockResolvedValue({ data: { state: 'FAILED' } } as never)
    const state = host()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(60000)
    expect(clawBotApi.getClawBotSetup).toHaveBeenCalledTimes(1)
    vi.mocked(clawBotApi.detectClawBotEnvironment).mockRejectedValue({
      isAxiosError: true,
      response: { status: 401 },
    })
    await state.detect()
    await vi.advanceTimersByTimeAsync(60000)
    expect(state.error.value).toContain('后台登录已失效')
    expect(clawBotApi.installClawBotTools).not.toHaveBeenCalled()
  })
  it('cancels the pending request and polling when leaving the page', async () => {
    vi.useFakeTimers()
    vi.mocked(clawBotApi.getClawBotSetup).mockResolvedValue({
      data: { state: 'DOWNLOADING_CLI' },
    } as never)
    host()
    await flushPromises()
    const options = vi.mocked(clawBotApi.getClawBotSetup).mock.calls[0]?.[0]
    wrapper?.unmount()
    await vi.advanceTimersByTimeAsync(60000)
    expect(options?.signal?.aborted).toBe(true)
    expect(clawBotApi.getClawBotSetup).toHaveBeenCalledTimes(1)
  })
})
