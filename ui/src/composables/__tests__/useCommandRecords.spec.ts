import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { useCommandRecords } from '../useCommandRecords'
import { clawBotApi } from '@/api'
vi.mock('@/api', () => ({
  clawBotApi: {
    listClawBotCommands: vi.fn<typeof clawBotApi.listClawBotCommands>(),
    getClawBotCommand: vi.fn<typeof clawBotApi.getClawBotCommand>(),
  },
}))
let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  vi.clearAllMocks()
})
function host() {
  let records!: ReturnType<typeof useCommandRecords>
  wrapper = mount(
    defineComponent({
      setup() {
        records = useCommandRecords()
        return () => h('div')
      },
    }),
  )
  return records
}
describe('command history requests', () => {
  it('uses the selected page size and loads page one once when pagination changes both', async () => {
    vi.mocked(clawBotApi.listClawBotCommands).mockResolvedValue({
      data: { items: [], total: 80 },
    } as never)
    const records = host()
    await flushPromises()
    records.page.value = 3
    await flushPromises()
    vi.mocked(clawBotApi.listClawBotCommands).mockClear()

    // Halo's pagination emits both updates when choosing a page size.
    records.size.value = 50
    records.page.value = 1
    await flushPromises()
    expect(clawBotApi.listClawBotCommands).toHaveBeenCalledTimes(1)
    expect(clawBotApi.listClawBotCommands).toHaveBeenCalledWith(
      { page: 1, size: 50, type: undefined, result: undefined },
      expect.anything(),
    )
  })
  it('resets to page one when a result filter changes', async () => {
    vi.mocked(clawBotApi.listClawBotCommands).mockResolvedValue({
      data: { items: [], total: 0 },
    } as never)
    const records = host()
    await flushPromises()
    records.page.value = 3
    await flushPromises()
    records.result.value = 'NEEDS_REVIEW'
    await flushPromises()
    expect(records.page.value).toBe(1)
    expect(clawBotApi.listClawBotCommands).toHaveBeenLastCalledWith(
      { page: 1, size: 20, type: undefined, result: 'NEEDS_REVIEW' },
      expect.anything(),
    )
  })
  it('ignores an older detail response after selecting another record', async () => {
    vi.mocked(clawBotApi.listClawBotCommands).mockResolvedValue({
      data: { items: [], total: 0 },
    } as never)
    let finishOld!: (value: unknown) => void
    vi.mocked(clawBotApi.getClawBotCommand)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishOld = resolve
          }) as never,
      )
      .mockResolvedValueOnce({ data: { submittedMarkdown: 'new body' } } as never)
    const records = host()
    void records.openDetail('one')
    await records.openDetail('two')
    finishOld({ data: { submittedMarkdown: 'old private body' } })
    await flushPromises()
    expect(records.detail.value?.submittedMarkdown).toBe('new body')
    expect(records.selectedId.value).toBe('two')
  })
  it('does not repopulate closed details when a response arrives late', async () => {
    vi.mocked(clawBotApi.listClawBotCommands).mockResolvedValue({
      data: { items: [], total: 0 },
    } as never)
    let finish!: (value: unknown) => void
    vi.mocked(clawBotApi.getClawBotCommand).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }) as never,
    )
    const records = host()
    void records.openDetail('one')
    records.closeDetail()
    finish({ data: { submittedMarkdown: 'private body' } })
    await flushPromises()
    expect(records.detail.value).toBeUndefined()
    expect(records.selectedId.value).toBe('')
    expect(records.detailLoading.value).toBe(false)
  })
})
