import {createRouter, createWebHashHistory} from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/graph',
  },
  {
    path: '/graph',
    name: 'Graph',
    component: () => import('@/views/GraphView.vue'),
    meta: { title: '知识图谱', icon: 'Share' },
  },
  {
    path: '/sparql',
    name: 'Sparql',
    component: () => import('@/views/SparqlView.vue'),
    meta: { title: 'SPARQL 查询', icon: 'Search' },
  },
  {
    path: '/datasets',
    name: 'Datasets',
    component: () => import('@/views/DatasetsView.vue'),
    meta: { title: '数据集管理', icon: 'DataBoard' },
  },
]

export default createRouter({
  history: createWebHashHistory(),
  routes,
})

