<script setup lang="ts">
import { computed } from 'vue'
import { VButton, VLoading } from '@halo-dev/components'
import ClawBotModal from '@/components/ClawBotModal.vue'
import DraftReviewPanel from '@/components/DraftReviewPanel.vue'
import type { CommandDetail } from '@/api/generated'
import {
  commandTypes,
  commandResult,
  commandExplanation,
  processingStates,
  commandTone,
} from '@/lib/commandStatus'
import { deliveryLabels, deliveryErrorMessage, formatTime } from '@/lib/status'
const props = defineProps<{ detail?: CommandDetail; loading: boolean; error: string }>()
defineEmits<{ close: []; refresh: [] }>()
const isAi = computed(() => (props.detail?.record?.type ?? '').startsWith('AI'))
</script>

<template>
  <ClawBotModal title="指令详情" @close="$emit('close')">
    <div v-if="loading" class="detail-placeholder" role="status" aria-live="polite">
      <VLoading />
      <p>正在读取指令详情…</p>
    </div>
    <div v-else-if="error" class="detail-placeholder" role="alert">
      <strong>详情暂时无法读取</strong>
      <p>{{ error }}</p>
    </div>
    <div v-else-if="detail?.record" class="command-detail">
      <div class="detail-summary">
        <div>
          <h3>{{ commandTypes[detail.record.type ?? ''] ?? '微信指令' }}</h3>
          <p>{{ formatTime(detail.record.receivedAt) }}</p>
        </div>
        <span class="cb-badge" :data-tone="commandTone(detail.record)">{{
          commandResult(detail.record)
        }}</span>
      </div>
      <p v-if="detail.record.reviewRequired" class="review-notice" role="status">
        {{
          isAi
            ? '执行结果尚未确认。请先到对应管理页面核查，避免重复执行。'
            : '网站结果尚未确认。请先到评论管理核查，确认前避免重新发送同一段回复。'
        }}
      </p>
      <dl class="result-grid">
        <div>
          <dt>微信回执</dt>
          <dd>{{ deliveryLabels[detail.record.receiptState ?? ''] ?? '等待生成回执' }}</dd>
        </div>
        <div>
          <dt>总耗时</dt>
          <dd>
            {{ detail.record.durationMs != null ? `${detail.record.durationMs} ms` : '未记录' }}
          </dd>
        </div>
        <div v-if="detail.record.commentName || detail.record.shortCode">
          <dt>{{ detail.record.targetReplyName ? '目标楼中楼' : '目标评论' }}</dt>
          <dd>{{ detail.record.shortCode ?? detail.record.commentName }}</dd>
        </div>
        <div v-if="detail.record.commentState">
          <dt>当前处理状态</dt>
          <dd>{{ processingStates[detail.record.commentState ?? ''] ?? '—' }}</dd>
        </div>
        <div v-if="detail.record.modelName">
          <dt>使用模型</dt>
          <dd>{{ detail.record.modelName }}</dd>
        </div>
        <div v-if="detail.record.modelDurationMs != null">
          <dt>模型调用耗时（含工具）</dt>
          <dd>{{ detail.record.modelDurationMs }} ms</dd>
        </div>
        <div v-if="detail.record.cliDurationMs != null">
          <dt>CLI 操作耗时</dt>
          <dd>{{ detail.record.cliDurationMs }} ms</dd>
        </div>
        <div v-if="detail.record.cleanedAt">
          <dt>正文清理时间</dt>
          <dd>{{ formatTime(detail.record.cleanedAt) }}</dd>
        </div>
      </dl>
      <p v-if="detail.record.errorCode" class="detail-notice">
        {{ commandExplanation(detail.record.errorCode) }} <code>{{ detail.record.errorCode }}</code>
      </p>
      <p v-if="detail.record.receiptErrorCode" class="detail-notice">
        微信回执：{{ deliveryErrorMessage(detail.record.receiptErrorCode) }}
      </p>
      <p v-if="detail.contentErrorCode" class="detail-notice">
        {{ commandExplanation(detail.contentErrorCode) }}
      </p>
      <DraftReviewPanel v-if="detail.preview" :preview="detail.preview" />
      <section v-if="detail.submittedMarkdown != null" class="content-section submitted-content">
        <h3>微信提交的正文</h3>
        <pre>{{ detail.submittedMarkdown }}</pre>
      </section>
      <section v-if="detail.commentMarkdown != null" class="content-section">
        <h3>原评论</h3>
        <pre>{{ detail.commentMarkdown }}</pre>
      </section>
      <section v-if="detail.targetReplyMarkdown != null" class="content-section">
        <h3>所回复的楼中楼</h3>
        <pre>{{ detail.targetReplyMarkdown }}</pre>
      </section>
      <section v-if="detail.replyMarkdown != null" class="content-section response-content">
        <h3>
          {{ isAi ? 'AI 答复' : '网站当前保存的回复' }}
        </h3>
        <pre>{{ detail.replyMarkdown }}</pre>
      </section>
      <details
        v-if="detail.record.replyName || detail.record.commentName"
        class="reference-details"
      >
        <summary>关联记录</summary>
        <p v-if="detail.record.commentName">评论：{{ detail.record.commentName }}</p>
        <p v-if="detail.record.targetReplyName">目标楼中楼：{{ detail.record.targetReplyName }}</p>
        <p v-if="detail.record.replyName">回复：{{ detail.record.replyName }}</p>
      </details>
    </div>
    <div v-else class="detail-placeholder" role="status">
      <strong>暂无可展示的详情</strong>
      <p>可以重新读取，或关闭后选择其他记录。</p>
    </div>
    <template #footer="{ close }">
      <div class="detail-actions">
        <RouterLink v-if="detail?.record?.commentName" to="/comments" @click="close"
          >前往评论管理核查</RouterLink
        >
        <VButton class="cb-button" :disabled="loading" @click="$emit('refresh')">重新读取</VButton>
        <VButton class="cb-button cb-primary" type="secondary" @click="close">关闭</VButton>
      </div>
    </template>
  </ClawBotModal>
