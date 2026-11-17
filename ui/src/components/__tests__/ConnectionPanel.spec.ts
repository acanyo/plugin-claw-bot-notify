import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defaultConfig, plugin } from '@formkit/vue'
import { disablesChildren, formInput, forms, message, messages } from '@formkit/inputs'
import ConnectionPanel from '../ConnectionPanel.vue'
import type { BindingView } from '@/api/generated'

// The host component bundle needs Halo's shared runtime. Keep its button contract
// here while exercising real FormKit rendering, validation and submission.
vi.mock('@halo-dev/components', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    VButton: defineComponent({
      props: ['disabled', 'loading', 'type'],
      emits: ['click'],
      setup:
        (props, { slots, emit }) =>
        () =>
          h(
            'button',
            { type: 'button', disabled: props.disabled, onClick: () => emit('click') },
            slots.default?.(),
          ),
    }),
    VCard: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('section', slots.default?.()),
    }),
    Dialog: { warning: vi.fn<(options: unknown) => void>() },
  }
})

// Halo 2.27's form input deliberately omits FormKit's automatic submit action.
// Use its actual schema and FormKit version so tests exercise the host behavior.
const haloForm = {
  schema: formInput('$slots.default', messages(message('$message.value'))),
  type: 'group' as const,
  props: ['actions', 'submit', 'submitLabel', 'submitAttrs', 'submitBehavior', 'incompleteMessage'],
  forceTypeProp: 'form',
  features: [forms, disablesChildren],
}

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})

function panel(binding: BindingView = {}, busy = false) {
  wrapper = mount(ConnectionPanel, {
    attachTo: document.body,
    props: {
      binding: {
        connectionState: 'AWAITING_ACTIVATION',
        botIdentity: 'test-bot',
        haloUsername: 'admin',
        credentialStorageReady: true,
        ...binding,
      },
      busy,
      credentialSaveCount: 0,
    },
    global: {
      plugins: [[plugin, defaultConfig({ inputs: { form: haloForm } })]],
      stubs: {
        HaloCredentialHelp: true,
      },
    },
  })
  return wrapper
}

async function settle() {
  // Allow FormKit's input debounce and validation promises to finish.
  await new Promise((resolve) => setTimeout(resolve, 40))
  await flushPromises()
}

function button(view: VueWrapper, label: string) {
  const found = view.findAll('button').find((candidate) => candidate.text() === label)
  expect(found, `Expected visible button: ${label}`).toBeDefined()
  return found!
}

describe('connection forms in Halo', () => {
  it('offers a save button and submits the entered credential', async () => {
    const view = panel()
    await view.get('input[name="token"]').setValue('pat-test-user')
    await settle()
    await button(view, '验证并保存令牌').trigger('click')
    await settle()
    expect(view.emitted('credential')).toEqual([['pat-test-user']])

    // A failed request must leave the value available for another attempt.
    expect((view.get('input[name="token"]').element as HTMLInputElement).value).toBe(
      'pat-test-user',
    )
    await view.setProps({ credentialSaveCount: 1 })
    await settle()
    expect((view.get('input[name="token"]').element as HTMLInputElement).value).toBe('')
  })

  it('validates an empty credential before submitting', async () => {
    const view = panel()
    await button(view, '验证并保存令牌').trigger('click')
    await settle()
    expect(view.emitted('credential')).toBeUndefined()
  })

  it('prevents repeat submissions while a request is in progress', () => {
    const view = panel({}, true)
    expect((button(view, '验证并保存令牌').element as HTMLButtonElement).disabled).toBe(true)
  })

  it.each(['1', '123456', '123456789012'])('submits a valid PIN %s', async (code) => {
    const view = panel({ botIdentity: undefined, qrState: 'NEEDS_CODE' })
    await view.get('input[name="code"]').setValue(code)
    await settle()
    await button(view, '核对验证码').trigger('click')
    await settle()
    expect(view.emitted('code')).toEqual([[code]])
  })

  it.each(['12a', '1234567890123'])('rejects an invalid PIN %s', async (code) => {
    const view = panel({ botIdentity: undefined, qrState: 'NEEDS_CODE' })
    await view.get('input[name="code"]').setValue(code)
    await settle()
    await button(view, '核对验证码').trigger('click')
    await settle()
    expect(view.emitted('code')).toBeUndefined()
  })
})
