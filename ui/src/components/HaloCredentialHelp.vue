<script setup lang="ts">
import { ref } from 'vue'
import { VButton } from '@halo-dev/components'
import HaloCredentialModal from '@/components/HaloCredentialModal.vue'

defineProps<{ username?: string }>()
const creationVisible = ref(false)
</script>

<template>
  <aside class="credential-help" aria-labelledby="credential-help-title">
    <div class="credential-help-heading">
      <h3 id="credential-help-title">还没有个人访问令牌？</h3>
      <VButton class="cb-button" size="sm" @click="creationVisible = true"
        >创建个人访问令牌</VButton
      >
    </div>
    <p>
      {{ username ? `请使用 Halo 用户「${username}」创建。` : '请使用发起绑定的 Halo 账号创建。' }}
      点击按钮，在弹出的小窗口内按以下步骤操作：
    </p>
    <ol>
      <li>点击“新建”，名称可填写“微信 ClawBot”，并设置过期时间。</li>
      <li>
        查询需勾选“评论查看”；通过微信发布回复需勾选“评论管理”权限。文章或页面标题需要对应的查看权限；无需勾选超级管理员角色。
      </li>
      <li>创建后复制仅展示一次的完整令牌，关闭小窗口后粘贴，再点击“验证并保存令牌”。</li>
    </ol>
  </aside>
  <HaloCredentialModal v-if="creationVisible" @close="creationVisible = false" />
</template>

<style scoped>
.credential-help {
  margin-bottom: 20px;
  padding: 16px;
  border: 1px solid var(--cb-border);
  border-radius: 10px;
  background: var(--cb-subtle);
  color: var(--cb-muted);
  font-size: 13px;
  line-height: 1.8;
}
.credential-help-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px 16px;
  margin-bottom: 8px;
}
h3 {
  color: var(--cb-text);
  font-size: 14px;
  font-weight: 600;
}
ol {
  list-style: decimal;
  margin: 8px 0 0;
  padding-left: 20px;
}
li + li {
  margin-top: 4px;
}
</style>
