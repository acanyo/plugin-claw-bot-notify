<script setup lang="ts">
import { VButton, VCard, VLoading } from '@halo-dev/components'
import IconModel from '~icons/ri/sparkling-line'
import IconTerminal from '~icons/ri/terminal-box-line'
import { useAssistantRuntime } from '@/composables/useAssistantRuntime'
import { commandExplanation } from '@/lib/commandStatus'
import { formatDuration, formatTime } from '@/lib/status'
const { status, loading, error, refresh } = useAssistantRuntime()
</script>

<template>
  <VCard class="runtime-card" title="智能助手运行状态">
    <template #header>
      <div class="runtime-header">
        <div class="runtime-heading">
          <h2>智能助手</h2>
          <span v-if="status" class="cb-badge" :data-tone="status.enabled ? 'success' : undefined">
            {{ status.enabled ? '已启用' : '未启用' }}
          </span>
        </div>
        <VButton class="cb-button" size="sm" :disabled="loading" @click="refresh">
          {{ loading ? '检查中…' : '检查运行状态' }}
        </VButton>
      </div>
    </template>
    <div class="runtime-body" :aria-busy="loading">
      <p v-if="error" class="cb-notice" role="alert">{{ error }}</p>
      <VLoading v-if="loading && !status" />
      <template v-if="status">
        <p class="runtime-mode">
          {{
            !status.enabled
              ? '在插件设置中启用，即可在微信对话和创作。'
              : status.allowWrites
                ? '在微信查询和管理站点，写操作经预览确认后执行。'
                : '当前支持对话与查询，写操作未开启。'
          }}
        </p>
        <div class="runtime-dependencies">
          <section
            v-for="item in [
              { label: '语言模型', icon: IconModel, dependency: status.model },
              { label: '站点管理操作', icon: IconTerminal, dependency: status.cli },
            ]"
            :key="item.label"
            class="dependency"
          >
            <div class="dependency-icon" aria-hidden="true"><component :is="item.icon" /></div>
            <div class="dependency-content">
              <div class="dependency-title">
                <h3>{{ item.label }}</h3>
                <span
                  class="dependency-state"
                  :data-tone="item.dependency?.available ? 'success' : 'danger'"
                >
                  <span class="dependency-dot" aria-hidden="true" />{{
                    item.dependency?.available ? '可用' : '不可用'
                  }}
                </span>
              </div>
              <p v-if="item.dependency?.errorCode" class="runtime-error">
                {{ commandExplanation(item.dependency.errorCode) }}
              </p>
              <p v-else class="cb-description">
                检查耗时 {{ formatDuration(item.dependency?.latencyMs) }}
              </p>
            </div>
          </section>
        </div>
        <dl class="runtime-retention">
          <div>
            <dt>预览有效期</dt>
            <dd>{{ status.draftMinutes }} <small>分钟</small></dd>
          </div>
          <div>
            <dt>历史正文保留</dt>
            <dd>{{ status.retentionDays }} <small>天</small></dd>
          </div>
          <div>
            <dt>最近自动清理</dt>
            <dd class="retention-time">
              {{ status.cleanupAt ? formatTime(status.cleanupAt) : '等待首次清理' }}
            </dd>
          </div>
          <div>
            <dt>最近清理正文</dt>
            <dd>{{ status.cleanedBodies ?? 0 }} <small>条</small></dd>
          </div>
        </dl>
        <p v-if="status.cleanupErrorCode" class="cb-notice" role="alert">
          历史清理未完成，稍后会重试。<code>{{ status.cleanupErrorCode }}</code>
        </p>
        <details class="runtime-details">
          <summary>
            检查详情<span>{{ formatTime(status.checkedAt) }}</span>
          </summary>
          <dl>
            <div>
              <dt>模型标识</dt>
              <dd>{{ status.model?.name ?? '尚未配置' }}</dd>
            </div>
            <div>
              <dt>站点管理工具</dt>
              <dd>{{ status.cli?.name ?? 'Halo CLI' }}</dd>
            </div>
            <div v-if="status.model?.errorCode">
              <dt>模型错误代码</dt>
              <dd>{{ status.model.errorCode }}</dd>
            </div>
            <div v-if="status.cli?.errorCode">
              <dt>工具错误代码</dt>
              <dd>{{ status.cli.errorCode }}</dd>
            </div>
          </dl>
          <p>可用性检查不会生成内容。调整配置：插件设置 → 智能助手。</p>
        </details>
      </template>
    </div>
  </VCard>
