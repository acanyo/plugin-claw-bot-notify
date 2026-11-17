<script setup lang="ts">
import { VCard, VEmpty, VPagination } from '@halo-dev/components'
import type { DeliveryView } from '@/api/generated'
import { deliveryLabels, deliveryErrorMessage, formatTime } from '@/lib/status'

defineProps<{ items: DeliveryView[]; page: number; size: number; total: number }>()
defineEmits<{ 'update:page': [page: number]; 'update:size': [size: number] }>()
</script>

<template>
  <section aria-labelledby="delivery-title">
    <h2 id="delivery-title" class="sr-only">消息发送</h2>
    <VCard class="cb-record-card" :body-class="['!p-0']">
      <template #header>
        <div class="delivery-toolbar">
          <span class="cb-count" aria-live="polite">{{ total }} 条记录</span>
          <span class="cb-description"
            >接口接收后，请在微信确认消息；结果未知的任务不会自动重发。</span
          >
        </div>
      </template>
      <VEmpty
        v-if="items.length === 0"
        title="还没有发送记录"
        message="启用连接后，评论通知、指令回执和测试消息的结果会显示在这里。"
      />
      <div v-else class="delivery-table-wrap" role="region" aria-label="消息发送记录" tabindex="0">
        <table class="delivery-table">
          <thead>
            <tr>
              <th scope="col">消息</th>
              <th scope="col">发送结果</th>
              <th scope="col">尝试时间</th>
              <th scope="col">完成时间</th>
              <th scope="col">说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id">
              <td>
                <span class="message-kind">{{
                  item.kind === 'COMMENT'
                    ? '评论通知'
                    : item.kind === 'REPLY'
                      ? '楼中楼通知'
                      : item.kind === 'COMMAND'
                        ? '指令回执'
                        : item.kind === 'AI_PROGRESS'
                          ? 'AI 处理中'
                          : '连接测试'
                }}</span
                ><code v-if="item.shortCode">{{ item.shortCode }}</code>
              </td>
              <td>
                <span
                  class="cb-badge"
                  :data-tone="
                    item.state === 'ACCEPTED'
                      ? 'success'
                      : ['UNKNOWN', 'DEAD'].includes(item.state ?? '')
                        ? 'danger'
                        : undefined
                  "
                  >{{ deliveryLabels[item.state ?? ''] ?? '等待核查' }}</span
                >
              </td>
              <td class="record-time">{{ formatTime(item.attemptedAt) }}</td>
              <td class="record-time">{{ formatTime(item.finishedAt) }}</td>
              <td class="delivery-explanation">
                {{ item.errorCode ? deliveryErrorMessage(item.errorCode) : '—' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <template #footer>
        <VPagination
          :page="page"
          :size="size"
          :size-options="[10, 20, 50, 100]"
          :total="total"
          @update:page="$emit('update:page', $event)"
          @update:size="$emit('update:size', $event)"
        />
      </template>
    </VCard>
  </section>
</template>

<style scoped>
.delivery-table-wrap {
  overflow-x: auto;
  scrollbar-width: thin;
}
.delivery-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 12px 24px;
  width: 100%;
  padding: 14px 20px;
}
.delivery-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
  font-size: 12px;
}
.delivery-table th {
  font-size: 12px;
  color: var(--cb-muted);
  font-weight: 500;
  white-space: nowrap;
}
.delivery-table th,
.delivery-table td {
  padding: 15px 16px;
  border-bottom: 1px solid var(--cb-border);
  vertical-align: middle;
}
.delivery-table :is(th, td):first-child {
  padding-left: 20px;
}
.delivery-table :is(th, td):last-child {
  padding-right: 20px;
}
.delivery-table tbody tr:last-child td {
  border-bottom: 0;
}
.delivery-table tbody tr:hover {
  background: var(--cb-subtle);
}
.message-kind {
  display: block;
  white-space: nowrap;
}
code {
  display: block;
  color: var(--cb-muted);
  font:
    11px ui-monospace,
    monospace;
  margin-top: 5px;
}
.record-time {
  white-space: nowrap;
  color: var(--cb-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.delivery-explanation {
  min-width: 130px;
  max-width: 260px;
  color: var(--cb-muted);
  line-height: 1.8;
}
</style>
