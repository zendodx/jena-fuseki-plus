<template>
  <div class="graph-view">
    <!-- 左侧控制面板 -->
    <div class="control-panel">

      <!-- 数据集 -->
      <div class="panel-section">
        <div class="section-title">数据集</div>
        <el-select v-model="selectedDataset" placeholder="选择数据集" size="small" style="width:100%"
          @change="onDatasetChange">
          <el-option v-for="ds in datasets" :key="ds.name" :label="ds.name" :value="ds.name" />
        </el-select>
      </div>

      <!-- 统计概览 -->
      <div class="panel-section" v-if="overview">
        <div class="section-title">概览</div>
        <div class="stat-grid">
          <div class="stat-item">
            <div class="stat-value">{{ formatNum(overview.totalTriples) }}</div>
            <div class="stat-label">三元组</div>
          </div>
          <div class="stat-item">
            <div class="stat-value">{{ overview.typeDistribution?.length || 0 }}</div>
            <div class="stat-label">实体类型</div>
          </div>
        </div>
        <div ref="typeChartEl" class="type-chart"></div>
      </div>

      <!-- 搜索节点 -->
      <div class="panel-section">
        <div class="section-title">搜索节点</div>
        <el-input v-model="searchKeyword" placeholder="实体名称 / URI 片段"
          clearable size="small" @keyup.enter="doSearch" :prefix-icon="Search" />
        <el-button type="primary" size="small" style="width:100%;margin-top:6px"
          @click="doSearch" :loading="searching">搜索</el-button>
        <div class="search-result" v-if="searchResults.length">
          <div v-for="node in searchResults" :key="node.uri"
            class="search-item" @click="locateOrLoadNode(node.uri)">
            <!-- 已在画布：定位图标；未在画布：加载图标 -->
            <el-icon :style="isOnCanvas(node.uri) ? 'color:#67c23a' : 'color:#409eff'">
              <component :is="isOnCanvas(node.uri) ? 'Aim' : 'Share'" />
            </el-icon>
            <span class="search-label" :title="node.uri">{{ node.label || node.uri }}</span>
            <el-tag v-if="isOnCanvas(node.uri)" size="small" type="success" style="flex-shrink:0">已有</el-tag>
          </div>
        </div>
      </div>

      <!-- 属性筛选搜索 -->
      <div class="panel-section">
        <div class="section-title">属性筛选</div>
        <div class="prop-search-hint">谓词留空则搜所有属性</div>
        <el-input
          v-model="propSearchPredicate"
          placeholder="谓词（可选）"
          clearable size="small" style="margin-bottom:6px"
        />
        <el-input
          v-model="propSearchValue"
          placeholder="属性值关键词（必填）"
          clearable size="small"
          @keyup.enter="doSearchByProp"
        />
        <el-button type="primary" size="small" style="width:100%;margin-top:6px"
          @click="doSearchByProp" :loading="propSearching">
          <el-icon><Filter /></el-icon> 筛选节点
        </el-button>
        <div class="search-result" v-if="propSearchResults.length">
          <div v-for="node in propSearchResults" :key="node.uri"
            class="search-item prop-result-item" @click="locateOrLoadNode(node.uri)">
            <el-icon :style="isOnCanvas(node.uri) ? 'color:#67c23a' : 'color:#409eff'">
              <component :is="isOnCanvas(node.uri) ? 'Aim' : 'Share'" />
            </el-icon>
            <div class="prop-result-info">
              <div class="search-label" :title="node.uri">{{ node.label || node.uri }}</div>
              <div class="prop-result-match" v-if="node.matchVal">
                <span class="match-prop">{{ node.matchProp || '属性' }}:</span>
                <span class="match-val">{{ node.matchVal }}</span>
              </div>
            </div>
          </div>
        </div>
        <div v-else-if="propSearchDone" class="prop-no-result">未找到匹配节点</div>
      </div>

      <!-- 展开深度（支持 0 = 纯属性查看） -->
      <div class="panel-section">
        <div class="section-title">展开深度</div>
        <el-slider v-model="expandDepth" :min="0" :max="3" :step="1" show-stops size="small" />
        <div class="depth-labels">
          <span>仅属性</span><span>1跳</span><span>2跳</span><span>3跳</span>
        </div>
      </div>

      <!-- 图谱操作 -->
      <div class="panel-section">
        <div class="section-title">操作</div>
        <el-button-group style="width:100%">
          <el-button size="small" @click="resetGraph" style="flex:1">
            <el-icon><RefreshLeft /></el-icon> 重置
          </el-button>
          <el-button size="small" @click="fitGraph" style="flex:1">
            <el-icon><FullScreen /></el-icon> 适配
          </el-button>
        </el-button-group>
        <el-button size="small" style="width:100%;margin-top:6px" @click="clearGraph">
          <el-icon><Delete /></el-icon> 清空画布
        </el-button>
      </div>

      <!-- 画布背景 -->
      <div class="panel-section">
        <div class="section-title">画布背景</div>
        <div class="bg-options">
          <div
            v-for="bg in bgOptions" :key="bg.key"
            class="bg-swatch"
            :class="{ active: canvasBg === bg.key }"
            :style="{ background: bg.preview }"
            :title="bg.label"
            @click="setCanvasBg(bg.key)"
          >
            <span class="bg-label">{{ bg.label }}</span>
          </div>
        </div>
      </div>

      <!-- 节点类型图例 -->
      <div class="panel-section" v-if="typeLegend.length">
        <div class="section-title">节点类型图例</div>
        <div class="legend-list">
          <div v-for="item in typeLegend" :key="item.type" class="legend-item">
            <span class="legend-dot" :style="{ background: item.color }"></span>
            <span class="legend-label" :title="item.type">{{ item.label }}</span>
          </div>
        </div>
      </div>

    </div>

    <!-- 图谱画布 -->
    <div class="graph-canvas-wrap" :class="'bg-' + canvasBg">
      <div v-if="!graphData.nodes.length" class="empty-hint">
        <el-empty description="搜索节点并点击加载图谱" :image-size="120">
          <el-button type="primary" @click="loadSampleQuery">加载示例（前20个节点）</el-button>
        </el-empty>
      </div>
      <canvas ref="graphCanvas" class="graph-canvas"
        @mousedown="onMouseDown" @mousemove="onMouseMove" @mouseup="onMouseUp"
        @wheel="onWheel" @dblclick="onDblClick" @contextmenu.prevent="onRightClick">
      </canvas>

      <!-- 缩放工具栏（悬浮在画布左下角） -->
      <div class="zoom-toolbar" v-if="graphData.nodes.length">
        <el-tooltip content="放大" placement="right"><div class="zoom-btn" @click="zoomIn"><el-icon><ZoomIn /></el-icon></div></el-tooltip>
        <el-tooltip content="缩小" placement="right"><div class="zoom-btn" @click="zoomOut"><el-icon><ZoomOut /></el-icon></div></el-tooltip>
        <div class="zoom-scale">{{ Math.round(transform.scale * 100) }}%</div>
        <el-tooltip content="重置视图" placement="right"><div class="zoom-btn" @click="resetGraph"><el-icon><RefreshLeft /></el-icon></div></el-tooltip>
        <el-tooltip content="适配画布" placement="right"><div class="zoom-btn" @click="fitGraph"><el-icon><FullScreen /></el-icon></div></el-tooltip>
      </div>

      <!-- 加载遮罩 -->
      <div v-if="loading" class="loading-mask">
        <el-icon class="spin" size="40"><Loading /></el-icon>
        <span>查询中...</span>
      </div>
      <!-- 节点数/边数提示 -->
      <div class="graph-info" v-if="graphData.nodes.length">
        节点 {{ graphData.nodes.length }} · 边 {{ graphData.edges.length }}
      </div>
      <!-- 右键上下文菜单 -->
      <div v-if="ctxMenu.visible" class="ctx-menu"
        :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }"
        @mouseleave="ctxMenu.visible = false">
        <div class="ctx-item ctx-item-danger" @click="removeNode(ctxMenu.nodeId)">
          <el-icon><Delete /></el-icon> 移除节点
        </div>
        <div class="ctx-item" @click="expandNode(ctxMenu.nodeId); ctxMenu.visible = false">
          <el-icon><Plus /></el-icon> 展开邻居
        </div>
      </div>
    </div>

  </div>

  <!-- 右侧节点详情浮层 -->
  <transition name="detail-slide">
    <div v-if="drawerVisible" class="detail-panel">
      <div class="detail-panel-header">
        <span class="detail-panel-title">节点详情</span>
        <el-icon class="detail-close" @click="drawerVisible = false"><Delete /></el-icon>
      </div>
      <div class="detail-panel-body">
        <div v-if="selectedNodeDetail">
          <div class="detail-uri" :title="selectedNodeDetail.uri">
            {{ selectedNodeDetail.uri }}
          </div>
          <el-divider style="margin:10px 0" />
          <div class="detail-label">{{ selectedNodeDetail.label || '未知' }}</div>
          <el-button type="primary" size="small" style="margin-bottom:12px"
            @click="expandNode(selectedNodeDetail.uri)">
            <el-icon><Plus /></el-icon> 展开邻居
          </el-button>
          <div class="prop-list">
            <div v-for="prop in selectedNodeDetail.properties" :key="prop.predicate"
              class="prop-item">
              <div class="prop-key" :title="prop.predicate">{{ extractLabel(prop.predicate) }}</div>
              <div class="prop-val" :title="prop.object">{{ prop.object }}</div>
            </div>
          </div>
        </div>
        <el-empty v-else description="点击节点查看详情" />
      </div>
    </div>
  </transition>
