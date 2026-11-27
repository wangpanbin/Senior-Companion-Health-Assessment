<script setup>
/**
 * 我的老人（M-19 列表 · docs/design/design.md §1.2 P1）
 *
 * 数据来源：
 *   - `GET /user/elder` 列表（`listElder`，分页 page/size ≤ 100）→ 我绑定的老人
 *   - `GET /user/elder/{id}` 详情（`getElder`）→ 点开卡片看完整档案（含脱敏身份证/地址/病史）
 *   - `PUT /user/elder/{id}` 修改（`updateElder`）→ 详情弹窗里的「编辑档案」（最终复盘 FE-01：
 *     此前老人档案只能新建和删除，填错一个地址就得删了重建）
 *   - `DELETE /user/elder/{id}` 删除（`removeElder`）→ 逻辑删除档案
 *   - `DELETE /user/elder/{id}/bind` 解绑（`unbindElder`）→ 解除当前家属与老人的绑定
 *
 * ⚠️ 列表 VO（`ElderVO.ofList`）口径：姓名/手机号已脱敏，且**不含** relation / address /
 *    病史 / idCard / mobilityLevel —— 这些只在 `getElder` 详情返回。所以列表卡只展示
 *    列表级字段（姓名、性别、年龄、脱敏手机、常去医院、绑定状态），关系与详细档案放详情弹窗。
 *
 * 删除 / 解绑是破坏性操作，必须二次确认；后端返回的业务错误（如存在进行中订单返回 409）
 * 由拦截器弹出，这里不再重复弹。
 *
 * - 形态自适应：正文只写一份，由 NlPageShell 决定套 Mobile 还是 Desktop 壳（ADR-0007）
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  NlPageShell, NlAvatar, NlEmpty, NlSkeleton, NlStatusChip
} from '@/components'
import { useResponsive } from '@/composables/useResponsive'
import { listElder, getElder, updateElder, removeElder, unbindElder } from '@/api/user'
import { confirmThen } from '@/utils/confirm'
import { shouldRollbackPage } from '@/utils/pagination'
import { formatDate, today } from '@/utils/format'

const router = useRouter()

const elders = ref([])
const loading = ref(false)
const total = ref(0)
/** P1-9：page 以前是死变量（列表恒取第 1 页），现在接上真正的分页 */
const page = ref(1)
const pageSize = 50

/**
 * P1-5：解绑 / 删除的 in-flight 锁。
 * 这两个按钮原先没有 loading 态，连点会重复提交（后端是 DELETE，重复执行只会报错）。
 * 锁在函数**第一行同步置位** —— 确认框弹出期间同样算「在飞」，
 * 否则用户在弹窗里再点一次照样会重复提交；finally 里复位。
 */
const unbindingId = ref(null)
const removingId = ref(null)

/* -------- 详情弹窗 -------- */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

/** < 768 让详情弹窗 fullscreen（T-08；用 isXs(≤767)替代 !isMd 修复大屏 PC bug） */
const { isXs } = useResponsive()

const isEmpty = computed(() => !loading.value && !elders.value.length)

/** R4：列表加载失败（不能与「还没绑定老人」的空态混用） */
const loadError = ref(false)

