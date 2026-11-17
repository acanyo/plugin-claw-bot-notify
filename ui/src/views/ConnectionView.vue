<script setup lang="ts">
import { VButton, VEmpty, VLoading } from '@halo-dev/components'
import ConnectionPanel from '@/components/ConnectionPanel.vue'
import { useClawBot } from '@/composables/useClawBot'
import ClawBotShell from '@/components/ClawBotShell.vue'
import WechatGuide from '@/components/WechatGuide.vue'
import AssistantRuntimePanel from '@/components/AssistantRuntimePanel.vue'
const {
  binding,
  loading,
  busy,
  credentialSaveCount,
  error,
  refresh,
  create,
  verifyCode,
  saveCredential,
  activate,
  disconnect,
  testMessage,
} = useClawBot()
</script>

<template>
  <ClawBotShell
    section="connection"
    title="微信连接"
    description="管理微信连接，检查评论回复与智能助手的运行状态。"
  >
    <template #actions
      ><VButton class="cb-button" :disabled="busy" @click="refresh">刷新状态</VButton></template
    >
    <p v-if="error" class="cb-notice cb-page-error" role="alert">{{ error }}</p>
    <VLoading v-if="loading" />
    <ConnectionPanel
      v-if="binding"
      :binding="binding"
      :busy="busy"
      :credential-save-count="credentialSaveCount"
      @create="create"
      @code="verifyCode"
      @credential="saveCredential"
      @activate="activate"
      @disconnect="disconnect"
      @test="testMessage"
    />
    <VEmpty
      v-else-if="!loading"
      title="暂时无法读取连接"
      message="请确认使用超级管理员账号登录，然后刷新状态。"
    />
    <AssistantRuntimePanel />
    <WechatGuide />
  </ClawBotShell>
</template>
