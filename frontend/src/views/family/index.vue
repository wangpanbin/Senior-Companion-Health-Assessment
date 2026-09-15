<script setup>
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'

/**
 * 家属工作台。
 *
 * 这是全站**唯一的写操作入口**：下单、代老人操作、查看进度、管理用药。
 * 家属是真正操作这个系统的人，UI 复杂度可以正常，但仍需保持清晰。
 */
</script>

<template>
  <div class="nl-page">
    <h2 class="nl-page__title">家属工作台</h2>

    <el-tabs model-value="order">
      <el-tab-pane label="陪诊订单" name="order">
        <ModulePlaceholder
          module="M4"
          title="陪诊订单与状态机"
          owner="B（后端主力）+ A（前端主力）"
          iteration="迭代二 · W5–W7（W7 竞讲交付 v1.0）"
          depends-on="M3 用户与档案"
          api-doc="docs/api/03-order.md"
          :features="[
            '下单：选择老人、医院、科室、就诊时间、文字地址、备注',
            '订单列表：多条件筛选 + 分页',
            '订单详情：状态流转时间线（M5 补充实时进度）',
            '订单取消（仅「待接单」状态允许）',
            '线下记账：服务完成后生成费用明细（不做在线支付）'
          ]"
          :acceptance="[
            'JMeter 50 并发抢同一单 → 只有 1 条成功接单记录，version 仅 +1',
            '跳级调用「待接单 → 已完成」返回 3002，数据库状态不变',
            '非订单归属陪诊员操作 → 403',
            '分页条数与 SELECT COUNT(*) 手工核对一致',
            '状态变更接口异常时事务回滚，状态与流转日志保持一致'
          ]"
        />
      </el-tab-pane>

      <el-tab-pane label="就诊进度" name="progress">
        <ModulePlaceholder
          module="M5"
          title="陪诊执行与实时进度"
          owner="A（前端）+ B（后端）"
          iteration="迭代三 · W8–W9"
          depends-on="M4 陪诊订单"
          api-doc="docs/api/04-companion-execution.md"
          :features="[
            '进度时间线：出发 / 到院 / 就诊中 / 取药 / 离院 / 完成',
            'WebSocket 或 SSE 实时推送，无需手动刷新',
            '断线自动重连，重连后补齐未读事件',
            '打卡现场照片、取药凭证查看'
          ]"
          :acceptance="[
            '打卡落库后 companion_order_checkin 与 companion_track 同时新增且 orderId 一致',
            '家属端不刷新页面，3 秒内收到进度推送',
            '断网 30 秒恢复后自动重连，不丢事件、不重复渲染',
            '时间线顺序与 checkin_time 升序一致'
          ]"
        />
      </el-tab-pane>

      <el-tab-pane label="用药管理" name="medication">
        <ModulePlaceholder
          module="M6"
          title="用药管理与漏服提醒"
          owner="D（建表）+ B（定时任务）+ A（日历页）"
          iteration="迭代三 · W9–W10"
          depends-on="M1 数据库、M8 站内信"
          api-doc="docs/api/05-medication.md"
          :features="[
            '用药计划：药品、剂量、频次、起止日期、饭前饭后',
            '服药日历：按月 / 周展示，状态为 待服 / 已服 / 漏服',
            '每天 07:00 定时生成当日服药任务',
            '每 30 分钟扫描漏服并推送家属',
            'Redis 分布式锁保证定时任务不重复执行'
          ]"
          :acceptance="[
            '手动触发定时任务两次，medication_task 不出现重复行',
            '任务时间设为过去时间，扫描后自动变「漏服」并推送到位',
            '日历页结果与 SELECT * FROM medication_task 逐条一致',
            '并发启动 2 个后端实例，定时任务仅 1 个实际执行',
            '全站搜索「建议服用」「推荐剂量」结果为空，药品页有免责声明'
          ]"
        />
      </el-tab-pane>

      <el-tab-pane label="我的老人" name="elder">
        <ModulePlaceholder
          module="M3"
          title="用户与档案管理"
          owner="B（后端）+ D（数据库）"
          iteration="W2–W5、W13"
          depends-on="M1 数据库、M2 认证"
          api-doc="docs/api/02-elder-family.md"
          :features="[
            '老人档案：姓名、性别、年龄、病史备注、紧急联系人、常去医院',
            '家属绑定老人（邀请码 / 手机号验证），一个家属可绑定多位老人',
            '身份证加密存储 + 接口返回脱敏',
            '代老人操作：以家属身份触发，但记录实际操作人'
          ]"
          :acceptance="[
            '家属绑定 1 位老人后，可在下单页选择该老人',
            '老人档案身份证字段在数据库中为密文，接口返回为脱敏串',
            '绑定关系不存在的老人返回 2005',
            '无权操作他人绑定的老人返回 2006'
          ]"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
