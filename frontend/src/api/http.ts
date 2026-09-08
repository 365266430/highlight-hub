import axios from 'axios'

// Same-origin API with the dev proxy (or same-site deployment in prod).
// Cookies carry the session; XSRF-TOKEN is echoed on every state change.
const http = axios.create({
  baseURL: '/',
  withCredentials: true,
  timeout: 30000
})

http.interceptors.request.use((config) => {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  if (match) {
    config.headers['X-XSRF-TOKEN'] = decodeURIComponent(match[1])
  }
  return config
})

http.interceptors.response.use(
  (resp) => resp,
  (error) => {
    if (error.response?.status === 401 && !location.pathname.startsWith('/login')) {
      location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default http

export interface ApiError {
  code: string
  message: string
  requestId?: string
}

export function errText(e: unknown): string {
  const ax = e as { response?: { data?: ApiError } }
  const data = ax?.response?.data
  if (data?.message) {
    return data.code === 'VALIDATION_FAILED' ? `参数错误: ${data.message}` : data.message
  }
  return '网络或服务异常，请稍后重试'
}
