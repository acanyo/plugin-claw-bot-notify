<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { Dialog, VButton } from '@halo-dev/components'
import type { BindingView } from '@/api/generated'
import { connectionLabels, errorMessage, formatTime, qrLabels } from '@/lib/status'
import HaloCredentialHelp from '@/components/HaloCredentialHelp.vue'

const props = defineProps<{ binding: BindingView; busy: boolean; credentialSaveCount: number }>()
const emit = defineEmits<{
  create: []
  code: [value: string]
  credential: [value: string]
  activate: []
  disconnect: []
  test: []
}>()
const qrImage = ref(''),
  codeForm = ref(0),
  tokenForm = ref(0)
const state = computed(() => props.binding.connectionState ?? 'UNBOUND')
const hasIdentity = computed(() => !!props.binding.botIdentity && state.value !== 'DISABLED')
const showScan = computed(
  () => !hasIdentity.value || !!props.binding.qrContent || state.value === 'BINDING',
)
const step = computed(() => (props.binding.enabled ? 3 : hasIdentity.value ? 2 : 1))

watch(
  () => props.credentialSaveCount,
  () => ++tokenForm.value,
)

watch(
  () => props.binding.qrContent,
  async (content, _, cleanup) => {
    let cancelled = false
    cleanup(() => {
      cancelled = true
    })
    qrImage.value = ''
    if (!content) return
    try {
      const image = await QRCode.toDataURL(content, {
        width: 240,
        margin: 2,
        errorCorrectionLevel: 'M',
      })
      if (!cancelled) qrImage.value = image
    } catch {
      if (!cancelled) qrImage.value = ''
    }
  },
  { immediate: true },
)

function create() {
  if (state.value === 'UNBOUND' || state.value === 'DISABLED') {
    emit('create')
    return
  }
  Dialog.warning({
    title: '重新绑定微信',
    description:
      '当前连接将停止。旧连接的消息和任务不会转投到新账号，需要重新配置本人的 Halo 访问令牌。',
    confirmText: '重新绑定',
    onConfirm: () => emit('create'),
  })
}
function disconnect() {
  Dialog.warning({
    title: '在本地断开连接',
    description: '停止收发并清理本地凭证。此操作不会撤销微信服务器上的授权。',
    confirmText: '断开连接',
    confirmType: 'danger',
    onConfirm: () => emit('disconnect'),
  })
}
function code(data: { code: string }) {
  emit('code', data.code)
  ++codeForm.value
}
function credential(data: { token: string }) {
  emit('credential', data.token)
}
</script>

