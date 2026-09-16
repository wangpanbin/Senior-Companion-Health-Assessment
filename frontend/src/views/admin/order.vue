<script setup>
/**
 * W-05 订单管理（管理员视角）
 */
import { ref } from 'vue'
import { NlCard, NlStatusChip } from '@/components'

const filter = ref({ status: '', date: '' })

const list = ref([
  { id: 'OD20250916001', elder: '张大爷', hospital: '市第一人民医院', companion: '李师傅', fee: 120, status: 'IN_SERVICE', createdAt: '09-15 17:30' },
  { id: 'OD20250916002', elder: '王奶奶', hospital: '市中医院',       companion: '张师傅', fee: 100, status: 'PENDING',    createdAt: '09-16 08:30' },
  { id: 'OD20250915003', elder: '陈大爷', hospital: '市第一人民医院', companion: '王师傅', fee: 120, status: 'COMPLETED',  createdAt: '09-15 09:00' },
  { id: 'OD20250914004', elder: '赵奶奶', hospital: '市妇幼保健院',   companion: '陈师傅', fee: 150, status: 'REVIEWED',   createdAt: '09-14 09:30' },
  { id: 'OD20250913005', elder: '李大爷', hospital: '社区卫生服务中心', companion: '赵师傅', fee: 80, status: 'CANCELLED', createdAt: '09-13 14:00' }
])
</script>

<template>
  <NlCard title="订单管理" plain>
    <section class="filters">
      <el-select v-model="filter.status" placeholder="状态" clearable style="width: 140px">
        <el-option label="待接单" value="PENDING" />
        <el-option label="已接单" value="ACCEPTED" />
        <el-option label="服务中" value="IN_SERVICE" />
        <el-option label="已完成" value="COMPLETED" />
        <el-option label="已评价" value="REVIEWED" />
        <el-option label="已取消" value="CANCELLED" />
      </el-select>
      <el-date-picker v-model="filter.date" type="daterange" range-separator="-" start-placeholder="开始" end-placeholder="结束" />
      <el-input placeholder="搜索订单号 / 就诊人" clearable style="width: 240px" />
      <el-button type="primary" plain>查询</el-button>
    </section>

    <el-table :data="list">
      <el-table-column prop="id" label="订单号" width="180" />
      <el-table-column prop="elder" label="就诊人" width="100" />
      <el-table-column prop="hospital" label="医院" min-width="180" />
      <el-table-column prop="companion" label="陪诊员" width="100" />
      <el-table-column label="服务费" width="100">
        <template #default="{ row }">
          <span class="is-num">¥{{ row.fee }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <NlStatusChip :status="row.status" />
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="160" />
      <el-table-column label="操作" min-width="120" fixed="right">
        <template #default>
          <el-button text size="small" type="primary">查看</el-button>
        </template>
      </el-table-column>
    </el-table>
  </NlCard>
</template>

<style scoped lang="scss">
.filters {
  display: flex;
  gap: var(--nl-space-3);
  margin-bottom: var(--nl-space-4);
}
</style>
