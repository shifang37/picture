import axios from 'axios'
import { message } from 'ant-design-vue'

// 创建 Axios 实例
const myAxios = axios.create({
  baseURL: '/api',
  timeout: 60000,
  withCredentials: true,
  // 使用自定义的 JSON 解析器来处理大数字
  transformResponse: [
    (data) => {
      if (typeof data === 'string') {
        try {
          // 使用正则替换大数字为字符串，避免精度丢失
          return JSON.parse(
            data.replace(/:(\d{15,})/g, (match, p1) => {
              return `:"${p1}"`
            }),
          )
        } catch (e) {
          return data
        }
      }
      return data
    },
  ],
})

// 全局请求拦截器
myAxios.interceptors.request.use(
  function (config) {
    // 确保大数字ID以字符串形式发送
    if (config.params) {
      Object.keys(config.params).forEach((key) => {
        const value = config.params[key]
        // 如果是超过安全整数范围的数字字符串，保持为字符串
        if (typeof value === 'string' && /^\d{15,}$/.test(value)) {
          // 保持字符串格式
        }
      })
    }
    return config
  },
  function (error) {
    // Do something with request error
    return Promise.reject(error)
  },
)

// 全局响应拦截器
myAxios.interceptors.response.use(
  function (response) {
    const { data } = response
    // 未登录
    if (data.code === 40100) {
      // 不是获取用户信息的请求，并且用户目前不是已经在用户登录页面，则跳转到登录页面
      if (
        !response.request.responseURL.includes('user/get/login') &&
        !window.location.pathname.includes('/user/login')
      ) {
        message.warning('请先登录')
        window.location.href = `/user/login?redirect=${window.location.href}`
      }
    }
    return response
  },
  function (error) {
    // Any status codes that falls outside the range of 2xx cause this function to trigger
    // Do something with response error
    return Promise.reject(error)
  },
)

export default myAxios
