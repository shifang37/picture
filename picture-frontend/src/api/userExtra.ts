// 手动补充的用户接口封装。
// 生成式客户端（userController.ts）由 npm run openapi 重新生成，这里的内容不会被覆盖。
import request from '@/request'

export interface UserUpdateProfileParams {
  userName?: string
  userAvatar?: string
  userProfile?: string
}

/**
 * 修改当前登录用户的个人资料（普通用户可用）
 */
export async function updateProfile(
  body: UserUpdateProfileParams,
  options?: { [key: string]: any },
) {
  return request<API.BaseResponseBoolean>('/user/update/profile', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

export interface UpdatePasswordParams {
  oldPassword: string
  newPassword: string
}

/**
 * 修改当前登录用户的密码
 */
export async function updatePassword(body: UpdatePasswordParams, options?: { [key: string]: any }) {
  return request<API.BaseResponseBoolean>('/user/update/password', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

export interface UserResetPasswordParams {
  id: string
  newPassword: string
}

/**
 * 管理员重置指定用户的密码
 */
export async function resetUserPassword(
  body: UserResetPasswordParams,
  options?: { [key: string]: any },
) {
  return request<API.BaseResponseBoolean>('/user/reset/password', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
