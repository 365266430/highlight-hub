<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { errText } from '../api'
import { ElMessage } from 'element-plus'

const router = useRouter()
const auth = useAuthStore()
const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const displayName = ref('')
const busy = ref(false)

async function submit() {
  busy.value = true
  try {
    if (mode.value === 'login') {
      await auth.login(username.value, password.value)
    } else {
      await auth.register(username.value, password.value, displayName.value)
    }
    router.push('/')
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="panel login-card">
      <h2 style="margin-top: 0">开黑剪辑台 <span style="color: var(--hub-accent)">HighlightHub</span></h2>
      <p class="muted">录像事件定位、高光候选筛选与轻量剪辑平台</p>
      <el-tabs v-model="mode">
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>
      <el-form @submit.prevent="submit" label-position="top">
        <el-form-item label="用户名">
          <el-input v-model="username" placeholder="3-32位字母/数字/下划线" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password placeholder="至少8位" />
        </el-form-item>
        <el-form-item v-if="mode === 'register'" label="显示名（可选）">
          <el-input v-model="displayName" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy" style="width: 100%">
          {{ mode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>
      <p class="muted" style="margin-top: 14px">
        所有游戏均可手动标记与剪辑；自动识别目前仅提供实验性 OCR 区域模板。
      </p>
    </div>
  </div>
</template>

<style scoped>
.login-wrap {
  display: flex; align-items: center; justify-content: center; height: 100%;
}
.login-card { width: 380px; padding: 28px; }
</style>