<template>
  <section
    class="connection-panel"
    :class="{ 'is-linked': binding.enabled && !showScan }"
    aria-labelledby="connection-title"
  >
    <div class="connection-summary">
      <h2 id="connection-title">连接信息</h2>
      <span
        class="connection-state"
        :data-tone="state === 'ACTIVE' ? 'success' : binding.errorCode ? 'danger' : undefined"
        role="status"
      >
        <span class="state-dot" aria-hidden="true" />{{ connectionLabels[state] ?? '状态待确认' }}
      </span>
    </div>
    <p v-if="!binding.enabled" class="connection-instruction">
      扫码绑定微信，验证 Halo 访问令牌后启用连接。
    </p>
    <p v-if="!binding.credentialStorageReady" class="cb-notice" role="alert">
      无法读取或创建凭证加密密钥。请检查 Halo 持久化目录的写入权限，并保留原有密钥文件。
    </p>
    <p v-if="binding.errorCode" class="cb-notice" role="alert">
      {{ errorMessage(binding.errorCode) }}
    </p>
    <ol v-if="!binding.enabled" class="connection-steps" aria-label="连接步骤">
      <li
        v-for="(label, index) in ['扫码绑定', '确认身份', '微信互动']"
        :key="label"
        :class="{ done: index + 1 < step, current: index + 1 === step }"
        :aria-current="index + 1 === step ? 'step' : undefined"
      >
        <span class="step-number">{{ index + 1 }}</span
        ><span>{{ label }}</span>
      </li>
    </ol>
    <div class="connection-layout" :class="{ 'has-scan': showScan }">
      <section v-if="showScan" class="scan-area" aria-label="微信扫码">
        <div v-if="qrImage" class="qr-box">
          <img
            :src="qrImage"
            width="240"
            height="240"
            alt="使用本人微信扫描此二维码以绑定 ClawBot"
          />
        </div>
        <div v-else class="qr-placeholder">
          <strong>微信扫码绑定</strong>
          <p>使用本人的微信连接。请先停止同一 Bot 的其他客户端。</p>
        </div>
        <p v-if="binding.qrState" class="qr-caption" aria-live="polite">
          {{ qrLabels[binding.qrState] ?? '等待微信确认' }}
        </p>
        <p v-if="qrImage" class="muted">扫码会话于 {{ formatTime(binding.qrExpiresAt) }} 结束</p>
        <FormKit
          v-if="binding.qrState === 'NEEDS_CODE'"
          :key="codeForm"
          v-slot="{ node }"
          type="form"
          @submit="code"
        >
          <FormKit
            name="code"
            type="text"
            label="手机微信显示的数字"
            :validation="[['required'], ['matches', /^[0-9]{1,12}$/]]"
            inputmode="numeric"
            autocomplete="one-time-code"
          />
          <VButton
            class="cb-button cb-primary"
            :disabled="busy"
            :loading="busy"
            @click="node.submit()"
            >核对验证码</VButton
          >
        </FormKit>
        <VButton
          class="cb-button cb-primary"
          :disabled="busy || !binding.credentialStorageReady"
          @click="create"
          >{{ state === 'UNBOUND' || state === 'DISABLED' ? '绑定微信' : '重新绑定' }}</VButton
        >
      </section>
      <div class="identity-area">
        <dl class="identity-list">
          <div>
            <dt>Halo 用户</dt>
            <dd>{{ binding.haloUsername ?? '绑定时关联当前用户' }}</dd>
          </div>
          <div>
            <dt>微信账号</dt>
            <dd>{{ binding.ownerIdentity ?? '尚未确认' }}</dd>
          </div>
          <div>
            <dt>ClawBot</dt>
            <dd>{{ binding.botIdentity ?? '尚未确认' }}</dd>
          </div>
          <div>
            <dt>访问令牌</dt>
            <dd>
              <span v-if="binding.haloCredentialConfigured" class="credential-ready"
                >已验证身份</span
              ><span v-else>尚未配置</span>
            </dd>
          </div>
        </dl>
        <div v-if="hasIdentity" class="primary-actions">
          <p v-if="state === 'AWAITING_ACTIVATION'" class="instruction">
            确认这是本人的微信账号，再启用连接。
          </p>
          <p v-else-if="binding.enabled && !binding.contextReceivedAt" class="instruction">
            在微信给 ClawBot 发送“开始”，再发送测试消息。
          </p>
          <div class="connection-actions">
            <VButton
              v-if="!binding.enabled || state !== 'ACTIVE'"
              class="cb-button cb-primary"
              :disabled="busy || !binding.haloCredentialConfigured"
              @click="emit('activate')"
              >{{ binding.enabled ? '重新启用连接' : '确认并启用' }}</VButton
            >
            <VButton class="cb-button" :disabled="busy || !binding.enabled" @click="emit('test')"
              >发送测试消息</VButton
            >
          </div>
        </div>
        <dl v-if="binding.enabled" class="activity">
          <div>
            <dt>最近成功轮询</dt>
            <dd>{{ formatTime(binding.lastPollAt) }}</dd>
          </div>
          <div>
            <dt>最近接口接收</dt>
            <dd>{{ formatTime(binding.lastAcceptedAt) }}</dd>
          </div>
        </dl>
      </div>
    </div>
    <details
      class="connection-settings"
      :open="!binding.enabled || !binding.haloCredentialConfigured"
    >
      <summary>{{ binding.enabled ? '连接设置与访问令牌' : '验证 Halo 访问令牌' }}</summary>
      <div class="settings-content">
        <div class="credential-config">
          <p class="settings-description">
            使用发起绑定的 Halo 账号创建令牌，用于读取评论和发布回复。
          </p>
          <HaloCredentialHelp :username="binding.haloUsername" />
          <FormKit
            v-if="hasIdentity"
            :key="tokenForm"
            v-slot="{ node }"
            type="form"
            @submit="credential"
          >
            <FormKit
              name="token"
              type="password"
              label="本人 Halo 个人访问令牌"
              help="粘贴完整令牌，保存时核对账号身份。令牌不会回显。"
              validation="required|length:1,8192"
              autocomplete="new-password"
            />
            <VButton class="cb-button" :disabled="busy" :loading="busy" @click="node.submit()"
              >验证并保存令牌</VButton
            >
          </FormKit>
        </div>
        <div v-if="hasIdentity" class="connection-maintenance">
          <div>
            <h3>更换或断开连接</h3>
            <p>重新绑定需要重新验证身份；本地断开会清理已保存的凭证。</p>
          </div>
          <div class="connection-actions">
            <VButton
              v-if="!showScan"
              class="cb-button"
              :disabled="busy || !binding.credentialStorageReady"
              @click="create"
              >重新绑定</VButton
            >
            <VButton class="cb-button cb-danger" :disabled="busy" @click="disconnect"
              >本地断开</VButton
            >
          </div>
        </div>
      </div>
    </details>
  </section>
</template>

