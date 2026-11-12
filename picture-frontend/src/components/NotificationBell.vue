<template>
  <a-popover placement="bottomRight" trigger="click" @openChange="handleOpenChange">
    <template #content>
      <div class="notification-panel">
        <div v-if="loading" class="panel-state">
          <a-spin size="small" />
        </div>
        <div v-else-if="errorMessage" class="panel-state panel-error">{{ errorMessage }}</div>
        <div v-else-if="!items.length" class="panel-state">暂无通知</div>
        <ul v-else class="notification-list">
          <li
            v-for="item in items"
            :key="item.id"
            class="notification-item"
            :class="{ unread: item.isRead === 0 }"
            @click="openItem(item)"
          >
            <div class="item-title">{{ item.title }}</div>
            <div v-if="item.content" class="item-content">{{ item.content }}</div>
            <div class="item-time">{{ formatTime(item.createTime) }}</div>
          </li>
        </ul>
        <div class="panel-footer">
          <a-button type="link" size="small" @click="goAll">查看全部</a-button>
        </div>
      </div>
    </template>
    <a-badge :count="notificationStore.unreadCount" :overflow-count="99" :offset="[-4, 4]">
      <BellOutlined class="bell-icon" data-testid="notification-bell" />
    </a-badge>
  </a-popover>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { BellOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import {
  listNotifications,
  markNotificationsRead,
  type NotificationItem,
} from '@/api/notificationExtra'
import { useNotificationStore } from '@/stores/useNotificationStore'

const notificationStore = useNotificationStore()
const router = useRouter()

const items = ref<NotificationItem[]>([])
const loading = ref(false)
const errorMessage = ref('')

// 顶栏只展示最近 5 条，完整列表在通知中心
const PREVIEW_SIZE = 5

const formatTime = (value?: string) => (value ? dayjs(value).format('MM-DD HH:mm') : '')

const fetchPreview = async () => {
  loading.value = true
  errorMessage.value = ''
  try {
    const res = await listNotifications({ current: 1, pageSize: PREVIEW_SIZE })
    if (res.data.code === 0) {
      items.value = res.data.data?.records ?? []
    } else {
      errorMessage.value = res.data.message || '加载通知失败'
    }
  } catch (error: any) {
    // 未登录等场景不弹全局错误，面板内提示即可
    errorMessage.value = error?.message || '加载通知失败'
  } finally {
    loading.value = false
  }
}

const handleOpenChange = async (open: boolean) => {
  if (!open) {
    return
  }
  await fetchPreview()
  await notificationStore.refreshUnread()
}

const markItemRead = async (item: NotificationItem) => {
  if (!item.id || item.isRead === 1) {
    return
  }
  try {
    const res = await markNotificationsRead([item.id])
    if (res.data.code === 0) {
      item.isRead = 1
      notificationStore.setUnreadCount(notificationStore.unreadCount - 1)
    }
  } catch {
    // 已读标记失败不阻塞跳转，下次打开面板会重新拉取
  }
}

const openItem = async (item: NotificationItem) => {
  await markItemRead(item)
  if (item.bizType === 'PICTURE' && item.bizId) {
    router.push(`/picture/${item.bizId}`)
  }
}

const goAll = () => {
  router.push('/notifications')
}
</script>

<style scoped>
.bell-icon {
  font-size: 18px;
  color: #595959;
  cursor: pointer;
}

.notification-panel {
  width: 320px;
}

.panel-state {
  padding: 16px 0;
  color: #999;
  text-align: center;
}

.panel-error {
  color: #ff4d4f;
}

.notification-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.notification-item {
  padding: 8px 4px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
}

.notification-item:last-child {
  border-bottom: none;
}

.notification-item:hover {
  background: #fafafa;
}

.item-title {
  font-weight: 500;
}

.unread .item-title::before {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 6px;
  vertical-align: middle;
  background: #ff4d4f;
  border-radius: 50%;
  content: '';
}

.item-content {
  margin-top: 2px;
  overflow: hidden;
  color: #666;
  font-size: 12px;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.item-time {
  margin-top: 4px;
  color: #bbb;
  font-size: 12px;
}

.panel-footer {
  padding-top: 8px;
  text-align: center;
  border-top: 1px solid #f0f0f0;
}
</style>
