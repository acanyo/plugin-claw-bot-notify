<script setup lang="ts">
import { computed } from 'vue'
import { VButton, VCard, VLoading } from '@halo-dev/components'
import type { Status } from '@/api/generated'
import { downloadSize, setupStages, setupTerminalStates } from '@/lib/cliSetup'
import { commandExplanation } from '@/lib/commandStatus'
import IconCheck from '~icons/ri/check-line'
import IconServer from '~icons/ri/server-line'

const props = defineProps<{ status?: Status; loading: boolean; error: string }>()
const emit = defineEmits<{ detect: []; install: []; finish: []; skip: [] }>()
const environment = computed(() => props.status?.environment)
const ready = computed(() => ['READY', 'EXTERNAL'].includes(props.status?.state ?? ''))
const working = computed(
  () => !!props.status && !setupTerminalStates.includes(props.status.state ?? ''),
)
const detecting = computed(
  () => !props.status || ['IDLE', 'DETECTING'].includes(props.status.state ?? ''),
)
const installationStarted = computed(() => working.value && !detecting.value)
const progress = computed(() => props.status?.progress)
const downloadPercent = computed(() => {
  const p = progress.value
  return p?.totalBytes
    ? Math.min(100, Math.round(((p.downloadedBytes ?? 0) / p.totalBytes) * 100))
    : undefined
})
const nodeSources: Record<string, string> = {
  SYSTEM: '复用系统已有版本',
  CUSTOM: '使用管理员配置',
  MANAGED: '已由插件安装',
  MISSING: '需要下载',
}
const currentStep = computed(() =>
  ready.value ? 3 : installationStarted.value || props.status?.state === 'FAILED' ? 2 : 1,
)
</script>

