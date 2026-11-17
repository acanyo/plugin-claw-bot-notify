import { afterEach, describe, expect, it } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, reactive, ref } from 'vue'
import type { CommandDetail } from '@/api/generated'
import CommandDetailModal from '../CommandDetailModal.vue'

enableAutoUnmount(afterEach)
afterEach(() => {
  document.body.innerHTML = ''
})

async function fixture() {
  const trigger = document.createElement('button')
  trigger.textContent = '查看详情'
  document.body.append(trigger)
  trigger.focus()
  const open = ref(true)
  const state = reactive<{ loading: boolean; error: string; detail?: CommandDetail }>({
    loading: true,
    error: '',
  })
  const view = mount(
    defineComponent({
      setup: () => () =>
        open.value
          ? h(CommandDetailModal, {
              ...state,
              onClose: () => {
                open.value = false
              },
            })
          : h('p', '已关闭'),
    }),
    { attachTo: document.body, global: { stubs: { RouterLink: true, transition: false } } },
  )
  await flushPromises()
  // Reproduce OverlayScrollbars relocating the native modal's slot nodes.
  const body = document.querySelector('.modal-body')!
  const viewport = document.createElement('div')
  viewport.className = 'test-scroll-viewport'
  viewport.append(...Array.from(body.childNodes))
  body.append(viewport)
  return { view, state, open, body, trigger }
}

async function afterClose() {
  // Halo emits close after its 200 ms leave transition.
  await new Promise((resolve) => setTimeout(resolve, 220))
  await nextTick()
}

describe('command detail with the real Halo modal', () => {
  it('updates async content in a relocated viewport, refreshes, closes and reopens', async () => {
    const { view, state, open, body, trigger } = await fixture()
    expect(body.textContent).toContain('正在读取')
    state.detail = {
      record: { type: 'AI', writeState: 'SUCCEEDED' },
      submittedMarkdown: '帮我写一段文案',
      replyMarkdown: '异步加载的答复',
    }
    state.loading = false
    await nextTick()
    expect(body.textContent).toContain('异步加载的答复')
    state.loading = true
    state.detail = undefined
    await nextTick()
    state.detail = { record: { type: 'AI' }, replyMarkdown: '更新后的答复' }
    state.loading = false
    await nextTick()
    expect(body.textContent).toContain('更新后的答复')
    document.querySelector<HTMLButtonElement>('.detail-actions .cb-primary')!.click()
    await afterClose()
    expect(view.text()).toContain('已关闭')
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(document.activeElement).toBe(trigger)
    open.value = true
    await flushPromises()
    expect(document.querySelector('.modal-body')?.textContent).toContain('更新后的答复')
  })

  it('shows a load error and recovers on a subsequent response in the same viewport', async () => {
    const { view, state, body } = await fixture()
    state.loading = false
    state.error = '此内容仅当前绑定的管理员可查看。'
    await nextTick()
    expect(body.querySelector('[role="alert"]')?.textContent).toContain('当前绑定')
    document.querySelector<HTMLButtonElement>('.detail-actions .cb-button')!.click()
    expect(view.findComponent(CommandDetailModal).emitted('refresh')).toHaveLength(1)
    state.error = ''
    state.loading = true
    await nextTick()
    state.detail = { record: { type: 'AI' }, submittedMarkdown: '<img src=x onerror=bad()>' }
    state.loading = false
    await nextTick()
    expect(body.textContent).toContain('<img src=x onerror=bad()>')
    expect(body.querySelector('img')).toBeNull()
  })

  it.each(['header', 'escape', 'backdrop'] as const)(
    'closes from %s while loading or without a record',
    async (action) => {
      const { state, body } = await fixture()
      if (action === 'escape') {
        state.loading = false
        state.error = '暂时无法读取记录'
        await nextTick()
      }
      if (action === 'backdrop') {
        state.loading = false
        await nextTick()
      }
      expect(body.textContent).toContain(
        state.loading ? '正在读取' : state.error || '暂无可展示的详情',
      )
      if (action === 'header')
        document.querySelector<HTMLButtonElement>('[aria-label="关闭指令详情"]')!.click()
      if (action === 'escape')
        document
          .querySelector('[role="dialog"]')!
          .dispatchEvent(new KeyboardEvent('keyup', { key: 'Escape', bubbles: true }))
      if (action === 'backdrop') document.querySelector<HTMLElement>('.modal-layer')!.click()
      await afterClose()
      expect(document.querySelector('[role="dialog"]')).toBeNull()
    },
  )

  it('keeps keyboard focus within the modal', async () => {
    await fixture()
    const first = document.querySelector<HTMLButtonElement>('[aria-label="关闭指令详情"]')!
    const last = document.querySelector<HTMLButtonElement>('.detail-actions .cb-primary')!
    first.focus()
    first.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }),
    )
    expect(document.activeElement).toBe(last)
    last.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }),
    )
    expect(document.activeElement).toBe(first)
  })
})
