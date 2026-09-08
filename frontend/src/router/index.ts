import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
    { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
    { path: '/upload', name: 'upload', component: () => import('../views/UploadView.vue') },
    { path: '/media', name: 'media-list', component: () => import('../views/MediaListView.vue') },
    { path: '/media/:id', name: 'media-detail', component: () => import('../views/MediaDetailView.vue') },
    { path: '/media/:id/timeline', name: 'timeline', component: () => import('../views/TimelineView.vue') },
    { path: '/projects', name: 'projects', component: () => import('../views/ProjectListView.vue') },
    { path: '/projects/:id', name: 'project-editor', component: () => import('../views/ProjectEditorView.vue') },
    { path: '/renders/:id', name: 'render-detail', component: () => import('../views/RenderDetailView.vue') },
    { path: '/tasks', name: 'tasks', component: () => import('../views/TasksView.vue') },
    { path: '/admin', name: 'admin', component: () => import('../views/AdminView.vue') }
  ]
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.loaded) await auth.fetchMe()
  if (to.name !== 'login' && !auth.isLoggedIn) return { name: 'login' }
  if (to.name === 'login' && auth.isLoggedIn) return { name: 'home' }
  if (to.name === 'admin' && auth.user?.role !== 'ADMIN') return { name: 'home' }
  return true
})

export default router
