<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  projectApi, renderApi, mediaApi, errText,
  type Project, type Media, type RenderJob, type Segment, type Edl
} from '../api'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id as string

const project = ref<Project | null>(null)
const media = ref<Media | null>(null)
const segments = ref<Segment[]>([])
const expectedRevision = ref(0)
const name = ref('')
const busy = ref(false)
const render = ref<RenderJob | null>(null)

const totalDuration = computed(() =>
  segments.value.reduce((acc, s) => acc + (s.sourceOutMs - s.sourceInMs), 0))

async function load() {
  const p = await projectApi.get(projectId)
  project.value = p.data
  name.value = p.data.name
  expectedRevision.value = p.data.latestRevision
  media.value = (await mediaApi.get(p.data.mediaId)).data
  if (p.data.edl) {
    segments.value = [...p.data.edl.segments]
  }
}

function addSegment() {
  segments.value.push({
    id: `s${segments.value.length + 1}-${Date.now() % 10000}`,
    sourceInMs: 0,
    sourceOutMs: Math.min(5000, media.value?.durationMs || 5000),
    caption: '',
    sourceVolume: 1.0
  })
}

function removeSegment(i: number) {
  segments.value.splice(i, 1)
}

function dupSegment(i: number) {
  const copy = { ...segments.value[i], id: `s${Date.now() % 100000}` }
  segments.value.splice(i + 1, 0, copy)
}

function move(i: number, dir: -1 | 1) {
  const j = i + dir
  if (j < 0 || j >= segments.value.length) return
  const arr = segments.value
  ;[arr[i], arr[j]] = [arr[j], arr[i]]
}

async function save() {
  busy.value = true
  try {
    const edl: Edl = {
      schemaVersion: 1,
      sourceMediaId: project.value!.mediaId,
      segments: segments.value,
      output: { aspectMode: 'SOURCE', width: 1920, height: 1080, fps: 30 }
    }
    const resp = await projectApi.save(projectId, {
      expectedRevision: expectedRevision.value,
      name: name.value,
      ...edl
    })
    expectedRevision.value = resp.data.revision
    ElMessage.success(`已保存为版本 ${resp.data.revision}`)
    await load()
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    busy.value = false
  }
}

async function submitRender() {
  busy.value = true
  try {
    await save()
    const resp = await renderApi.submit(projectId, expectedRevision.value)
    render.value = resp.data
    ElMessage.info('渲染已提交')
    router.push(`/renders/${resp.data.id}`)
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    busy.value = false
  }
}

function fmtMs(ms: number) {
  const s = ms / 1000
  return `${s.toFixed(1)}s`
}

onMounted(load)
</script>

<template>
  <div class="page" v-if="project">
    <div class="page-title">
      编辑 - {{ project.name }}
      <span class="muted" style="font-size: 13px; margin-left: 10px">当前版本 R{{ expectedRevision }}</span>
    </div>
    <el-row :gutter="16">
      <el-col :span="14">
        <div class="panel">
          <div style="display: flex; justify-content: space-between; align-items: center">
            <h4>片段列表（顺序即成片顺序）</h4>
            <el-button size="small" @click="addSegment">添加片段</el-button>
          </div>
          <el-empty v-if="!segments.length" description="还没有片段：从候选创建或手动添加" :image-size="60" />
          <div v-for="(s, i) in segments" :key="s.id" class="segment">
            <el-row :gutter="8">
              <el-col :span="5">
                <label class="muted">起点 (ms)</label>
                <el-input-number v-model="s.sourceInMs" :min="0" :max="media?.durationMs || 0" :step="100" style="width: 100%" />
              </el-col>
              <el-col :span="5">
                <label class="muted">终点 (ms)</label>
                <el-input-number v-model="s.sourceOutMs" :min="0" :max="media?.durationMs || 0" :step="100" style="width: 100%" />
              </el-col>
              <el-col :span="8">
                <label class="muted">字幕（覆盖本片段）</label>
                <el-input v-model="s.caption" maxlength="200" placeholder="可空" />
              </el-col>
              <el-col :span="4">
                <label class="muted">音量 {{ s.sourceVolume.toFixed(1) }}</label>
                <el-slider v-model="s.sourceVolume" :min="0" :max="1.5" :step="0.1" />
              </el-col>
              <el-col :span="2">
                <label class="muted">&nbsp;</label>
                <div style="display: flex; gap: 2px">
                  <el-button size="small" text @click="move(i, -1)">↑</el-button>
                  <el-button size="small" text @click="move(i, 1)">↓</el-button>
                </div>
              </el-col>
            </el-row>
            <div style="margin-top: 6px; display: flex; gap: 8px">
              <span class="muted">本段时长 {{ fmtMs(s.sourceOutMs - s.sourceInMs) }}</span>
              <el-button size="small" text @click="dupSegment(i)">复制</el-button>
              <el-button size="small" text type="danger" @click="removeSegment(i)">删除</el-button>
            </div>
          </div>
          <p class="muted" style="margin-top: 10px">
            总导出时长 {{ fmtMs(totalDuration) }} ·
            拖拽不可用时请使用数字输入精确调整边界 · 渲染以保存的版本为准
          </p>
        </div>
      </el-col>
      <el-col :span="10">
        <div class="panel">
          <h4>工程设置</h4>
          <el-form label-position="top">
            <el-form-item label="工程名">
              <el-input v-model="name" />
            </el-form-item>
          </el-form>
          <div style="display: flex; gap: 10px; margin-top: 8px">
            <el-button type="primary" :loading="busy" @click="save">保存新版本</el-button>
            <el-button type="success" :loading="busy" @click="submitRender">保存并提交渲染</el-button>
          </div>
          <p class="muted" style="margin-top: 12px">
            保存使用乐观并发控制：若他人已保存新版本，将收到 409 并需要刷新。
            渲染永远绑定提交时的不可变版本。
          </p>
          <h4 style="margin-top: 18px">历史版本</h4>
          <el-button size="small" text type="primary" @click="router.push(`/media/${project.mediaId}`)">
            查看源素材
          </el-button>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.segment {
  border: 1px solid var(--hub-border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 10px;
}
label { display: block; margin-bottom: 4px; }
</style>
