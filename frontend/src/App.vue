<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { computed } from 'vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const navs = computed(() => {
  const base = [
    { path: '/', label: '工作台' },
    { path: '/media', label: '素材库' },
    { path: '/upload', label: '上传' },
    { path: '/projects', label: '剪辑工程' },
    { path: '/tasks', label: '任务中心' },
    { path: '/games', label: '游戏档案' }
  ]
  if (auth.user?.role === 'ADMIN') {
    base.push({ path: '/admin', label: '管理后台' })
  }
  return base
})

async function onLogout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <el-container style="height: 100vh">
    <el-aside v-if="route.name !== 'login'" width="200px" class="sidebar">
      <div class="logo">
        <span class="logo-main">开黑剪辑台</span>
        <span class="logo-sub">HighlightHub</span>
      </div>
      <nav>
        <RouterLink
          v-for="n in navs"
          :key="n.path"
          :to="n.path"
          class="nav-link"
          :class="{ active: route.path === n.path }"
        >
          {{ n.label }}
        </RouterLink>
      </nav>
      <div v-if="auth.user" class="user-box">
        <div class="user-name">{{ auth.user.displayName || auth.user.username }}</div>
        <div class="muted">{{ auth.user.usedBytes }} / {{ auth.user.storageQuotaBytes }} 字节</div>
        <el-button size="small" text @click="onLogout">退出登录</el-button>
      </div>
    </el-aside>
    <el-main style="padding: 0; overflow: auto">
      <RouterView />
    </el-main>
  </el-container>
</template>

<style scoped>
.sidebar {
  background: var(--hub-panel);
  border-right: 1px solid var(--hub-border);
  display: flex;
  flex-direction: column;
  padding: 18px 0;
}
.logo { padding: 0 18px 18px; display: flex; flex-direction: column; }
.logo-main { font-size: 18px; font-weight: 700; }
.logo-sub { font-size: 12px; color: var(--hub-accent); }
nav { display: flex; flex-direction: column; }
.nav-link {
  color: var(--hub-muted);
  text-decoration: none;
  padding: 10px 18px;
  font-size: 14px;
}
.nav-link.active, .nav-link:hover { color: var(--hub-text); background: rgba(124, 92, 255, 0.12); }
.user-box { margin-top: auto; padding: 12px 18px 0; border-top: 1px solid var(--hub-border); }
.user-name { font-size: 14px; margin-bottom: 4px; }
</style>
