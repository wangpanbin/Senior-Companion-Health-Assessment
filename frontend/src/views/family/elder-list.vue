<script setup>
/**
 * 我的老人（M-19 列表 · design.md §1.2 P1）
 *
 * 数据来源：
 *   - `GET /user/elder` 列表（`listElder`，分页 page/size ≤ 100）→ 我绑定的老人
 *   - `GET /user/elder/{id}` 详情（`getElder`）→ 点开卡片看完整档案（含脱敏身份证/地址/病史）
 *   - `DELETE /user/elder/{id}` 删除（`removeElder`）→ 逻辑删除档案
 *   - `DELETE /user/elder/{id}/bind` 解绑（`unbindElder`）→ 解除当前家属与老人的绑定
 *
 * ⚠️ 列表 VO（`ElderVO.ofList`）口径：姓名/手机号已脱敏，且**不含** relation / address /
 *    病史 / idCard / mobilityLevel —— 这些只在 `getElder` 详情返回。所以列表卡只展示
 *    列表级字段（姓名、性别、年龄、脱敏手机、常去医院、绑定状态），关系与详细档案放详情弹窗。
 *
 * 删除 / 解绑是破坏性操作，必须二次确认；后端返回的业务错误（如存在进行中订单返回 409）
 * 由拦截器弹出，这里不再重复弹。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  NlPhoneShell, NlAvatar, NlEmpty, NlSkeleton, NlStatusChip
} from '@/components'
import { listElder, getElder, removeElder, unbindElder } from '@/api/user'
import { formatDate } from '@/utils/format'

const router = useRouter()

const elders = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const pageSize = 50

/* -------- 详情弹窗 -------- */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const isEmpty = computed(() => !loading.value && !elders.value.length)

