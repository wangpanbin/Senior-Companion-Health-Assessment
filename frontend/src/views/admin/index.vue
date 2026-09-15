<script setup>
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'

/**
 * 管理后台。
 *
 * 所有管理动作必须写 admin_oper_log，做到「谁 / 何时 / 对谁 / 做了什么」可追溯。
 */
</script>

<template>
  <div class="nl-page">
    <h2 class="nl-page__title">管理后台</h2>

    <el-tabs model-value="stat">
      <el-tab-pane label="数据看板" name="stat">
        <ModulePlaceholder
          module="M10"
          title="数据统计、可视化与导出"
          owner="A（ECharts 看板）+ B（统计与导出接口）"
          iteration="迭代五 · W14–W15"
          depends-on="M4 订单、M9 管理后台"
          api-doc="docs/api/09-statistics-export.md"
          :features="[
            '统计接口：订单量、完成率、用户增长（日 / 周 / 月）',
            '陪诊员接单排行、漏服率',
            'ECharts 看板：柱状图、饼图、折线图 + 时间区间切换',
            'EasyExcel 导出订单 / 用户数据（按当前筛选条件）',
            '统计查询索引优化，1 万条数据下响应 < 2s'
          ]"
          :acceptance="[
            '看板「订单总量」与 SELECT COUNT(*) 手工核对一致',
            '「完成率」= 已完成 / 总订单，与 SQL 计算值一致（保留 2 位小数）',
            '切换时间区间后数据随之变化，区间外数据不混入',
            '导出 xlsx 可打开，行数 = 当前筛选条件记录数，无乱码',
            '无数据时显示空状态而非报错'
          ]"
        />
      </el-tab-pane>

      <el-tab-pane label="资质审核" name="audit">
        <ModulePlaceholder
          module="M9"
          title="陪诊员资质审核"
          owner="A（前端）+ B（后端）"
          iteration="迭代四 · W13 启动，迭代五 W14 完成"
          depends-on="M3 用户与档案、M4 陪诊订单、M7 评价与投诉"
          api-doc="docs/api/08-admin.md"
          :features="[
            '待审核列表 / 已通过 / 已驳回三种状态及数量',
            '查看证件材料（图片预览）',
            '通过 / 驳回（驳回必填原因）',
            '审核结果自动生成站内信发送给陪诊员'
          ]"
          :acceptance="[
            '三种状态的列表与数量与数据库一致',
            '驳回未填原因返回 8003',
            '审核动作在 admin_oper_log 留下记录（操作人、目标、动作、时间）',
            '驳回原因在陪诊员端可见'
          ]"
        />
      </el-tab-pane>

      <el-tab-pane label="用户管理" name="user">
        <ModulePlaceholder
          module="M9"
          title="用户管理"
          owner="A（前端）+ B（后端）"
          iteration="迭代四 · W13"
          depends-on="M3 用户与档案"
          api-doc="docs/api/08-admin.md"
          :features="[
            '用户查询：按角色 / 手机号 / 状态筛选 + 分页',
            '封禁 / 解封（封禁后已签发 token 立即失效）',
            '重置密码',
            '不能封禁管理员账号'
          ]"
          :acceptance="[
            '封禁用户后任意接口请求立即返回 403，无需等待 token 过期',
            '封禁管理员返回 8002',
            '每次封禁 / 解封 / 重置密码都写入 admin_oper_log',
            '手机号在列表中脱敏展示'
          ]"
        />
      </el-tab-pane>

      <el-tab-pane label="订单与纠纷" name="order">
        <ModulePlaceholder
          module="M9"
          title="订单管理与纠纷处理"
          owner="A（前端）+ B（后端）"
          iteration="迭代五 · W14"
          depends-on="M4 陪诊订单、M7 评价与投诉"
          api-doc="docs/api/08-admin.md"
          :features="[
            '全部订单查询：按状态 / 时间 / 双方用户筛选 + 分页',
            '订单详情：完整状态流转日志与打卡记录',
            '纠纷处理：强制把订单置为终态（已完成 / 已取消）+ 处理备注',
            '处理结果给双方各发一条站内信'
          ]"
          :acceptance="[
            '仅有 ADMIN 可调用纠纷处理接口，其他角色返回 403',
            '强制改终态后可查 admin_oper_log 完整记录',
            '处理完成后家属与陪诊员各收到一条站内信',
            '已被强制终态化的订单不能再进行正向流转'
          ]"
        />
      </el-tab-pane>

      <el-tab-pane label="操作日志" name="log">
        <ModulePlaceholder
          module="M9"
          title="管理员操作日志"
          owner="D（建表）+ B（后端）+ A（前端）"
          iteration="迭代四 · W13"
          depends-on="M9 其他子模块"
          api-doc="docs/api/08-admin.md"
          :features="[
            '记录字段：操作人、操作类型、目标对象、操作时间、备注',
            '按操作人 / 操作类型 / 时间区间检索',
            '日志只增不改不删'
          ]"
          :acceptance="[
            '每一次审核 / 封禁 / 纠纷处理都必然新增一条日志',
            '按时间区间 + 操作类型筛选结果正确',
            '日志表中不存在 UPDATE / DELETE 语句（代码层面无对应接口）'
          ]"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
