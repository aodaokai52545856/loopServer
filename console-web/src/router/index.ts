import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '../features/dashboard/DashboardView.vue'
import TaskListView from '../features/tasks/TaskListView.vue'
import TaskDetailView from '../features/tasks/TaskDetailView.vue'
import NodeListView from '../features/nodes/NodeListView.vue'
import NodeDetailView from '../features/nodes/NodeDetailView.vue'
import ProjectProfileEditor from '../features/projects/ProjectProfileEditor.vue'
import DeliveryListView from '../features/operations/DeliveryListView.vue'
import AuditLogView from '../features/operations/AuditLogView.vue'

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
    {
      path: '/nodes',
      name: 'nodes',
      component: NodeListView,
    },
    {
      path: '/nodes/:nodeId',
      name: 'node-detail',
      component: NodeDetailView,
    },
    {
      path: '/projects',
      name: 'projects',
      component: ProjectProfileEditor,
    },
    {
      path: '/operations/deliveries',
      name: 'deliveries',
      component: DeliveryListView,
    },
    {
      path: '/operations/audit',
      name: 'audit',
      component: AuditLogView,
    },
  ],
})

export default router