<style scoped>
.connection-panel {
  border: 1px solid var(--cb-border);
  border-radius: 8px;
  overflow: hidden;
}
.connection-summary {
  padding: 14px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--cb-border);
}
.connection-summary h2 {
  font-size: 14px;
  font-weight: 600;
}
.connection-instruction {
  padding: 16px 20px 0;
  color: var(--cb-muted);
  font-size: 12px;
  line-height: 1.8;
}
.connection-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  color: var(--cb-muted);
  font-size: 12px;
}
.connection-state[data-tone='success'] {
  color: var(--cb-accent);
}
.connection-state[data-tone='danger'] {
  color: var(--cb-danger);
}
.state-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.connection-panel > .cb-notice {
  margin: 16px 20px 0;
}
.connection-steps {
  display: flex;
  list-style: none;
  gap: 28px;
  padding: 20px 20px 0;
  margin: 0;
}
.connection-steps li {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--cb-muted);
  font-size: 12px;
}
.connection-steps .current {
  color: var(--cb-text);
  font-weight: 600;
}
.connection-steps .done {
  color: var(--cb-accent);
}
.step-number {
  display: grid;
  place-items: center;
  width: 23px;
  height: 23px;
  border-radius: 50%;
  border: 1px solid var(--cb-border);
  font:
    11px ui-monospace,
    monospace;
}
.current .step-number {
  background: var(--cb-text);
  color: var(--cb-bg);
  border-color: var(--cb-text);
}
.connection-layout {
  padding: 20px;
}
.connection-layout.has-scan {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 32px;
}
.scan-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  text-align: center;
}
.scan-area :deep(.formkit-outer) {
  text-align: left;
}
.qr-box {
  max-width: 100%;
  background: #fff;
  padding: 8px;
  border: 1px solid var(--cb-border);
  border-radius: 12px;
}
.qr-box img {
  display: block;
  max-width: 100%;
  height: auto;
}
.qr-placeholder {
  display: flex;
  align-items: center;
  min-height: 240px;
  flex-direction: column;
  justify-content: center;
  width: 100%;
  background: var(--cb-subtle);
  border: 1px dashed var(--cb-border);
  border-radius: 12px;
  padding: 24px;
}
.qr-placeholder strong {
  font-size: 14px;
  font-weight: 500;
}
.qr-placeholder p,
.muted {
  margin-top: 8px;
  color: var(--cb-muted);
  font-size: 12px;
  line-height: 1.8;
}
.qr-caption {
  font-size: 13px;
}
.identity-list {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 20px;
}
.has-scan .identity-list {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  padding-top: 12px;
}
.identity-list dt {
  color: var(--cb-muted);
  font-size: 12px;
}
.identity-list dd {
  margin-top: 7px;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.credential-ready {
  color: var(--cb-accent);
}
.primary-actions {
  margin-top: 20px;
}
.is-linked .identity-area {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 20px;
}
.is-linked .primary-actions {
  margin: 0;
}
.is-linked .activity {
  grid-column: 1 / -1;
  margin-top: 0;
  padding-top: 16px;
}
.connection-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.instruction {
  color: var(--cb-muted);
  font-size: 12px;
  line-height: 1.8;
  margin-bottom: 14px;
}
.activity {
  margin-top: 24px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px 28px;
  padding-top: 20px;
  border-top: 1px solid var(--cb-border);
}
.activity div {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  font-size: 11px;
  color: var(--cb-muted);
}
.connection-settings {
  border-top: 1px solid var(--cb-border);
}
.connection-settings summary {
  padding: 12px 20px;
  background: var(--cb-subtle);
  color: var(--cb-muted);
  font-size: 12px;
  cursor: pointer;
}
.connection-settings[open] summary {
  color: var(--cb-text);
}
.settings-content {
  padding: 20px;
}
.credential-config {
  max-width: 720px;
}
.settings-description {
  color: var(--cb-muted);
  font-size: 13px;
  line-height: 1.8;
  margin-bottom: 16px;
}
.connection-maintenance {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--cb-border);
}
.connection-maintenance h3 {
  font-size: 13px;
  font-weight: 500;
}
.connection-maintenance p {
  color: var(--cb-muted);
  font-size: 12px;
  line-height: 1.8;
  margin-top: 4px;
}
@container clawbot (max-width: 760px) {
  .identity-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .connection-layout.has-scan {
    grid-template-columns: 1fr;
    gap: 28px;
  }
  .scan-area {
    max-width: 280px;
    margin: 0 auto;
  }
}
@container clawbot (max-width: 500px) {
  .is-linked .identity-area {
    grid-template-columns: 1fr;
  }
  .connection-summary {
    flex-wrap: wrap;
  }
  .connection-steps {
    padding-inline: 20px;
    gap: 16px;
  }
  .connection-steps li {
    gap: 5px;
    font-size: 11px;
  }
  .connection-settings summary {
    padding-inline: 20px;
  }
  .settings-content {
    padding-inline: 20px;
  }
  .connection-panel > .cb-notice {
    margin-inline: 20px;
  }
}
</style>