<template>
  <VCard class="setup-guide">
    <div class="setup-content">
      <div class="setup-intro">
        <span class="setup-icon" aria-hidden="true"><IconServer /></span>
        <div>
          <h2>{{ ready ? '站点管理工具已就绪' : '先准备站点管理工具' }}</h2>
          <p>检测 Halo 服务器环境，按需安装 Node.js 和 Halo CLI。</p>
        </div>
      </div>
      <ol class="setup-steps" aria-label="工具安装步骤">
        <li
          v-for="(step, index) in ['检测环境', '下载安装', '开始使用']"
          :key="step"
          :aria-current="currentStep === index + 1 ? 'step' : undefined"
          :class="{ complete: currentStep > index + 1 }"
        >
          <span class="step-number"
            ><IconCheck v-if="currentStep > index + 1" /><template v-else>{{
              index + 1
            }}</template></span
          >{{ step }}
        </li>
      </ol>
      <p v-if="error" class="cb-notice" role="alert">{{ error }}</p>
      <div v-if="detecting" class="setup-detecting" role="status">
        <VLoading /><span>正在检测系统、处理器和工具环境…</span>
      </div>
      <template v-if="environment && !detecting">
        <div class="setup-machine">
          <div>
            <span>Halo 服务器</span
            ><strong
              >{{ environment.system }} <small v-if="environment.container">容器</small></strong
            >
          </div>
          <div>
            <span>处理器架构</span
            ><strong>{{
              environment.platform?.split('-').slice(-1)[0] ?? environment.architecture
            }}</strong>
          </div>
          <div>
            <span>安装目录</span
            ><strong :class="{ 'setup-warning': !environment.storageWritable }">{{
              environment.storageWritable ? '可写入' : '无法写入'
            }}</strong>
          </div>
        </div>
        <p class="setup-target">
          {{
            environment.container
              ? '检测结果来自运行 Halo 的容器，安装文件也保存在该容器的持久化目录。'
              : '检测结果来自运行 Halo 的服务器。工具将安装到 Halo 的持久化目录。'
          }}
        </p>
        <dl class="setup-tools">
          <div>
            <dt><strong>Node.js</strong><span>运行 Halo CLI 所需的环境</span></dt>
            <dd>
              <strong>{{
                environment.nodeVersion ? `v${environment.nodeVersion}` : `将安装 v22.23.5`
              }}</strong
              ><span>{{ nodeSources[environment.nodeSource ?? 'MISSING'] }}</span>
            </dd>
          </div>
          <div>
            <dt><strong>Halo CLI</strong><span>用于查询和管理本站内容</span></dt>
            <dd>
              <strong>v{{ status?.cliVersion ?? '1.4.0' }}</strong
              ><span>{{ ready || environment.cliInstalled ? '已安装' : '需要下载' }}</span>
            </dd>
          </div>
        </dl>
        <div
          v-if="installationStarted || status?.state === 'FAILED'"
          class="setup-progress"
          :aria-busy="installationStarted"
          role="status"
          aria-live="polite"
        >
          <div>
            <strong>{{ setupStages[status?.state ?? ''] ?? '正在安装' }}</strong
            ><span v-if="progress?.stage === 'DOWNLOADING_CLI'"
              >{{ progress.completedPackages ?? 0 }} / {{ progress.totalPackages }} 个组件</span
            ><span v-else-if="progress?.totalBytes"
              >{{ downloadSize(progress.downloadedBytes) }} /
              {{ downloadSize(progress.totalBytes) }}</span
            >
          </div>
          <progress
            v-if="progress?.stage === 'DOWNLOADING_NODE'"
            :value="downloadPercent"
            max="100"
            aria-label="Node.js 下载进度"
          />
          <progress
            v-else-if="progress?.stage === 'DOWNLOADING_CLI'"
            :value="progress.completedPackages"
            :max="progress.totalPackages || 1"
            aria-label="Halo CLI 下载进度"
          />
          <p v-if="installationStarted">可以离开此页，后台会继续安装；返回后可查看进度。</p>
        </div>
        <p v-if="status?.errorCode" class="cb-notice" role="alert">
          {{ commandExplanation(status.errorCode) }}
        </p>
        <p v-if="ready" class="setup-ready" role="status">
          <IconCheck aria-hidden="true" />环境检查通过，可以继续绑定微信和配置模型。
        </p>
        <details class="setup-details">
          <summary>安装位置与下载来源</summary>
          <dl>
            <div>
              <dt>保存位置</dt>
              <dd>{{ environment.dataDirectory }}</dd>
            </div>
            <div v-if="environment.nodePath">
              <dt>Node.js 路径</dt>
              <dd>{{ environment.nodePath }}</dd>
            </div>
            <div>
              <dt>下载来源</dt>
              <dd>Node.js：nodejs.org · Halo CLI：registry.npmjs.org</dd>
            </div>
          </dl>
          <p>
            仅下载当前环境需要的文件，校验通过后安装。已有可用版本会复用，用户无需安装 npm
            或执行命令。
          </p>
        </details>
      </template>
      <div v-if="status?.state === 'EXTERNAL'" class="setup-ready" role="status">
        <IconCheck />已配置外部 CLI，可继续使用。外部工具由管理员维护。
      </div>
      <footer class="setup-actions">
        <VButton v-if="ready" class="cb-button cb-primary" @click="emit('finish')"
          >完成，开始使用</VButton
        >
        <VButton
          v-else-if="!detecting"
          class="cb-button cb-primary"
          :disabled="loading || working || !environment?.canInstall"
          :loading="installationStarted"
          @click="emit('install')"
          >{{
            installationStarted
              ? '正在安装…'
              : status?.state === 'FAILED'
                ? '重新下载安装'
                : '下载并安装'
          }}</VButton
        >
        <VButton class="cb-button" :disabled="loading || working" @click="emit('detect')"
          >重新检测</VButton
        >
        <button v-if="!ready" class="setup-skip" @click="emit('skip')">稍后设置，先绑定微信</button>
      </footer>
    </div>
  </VCard>
</template>

