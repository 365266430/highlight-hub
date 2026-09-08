<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { mediaApi, projectApi, taskApi, type Media, type Project, type Task } from '../api'

const medias = ref<Media[]>([])
const projects = ref<Project[]>([])
const runningTasks = ref<Task[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    const [m, p, t] = await Promise.all([
      mediaApi.list(1, 6),
      projectApi.list(1, 6),
      taskApi.list({ size: 8 })
    ])
    medias.value = (m.data as any).items || []
    projects.value = (p.data as any).items || []
    runningTasks.value = ((t.data as any).items || []).filter(
      (x: Task) => x.status === 'QUEUED' || x.status === 'RUNNING' || x.status === 'CANCEL_REQUESTED'
    )
  } finally {
    loading.value = false
  }
})

function fmtBytes(n?: number) {
  if (!n && n !== 0) return '-'
  if (n > 1 << 30) return (n / (1 << 30)).toFixed(2) + ' GB'
  if (n > 1 << 20) return (n / (1 << 20)).toFixed(1) + ' MB'
  return (n / 1024).toFixed(0) + ' KB'
}

function fmtMs(ms?: number) {
  if (!ms && ms !== 0) return '-'
  const s = Math.round(ms / 1000)
  return `${Math.floor(s / 60)}分${String(s % 60).padStart(2, '0')}秒`
}
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-title">工作台</div>

    <div class="panel" style="margin-bottom: 16px">
      <el-row :gutter="16">
        <el-col :span="8">
          <div class="stat">
            <div class="stat-num">{{ medias.length }}</div>
            <div class="muted">最近素材</div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat">
            <div class="stat-num">{{ runningTasks.length }}</div>
            <div class="muted">进行中任务</div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat">
            <div class="stat-num">{{ projects.length }}</div>
            <div class="muted">最近工程</div>
          </div>
        </el-col>
      </el-row>
      <el-button type="primary" style="margin-top: 12px" @click="$router.push('/upload')">
        上传新录像
      </el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="12">
        <div class="panel">
          <h4>最近素材</h4>
          <el-empty v-if="!medias.length" description="还没有素材，先上传一段录像" :image-size="60" />
          <ul v-else class="list">
            <li v-for="m in medias" :key="m.id">
              <RouterLink :to="`/media/${m.id}`">{{ m.originalFilename }}</RouterLink>
              <span class="muted">{{ fmtMs(m.durationMs) }} · {{ m.status }}</span>
            </li>
          </ul>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="panel">
          <h4>进行中任务</h4>
          <el-empty v-if="!runningTasks.length" description="当前没有进行中的任务" :image-size="60" />
          <ul v-else class="list">
            <li v-for="t in runningTasks" :key="t.id">
              <RouterLink to="/tasks">{{ t.type }}</RouterLink>
              <span class="muted">{{ t.status }} · {{ t.progress }}%</span>
            </li>
          </ul>
          <h4 style="margin-top: 18px">最近工程</h4>
          <el-empty v-if="!projects.length" description="还没有剪辑工程" :image-size="60" />
          <ul v-else class="list">
            <li v-for="p in projects" :key="p.id">
              <RouterLink :to="`/projects/${p.id}`">{{ p.name }}</RouterLink>
              <span class="muted">R{{ p.latestRevision }}</span>
            </li>
          </ul>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.stat { text-align: center; padding: 8px 0; }
.stat-num { font-size: 30px; font-weight: 700; color: var(--hub-accent); }
.list { list-style: none; padding: 0; margin: 0; }
.list li {
  display: flex; justify-content: space-between; gap: 12px;
  padding: 8px 0; border-bottom: 1px dashed var(--hub-border); font-size: 14px;
}
.list li:last-child { border-bottom: none; }
.list a { color: var(--hub-text); text-decoration: none; }
.list a:hover { color: var(--hub-accent); }
</style>