</template>

<script setup>
import {computed, nextTick, onActivated, onBeforeUnmount, onMounted, reactive, ref} from 'vue'
import {fusekiApi, graphApi} from '@/api/index.js'
import {useGraphStore} from '@/stores/graphStore.js'
import * as echarts from 'echarts'
import {ElMessage} from 'element-plus'
import {Delete, Filter, FullScreen, Loading, Plus, RefreshLeft, Search, ZoomIn, ZoomOut} from '@element-plus/icons-vue'

// ─── 状态 ─────────────────────────────────────────────
const datasets = ref([])
const selectedDataset = ref('')
const overview = ref(null)
const searchKeyword = ref('')
const searchResults = ref([])
const searching = ref(false)
const expandDepth = ref(1)
const loading = ref(false)
const drawerVisible = ref(false)
const selectedNodeDetail = ref(null)

// ─── 右键菜单状态 ────────────────────────────────────
const ctxMenu = reactive({ visible: false, x: 0, y: 0, nodeId: null })

// ─── 属性筛选搜索状态 ──────────────────────────────────
const propSearchPredicate = ref('')
const propSearchValue = ref('')
const propSearchResults = ref([])
const propSearching = ref(false)
const propSearchDone = ref(false)

// ─── 背景主题 ────────────────────────────────────────
const canvasBg = ref('light')
const bgOptions = [
  { key: 'light', label: '浅灰', preview: 'radial-gradient(ellipse at center,#f8faff 0%,#eceff8 100%)' },
  { key: 'dark',  label: '深色', preview: 'radial-gradient(ellipse at center,#1a1f2e 0%,#0d1117 100%)' },
]