</template>

<style scoped>
.runtime-card {
  margin-top: 20px;
  border: 1px solid var(--cb-border);
  border-radius: 14px;
  overflow: hidden;
}
.runtime-card :deep(.card-header) {
  border-color: var(--cb-border);
}
.runtime-card :deep(.card-body) {
  padding: 0;
}
.runtime-header {
  display: flex;
  width: 100%;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 16px 24px;
}
.runtime-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.runtime-heading h2 {
  font-size: 16px;
  font-weight: 600;
}
.runtime-body {
  display: grid;
  gap: 20px;
  padding: 20px 24px;
}
.runtime-mode {
  color: var(--cb-muted);
  font-size: 13px;
  line-height: 1.8;
}
.runtime-dependencies {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.dependency {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--cb-border);
  border-radius: 10px;
}
.dependency-icon {
  display: grid;
  flex-shrink: 0;
  place-items: center;
  width: 36px;
  height: 36px;
  background: var(--cb-subtle);
  border-radius: 9px;
  color: var(--cb-muted);
}
.dependency-icon svg {
  width: 19px;
  height: 19px;
}
.dependency-content {
  min-width: 0;
  flex: 1;
}
.dependency-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 6px;
}
.dependency-title h3 {
  font-size: 14px;
  font-weight: 500;
}
.dependency-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  white-space: nowrap;
}
.dependency-state[data-tone='success'] {
  color: var(--cb-accent);
}
.dependency-state[data-tone='danger'] {
  color: var(--cb-danger);
}
.dependency-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}
.runtime-error {
  color: var(--cb-danger);
  font-size: 12px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.runtime-retention {
  display: grid;
  grid-template-columns: 1fr 1fr 1.5fr 1fr;
  gap: 16px;
  padding-top: 20px;
  border-top: 1px solid var(--cb-border);
}
.runtime-retention dt {
  color: var(--cb-muted);
  font-size: 12px;
  margin-bottom: 8px;
}
.runtime-retention dd {
  font-size: 19px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.runtime-retention small {
  font-size: 12px;
  font-weight: 400;
  color: var(--cb-muted);
}
.runtime-retention .retention-time {
  font-size: 12px;
  line-height: 28px;
}
.runtime-details {
  padding-top: 16px;
  border-top: 1px solid var(--cb-border);
  color: var(--cb-muted);
  font-size: 12px;
}
.runtime-details summary {
  cursor: pointer;
}
.runtime-details summary span {
  float: right;
  font-variant-numeric: tabular-nums;
}
.runtime-details dl {
  display: grid;
  gap: 12px;
  margin-top: 20px;
}
.runtime-details dl div {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 12px;
}
.runtime-details dd {
  color: var(--cb-text);
  overflow-wrap: anywhere;
  font-family: ui-monospace, monospace;
}
.runtime-details p {
  margin-top: 16px;
}
@container clawbot (max-width: 680px) {
  .runtime-retention {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .runtime-dependencies {
    grid-template-columns: 1fr;
  }
  .runtime-header {
    flex-wrap: wrap;
  }
}
@container clawbot (max-width: 440px) {
  .runtime-header,
  .runtime-body {
    padding-inline: 20px;
  }
  .runtime-details summary span {
    display: block;
    float: none;
    margin-top: 6px;
  }
}
</style>
