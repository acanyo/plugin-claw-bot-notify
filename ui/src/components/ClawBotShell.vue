<script setup lang="ts">
import { RouterLink } from 'vue-router'
import logoUrl from '@/assets/logo.svg?url'
import '@/styles/workspace.css'

defineProps<{ section: 'connection' | 'records' | 'setup'; title: string; description: string }>()
</script>

<template>
  <div class="clawbot-workspace">
    <header class="cb-header">
      <RouterLink to="/clawbot" class="cb-brand" aria-label="ClawBot 连接管理">
        <span class="cb-brand-mark"><img :src="logoUrl" alt="" width="30" height="30" /></span>
        <strong>ClawBot</strong><span class="cb-brand-caption">微信站点助手</span>
      </RouterLink>
      <nav class="cb-nav" aria-label="ClawBot 导航">
        <RouterLink
          to="/clawbot"
          :class="{ active: section === 'connection' }"
          :aria-current="section === 'connection' ? 'page' : undefined"
          >连接管理</RouterLink
        >
        <RouterLink
          to="/clawbot/records"
          :class="{ active: section === 'records' }"
          :aria-current="section === 'records' ? 'page' : undefined"
          >记录</RouterLink
        >
        <RouterLink
          to="/clawbot/setup"
          :class="{ active: section === 'setup' }"
          :aria-current="section === 'setup' ? 'page' : undefined"
          >环境设置</RouterLink
        >
      </nav>
      <div class="cb-header-actions"><slot name="actions" /></div>
    </header>
    <main class="cb-content">
      <div class="cb-page-heading">
        <h1>{{ title }}</h1>
        <p>{{ description }}</p>
      </div>
      <slot />
    </main>
  </div>
</template>
