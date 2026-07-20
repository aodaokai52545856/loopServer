import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '../features/dashboard/DashboardView.vue'
import TaskListView from '../features/tasks/TaskListView.vue'
import TaskDetailView from '../features/tasks/TaskDetailView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'dashboard',
      component: DashboardView,
    },
    {
      path: '/tasks',
      name: 'tasks',
      component: TaskListView,
    },
    {
      path: '/tasks/:taskId',
      name: 'task-detail',
      component: TaskDetailView,
    },
  ],
})

export default router
