<script setup lang="ts">
import { ref, onMounted } from 'vue'
import http, { errText } from '../api/http'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const stats = ref<Record<string, any>>({})
const users = ref<any[]>([])
const adapters = ref<any[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const [s, u, a] = await Promise.all([
      http.get('/api/admin/stats'),
      http.get('/api/admin/users'),
      http.get('/api/admin/adapters')
    ])
    stats.value = s.data
    users.value = u.data
    adapters.value = a.data
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    loading.value = false
  }
}

async function editQuota(row: any) {
  const { value } = await ElMessageBox.prompt(
    `为 ${row.username} 设置新的存储配额（字节，当前 ${(row.storageQuotaBytes / 1073741824).toFixed(1)} GiB）`,
    '调整配额',
    { inputPattern: /^\d+$/, inputErrorMessage: '请输入非负整数字节' }
  )
  try {
    await http.put(`/api/admin/users/${row.id}/quota`, { quotaBytes: Number(value) })
    ElMessage.success('配额已更新')
    await load()
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

const nextByStatus: Record<string, string[]> = {
  DRAFT: ['EXPERIMENTAL', 'DISABLED'],
  EXPERIMENTAL: ['VERIFIED', 'DISABLED'],
  VERIFIED: ['DISABLED'],
  DISABLED: ['EXPERIMENTAL']
}

async function transition(versionId: string, current: string, next: string) {
  let attestation = ''
  if (next === 'VERIFIED') {
    const { value } = await ElMessageBox.prompt(
      '标记 VERIFIED 必须附上真实游戏评测凭据（授权样本 + Precision/Recall 指标）。无评测请勿标记。',
      'VERIFIED 评测凭据（必填）'
    )
    attestation = value
  }
  try {
    await http.put(`/api/admin/adapters/versions/${versionId}/status`,
      next === 'VERIFIED' ? { status: next, attestation } : { status: next })
    ElMessage.success(`已置为 ${next}`)
    await load()
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

function fmtBytes(n: number) {
  if (n > 1 << 30) return (n / (1 << 30)).toFixed(2) + ' GiB'
  if (n > 1 << 20) return (n / (1 << 20)).toFixed(1) + ' MiB'
  return (n / 1024).toFixed(0) + ' KiB'
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-title">管理后台</div>

    <div class="panel" style="margin-bottom: 16px">
      <h4>任务统计（实测）</h4>
      <el-row :gutter="12">
        <el-col :span="8">
          <h5>按状态</h5>
          <el-tag
            v-for="row in stats.tasksByStatus || []" :key="row.status"
            style="margin: 0 6px 6px 0"
          >{{ row.status }}: {{ row.n }}</el-tag>
        </el-col>
        <el-col :span="8">
          <h5>平均排队耗时（秒）</h5>
          <div v-for="row in stats.avgQueueSeconds || []" :key="row.type" class="muted">
            {{ row.type }}: {{ row.avg_s }}s
          </div>
        </el-col>
        <el-col :span="8">
          <h5>其他</h5>
          <div class="muted">发生重试的任务: {{ stats.retriedTasks }}</div>
          <div class="muted">渲染成功率: {{ stats.renderSuccessRate ?? '暂无终态渲染' }}</div>
          <div class="muted">候选决策:
            <el-tag v-for="row in stats.candidatesByStatus || []" :key="row.status"
              size="small" style="margin: 0 4px 4px 0">{{ row.status }}: {{ row.n }}</el-tag>
          </div>
        </el-col>
      </el-row>
    </div>

    <div class="panel" style="margin-bottom: 16px">
      <h4>用户与配额</h4>
      <el-table :data="users" size="small">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="username" label="用户名" width="140" />
        <el-table-column prop="role" label="角色" width="90" />
        <el-table-column label="已用 / 配额" min-width="200">
          <template #default="{ row }">
            <el-progress
              :percentage="Math.min(100, Math.round(row.usedBytes / Math.max(row.storageQuotaBytes, 1) * 100))"
              :stroke-width="8"
            />
            <span class="muted">{{ fmtBytes(row.usedBytes) }} / {{ fmtBytes(row.storageQuotaBytes) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="editQuota(row)">调整配额</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <h4>适配器生命周期</h4>
      <p class="muted">
        VERIFIED 必须基于真实授权录像与人工标注评测，需填写评测凭据；未验证的适配器始终显示为实验性。
      </p>
      <div v-for="a in adapters" :key="a.id" style="margin-bottom: 12px">
        <b>{{ a.displayName }}</b> <span class="muted">{{ a.gameKey }}</span>
        <div v-for="v in a.versions" :key="v.id" style="margin: 6px 0">
          <el-tag size="small" :type="v.status === 'VERIFIED' ? 'success' : v.status === 'DISABLED' ? 'danger' : 'warning'">
            v{{ v.adapterVersion }} · {{ v.status }}
          </el-tag>
          <el-button
            v-for="n in nextByStatus[v.status] || []"
            :key="n" size="small" text
            @click="transition(v.id, v.status, n)"
          >→ {{ n }}</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
h5 { margin: 4px 0 8px; }
</style>
