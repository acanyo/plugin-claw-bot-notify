import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import CliSetupGuide from '../CliSetupGuide.vue'
vi.mock('@halo-dev/components', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    VButton: defineComponent({
      props: ['disabled', 'loading'],
      emits: ['click'],
      setup:
        (props, { slots, emit }) =>
        () =>
          h(
            'button',
            { disabled: props.disabled, onClick: () => emit('click') },
            slots.default?.(),
          ),
    }),
    VCard: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('section', slots.default?.()),
    }),
    VLoading: defineComponent({ setup: () => () => h('span', '加载中') }),
  }
})
const environment = {
  system: 'Linux',
  platform: 'linux-arm64',
  architecture: 'aarch64',
  container: true,
  storageWritable: true,
  nodeSource: 'MISSING',
  canInstall: true,
}
describe('setup guide', () => {
  it('explains the server environment and waits for the explicit installation click', async () => {
    const wrapper = mount(CliSetupGuide, {
      props: { status: { state: 'REQUIRED', environment }, loading: false, error: '' },
    })
    expect(wrapper.text()).toContain('Linux')
    expect(wrapper.text()).toContain('arm64')
    expect(wrapper.text()).toContain('容器')
    expect(wrapper.emitted('install')).toBeUndefined()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '下载并安装')!
      .trigger('click')
    expect(wrapper.emitted('install')).toHaveLength(1)
    wrapper.unmount()
  })
  it('shows actual progress and prevents duplicate install clicks', () => {
    const wrapper = mount(CliSetupGuide, {
      props: {
        status: {
          state: 'DOWNLOADING_CLI',
          environment,
          progress: { stage: 'DOWNLOADING_CLI', completedPackages: 12, totalPackages: 110 },
        },
        loading: false,
        error: '',
      },
    })
    expect(wrapper.text()).toContain('12 / 110 个组件')
    expect(wrapper.find('progress').attributes('value')).toBe('12')
    expect(
      wrapper
        .findAll('button')
        .find((b) => b.text() === '正在安装…')!
        .attributes('disabled'),
    ).toBeDefined()
    wrapper.unmount()
  })
  it('disables installation for unsupported environments and offers a retry after failure', async () => {
    const wrapper = mount(CliSetupGuide, {
      props: {
        status: {
          state: 'UNSUPPORTED',
          environment: { ...environment, canInstall: false },
          errorCode: 'CLI_MUSL_UNSUPPORTED',
        },
        loading: false,
        error: '',
      },
    })
    expect(wrapper.text()).toContain('musl')
    expect(
      wrapper
        .findAll('button')
        .find((b) => b.text() === '下载并安装')!
        .attributes('disabled'),
    ).toBeDefined()
    await wrapper.setProps({
      status: { state: 'FAILED', environment, errorCode: 'CLI_DOWNLOAD_FAILED' },
    })
    expect(wrapper.text()).toContain('重新下载安装')
    wrapper.unmount()
  })
})
