<script setup lang="ts">
import {
  VButton,
  VCard,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VPagination,
} from '@halo-dev/components'
import CommandDetailModal from '@/components/CommandDetailModal.vue'
import { useCommandRecords } from '@/composables/useCommandRecords'
import { commandResult, commandTone, commandTypes, processingStates } from '@/lib/commandStatus'
import { deliveryLabels, formatDuration, formatTime } from '@/lib/status'
const {
  records,
  page,
  size,
  total,
  type,
  result,
  loading,
  error,
  selectedId,
  detail,
  detailLoading,
  detailError,
  refresh,
  openDetail,
  closeDetail,
} = useCommandRecords()
defineExpose({ refresh, loading })

function clearFilters() {
  type.value = ''
  result.value = ''
}

const typeOptions = [
  { label: '全部指令' },
  ...Object.entries(commandTypes).map(([value, label]) => ({ value, label })),
]
const resultOptions = [
  { label: '全部结果' },
  { label: '需要核查', value: 'NEEDS_REVIEW' },
  { label: '处理成功', value: 'SUCCEEDED' },
  { label: '等待预览或审核确认', value: 'AWAITING_APPROVAL' },
  { label: '未执行成功', value: 'REJECTED' },
  { label: '等待处理', value: 'READY' },
]
</script>

<template>
  <div>
    <p v-if="error" class="cb-notice cb-page-error" role="alert">{{ error }}</p>
    <section class="command-records" aria-label="微信指令记录" :aria-busy="loading">
      <VCard class="cb-record-card" :body-class="['!p-0']">
        <template #header>
          <div class="record-toolbar">
            <span class="cb-count" aria-live="polite">{{ total }} 条记录</span>
            <div class="record-filters">
              <FilterCleanButton v-if="type || result" @click="clearFilters" />
              <FilterDropdown
                :model-value="type || undefined"
                label="指令类型"
                :items="typeOptions"
                @update:model-value="type = typeof $event === 'string' ? $event : ''"
              />
              <FilterDropdown
                :model-value="result || undefined"
                label="执行结果"
                :items="resultOptions"
                @update:model-value="result = typeof $event === 'string' ? $event : ''"
              />
            </div>
          </div>
        </template>
        <VLoading v-if="loading && records.length === 0" />
        <VEmpty
          v-else-if="!error && records.length === 0"
          title="没有符合条件的指令"
          message="在微信发送指令后，处理记录会显示在这里。"
        />
        <VEntityContainer v-else class="cb-record-list">
          <VEntity v-for="record in records" :key="record.id">
            <template #start>
              <VEntityField
                class="command-identity"
                :title="commandTypes[record.type ?? ''] ?? '微信指令'"
                :description="formatTime(record.receivedAt)"
              >
                <template #extra
                  ><code v-if="record.shortCode" class="record-code">{{
                    record.targetReplyName ? `楼中楼 · ${record.shortCode}` : record.shortCode
                  }}</code></template
                >
              </VEntityField>
            </template>
            <template #end>
              <VEntityField
                class="execution-field"
                :description="
                  record.durationMs != null
                    ? `耗时 ${formatDuration(record.durationMs)}`
                    : '执行结果'
                "
              >
                <template #title>
                  <span class="cb-badge" :data-tone="commandTone(record)">{{
                    commandResult(record)
                  }}</span>
                </template>
              </VEntityField>
              <VEntityField
                class="receipt-field"
                title="微信回执"
                :description="deliveryLabels[record.receiptState ?? ''] ?? '等待生成'"
              />
              <VEntityField
                v-if="record.commentState"
                title="评论状态"
                :description="processingStates[record.commentState ?? ''] ?? '—'"
              />
              <VButton
                class="cb-button"
                size="sm"
                :aria-label="`查看${commandTypes[record.type ?? ''] ?? '指令'}详情${record.shortCode ? ` · ${record.shortCode}` : ''}`"
                @click="record.id && openDetail(record.id)"
                >查看详情</VButton
              >
            </template>
          </VEntity>
        </VEntityContainer>
        <template #footer>
          <VPagination
            v-model:page="page"
            v-model:size="size"
            :size-options="[10, 20, 50, 100]"
            :total="total"
          />
        </template>
      </VCard>
      <p class="records-hint cb-description">
        执行结果表示网站处理情况；微信回执单独记录通知是否被接口接收。
      </p>
    </section>
    <CommandDetailModal
      v-if="selectedId"
      :detail="detail"
      :loading="detailLoading"
      :error="detailError"
      @close="closeDetail"
      @refresh="openDetail(selectedId)"
    />
  </div>
</template>

<style scoped>
.record-toolbar {
  display: flex;
  width: 100%;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 24px;
  padding: 14px 20px;
}
.record-filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 20px;
}
.record-filters :deep(.text-gray-700) {
  color: var(--cb-muted);
}
.record-filters :deep(.text-gray-700:hover) {
  color: var(--cb-text);
}
.records-hint {
  margin-top: 12px;
}
.record-code {
  color: var(--cb-muted);
  background: var(--cb-hover);
  padding: 2px 6px;
  border-radius: 5px;
  font:
    10px ui-monospace,
    monospace;
  margin-left: 8px;
}
.execution-field {
  min-width: 150px;
}
.receipt-field {
  min-width: 110px;
  max-width: 180px;
}
.cb-record-list :deep(.entity-start-wrapper) {
  padding: 16px 12px 16px 20px;
}
.cb-record-list :deep(.entity-end-wrapper) {
  padding-block: 16px;
  padding-right: 20px;
}
.cb-record-list :deep(.entity-end) {
  gap: 24px;
}
.cb-record-list :deep(.entity-field-title) {
  font-size: 12px;
  font-weight: 500;
}
.cb-record-list :deep(.entity-field-description) {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.command-identity :deep(.entity-field-title) {
  font-size: 14px;
  font-weight: 600;
}
.execution-field :deep(.entity-field-title) {
  overflow: visible;
}
.execution-field :deep(.entity-field-description) {
  margin-top: 6px;
}
@container clawbot (max-width: 960px) {
  .cb-record-list :deep(.entity-end) {
    gap: 16px;
  }
  .execution-field {
    min-width: 136px;
  }
}
@container clawbot (max-width: 740px) {
  .cb-record-list :deep(table),
  .cb-record-list :deep(tbody),
  .cb-record-list :deep(.entity-wrapper) {
    display: block;
    width: 100%;
  }
  .cb-record-list :deep(.entity-wrapper) {
    padding: 16px 20px;
  }
  .cb-record-list :deep(.entity-start-wrapper),
  .cb-record-list :deep(.entity-end-wrapper) {
    display: block;
    padding: 0;
  }
  .cb-record-list :deep(.entity-end) {
    margin-top: 16px;
    justify-content: flex-start;
    flex-wrap: wrap;
    gap: 16px 24px;
  }
}
@container clawbot (max-width: 440px) {
  .record-toolbar {
    padding: 14px 16px;
  }
  .record-filters {
    width: 100%;
    gap: 12px 16px;
  }
  .cb-record-list :deep(.entity-end) {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }
  .cb-record-list .cb-button {
    justify-self: start;
  }
}
</style>