async function loadElders() {
  loading.value = true
  try {
    const data = await listElder({ page: page.value, size: pageSize })
    elders.value = data?.records || []
    total.value = data?.total || 0
  } catch {
    elders.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function openDetail(id) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getElder(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function unbind(e) {
  try {
    await ElMessageBox.confirm(
      `确定解除与「${e.name}」的绑定关系吗？解绑后您将无法直接代其预约陪诊或管理用药。`,
      '解绑确认',
      { type: 'warning', confirmButtonText: '确定解绑', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  try {
    await unbindElder(e.id)
    ElMessage.success('已解绑')
    loadElders()
  } catch {
    // 业务错误（如存在进行中订单）已由拦截器弹出
  }
}

async function remove(e) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${e.name}」的档案吗？该操作不可恢复，但其历史订单与用药记录仍会保留。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await removeElder(e.id)
    ElMessage.success('已删除档案')
    loadElders()
  } catch {
    // 业务错误（如存在进行中订单返回 409）已由拦截器弹出
  }
}

function bind() {
  router.push('/family/elder/bind')
}

onMounted(loadElders)
</script>

<template>
  <NlPhoneShell :nav="{ title: '我的老人' }">
    <NlSkeleton v-if="loading" :count="3" class="list-pad" />

    <NlEmpty
      v-else-if="isEmpty"
      title="还没有绑定老人"
      description="家属可绑定多位老人，帮他们预约陪诊 / 管理用药"
      action-text="绑定老人"
      @action="bind"
    />

    <ul v-else class="elders">
      <li
        v-for="e in elders"
        :key="e.id"
        class="elder"
        @click="openDetail(e.id)"
      >
        <NlAvatar :fallback="e.name ? e.name.slice(0, 1) : '?'" :size="48" tone="primary" />
        <div class="elder__body">
          <div class="elder__name">
            {{ e.name }}
            <span class="elder__rel">
              （{{ e.genderLabel }}<template v-if="e.age != null"> · {{ e.age }} 岁</template>）
            </span>
            <NlStatusChip
              class="elder__bind"
              scope="bind"
              :status="e.bindStatus"
              :text="e.bindStatusLabel"
            />
          </div>
          <div class="nl-caption nl-text-muted is-num">{{ e.phone }}</div>
          <div v-if="e.favoriteHospital" class="nl-caption nl-text-weak">{{ e.favoriteHospital }}</div>
        </div>
        <div class="elder__ops" @click.stop>
          <el-button type="primary" plain round size="small" @click="unbind(e)">解绑</el-button>
          <el-button type="danger" plain round size="small" @click="remove(e)">删除</el-button>
        </div>
      </li>
    </ul>

    <div v-if="!loading && elders.length" class="nl-caption nl-text-weak list-foot">
      共 {{ total }} 位老人
    </div>

    <div class="bind-bar">
      <el-button type="primary" size="large" round class="bind-bar__btn" @click="bind">
        + 绑定新老人
      </el-button>
    </div>

    <!-- 详情弹窗（getElder，含脱敏身份证/地址/病史等详情级字段） -->
    <el-dialog v-model="detailVisible" title="老人档案" width="92%">
      <NlSkeleton v-if="detailLoading" :count="4" />
      <template v-else-if="detail">
        <div class="detail-head">
          <NlAvatar :fallback="detail.name ? detail.name.slice(0, 1) : '?'" :size="56" tone="primary" />
          <div>
            <div class="detail-head__name">
              {{ detail.name }}
              <span class="nl-caption nl-text-muted">
                {{ detail.genderLabel }}<template v-if="detail.age != null"> · {{ detail.age }} 岁</template>
              </span>
            </div>
            <NlStatusChip scope="bind" :status="detail.bindStatus" :text="detail.bindStatusLabel" />
          </div>
        </div>

        <ul class="detail-rows">
          <li v-if="detail.birthDate"><span>出生日期</span><b class="is-num">{{ formatDate(detail.birthDate) }}</b></li>
          <li v-if="detail.idCard"><span>身份证号</span><b class="is-num">{{ detail.idCard }}</b></li>
          <li v-if="detail.phone"><span>联系电话</span><b class="is-num">{{ detail.phone }}</b></li>
          <li v-if="detail.address"><span>常用地址</span><b>{{ detail.address }}</b></li>
          <li v-if="detail.emergencyContact"><span>紧急联系人</span><b>{{ detail.emergencyContact }}<template v-if="detail.emergencyPhone"> · {{ detail.emergencyPhone }}</template></b></li>
          <li v-if="detail.mobilityLevelLabel"><span>行动能力</span><b>{{ detail.mobilityLevelLabel }}</b></li>
          <li v-if="detail.relation"><span>与您关系</span><b>{{ detail.relation }}</b></li>
          <li v-if="detail.favoriteHospital"><span>常去医院</span><b>{{ detail.favoriteHospital }}</b></li>
          <li v-if="detail.medicalHistory"><span>病史备注</span><b>{{ detail.medicalHistory }}</b></li>
          <li v-if="detail.allergyHistory"><span>过敏史</span><b>{{ detail.allergyHistory }}</b></li>
        </ul>
      </template>
      <NlEmpty v-else type="empty" title="档案加载失败" description="请关闭后重试" />
    </el-dialog>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.list-pad {
  padding: 0 var(--nl-gutter);
}

.list-foot {
  margin: var(--nl-space-3) 0 0;
  padding: 0 var(--nl-gutter);
  text-align: center;
}

.elders {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0 var(--nl-gutter);
  margin: 0;
  list-style: none;
}

.elder {
  display: flex;
  gap: var(--nl-space-3);
  align-items: center;
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  cursor: pointer;

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__name {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;
    font-size: 15px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__rel {
    font-size: 13px;
    font-weight: 400;
    color: var(--nl-text-2);
  }

  &__bind {
    margin-left: auto;
  }

  &__ops {
    display: flex;
    flex-direction: column;
    gap: 6px;
    flex-shrink: 0;
  }
}

.bind-bar {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
  }
}

/* 详情弹窗 */
.detail-head {
  display: flex;
  gap: var(--nl-space-3);
  align-items: center;
  margin-bottom: var(--nl-space-3);

  &__name {
    font-size: 16px;
    font-weight: 600;
    color: var(--nl-text-1);
  }
}

.detail-rows {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-2);
  padding: 0;
  margin: 0;
  list-style: none;

  li {
    display: flex;
    gap: var(--nl-space-3);
    align-items: baseline;
    font-size: 14px;

    span {
      flex-shrink: 0;
      width: 76px;
      color: var(--nl-text-3);
    }

    b {
      font-weight: 500;
      color: var(--nl-text-1);
      word-break: break-all;
    }
  }
}
</style>
