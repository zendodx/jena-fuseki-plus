<template>
  <el-container class="app-container">
    <!-- 侧边栏 -->
    <el-aside :width="collapsed ? '64px' : '220px'" class="sidebar">
      <div class="logo" :class="{ collapsed }">
        <el-icon size="24" color="#409eff"><Share /></el-icon>
        <span v-if="!collapsed" class="logo-text">知识图谱管理台</span>
      </div>
      <el-menu
        :default-active="$route.path"
        router
        :collapse="collapsed"
        background-color="#1e2a3a"
        text-color="#c0c4cc"
        active-text-color="#409eff"
        class="side-menu"
      >
        <el-menu-item index="/graph">
          <el-icon><Share /></el-icon>
          <template #title>知识图谱</template>
        </el-menu-item>
        <el-menu-item index="/sparql">
          <el-icon><Search /></el-icon>
          <template #title>SPARQL 查询</template>
        </el-menu-item>
        <el-menu-item index="/datasets">
          <el-icon><DataBoard /></el-icon>
          <template #title>数据集管理</template>
        </el-menu-item>
        <!-- 官方管理台：不走路由，直接跳转到 Fuseki 原生 UI -->
        <el-menu-item index="__admin__" @click="openAdminConsole">
          <el-icon>
            <Monitor />
          </el-icon>
          <template #title>
            <span>官方管理台</span>
            <el-icon v-if="!collapsed" class="external-icon"><TopRight /></el-icon>
          </template>
        </el-menu-item>
      </el-menu>
      <div class="collapse-btn" @click="collapsed = !collapsed">
        <el-icon><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
      </div>
    </el-aside>

    <!-- 主内容区 -->
    <el-container>
      <el-header class="app-header">
        <span class="header-title">{{ currentTitle }}</span>
        <div class="header-right">
          <el-tag type="success" size="small">Fuseki 4.10.0</el-tag>
          <el-tag type="info" size="small" style="margin-left:8px">API :3040</el-tag>
          <el-tag type="warning" size="small" style="margin-left:8px">SPARQL :3030</el-tag>
        </div>
      </el-header>
      <el-main class="app-main">
        <router-view v-slot="{ Component }">
          <keep-alive>
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import {computed, ref} from 'vue'
import {useRoute} from 'vue-router'
import {ElMessage} from 'element-plus'
import {fusekiApi} from '@/api/index.js'

const collapsed = ref(false)
const route = useRoute()

const titleMap = {
  '/graph': '知识图谱可视化',
  '/sparql': 'SPARQL 查询编辑器',
  '/datasets': '数据集管理',
}
const currentTitle = computed(() => titleMap[route.path] || '知识图谱管理台')

/** 点击「官方管理台」：调后端接口拿 Fuseki URL，新标签打开 */
async function openAdminConsole() {
  try {
    const res = await fusekiApi.adminUrl()
    if (!res.running) {
      ElMessage.warning('Fuseki 服务尚未就绪，请稍候再试')
      return
    }
    window.open(res.url, '_blank')
  } catch (e) {
    ElMessage.error('获取管理台地址失败：' + (e.message || '未知错误'))
  }
}
</script>

<style scoped>
.app-container {
  height: 100vh;
  overflow: hidden;
}
.sidebar {
  background: #1e2a3a;
  display: flex;
  flex-direction: column;
  transition: width 0.25s;
  overflow: hidden;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  gap: 10px;
  border-bottom: 1px solid #2d3f54;
  white-space: nowrap;
  overflow: hidden;
}
.logo.collapsed {
  padding: 0;
  justify-content: center;
}
.logo-text {
  color: #e5eaf3;
  font-size: 15px;
  font-weight: 600;
}
.side-menu {
  flex: 1;
  border-right: none;
  overflow-y: auto;
  overflow-x: hidden;
}
.collapse-btn {
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  cursor: pointer;
  border-top: 1px solid #2d3f54;
  transition: color 0.2s;
}
.collapse-btn:hover { color: #409eff; }
.external-icon {
  font-size: 11px;
  margin-left: 4px;
  opacity: 0.6;
  vertical-align: middle;
}
.app-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 56px;
  box-shadow: 0 1px 4px rgba(0,0,0,.08);
}
.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}
.app-main {
  padding: 0;
  overflow: hidden;
  background: #f0f2f5;
}
</style>