function setCanvasBg(key) {
  canvasBg.value = key
  drawFrame()
}

function themeColor(light, dark) {
  return canvasBg.value === 'dark' ? dark : light
}

const graphData = reactive({ nodes: [], edges: [] })

// ─── rdf:type 着色 ────────────────────────────────────
// 颜色池（按类型分配，固定映射）
const TYPE_COLORS = [
  '#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de',
  '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc', '#48b0a8',
  '#f0a045', '#d6547e', '#6e9ef4', '#b5c766', '#ff7c7c',
]
// 未知类型的默认颜色
const DEFAULT_NODE_COLOR = '#aab0c0'

// 类型 URI → 颜色
const typeColorMap = {}   // typeUri → color
// 节点 URI → 类型 URI（从邻居查询结果中提取 rdf:type 边）
const nodeTypeMap = {}    // nodeId → typeUri

const RDF_TYPE = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type'

// 当前图例（有类型的才显示）
const typeLegend = computed(() => {
  const seen = new Set()
  const items = []
  for (const n of graphData.nodes) {
    const typeUri = nodeTypeMap[n.id]
    if (typeUri && !seen.has(typeUri)) {
      seen.add(typeUri)
      items.push({
        type: typeUri,
        label: extractLabel(typeUri),
        color: getTypeColor(typeUri),
      })
    }
  }
  return items
})

function getTypeColor(typeUri) {
  if (!typeUri) return DEFAULT_NODE_COLOR
  if (!typeColorMap[typeUri]) {
    const idx = Object.keys(typeColorMap).length % TYPE_COLORS.length
    typeColorMap[typeUri] = TYPE_COLORS[idx]
  }
  return typeColorMap[typeUri]
}

function getNodeColor(node) {
  const typeUri = nodeTypeMap[node.id]
  return getTypeColor(typeUri)
}

// 从 edges 中提取 rdf:type 信息，更新 nodeTypeMap
function extractNodeTypes(edges) {
  for (const e of edges) {
    if (e.predicate === RDF_TYPE) {
      nodeTypeMap[e.source] = e.target
    }
  }
}

// ─── 高亮节点 ─────────────────────────────────────────
let highlightNodeId = null   // 当前高亮的节点 id
let highlightTimer = null    // 高亮自动消除定时器

function isOnCanvas(uri) {
  return graphData.nodes.some(n => n.id === uri)
}

/** 搜索结果点击：已在画布则高亮居中，否则加载 */
async function locateOrLoadNode(uri) {
  if (isOnCanvas(uri)) {
    locateNode(uri)
  } else {
    await loadNode(uri)
  }
}

/** 将已有节点高亮并平滑居中到画布中央 */
function locateNode(uri) {
  const pos = nodePositions[uri]
  if (!pos) return

  // 高亮
  highlightNodeId = uri
  if (highlightTimer) clearTimeout(highlightTimer)
  highlightTimer = setTimeout(() => {
    highlightNodeId = null
    drawFrame()
  }, 2000)

  // 平滑居中：将该节点移动到 canvas 中心
  const canvas = graphCanvas.value
  if (!canvas) return
  const W = canvas.width
  const H = canvas.height
  const targetX = W / 2 - pos.x * transform.scale
  const targetY = H / 2 - pos.y * transform.scale

  // 简单线性动画（10帧）
  const startX = transform.x
  const startY = transform.y
  const frames = 15
  let frame = 0
  function animate() {
    frame++
    const t = frame / frames
    const ease = t < 0.5 ? 2 * t * t : -1 + (4 - 2 * t) * t  // easeInOut
    transform.x = startX + (targetX - startX) * ease
    transform.y = startY + (targetY - startY) * ease
    drawFrame()
    if (frame < frames) requestAnimationFrame(animate)
  }
  requestAnimationFrame(animate)

  ElMessage.success({ message: '已定位节点', duration: 1200 })
}

// Canvas refs
const graphCanvas = ref(null)
const typeChartEl = ref(null)
let typeChart = null

// ─── Canvas 渲染状态 ────────────────────────────────────
let ctx = null
const transform = reactive({ x: 0, y: 0, scale: 1 })
let nodePositions = {}
let dragNode = null
let lastMouse = { x: 0, y: 0 }
let isPanning = false
let animFrame = null
let simulationRunning = false

// ─── 初始化 ───────────────────────────────────────────
onMounted(async () => {
  await loadDatasets()
  initCanvas()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (animFrame) cancelAnimationFrame(animFrame)
  if (highlightTimer) clearTimeout(highlightTimer)
  typeChart?.dispose()
})

const graphStore = useGraphStore()

onActivated(async () => {
  const task = graphStore.consumePendingSparql()
  if (!task) return
  if (!datasets.value.length) await loadDatasets()
  const matched = datasets.value.find(d => (d.path || '/' + d.name) === task.dataset)
  if (matched) selectedDataset.value = matched.name
  loading.value = true
  try {
    const res = await graphApi.sparqlToGraph(task.dataset, task.sparql)
    clearGraph()
    mergeGraph(res, null)
    const nodeCount = res.nodes?.length || 0
    if (nodeCount === 0) {
      ElMessage.warning('未渲染出节点。若查询结果不含 URI 列，图谱无法展示，请确保结果包含 ?s ?p ?o 或其他 URI 类型的列')
    } else {
      ElMessage.success(`渲染完成，共 ${nodeCount} 个节点`)
    }
  } catch {
    ElMessage.error('SPARQL 转图谱失败，请检查查询语句')
  } finally {
    loading.value = false
  }
})