<style scoped>
.setup-guide {
  max-width: 900px;
  margin: 0 auto 24px;
  border: 1px solid var(--cb-border);
  border-radius: 14px;
  overflow: hidden;
}
.setup-guide :deep(.card-body) {
  padding: 0;
}
.setup-content {
  padding: 30px 32px;
  display: grid;
  gap: 22px;
}
.setup-intro {
  display: flex;
  gap: 14px;
  align-items: center;
}
.setup-icon {
  display: grid;
  place-items: center;
  color: var(--cb-accent);
  background: var(--cb-accent-soft);
  width: 46px;
  height: 46px;
  border-radius: 12px;
  flex-shrink: 0;
}
.setup-icon svg {
  width: 24px;
  height: 24px;
}
.setup-intro h2 {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.4px;
}
.setup-intro p {
  margin-top: 5px;
  color: var(--cb-muted);
  font-size: 13px;
  line-height: 1.7;
}
.setup-steps {
  list-style: none;
  display: flex;
  margin: 0;
  padding: 0 0 20px;
  border-bottom: 1px solid var(--cb-border);
  gap: 30px;
}
.setup-steps li {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--cb-muted);
  font-size: 13px;
}
.setup-steps li[aria-current],
.setup-steps .complete {
  color: var(--cb-accent);
}
.step-number {
  display: grid;
  place-items: center;
  border: 1px solid var(--cb-border);
  border-radius: 50%;
  width: 23px;
  height: 23px;
  font-size: 12px;
}
[aria-current] .step-number {
  color: var(--cb-bg);
  background: var(--cb-accent);
  border-color: var(--cb-accent);
}
.step-number svg {
  width: 15px;
}
.setup-detecting {
  display: flex;
  gap: 12px;
  justify-content: center;
  align-items: center;
  min-height: 160px;
  color: var(--cb-muted);
}
.setup-machine {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr;
  background: var(--cb-subtle);
  border: 1px solid var(--cb-border);
  border-radius: 10px;
  padding: 20px;
  gap: 20px;
}
.setup-machine div,
.setup-tools dd {
  display: grid;
  gap: 7px;
}
.setup-machine span {
  color: var(--cb-muted);
  font-size: 12px;
}
.setup-machine strong {
  font-family: ui-monospace, monospace;
  font-size: 16px;
  font-weight: 500;
}
.setup-machine small {
  font-family: inherit;
  font-size: 11px;
  padding: 2px 5px;
  color: var(--cb-muted);
  border: 1px solid var(--cb-border);
  border-radius: 4px;
}
.setup-machine .setup-warning {
  color: var(--cb-danger);
}
.setup-target {
  font-size: 12px;
  color: var(--cb-muted);
  line-height: 1.7;
  margin-top: -12px;
}
.setup-tools {
  display: grid;
}
.setup-tools > div {
  display: flex;
  justify-content: space-between;
  padding: 15px 0;
  border-bottom: 1px solid var(--cb-border);
  gap: 20px;
}
.setup-tools > div:first-child {
  padding-top: 0;
}
.setup-tools dt {
  display: grid;
  gap: 6px;
}
.setup-tools strong {
  font-size: 14px;
  font-weight: 500;
}
.setup-tools span {
  color: var(--cb-muted);
  font-size: 12px;
}
.setup-tools dd {
  text-align: right;
}
.setup-progress {
  display: grid;
  gap: 10px;
  padding: 17px 18px;
  background: var(--cb-subtle);
  border-radius: 9px;
}
.setup-progress > div {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 13px;
}
.setup-progress strong {
  font-weight: 500;
}
.setup-progress span,
.setup-progress p {
  font-size: 12px;
  color: var(--cb-muted);
}
.setup-progress progress {
  width: 100%;
  height: 7px;
  accent-color: var(--cb-accent);
}
.setup-ready {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--cb-accent);
  font-size: 13px;
}
.setup-ready svg {
  width: 18px;
  flex-shrink: 0;
}
.setup-details {
  color: var(--cb-muted);
  font-size: 12px;
}
.setup-details summary {
  cursor: pointer;
}
.setup-details dl {
  display: grid;
  gap: 10px;
  margin-top: 16px;
}
.setup-details dl div {
  display: grid;
  grid-template-columns: 90px 1fr;
  gap: 14px;
}
.setup-details dd {
  overflow-wrap: anywhere;
  font-family: ui-monospace, monospace;
  color: var(--cb-text);
}
.setup-details p {
  margin-top: 14px;
  line-height: 1.8;
}
.setup-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding-top: 5px;
}
.setup-skip {
  margin-left: auto;
  color: var(--cb-muted);
  font-size: 12px;
  text-decoration: underline;
  text-underline-offset: 4px;
  background: none;
  border: 0;
  padding: 7px 0;
  cursor: pointer;
}
@container clawbot (max-width: 600px) {
  .setup-content {
    padding: 24px 20px;
    gap: 18px;
  }
  .setup-machine {
    padding: 16px;
    gap: 16px;
    grid-template-columns: 1fr 1fr;
  }
  .setup-machine > div:first-child {
    grid-column: 1 / -1;
  }
  .setup-steps {
    gap: 16px;
    justify-content: space-between;
  }
  .setup-steps li {
    gap: 5px;
    font-size: 12px;
  }
  .setup-skip {
    margin-left: 0;
    width: 100%;
    text-align: left;
  }
  .setup-intro h2 {
    font-size: 18px;
  }
}
</style>
