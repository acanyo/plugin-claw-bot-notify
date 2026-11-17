import { isAxiosError } from 'axios'

export const loginExpiredMessage = '后台登录已失效，已暂停自动刷新。请重新登录 Halo 后刷新此页。'

export function isLoginExpired(cause: unknown) {
  return isAxiosError(cause) && cause.response?.status === 401
}
