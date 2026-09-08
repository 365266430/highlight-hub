import http from './http'
export { errText } from './http'

export interface User {
  id: number
  username: string
  displayName?: string
  role: string
  storageQuotaBytes: number
  usedBytes: number
}

export interface Media {
  id: string
  originalFilename: string
  status: string
  fileSize?: number
  durationMs?: number
  width?: number
  height?: number
  videoCodec?: string
  audioCodec?: string
  frameRate?: number
  variableFrameRate?: boolean
  rotation?: number
  audioStreamCount?: number
  hasPreview?: boolean
  hasThumbnails?: boolean
  thumbnailIndex?: {
    intervalSeconds: number
    columns: number
    rows: number
    frameWidth: number
    frameHeight: number
    count: number
  }
  errorMessage?: string
  createdAt?: string
}

export interface Task {
  id: string
  type: string
  status: string
  attempt: number
  maxAttempts: number
  progress: number
  phase: string
  inputRef: string
  errorCode: string
  errorMessage: string
  createdAt: string
  finishedAt?: string
}

export interface Project {
  id: string
  mediaId: string
  name: string
  latestRevision: number
  status: string
  createdAt: string
  updatedAt: string
  edl?: Edl
}

export interface Segment {
  id: string
  sourceInMs: number
  sourceOutMs: number
  caption: string
  sourceVolume: number
}

export interface MaskRegion {
  x: number
  y: number
  w: number
  h: number
}

export interface Edl {
  schemaVersion: number
  sourceMediaId: string
  segments: Segment[]
  output: { aspectMode: string; width: number; height: number; fps: number }
  /** static black boxes over the SOURCE picture (relative 0..1) */
  masks?: MaskRegion[]
}

export interface RenderJob {
  id: string
  projectId: string
  projectRevision: number
  status: string
  presetVersion: string
  taskId?: string
  outputSize?: number
  errorCode?: string
  errorMessage?: string
  createdAt: string
}

export interface VideoEvent {
  id: string
  mediaId: string
  analysisRunId?: string
  type: string
  startMs: number
  endMs?: number
  source: string
  status: string
  confidence?: number
  attributes?: Record<string, unknown>
}

export interface AnalysisRun {
  id: string
  mediaId: string
  mode: string
  status: string
  adapterVersionId?: string
  algorithmVersion?: string
  createdAt: string
}

export interface Candidate {
  id: string
  highlightRunId: string
  analysisRunId: string
  mediaId: string
  startMs: number
  endMs: number
  score?: number
  reasonCode: string
  reasonText: string
  eventIds: string[]
  status: string
}

export const authApi = {
  register: (username: string, password: string, displayName?: string) =>
    http.post('/api/auth/register', { username, password, displayName }),
  login: (username: string, password: string) =>
    http.post('/api/auth/login', { username, password }),
  logout: () => http.post('/api/auth/logout', {}),
  me: () => http.get<User>('/api/me'),
  csrf: () => http.get('/api/csrf')
}

export const uploadApi = {
  create: (originalFilename: string, declaredSize: number) =>
    http.post<{ uploadId: string; chunkSize: number; expectedChunkCount: number }>('/api/uploads', {
      originalFilename,
      declaredSize
    }),
  status: (id: string) => http.get(`/api/uploads/${id}`),
  putChunk: (id: string, index: number, data: ArrayBuffer, onProgress?: (pct: number) => void) =>
    http.put(`/api/uploads/${id}/chunks/${index}`, data, {
      headers: { 'Content-Type': 'application/octet-stream' },
      onUploadProgress: (e) => {
        if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
      }
    }),
  complete: (id: string) => http.post(`/api/uploads/${id}/complete`, {}),
  cancel: (id: string) => http.delete(`/api/uploads/${id}`)
}

export const mediaApi = {
  list: (page = 1, size = 20) => http.get('/api/media', { params: { page, size } }),
  get: (id: string) => http.get<Media>(`/api/media/${id}`),
  remove: (id: string) => http.delete(`/api/media/${id}`),
  previewUrl: (id: string) => `/api/media/${id}/preview`,
  thumbnailUrl: (id: string) => `/api/media/${id}/thumbnails`
}

export const taskApi = {
  list: (params?: Record<string, unknown>) => http.get('/api/tasks', { params }),
  get: (id: string) => http.get<Task>(`/api/tasks/${id}`),
  cancel: (id: string) => http.post(`/api/tasks/${id}/cancel`, {}),
  retry: (id: string) => http.post(`/api/tasks/${id}/retry`, {})
}

export const adapterApi = {
  list: () => http.get('/api/adapters'),
  versions: (id: string) => http.get(`/api/adapters/${id}/versions`)
}

export const analysisApi = {
  create: (mediaId: string, adapterVersionId: string, params: Record<string, unknown>) =>
    http.post<AnalysisRun>(`/api/media/${mediaId}/analyses`, { adapterVersionId, params }),
  get: (id: string) => http.get<AnalysisRun>(`/api/analyses/${id}`),
  events: (id: string) => http.get<VideoEvent[]>(`/api/analyses/${id}/events`)
}

export const eventApi = {
  list: (mediaId: string) => http.get<VideoEvent[]>(`/api/media/${mediaId}/events`),
  create: (mediaId: string, body: { type: string; startMs: number; endMs?: number; note?: string }) =>
    http.post<VideoEvent>(`/api/media/${mediaId}/events`, body),
  patch: (id: string, body: { startMs?: number; endMs?: number; type?: string }) =>
    http.patch<VideoEvent>(`/api/events/${id}`, body),
  reject: (id: string) => http.post(`/api/events/${id}/reject`, {})
}

export const highlightApi = {
  createRun: (analysisId: string, params: Record<string, unknown>) =>
    http.post(`/api/analyses/${analysisId}/highlight-runs`, params),
  candidates: (runId: string) =>
    http.get<Candidate[]>(`/api/highlight-runs/${runId}/candidates`),
  decide: (candidateId: string, status: 'ACCEPTED' | 'REJECTED' | 'PENDING') =>
    http.patch<Candidate>(`/api/highlight-candidates/${candidateId}`, { status })
}

export const projectApi = {
  create: (mediaId: string, name: string) => http.post<Project>('/api/projects', { mediaId, name }),
  list: (page = 1, size = 20) => http.get('/api/projects', { params: { page, size } }),
  get: (id: string) => http.get<Project>(`/api/projects/${id}`),
  save: (id: string, body: Record<string, unknown>) => http.put(`/api/projects/${id}`, body),
  revisions: (id: string) => http.get(`/api/projects/${id}/revisions`),
  revision: (id: string, revision: number) => http.get(`/api/projects/${id}/revisions/${revision}`)
}

export const renderApi = {
  submit: (projectId: string, projectRevision?: number) =>
    http.post<RenderJob>(`/api/projects/${projectId}/renders`, projectRevision ? { projectRevision } : {}),
  get: (id: string) => http.get<RenderJob>(`/api/renders/${id}`),
  downloadUrl: (id: string) => `/api/renders/${id}/download`
}
