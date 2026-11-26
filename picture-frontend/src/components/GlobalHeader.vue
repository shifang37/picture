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

    <nav class="nav" aria-label="主导航">
      <RouterLink
        v-for="item in primaryLinks"
        :key="item.path"
        :to="item.path"
        class="nav-pill"
        :class="{ 'is-active': isPathActive(item.path) }"
      >
        <component :is="item.icon" class="nav-pill-icon" />
        <span>{{ item.label }}</span>
      </RouterLink>

      <!-- 我的空间分组：避免所有一级菜单横向堆叠挤出屏幕 -->
      <a-dropdown v-if="isLoggedIn" placement="bottomLeft" :trigger="['hover', 'click']">
        <button
          type="button"
          class="nav-pill nav-dropdown-trigger"
          :class="{ 'is-active': isSpaceActive }"
        >
          <FolderOutlined class="nav-pill-icon" />
          <span>我的空间</span>
          <DownOutlined class="nav-caret" />
        </button>
        <template #overlay>
          <a-menu :selected-keys="selectedKeys" @click="doMenuClick">
            <a-menu-item key="/my_pictures">
              <PictureOutlined />
              我的图片
            </a-menu-item>
            <a-menu-item key="/my/favorites">
              <StarOutlined />
              我的收藏
            </a-menu-item>
            <a-menu-item key="/my/albums">
              <AppstoreOutlined />
              我的专辑
            </a-menu-item>
            <a-menu-divider />
            <a-menu-item key="/batch_upload">
              <CloudUploadOutlined />
              批量上传
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>

      <!-- 管理后台分组：6 个管理页收敛到一个清晰下拉菜单 -->
      <a-dropdown v-if="isAdmin" placement="bottomLeft" :trigger="['hover', 'click']">
        <button
          type="button"
          class="nav-pill nav-dropdown-trigger"
          :class="{ 'is-active': isAdminActive }"
        >
          <SettingOutlined class="nav-pill-icon" />
          <span>管理后台</span>
          <DownOutlined class="nav-caret" />
        </button>
        <template #overlay>
          <a-menu :selected-keys="selectedKeys" @click="doMenuClick">
            <a-menu-item key="/admin/dashboard">
              <DashboardOutlined />
              数据看板
            </a-menu-item>
            <a-menu-item key="/admin/pictureReview">
              <AuditOutlined />
              图片审核
            </a-menu-item>
            <a-menu-item key="/admin/pictureManage">
              <PictureOutlined />
              图片管理
            </a-menu-item>
            <a-menu-item key="/admin/pictureRecycle">
              <RestOutlined />
              图片回收站
            </a-menu-item>
            <a-menu-divider />
            <a-menu-item key="/admin/userManage">
              <TeamOutlined />
              用户管理
            </a-menu-item>
            <a-menu-item key="/admin/tagCategoryManage">
              <TagsOutlined />
              标签分类管理
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
    </nav>

    <div class="actions">
      <RouterLink v-if="isLoggedIn" to="/add_picture" class="upload-cta">
        <PlusOutlined />
        <span class="upload-cta-text">上传图片</span>
      </RouterLink>

      <NotificationBell v-if="isLoggedIn" class="notification-bell" />

      <a-dropdown v-if="isLoggedIn" placement="bottomRight">
        <button class="user-chip" type="button" aria-label="账号菜单">
          <a-avatar
            :src="loginUserStore.loginUser.userAvatar || undefined"
            :size="28"
            class="user-avatar"
          >
            {{ userInitial }}
          </a-avatar>
          <span class="user-name">{{ displayName }}</span>
          <DownOutlined class="user-caret" />
        </button>
        <template #overlay>
          <a-menu>
            <a-menu-item @click="doProfile">
              <UserOutlined />
              个人中心
            </a-menu-item>
            <a-menu-item @click="router.push('/my_pictures')">
              <PictureOutlined />
              我的图片
            </a-menu-item>
            <a-menu-item @click="router.push('/my/favorites')">
              <StarOutlined />
              我的收藏
            </a-menu-item>
            <a-menu-item @click="router.push('/my/albums')">
              <AppstoreOutlined />
              我的专辑
            </a-menu-item>
            <a-menu-divider />
            <a-menu-item @click="doLogout">
              <LogoutOutlined />
              退出登录
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>

      <a-button v-else type="primary" class="login-btn" @click="doLogin">登录</a-button>

      <!-- 移动端抽屉触发器 -->
      <button
        type="button"
        class="mobile-menu-btn"
        aria-label="打开导航菜单"
        @click="mobileMenuOpen = true"
      >
        <MenuOutlined />
      </button>
    </div>

    <a-drawer
      v-model:open="mobileMenuOpen"
      placement="right"
      :width="280"
      title="导航菜单"
      class="mobile-drawer"
    >
      <div class="mobile-nav-list">
        <div class="mobile-section-label">发现</div>
        <RouterLink to="/" class="mobile-nav-item" @click="mobileMenuOpen = false">
          <HomeOutlined />
          <span>主页</span>
        </RouterLink>
        <RouterLink
          v-if="ragConfig.askEnabled && isLoggedIn"
          to="/ask"
          class="mobile-nav-item"
          @click="mobileMenuOpen = false"
        >
          <BulbOutlined />
          <span>图库问答</span>
        </RouterLink>

        <template v-if="isLoggedIn">
          <div class="mobile-section-label">我的空间</div>
          <RouterLink to="/add_picture" class="mobile-nav-item" @click="mobileMenuOpen = false">
            <PlusOutlined />
            <span>创建图片</span>
          </RouterLink>
          <RouterLink to="/batch_upload" class="mobile-nav-item" @click="mobileMenuOpen = false">
            <CloudUploadOutlined />
            <span>批量上传</span>
          </RouterLink>
          <RouterLink to="/my_pictures" class="mobile-nav-item" @click="mobileMenuOpen = false">
            <PictureOutlined />
            <span>我的图片</span>
          </RouterLink>
          <RouterLink to="/my/favorites" class="mobile-nav-item" @click="mobileMenuOpen = false">
            <StarOutlined />
            <span>我的收藏</span>
          </RouterLink>
          <RouterLink to="/my/albums" class="mobile-nav-item" @click="mobileMenuOpen = false">
            <AppstoreOutlined />
            <span>我的专辑</span>
          </RouterLink>
        </template>

        <template v-if="isAdmin">
          <div class="mobile-section-label">管理后台</div>
          <RouterLink to="/admin/dashboard" class="mobile-nav-item" @click="mobileMenuOpen = false">
            <DashboardOutlined />
            <span>数据看板</span>
          </RouterLink>
          <RouterLink
            to="/admin/pictureReview"
            class="mobile-nav-item"
            @click="mobileMenuOpen = false"
          >
            <AuditOutlined />
            <span>图片审核</span>
          </RouterLink>
          <RouterLink
            to="/admin/pictureManage"
            class="mobile-nav-item"
            @click="mobileMenuOpen = false"
          >
            <PictureOutlined />
            <span>图片管理</span>
          </RouterLink>
          <RouterLink
            to="/admin/pictureRecycle"
            class="mobile-nav-item"
            @click="mobileMenuOpen = false"
          >
            <RestOutlined />
            <span>图片回收站</span>
          </RouterLink>
          <RouterLink
            to="/admin/userManage"
            class="mobile-nav-item"
            @click="mobileMenuOpen = false"
          >
            <TeamOutlined />
            <span>用户管理</span>
          </RouterLink>
          <RouterLink
            to="/admin/tagCategoryManage"
            class="mobile-nav-item"
            @click="mobileMenuOpen = false"
          >
            <TagsOutlined />
            <span>标签分类管理</span>
          </RouterLink>
        </template>
      </div>
    </a-drawer>
  </div>
