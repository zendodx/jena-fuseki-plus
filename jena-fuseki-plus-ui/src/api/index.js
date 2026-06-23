import axios from 'axios'
import {ElMessage} from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

http.interceptors.response.use(
  res => res.data,
  err => {
    const msg = err.response?.data?.error || err.message || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

// ---- 图谱接口 ----
export const graphApi = {
  /** 邻居查询：展开某节点的关系 */
  neighbors(dataset, uri, depth = 1, limit = 100) {
    return http.get('/graph/neighbors', { params: { dataset, uri, depth, limit } })
  },
  /** 节点详情：某 URI 的所有属性 */
  nodeDetail(dataset, uri) {
    return http.get('/graph/node', { params: { dataset, uri } })
  },
  /** 关键词搜索节点 */
  search(dataset, keyword, limit = 20) {
    return http.get('/graph/search', { params: { dataset, keyword, limit } })
  },
  /** 自定义 SPARQL → 图结构 */
  sparqlToGraph(dataset, sparql) {
    return http.post('/graph/sparql', { dataset, sparql })
  },
  /** 数据集概览统计 */
  overview(dataset) {
    return http.get('/graph/overview', { params: { dataset } })
  },
  /**
   * 按指定属性（谓词）+属性值关键词搜索节点
   * @param {string} dataset  数据集路径，如 /travel_kg_v4
   * @param {string} predicate 谓词 URI 或简写片段（留空则搜所有属性）
   * @param {string} value    属性值关键词
   * @param {number} limit    最多返回条数
   */
  searchByProp(dataset, predicate, value, limit = 20) {
    return http.get('/graph/search-by-prop', { params: { dataset, predicate, value, limit } })
  },
  /** 获取数据集中所有 distinct 谓词（供编辑器自动补全） */
  predicates(dataset, limit = 200) {
    return http.get('/graph/predicates', { params: { dataset, limit } })
  },
}

// ---- 数据集接口 ----
export const fusekiApi = {
  datasets() {
    return http.get('/fuseki/datasets')
  },
  health() {
    return http.get('/fuseki/health')
  },
  /** 获取 Fuseki 官方管理台 URL */
  adminUrl() {
    return http.get('/fuseki/admin-url')
  },
}

// ---- SPARQL 执行（通过后端代理转发给 Fuseki，避免跨域） ----
export async function executeSparql(datasetPath, sparql) {
  // 统一走 /api/fuseki/sparql，由 Spring Boot 代理转发给 Fuseki (3030)
  return http.post('/fuseki/sparql', { dataset: datasetPath, query: sparql })
}

