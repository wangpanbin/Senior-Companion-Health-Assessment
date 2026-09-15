<script setup>
import { ref, onMounted } from 'vue'
import { getHealth } from '@/api/health'

/**
 * 首页 / 骨架自检页。
 *
 * 作用：打通「前端 → Vite 代理 → 后端 → 统一响应 → 前端拆包」整条链路。
 * 如果这里能看到后端返回的数据，说明 M0 骨架已经跑通。
 */
const health = ref(null)
const healthError = ref('')
const loading = ref(false)

const modules = [
  { code: 'M0', name: '工程骨架与开发规范', status: 'done' },
  { code: 'M1', name: '数据库设计与数据初始化', status: 'todo' },
  { code: 'M2', name: '认证与多角色鉴权', status: 'todo' },
  { code: 'M3', name: '用户与档案管理', status: 'todo' },
  { code: 'M4', name: '陪诊订单与状态机', status: 'todo' },
  { code: 'M5', name: '陪诊执行与打卡', status: 'todo' },
  { code: 'M6', name: '用药管理与漏服提醒', status: 'todo' },
  { code: 'M7', name: '评价与投诉', status: 'todo' },
  { code: 'M8', name: '站内信与通知', status: 'todo' },
  { code: 'M9', name: '管理后台', status: 'todo' },
  { code: 'M10', name: '数据统计与导出', status: 'todo' },
  { code: 'M11', name: '适老化前端体系', status: 'doing' },
  { code: 'M12', name: '测试与质量保障', status: 'todo' },
  { code: 'M13', name: '部署与交付物', status: 'todo' }
]

const statusMeta = {
  done: { text: '已完成', type: 'success' },
  doing: { text: '进行中', type: 'warning' },
  todo: { text: '待开始', type: 'info' }
}

async function checkHealth() {
  loading.value = true
  healthError.value = ''
  try {
    // 注意：拦截器已把 { code, message, data } 拆包，这里拿到的就是 data
    health.value = await getHealth()
  } catch (e) {
    health.value = null
    healthError.value = e?.message || '后端未启动或代理未生效'
  } finally {
    loading.value = false
  }
}

onMounted(checkHealth)
</script>

<template>
  <div class="nl-page dashboard">
    <h2 class="nl-page__title">项目骨架自检</h2>

    <!-- ==================== 链路连通性 ==================== -->
    <el-card shadow="never" class="dashboard__block">
      <template #header>
        <div class="dashboard__header">
          <span>前后端链路连通性</span>
          <el-button text type="primary" :loading="loading" @click="checkHealth">重新检测</el-button>
        </div>
      </template>

      <el-skeleton v-if="loading" :rows="3" animated />

      <el-alert
        v-else-if="healthError"
        type="warning"
        :closable="false"
        show-icon
        title="后端未连通"
        :description="`${healthError}。请确认后端已启动（mvn spring-boot:run，端口 8080）。`"
      />

      <el-descriptions v-else :column="2" border size="small">
        <el-descriptions-item label="服务名">{{ health?.application }}</el-descriptions-item>
        <el-descriptions-item label="运行环境">{{ health?.profiles }}</el-descriptions-item>
        <el-descriptions-item label="JDK">{{ health?.javaVersion }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag type="success" effect="dark">{{ health?.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="服务器时间">{{ health?.serverTime }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- ==================== 模块进度 ==================== -->
    <el-card shadow="never" class="dashboard__block">
      <template #header>模块进度（对照 plan.md）</template>
      <div class="dashboard__modules">
        <div v-for="m in modules" :key="m.code" class="dashboard__module">
          <el-tag :type="statusMeta[m.status].type" effect="plain" size="small">{{ m.code }}</el-tag>
          <span class="dashboard__module-name">{{ m.name }}</span>
          <el-tag :type="statusMeta[m.status].type" size="small">{{ statusMeta[m.status].text }}</el-tag>
        </div>
      </div>
    </el-card>

    <!-- ==================== 骨架清单 ==================== -->
    <el-card shadow="never" class="dashboard__block">
      <template #header>骨架已就绪的基础设施</template>
      <el-row :gutter="16">
        <el-col :span="12">
          <div class="dashboard__sub">后端</div>
          <ul class="dashboard__list">
            <li>统一响应 <code>Result</code> / 分页 <code>PageResult</code></li>
            <li>全局异常处理器（参数校验、鉴权、404、兜底）</li>
            <li>业务异常 + 9 段错误码（1xxx–9xxx）</li>
            <li>MyBatis-Plus：分页 / 乐观锁 / 防全表更新</li>
            <li>Spring Security 无状态骨架 + BCrypt 密码器</li>
            <li>Knife4j 接口文档（按 9 个模块分组）</li>
            <li>Logback 分级日志 + 敏感信息脱敏工具</li>
            <li>Flyway 就绪（M1 建库后开启）</li>
          </ul>
        </el-col>
        <el-col :span="12">
          <div class="dashboard__sub">前端</div>
          <ul class="dashboard__list">
            <li>Vue 3 + Vite + Element Plus（中文语言包）</li>
            <li>Vue Router 4 + 多角色守卫（守卫逻辑已写好，待 M2 开启）</li>
            <li>Pinia：登录态 / 适老化模式</li>
            <li>Axios 封装：token 携带、401 刷新重放、统一提示</li>
            <li>适老化主题：一键切换老人模式（CSS 变量驱动）</li>
            <li>主布局：侧边菜单 + 顶栏 + 老人模式开关</li>
            <li>9 个模块的接口封装与占位页</li>
          </ul>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.dashboard {
  &__block {
    margin-bottom: $space-base;
    border-radius: $radius-base;
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__modules {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: 10px;
  }

  &__module {
    display: flex;
    gap: 8px;
    align-items: center;
    padding: 8px 12px;
    background: #fafbfc;
    border-radius: $radius-sm;
  }

  &__module-name {
    flex: 1;
    font-size: 14px;
  }

  &__sub {
    margin-bottom: 8px;
    font-weight: 600;
  }

  &__list {
    margin: 0;
    padding-left: 1.3em;
    font-size: 14px;
    line-height: 1.9;
    color: var(--nl-text-regular);
  }
}

code {
  padding: 1px 5px;
  font-family: Consolas, Monaco, monospace;
  background: rgb(31 39 51 / 6%);
  border-radius: 4px;
}
</style>
