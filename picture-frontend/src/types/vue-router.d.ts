import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    /**
     * 需要登录才能访问
     */
    requiresLogin?: boolean

    /**
     * 需要管理员角色才能访问
     */
    requiresAdmin?: boolean

    /**
     * 页面需要被 keep-alive 缓存（例如首页，返回时保留列表与滚动位置）
     */
    keepAlive?: boolean
  }
}