function datasetPath(name) {
  const ds = datasets.value.find(d => d.name === name)
  return ds?.path || ('/' + name)
}

async function loadDatasets() {
  try {
    const res = await fusekiApi.datasets()
    datasets.value = res.datasets || []
    if (datasets.value.length) {
      selectedDataset.value = datasets.value[0].name
      await onDatasetChange()
    }
  } catch {
    datasets.value = [{ name: 'travel_kg_v4', path: '/travel_kg_v4' }]
    selectedDataset.value = 'travel_kg_v4'
  }
}

async function onDatasetChange() {
  if (!selectedDataset.value) return
  try {
    overview.value = await graphApi.overview(datasetPath(selectedDataset.value))
    await nextTick()
    renderTypeChart()
  } catch {}
}

// ─── 类型分布饼图 ──────────────────────────────────────
function renderTypeChart() {
  if (!typeChartEl.value || !overview.value?.typeDistribution?.length) return
  if (!typeChart) typeChart = echarts.init(typeChartEl.value)
  const top8 = overview.value.typeDistribution.slice(0, 8)
  typeChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c}' },
    series: [{
      type: 'pie',
      radius: ['35%', '65%'],
      data: top8.map(t => ({ name: t.label || t.type, value: t.count })),
      label: { fontSize: 10, overflow: 'truncate', width: 60 },
    }]
  })
}

// ─── 搜索 ─────────────────────────────────────────────
async function doSearch() {
  if (!searchKeyword.value.trim() || !selectedDataset.value) return
  searching.value = true
  try {
    const res = await graphApi.search(datasetPath(selectedDataset.value), searchKeyword.value)
    searchResults.value = res.nodes || []
  } finally {
    searching.value = false
  }
}

// ─── 属性筛选搜索 ──────────────────────────────────────
async function doSearchByProp() {
  if (!propSearchValue.value.trim()) {
    ElMessage.warning('请输入属性值关键词')
    return
  }
  if (!selectedDataset.value) {
    ElMessage.warning('请先选择数据集')
    return
  }
  propSearching.value = true
  propSearchDone.value = false
  propSearchResults.value = []
  try {
    const res = await graphApi.searchByProp(
      datasetPath(selectedDataset.value),
      propSearchPredicate.value.trim(),
      propSearchValue.value.trim(),
    )
    propSearchResults.value = res.nodes || []
  } catch {
    // 错误由 http 拦截器统一弹出
  } finally {
    propSearching.value = false
    propSearchDone.value = true
  }
}

// ─── 加载节点 ─────────────────────────────────────────
async function loadNode(uri) {
  if (!selectedDataset.value) return
  loading.value = true
  try {
    if (expandDepth.value === 0) {
      // 深度 0：只查自身属性，不展开邻居，显示节点详情
      const res = await graphApi.nodeDetail(datasetPath(selectedDataset.value), uri)
      selectedNodeDetail.value = res
      drawerVisible.value = true
      // 仍把节点加入图谱（作为孤立节点）
      if (!isOnCanvas(uri)) {
        const fakeNode = { id: uri, uri, label: res.label || extractLabel(uri), isCenter: true }
        graphData.nodes.push(fakeNode)
        const W = graphCanvas.value?.width || 800
        const H = graphCanvas.value?.height || 600
        const cx = (W / 2 - transform.x) / transform.scale
        const cy = (H / 2 - transform.y) / transform.scale
        nodePositions[uri] = { x: cx, y: cy, vx: 0, vy: 0 }
        drawFrame()
      }
    } else {
      const res = await graphApi.neighbors(datasetPath(selectedDataset.value), uri, expandDepth.value)
      mergeGraph(res, uri)
    }
  } finally {
    loading.value = false
  }
}

async function expandNode(uri) {
  // 展开时临时使用至少 1 跳
  const savedDepth = expandDepth.value
  if (expandDepth.value === 0) expandDepth.value = 1
  await loadNode(uri)
  expandDepth.value = savedDepth
  drawerVisible.value = false
}

async function loadSampleQuery() {
  if (!selectedDataset.value) return
  loading.value = true
  try {
    const sparql = 'SELECT ?s ?p ?o WHERE { ?s ?p ?o FILTER(isURI(?o)) } LIMIT 80'
    const res = await graphApi.sparqlToGraph(datasetPath(selectedDataset.value), sparql)
    mergeGraph(res, null)
  } finally {
    loading.value = false
  }
}

function mergeGraph(res, centerUri) {
  const newNodes = res.nodes || []
  const newEdges = res.edges || []
  const existingIds = new Set(graphData.nodes.map(n => n.id))

  // 提取 rdf:type 信息用于着色
  extractNodeTypes(newEdges)

  const addedNodes = newNodes.filter(n => !existingIds.has(n.id))
  const w = graphCanvas.value?.width || 800
  const h = graphCanvas.value?.height || 600
  const cx = (w / 2 - transform.x) / transform.scale
  const cy = (h / 2 - transform.y) / transform.scale
  const total = addedNodes.length
  addedNodes.forEach((n, idx) => {
    graphData.nodes.push(n)
    if (n.isCenter) {
      nodePositions[n.id] = { x: cx, y: cy, vx: 0, vy: 0 }
    } else {
      const angle = (idx / Math.max(total - 1, 1)) * Math.PI * 2
      const radius = 160 + Math.random() * 60
      nodePositions[n.id] = {
        x: cx + Math.cos(angle) * radius,
        y: cy + Math.sin(angle) * radius,
        vx: 0, vy: 0,
      }
    }
  })

  const existingEdges = new Set(graphData.edges.map(e => e.source + '->' + e.target + '->' + e.predicate))
  newEdges.forEach(e => {
    const key = e.source + '->' + e.target + '->' + e.predicate
    if (!existingEdges.has(key)) graphData.edges.push(e)
  })

  if (!simulationRunning) runSimulation()
}

