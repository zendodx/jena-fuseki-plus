import {defineStore} from 'pinia'
import {ref} from 'vue'

/**
 * 跨页面共享状态：SPARQL 查询页 → 图谱页
 * 当用户在 SPARQL 页点击"在图谱中查看"时，将查询和数据集存入此 store，
 * 图谱页通过 onActivated 钩子检测到有待执行的任务后自动执行。
 */
export const useGraphStore = defineStore('graph', () => {
  // 待在图谱页执行的 SPARQL（null 表示无待执行任务）
  const pendingSparql = ref(null)   // { dataset: string, sparql: string }

  function setPendingSparql(dataset, sparql) {
    pendingSparql.value = { dataset, sparql }
  }

  function consumePendingSparql() {
    const val = pendingSparql.value
    pendingSparql.value = null
    return val
  }

  return { pendingSparql, setPendingSparql, consumePendingSparql }
})

