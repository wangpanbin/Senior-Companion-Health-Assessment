<script setup>
/**
 * 绑定 / 新建老人（M-19 · design.md §1.2 P1）
 *
 * 两个入口（顶部 Tab 切换）：
 *   1. 绑定已有老人 —— `POST /user/elder/bind`，入参 ElderBindDTO：
 *        bindType  绑定方式，一期仅支持 PHONE（INVITE_CODE 后端返回 501）
 *        bindValue 老人注册手机号
 *        relation  与老人关系（SON / DAUGHTER / RELATIVE / OTHER）
 *   2. 新建老人档案 —— `POST /user/elder`，入参 ElderCreateDTO：
 *        必填：name / gender / birthDate（年龄由出生日期算，后端不收 age）
 *        可选：idCard / phone / address / emergencyContact / emergencyPhone /
 *              medicalHistory / allergyHistory / mobilityLevel /
 *              favoriteHospital / remark
 *
 * ⚠️ 字段名与校验规则严格对齐后端 DTO（`backend/.../dto/ElderBindDTO.java`、
 * `ElderCreateDTO.java` 与 `constant/ValidationPatterns.java`）：
 *    - 手机号 `^1[3-9]\d{9}$`
 *    - 身份证 `^[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]$`
 *    - 性别 MALE/FEMALE；行动能力 SELF/ASSIST/WHEELCHAIR
 *    - 可选字段留空即「不填」，提交前剔除空串（与后端 Optional 正则 `^$|...` 一致）
 *
 * 前端校验只是体验，真正的边界在后端：提交后若后端返回业务错误，拦截器已弹提示，
 * `catch` 里不再重复弹。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPhoneShell, NlCard, NlNoticeBar } from '@/components'
import { bindElder, createElder } from '@/api/user'
import { today } from '@/utils/format'

const PHONE_RE = /^1[3-9]\d{9}$/
const ID_CARD_RE = /^[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]$/

const router = useRouter()
const tab = ref('create')

/* ---------------- 绑定已有老人 ---------------- */
const bindFormRef = ref(null)
const bindForm = ref({
  phone: '',
  relation: ''
})

/* ---------------- 新建老人档案 ---------------- */
const bindRules = {
  phone: [
    { required: true, message: '请输入老人手机号', trigger: 'blur' },
    { pattern: PHONE_RE, message: '手机号格式不正确', trigger: 'blur' }
  ],
  relation: [{ required: true, message: '请选择与老人的关系', trigger: 'change' }]
}

const createFormRef = ref(null)
const createForm = ref({
  name: '',
  gender: '',
  birthDate: '',
  idCard: '',
  phone: '',
  address: '',
  emergencyContact: '',
  emergencyPhone: '',
  medicalHistory: '',
  allergyHistory: '',
  mobilityLevel: '',
  favoriteHospital: '',
  remark: ''
})

const createRules = {
  name: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { min: 2, max: 20, message: '姓名长度需在 2-20 个字符之间', trigger: 'blur' }
  ],
  gender: [{ required: true, message: '请选择性别', trigger: 'change' }],
  birthDate: [
    { required: true, message: '请选择出生日期', trigger: 'change' },
    {
      validator: (_rule, value, cb) => {
        if (value && value >= today()) cb(new Error('出生日期必须早于今天'))
        else cb()
      },
      trigger: 'change'
    }
  ],
  idCard: [{ pattern: ID_CARD_RE, message: '身份证号格式不正确', trigger: 'blur' }],
  phone: [{ pattern: PHONE_RE, message: '手机号格式不正确', trigger: 'blur' }],
  emergencyPhone: [{ pattern: PHONE_RE, message: '紧急联系人手机号格式不正确', trigger: 'blur' }],
  mobilityLevel: [
    { pattern: /^(SELF|ASSIST|WHEELCHAIR)$/, message: '请选择有效的行动能力', trigger: 'change' }
  ]
}

const relationOptions = [
  { value: 'SON', label: '儿子' },
  { value: 'DAUGHTER', label: '女儿' },
  { value: 'RELATIVE', label: '亲属' },
  { value: 'OTHER', label: '其他' }
]
const genderOptions = [
  { value: 'MALE', label: '男' },
  { value: 'FEMALE', label: '女' }
]
const mobilityOptions = [
  { value: 'SELF', label: '可自理' },
  { value: 'ASSIST', label: '需搀扶' },
  { value: 'WHEELCHAIR', label: '需轮椅' }
]

async function submitBind() {
  try {
    await bindFormRef.value.validate()
  } catch {
    return
  }
  try {
    const res = await bindElder({
      bindType: 'PHONE',
      bindValue: bindForm.value.phone.trim(),
      relation: bindForm.value.relation
    })
    // 后端实际返回 ElderIdVO{ elderId }，无 bindStatus 字段；
    // 以 elderId 非空判定成功。
    if (res?.elderId != null) {
      ElMessage.success('绑定成功')
      router.push('/family/elder')
    }
  } catch {
    // 业务错误已由拦截器弹出，这里不再重复弹
  }
}

