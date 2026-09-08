<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { mediaApi, type Media } from '../api'

const items = ref<Media[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const resp = await mediaApi.list(page.value, 20)
    items.value = (resp.data as any).items
    total.value = (resp.data as any).total
  } finally {
    loading.value = false
  }
}

function fmtMs(ms?: number) {
  if (!ms && ms !== 0) return '-'
  const s = Math.round(ms / 1000)
  return `${Math.floor(s / 60)}分${String(s % 60).padStart(2, '0')}秒`
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-title">素材库</div>
    <div class="panel">
      <el-table :data="items" style="width: 100%">
        <el-table-column label="文件名" min-width="220">
          <template #default="{ row }">
            <RouterLink :to="`/media/${row.id}`" style="color: var(--hub-accent); text-decoration: none">
              {{ row.originalFilename }}
            </RouterLink>
          </template>
        </el-table-column>
        <el-table-column label="时长" width="120">
          <template #default="{ row }">{{ fmtMs(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="分辨率" width="110">
          <template #default="{ row }">{{ row.width ? `${row.width}×${row.height}` : '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column label="处理" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.hasPreview" size="small" type="success">预览</el-tag>
            <el-tag v-if="row.hasThumbnails" size="small" type="success" style="margin-left: 4px">缩略图</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" @click="$router.push(`/media/${row.id}`)">详情</el-button>
            <el-button size="small" type="primary" plain @click="$router.push(`/media/${row.id}/timeline`)">
              事件时间轴
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
