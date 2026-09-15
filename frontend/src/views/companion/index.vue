<script setup>
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'

/**
 * 陪诊员工作台。
 *
 * 关键约束：陪诊员必须**资质审核通过**才能看到订单大厅并接单，前后端双重拦截。
 */
</script>

<template>
  <div class="nl-page">
    <h2 class="nl-page__title">陪诊员工作台</h2>

    <el-tabs model-value="hall">
      <el-tab-pane label="订单大厅" name="hall">
        <ModulePlaceholder
          module="M4"
          title="接单（乐观锁防超卖）"
          owner="B（后端主力）+ A（前端主力）"
          iteration="迭代二 · W5–W7"
          depends-on="M2 认证、M3 陪诊员资质"
          api-doc="docs/api/03-order.md"
          :features="[
            '待接单订单列表：按区域 / 就诊时间筛选 + 分页',
            '接单按钮：乐观锁 @Version 保证同一订单只能一人接到',
            '我的订单：已接单 / 服务中列表',
            '拒单（需填写原因）'
          ]"
          :acceptance="[
            'JMeter 50 并发抢同一单 → 数据库仅 1 条成功接单原记录，抢失败者收到 3003',
            '未审核陪诊员调用接单接口返回 2003 / 403',
            '审核通过后同一账号立即可以接单，无需重新登录',
            '订单列表分页与条件筛选结果与 SQL 手工核对一致'
          ]"
        />
      </el-tab-pane>

      <el-tab-pane label="陪诊打卡" name="checkin">
        <ModulePlaceholder
          module="M5"
          title="陪诊打卡与轨迹"
          owner="A（前端）+ B（后端）"
          iteration="迭代三 · W8–W9"
          depends-on="M4 陪诊订单"
          api-doc="docs/api/04-companion-execution.md"
          :features="[
            '打卡节点：出发 / 到院 / 就诊中 / 取药 / 离院 / 完成',
            'GPS 定位（一期用模拟坐标，不做地图导航）',
            '距离校验：偏离订单地址超过阈值判为异常打卡',
            '现场照片上传（可选）',
            '每次打卡写入轨迹表，供家属端回放'
          ]"
          :acceptance="[
            '打卡坐标超出阈值返回 4001，记录不写入或标记异常',
            '同一陪诊员对同一订单重复提交同一节点返回 4002 并去重',
            '打卡成功同时写入 order_checkin 与 companion_track',
            '非该订单陪诊员打卡返回 4003'
          ]"
        />
      </el-tab-pane>

      <el-tab-pane label="我的资质" name="qualification">
        <ModulePlaceholder
          module="M3"
          title="陪诊员资质与审核"
          owner="B（后端）+ D（数据库）"
          iteration="W2–W5、W13"
          depends-on="M1 数据库、M2 认证"
          api-doc="docs/api/02-elder-family.md"
          :features="[
            '资料提交：身份信息、服务区域、可服务时段、资质证件上传',
            '审核状态展示：待审核 / 已通过 / 已驳回（含驳回原因）',
            '审核结果通过站内信通知（M8）',
            '通过后自动解锁接单权限'
          ]"
          :acceptance="[
            '提交资料后状态为「待审核」，此时不能接单',
            '管理员驳回时必填原因，驳回理由可在陪诊员端看到',
            '审核通过后收到站内信通知',
            '审核状态非法流转返回 8001'
          ]"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
