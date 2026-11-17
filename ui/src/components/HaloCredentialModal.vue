<script setup lang="ts">
import { VButton } from '@halo-dev/components'
import ClawBotModal from '@/components/ClawBotModal.vue'

defineEmits<{ close: [] }>()
</script>

<template>
  <ClawBotModal title="创建个人访问令牌" :width="1040" @close="$emit('close')">
    <p class="credential-modal-help">
      在下方点击“新建”，创建并复制令牌后，关闭此窗口回到连接页面粘贴保存。
      查询选择“评论查看”，发布回复选择“评论管理”；显示文章或页面标题需对应查看权限。无需授予超级管理员角色。
    </p>
    <iframe
      src="/uc/profile?tab=pat"
      title="Halo 个人访问令牌管理"
      allow="clipboard-write"
      class="credential-frame"
    />
    <template #footer="{ close }">
      <VButton class="cb-button cb-primary" type="secondary" @click="close"
        >完成，返回填写令牌</VButton
      >
    </template>
  </ClawBotModal>
</template>

<style scoped>
.credential-modal-help {
  margin-bottom: 16px;
  color: var(--cb-muted);
  font-size: 13px;
  line-height: 1.8;
}
.credential-frame {
  display: block;
  width: 100%;
  height: 66vh;
  border: 1px solid var(--cb-border);
  border-radius: 10px;
  background: #fff;
}
</style>