</template>

<style scoped>
.command-detail {
  display: grid;
  gap: 20px;
}
.detail-placeholder {
  min-height: 200px;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 12px;
  text-align: center;
  color: var(--cb-muted);
  font-size: 13px;
}
.detail-placeholder strong {
  color: var(--cb-text);
  font-weight: 600;
}
.detail-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--cb-border);
}
.detail-summary h3 {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.4px;
}
.detail-summary p {
  margin-top: 6px;
  color: var(--cb-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.result-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  padding: 16px;
  background: var(--cb-subtle);
  border-radius: 10px;
}
dt,
.reference-details {
  color: var(--cb-muted);
  font-size: 12px;
}
dd {
  margin-top: 4px;
  font-size: 14px;
  color: var(--cb-text);
  overflow-wrap: anywhere;
}
.detail-notice,
.review-notice {
  padding: 12px 16px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.8;
  background: var(--cb-subtle);
  color: var(--cb-muted);
}
.review-notice {
  background: var(--cb-subtle);
  color: var(--cb-danger);
  border: 1px solid var(--cb-border);
}
.content-section h3 {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
}
pre {
  margin: 0;
  padding: 16px;
  background: var(--cb-subtle);
  border: 1px solid var(--cb-border);
  border-radius: 10px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.8;
}
.response-content pre {
  border-left: 3px solid var(--cb-accent);
  background: var(--cb-accent-soft);
}
.detail-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 12px;
}
.detail-actions a {
  margin-right: auto;
  font-size: 13px;
  color: var(--cb-accent);
}
.reference-details p {
  margin-top: 8px;
  overflow-wrap: anywhere;
}
@media (max-width: 560px) {
  .result-grid {
    grid-template-columns: 1fr;
  }
}
</style>
