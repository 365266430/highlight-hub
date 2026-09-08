<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  mediaApi, adapterApi, analysisApi, taskApi, gameApi, errText,
  type Media, type AnalysisRun, type Task, type GameProfile
} from '../api'

const route = useRoute()
const router = useRouter()
const mediaId = route.params.id as string

const media = ref<Media | null>(null)
const adapters = ref<any[]>([])
const versions = ref<any[]>([])
const selectedVersion = ref('')
const roisText = ref(JSON.stringify([
  { id: 'roi-1', x: 0.55, y: 0.02, w: 0.44, h: 0.30, mode: 'number', eventType: 'SCORE_CHANGE' }
], null, 2))
const intervalMs = ref(500)
const confirmFrames = ref(2)
const busy = ref(false)
const games = ref<GameProfile[]>([])
const selectedGame = ref('')
const analysis = ref<AnalysisRun | null>(null)
const pollTimer = ref<ReturnType<typeof setTimeout>>()

const isExperimental = computed(() =>
  versions.value.find((v) => v.id === selectedVersion.value)?.status === 'EXPERIMENTAL')

async function load() {
  const resp = await mediaApi.get(mediaId)
  media.value = resp.data
}

async function loadAdapters() {
  const resp = await adapterApi.list()
  adapters.value = resp.data
  const generic = adapters.value.find((a) => a.gameKey === 'generic-ocr')
  if (generic) {
    const vs = await adapterApi.versions(generic.id)
    versions.value = vs.data
    if (vs.data.length) selectedVersion.value = vs.data[0].id
  }
  try {
    games.value = (await gameApi.list()).data
  } catch {
    games.value = []
  }
}

function onGameChange(gameId: string) {
  const g = games.value.find((x) => x.id === gameId)
  if (g && g.defaultRois && g.defaultRois.length) {
    roisText.value = JSON.stringify(g.defaultRois, null, 2)
  }
}

function pollAnalysis() {
  pollTimer.value = setTimeout(async () => {
    if (!analysis.value) return
    const resp = await analysisApi.get(analysis.value.id)
    analysis.value = resp.data
    if (analysis.value.status === 'QUEUED' || analysis.value.status === 'RUNNING') {
      pollAnalysis()
    } else {
      load()
    }
  }, 1500)
}

async function startAnalysis() {
  let rois: unknown
  try {
    rois = JSON.parse(roisText.value)
  } catch {
    ElMessage.error('ROI 配置不是合法 JSON')
    return
  }
  busy.value = true
  try {
    const resp = await analysisApi.create(mediaId, selectedVersion.value, {
      sampleIntervalMs: intervalMs.value,
      multiFrameConfirm: confirmFrames.value,
      rois
    }, selectedGame.value || undefined)
    analysis.value = resp.data
    ElMessage.info('分析任务已提交，任务中心可查看进度')
    pollAnalysis()
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    busy.value = false
  }
}

