<template>
  <div class="header-inner">
    <RouterLink to="/" class="brand">
      <span class="brand-mark">
        <img class="logo" src="../assets/logo.svg" alt="shifang37 云图库" />
      </span>
      <span class="brand-text">
        <span class="brand-name">shifang37</span>
        <span class="brand-sub">云图库</span>
      </span>
    </RouterLink>
    <nav class="nav">
      <a-menu
        v-model:selectedKeys="current"
        class="nav-menu"
        mode="horizontal"
        :items="items"
        @click="doMenuClick"
      />
    </nav>
    <div class="actions">
      <NotificationBell v-if="loginUserStore.loginUser.id" class="notification-bell" />
      <a-dropdown v-if="loginUserStore.loginUser.id" placement="bottomRight">
        <button class="user-chip" type="button" aria-label="账号菜单">
          <a-avatar :src="loginUserStore.loginUser.userAvatar" :size="28" />
          <span class="user-name">
            {{
              loginUserStore.loginUser.userName || loginUserStore.loginUser.userAccount || '无名'
            }}
          </span>
        </button>
        <template #overlay>
          <a-menu>
            <a-menu-item @click="doProfile">
              <UserOutlined />
              个人中心
            </a-menu-item>
            <a-menu-item @click="doLogout">
              <LogoutOutlined />
              退出登录
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
      <a-button v-else type="primary" class="login-btn" @click="doLogin">登录</a-button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { h, ref, computed, onUnmounted, watch } from 'vue'
import { HomeOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons-vue'
import type { MenuProps } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { useNotificationStore } from '@/stores/useNotificationStore'
import { userLogout } from '@/api/userController'
import NotificationBell from '@/components/NotificationBell.vue'

const loginUserStore = useLoginUserStore()
const notificationStore = useNotificationStore()

// 登录后启动未读轮询、登出后停止：顶栏铃铛在任何页面都可用
watch(
  () => loginUserStore.loginUser.id,
  (id) => {
    if (id) {
      notificationStore.startPolling()
    } else {
      notificationStore.reset()
    }
  },
  { immediate: true },
)

onUnmounted(() => {
  notificationStore.stopPolling()
})

// 菜单列表
const originItems = [
  {
    key: '/',
    icon: () => h(HomeOutlined),
    label: '主页',
    title: '主页',
  },
  {
    key: '/add_picture',
    label: '创建图片',
    title: '创建图片',
  },
  {
    key: '/batch_upload',
    label: '批量上传',
    title: '批量上传',
  },
  {
    key: '/my_pictures',
    label: '我的图片',
    title: '我的图片',
  },
  {
    key: '/my/favorites',
    label: '我的收藏',
    title: '我的收藏',
  },
  {
    key: '/my/albums',
    label: '我的专辑',
    title: '我的专辑',
  },
  {
    key: '/admin/dashboard',
    label: '数据看板',
    title: '数据看板',
  },
  {
    key: '/admin/pictureReview',
    label: '图片审核',
    title: '图片审核',
  },
  {
    key: '/admin/pictureManage',
    label: '图片管理',
    title: '图片管理',
  },
  {
    key: '/admin/pictureRecycle',
    label: '图片回收站',
    title: '图片回收站',
  },
  {
    key: '/admin/userManage',
    label: '用户管理',
    title: '用户管理',
  },
  {
    key: '/admin/tagCategoryManage',
    label: '标签分类管理',
    title: '标签分类管理',
  },
]

// 过滤菜单项
const filterMenus = (menus = [] as MenuProps['items']) => {
  const loginUser = loginUserStore.loginUser
  return menus?.filter((menu) => {
    const menuKey = menu?.key
    if (typeof menuKey !== 'string') {
      return true
    }
    // 管理菜单仅管理员可见
    if (menuKey.startsWith('/admin') && loginUser?.userRole !== 'admin') {
      return false
    }
    // 上传、「我的图片」「我的收藏」「我的专辑」需要登录
    if (
      (menuKey === '/add_picture' ||
        menuKey === '/batch_upload' ||
        menuKey === '/my_pictures' ||
        menuKey === '/my/favorites' ||
        menuKey === '/my/albums') &&
      !loginUser?.id
    ) {
      return false
    }
    return true
  })
}

// 展示在菜单的路由数组
const items = computed<MenuProps['items']>(() => filterMenus(originItems))

const router = useRouter()
const doLogin = () => {
  router.push('/user/login')
}
const doProfile = () => {
  router.push('/user/profile')
}
const doMenuClick: MenuProps['onClick'] = ({ key }) => {
  // 菜单 key 可能是 number，只处理字符串路径
  if (typeof key !== 'string') {
    return
  }
  router.push({
    path: key,
  })
}
// 当前选中菜单
const current = ref<string[]>([])
// 监听路由变化，更新当前选中菜单
router.afterEach((to) => {
  current.value = [to.path]
})
// 用户注销
const doLogout = async () => {
  try {
    const res = await userLogout()
    if (res.data.code === 0) {
      loginUserStore.setLoginUser({
        userName: '未登录',
      })
      message.success('退出登录成功')
      await router.push('/user/login')
    } else {
      message.error('退出登录失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('退出登录失败：' + (error.message || '系统错误'))
  }
}
</script>

<style scoped>
.header-inner {
  display: flex;
  align-items: center;
  gap: 20px;
  max-width: 1240px;
  height: 64px;
  margin: 0 auto;
  padding: 0 24px;
}

.brand {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
}

.brand-mark {
  display: grid;
  width: 38px;
  height: 38px;
  background: linear-gradient(135deg, var(--brand) 0%, var(--brand-2) 100%);
  border-radius: 12px;
  box-shadow: 0 10px 22px -10px rgba(79, 70, 229, 0.8);
  place-items: center;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease;
}

.brand:hover .brand-mark {
  box-shadow: 0 14px 26px -10px rgba(79, 70, 229, 0.9);
  transform: translateY(-1px) rotate(-4deg);
}

.logo {
  display: block;
  width: 19px;
  height: 19px;
  filter: brightness(0) invert(1);
}

.brand-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  line-height: 1.1;
}

.brand-name {
  color: var(--ink-900);
  font-weight: 700;
  font-size: 15.5px;
  letter-spacing: -0.01em;
}

.brand-sub {
  color: var(--ink-400);
  font-size: 10.5px;
  letter-spacing: 0.22em;
}

.nav {
  flex: 1;
  min-width: 0;
}

.nav-menu {
  background: transparent;
  border-bottom: none;
  font-weight: 500;
}

.nav-menu :deep(.ant-menu-item) {
  padding-inline: 12px;
  border-radius: 10px;
  transition:
    color 0.18s ease,
    background-color 0.18s ease;
}

.nav-menu :deep(.ant-menu-item:hover) {
  background: var(--brand-soft);
}

.nav-menu :deep(.ant-menu-item::after) {
  display: none !important;
}

.nav-menu :deep(.ant-menu-item-selected) {
  color: var(--brand-600);
  font-weight: 600;
  background: var(--brand-soft);
}

.actions {
  display: flex;
  flex: none;
  gap: 12px;
  align-items: center;
}

.notification-bell {
  cursor: pointer;
}

.user-chip {
  display: flex;
  gap: 8px;
  align-items: center;
  max-width: 180px;
  padding: 4px 12px 4px 4px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 999px;
  cursor: pointer;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}

.user-chip:hover {
  border-color: var(--brand-ring);
  box-shadow: var(--shadow-sm);
}

.user-name {
  overflow: hidden;
  color: var(--ink-700);
  font-size: 13px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.login-btn {
  padding-inline: 20px;
  border-radius: 999px;
}

@media (max-width: 720px) {
  .header-inner {
    gap: 12px;
    padding: 0 14px;
  }

  .brand-sub {
    display: none;
  }

  .nav-menu :deep(.ant-menu-item) {
    padding-inline: 8px;
  }
}
</style>
