import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import CommandDetailModal from '../CommandDetailModal.vue'
vi.mock('@halo-dev/components', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    VModal: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', [slots.default?.(), slots.footer?.()]),
    }),
    VLoading: defineComponent({ setup: () => () => h('div', '加载中') }),
    VButton: defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.()),
    }),
  }
})
function modal(detail: object) {
  return mount(CommandDetailModal, {
    props: { detail, loading: false, error: '' },
    global: { stubs: { RouterLink: true } },
  })
}
describe('command detail presentation', () => {
  it('shows AI output and content review guidance without labelling it a comment reply', () => {
    const view = modal({
      record: { type: 'AI', writeState: 'UNKNOWN', reviewRequired: true },
      replyMarkdown: '<script>unsafe()</script>资源：clawbot-created',
    })
    expect(view.text()).toContain('AI 答复')
    expect(view.text()).toContain('对应管理页面')
    expect(view.text()).not.toContain('网站当前保存的回复')
    expect(view.find('script').exists()).toBe(false)
    view.unmount()
  })

  it('shows model and operation timing with a concrete failure code', () => {
    const view = modal({
      record: {
        type: 'AI_CONFIRM',
        writeState: 'REJECTED',
        modelName: '语言模型',
        durationMs: 1250,
        modelDurationMs: 1000,
        cliDurationMs: 200,
        errorCode: 'AI_DRAFT_CHANGED',
      },
    })
    expect(view.text()).toContain('1250 ms')
    expect(view.text()).toContain('1000 ms')
    expect(view.text()).toContain('200 ms')
    expect(view.text()).toContain('AI_DRAFT_CHANGED')
    expect(view.text()).toContain('草稿在预览后已被改动')
    view.unmount()
  })

  it('keeps successful website writes separate from unknown receipt delivery', () => {
    const view = modal({
      record: { type: 'REPLY', writeState: 'SUCCEEDED', receiptState: 'UNKNOWN' },
    })
    expect(view.text()).toContain('回复已发布')
    expect(view.text()).toContain('结果未知，请在微信核查')
    expect(view.text()).not.toContain('网站结果尚未确认')
    view.unmount()
  })
  it('renders stored Markdown and source text without executing HTML', () => {
    const view = modal({
      record: { type: 'REPLY', writeState: 'SUCCEEDED' },
      submittedMarkdown: '<img src=x onerror=alert(1)>',
      commentMarkdown: '<script>bad()</script>',
      replyMarkdown: '**网站回复**',
    })
    expect(view.find('img').exists()).toBe(false)
    expect(view.find('script').exists()).toBe(false)
    expect(view.text()).toContain('<img src=x onerror=alert(1)>')
    expect(view.text()).toContain('**网站回复**')
    view.unmount()
  })
  it('provides a review warning without a publish or retry action', () => {
    const view = modal({
      record: {
        type: 'APPROVE_REPLY',
        writeState: 'PARTIAL',
        reviewRequired: true,
        commentName: 'a',
      },
    })
    expect(view.text()).toContain('先到评论管理核查')
    expect(view.text()).not.toContain('重新发布')
    expect(view.findAll('button').map((button) => button.text())).toEqual(['重新读取', '关闭'])
    view.unmount()
  })
  it('separates the selected nested reply from root context and published response', () => {
    const view = modal({
      record: {
        type: 'REPLY',
        targetReplyName: 'visitor-child',
        commentName: 'root',
        replyName: 'answer',
      },
      commentMarkdown: '原始主评论',
      targetReplyMarkdown: '<img src=x onerror=alert(1)>访客追问',
      replyMarkdown: '管理员答复',
    })
    expect(view.text()).toContain('目标楼中楼')
    expect(view.findAll('pre').map((item) => item.text())).toEqual([
      '原始主评论',
      '<img src=x onerror=alert(1)>访客追问',
      '管理员答复',
    ])
    expect(view.find('img').exists()).toBe(false)
    view.unmount()
  })
  it('shows every article part without truncation and renders complete changes as text', async () => {
    const view = modal({
      record: { type: 'AI', writeState: 'AWAITING_APPROVAL' },
      preview: {
        tool: 'post_update_draft',
        title: '新标题',
        beforeTitle: '旧标题',
        version: 'abcdef123456',
        characters: 2500,
        parts: ['文章首段', '完整末尾<script>unsafe()</script>'],
        changes: [
          { kind: 'REMOVED', text: '旧正文' },
          { kind: 'ADDED', text: '新增完整正文<img src=x>' },
        ],
        confirmation: '确认全文保存 abcdef123456',
      },
    })
    expect(view.text()).toContain('2500 字')
    expect(view.text()).toContain('文章首段')
    const next = view.findAll('button').find((button) => button.text() === '下一段')!
    await next.trigger('click')
    expect(view.text()).toContain('完整末尾<script>unsafe()</script>')
    expect(view.find('script').exists()).toBe(false)
    expect(next.attributes('disabled')).toBeDefined()
    await view
      .findAll('button')
      .find((button) => button.text() === '修改差异')!
      .trigger('click')
    expect(view.text()).toContain('旧标题 → 新标题')
    expect(view.text()).toContain('旧正文')
    expect(view.text()).toContain('新增完整正文<img src=x>')
    expect(view.find('img').exists()).toBe(false)
    expect(view.text()).toContain('确认全文保存 abcdef123456')
    view.unmount()
  })
})
