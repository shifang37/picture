<template>
  <div id="basicLayout">
    <a-layout class="shell">
      <a-layout-header class="header">
        <GlobalHeader />
      </a-layout-header>
      <a-layout-content class="content">
        <div class="page-container">
          <!-- 只缓存声明了 meta.keepAlive 的页面（当前仅首页），其余页面每次进入都重新创建 -->
          <router-view v-slot="{ Component }">
            <keep-alive>
              <component :is="Component" v-if="$route.meta.keepAlive" />
            </keep-alive>
            <component :is="Component" v-if="!$route.meta.keepAlive" />
          </router-view>
        </div>
      </a-layout-content>
      <a-layout-footer class="footer">
        <div class="footer-inner">
          <span class="footer-brand">shifang37 · 云图库</span>
          <span class="footer-dot" aria-hidden="true">·</span>
          <span class="footer-note">图片素材管理、策展与智能检索平台</span>
        </div>
      </a-layout-footer>
    </a-layout>
  </div>
</template>

<script setup lang="ts">
import GlobalHeader from '@/components/GlobalHeader.vue'
</script>

<style scoped>
.shell {
  min-height: 100vh;
  background: transparent;
}

.header {
  position: sticky;
  top: 0;
  z-index: 100;
  height: 68px;
  padding: 0;
  color: var(--ink-900);
  background: rgba(250, 250, 248, 0.84);
  border-bottom: 1px solid var(--line);
  backdrop-filter: saturate(180%) blur(18px);
  -webkit-backdrop-filter: saturate(180%) blur(18px);
}

.content {
  padding: 32px 24px 72px;
  background: transparent;
}

.page-container {
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
}

.footer {
  padding: 26px 24px;
  color: var(--ink-400);
  font-size: 12.5px;
  background: transparent;
  border-top: 1px solid var(--line);
}

.footer-inner {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: center;
  max-width: 1280px;
  margin: 0 auto;
}

.footer-brand {
  color: var(--ink-700);
  font-weight: 600;
  letter-spacing: 0.02em;
}

.footer-dot {
  color: var(--ink-300);
}

.footer-note {
  letter-spacing: 0.02em;
}

@media (max-width: 720px) {
  .header {
    height: 60px;
  }

  .content {
    padding: 20px 14px 52px;
  }
}
</style>