function clearGraph() {
  graphData.nodes = []
  graphData.edges = []
  nodePositions = {}
  simulationRunning = false
  highlightNodeId = null
  if (animFrame) cancelAnimationFrame(animFrame)
  drawFrame()
}

// ─── Force-Directed 布局 ─────────────────────────────
const REPULSION = 8000
const SPRING_LEN = 120
const SPRING_K = 0.05
const DAMPING = 0.88
const MIN_ENERGY = 0.01

function runSimulation() {
  simulationRunning = true
  let steps = 0
  const maxSteps = 400

  function step() {
    if (!simulationRunning || steps++ > maxSteps) {
      simulationRunning = false
      drawFrame()
      return
    }

    const nodes = graphData.nodes
    for (let i = 0; i < nodes.length; i++) {
      const a = nodePositions[nodes[i].id]
      if (!a) continue
      for (let j = i + 1; j < nodes.length; j++) {
        const b = nodePositions[nodes[j].id]
        if (!b) continue
        const dx = b.x - a.x
        const dy = b.y - a.y
        const dist = Math.sqrt(dx * dx + dy * dy) || 0.1
        const safeDist = Math.max(dist, 1)
        const force = REPULSION / (safeDist * safeDist)
        a.vx -= (dx / safeDist) * force
        a.vy -= (dy / safeDist) * force
        b.vx += (dx / safeDist) * force
        b.vy += (dy / safeDist) * force
      }
    }

    graphData.edges.forEach(e => {
      const a = nodePositions[e.source]
      const b = nodePositions[e.target]
      if (!a || !b) return
      const dx = b.x - a.x
      const dy = b.y - a.y
      const dist = Math.sqrt(dx * dx + dy * dy) || 1
      const stretch = dist - SPRING_LEN
      const force = stretch * SPRING_K
      a.vx += (dx / dist) * force
      a.vy += (dy / dist) * force
      b.vx -= (dx / dist) * force
      b.vy -= (dy / dist) * force
    })

    const MAX_SPEED = 20
    let totalEnergy = 0
    nodes.forEach(n => {
      const p = nodePositions[n.id]
      if (!p) return
      if (dragNode === n.id) return
      p.vx *= DAMPING
      p.vy *= DAMPING
      const speed = Math.sqrt(p.vx * p.vx + p.vy * p.vy)
      if (speed > MAX_SPEED) {
        p.vx = (p.vx / speed) * MAX_SPEED
        p.vy = (p.vy / speed) * MAX_SPEED
      }
      p.x += p.vx
      p.y += p.vy
      totalEnergy += Math.abs(p.vx) + Math.abs(p.vy)
    })

    drawFrame()
    if (steps > 10 && totalEnergy < MIN_ENERGY * nodes.length) {
      simulationRunning = false
      drawFrame()
      return
    }
    animFrame = requestAnimationFrame(step)
  }
  animFrame = requestAnimationFrame(step)
}

// ─── Canvas 绘制 ──────────────────────────────────────
function initCanvas() {
  const canvas = graphCanvas.value
  if (!canvas) return
  ctx = canvas.getContext('2d')
  onResize()
}

function onResize() {
  const canvas = graphCanvas.value
  if (!canvas) return
  const wrap = canvas.parentElement
  canvas.width = wrap.clientWidth
  canvas.height = wrap.clientHeight
  drawFrame()
  typeChart?.resize()
}

function drawFrame() {
  if (!ctx || !graphCanvas.value) return
  const W = graphCanvas.value.width
  const H = graphCanvas.value.height

  const bgFill = canvasBg.value === 'dark' ? '#0d1117' : '#eceff8'
  ctx.fillStyle = bgFill
  ctx.fillRect(0, 0, W, H)

  ctx.save()
  ctx.translate(transform.x, transform.y)
  ctx.scale(transform.scale, transform.scale)

  graphData.edges.forEach(e => {
    const a = nodePositions[e.source]
    const b = nodePositions[e.target]
    if (!a || !b) return
    drawEdge(a, b, e.label || '')
  })

  graphData.nodes.forEach(n => {
    const p = nodePositions[n.id]
    if (!p) return
    drawNode(p, n)
  })

  ctx.restore()
}

const NODE_R = 22

function drawNode(p, node) {
  const color = getNodeColor(node)
  const isCenter = node.isCenter
  const isHighlight = node.id === highlightNodeId
  const r = isCenter ? NODE_R + 4 : NODE_R

  // 高亮光晕
  if (isHighlight) {
    ctx.beginPath()
    ctx.arc(p.x, p.y, r + 8, 0, Math.PI * 2)
    ctx.fillStyle = 'rgba(255, 200, 50, 0.35)'
    ctx.fill()
  }

  ctx.beginPath()
  ctx.arc(p.x, p.y, r, 0, Math.PI * 2)
  ctx.fillStyle = color
  ctx.fill()

  if (isCenter || isHighlight) {
    ctx.strokeStyle = isHighlight
      ? 'rgba(255, 200, 50, 0.9)'
      : themeColor('#fff', '#ffffffcc')
    ctx.lineWidth = isHighlight ? 3 : 3
    ctx.stroke()
  }

  const label = (node.label || extractLabel(node.id)).substring(0, 10)
  ctx.fillStyle = '#fff'
  ctx.font = `bold ${isCenter ? 12 : 11}px sans-serif`
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(label, p.x, p.y)
}

