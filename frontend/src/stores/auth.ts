import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi, type User } from '../api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const loaded = ref(false)

  const isLoggedIn = computed(() => user.value !== null)

  async function fetchMe() {
    try {
      const resp = await authApi.me()
      user.value = resp.data
    } catch {
      user.value = null
    } finally {
      loaded.value = true
    }
  }

  async function login(username: string, password: string) {
    await authApi.login(username, password)
    await fetchMe()
  }

  async function register(username: string, password: string, displayName?: string) {
    await authApi.register(username, password, displayName)
    await login(username, password)
  }

  async function logout() {
    try {
      await authApi.logout()
    } finally {
      user.value = null
    }
  }

  return { user, loaded, isLoggedIn, fetchMe, login, register, logout }
})
