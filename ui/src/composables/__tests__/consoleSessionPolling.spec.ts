import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, type Ref } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { useClawBot } from '../useClawBot'
import { useCommandRecords } from '../useCommandRecords'
import { useDeliveryRecords } from '../useDeliveryRecords'
import { clawBotApi } from '@/api'

vi.mock('@halo-dev/components', () => ({ Toast: { success: vi.fn<(message: string) => void>() } }))
vi.mock('@/api', () => ({
  clawBotApi: {
    getClawBotBinding: vi.fn<typeof clawBotApi.getClawBotBinding>(),
    activateClawBotBinding: vi.fn<typeof clawBotApi.activateClawBotBinding>(),
    listClawBotCommands: vi.fn<typeof clawBotApi.listClawBotCommands>(),
    getClawBotCommand: vi.fn<typeof clawBotApi.getClawBotCommand>(),
    listClawBotDeliveries: vi.fn<typeof clawBotApi.listClawBotDeliveries>(),
  },
}))

let wrapper: VueWrapper | undefined
function host<T>(use: () => T) {
  let state!: T
  wrapper = mount(
    defineComponent({
      setup() {
        state = use()
        return () => h('div')
      },
    }),
  )
  return state
}
function failure(status: number) {
  return { isAxiosError: true, response: { status } }
}
afterEach(() => {
  wrapper?.unmount()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.resetAllMocks()
})

type PollingState = { error: Ref<string>; refresh: () => Promise<void> }
const pages = [
  { name: 'connection', use: useClawBot, request: clawBotApi.getClawBotBinding, interval: 3000 },
  {
    name: 'commands',
    use: useCommandRecords,
    request: clawBotApi.listClawBotCommands,
    interval: 10000,
  },
  {
    name: 'deliveries',
    use: useDeliveryRecords,
    request: clawBotApi.listClawBotDeliveries,
    interval: 10000,
  },
]
describe.each(pages)('$name session expiry', ({ use, request, interval }) => {
  it('pauses after 401 and resumes only after a successful manual refresh', async () => {
    vi.useFakeTimers()
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    const api = vi.mocked(request).mockRejectedValue(failure(401))
    const state = host<PollingState>(use)
    await flushPromises()
    expect(state.error.value).toContain('后台登录已失效')
    await vi.advanceTimersByTimeAsync(60000)
    expect(api).toHaveBeenCalledTimes(1)

    api.mockRejectedValue(failure(503))
    await state.refresh()
    await vi.advanceTimersByTimeAsync(60000)
    expect(api).toHaveBeenCalledTimes(2)

    api.mockResolvedValue({ data: {} } as never)
    await state.refresh()
    expect(state.error.value).toBe('')
    await vi.advanceTimersByTimeAsync(interval)
    expect(api).toHaveBeenCalledTimes(4)
  })
  it('continues polling after a temporary server error', async () => {
    vi.useFakeTimers()
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    const api = vi.mocked(request).mockRejectedValue(failure(503))
    host<PollingState>(use)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(interval)
    expect(api).toHaveBeenCalledTimes(2)
  })
})

it('pauses connection polling when an action discovers an expired session', async () => {
  vi.useFakeTimers()
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.mocked(clawBotApi.getClawBotBinding).mockResolvedValue({ data: {} } as never)
  vi.mocked(clawBotApi.activateClawBotBinding).mockRejectedValue(failure(401))
  const state = host(useClawBot)
  await flushPromises()
  await state.activate()
  expect(state.error.value).toContain('后台登录已失效')
  await vi.advanceTimersByTimeAsync(60000)
  expect(clawBotApi.getClawBotBinding).toHaveBeenCalledTimes(1)
  await state.refresh()
  expect(state.error.value).toBe('')
})

it('pauses command polling when opening details returns 401', async () => {
  vi.useFakeTimers()
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.mocked(clawBotApi.listClawBotCommands).mockResolvedValue({ data: {} } as never)
  vi.mocked(clawBotApi.getClawBotCommand).mockRejectedValue(failure(401))
  const state = host(useCommandRecords)
  await flushPromises()
  await state.openDetail('record-one')
  expect(state.detailError.value).toContain('后台登录已失效')
  await vi.advanceTimersByTimeAsync(60000)
  expect(clawBotApi.listClawBotCommands).toHaveBeenCalledTimes(1)
})