function drawEdge(a, b, label) {
  const edgeColor = themeColor('rgba(150,150,180,0.55)', 'rgba(100,120,200,0.5)')
  const arrowColor = themeColor('rgba(150,150,180,0.7)', 'rgba(100,120,200,0.65)')
  const labelColor = themeColor('#888', '#8899cc')

  ctx.beginPath()
  ctx.moveTo(a.x, a.y)
  ctx.lineTo(b.x, b.y)
  ctx.strokeStyle = edgeColor
  ctx.lineWidth = 1.2
  ctx.stroke()

  const angle = Math.atan2(b.y - a.y, b.x - a.x)
  const arrowLen = 10
  const tx = b.x - Math.cos(angle) * (NODE_R + 4)
  const ty = b.y - Math.sin(angle) * (NODE_R + 4)
  ctx.beginPath()
  ctx.moveTo(tx, ty)
  ctx.lineTo(tx - arrowLen * Math.cos(angle - 0.4), ty - arrowLen * Math.sin(angle - 0.4))
  ctx.lineTo(tx - arrowLen * Math.cos(angle + 0.4), ty - arrowLen * Math.sin(angle + 0.4))
  ctx.closePath()
  ctx.fillStyle = arrowColor
  ctx.fill()

  if (transform.scale > 0.7 && label) {
    const mx = (a.x + b.x) / 2
    const my = (a.y + b.y) / 2
    const shortLabel = label.substring(0, 12)
    ctx.font = '10px sans-serif'
    ctx.fillStyle = labelColor
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(shortLabel, mx, my - 8)
  }
}

// ─── 鼠标交互 ─────────────────────────────────────────
function canvasToWorld(cx, cy) {
  return {
    x: (cx - transform.x) / transform.scale,
    y: (cy - transform.y) / transform.scale,
  }
}

function hitTest(wx, wy) {
  for (const n of graphData.nodes) {
    const p = nodePositions[n.id]
    if (!p) continue
    const dx = wx - p.x
    const dy = wy - p.y
    if (dx * dx + dy * dy <= (NODE_R + 6) * (NODE_R + 6)) return n
  }
  return null
}

let mouseDownNode = null
let mouseDragDist = 0
let mouseDownPos = { x: 0, y: 0 }

function onMouseDown(e) {
  if (e.button === 0) ctxMenu.visible = false
  const rect = graphCanvas.value.getBoundingClientRect()
  const cx = e.clientX - rect.left
  const cy = e.clientY - rect.top
  const w = canvasToWorld(cx, cy)
  const hit = hitTest(w.x, w.y)
  mouseDownNode = hit
  mouseDragDist = 0
  mouseDownPos = { x: cx, y: cy }
  if (hit) {
    dragNode = hit.id
  } else {
    isPanning = true
  }
  lastMouse = { x: cx, y: cy }
}

function onMouseMove(e) {
  const rect = graphCanvas.value.getBoundingClientRect()
  const cx = e.clientX - rect.left
  const cy = e.clientY - rect.top
  const dx = cx - lastMouse.x
  const dy = cy - lastMouse.y
  lastMouse = { x: cx, y: cy }
  mouseDragDist += Math.sqrt(dx * dx + dy * dy)

  if (dragNode) {
    const p = nodePositions[dragNode]
    if (p) {
      p.x += dx / transform.scale
      p.y += dy / transform.scale
      p.vx = 0; p.vy = 0
    }
    drawFrame()
  } else if (isPanning) {
    transform.x += dx
    transform.y += dy
    drawFrame()
  }
}

async function onMouseUp(e) {
  const wasDragging = mouseDragDist > 5
  const hitNode = mouseDownNode
  dragNode = null
  isPanning = false
  mouseDownNode = null

  if (e.button === 0 && !wasDragging) {
    if (hitNode && selectedDataset.value) {
      try {
        const res = await graphApi.nodeDetail(datasetPath(selectedDataset.value), hitNode.id)
        selectedNodeDetail.value = res
        drawerVisible.value = true
      } catch {}
    } else {
      drawerVisible.value = false
    }
  }
}

function onWheel(e) {
  e.preventDefault()
  const rect = graphCanvas.value.getBoundingClientRect()
  const cx = e.clientX - rect.left
  const cy = e.clientY - rect.top
  const factor = e.deltaY < 0 ? 1.1 : 0.9
  const newScale = Math.min(Math.max(transform.scale * factor, 0.1), 5)
  transform.x = cx - (cx - transform.x) * (newScale / transform.scale)
  transform.y = cy - (cy - transform.y) * (newScale / transform.scale)
  transform.scale = newScale
  drawFrame()
}

function onDblClick(e) {
  const rect = graphCanvas.value.getBoundingClientRect()
  const cx = e.clientX - rect.left
  const cy = e.clientY - rect.top
  const w = canvasToWorld(cx, cy)
  const hit = hitTest(w.x, w.y)
  if (hit) expandNode(hit.id)
}

