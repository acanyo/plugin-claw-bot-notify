<script setup lang="ts">
import { computed, useTemplateRef } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { VButton } from '@halo-dev/components'
import ClawBotShell from '@/components/ClawBotShell.vue'
import CommandRecordsPanel from '@/components/CommandRecordsPanel.vue'
import DeliveryRecordsPanel from '@/components/DeliveryRecordsPanel.vue'

const route = useRoute()
const tab = computed(() => (route.query.tab === 'messages' ? 'messages' : 'commands'))
const commandsPanel = useTemplateRef<InstanceType<typeof CommandRecordsPanel>>('commandsPanel')
const deliveriesPanel = useTemplateRef<InstanceType<typeof DeliveryRecordsPanel>>('deliveriesPanel')
const activePanel = computed(() =>
  tab.value === 'commands' ? commandsPanel.value : deliveriesPanel.value,
)
</script>

<template>
  <ClawBotShell
    section="records"
    title="记录"
    description="统一查看微信指令的处理结果和消息发送情况。"
  >
    <template #actions>
      <VButton
        class="cb-button"
        :disabled="!activePanel || activePanel.loading"
        @click="activePanel?.refresh()"
        >刷新记录</VButton
      >
    </template>
    <nav class="record-tabs" aria-label="记录分类">
      <RouterLink
        to="/clawbot/records"
        :class="{ active: tab === 'commands' }"
        :aria-current="tab === 'commands' ? 'page' : undefined"
        >指令处理</RouterLink
      >
      <RouterLink
        :to="{ path: '/clawbot/records', query: { tab: 'messages' } }"
        :class="{ active: tab === 'messages' }"
        :aria-current="tab === 'messages' ? 'page' : undefined"
        >消息发送</RouterLink
      >
    </nav>
    <CommandRecordsPanel v-if="tab === 'commands'" ref="commandsPanel" />
    <DeliveryRecordsPanel v-else ref="deliveriesPanel" />
  </ClawBotShell>
</template>

<style scoped>
.record-tabs {
  display: flex;
  gap: 4px;
  width: fit-content;
  max-width: 100%;
  margin-bottom: 20px;
  padding: 4px;
  border-radius: 11px;
  background: var(--cb-hover);
}
.record-tabs a {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 36px;
  padding: 7px 18px;
  border-radius: 8px;
  color: var(--cb-muted);
  font-size: 13px;
  text-decoration: none;
}
.record-tabs a.active {
  background: var(--cb-bg);
  color: var(--cb-text);
  font-weight: 500;
}
.record-tabs a:hover {
  color: var(--cb-text);
}
@media (pointer: coarse) {
  .record-tabs a {
    min-height: 44px;
  }
}
</style>
