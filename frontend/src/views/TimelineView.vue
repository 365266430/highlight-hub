<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  mediaApi, analysisApi, eventApi, highlightApi, projectApi,
  type Media, type VideoEvent, type AnalysisRun, type Candidate
} from '../api'

const route = useRoute()
const router = useRouter()
const mediaId = route.params.id as string
const media = ref<Media | null>(null)
const runs = ref<AnalysisRun[]>([])
const events = ref<VideoEvent[]>([])
const candidates = ref<Candidate[]>([])
const filterType = ref('')
const busy = ref(false)

const eventTypes = ['ELIMINATION_NOTICE', 'SCORE_CHANGE', 'ROUND_START', 'ROUND_END', 'MANUAL_MARKER']
const filtered = computed(() =>
  filterType.value ? events.value.filter((e) => e.type === filterType.value) : events.value)

async function load() {
  const m = await mediaApi.get(mediaId)
  media.value = m.data
  // latest finished analysis' events + all manual events, merged by id
  const manual = (await eventApi.list(mediaId)).data
  events.value = manual
  candidates.value = []
}

async function runCandidates(runId: string) {
  try {
    const resp = await highlightApi.createRun(runId, {
      eventType: 'SCORE_CHANGE',
      windowMs: 6000,
      minimumCount: 3,
      paddingBeforeMs: 1500,
      paddingAfterMs: 1000,
      mergeGapMs: 1000,
      maxSegmentDurationMs: 30000
    })
    const runId2 = resp.data.id
    candidates.value = (await highlightApi.candidates(runId2)).data
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '候选生成失败')
  }
}

async function loadLatestAnalysisEvents() {
  // the timeline shows events of the most recent succeeded analysis if any
  const analysisId = route.query.analysis as string
  if (analysisId) {
    events.value = (await analysisApi.events(analysisId)).data
  } else {
    await load()
  }
}

async function addManual() {
  const { value } = await ElMessageBox.prompt('输入该标记的时刻（秒）', '添加手动事件', {
    inputPattern: /^\d+(\.\d+)?$/,
    inputErrorMessage: '请输入数字秒'
  })
  try {
    await eventApi.create(mediaId, {
      type: 'MANUAL_MARKER',
      startMs: Math.round(parseFloat(value) * 1000)
    })
    ElMessage.success('已添加')
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '添加失败')
  }
}

async function rejectEvent(e: VideoEvent) {
  await eventApi.reject(e.id)
  await load()
}

function seek(e: VideoEvent) {
  const video = document.querySelector('video')
  if (video) video.currentTime = e.startMs / 1000
}

async function acceptCandidate(c: Candidate) {
  await highlightApi.decide(c.id, 'ACCEPTED')
  const projectResp = await projectApi.create(mediaId, '我的高光工程')
  const p = projectResp.data
  const edl = {
    schemaVersion: 1,
    sourceMediaId: mediaId,
    segments: [{
      id: 's1',
      sourceInMs: c.startMs,
      sourceOutMs: c.endMs,
      caption: '',
      sourceVolume: 1.0
    }],
    output: { aspectMode: 'SOURCE', width: 1920, height: 1080, fps: 30 }
  }
  await projectApi.save(p.id, { expectedRevision: 0, name: p.name, ...edl })
  ElMessage.success('已从候选创建剪辑工程')
  router.push(`/projects/${p.id}`)
}

async function rejectCandidate(c: Candidate) {
  await highlightApi.decide(c.id, 'REJECTED')
  candidates.value = candidates.value.filter((x) => x.id !== c.id)
}

function fmt(ms: number) {
  const s = ms / 1000
  return `${Math.floor(s / 60)}:${String((s % 60).toFixed(1)).padStart(4, '0')}`
}

onMounted(loadLatestAnalysisEvents)
</script>

