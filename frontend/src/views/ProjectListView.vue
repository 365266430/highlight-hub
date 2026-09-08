<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { projectApi, type Project } from '../api'

const items = ref<Project[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const resp = await projectApi.list(page.value, 20)
    items.value = (resp.data as any).items
    total.value = (resp.data as any).total
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-title">剪辑工程</div>
    <div class="panel">
      <el-table :data="items" style="width: 100%">
        <el-table-column label="工程名" min-width="220">
          <template #default="{ row }">
            <RouterLink :to="`/projects/${row.id}`" style="color: var(--hub-accent); text-decoration: none">
              {{ row.name }}
            </RouterLink>
          </template>
        </el-table-column>
        <el-table-column prop="latestRevision" label="版本" width="80" />
        <el-table-column prop="updatedAt" label="最近更新" width="200" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="$router.push(`/projects/${row.id}`)">
              打开编辑
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
