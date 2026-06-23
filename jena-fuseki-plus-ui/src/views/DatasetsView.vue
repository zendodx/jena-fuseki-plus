<template>
  <div class="datasets-view">
    <!-- 服务状态卡片 -->
    <div class="status-row">
      <div class="status-card" :class="health?.fusekiRunning ? 'ok' : 'err'">
        <div class="sc-icon">
          <el-icon size="28"><component :is="health?.fusekiRunning ? 'CircleCheckFilled' : 'CircleCloseFilled'" /></el-icon>
        </div>
        <div class="sc-info">
          <div class="sc-title">Fuseki 服务</div>
          <div class="sc-value">{{ health?.fusekiRunning ? '运行中' : '未就绪' }}</div>
        </div>
      </div>
      <div class="status-card ok">
        <div class="sc-icon"><el-icon size="28"><DataBoard /></el-icon></div>
        <div class="sc-info">
          <div class="sc-title">数据集数量</div>
          <div class="sc-value">{{ datasets.length }}</div>
        </div>
      </div>
      <div class="status-card info">
        <div class="sc-icon"><el-icon size="28"><Connection /></el-icon></div>
        <div class="sc-info">
          <div class="sc-title">API 端口</div>
          <div class="sc-value">:3040</div>
        </div>
      </div>
      <div class="status-card info">
        <div class="sc-icon"><el-icon size="28"><Lightning /></el-icon></div>
        <div class="sc-info">
          <div class="sc-title">SPARQL 端口</div>
          <div class="sc-value">:3030</div>
        </div>
      </div>
    </div>

    <!-- 数据集列表 -->
    <div class="card">
      <div class="card-header">
        <span class="card-title">数据集列表</span>
        <el-button type="primary" :icon="Refresh" @click="loadAll" :loading="loading">刷新</el-button>
      </div>
      <el-table :data="datasetsDetail" v-loading="loading" stripe border style="width:100%">
        <el-table-column prop="name" label="数据集名称" min-width="160">
          <template #default="{ row }">
            <el-link type="primary" :href="`http://localhost:3030/${row.name}`" target="_blank">
              {{ row.name }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.accepting ? 'success' : 'danger'" size="small">
              {{ row.accepting ? '接受请求' : '已暂停' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalTriples" label="三元组数" width="130" align="right">
          <template #default="{ row }">
            {{ row.totalTriples != null ? formatNum(row.totalTriples) : '加载中...' }}
          </template>
        </el-table-column>
        <el-table-column label="实体类型数" width="120" align="right">
          <template #default="{ row }">
            {{ row.typeCount != null ? row.typeCount : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="SPARQL 端点" min-width="260">
          <template #default="{ row }">
            <code class="endpoint">http://localhost:3030/{{ row.name }}/sparql</code>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openGraph(row.name)" :icon="Share">图谱</el-button>
            <el-button size="small" @click="openSparql(row.name)" :icon="Search">查询</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 类型分布（当前选中数据集） -->
    <div class="card" v-if="currentOverview">
      <div class="card-header">
        <span class="card-title">类型分布 - {{ currentOverview.dataset }}</span>
      </div>
      <div class="type-list">
        <div v-for="(t, i) in currentOverview.typeDistribution" :key="t.type"
          class="type-bar-row">
          <span class="type-rank">#{{ i + 1 }}</span>
          <span class="type-label" :title="t.type">{{ t.label || t.type }}</span>
          <div class="type-bar-wrap">
            <div class="type-bar"
              :style="{width: barWidth(t.count, currentOverview.typeDistribution) + '%'}">
            </div>
          </div>
          <span class="type-count">{{ formatNum(t.count) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import {onMounted, ref} from 'vue'
import {fusekiApi, graphApi} from '@/api/index.js'
import {useRouter} from 'vue-router'
import {Refresh, Search, Share} from '@element-plus/icons-vue'

const router = useRouter()
const loading = ref(false)
const datasets = ref([])
const datasetsDetail = ref([])
const health = ref(null)
const currentOverview = ref(null)

onMounted(() => loadAll())

async function loadAll() {
  loading.value = true
  try {
    // 健康状态
    health.value = await fusekiApi.health().catch(() => null)

    // 数据集列表
    const res = await fusekiApi.datasets()
    datasets.value = res.datasets || []

    // 每个数据集查询概览
    datasetsDetail.value = datasets.value.map(d => ({
      name: d.name,
      accepting: d.accepting,
      totalTriples: null,
      typeCount: null,
    }))

    // 异步填充统计数据
    for (const ds of datasetsDetail.value) {
      try {
        const ov = await graphApi.overview('/' + ds.name)
        ds.totalTriples = ov.totalTriples
        ds.typeCount = ov.typeDistribution?.length || 0
        // 记录第一个数据集的详细分布用于图表
        if (!currentOverview.value) currentOverview.value = ov
      } catch {}
    }
  } finally {
    loading.value = false
  }
}

function openGraph(name) {
  router.push({ path: '/graph', query: { dataset: name } })
}
function openSparql(name) {
  router.push({ path: '/sparql', query: { dataset: name } })
}

function formatNum(n) {
  if (n == null) return '-'
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K'
  return n
}

function barWidth(count, list) {
  const max = Math.max(...list.map(t => t.count || 0), 1)
  return Math.round((count / max) * 100)
}
</script>

<style scoped>
.datasets-view {
  padding: 20px;
  overflow-y: auto;
  height: calc(100vh - 56px);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 状态卡片行 */
.status-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.status-card {
  background: #fff;
  border-radius: 8px;
  padding: 16px 20px;
  display: flex;
  align-items: center;
  gap: 14px;
  box-shadow: 0 1px 4px rgba(0,0,0,.06);
  border-left: 4px solid #ddd;
}
.status-card.ok { border-left-color: #67c23a; }
.status-card.ok .sc-icon { color: #67c23a; }
.status-card.err { border-left-color: #f56c6c; }
.status-card.err .sc-icon { color: #f56c6c; }
.status-card.info { border-left-color: #409eff; }
.status-card.info .sc-icon { color: #409eff; }
.sc-title { font-size: 12px; color: #909399; }
.sc-value { font-size: 22px; font-weight: 700; color: #303133; margin-top: 2px; }

/* 卡片 */
.card {
  background: #fff;
  border-radius: 8px;
  padding: 16px 20px;
  box-shadow: 0 1px 4px rgba(0,0,0,.06);
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.card-title { font-size: 15px; font-weight: 600; color: #303133; }
.endpoint {
  font-size: 12px;
  color: #409eff;
  background: #f0f7ff;
  padding: 2px 6px;
  border-radius: 3px;
}

/* 类型分布 */
.type-list { display: flex; flex-direction: column; gap: 8px; }
.type-bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.type-rank { color: #aaa; width: 28px; text-align: right; flex-shrink: 0; }
.type-label {
  width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #303133;
  flex-shrink: 0;
}
.type-bar-wrap {
  flex: 1;
  background: #f0f2f5;
  border-radius: 4px;
  height: 14px;
  overflow: hidden;
}
.type-bar {
  height: 100%;
  background: linear-gradient(90deg, #409eff, #79bbff);
  border-radius: 4px;
  transition: width 0.5s ease;
  min-width: 4px;
}
.type-count { width: 52px; text-align: right; color: #606266; flex-shrink: 0; }
</style>

