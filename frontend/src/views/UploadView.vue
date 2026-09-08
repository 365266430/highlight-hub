<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { uploadApi, errText } from '../api'

const router = useRouter()
const fileInput = ref<HTMLInputElement>()
const dragging = ref(false)
const busy = ref(false)
const uploadId = ref('')
const progressPct = ref(0)
const phase = ref('')
const currentFile = ref<File | null>(null)
const CHUNK = 512 * 1024

function pick() {
  fileInput.value?.click()
}

async function onFile(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (f) await start(f)
}

async function start(file: File) {
  currentFile.value = file
  busy.value = true
  phase.value = '创建上传会话'
  try {
    const resp = await uploadApi.create(file.name, file.size)
    uploadId.value = resp.data.uploadId
    const chunkSize = Math.min(resp.data.chunkSize || CHUNK, CHUNK)
    const total = Math.ceil(file.size / chunkSize)
    // resume: ask the server which chunks it already has
    const state = (await uploadApi.status(uploadId.value)).data as any
    const received: number[] = state.receivedChunks || []
    phase.value = '上传分片'
    for (let i = 0; i < total; i++) {
      if (received.includes(i)) continue
      const start = i * chunkSize
      const blob = await file.slice(start, Math.min(start + chunkSize, file.size)).arrayBuffer()
      let lastPct = 0
      await uploadApi.putChunk(uploadId.value, i, blob, (pct) => {
        const overall = Math.round(((i + pct / 100) / total) * 100)
        if (overall !== lastPct) {
          lastPct = overall
          progressPct.value = overall
        }
      })
      progressPct.value = Math.round(((i + 1) / total) * 100)
    }
    phase.value = '合并分片'
    const done = (await uploadApi.complete(uploadId.value)).data as any
    phase.value = '完成'
    ElMessage.success('上传完成，正在生成预览')
    router.push(`/media/${done.mediaId}`)
  } catch (e) {
    ElMessage.error(errText(e))
    busy.value = false
    phase.value = ''
  }
}

async function cancel() {
  if (uploadId.value) {
    try {
      await uploadApi.cancel(uploadId.value)
      ElMessage.info('已取消上传')
    } catch (e) {
      ElMessage.error(errText(e))
    }
  }
  busy.value = false
  phase.value = ''
  progressPct.value = 0
}
</script>

<template>
  <div class="page">
    <div class="page-title">上传录像</div>
    <div class="panel">
      <div
        v-if="!busy"
        class="dropzone"
        :class="{ dragging }"
        @click="pick"
        @dragover.prevent="dragging = true"
        @dragleave="dragging = false"
        @drop.prevent="dragging = false; onFile($event)"
      >
        <el-icon style="font-size: 42px; color: var(--hub-accent)"><UploadFilled /></el-icon>
        <p>点击选择或拖入录像文件</p>
        <p class="muted">支持分片上传与断点续传 · 单文件与配额上限由服务端控制</p>
      </div>
      <input ref="fileInput" type="file" hidden @change="onFile" />
      <div v-if="busy">
        <p>{{ phase }} - {{ currentFile?.name }}</p>
        <el-progress :percentage="progressPct" :stroke-width="14" />
        <div style="margin-top: 14px; display: flex; gap: 10px">
          <el-button @click="pick" :disabled="!currentFile">继续传其他文件</el-button>
          <el-button type="danger" plain @click="cancel">取消上传</el-button>
        </div>
        <p class="muted" style="margin-top: 12px">
          上传进度与处理进度（探测/预览/缩略图）分别显示，完成后跳转素材详情。
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dropzone {
  border: 2px dashed var(--hub-border);
  border-radius: 10px;
  padding: 60px 20px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.2s;
}
.dropzone.dragging, .dropzone:hover { border-color: var(--hub-accent); }
</style>
