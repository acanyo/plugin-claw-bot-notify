<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { VButton } from '@halo-dev/components'
import type { DraftPreview } from '@/api/generated'
const props = defineProps<{ preview: DraftPreview }>()
const mode = ref<'content' | 'changes'>('content')
const page = ref(1)
const parts = computed(() => props.preview.parts ?? [])
const changes = computed(() => props.preview.changes ?? [])
const labels: Record<string, string> = {
  post_create_draft: '保存文章草稿',
  post_update_draft: '修改文章草稿',
  post_publish_draft: '发布文章草稿',
}
const changeLabels: Record<string, string> = {
  ADDED: '+ 新增',
  REMOVED: '− 删除',
  UNCHANGED: '保留',
}
watch(
  () => props.preview.version,
  () => {
    page.value = 1
    mode.value = 'content'
  },
)
</script>

<template>
  <section class="draft-review" aria-label="文章完整预览">
    <header>
      <div>
        <h3>{{ labels[preview.tool ?? ''] ?? '文章预览' }}</h3>
        <p>
          {{ preview.characters ?? 0 }} 字 · {{ parts.length }} 段 · 版本
          <code>{{ preview.version }}</code>
        </p>
      </div>
      <div class="review-tabs" role="group" aria-label="预览内容">
        <VButton
          class="cb-button"
          size="sm"
          :aria-pressed="mode === 'content'"
          @click="mode = 'content'"
          >全文</VButton
        >
        <VButton
          class="cb-button"
          size="sm"
          :aria-pressed="mode === 'changes'"
          @click="mode = 'changes'"
          >修改差异</VButton
        >
      </div>
    </header>
    <template v-if="mode === 'content'">
      <h4>{{ preview.title }}</h4>
      <pre class="article-part" tabindex="0">{{ parts[page - 1] }}</pre>
      <nav v-if="parts.length > 1" aria-label="全文分段" class="part-navigation">
        <VButton class="cb-button" size="sm" :disabled="page <= 1" @click="page--">上一段</VButton>
        <span role="status" aria-live="polite">第 {{ page }} / {{ parts.length }} 段</span>
        <VButton class="cb-button" size="sm" :disabled="page >= parts.length" @click="page++"
          >下一段</VButton
        >
      </nav>
    </template>
    <div v-else class="article-changes" tabindex="0" aria-label="完整修改差异">
      <p v-if="preview.beforeTitle !== preview.title" class="title-change">
        标题：{{ preview.beforeTitle || '（空）' }} → {{ preview.title }}
      </p>
      <p v-if="!changes.length">正文未变化。</p>
      <div
        v-for="(change, index) in changes"
        :key="index"
        class="change-block"
        :data-kind="change.kind"
      >
        <span>{{ changeLabels[change.kind ?? ''] ?? '修改' }}</span>
        <pre>{{ change.text }}</pre>
      </div>
    </div>
    <p class="confirmation-hint">
      核对完整内容后，在微信引用最新有效的预览发送 <code>{{ preview.confirmation }}</code
      >。
    </p>
  </section>
</template>

<style scoped>
.draft-review {
  display: grid;
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--cb-border);
  border-radius: 10px;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
h3 {
  font-size: 14px;
  font-weight: 600;
}
header p,
.confirmation-hint,
.part-navigation {
  color: var(--cb-muted);
  font-size: 12px;
  line-height: 1.8;
}
h4 {
  font-size: 16px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.review-tabs,
.part-navigation {
  display: flex;
  align-items: center;
  gap: 10px;
}
.review-tabs [aria-pressed='true'] {
  color: var(--cb-accent);
  background: var(--cb-accent-soft);
}
.part-navigation {
  justify-content: center;
}
pre {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.9;
}
.article-part,
.article-changes {
  padding: 16px;
  background: var(--cb-subtle);
  border-radius: 8px;
  max-height: 480px;
  overflow: auto;
}
.change-block {
  padding: 8px 12px;
  border-left: 3px solid var(--cb-border);
}
.change-block > span {
  font-size: 11px;
  color: var(--cb-muted);
}
.change-block[data-kind='ADDED'] {
  border-color: var(--cb-accent);
  background: var(--cb-accent-soft);
}
.change-block[data-kind='REMOVED'] {
  border-color: var(--cb-danger);
}
.change-block[data-kind='REMOVED'] > span {
  color: var(--cb-danger);
}
.title-change {
  font-size: 13px;
  margin-bottom: 12px;
  overflow-wrap: anywhere;
}
.confirmation-hint code {
  color: var(--cb-text);
  overflow-wrap: anywhere;
}
</style>
