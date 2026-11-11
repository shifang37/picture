<template>
  <div id="notFoundPage">
    <a-result status="404" title="404" sub-title="抱歉，你访问的页面不存在或已被删除">
      <template #extra>
        <a-space>
          <a-button type="primary" @click="goHome">返回首页</a-button>
          <a-button @click="goBack">返回上一页</a-button>
        </a-space>
      </template>
    </a-result>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const goHome = () => {
  router.replace('/')
}

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.replace('/')
  }
}
</script>

<style scoped>
#notFoundPage {
  max-width: 720px;
  margin: 60px auto;
}
</style>