</template>

<script lang="ts" setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import {
  AppstoreOutlined,
  AuditOutlined,
  BulbOutlined,
  CloudUploadOutlined,
  DashboardOutlined,
  DownOutlined,
  FolderOutlined,
  HomeOutlined,
  LogoutOutlined,
  MenuOutlined,
  PictureOutlined,
  PlusOutlined,
  RestOutlined,
  SettingOutlined,
  StarOutlined,
  TagsOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import type { MenuProps } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { useNotificationStore } from '@/stores/useNotificationStore'
import { useRagConfig } from '@/composables/useRagConfig'
import { userLogout } from '@/api/userController'
import NotificationBell from '@/components/NotificationBell.vue'

const loginUserStore = useLoginUserStore()
const notificationStore = useNotificationStore()
const route = useRoute()
const router = useRouter()

// 顶栏「图库问答」入口只在 RAG 开启时出现；配置失败时按关闭处理
const { ragConfig, loadRagConfig } = useRagConfig()
loadRagConfig()

const mobileMenuOpen = ref(false)

const isLoggedIn = computed(() => Boolean(loginUserStore.loginUser.id))
const isAdmin = computed(() => loginUserStore.loginUser.userRole === 'admin')
const displayName = computed(
  () => loginUserStore.loginUser.userName || loginUserStore.loginUser.userAccount || '无名',
)
const userInitial = computed(() => displayName.value.slice(0, 1).toUpperCase())

const primaryLinks = computed(() => {
  const list = [{ path: '/', label: '发现', icon: HomeOutlined }]
  if (ragConfig.value.askEnabled && isLoggedIn.value) {
    list.push({ path: '/ask', label: '图库问答', icon: BulbOutlined })
  }
  return list
})

const selectedKeys = computed(() => [route.path])
const isPathActive = (path: string) => route.path === path
const isSpaceActive = computed(() =>
  ['/my_pictures', '/my/favorites', '/my/albums', '/batch_upload', '/add_picture'].includes(
    route.path,
  ),
)
const isAdminActive = computed(() => route.path.startsWith('/admin'))

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

const doLogin = () => {
  router.push('/user/login')
}

const doProfile = () => {
  router.push('/user/profile')
}

const doMenuClick: MenuProps['onClick'] = ({ key }) => {
  if (typeof key !== 'string') {
    return
  }
  router.push({ path: key })
}

const doLogout = async () => {
  try {
    const res = await userLogout()
    if (res.data.code === 0) {
      loginUserStore.setLoginUser({ userName: '未登录' })
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
  gap: 20px;
  align-items: center;
  max-width: 1280px;
  height: 100%;
  margin: 0 auto;
  padding: 0 24px;
}

.brand {
  display: inline-flex;
  flex: none;
  gap: 11px;
  align-items: center;
}

.brand-mark {
  display: grid;
  width: 36px;
  height: 36px;
  background: var(--accent-dark);
  border-radius: 11px;
  box-shadow: 0 8px 18px -10px rgba(24, 24, 27, 0.7);
  place-items: center;
  transition:
    background-color 0.2s ease,
    transform 0.2s ease;
}

.brand:hover .brand-mark {
  background: var(--brand);
  transform: translateY(-1px);
}

.logo {
  display: block;
  width: 20px;
  height: 20px;
  filter: brightness(0) invert(1);
}

.brand-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  line-height: 1.05;
}

.brand-name {
  color: var(--ink-900);
  font-weight: 700;
  font-size: 15.5px;
  letter-spacing: -0.02em;
}

.brand-sub {
  color: var(--ink-400);
  font-size: 10.5px;
  letter-spacing: 0.2em;
}

.nav {
  display: flex;
  flex: 1;
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.nav-pill {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  height: 36px;
  padding: 0 13px;
  color: var(--ink-500);
  font-weight: 500;
  font-size: 13.5px;
  line-height: 1;
  white-space: nowrap;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 999px;
  cursor: pointer;
  transition:
    color 0.18s ease,
    background-color 0.18s ease,
    border-color 0.18s ease;
}

.nav-pill:hover {
  color: var(--ink-900);
  background: rgba(24, 24, 27, 0.045);
}

.nav-pill.is-active {
  color: var(--ink-900);
  font-weight: 600;
  background: var(--surface);
  border-color: var(--line);
  box-shadow: var(--shadow-xs);
}

.nav-pill-icon {
  font-size: 14px;
}

.nav-caret {
  margin-left: 1px;
  font-size: 10px;
  opacity: 0.6;
}

.actions {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
}

.upload-cta {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  height: 36px;
  padding: 0 15px;
  color: #fff;
  font-weight: 600;
  font-size: 13px;
  line-height: 1;
  white-space: nowrap;
  background: var(--accent-dark);
  border-radius: 999px;
  box-shadow: 0 8px 18px -10px rgba(24, 24, 27, 0.65);
  transition:
    background-color 0.18s ease,
    transform 0.18s ease;
}

.upload-cta:hover {
  color: #fff;
  background: var(--brand);
  transform: translateY(-1px);
}

.notification-bell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 999px;
  cursor: pointer;
  transition: background-color 0.18s ease;
}

.notification-bell:hover {
  background: rgba(24, 24, 27, 0.05);
}

.user-chip {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  max-width: 190px;
  height: 36px;
  padding: 3px 12px 3px 4px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  cursor: pointer;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}

.user-chip:hover {
  border-color: var(--line-strong);
  box-shadow: var(--shadow-xs);
}

.user-avatar {
  flex: none;
  color: #fff;
  font-weight: 600;
  font-size: 12px;
  background: var(--brand);
}

.user-name {
  overflow: hidden;
  color: var(--ink-700);
  font-weight: 500;
  font-size: 13px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.user-caret {
  flex: none;
  color: var(--ink-400);
  font-size: 10px;
}

.login-btn {
  height: 36px;
  padding-inline: 20px;
  border-radius: 999px;
}

.mobile-menu-btn {
  display: none;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  color: var(--ink-700);
  font-size: 17px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 10px;
  cursor: pointer;
}

.mobile-nav-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.mobile-section-label {
  margin: 14px 0 6px;
  padding: 0 10px;
  color: var(--ink-400);
  font-weight: 600;
  font-size: 11.5px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.mobile-section-label:first-child {
  margin-top: 0;
}

.mobile-nav-item {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 10px 12px;
  color: var(--ink-700);
  font-weight: 500;
  font-size: 14px;
  border-radius: var(--radius-sm);
  transition: background-color 0.15s ease;
}

.mobile-nav-item:hover,
.mobile-nav-item.router-link-exact-active {
  color: var(--ink-900);
  background: var(--surface-subtle);
}

@media (max-width: 860px) {
  .nav {
    display: none;
  }

  .mobile-menu-btn {
    display: inline-flex;
  }

  .actions {
    margin-left: auto;
  }
}

@media (max-width: 720px) {
  .header-inner {
    gap: 10px;
    padding: 0 14px;
  }

  .brand-sub,
  .upload-cta-text,
  .user-name,
  .user-caret {
    display: none;
  }

  .upload-cta {
    width: 36px;
    padding: 0;
    justify-content: center;
  }

  .user-chip {
    padding: 3px;
  }
}
</style>