function onRightClick(e) {
  const rect = graphCanvas.value.getBoundingClientRect()
  const cx = e.clientX - rect.left
  const cy = e.clientY - rect.top
  const w = canvasToWorld(cx, cy)
  const hit = hitTest(w.x, w.y)
  if (hit) {
    ctxMenu.x = cx
    ctxMenu.y = cy
    ctxMenu.nodeId = hit.id
    ctxMenu.visible = true
  } else {
    ctxMenu.visible = false
  }
}

function removeNode(nodeId) {
  ctxMenu.visible = false

  const toRemove = new Set([nodeId])

  function buildAdj(excludeIds) {
    const adj = {}
    for (const e of graphData.edges) {
      if (excludeIds.has(e.source) || excludeIds.has(e.target)) continue
      ;(adj[e.source] = adj[e.source] || new Set()).add(e.target)
      ;(adj[e.target] = adj[e.target] || new Set()).add(e.source)
    }
    return adj
  }

  let frontier = new Set([nodeId])
  const depth = Math.max(expandDepth.value, 1)
  for (let d = 0; d < depth; d++) {
    const adj = buildAdj(toRemove)
    const nextFrontier = new Set()
    for (const fid of frontier) {
      for (const e of graphData.edges) {
        let neighbor = null
        if (e.source === fid) neighbor = e.target
        else if (e.target === fid) neighbor = e.source
        if (!neighbor || toRemove.has(neighbor)) continue
        const neighbors = adj[neighbor]
        if (!neighbors || neighbors.size === 0) {
          toRemove.add(neighbor)
          nextFrontier.add(neighbor)
        }
      }
    }
    frontier = nextFrontier
    if (frontier.size === 0) break
  }

  graphData.nodes = graphData.nodes.filter(n => !toRemove.has(n.id))
  graphData.edges = graphData.edges.filter(e => !toRemove.has(e.source) && !toRemove.has(e.target))
  for (const id of toRemove) delete nodePositions[id]

  if (toRemove.has(selectedNodeDetail.value?.uri)) drawerVisible.value = false
  drawFrame()
}

// ─── 缩放工具栏 ───────────────────────────────────────
function zoomIn() {
  const W = graphCanvas.value?.width || 800
  const H = graphCanvas.value?.height || 600
  const cx = W / 2, cy = H / 2
  const newScale = Math.min(transform.scale * 1.25, 5)
  transform.x = cx - (cx - transform.x) * (newScale / transform.scale)
  transform.y = cy - (cy - transform.y) * (newScale / transform.scale)
  transform.scale = newScale
  drawFrame()
}

function zoomOut() {
  const W = graphCanvas.value?.width || 800
  const H = graphCanvas.value?.height || 600
  const cx = W / 2, cy = H / 2
  const newScale = Math.max(transform.scale * 0.8, 0.1)
  transform.x = cx - (cx - transform.x) * (newScale / transform.scale)
  transform.y = cy - (cy - transform.y) * (newScale / transform.scale)
  transform.scale = newScale
  drawFrame()
}

// ─── 工具按钮 ─────────────────────────────────────────
function resetGraph() {
  transform.x = 0
  transform.y = 0
  transform.scale = 1
  drawFrame()
}

function fitGraph() {
  if (!graphData.nodes.length) return
  const positions = Object.values(nodePositions)
  if (!positions.length) return
  const xs = positions.map(p => p.x)
  const ys = positions.map(p => p.y)
  const minX = Math.min(...xs), maxX = Math.max(...xs)
  const minY = Math.min(...ys), maxY = Math.max(...ys)
  const W = graphCanvas.value.width, H = graphCanvas.value.height
  const padding = 60
  const scaleX = (W - padding * 2) / (maxX - minX || 1)
  const scaleY = (H - padding * 2) / (maxY - minY || 1)
  transform.scale = Math.min(scaleX, scaleY, 2)
  transform.x = padding - minX * transform.scale + ((W - padding * 2) - (maxX - minX) * transform.scale) / 2
  transform.y = padding - minY * transform.scale + ((H - padding * 2) - (maxY - minY) * transform.scale) / 2
  drawFrame()
}

function formatNum(n) {
  if (n == null) return '-'
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K'
  return n
}

function extractLabel(uri) {
  if (!uri) return ''
  const hash = uri.lastIndexOf('#')
  if (hash >= 0) return uri.substring(hash + 1)
  const slash = uri.lastIndexOf('/')
  if (slash >= 0) return uri.substring(slash + 1)
  return uri
}
</script>

<style scoped>
.graph-view {
  display: flex;
  height: calc(100vh - 56px);
  overflow: hidden;
  position: relative;
}

