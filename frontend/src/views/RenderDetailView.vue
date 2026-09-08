<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { renderApi, type RenderJob } from '../api'

const route = useRoute()
const renderId = route.params.id as string
const render = ref<RenderJob | null>(null)
const timer = ref<ReturnType<typeof setTimeout>>()

async function load() {
  const resp = await renderApi.get(renderId)
  render.value = resp.data
  if (['QUEUED', 'RUNNING'].includes(resp.data.status)) {
    timer.value = setTimeout(load, 2000)
  }
}

onMounted(load)
onBeforeUnmount(() => timer.value && clearTimeout(timer.value))
</script>

<template>
  <div class="page" v-if="render">
    <div class="page-title">成片</div>
    <div class="panel">
      <p>
        状态：
        <el-tag :type="render.status === 'SUCCEEDED' ? 'success' : render.status === 'FAILED' ? 'danger' : 'info'">
          {{ render.status }}
        </el-tag>
        <span class="muted" style="margin-left: 12px">
          工程 {{ render.projectId }} · 版本 R{{ render.projectRevision }} · {{ render.presetVersion }}
        </span>
      </p>
      <el-alert
        v-if="render.status === 'FAILED'"
        type="error" :closable="false"
        :title="`${render.errorCode || ''}: ${render.errorMessage || ''}`"
        style="margin: 10px 0"
      />
      <video
        v-if="render.status === 'SUCCEEDED'"
        :src="renderApi.downloadUrl(render.id)"
        controls
        style="width: 100%; max-width: 860px; border-radius: 8px; background: #000"
      />
      <div v-if="render.status === 'SUCCEEDED'" style="margin-top: 14px">
        <a :href="renderApi.downloadUrl(render.id)" download>
          <el-button type="primary">下载 MP4（{{ ((render.outputSize || 0) / 1048576).toFixed(1) }} MB）</el-button>
        </a>
      </div>
      <p class="muted" style="margin-top: 12px">
        分享链接属于第二阶段能力，当前成片仅登录的所有者可见。
      </p>
    </div>
  </div>
</template>
