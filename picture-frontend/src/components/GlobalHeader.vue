<template>
  <a-row :wrap="false">
    <a-col flex="200px">
      <RouterLink to="/">
        <div class="title-bar">
          <img class="logo" src="../assets/logo.svg" alt="logo" />
          <div class="title">鱼皮云图库</div>
        </div>
      </RouterLink>
    </a-col>
    <a-col flex="auto">
      <a-menu
        v-model:selectedKeys="current"
        mode="horizontal"
        :items="items"
        @click="doMenuClick"
      />
    </a-col>
    <a-col flex="220px">
      <div class="user-login-status">
        <NotificationBell v-if="loginUserStore.loginUser.id" class="notification-bell" />
        <div v-if="loginUserStore.loginUser.id">
          <a-dropdown>
            <ASpace>
              <a-avatar :src="loginUserStore.loginUser.userAvatar" />
              {{
                loginUserStore.loginUser.userName || loginUserStore.loginUser.userAccount || '无名'
              }}
            </ASpace>
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
        </div>
        <a-button v-else type="primary" size="small" @click="doLogin">登录</a-button>
      </div>
    </a-col>
  </a-row>
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
    // 上传、「我的图片」和「我的收藏」需要登录
    if (
      (menuKey === '/add_picture' ||
        menuKey === '/batch_upload' ||
        menuKey === '/my_pictures' ||
        menuKey === '/my/favorites') &&
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
.title-bar {
  display: flex;
  align-items: center;
}

.title {
  color: black;
  font-size: 18px;
  margin-left: 16px;
}

.logo {
  height: 48px;
}

.user-login-status {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  height: 100%;
}

.notification-bell {
  cursor: pointer;
}
</style>
