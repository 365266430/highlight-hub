<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { taskApi, type Task } from '../api'

const items = ref<Task[]>([])
const total = ref(0)
const page = ref(1)
const statusFilter = ref('')
const timer = ref<ReturnType<typeof setTimeout>>()

const statusOptions = ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELLED']

async function load() {
  const resp = await taskApi.list({ page: page.value, size: 20, status: statusFilter.value || undefined })
  items.value = (resp.data as any).items
  total.value = (resp.data as any).total
  timer.value = setTimeout(load, 4000) // REST polling (phase 1); SSE arrives in phase 3
}

async function cancel(t: Task) {
  await taskApi.cancel(t.id)
  await load()
}

async function retry(t: Task) {
  await taskApi.retry(t.id)
  await load()
}

onMounted(load)
onBeforeUnmount(() => timer.value && clearTimeout(timer.value))
</script>

<template>
  <div class="page">
    <div class="page-title">任务中心</div>
    <div class="panel">
      <div style="margin-bottom: 12px; display: flex; gap: 12px">
        <el-select v-model="statusFilter" clearable placeholder="状态筛选" style="width: 200px" @change="load">
          <el-option v-for="s in statusOptions" :key="s" :value="s" :label="s" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="items">
        <el-table-column prop="type" label="类型" width="150" />
        <el-table-column prop="status" label="状态" width="150">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'SUCCEEDED' ? 'success' : row.status === 'FAILED' ? 'danger' : 'info'"
            >{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="200">
          <template #default="{ row }">
            <el-progress
              :percentage="row.progress"
              :stroke-width="8"
              :status="row.status === 'FAILED' ? 'exception' : row.status === 'SUCCEEDED' ? 'success' : undefined"
            />
            <span class="muted">{{ row.phase }}</span>
          </template>
        </el-table-column>
        <el-table-column label="尝试" width="70">
          <template #default="{ row }">{{ row.attempt }}/{{ row.maxAttempts }}</template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="180">
          <template #default="{ row }">{{ row.errorMessage || '' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button
              v-if="['QUEUED', 'RUNNING'].includes(row.status)"
              size="small" text type="warning" @click="cancel(row)"
            >取消</el-button>
            <el-button v-if="row.status === 'FAILED'" size="small" text type="primary" @click="retry(row)">
              重试
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="total > 20"
        v-model:current-page="page"
        :total="total"
        :page-size="20"
        layout="prev, pager, next"
        style="margin-top: 14px"
        @current-change="load"
      />
    </div>
  </div>
</template>