/* 左侧控制面板 */
.control-panel {
  width: 240px;
  min-width: 240px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 0;
}
.panel-section {
  padding: 10px 0;
  border-bottom: 1px solid #f0f0f0;
}
.section-title {
  font-size: 12px;
  font-weight: 600;
  color: #909399;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 8px;
}
.stat-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 8px;
}
.stat-item {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 8px;
  text-align: center;
}
.stat-value {
  font-size: 18px;
  font-weight: 700;
  color: #409eff;
}
.stat-label {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}
.type-chart {
  height: 160px;
  margin-top: 4px;
}
.search-result {
  margin-top: 8px;
  max-height: 180px;
  overflow-y: auto;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
}
.search-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.15s;
}
.search-item:hover { background: #f0f7ff; }
.search-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

/* 展开深度刻度标签 */
.depth-labels {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}

/* 属性筛选 */
.prop-search-hint {
  font-size: 11px;
  color: #c0c4cc;
  margin-bottom: 6px;
}
.prop-result-item {
  align-items: flex-start !important;
  gap: 6px;
}
.prop-result-info {
  flex: 1;
  min-width: 0;
}
.prop-result-match {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.match-prop {
  color: #c0a060;
  margin-right: 3px;
  font-weight: 500;
}
.match-val {
  color: #409eff;
}
.prop-no-result {
  text-align: center;
  color: #c0c4cc;
  font-size: 12px;
  padding: 10px 0;
}

/* 节点类型图例 */
.legend-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: #606266;
}
.legend-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}
.legend-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 背景色块选择器 */
.bg-options {
  display: flex;
  gap: 8px;
}
.bg-swatch {
  flex: 1;
  height: 44px;
  border-radius: 6px;
  cursor: pointer;
  border: 2px solid transparent;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding-bottom: 4px;
  transition: border-color 0.2s, box-shadow 0.2s;
  user-select: none;
}
.bg-swatch:hover {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64,158,255,0.2);
}
.bg-swatch.active {
  border-color: #409eff;
  box-shadow: 0 0 0 3px rgba(64,158,255,0.3);
}
.bg-label {
  font-size: 11px;
  font-weight: 600;
  color: rgba(255,255,255,0.9);
  text-shadow: 0 1px 3px rgba(0,0,0,0.5);
  letter-spacing: 0.5px;
}

/* 画布区域 */
.graph-canvas-wrap {
  flex: 1;
  position: relative;
  overflow: hidden;
  transition: background 0.3s;
}
.graph-canvas-wrap.bg-light {
  background: radial-gradient(ellipse at center, #f8faff 0%, #eceff8 100%);
}
.graph-canvas-wrap.bg-dark {
  background: radial-gradient(ellipse at center, #1a1f2e 0%, #0d1117 100%);
}
.graph-canvas {
  display: block;
  cursor: grab;
  width: 100%;
  height: 100%;
}
.graph-canvas:active { cursor: grabbing; }
.empty-hint {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.loading-mask {
  position: absolute;
  inset: 0;
  background: rgba(255,255,255,0.7);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  font-size: 14px;
  color: #409eff;
  z-index: 10;
}
.bg-dark .loading-mask {
  background: rgba(13,17,23,0.75);
  color: #7eb8ff;
}

/* 缩放工具栏 */
.zoom-toolbar {
  position: absolute;
  bottom: 48px;
  left: 14px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  background: rgba(255,255,255,0.92);
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 4px;
  box-shadow: 0 2px 10px rgba(0,0,0,0.1);
  z-index: 100;
  user-select: none;
}
.bg-dark .zoom-toolbar {
  background: rgba(30,36,54,0.92);
  border-color: #3a4560;
  color: #c0c4cc;
}
.zoom-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 5px;
  cursor: pointer;
  font-size: 16px;
  transition: background 0.15s, color 0.15s;
  color: #606266;
}
.zoom-btn:hover {
  background: #ecf5ff;
  color: #409eff;
}
.bg-dark .zoom-btn:hover {
  background: rgba(64,158,255,0.2);
  color: #79bbff;
}
.zoom-scale {
  font-size: 11px;
  color: #909399;
  padding: 2px 0;
  min-width: 36px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

/* 右键上下文菜单 */
.ctx-menu {
  position: absolute;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  padding: 4px 0;
  min-width: 130px;
  z-index: 300;
  user-select: none;
}
.ctx-item {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 14px;
  font-size: 13px;
  color: #303133;
  cursor: pointer;
  transition: background 0.15s;
}
.ctx-item:hover {
  background: #f5f7fa;
}
.ctx-item-danger {
  color: #f56c6c;
}
.ctx-item-danger:hover {
  background: #fef0f0;
}

.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.graph-info {
  position: absolute;
  bottom: 12px;
  right: 12px;
  background: rgba(0,0,0,0.45);
  color: #fff;
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 12px;
}

/* 节点详情浮层面板 */
.detail-panel {
  position: fixed;
  top: 56px;
  right: 0;
  width: 320px;
  height: calc(100vh - 56px);
  background: #fff;
  box-shadow: -3px 0 16px rgba(0, 0, 0, 0.12);
  display: flex;
  flex-direction: column;
  z-index: 200;
  pointer-events: auto;
}
.detail-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px 12px;
  border-bottom: 1px solid #ebeef5;
  flex-shrink: 0;
}
.detail-panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.detail-close {
  cursor: pointer;
  font-size: 16px;
  color: #909399;
  padding: 4px;
  border-radius: 4px;
  transition: color 0.2s, background 0.2s;
}
.detail-close:hover {
  color: #f56c6c;
  background: #fef0f0;
}
.detail-panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px;
}
.detail-uri {
  font-size: 11px;
  color: #909399;
  word-break: break-all;
  background: #f5f7fa;
  padding: 6px 8px;
  border-radius: 4px;
}
.detail-label {
  font-size: 18px;
  font-weight: 700;
  color: #303133;
  margin: 8px 0 12px;
}
.prop-list { display: flex; flex-direction: column; gap: 6px; }
.prop-item {
  display: flex;
  gap: 8px;
  font-size: 13px;
  border-bottom: 1px solid #f0f0f0;
  padding-bottom: 6px;
}
.prop-key {
  color: #606266;
  min-width: 90px;
  max-width: 90px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 0;
}
.prop-val {
  color: #303133;
  flex: 1;
  word-break: break-all;
}

/* 面板滑入/滑出动画 */
.detail-slide-enter-active,
.detail-slide-leave-active {
  transition: transform 0.25s ease, opacity 0.25s ease;
}
.detail-slide-enter-from,
.detail-slide-leave-to {
  transform: translateX(100%);
  opacity: 0;
}
</style>

