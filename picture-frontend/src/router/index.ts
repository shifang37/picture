import { createRouter, createWebHistory } from 'vue-router'
// 首页保持静态导入，保证首屏不额外多一次请求；其余页面全部按需加载
import HomePage from '@/pages/HomePage.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  // 首页自己恢复滚动位置（keep-alive + sessionStorage），其余页面沿用浏览器前进后退的位置
  scrollBehavior(to, from, savedPosition) {
    if (to.meta.keepAlive) {
      return false
    }
    return savedPosition ?? { top: 0 }
  },
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomePage,
      // 首页使用 keep-alive，从详情页返回时保留已加载的列表和筛选条件
      meta: { keepAlive: true },
    },
    {
      path: '/user/login',
      name: '用户登录',
      component: () => import('@/pages/user/UserLoginPage.vue'),
    },
    {
      path: '/user/register',
      name: '用户注册',
      component: () => import('@/pages/user/UserRegisterPage.vue'),
    },
    {
      path: '/user/profile',
      name: '个人中心',
      component: () => import('@/pages/user/UserProfilePage.vue'),
      meta: { requiresLogin: true },
    },
    {
      path: '/admin/pictureReview',
      name: '图片审核',
      component: () => import('@/pages/admin/PictureReviewPage.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin/pictureManage',
      name: '图片管理',
      component: () => import('@/pages/admin/PictureManagePage.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin/userManage',
      name: '用户管理',
      component: () => import('@/pages/admin/UserManagePage.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin/tagCategoryManage',
      name: '标签分类管理',
      component: () => import('@/pages/admin/TagCategoryManagePage.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/add_picture',
      name: '创建图片',
      component: () => import('@/pages/AddPicturePage.vue'),
      meta: { requiresLogin: true },
    },
    {
      path: '/batch_upload',
      name: '批量上传',
      component: () => import('@/pages/BatchUploadPage.vue'),
      meta: { requiresLogin: true },
    },
    {
      path: '/my_pictures',
      name: '我的图片',
      component: () => import('@/pages/MyPicturesPage.vue'),
      meta: { requiresLogin: true },
    },
    {
      path: '/picture/:id',
      name: '图片详情',
      component: () => import('@/pages/PictureDetailPage.vue'),
      props: true,
    },
    {
      // 兜底路由：未匹配的站内地址统一显示 404 页面
      path: '/:pathMatch(.*)*',
      name: 'notFound',
      component: () => import('@/pages/NotFoundPage.vue'),
    },
  ],
})

export default router