async function cancelAnalysis() {
  if (!analysis.value?.taskId) return
  try {
    await taskApi.cancel(analysis.value.taskId)
    ElMessage.info('已请求取消')
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function removeMedia() {
  await ElMessageBox.confirm('删除素材将同时删除其预览与成片，确定？', '删除素材', { type: 'warning' })
  try {
    await mediaApi.remove(mediaId)
    ElMessage.success('已进入删除流程')
    router.push('/media')
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

function fmtMs(ms?: number) {
  if (!ms && ms !== 0) return '-'
  const s = Math.round(ms / 1000)
  return `${Math.floor(s / 60)}分${String(s % 60).padStart(2, '0')}秒`
}

onMounted(() => {
  load()
  loadAdapters()
})

onBeforeUnmount(() => pollTimer.value && clearTimeout(pollTimer.value))
</script>

<template>
  <div class="page" v-if="media">
    <div class="page-title">{{ media.originalFilename }}</div>
    <el-alert
      v-if="media.status === 'FAILED'"
      type="error" :closable="false" style="margin-bottom: 14px"
      :title="`处理失败：${media.errorMessage || '未知原因'}`"
    />
    <el-row :gutter="16">
      <el-col :span="14">
        <div class="panel">
          <h4>预览</h4>
          <video
            v-if="media.hasPreview"
            :src="mediaApi.previewUrl(media.id)"
            controls
            style="width: 100%; border-radius: 8px; background: #000"
          />
          <el-skeleton v-else-if="media.status === 'PROBING' || !media.hasPreview" :rows="3" animated />
          <div style="margin-top: 12px; display: flex; gap: 10px; flex-wrap: wrap">
            <el-button type="primary" @click="router.push(`/media/${media.id}/timeline`)">
              事件时间轴 / 分析
            </el-button>
            <el-button @click="router.push('/projects')">剪辑工程</el-button>
            <el-button type="danger" plain @click="removeMedia">删除素材</el-button>
          </div>
        </div>
      </el-col>
      <el-col :span="10">
        <div class="panel">
          <h4>媒体信息</h4>
          <table class="info">
            <tr><td class="muted">状态</td><td>{{ media.status }}</td></tr>
            <tr><td class="muted">时长</td><td>{{ fmtMs(media.durationMs) }}</td></tr>
            <tr><td class="muted">分辨率</td><td>{{ media.width ? `${media.width}×${media.height}` : '-' }}</td></tr>
            <tr><td class="muted">视频编码</td><td>{{ media.videoCodec || '-' }}</td></tr>
            <tr><td class="muted">音频编码</td><td>{{ media.audioCodec || '无音轨' }}</td></tr>
            <tr>
              <td class="muted">帧率</td>
              <td>
                {{ media.frameRate || '-' }}
                <el-tag v-if="media.variableFrameRate" size="small" type="warning" style="margin-left: 6px">可变帧率</el-tag>
              </td>
            </tr>
          </table>
        </div>

        <div class="panel" style="margin-top: 16px">
          <h4>自动分析（可选，需先手动剪辑也可跳过）</h4>
          <el-alert
            v-if="isExperimental"
            type="warning" :closable="false" show-icon style="margin-bottom: 10px"
            title="该适配器为实验性：未经真实游戏验证，结果仅供筛选参考，请逐条确认"
          />
          <el-select v-model="selectedVersion" placeholder="选择适配器版本" style="width: 100%">
            <el-option
              v-for="v in versions"
              :key="v.id"
              :value="v.id"
              :label="`v${v.adapterVersion} · ${v.status}`"
            />
          </el-select>
          <div style="display: flex; gap: 10px; align-items: center; margin-top: 10px">
            <span class="muted">游戏档案:</span>
            <el-select v-model="selectedGame" clearable placeholder="未分类 / 手动" style="flex: 1"
              @change="onGameChange">
              <el-option v-for="g in games" :key="g.id" :value="g.id" :label="g.displayName" />
            </el-select>
            <el-button size="small" text type="primary" @click="$router.push('/games')">管理档案</el-button>
          </div>
          <div style="display: flex; gap: 10px; margin: 12px 0">
            <el-input-number v-model="intervalMs" :min="200" :max="5000" :step="100" />
            <span class="muted" style="align-self: center">采样间隔 (ms)</span>
            <el-input-number v-model="confirmFrames" :min="1" :max="5" />
            <span class="muted" style="align-self: center">多帧确认</span>
          </div>
          <el-input
            v-model="roisText"
            type="textarea"
            :rows="6"
            style="font-family: monospace"
          />
          <p class="muted" style="margin: 8px 0">
            ROI 为相对坐标 (0~1)。不匹配时请重新校准或使用手动模式——系统不会用错误模板给出可信结果。
          </p>
          <div style="display: flex; gap: 10px">
            <el-button type="primary" :loading="busy" @click="startAnalysis">开始自动分析</el-button>
            <el-button v-if="analysis && ['QUEUED','RUNNING'].includes(analysis.status)" @click="cancelAnalysis">
              取消分析
            </el-button>
          </div>
          <p v-if="analysis" class="muted" style="margin-top: 10px">
            分析状态：{{ analysis.status }}
            <el-button
              v-if="analysis.status === 'SUCCEEDED'" size="small" text type="primary"
              @click="router.push(`/media/${media.id}/timeline`)"
            >查看事件时间轴</el-button>
          </p>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.info { width: 100%; border-collapse: collapse; font-size: 14px; }
.info td { padding: 6px 0; border-bottom: 1px dashed var(--hub-border); }
.info td:first-child { width: 90px; }
</style>
