<script setup lang="ts">
import { useRouter } from 'vue-router'
import ClawBotShell from '@/components/ClawBotShell.vue'
import CliSetupGuide from '@/components/CliSetupGuide.vue'
import { useCliSetup } from '@/composables/useCliSetup'
const { status, loading, error, detect, install } = useCliSetup()
const router = useRouter()
</script>
<template>
  <ClawBotShell
    section="setup"
    title="环境设置"
    description="检测运行 Halo 的服务器环境，准备微信助手的站点管理能力。"
  >
    <CliSetupGuide
      :status="status"
      :loading="loading"
      :error="error"
      @detect="detect"
      @install="install"
      @finish="router.push('/clawbot')"
      @skip="router.push({ path: '/clawbot', query: { setup: 'later' } })"
    />
  </ClawBotShell>
</template>
