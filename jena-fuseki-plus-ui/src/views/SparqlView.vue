<template>
  <div class="sparql-view">
    <!-- 顶部工具栏 -->
    <div class="toolbar">
      <el-select v-model="selectedDataset" placeholder="选择数据集" style="width:200px"
        @change="onDatasetChange">
        <el-option v-for="ds in datasets" :key="ds.name" :label="ds.name" :value="ds.name" />
      </el-select>
      <el-button type="primary" @click="runQuery" :loading="loading" :icon="VideoPlay">
        执行查询
      </el-button>
      <el-button v-if="loading" type="danger" plain @click="cancelQuery">取消</el-button>
      <el-button @click="formatQuery" :icon="Edit">格式化</el-button>
      <el-button @click="copyAsSingleLine" :icon="CopyDocument" title="复制为单行（去除换行）">复制单行</el-button>
      <el-button @click="clearQuery" :icon="Delete">清空</el-button>
      <el-divider direction="vertical" />
      <!-- 历史记录下拉 -->
      <el-dropdown v-if="queryHistory.length" @command="loadFromHistory" trigger="click">
        <el-button :icon="Clock">历史 <el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
        <template #dropdown>
          <el-dropdown-menu style="max-width:480px">
            <el-dropdown-item
              v-for="(h, i) in queryHistory"
              :key="i"
              :command="h"
              style="font-size:12px;max-width:480px"
            >
              <div class="history-item">
                <span class="history-ds">{{ h.dataset }}</span>
                <span class="history-preview">{{ h.preview }}</span>
                <span class="history-time">{{ h.time }}</span>
              </div>
            </el-dropdown-item>
            <el-dropdown-item divided command="__clear__" style="color:#f56c6c;font-size:12px">清空历史</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-divider direction="vertical" />
      <span class="hint">Ctrl+Enter 执行 · Ctrl+Space 补全 · Tab 缩进 · 双击示例加载</span>
    </div>

    <div class="editor-result" ref="editorResultRef">
      <!-- 左侧编辑器 + 示例 -->
      <div class="editor-wrap" :style="{ width: editorWidth + 'px' }">
        <!-- CodeMirror 编辑器挂载点 -->
        <div class="editor-area">
          <div ref="cmContainer" class="cm-container"></div>
          <div v-if="queryError" class="error-bar">
            <el-icon><WarningFilled /></el-icon> {{ queryError }}
          </div>
        </div>

        <!-- 示例查询 -->
        <div class="examples-panel">
          <div class="examples-title">示例查询</div>
          <div v-for="ex in examples" :key="ex.name"
            class="example-item" @dblclick="loadExample(ex)">
            <div class="ex-name">{{ ex.name }}</div>
            <div class="ex-desc">{{ ex.desc }}</div>
          </div>
        </div>
      </div>

      <!-- 拖拽分割条 -->
      <div class="resizer" @mousedown="startResize"
        :class="{ 'resizer-active': isResizing }"
        title="拖拽调整编辑器宽度">
        <div class="resizer-handle"></div>
      </div>

      <!-- 右侧结果 -->
      <div class="result-wrap">
        <div class="result-header" v-if="queryResult">
          <span class="result-count">
            共 <b>{{ totalCount }}</b> 条结果
            <span v-if="totalCount > PAGE_SIZE" class="page-info">
              · 第 {{ currentPage }}/{{ totalPages }} 页
            </span>
          </span>
          <!-- 大结果集警告 -->
          <el-tag
            v-if="isTruncated"
            type="warning"
            size="small"
            style="margin-left:4px"
            :title="`结果超过 ${WARN_THRESHOLD} 条，建议查询时加 LIMIT 限制返回数量`"
          >⚠ 结果过多，建议加 LIMIT</el-tag>
          <div style="flex:1"></div>
          <el-button size="small" @click="exportCSV" :icon="Download">导出 CSV</el-button>
          <el-button size="small" @click="viewAsGraph" :icon="Share" type="primary" plain>
            在图谱中查看
          </el-button>
        </div>

        <!-- 结果表格 -->
        <div class="result-table-wrap" v-if="queryResult?.results?.bindings?.length">
          <el-table
            :data="pagedTableData"
            border
            stripe
            height="100%"
            size="small"
            style="width:100%"
          >
            <el-table-column
              v-for="col in tableColumns"
              :key="col"
              :prop="col"
              :label="col"
              min-width="160"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <span
                  :class="{ 'uri-cell': isUri(row[col]) }"
                  @click="isUri(row[col]) && copyUri(row[col])"
                  :title="row[col]"
                >{{ row[col] }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- ASK 结果 -->
        <div class="ask-result" v-else-if="queryResult?.boolean !== undefined">
          <el-result
            :icon="queryResult.boolean ? 'success' : 'error'"
            :title="queryResult.boolean ? 'TRUE' : 'FALSE'"
          />
        </div>

        <!-- 空状态 -->
        <div class="result-empty" v-else>
          <el-empty description="执行查询后显示结果" :image-size="100" />
        </div>

        <!-- 分页控件 -->
        <div class="result-pagination" v-if="totalPages > 1">
          <el-pagination
            v-model:current-page="currentPage"
            :page-size="PAGE_SIZE"
            :total="totalCount"
            :page-sizes="[50, 100, 200, 500]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="onPageSizeChange"
            small
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {executeSparql, fusekiApi, graphApi} from '@/api/index.js'
import {ElMessage} from 'element-plus'
import {
  ArrowDown,
  Clock,
  CopyDocument,
  Delete,
  Download,
  Edit,
  Share,
  VideoPlay,
  WarningFilled
} from '@element-plus/icons-vue'
import {useGraphStore} from '@/stores/graphStore.js'

// ── CodeMirror 6 ──────────────────────────────────────────────────────────
import {EditorState} from '@codemirror/state'
import {EditorView, highlightActiveLine, keymap, lineNumbers} from '@codemirror/view'
import {defaultKeymap, history, historyKeymap, indentWithTab} from '@codemirror/commands'
import {acceptCompletion, autocompletion, completionKeymap} from '@codemirror/autocomplete'
import {HighlightStyle, StreamLanguage, syntaxHighlighting} from '@codemirror/language'
import {tags} from '@lezer/highlight'
// sparql 流式语言模式
import {sparql as sparqlLang} from '@codemirror/legacy-modes/mode/sparql'

// 大结果集阈值：超过此条数展示警告
const WARN_THRESHOLD = 500
// 分页大小默认值
const PAGE_SIZE_DEFAULT = 100

// ── SPARQL 自定义语法高亮主题 ──────────────────────────────────────────────
const sparqlHighlightStyle = HighlightStyle.define([
  // 关键词：SELECT WHERE FILTER 等 → 蓝色加粗
  { tag: tags.keyword,          color: '#0070c1', fontWeight: 'bold' },
  // 内置函数：STR LANG COUNT 等 → 紫色
  { tag: tags.standard(tags.name), color: '#7a29a1' },
  { tag: tags.function(tags.name), color: '#7a29a1' },
  // 变量 ?s ?p → 橙色
  { tag: tags.variableName,     color: '#e07b00' },
  // URI <http://...> 和前缀 → 青绿色
  { tag: tags.atom,             color: '#008080' },
  { tag: tags.namespace,        color: '#008080' },
  // 字符串 → 绿色
  { tag: tags.string,           color: '#22863a' },
  // 注释 → 灰色斜体
  { tag: tags.comment,          color: '#6a737d', fontStyle: 'italic' },
  // 运算符
  { tag: tags.operator,         color: '#d73a49' },
  // 数字
  { tag: tags.number,           color: '#005cc5' },
  // 元信息（@ 语言标签等）
  { tag: tags.meta,             color: '#e36209' },
  // 括号
  { tag: tags.bracket,          color: '#24292e' },
])

// ─── 拖拽调整编辑器宽度 ────────────────────────────────────────────────────
const editorWidth = ref(560)   // 默认宽度 px
const isResizing = ref(false)
const editorResultRef = ref(null)
let resizeStartX = 0
let resizeStartWidth = 0

function startResize(e) {
  isResizing.value = true
  resizeStartX = e.clientX
  resizeStartWidth = editorWidth.value
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  document.addEventListener('mousemove', onResize)
  document.addEventListener('mouseup', stopResize)
  e.preventDefault()
}

function onResize(e) {
  if (!isResizing.value) return
  const container = editorResultRef.value
  const maxWidth = container ? container.offsetWidth - 200 : 1200
  const minWidth = 280
  const delta = e.clientX - resizeStartX
  editorWidth.value = Math.min(maxWidth, Math.max(minWidth, resizeStartWidth + delta))
}

function stopResize() {
  isResizing.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  document.removeEventListener('mousemove', onResize)
  document.removeEventListener('mouseup', stopResize)
}

const datasets = ref([])
const selectedDataset = ref('')
const sparqlText = ref(`SELECT ?s ?p ?o
WHERE {
  ?s ?p ?o
}
LIMIT 20`)
const loading = ref(false)
const queryResult = ref(null)
const queryError = ref('')
const cmContainer = ref(null)

// 谓词缓存（按数据集）
const predicateCache = ref([])

let editorView = null  // CodeMirror 实例

// ─── SPARQL 关键词 ─────────────────────────────────────────────────────────
const SPARQL_KEYWORDS = [
  'SELECT', 'DISTINCT', 'REDUCED', 'WHERE', 'FILTER', 'OPTIONAL', 'UNION', 'MINUS',
  'GRAPH', 'NAMED', 'SERVICE', 'BIND', 'AS', 'VALUES', 'LET',
  'GROUP BY', 'ORDER BY', 'HAVING', 'LIMIT', 'OFFSET',
  'PREFIX', 'BASE', 'ASK', 'CONSTRUCT', 'DESCRIBE',
  'INSERT', 'DELETE', 'WITH', 'LOAD', 'CLEAR', 'DROP', 'CREATE', 'COPY', 'MOVE', 'ADD',
  'FROM', 'INTO', 'USING',
  'NOT', 'IN', 'EXISTS', 'NOT EXISTS',
  'TRUE', 'FALSE',
  // 函数
  'STR', 'LANG', 'LANGMATCHES', 'DATATYPE', 'BOUND', 'IRI', 'URI', 'BNODE',
  'RAND', 'ABS', 'CEIL', 'FLOOR', 'ROUND', 'CONCAT', 'STRLEN', 'UCASE', 'LCASE',
  'ENCODE_FOR_URI', 'CONTAINS', 'STRSTARTS', 'STRENDS', 'STRBEFORE', 'STRAFTER',
  'YEAR', 'MONTH', 'DAY', 'HOURS', 'MINUTES', 'SECONDS', 'TIMEZONE', 'TZ', 'NOW',
  'MD5', 'SHA1', 'SHA256', 'SHA384', 'SHA512',
  'COALESCE', 'IF', 'STRLANG', 'STRDT', 'SAMETERM', 'ISIRI', 'ISURI', 'ISBLANK', 'ISLITERAL', 'ISNUMERIC',
  'REGEX', 'SUBSTR', 'REPLACE',
  'COUNT', 'SUM', 'MIN', 'MAX', 'AVG', 'SAMPLE', 'GROUP_CONCAT',
  'isURI', 'isBlank', 'isLiteral', 'isNumeric',
]

// 常用前缀
const PREFIXES = [
  { label: 'rdf:', apply: '<http://www.w3.org/1999/02/22-rdf-syntax-ns#', info: 'RDF 命名空间' },
  { label: 'rdfs:', apply: '<http://www.w3.org/2000/01/rdf-schema#', info: 'RDFS 命名空间' },
  { label: 'owl:', apply: '<http://www.w3.org/2002/07/owl#', info: 'OWL 命名空间' },
  { label: 'xsd:', apply: '<http://www.w3.org/2001/XMLSchema#', info: 'XSD 数据类型' },
  { label: 'skos:', apply: '<http://www.w3.org/2004/02/skos/core#', info: 'SKOS 命名空间' },
  { label: 'dc:', apply: '<http://purl.org/dc/elements/1.1/', info: 'Dublin Core' },
  { label: 'foaf:', apply: '<http://xmlns.com/foaf/0.1/', info: 'FOAF 命名空间' },
  { label: 'schema:', apply: '<http://schema.org/', info: 'Schema.org' },
]

// rdf:type / rdfs:label 等常用属性快捷补全
const COMMON_PROPS = [
  { label: 'rdf:type', apply: '<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>' },
  { label: 'rdfs:label', apply: '<http://www.w3.org/2000/01/rdf-schema#label>' },
  { label: 'rdfs:comment', apply: '<http://www.w3.org/2000/01/rdf-schema#comment>' },
  { label: 'rdfs:subClassOf', apply: '<http://www.w3.org/2000/01/rdf-schema#subClassOf>' },
  { label: 'owl:sameAs', apply: '<http://www.w3.org/2002/07/owl#sameAs>' },
]

// ─── 自动补全函数 ──────────────────────────────────────────────────────────
function sparqlCompletionSource(context) {
  // 获取当前 token（单词 / < 开头的 URI / 变量）
  const word = context.matchBefore(/[<\w?:][^\s,;{}\[\]()]*/)
  if (!word && !context.explicit) return null
  const token = word ? word.text : ''
  const from = word ? word.from : context.pos

  const options = []

  // 1. SPARQL 关键词
  const kwLower = token.toUpperCase()
  for (const kw of SPARQL_KEYWORDS) {
    if (kw.startsWith(kwLower) || kwLower === '') {
      options.push({ label: kw, type: 'keyword', boost: 3 })
    }
  }

  // 2. 前缀
  for (const p of PREFIXES) {
    if (p.label.startsWith(token) || token === '') {
      options.push({ label: p.label, apply: p.apply, type: 'namespace', detail: p.info, boost: 2 })
    }
  }

  // 3. 常用属性
  for (const prop of COMMON_PROPS) {
    if (prop.label.includes(token) || token.startsWith('<')) {
      options.push({ label: prop.label, apply: prop.apply, type: 'property', boost: 2 })
    }
  }

  // 4. 数据集谓词（动态）
  for (const pred of predicateCache.value) {
    const shortLabel = pred.replace(/^.*[#/]/, '')  // 取 URI 片段
    if (!token || pred.includes(token) || shortLabel.toLowerCase().includes(token.toLowerCase())) {
      options.push({
        label: shortLabel,
        apply: `<${pred}>`,
        detail: pred,
        type: 'property',
        boost: 1,
      })
    }
  }

  // 5. 变量补全（扫描当前文档中已有的 ?xxx 变量名）
  if (token.startsWith('?')) {
    const docText = editorView?.state.doc.toString() || ''
    const varNames = new Set([...docText.matchAll(/\?(\w+)/g)].map(m => m[1]))
    for (const v of varNames) {
      const full = '?' + v
      if (full.startsWith(token)) {
        options.push({ label: full, type: 'variable', boost: 4 })
      }
    }
  }

  return { from, options, validFor: /^[<\w?:][^\s]*$/ }
}

// ─── 初始化 CodeMirror ─────────────────────────────────────────────────────
function initEditor() {
  if (editorView) {
    editorView.destroy()
    editorView = null
  }

  const startState = EditorState.create({
    doc: sparqlText.value,
    extensions: [
      lineNumbers(),
      highlightActiveLine(),
      history(),
      StreamLanguage.define(sparqlLang),
      syntaxHighlighting(sparqlHighlightStyle, { fallback: true }),
      autocompletion({
        override: [sparqlCompletionSource],
        activateOnTyping: true,
        maxRenderedOptions: 30,
      }),
      keymap.of([
        ...defaultKeymap,
        ...historyKeymap,
        ...completionKeymap,
        indentWithTab,
        {
          key: 'Ctrl-Enter',
          mac: 'Cmd-Enter',
          run: () => { runQuery(); return true },
        },
        {
          key: 'Tab',
          run: acceptCompletion,
        },
      ]),
      EditorView.updateListener.of(update => {
        if (update.docChanged) {
          sparqlText.value = update.state.doc.toString()
        }
      }),
      EditorView.theme({
        '&': { height: '100%', fontSize: '13px' },
        '.cm-scroller': { overflow: 'auto', fontFamily: "'Fira Code','JetBrains Mono','Consolas',monospace", lineHeight: '1.6' },
        '.cm-content': { padding: '12px 0' },
        '.cm-line': { padding: '0 14px' },
        '.cm-gutters': { background: '#f5f5f5', borderRight: '1px solid #e0e0e0', color: '#999' },
        '.cm-activeLine': { background: '#f0f7ff' },
        '.cm-tooltip.cm-tooltip-autocomplete': { zIndex: 999 },
        '.cm-tooltip.cm-tooltip-autocomplete > ul > li': { padding: '3px 10px' },
        '.cm-tooltip.cm-tooltip-autocomplete > ul > li[aria-selected]': { background: '#409eff', color: '#fff' },
      }),
    ],
  })

  editorView = new EditorView({
    state: startState,
    parent: cmContainer.value,
  })
}

// ─── 查询历史（localStorage） ─────────────────────────────────────────────
const HISTORY_KEY = 'sparql_query_history'
const HISTORY_MAX = 20
const queryHistory = ref([])

function loadHistory() {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    queryHistory.value = raw ? JSON.parse(raw) : []
  } catch {
    queryHistory.value = []
  }
}

function saveToHistory(dataset, sparql) {
  const preview = sparql.replace(/\s+/g, ' ').trim().substring(0, 60)
  const now = new Date()
  const time = `${now.getMonth() + 1}/${now.getDate()} ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
  const item = { dataset, sparql, preview, time }
  // 去重（相同 dataset+sparql 只保留最新）
  queryHistory.value = queryHistory.value.filter(
    h => !(h.dataset === dataset && h.sparql === sparql)
  )
  queryHistory.value.unshift(item)
  if (queryHistory.value.length > HISTORY_MAX) queryHistory.value = queryHistory.value.slice(0, HISTORY_MAX)
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(queryHistory.value))
  } catch {}
}

function loadFromHistory(cmd) {
  if (cmd === '__clear__') {
    queryHistory.value = []
    localStorage.removeItem(HISTORY_KEY)
    ElMessage.success('历史记录已清空')
    return
  }
  // 恢复数据集选择
  if (cmd.dataset) {
    const ds = datasets.value.find(d => d.name === cmd.dataset)
    if (ds) selectedDataset.value = ds.name
  }
  // 恢复查询内容
  sparqlText.value = cmd.sparql
  if (editorView) {
    editorView.dispatch({
      changes: { from: 0, to: editorView.state.doc.length, insert: cmd.sparql }
    })
  }
  ElMessage.success({ message: '已恢复历史查询', duration: 1200 })
}

// ─── 复制为单行 ────────────────────────────────────────────────────────────
function copyAsSingleLine() {
  if (!sparqlText.value.trim()) {
    ElMessage.warning('查询内容为空')
    return
  }
  const singleLine = sparqlText.value
    .replace(/\r?\n/g, ' ')   // 换行 → 空格
    .replace(/\t/g, ' ')       // Tab → 空格
    .replace(/ {2,}/g, ' ')    // 多空格 → 单空格
    .trim()
  navigator.clipboard?.writeText(singleLine).then(() => {
    ElMessage.success({ message: '已复制为单行', duration: 1500 })
  }).catch(() => {
    ElMessage.error('复制失败，请手动复制')
  })
}

// ─── 生命周期 ──────────────────────────────────────────────────────────────
onMounted(async () => {
  try {
    const res = await fusekiApi.datasets()
    datasets.value = res.datasets || []
    if (datasets.value.length) selectedDataset.value = datasets.value[0].name
  } catch {
    datasets.value = [{ name: 'travel_kg_v4', path: '/travel_kg_v4' }]
    selectedDataset.value = 'travel_kg_v4'
  }
  initEditor()
  // 初始加载谓词
  if (selectedDataset.value) loadPredicates()
  // 加载本地历史记录
  loadHistory()
})

onBeforeUnmount(() => {
  editorView?.destroy()
  document.removeEventListener('mousemove', onResize)
  document.removeEventListener('mouseup', stopResize)
})

// 切换数据集时刷新谓词
function onDatasetChange() {
  loadPredicates()
}

async function loadPredicates() {
  if (!selectedDataset.value) return
  try {
    const ds = datasets.value.find(d => d.name === selectedDataset.value)
    const datasetPath = ds?.path || ('/' + selectedDataset.value)
    const res = await graphApi.predicates(datasetPath)
    predicateCache.value = res.predicates || []
  } catch {
    predicateCache.value = []
  }
}

// ─── 执行查询 ──────────────────────────────────────────────────────────────
async function runQuery() {
  if (!sparqlText.value.trim()) return
  if (!selectedDataset.value) {
    ElMessage.warning('请先选择数据集')
    return
  }
  // 如有正在进行的查询，先取消
  if (abortController) abortController.abort()
  abortController = new AbortController()

  loading.value = true
  queryError.value = ''
  queryResult.value = null
  currentPage.value = 1
  try {
    const ds = datasets.value.find(d => d.name === selectedDataset.value)
    const datasetPath = ds?.path || ('/' + selectedDataset.value)
    const result = await executeSparql(datasetPath, sparqlText.value, abortController.signal)
    queryResult.value = result
    // 大结果集提示
    const count = result?.results?.bindings?.length || 0
    if (count > WARN_THRESHOLD) {
      ElMessage.warning({
        message: `返回 ${count} 条结果，建议在查询中加入 LIMIT 限制返回数量`,
        duration: 4000,
      })
    }
    // 执行成功后保存到历史
    saveToHistory(selectedDataset.value, sparqlText.value)
  } catch (e) {
    // AbortError 是用户主动取消，不显示错误
    if (e?.name === 'AbortError' || e?.code === 'ERR_CANCELED') return
    queryError.value = e.response?.data || e.message || '查询失败'
  } finally {
    abortController = null
    loading.value = false
  }
}

// ─── 格式化 ───────────────────────────────────────────────────────────────
// 对 SPARQL 查询字符串进行格式化：关键词大写、合理缩进、换行对齐
function formatQuery() {
  const raw = sparqlText.value.trim()
  if (!raw) return

  try {
    const formatted = sparqlFormat(raw)
    sparqlText.value = formatted
    if (editorView) {
      editorView.dispatch({
        changes: { from: 0, to: editorView.state.doc.length, insert: formatted }
      })
    }
    ElMessage.success({ message: '格式化完成', duration: 1000 })
  } catch {
    ElMessage.warning('格式化失败，请检查查询语法')
  }
}

/**
 * 简单但实用的 SPARQL 格式化器
 * - 关键词统一大写
 * - 顶级子句（SELECT/WHERE/PREFIX/LIMIT 等）顶格
 * - { } 内部语句缩进 2 个空格
 * - 多余空白折叠
 */
function sparqlFormat(query) {
  // ── Step 1: 归一化空白（保留换行语义先压平） ──────────────────────────
  // 先把所有换行变为空格，再整理
  let q = query
    .replace(/\r\n|\r/g, '\n')
    .replace(/[ \t]+/g, ' ')   // 多空格→单空格（不动换行）
    .trim()

  // ── Step 2: 关键词大写 ────────────────────────────────────────────────
  // 仅对边界单词替换，避免动到字符串/URI 内部（简单处理：URI 和字符串先占位）
  const placeholders = []
  // 占位 URI <...>
  q = q.replace(/<[^>]*>/g, (m) => { placeholders.push(m); return `\x00URI${placeholders.length - 1}\x00` })
  // 占位字符串 "..." '...'
  q = q.replace(/"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/g, (m) => { placeholders.push(m); return `\x00STR${placeholders.length - 1}\x00` })
  // 占位注释 #...
  q = q.replace(/#[^\n]*/g, (m) => { placeholders.push(m); return `\x00CMT${placeholders.length - 1}\x00` })

  // 关键词列表（注意多词关键词先处理）
  const KWS = [
    'NOT EXISTS', 'GROUP CONCAT', 'GROUP BY', 'ORDER BY', 'NOT IN',
    'SELECT', 'DISTINCT', 'REDUCED', 'WHERE', 'FILTER', 'OPTIONAL', 'UNION', 'MINUS',
    'GRAPH', 'NAMED', 'SERVICE', 'BIND', 'AS', 'VALUES', 'LET',
    'HAVING', 'LIMIT', 'OFFSET',
    'PREFIX', 'BASE', 'ASK', 'CONSTRUCT', 'DESCRIBE',
    'INSERT DATA', 'DELETE DATA', 'INSERT', 'DELETE', 'WITH', 'LOAD', 'CLEAR',
    'DROP', 'CREATE', 'COPY', 'MOVE', 'ADD', 'FROM', 'INTO', 'USING',
    'NOT', 'IN', 'EXISTS',
    'TRUE', 'FALSE', 'UNDEF',
    'COUNT', 'SUM', 'MIN', 'MAX', 'AVG', 'SAMPLE',
  ]
  for (const kw of KWS) {
    // \b 边界（多词关键词中间有空格用 \s+）
    const pat = kw.replace(/ /g, '\\s+')
    q = q.replace(new RegExp('(?<![\x00\\w])' + pat + '(?![\\w\x00])', 'gi'), kw)
  }

  // ── Step 3: 换行规则 ──────────────────────────────────────────────────
  // 先把所有换行压平成空格
  q = q.replace(/\n/g, ' ').replace(/ {2,}/g, ' ')

  // 顶级关键词前加换行：在"空格 + 顶级关键词"处替换为"\n顶级关键词"
  // 注意多词关键词（GROUP BY / ORDER BY 等）先处理，避免被单词拆散
  const TOP_KW = [
    'INSERT DATA', 'DELETE DATA', 'GROUP BY', 'ORDER BY',
    'PREFIX', 'BASE', 'SELECT', 'ASK', 'CONSTRUCT', 'DESCRIBE',
    'INSERT', 'DELETE', 'WITH',
    'LOAD', 'CLEAR', 'DROP', 'CREATE', 'COPY', 'MOVE', 'ADD',
    'WHERE', 'FROM', 'NAMED', 'HAVING', 'LIMIT', 'OFFSET', 'VALUES',
  ]
  for (const kw of TOP_KW) {
    // 将字符串中所有 " KEYWORD"（前有空格，后跟空格或{或行尾）替换为 "\nKEYWORD"
    // 用 replace + 全局匹配，不依赖 lookbehind
    const pat = kw.replace(/ /g, '\\s+')
    q = q.replace(new RegExp(' (' + pat + ')(?=[ \\t{]|$)', 'gi'), '\n$1')
  }
  // 去掉可能产生的行首多余空格
  q = q.split('\n').map(l => l.trimStart()).join('\n')

  // ── Step 4: 花括号展开 ────────────────────────────────────────────────
  // { 后换行，} 前换行
  q = q
    .replace(/\{\s*/g, '{\n')
    .replace(/\s*\}/g, '\n}')

  // ── Step 5: 缩进 ─────────────────────────────────────────────────────
  const INDENT = '  '
  let depth = 0
  const lines = q.split('\n').map(line => line.trim()).filter(l => l.length > 0)
  const result = []
  for (const line of lines) {
    if (line === '}') depth = Math.max(0, depth - 1)
    result.push(INDENT.repeat(depth) + line)
    if (line.endsWith('{')) depth++
  }

  // ── Step 6: 点号分隔三元组换行 ────────────────────────────────────────
  // depth=1 内部的 . 分隔符后换行（简单：在 . 后（不跟<>）加换行）
  let out = result.join('\n')
  // 在 triple 末尾的 . 后换行（不影响 URI 和小数）
  out = out.replace(/(?<=[^.<>\d]) \. (?=[^}])/g, ' .\n' + INDENT.repeat(1))

  // ── Step 7: 还原占位符 ────────────────────────────────────────────────
  out = out.replace(/\x00(URI|STR|CMT)(\d+)\x00/g, (_, type, idx) => placeholders[parseInt(idx)])

  // ── Step 8: 清理多余空行 ─────────────────────────────────────────────
  out = out.replace(/\n{3,}/g, '\n\n').trim()

  return out
}

function clearQuery() {
  sparqlText.value = ''
  if (editorView) {
    editorView.dispatch({
      changes: { from: 0, to: editorView.state.doc.length, insert: '' }
    })
  }
}

function loadExample(ex) {
  sparqlText.value = ex.sparql
  if (editorView) {
    editorView.dispatch({
      changes: { from: 0, to: editorView.state.doc.length, insert: ex.sparql }
    })
  }
}

// ─── 查询取消（AbortController） ──────────────────────────────────────────────────
let abortController = null

function cancelQuery() {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  loading.value = false
  ElMessage.info({ message: '查询已取消', duration: 1500 })
}

// ─── 结果处理（分页） ──────────────────────────────────────────────────
const PAGE_SIZE = ref(PAGE_SIZE_DEFAULT)
const currentPage = ref(1)

const tableColumns = computed(() => {
  if (!queryResult.value?.head?.vars) return []
  return queryResult.value.head.vars
})

// 全量扁平数据（不分页，用于导出 CSV）
const tableData = computed(() => {
  const bindings = queryResult.value?.results?.bindings || []
  return bindings.map(row => {
    const flat = {}
    tableColumns.value.forEach(col => {
      flat[col] = row[col]?.value ?? ''
    })
    return flat
  })
})

const totalCount = computed(() => tableData.value.length)
const totalPages = computed(() => Math.ceil(totalCount.value / PAGE_SIZE.value))
const isTruncated = computed(() => totalCount.value > WARN_THRESHOLD)

// 当前页切片数据（传给 el-table）
const pagedTableData = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE.value
  return tableData.value.slice(start, start + PAGE_SIZE.value)
})

function onPageSizeChange(size) {
  PAGE_SIZE.value = size
  currentPage.value = 1
}

function isUri(val) {
  return typeof val === 'string' && (val.startsWith('http://') || val.startsWith('https://'))
}

function copyUri(uri) {
  navigator.clipboard?.writeText(uri)
  ElMessage.success({ message: 'URI 已复制', duration: 1500 })
}

function exportCSV() {
  if (!tableData.value.length) return
  const cols = tableColumns.value
  const rows = [cols.join(','), ...tableData.value.map(r => cols.map(c => `"${(r[c] || '').replace(/"/g, '""')}"`).join(','))]
  const blob = new Blob([rows.join('\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = 'sparql-result.csv'; a.click()
  URL.revokeObjectURL(url)
}

const router = useRouter()
const graphStore = useGraphStore()

function viewAsGraph() {
  if (!sparqlText.value.trim()) {
    ElMessage.warning('查询内容为空')
    return
  }
  if (!selectedDataset.value) {
    ElMessage.warning('请先选择数据集')
    return
  }
  const ds = datasets.value.find(d => d.name === selectedDataset.value)
  const datasetPath = ds?.path || ('/' + selectedDataset.value)
  graphStore.setPendingSparql(datasetPath, sparqlText.value)
  router.push('/graph')
  ElMessage.success('已切换到图谱页，正在渲染结果...')
}

// 示例查询
const examples = [
  {
    name: '查询所有三元组',
    desc: '列出前20条三元组',
    sparql: 'SELECT ?s ?p ?o\nWHERE { ?s ?p ?o }\nLIMIT 20',
  },
  {
    name: '查询实体类型',
    desc: '统计各类型实体数量',
    sparql: `SELECT ?type (COUNT(?s) AS ?count)
WHERE {
  ?s <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?type
}
GROUP BY ?type
ORDER BY DESC(?count)
LIMIT 20`,
  },
  {
    name: '查询指定属性',
    desc: '查找有 label 的实体',
    sparql: `SELECT ?s ?label
WHERE {
  ?s <http://www.w3.org/2000/01/rdf-schema#label> ?label
}
LIMIT 30`,
  },
  {
    name: '路径查询',
    desc: '两跳关系路径',
    sparql: `SELECT ?a ?p1 ?b ?p2 ?c
WHERE {
  ?a ?p1 ?b .
  ?b ?p2 ?c .
  FILTER(?a != ?c)
}
LIMIT 20`,
  },
  {
    name: '关键词搜索',
    desc: '模糊匹配 label',
    sparql: `SELECT ?s ?label
WHERE {
  ?s <http://www.w3.org/2000/01/rdf-schema#label> ?label
  FILTER(CONTAINS(LCASE(STR(?label)), "北京"))
}
LIMIT 20`,
  },
  {
    name: 'ASK 存在判断',
    desc: '判断是否存在某类节点',
    sparql: `ASK {
  ?s <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?type
}`,
  },
]
</script>

<style scoped>
.sparql-view {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 56px);
  overflow: hidden;
}
.toolbar {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  padding: 10px 16px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.hint { font-size: 12px; color: #aaa; }

/* 历史记录下拉项 */
.history-item {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 440px;
  overflow: hidden;
}
.history-ds {
  font-size: 11px;
  font-weight: 600;
  color: #409eff;
  background: #ecf5ff;
  border-radius: 3px;
  padding: 1px 5px;
  white-space: nowrap;
  flex-shrink: 0;
}
.history-preview {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #303133;
  font-family: monospace;
  font-size: 12px;
}
.history-time {
  font-size: 11px;
  color: #c0c4cc;
  flex-shrink: 0;
  margin-left: 4px;
}
.editor-result {
  display: flex;
  flex: 1;
  overflow: hidden;
}
.editor-wrap {
  /* width 由 JS 控制，min/max 在 onResize 中限制 */
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  /* 不设 border-right，由 resizer 代替 */
}

/* 拖拽分割条 */
.resizer {
  width: 6px;
  flex-shrink: 0;
  cursor: col-resize;
  background: #e4e7ed;
  position: relative;
  transition: background 0.15s;
  user-select: none;
}
.resizer:hover,
.resizer-active {
  background: #409eff;
}
.resizer-handle {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 2px;
  height: 32px;
  border-radius: 2px;
  background: rgba(255,255,255,0.7);
  pointer-events: none;
}
.editor-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
/* CodeMirror 容器 */
.cm-container {
  flex: 1;
  overflow: hidden;
  background: #fafafa;
}
/* 让 CodeMirror 内部填满容器 */
.cm-container :deep(.cm-editor) {
  height: 100%;
}
.cm-container :deep(.cm-scroller) {
  height: 100%;
}
.error-bar {
  padding: 8px 14px;
  background: #fef0f0;
  color: #f56c6c;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
  border-top: 1px solid #fde;
  flex-shrink: 0;
}
.examples-panel {
  border-top: 1px solid #f0f0f0;
  max-height: 220px;
  overflow-y: auto;
  padding: 8px;
  background: #fff;
  flex-shrink: 0;
}
.examples-title {
  font-size: 11px;
  color: #999;
  font-weight: 600;
  padding: 4px 4px 6px;
  text-transform: uppercase;
}
.example-item {
  padding: 7px 8px;
  border-radius: 4px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.15s;
}
.example-item:hover { background: #f0f7ff; border-color: #c6e2ff; }
.ex-name { font-size: 13px; font-weight: 500; color: #303133; }
.ex-desc { font-size: 11px; color: #909399; margin-top: 2px; }

.result-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
}
.result-header {
  padding: 10px 16px;
  border-bottom: 1px solid #f0f0f0;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.result-count { font-size: 13px; color: #606266; flex-shrink: 0; }
.page-info { color: #909399; margin-left: 4px; }
.result-table-wrap {
  flex: 1;
  overflow: hidden;
  min-height: 0;
}
.result-pagination {
  padding: 8px 16px;
  border-top: 1px solid #f0f0f0;
  background: #fff;
  flex-shrink: 0;
  display: flex;
  justify-content: flex-end;
}
.uri-cell {
  color: #409eff;
  cursor: pointer;
  font-size: 12px;
}
.uri-cell:hover { text-decoration: underline; }
.ask-result, .result-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>