async function submitCreate() {
  try {
    await createFormRef.value.validate()
  } catch {
    return
  }
  const data = { ...createForm.value }
  Object.keys(data).forEach((k) => {
    if (typeof data[k] === 'string') data[k] = data[k].trim()
  })
  // 剔除空串可选字段，让后端按「未填」处理（与后端 Optional 正则口径一致）
  ;[
    'idCard', 'phone', 'address', 'emergencyContact', 'emergencyPhone',
    'medicalHistory', 'allergyHistory', 'mobilityLevel', 'favoriteHospital', 'remark'
  ].forEach((k) => {
    if (!data[k]) delete data[k]
  })
  try {
    const res = await createElder(data)
    if (res?.elderId != null) {
      ElMessage.success('档案创建成功，已自动绑定到您名下')
      router.push('/family/elder')
    }
  } catch {
    // 业务错误已由拦截器弹出
  }
}

function submit() {
  if (tab.value === 'bind') submitBind()
  else submitCreate()
}
</script>

<template>
  <NlPhoneShell :nav="{ title: '绑定老人' }">
    <NlNoticeBar>
      绑定后您可代老人预约陪诊 / 管理用药 / 查看就医记录，老人本人无需操作。
    </NlNoticeBar>

    <section class="tabs">
      <button :class="['tabs__btn', { 'is-active': tab === 'bind' }]" @click="tab = 'bind'">
        绑定已有老人
      </button>
      <button :class="['tabs__btn', { 'is-active': tab === 'create' }]" @click="tab = 'create'">
        新建老人档案
      </button>
    </section>

    <NlCard>
      <!-- ============ 绑定已有老人 ============ -->
      <el-form v-if="tab === 'bind'" ref="bindFormRef" :model="bindForm" :rules="bindRules" size="large" label-position="top">
        <el-form-item label="老人手机号" prop="phone" required>
          <el-input v-model="bindForm.phone" placeholder="请输入老人注册时的手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="与老人关系" prop="relation" required>
          <el-select v-model="bindForm.relation" placeholder="请选择您与老人的关系" style="width: 100%">
            <el-option v-for="r in relationOptions" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-form-item>
        <p class="nl-caption nl-text-muted">
          绑定方式为一期仅支持的手机号认领（老人在本平台已注册账号）。邀请码绑定暂未开放。
        </p>
      </el-form>

      <!-- ============ 新建老人档案 ============ -->
      <el-form v-else ref="createFormRef" :model="createForm" :rules="createRules" size="large" label-position="top">
        <el-form-item label="姓名" prop="name" required>
          <el-input v-model="createForm.name" placeholder="请输入老人姓名" maxlength="20" />
        </el-form-item>
        <el-form-item label="性别" prop="gender" required>
          <el-radio-group v-model="createForm.gender">
            <el-radio-button v-for="g in genderOptions" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="出生日期" prop="birthDate" required>
          <el-date-picker
            v-model="createForm.birthDate"
            type="date"
            value-format="YYYY-MM-DD"
            :max="today()"
            placeholder="选择出生日期（年龄将自动计算）"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="身份证号（选填）" prop="idCard">
          <el-input v-model="createForm.idCard" placeholder="18 位身份证号，加密存储" maxlength="18" />
        </el-form-item>
        <el-form-item label="老人联系电话（选填）" prop="phone">
          <el-input v-model="createForm.phone" placeholder="11 位手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="常用地址（选填）" prop="address">
          <el-input v-model="createForm.address" placeholder="文字地址，一期不做地图导航" maxlength="200" />
        </el-form-item>
        <el-form-item label="紧急联系人姓名（选填）" prop="emergencyContact">
          <el-input v-model="createForm.emergencyContact" placeholder="紧急联系人姓名" maxlength="20" />
        </el-form-item>
        <el-form-item label="紧急联系人电话（选填）" prop="emergencyPhone">
          <el-input v-model="createForm.emergencyPhone" placeholder="11 位手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="行动能力（选填）" prop="mobilityLevel">
          <el-select v-model="createForm.mobilityLevel" placeholder="用于匹配陪诊员" clearable style="width: 100%">
            <el-option v-for="m in mobilityOptions" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="常去医院（选填）" prop="favoriteHospital">
          <el-input v-model="createForm.favoriteHospital" placeholder="如：海南省人民医院" maxlength="100" />
        </el-form-item>
        <el-form-item label="病史备注（选填）" prop="medicalHistory">
          <el-input v-model="createForm.medicalHistory" type="textarea" :rows="2" placeholder="仅记录，系统不做诊断、不给用药建议" maxlength="500" />
        </el-form-item>
        <el-form-item label="过敏史备注（选填）" prop="allergyHistory">
          <el-input v-model="createForm.allergyHistory" type="textarea" :rows="2" placeholder="如：青霉素过敏" maxlength="500" />
        </el-form-item>
        <el-form-item label="备注（选填）" prop="remark">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" placeholder="如：听力较差，沟通需大声" maxlength="200" />
        </el-form-item>
      </el-form>
    </NlCard>

    <div class="submit-bar">
      <el-button type="primary" size="large" round class="submit-bar__btn" @click="submit">
        {{ tab === 'bind' ? '绑定' : '创建档案' }}
      </el-button>
    </div>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.tabs {
  display: flex;
  gap: var(--nl-space-2);
  padding: 0 var(--nl-gutter);

  &__btn {
    flex: 1;
    padding: 12px 0;
    font-size: 14px;
    font-weight: 500;
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: 12px 12px 0 0;
    cursor: pointer;
    border-bottom: 0;

    &.is-active {
      color: var(--nl-primary);
      background: var(--nl-bg-card);
      border-bottom: 2px solid var(--nl-primary);
    }
  }
}

.submit-bar {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
  }
}
</style>
