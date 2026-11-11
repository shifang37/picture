import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { message } from 'ant-design-vue'
import router from '@/router'

// 是否为首次获取登录用户
let firstFetchLoginUser = true

/**
 * 全局权限校验：具体规则由路由的 meta 声明，新增页面时只改路由表即可
 */
router.beforeEach(async (to) => {
  const loginUserStore = useLoginUserStore()
  let loginUser = loginUserStore.loginUser
  // 确保页面刷新，首次加载时，能够等后端返回用户信息后再校验权限
  if (firstFetchLoginUser) {
    await loginUserStore.fetchLoginUser()
    loginUser = loginUserStore.loginUser
    firstFetchLoginUser = false
  }
  if (to.meta.requiresAdmin) {
    if (!loginUser || loginUser.userRole !== 'admin') {
      message.error('没有权限')
      return `/user/login?redirect=${encodeURIComponent(to.fullPath)}`
    }
  }
  if (to.meta.requiresLogin && !loginUser?.id) {
    message.warning('请先登录')
    return `/user/login?redirect=${encodeURIComponent(to.fullPath)}`
  }
  return true
})
