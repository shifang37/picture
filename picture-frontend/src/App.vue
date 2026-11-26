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

// Ant Design Vue 4 的全局主题：与 styles/theme.css 中的设计系统变量保持一致
const themeConfig = {
  token: {
    colorPrimary: '#2563eb',
    colorInfo: '#2563eb',
    colorLink: '#2563eb',
    colorSuccess: '#10b981',
    colorWarning: '#d97706',
    colorError: '#e11d48',
    colorTextBase: '#18181b',
    colorTextSecondary: '#52525b',
    colorBorder: 'rgba(24, 24, 27, 0.12)',
    colorBorderSecondary: 'rgba(24, 24, 27, 0.08)',
    colorBgLayout: '#fafaf8',
    colorBgContainer: '#ffffff',
    borderRadius: 10,
    borderRadiusLG: 16,
    fontSize: 14,
    controlHeight: 38,
    fontFamily:
      "'Plus Jakarta Sans', 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
  },
}

const loginUserStore = useLoginUserStore()
loginUserStore.fetchLoginUser()
</script>