<template>
  <div class="page" v-if="media">
    <div class="page-title">
      事件时间轴 - {{ media.originalFilename }}
      <el-button size="small" text @click="router.push(`/media/${media.id}`)">返回素材</el-button>
    </div>
    <el-row :gutter="16">
      <el-col :span="15">
        <div class="panel">
          <video
            v-if="media.hasPreview"
            :src="mediaApi.previewUrl(media.id)"
            controls
            style="width: 100%; border-radius: 8px; background: #000"
          />
          <p v-else class="muted">预览尚未生成，事件仍可查看与编辑</p>

          <div style="margin: 14px 0; display: flex; gap: 10px; align-items: center">
            <el-select v-model="filterType" clearable placeholder="事件类型筛选" style="width: 220px">
              <el-option v-for="t in eventTypes" :key="t" :value="t" :label="t" />
            </el-select>
            <el-button @click="addManual">添加手动事件</el-button>
            <el-button
              type="primary" plain :loading="busy"
              @click="runs.length ? null : null; $router.go(0)"
            >刷新</el-button>
          </div>

          <!-- timeline strip -->
          <div class="strip">
            <div
              v-for="e in filtered"
              :key="e.id"
              class="tick"
              :class="e.type"
              :style="{ left: (e.startMs / (media.durationMs || 1)) * 100 + '%' }"
              :title="`${e.type} @ ${fmt(e.startMs)}`"
              @click="seek(e)"
            />
          </div>

          <el-table :data="filtered" size="small" style="margin-top: 12px">
            <el-table-column label="时间" width="110">
              <template #default="{ row }">{{ fmt(row.startMs) }}</template>
            </el-table-column>
            <el-table-column prop="type" label="类型" width="190" />
            <el-table-column label="来源" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.source === 'AUTO' ? 'warning' : 'info'">{{ row.source }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="180">
              <template #default="{ row }">
                {{ (row.attributes && (row.attributes.to || row.attributes.note)) || '' }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170">
              <template #default="{ row }">
                <el-button size="small" text @click="seek(row)">跳转</el-button>
                <el-popconfirm title="拒绝该事件？" @confirm="rejectEvent(row)">
                  <template #reference>
                    <el-button size="small" text type="danger">拒绝</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
      <el-col :span="9">
        <div class="panel">
          <h4>高光候选</h4>
          <p class="muted">
            候选由规则根据事件密度生成，原因均可追溯；只有你确认过的片段才会进入剪辑工程。
          </p>
          <el-button
            type="primary"
            :loading="busy"
            @click="runs.length ? null : null; runCandidates((events[0] && events[0].analysisRunId) || ($route.query.analysis as string))"
          >生成候选（默认规则）</el-button>
          <el-empty v-if="!candidates.length" description="尚无候选" :image-size="60" />
          <div v-for="c in candidates" :key="c.id" class="candidate">
            <div>
              <b>{{ fmt(c.startMs) }} - {{ fmt(c.endMs) }}</b>
              <div class="muted">{{ c.reasonText }}</div>
            </div>
            <div style="margin-top: 8px; display: flex; gap: 8px">
              <el-button size="small" type="primary" @click="acceptCandidate(c)">保留并建工程</el-button>
              <el-button size="small" @click="rejectCandidate(c)">排除</el-button>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.strip {
  position: relative; height: 34px; background: var(--hub-bg);
  border: 1px solid var(--hub-border); border-radius: 6px; margin-top: 8px;
}
.tick {
  position: absolute; top: 4px; bottom: 4px; width: 6px;
  border-radius: 3px; background: var(--hub-accent); cursor: pointer;
}
.tick.SCORE_CHANGE { background: var(--hub-accent); }
.tick.MANUAL_MARKER { background: var(--hub-accent2); }
.tick.ROUND_START, .tick.ROUND_END { background: #e6b84c; }
.candidate {
  border: 1px solid var(--hub-border); border-radius: 8px;
  padding: 10px 12px; margin-top: 10px;
}
</style>
