<template>
  <a-config-provider :theme="themeConfig">
    <div id="app">
      <BasicLayout />
    </div>
  </a-config-provider>
</template>

<script setup lang="ts">
import BasicLayout from './layouts/BasicLayout.vue'
import { useLoginUserStore } from './stores/useLoginUserStore'

// Ant Design Vue 4 的全局主题：与 styles/theme.css 中的 CSS 变量保持一致
const themeConfig = {
  token: {
    colorPrimary: '#4f46e5',
    colorInfo: '#4f46e5',
    colorLink: '#4f46e5',
    colorTextBase: '#0b1220',
    colorTextSecondary: '#64748b',
    colorBorder: 'rgba(15, 23, 42, 0.14)',
    colorBgLayout: '#f6f7fb',
    borderRadius: 10,
    fontSize: 14,
    controlHeight: 36,
    fontFamily:
      "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
  },
}

const loginUserStore = useLoginUserStore()
loginUserStore.fetchLoginUser()
</script>