async function loadElders() {
  loading.value = true
  try {
    let data = await listElder({ page: page.value, size: pageSize })
    // 删掉最后一页的最后一条后，当前页会越界变成空页 —— 自动回退一页再取，
    // 否则列表会停在「共 N 位老人」但下面一片空白。
    // 判据与后台列表统一（frontend/src/utils/pagination.js）：
    // 「本页被掏空」才回退，不是「刚删的那条不在本页」。
    if (shouldRollbackPage(page.value, data?.records)) {
      page.value -= 1
      data = await listElder({ page: page.value, size: pageSize })
    }
    elders.value = data?.records || []
    total.value = data?.total || 0
    loadError.value = false
  } catch {
    elders.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}

/** 翻页 */
function onPageChange(p) {
  page.value = p
  loadElders()
}

/** 当前打开的档案 id：错误态重试时要用（不从 DOM 反推） */
const detailId = ref(null)
const detailError = ref(false)

async function openDetail(id) {
  detailId.value = id
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  detailError.value = false
  try {
    detail.value = await getElder(id)
  } catch {
    detail.value = null
    detailError.value = true
  } finally {
    detailLoading.value = false
  }
}

async function unbind(e) {
  if (unbindingId.value != null) return // 已有解绑在飞，忽略连点
  unbindingId.value = e.id
  try {
    const ok = await confirmThen(() => ElMessageBox.confirm(
      `确定解除与「${e.name}」的绑定关系吗？解绑后您将无法直接代其预约陪诊或管理用药。`,
      '解绑确认',
      { type: 'warning', confirmButtonText: '确定解绑', cancelButtonText: '取消' }
    ))
    if (!ok) return // 用户取消
    await unbindElder(e.id)
    ElMessage.success('已解绑')
    loadElders()
  } catch {
    // 业务错误（如存在进行中订单）已由拦截器弹出
  } finally {
    unbindingId.value = null
  }
}

async function remove(e) {
  if (removingId.value != null) return // 已有删除在飞，忽略连点
  removingId.value = e.id
  try {
    const ok = await confirmThen(() => ElMessageBox.confirm(
      `确定删除「${e.name}」的档案吗？该操作不可恢复，但其历史订单与用药记录仍会保留。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    ))
    if (!ok) return // 用户取消
    await removeElder(e.id)
    ElMessage.success('已删除档案')
    loadElders()
  } catch {
    // 业务错误（如存在进行中订单返回 409）已由拦截器弹出
  } finally {
    removingId.value = null
  }
}

function bind() {
  router.push('/family/elder/bind')
}

/* ================================================================== */
/* 编辑档案（PUT /user/elder/{id} —— 最终复盘 FE-01 的第 1 个断点）      */
/* ================================================================== */

/**
 * 为什么从**详情弹窗**进入编辑，而不是列表行的第三个按钮：
 * 列表 VO（`ElderVO.ofList`）不含性别 / 出生日期 / 行动能力等字段，
 * 从列表直接开表单只能拿到姓名和手机号，等于让用户「编辑一个空表单」。
 *
 * ⚠️ 脱敏字段的处理（这是最容易写错的地方）：
 *   `getElder` 返回的 `idCard` / `phone` / `address` / `emergencyPhone` 都是**打过码的**
 *   （`460101********0011`、`139****2222`、`…人民大道***`）。把打码值回填进输入框再提交，
 *   等于把打码串写成新值 —— 后端的局部更新语义是「null 不修改、空串清空」，
 *   所以这些字段**一律留空**，placeholder 写清「留空则不修改」，只有用户真填了才提交。
 */
const editVisible = ref(false)
const editSaving = ref(false)
const editFormRef = ref(null)
const editId = ref(null)

const editForm = reactive({
  name: '',
  gender: '',
  birthDate: '',
  mobilityLevel: '',
  favoriteHospital: '',
  medicalHistory: '',
  allergyHistory: '',
  /* 以下为脱敏字段：留空 = 不修改 */
  address: '',
  phone: '',
  idCard: '',
  emergencyContact: '',
  emergencyPhone: '',
  remark: ''
})

const editRules = {
  name: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { min: 2, max: 20, message: '姓名长度需在 2-20 个字符之间', trigger: 'blur' }
  ],
  birthDate: [
    {
      validator: (_rule, value, cb) => {
        if (value && value >= today()) cb(new Error('出生日期必须早于今天'))
        else cb()
      },
      trigger: 'change'
    }
  ],
  phone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  emergencyPhone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '紧急联系人手机号格式不正确', trigger: 'blur' }]
}

const genderOptions = [
  { value: 'MALE', label: '男' },
  { value: 'FEMALE', label: '女' }
]
const mobilityOptions = [
  { value: 'SELF', label: '可自理' },
  { value: 'ASSIST', label: '需搀扶' },
  { value: 'WHEELCHAIR', label: '需轮椅' }
]

/** 打码值识别：出现 `*` 就不是真实值，绝不能回填 */
function masked(value) {
  return typeof value === 'string' && value.includes('*')
}

function openEdit() {
  const d = detail.value
  if (!d) return
  editId.value = d.id
  editForm.name = d.name || ''
  editForm.gender = d.gender || ''
  editForm.birthDate = d.birthDate || ''
  editForm.mobilityLevel = d.mobilityLevel || ''
  editForm.favoriteHospital = d.favoriteHospital || ''
  editForm.medicalHistory = d.medicalHistory || ''
  editForm.allergyHistory = d.allergyHistory || ''
  // 脱敏字段一律清空：宁可让用户重填，也不能把 `139****2222` 当成新手机号提交
  editForm.address = masked(d.address) ? '' : d.address || ''
  editForm.phone = masked(d.phone) ? '' : d.phone || ''
  editForm.idCard = masked(d.idCard) ? '' : d.idCard || ''
  editForm.emergencyContact = d.emergencyContact || ''
  editForm.emergencyPhone = masked(d.emergencyPhone) ? '' : d.emergencyPhone || ''
  editForm.remark = ''
  editVisible.value = true
  editFormRef.value?.clearValidate()
}

async function submitEdit() {
  if (editSaving.value) return
  editSaving.value = true
  try {
    editFormRef.value?.clearValidate()
    const valid = await editFormRef.value?.validate().catch(() => false)
    if (!valid) return

    // 只提交「用户真的填了」的字段：
    // 后端语义是 null=不修改 / ""=清空，所以空串必须**剔除**而不是原样发过去，
    // 否则用户没动的地址会被清空。
    const payload = {}
    Object.keys(editForm).forEach((k) => {
      const v = typeof editForm[k] === 'string' ? editForm[k].trim() : editForm[k]
      if (v !== '' && v != null) payload[k] = v
    })
    if (!Object.keys(payload).length) {
      ElMessage.info('没有改动需要保存')
      return
    }
    await updateElder(editId.value, payload)
    ElMessage.success('档案已更新')
    editVisible.value = false
    // 详情弹窗里的旧值要跟着变，否则用户关掉编辑还以为没生效
    if (detail.value?.id === editId.value) await openDetail(editId.value)
    loadElders()
  } catch {
    // 业务错误（如身份证重复 2xxx）由拦截器弹出，这里不重复弹
  } finally {
    editSaving.value = false
  }
}

onMounted(loadElders)
</script>

<template>
  <NlPageShell title="我的老人">
    <NlSkeleton v-if="loading" :count="3" class="list-pad" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="老人档案加载失败"
      action-text="重试"
      @action="loadElders"
    />

    <NlEmpty
      v-else-if="isEmpty"
      title="还没有绑定老人"
      description="家属可绑定多位老人，帮他们预约陪诊 / 管理用药"
      action-text="绑定老人"
      @action="bind"
    />

    <ul v-else role="list" class="elders">
      <li
v-for="e in elders" :key="e.id" role="button" tabindex="0"
        class="elder"
        @keydown.enter.prevent="openDetail(e.id)"
        @keydown.space.prevent="openDetail(e.id)"
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
          <el-button
            type="primary"
            plain
            round
            size="small"
            :loading="unbindingId === e.id"
            :disabled="unbindingId != null"
            @click="unbind(e)"
          >解绑</el-button>
          <el-button
            type="danger"
            plain
            round
            size="small"
            :loading="removingId === e.id"
            :disabled="removingId != null"
            @click="remove(e)"
          >删除</el-button>
        </div>
      </li>
    </ul>

    <div v-if="!loading && elders.length" class="nl-caption nl-text-weak list-foot">
      共 {{ total }} 位老人
    </div>

    <!-- P1-9：真正接上分页（以前 page 是死变量，列表恒取第 1 页，多于 50 位老人就看不见了） -->
    <el-pagination
      v-if="!loading && !loadError && total > pageSize"
      class="elder-pager"
      layout="prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="page"
      @current-change="onPageChange"
    />

    <div class="bind-bar">
      <el-button type="primary" size="large" round class="bind-bar__btn" @click="bind">
        + 绑定新老人
      </el-button>
    </div>

    <!-- 详情弹窗（getElder，含脱敏身份证/地址/病史等详情级字段） -->
    <el-dialog v-model="detailVisible" title="老人档案" width="92%" :fullscreen="isXs">
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
      <NlEmpty
        v-else
        type="network"
        title="档案加载失败"
        description="请检查网络后重试"
        action-text="重试"
        @action="openDetail(detailId)"
      />
      <!-- FE-01：档案此前只能新建 / 删除，改一个错字就得整份重建 -->
      <template v-if="detail" #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button type="primary" @click="openEdit">编辑档案</el-button>
      </template>
    </el-dialog>

    <!-- ============ 编辑档案（PUT /user/elder/{id}） ============ -->
    <el-dialog v-model="editVisible" title="编辑老人档案" width="92%" :fullscreen="isXs">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" size="large" label-position="top">
        <el-form-item label="姓名" prop="name" required>
          <el-input v-model="editForm.name" placeholder="请输入老人姓名" maxlength="20" />
        </el-form-item>
        <el-form-item label="性别" prop="gender">
          <el-radio-group v-model="editForm.gender">
            <el-radio-button v-for="g in genderOptions" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="出生日期" prop="birthDate">
          <el-date-picker
            v-model="editForm.birthDate"
            type="date"
            value-format="YYYY-MM-DD"
            :max="today()"
            placeholder="选择出生日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="行动能力" prop="mobilityLevel">
          <el-select v-model="editForm.mobilityLevel" placeholder="用于匹配陪诊员" clearable style="width: 100%">
            <el-option v-for="m in mobilityOptions" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="常去医院" prop="favoriteHospital">
          <el-input v-model="editForm.favoriteHospital" placeholder="如：海南省人民医院" maxlength="100" />
        </el-form-item>

        <p class="nl-caption nl-text-muted edit-tip">
          以下字段在详情里是<b>脱敏展示</b>的，因此留空即「不修改」；只有填了才会覆盖原值。
        </p>

        <el-form-item label="常用地址" prop="address">
          <el-input v-model="editForm.address" placeholder="留空则不修改" maxlength="200" />
        </el-form-item>
        <el-form-item label="老人联系电话" prop="phone">
          <el-input v-model="editForm.phone" type="tel" inputmode="numeric" placeholder="留空则不修改" maxlength="11" />
        </el-form-item>
        <el-form-item label="身份证号" prop="idCard">
          <el-input v-model="editForm.idCard" placeholder="留空则不修改" maxlength="18" />
        </el-form-item>
        <el-form-item label="紧急联系人姓名" prop="emergencyContact">
          <el-input v-model="editForm.emergencyContact" placeholder="留空则不修改" maxlength="20" />
        </el-form-item>
        <el-form-item label="紧急联系人电话" prop="emergencyPhone">
          <el-input v-model="editForm.emergencyPhone" type="tel" inputmode="numeric" placeholder="留空则不修改" maxlength="11" />
        </el-form-item>

        <el-form-item label="病史备注" prop="medicalHistory">
          <el-input v-model="editForm.medicalHistory" type="textarea" :rows="2" placeholder="仅记录，系统不做诊断、不给用药建议" maxlength="500" />
        </el-form-item>
        <el-form-item label="过敏史备注" prop="allergyHistory">
          <el-input v-model="editForm.allergyHistory" type="textarea" :rows="2" placeholder="如：青霉素过敏" maxlength="500" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="editForm.remark" type="textarea" :rows="2" placeholder="留空则不修改" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </NlPageShell>
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

.elder-pager {
  margin-top: var(--nl-space-3);
  padding: 0 var(--nl-gutter);
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

  /* 移动端：操作按钮挪到卡片底部整行（见下方 __ops 说明），故允许换行 */
  @media (max-width: 767px) {
    flex-wrap: wrap;
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__name {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;
    font-size: var(--nl-font-h3);
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__rel {
    font-size: var(--nl-font-caption);
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

    /*
     * 移动端：「解绑 / 删除」改为卡片底部整行操作栏。
     *
     * 改之前这两个 size="small" 按钮只有 24px 高、竖着挤在卡片右上角：
     *   ① 触控区远低于 design.md 的「最小触控区 >=48px」，老年人点不准；
     *   ② 视觉上把卡片右侧压得很挤，两个胶囊一蓝一红竖排看着像残留控件。
     * 现在撑满一行、等分宽度，破坏性操作「删除」仍然靠右但不再是针尖大小。
     */
    @media (max-width: 767px) {
      flex-direction: row;
      width: 100%;
      gap: var(--nl-space-2);
      padding-top: var(--nl-space-3);
      margin-top: var(--nl-space-1);
      border-top: 1px solid var(--nl-divider);

      :deep(.el-button) {
        flex: 1;
        margin-left: 0;
      }
    }
  }
}

.bind-bar {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
  }
}

/* 详情弹窗 */
/* 编辑弹窗里的脱敏说明：必须显眼一点，否则用户会以为「地址空着等于没填过」 */
.edit-tip {
  margin: var(--nl-space-2) 0 var(--nl-space-3);
  line-height: 1.6;
}

.detail-head {
  display: flex;
  gap: var(--nl-space-3);
  align-items: center;
  margin-bottom: var(--nl-space-3);

  &__name {
    font-size: var(--nl-font-h3);
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
    font-size: var(--nl-font-body);

    span {
      flex-shrink: 0;
      width: 76px;
      color: var(--nl-text-2);
    }

    b {
      font-weight: 500;
      color: var(--nl-text-1);
      word-break: break-all;
    }
  }
}
</style>
