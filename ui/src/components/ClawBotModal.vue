<script setup lang="ts">
import { onUnmounted, useTemplateRef } from 'vue'
import { VButton, VModal } from '@halo-dev/components'
import IconClose from '~icons/ri/close-line'

withDefaults(defineProps<{ title: string; width?: number }>(), { width: 800 })
defineEmits<{ close: [] }>()
const modal = useTemplateRef<InstanceType<typeof VModal>>('modal')
const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
function close() {
  modal.value?.close()
}
function trapFocus(event: KeyboardEvent) {
  const root = event.currentTarget as HTMLElement
  const targets = root.querySelectorAll<HTMLElement>(
    'button:not(:disabled), a[href], input:not(:disabled), textarea:not(:disabled), select:not(:disabled), iframe, [tabindex]:not([tabindex="-1"])',
  )
  const first = targets[0],
    last = targets[targets.length - 1]
  if (event.shiftKey && (document.activeElement === first || document.activeElement === root)) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}
onUnmounted(() => {
  if (previousFocus?.isConnected) previousFocus.focus()
})
</script>

<template>
  <VModal
    ref="modal"
    class="clawbot-modal"
    :title="title"
    :width="width"
    :mount-to-body="true"
    :layer-closable="true"
    :aria-label="title"
    @keydown.tab="trapFocus"
    @close="$emit('close')"
  >
    <template #header>
      <h2 class="cb-modal-title">{{ title }}</h2>
      <VButton class="cb-icon-button" :aria-label="`关闭${title}`" @click="close">
        <IconClose aria-hidden="true" />
      </VButton>
    </template>
    <!-- Keep this root stable: Halo's scrollbar relocates the slot's DOM nodes. -->
    <div class="cb-modal-body"><slot /></div>
    <template #footer><slot name="footer" :close="close" /></template>
  </VModal>
</template>
