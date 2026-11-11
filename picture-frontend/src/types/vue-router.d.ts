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
  }
}
