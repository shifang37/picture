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
      <a-layout-footer class="footer"> shifang37 </a-layout-footer>
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
  height: 64px;
  padding: 0;
  color: var(--ink-900);
  background: rgba(255, 255, 255, 0.72);
  border-bottom: 1px solid var(--line);
  backdrop-filter: saturate(180%) blur(16px);
  -webkit-backdrop-filter: saturate(180%) blur(16px);
}

.content {
  padding: 32px 24px 64px;
  background: transparent;
}

.page-container {
  width: 100%;
  max-width: 1240px;
  margin: 0 auto;
}

.footer {
  padding: 24px;
  color: var(--ink-400);
  font-size: 12.5px;
  text-align: center;
  letter-spacing: 0.04em;
  background: transparent;
  border-top: 1px solid var(--line);
}

@media (max-width: 720px) {
  .content {
    padding: 20px 14px 48px;
  }
}
</style>
