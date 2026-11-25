import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/LoginView.vue') },
  { path: '/', redirect: '/catalog' },
  { path: '/catalog', component: () => import('../views/CatalogView.vue'), meta: { title: '对象目录与分层' } },
  { path: '/lineage', component: () => import('../views/LineageView.vue'), meta: { title: '血缘 DAG 与共享' } },
  { path: '/retention', component: () => import('../views/RetentionView.vue'), meta: { title: '保留策略与传播' } },
  { path: '/restore', component: () => import('../views/RestoreView.vue'), meta: { title: '恢复计划与校验' } },
  { path: '/cost', component: () => import('../views/CostView.vue'), meta: { title: '成本账与分摊' } },
  { path: '/snapshots', component: () => import('../views/SnapshotView.vue'), meta: { title: '归档快照回放' } },
  { path: '/cohort', component: () => import('../views/CohortView.vue'), meta: { title: '研究队列抽取' } },
  { path: '/projects', component: () => import('../views/ProjectView.vue'), meta: { title: '项目审阅' } }
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach((to) => {
  const token = localStorage.getItem('lakegov_token')
  if (to.path !== '/login' && !token) return '/login'
  if (to.path === '/login' && token) return '/catalog'
  return true
})

export default router
