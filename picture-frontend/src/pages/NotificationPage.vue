<template>
  <div id="notificationPage">
    <div class="page-header">
      <div>
        <h2>通知中心</h2>
        <div class="desc">审核结果与账号安全提醒会在这里通知你</div>
      </div>
      <a-space>
        <a-button :disabled="!unreadCount" @click="doMarkAllRead">全部已读</a-button>
        <a-button type="primary" ghost @click="fetchData">刷新</a-button>
      </a-space>
    </div>

    <a-tabs v-model:activeKey="activeTab" @change="handleTabChange">
      <a-tab-pane key="all" tab="全部" />
      <a-tab-pane key="unread" :tab="`未读${unreadCount ? `（${unreadCount}）` : ''}`" />
    </a-tabs>

    <div v-if="loading" class="state-block">
      <a-spin />
    </div>
    <a-empty v-else-if="!dataList.length" description="还没有通知" />
    <a-list v-else :data-source="dataList" item-layout="horizontal">
      <template #renderItem="{ item }">
        <a-list-item class="notification-item" :class="{ unread: item.isRead === 0 }">
          <a-list-item-meta>
            <template #title>
              <a-space>
                <span :class="{ 'unread-title': item.isRead === 0 }">{{ item.title }}</span>
                <a-tag :color="tagColor(item.type)">{{ item.typeText || item.type }}</a-tag>
              </a-space>
            </template>
            <template #description>
              <div class="item-content">{{ item.content }}</div>
              <div class="item-time">{{ formatTime(item.createTime) }}</div>
            </template>
          </a-list-item-meta>
          <template #actions>
            <a-button
              v-if="item.bizType === 'PICTURE' && item.bizId"
              type="link"
              size="small"
              @click="openPicture(item)"
            >
              查看图片
            </a-button>
            <a-button v-if="item.isRead === 0" type="link" size="small" @click="markRead(item)">
              标记已读
            </a-button>
            <a-popconfirm title="确定删除这条通知吗？" @confirm="removeItem(item)">
              <a-button type="link" size="small" danger>删除</a-button>
            </a-popconfirm>
          </template>
        </a-list-item>
      </template>
    </a-list>

    <div v-if="total > pageSize" class="pager">
      <a-pagination
        v-model:current="current"
        :page-size="pageSize"
        :total="total"
        :show-size-changer="false"
        @change="fetchData"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  deleteNotifications,
  listNotifications,
  markAllNotificationsRead,
  markNotificationsRead,
  type NotificationItem,
  type NotificationType,
} from '@/api/notificationExtra'
import { useNotificationStore } from '@/stores/useNotificationStore'

const router = useRouter()
const notificationStore = useNotificationStore()

const activeTab = ref<'all' | 'unread'>('all')
const dataList = ref<NotificationItem[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = 10
const loading = ref(false)

const unreadCount = computed(() => notificationStore.unreadCount)

const formatTime = (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-')

const tagColor = (type?: NotificationType) => {
  const map: Record<string, string> = {
    REVIEW_PASS: 'green',
    REVIEW_REJECT: 'red',
    PASSWORD_RESET: 'orange',
    ROLE_CHANGED: 'blue',
    SYSTEM: 'purple',
  }
  return (type && map[type]) || 'default'
}

const fetchData = async () => {
  loading.value = true
  try {
    const res = await listNotifications({
      current: current.value,
      pageSize,
      onlyUnread: activeTab.value === 'unread',
    })
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records ?? []
      total.value = Number(res.data.data.total) || 0
    } else {
      message.error('获取通知失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('获取通知失败：' + (error?.message || '系统错误'))
  } finally {
    loading.value = false
  }
  // 列表与红点保持一致：每次拉取都刷新一次未读数
  notificationStore.refreshUnread()
}

const handleTabChange = () => {
  current.value = 1
  fetchData()
}

const openPicture = (item: NotificationItem) => {
  if (!item.bizId) {
    return
  }
  markRead(item)
  router.push(`/picture/${item.bizId}`)
}

const markRead = async (item: NotificationItem) => {
  if (!item.id || item.isRead === 1) {
    return
  }
  const previousUnread = notificationStore.unreadCount
  notificationStore.setUnreadCount(previousUnread - 1)
  try {
    const res = await markNotificationsRead([item.id])
    if (res.data.code === 0) {
      item.isRead = 1
    } else {
      notificationStore.setUnreadCount(previousUnread)
      message.error(res.data.message || '标记已读失败')
    }
  } catch (error: any) {
    notificationStore.setUnreadCount(previousUnread)
    message.error(error?.message || '标记已读失败')
  }
}

const doMarkAllRead = async () => {
  const previousUnread = notificationStore.unreadCount
  notificationStore.setUnreadCount(0)
  try {
    const res = await markAllNotificationsRead()
    if (res.data.code === 0) {
      message.success('已全部标记为已读')
      dataList.value = dataList.value.map((item) => ({ ...item, isRead: 1 }))
    } else {
      notificationStore.setUnreadCount(previousUnread)
      message.error(res.data.message || '操作失败')
    }
  } catch (error: any) {
    notificationStore.setUnreadCount(previousUnread)
    message.error(error?.message || '操作失败')
  }
}

const removeItem = async (item: NotificationItem) => {
  if (!item.id) {
    return
  }
  try {
    const res = await deleteNotifications([item.id])
    if (res.data.code === 0) {
      message.success('已删除')
      if (dataList.value.length === 1 && current.value > 1) {
        current.value -= 1
      }
      await fetchData()
    } else {
      message.error(res.data.message || '删除失败')
    }
  } catch (error: any) {
    message.error(error?.message || '删除失败')
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
#notificationPage {
  max-width: 900px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 8px;
}

.page-header h2 {
  margin-bottom: 4px;
}

.desc {
  color: #999;
  font-size: 13px;
}

.state-block {
  display: flex;
  justify-content: center;
  padding: 64px 0;
}

.notification-item.unread {
  background: #fafafa;
}

.unread-title {
  font-weight: 600;
}

.item-content {
  color: #595959;
}

.item-time {
  margin-top: 4px;
  color: #bbb;
  font-size: 12px;
}

.pager {
  margin-top: 16px;
  text-align: center;
}
</style>
