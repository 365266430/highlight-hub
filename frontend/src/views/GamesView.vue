<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { gameApi, errText, type GameProfile } from '../api'

const genres = ref<{ key: string; name: string; description: string;
  typicalEvents: string[]; exampleGames: string[] }[]>([])
const games = ref<GameProfile[]>([])
const loading = ref(true)
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const form = ref({ displayName: '', genre: 'fps', defaultRois: '[]', notes: '' })

const roiTemplates: Record<string, string> = {
  fps: JSON.stringify([{ id: 'killfeed', x: 0.6, y: 0.02, w: 0.38, h: 0.25, mode: 'text', eventType: 'ELIMINATION_NOTICE' }], null, 2),
  moba: JSON.stringify([{ id: 'announcement', x: 0.3, y: 0.05, w: 0.4, h: 0.1, mode: 'text', eventType: 'ELIMINATION_NOTICE' }], null, 2),
  sports: JSON.stringify([{ id: 'scoreboard', x: 0.35, y: 0.0, w: 0.3, h: 0.08, mode: 'number', eventType: 'SCORE_CHANGE' }], null, 2),
  'battle-royale': JSON.stringify([{ id: 'killfeed', x: 0.6, y: 0.02, w: 0.38, h: 0.25, mode: 'text', eventType: 'ELIMINATION_NOTICE' }], null, 2)
}

function templateFor(genre: string) {
  return roiTemplates[genre] || '[]'
}

async function load() {
  loading.value = true
  try {
    const [g, gs] = await Promise.all([gameApi.list(), gameApi.genres()])
    games.value = g.data
    genres.value = gs.data
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.value = { displayName: '', genre: 'fps', defaultRois: templateFor('fps'), notes: '' }
  dialogVisible.value = true
}

function openEdit(g: GameProfile) {
  editingId.value = g.id
  form.value = {
    displayName: g.displayName,
    genre: g.genre,
    defaultRois: JSON.stringify(g.defaultRois || [], null, 2),
    notes: g.notes || ''
  }
  dialogVisible.value = true
}

function onGenreChange(genre: string) {
  if (!editingId.value) {
    form.value.defaultRois = templateFor(genre)
  }
}

async function save() {
  let rois: unknown
  try {
    rois = JSON.parse(form.value.defaultRois)
  } catch {
    ElMessage.error('ROI 模板不是合法 JSON')
    return
  }
  try {
    if (editingId.value) {
      await gameApi.update(editingId.value, { ...form.value, defaultRois: rois })
    } else {
      await gameApi.create({ ...form.value, defaultRois: rois })
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function remove(g: GameProfile) {
  await ElMessageBox.confirm(`删除游戏档案「${g.displayName}」？已创建的分析不受影响。`, '删除', { type: 'warning' })
  try {
    await gameApi.remove(g.id)
    await load()
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

function genreName(key: string) {
  return genres.value.find((g) => g.key === key)?.name || key
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-title">
      游戏档案
      <el-button type="primary" size="small" style="margin-left: 12px" @click="openCreate">新建游戏档案</el-button>
    </div>

    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 14px"
      title="游戏档案 = 类型预设 + 默认 ROI 校准。类型只影响校准起点与规则预设，不代表已验证的自动识别能力；所有游戏都可手动标记与剪辑。"
    />

    <el-empty v-if="!games.length" description="还没有游戏档案，先新建一个（例如：CS2、王者荣耀）" />

    <el-row :gutter="16">
      <el-col v-for="g in games" :key="g.id" :span="8" style="margin-bottom: 16px">
        <div class="panel">
          <div style="display: flex; justify-content: space-between; align-items: center">
            <b>{{ g.displayName }}</b>
            <el-tag size="small">{{ genreName(g.genre) }}</el-tag>
          </div>
          <p class="muted" style="margin: 8px 0 4px">
            默认 ROI: {{ (g.defaultRois || []).length }} 个区域
          </p>
          <p class="muted" v-if="g.notes">{{ g.notes }}</p>
          <div style="display: flex; gap: 8px; margin-top: 10px">
            <el-button size="small" @click="openEdit(g)">编辑</el-button>
            <el-button size="small" type="danger" plain @click="remove(g)">删除</el-button>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑游戏档案' : '新建游戏档案'" width="640px">
      <el-form label-position="top">
        <el-form-item label="游戏显示名">
          <el-input v-model="form.displayName" placeholder="例如：CS2 / 王者荣耀" />
        </el-form-item>
        <el-form-item label="游戏类型（决定校准起点与规则预设）">
          <el-select v-model="form.genre" style="width: 100%" @change="onGenreChange">
            <el-option
              v-for="g in genres"
              :key="g.key"
              :value="g.key"
              :label="`${g.name} — ${g.description}`"
            />
          </el-select>
          <p class="muted" style="margin: 6px 0 0">
            典型事件: {{ genres.find((g) => g.key === form.genre)?.typicalEvents?.join(', ') || '-' }}
          </p>
        </el-form-item>
        <el-form-item label="默认 ROI 模板（相对坐标 0~1，进入分析时可再覆盖）">
          <el-input v-model="form.defaultRois" type="textarea" :rows="7" style="font-family: monospace" />
        </el-form-item>
        <el-form-item label="备注（分辨率 / HUD 等）">
          <el-input v-model="form.notes" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
