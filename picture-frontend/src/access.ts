import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { message } from 'ant-design-vue'
import router from '@/router'

// 是否为首次获取登录用户
let firstFetchLoginUser = true

/**
 * 全局权限校验
 */
router.beforeEach(async (to, from) => {
  const loginUserStore = useLoginUserStore()
  let loginUser = loginUserStore.loginUser
  // 确保页面刷新，首次加载时，能够等后端返回用户信息后再校验权限
  if (firstFetchLoginUser) {
    await loginUserStore.fetchLoginUser()
    loginUser = loginUserStore.loginUser
    firstFetchLoginUser = false
  }
  const toUrl = to.fullPath
  if (toUrl.startsWith('/admin')) {
    if (!loginUser || loginUser.userRole !== 'admin') {
      message.error('没有权限')
      return `/user/login?redirect=${encodeURIComponent(to.fullPath)}`
    }
  }
  // 普通用户上传、查看自己的图片、个人中心需要登录
  const requiresLogin =
    to.path === '/add_picture' ||
    to.path === '/batch_upload' ||
    to.path === '/my_pictures' ||
    to.path === '/user/profile'
  if (requiresLogin && !loginUser?.id) {
    message.warning('请先登录')
    return `/user/login?redirect=${encodeURIComponent(to.fullPath)}`
  }
  return true
})
