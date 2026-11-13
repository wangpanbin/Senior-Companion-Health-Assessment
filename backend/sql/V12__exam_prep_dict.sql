-- =============================================================================
-- 银龄伴诊 —— 检查准备静态字典种子（V5 · B · F1 检查准备提醒）
-- -----------------------------------------------------------------------------
-- 版本      : V12
-- 覆盖模块  : 检查准备提醒（docs/api/14-exam-prep.md）
-- 变更内容  : sys_dict 新增 EXAM_PREP 类型 6 条常见检查准备条目。
--             不改表结构；与既有字典按 dict_type 隔离。
--
-- 合规口径  : 内容为医院公开检查须知的静态转述（AI 起草占位，待人工审校），
--             每条 remark 必须含「来源 URL + 免责声明」两段结构 ——
--             依据《医疗广告认定指南》(2025) 第 5/7 条：公开就医必需信息
--             与合规科普不构成广告；红线-1：不由 LLM 生成、不含任何医疗建议。
--
-- 幂等性    : ON DUPLICATE KEY UPDATE 只刷新名称/要求/排序/备注，
--             不覆盖 dict_value 之外的运营调整口径（与 V10 同法）。
-- =============================================================================

SET NAMES utf8mb4;

INSERT INTO `sys_dict` (`id`, `dict_type`, `dict_code`, `dict_label`, `dict_value`, `sort_no`, `status`, `remark`) VALUES
  (44901, 'EXAM_PREP', 'FASTING_BLOOD', '空腹血糖 / 静脉采血', '空腹 8–12 小时', 1, 1,
   '检查前空腹 8–12 小时，期间可少量饮白开水；来源：厦门大学附属中山医院检查须知 https://www.xmuzsh.cn/ksjs/csk/jcqzb.htm ；就医须知转述，不构成医疗建议，以就诊医院要求为准'),
  (44902, 'EXAM_PREP', 'ABDOMINAL_US', '腹部 B 超', '空腹 8–12 小时，盆腔项目需憋尿', 2, 1,
   '检查前空腹 8–12 小时；盆腔项目需憋尿至检查结束；来源：厦门大学附属中山医院检查须知 https://www.xmuzsh.cn/ksjs/csk/jcqzb.htm ；就医须知转述，不构成医疗建议，以就诊医院要求为准'),
  (44903, 'EXAM_PREP', 'GASTROSCOPY', '胃镜', '空腹 ≥8 小时，携带既往检查资料', 3, 1,
   '检查前空腹 ≥8 小时，随身携带既往检查资料；来源：厦门大学附属中山医院检查须知 https://www.xmuzsh.cn/ksjs/csk/jcqzb.htm ；就医须知转述，不构成医疗建议，以就诊医院要求为准'),
  (44904, 'EXAM_PREP', 'COLONOSCOPY', '肠镜', '检查前 1–3 天低渣饮食', 4, 1,
   '检查前 1–3 天低渣饮食；具体准备方案由就诊医院告知，本站不提供用药指导；来源：上海市第七人民医院体检须知 https://www.hosno7.com/shsqyy/tjxz/9070.jhtml ；就医须知转述，不构成医疗建议，以就诊医院要求为准'),
  (44905, 'EXAM_PREP', 'URINE_TEST', '尿常规', '留取中段尿，避开月经期', 5, 1,
   '留取中段尿，避开月经期；来源：上海市第七人民医院体检须知 https://www.hosno7.com/shsqyy/tjxz/9070.jhtml ；就医须知转述，不构成医疗建议，以就诊医院要求为准'),
  (44906, 'EXAM_PREP', 'CHEST_CT', '胸部 CT', '无空腹要求，去除金属饰品', 6, 1,
   '无空腹要求，检查前去除金属饰品；来源：厦门大学附属中山医院检查须知 https://www.xmuzsh.cn/ksjs/csk/jcqzb.htm ；就医须知转述，不构成医疗建议，以就诊医院要求为准')
ON DUPLICATE KEY UPDATE
  `dict_label` = VALUES(`dict_label`),
  `dict_value` = VALUES(`dict_value`),
  `sort_no`    = VALUES(`sort_no`),
  `remark`     = VALUES(`remark`);
