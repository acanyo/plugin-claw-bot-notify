<script setup lang="ts">
import { VLoading } from '@halo-dev/components'
import DeliveryRecords from '@/components/DeliveryRecords.vue'
import { useDeliveryRecords } from '@/composables/useDeliveryRecords'

const { items, page, size, total, loading, error, refresh } = useDeliveryRecords()
defineExpose({ refresh, loading })
</script>

<template>
  <div>
    <p v-if="error" class="cb-notice cb-page-error" role="alert">{{ error }}</p>
    <VLoading v-if="loading && items.length === 0" />
    <DeliveryRecords
      v-else-if="items.length > 0 || !error"
      :items="items"
      :page="page"
      :size="size"
      :total="total"
      @update:page="page = $event"
      @update:size="size = $event"
    />
  </div>
</template>
