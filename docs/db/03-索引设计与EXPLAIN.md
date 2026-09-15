# 银龄伴诊 · 索引设计与 EXPLAIN 实测（M1）

## 一、索引清单

| 表 | 索引名 | 类型 | 列 | 设计目的 |
|---|---|---|---|---|
| `sys_user` | `uk_username` | 唯一 | `username` | 登录按用户名查询，且防重名 |
| `sys_user` | `uk_phone` | 唯一 | `phone` | 手机号登录/找回，且防一号多账号 |
| `sys_user` | `idx_role_status` | 普通 | `role`, `status` | 后台按角色 + 状态筛选用户列表 |
| `sys_user` | `idx_create_time` | 普通 | `create_time` | 时间倒序分页 / 按日统计 |
| `sys_user` | `idx_nickname` | 普通 | `nickname` | 后台按昵称模糊前缀检索 |
| `sys_login_log` | `idx_user_time` | 普通 | `user_id`, `login_time` | 按用户查登录历史（安全审计） |
| `sys_login_log` | `idx_username` | 普通 | `username` | 支撑业务查询条件 |
| `sys_login_log` | `idx_status_time` | 普通 | `status`, `login_time` | 按结果查失败登录（风控） |
| `sys_dict` | `uk_type_code` | 唯一 | `dict_type`, `dict_code` | 同一字典类型下编码唯一 |
| `sys_dict` | `idx_type_sort` | 普通 | `dict_type`, `sort_no` | 下拉项按类型取出并有序返回 |
| `sys_file` | `uk_file_id` | 唯一 | `file_id` | 上传接口按 fileId 反查 |
| `sys_file` | `idx_biz` | 普通 | `biz_type`, `biz_id` | 支撑业务查询条件 |
| `sys_file` | `idx_uploader` | 普通 | `uploader_id` | 统计某人上传文件 |
| `admin_oper_log` | `idx_operator_time` | 普通 | `operator_id`, `oper_time` | 按管理员查操作记录 |
| `admin_oper_log` | `idx_oper_type_time` | 普通 | `oper_type`, `oper_time` | 按操作类型统计（M10） |
| `admin_oper_log` | `idx_target` | 普通 | `target_type`, `target_id` | 按目标对象追溯全部处置记录 |
| `admin_oper_log` | `idx_oper_time` | 普通 | `oper_time` | 按时间倒序分页 |
| `elder_profile` | `idx_user_id` | 普通 | `user_id` | 由老人账号反查档案 |
| `elder_profile` | `idx_bind_status` | 普通 | `bind_status` | 筛选未绑定档案（家属认领） |
| `elder_profile` | `idx_create_by` | 普通 | `create_by` | 按建档家属查其代建档案 |
| `elder_profile` | `idx_name` | 普通 | `name` | 档案按姓名检索 |
| `elder_profile` | `idx_phone` | 普通 | `phone` | 按电话匹配档案 |
| `family_elder_relation` | `uk_family_elder` | 唯一 | `family_id`, `elder_id` | 同一家属与同一老人只有一条关系 |
| `family_elder_relation` | `idx_elder_status` | 普通 | `elder_id`, `status` | 查某老人的有效绑定人 |
| `family_elder_relation` | `idx_family_status` | 普通 | `family_id`, `status` | 查某家属绑定的全部老人 |
| `companion_audit_record` | `idx_applicant_status` | 普通 | `applicant_user_id`, `audit_status` | 申请人查自己的申请进度 |
| `companion_audit_record` | `idx_status_submit` | 普通 | `audit_status`, `submit_time` | 后台待审核队列（按提交时间） |
| `companion_audit_record` | `idx_real_name` | 普通 | `real_name` | 按姓名检索申请 |
| `companion_profile` | `uk_user_id` | 唯一 | `user_id` | 一账号一份陪诊员资料 |
| `companion_profile` | `idx_audit_work` | 普通 | `audit_status`, `work_status` | 筛选「已通过且在岗」的可接单陪诊员 |
| `companion_profile` | `idx_service_area` | 普通 | `service_area` | 按服务区域匹配陪诊员 |
| `companion_profile` | `idx_score` | 普通 | `score` | 按评分排序（选人页） |
| `companion_profile` | `idx_order_count` | 普通 | `order_count` | 按接单量排序（榜单） |
| `companion_order` | `uk_order_no` | 唯一 | `order_no` | 订单号唯一 + 按订单号查询 |
| `companion_order` | `idx_status_create` | 普通 | `status`, `create_time` | 按状态 + 下单时间分页 |
| `companion_order` | `idx_status_visit` | 普通 | `status`, `visit_time` | 订单大厅：status='PENDING' AND visit_time>NOW() ORDER BY visit_time |
| `companion_order` | `idx_family_create` | 普通 | `family_id`, `create_time` | 家属「我的订单」按时间倒序 |
| `companion_order` | `idx_companion_status` | 普通 | `companion_id`, `status` | 陪诊员「我的接单」按状态筛选 |
| `companion_order` | `idx_elder_id` | 普通 | `elder_id` | 按老人查历史订单 |
| `companion_order` | `idx_visit_time` | 普通 | `visit_time` | 按就诊时间范围查询 |
| `companion_order` | `idx_payment_status` | 普通 | `payment_status` | 结算状态筛选（线下对账） |
| `companion_order` | `idx_create_time` | 普通 | `create_time` | 时间倒序分页 / 按日统计 |
| `order_status_log` | `idx_order_time` | 普通 | `order_id`, `operate_time` | 订单时间线按时间正序 |
| `order_status_log` | `idx_to_status` | 普通 | `to_status` | 统计各状态到达量（漏斗） |
| `order_status_log` | `idx_operator` | 普通 | `operator_id` | 按操作人追溯 |
| `order_reject_log` | `uk_order_companion` | 唯一 | `order_id`, `companion_id` | 同一陪诊员对同一单只拒一次 |
| `order_reject_log` | `idx_companion_id` | 普通 | `companion_id` | 查某陪诊员的全部相关记录 |
| `order_checkin` | `uk_order_node` | 唯一 | `order_id`, `node` | 同一订单同一节点只允许打卡一次（幂等） |
| `order_checkin` | `idx_order_time` | 普通 | `order_id`, `checkin_time` | 订单时间线按时间正序 |
| `order_checkin` | `idx_companion_id` | 普通 | `companion_id` | 查某陪诊员的全部相关记录 |
| `companion_track` | `idx_order_time` | 普通 | `order_id`, `record_time` | 订单时间线按时间正序 |
| `companion_track` | `idx_companion_id` | 普通 | `companion_id` | 查某陪诊员的全部相关记录 |
| `medicine_dict` | `idx_name` | 普通 | `name` | 档案按姓名检索 |
| `medicine_dict` | `idx_trade_name` | 普通 | `trade_name` | 支撑业务查询条件 |
| `medicine_dict` | `idx_dosage_form` | 普通 | `dosage_form` | 支撑业务查询条件 |
| `medicine_dict` | `idx_is_common` | 普通 | `is_common` | 支撑业务查询条件 |
| `medication_plan` | `idx_elder_status` | 普通 | `elder_id`, `status` | 查某老人的有效绑定人 |
| `medication_plan` | `idx_medicine_id` | 普通 | `medicine_id` | 支撑业务查询条件 |
| `medication_plan` | `idx_start_end` | 普通 | `start_date`, `end_date` | 按日期区间筛有效计划 |
| `medication_plan` | `idx_created_by` | 普通 | `created_by` | 按创建家属查计划 |
| `medication_task` | `uk_plan_time` | 唯一 | `plan_id`, `plan_time` | 定时任务重复触发不产生重复行（幂等） |
| `medication_task` | `idx_elder_date_status` | 普通 | `elder_id`, `plan_date`, `status` | 老人某日服药任务（今日用药卡片） |
| `medication_task` | `idx_status_plan_time` | 普通 | `status`, `plan_time` | 漏服扫描：status='PENDING' AND plan_time<NOW() |
| `medication_task` | `idx_plan_id` | 普通 | `plan_id` | 按计划查任务 |
| `medication_task` | `idx_plan_date` | 普通 | `plan_date` | 按日聚合用 |
| `order_review` | `uk_order_id` | 唯一 | `order_id` | 一单一评，防重复评价 |
| `order_review` | `idx_companion_valid` | 普通 | `companion_id`, `is_valid` | 评分聚合：按陪诊员 + 是否有效 |
| `order_review` | `idx_family_id` | 普通 | `family_id` | 家属查看自己发出的评价 |
| `order_review` | `idx_score` | 普通 | `score` | 按评分排序（选人页） |
| `order_review` | `idx_create_time` | 普通 | `create_time` | 时间倒序分页 / 按日统计 |
| `complaint` | `idx_order_id` | 普通 | `order_id` | 支撑业务查询条件 |
| `complaint` | `idx_status_create` | 普通 | `status`, `create_time` | 按状态 + 下单时间分页 |
| `complaint` | `idx_complainant` | 普通 | `complainant_id` | 查某用户发起的投诉 |
| `complaint` | `idx_target_user` | 普通 | `target_user_id` | 查某用户被投诉记录（违规累计） |
| `complaint` | `idx_type` | 普通 | `type` | 支撑业务查询条件 |
| `internal_message` | `idx_receiver_read` | 普通 | `receiver_id`, `is_read`, `create_time` | 未读消息数 + 消息列表（覆盖索引） |
| `internal_message` | `idx_receiver_type` | 普通 | `receiver_id`, `type` | 按类型筛选消息 |
| `internal_message` | `idx_biz` | 普通 | `biz_type`, `biz_id` | 支撑业务查询条件 |
| `internal_message` | `idx_create_time` | 普通 | `create_time` | 时间倒序分页 / 按日统计 |

## 二、关键设计决策

### 1. 订单大厅为什么单独加 `idx_status_visit`

大厅查询是 `WHERE status='PENDING' AND visit_time > NOW() ORDER BY visit_time LIMIT 20`。
仅有 `idx_status_create(status, create_time)` 时，MySQL 会先用它过滤出所有
`PENDING` 订单，再回表排序 `visit_time`，EXPLAIN 出现 **Using filesort**。
补上 `(status, visit_time)` 复合索引后，排序键与索引顺序一致，
`type=range` 且 **filesort 消失**。

### 2. 复合索引的最左前缀

`idx_elder_date_status(elder_id, plan_date, status)` 的顺序不是随便定的：
查询条件是「某老人 + 某天 + 某状态」，等值列按**区分度**排列，
`elder_id`（基数高）在前、`status`（基数仅 3）在后，
这样 `(elder_id, plan_date)` 也能被 `(elder_id)` 前缀复用。

### 3. 用唯一索引替代外键做幂等

三处唯一索引同时承担**业务约束 + 幂等保证**：

| 唯一索引 | 约束 | 防的场景 |
|---|---|---|
| `uk_order_node(order_id, node)` | 同单同节点只打一次卡 | 网络重试导致重复打卡 |
| `uk_plan_time(plan_id, plan_time)` | 同计划同时间点只有一条任务 | 定时任务重复触发产生重复服药任务 |
| `uk_order_id(order_id)` | 一单一评 | 家属连点两次提交按钮 |

### 4. 冗余字段是有意为之

`medication_task` 冗余了 `medicine_name` / `dosage` / `meal_relation`，
`order_review` 冗余了 `order_no` —— 目的是让**历史记录不受主数据变更影响**：
药品字典改名后，三个月前那条服药任务仍应显示当时的药名。

### 5. 为什么没有物理外键

① 本课程设计需演示**并发接单**，外键会加剧行锁竞争；
② 逻辑删除与 `ON DELETE` 语义冲突，`deleted=1` 的行仍被外键视为「存在」；
③ 参照完整性由 Service 层校验 + 唯一索引兜底。

## 三、EXPLAIN 实测结论

对 7 类高频查询执行 `EXPLAIN`，**全部命中索引，无 `type=ALL` 全表扫描，无 `filesort`**：

| # | 查询场景 | 命中索引 | 结论 |
|---|---|---|---|
| 1 | 待接单按时间倒序 | `idx_status_create` | type=ref，无 filesort |
| 2 | 老人某日待服药任务 | `idx_elder_date_status` | type=ref（三列全用） |
| 3 | 可接单陪诊员列表 | `idx_audit_work` | type=ref |
| 4 | 订单大厅 | `idx_status_visit` | type=range，**无 filesort** |
| 5 | 未读消息数 | `idx_receiver_read` | type=ref，覆盖索引 |
| 6 | 漏服扫描 | `idx_status_plan_time` | type=range |
| 7 | 评分聚合 | `idx_companion_valid` | type=ref，聚合前已过滤 |

### 已知未走索引的查询（有意暂不优化）

查询「`status='CANCELLED'` 且按 `cancel_time` 倒序」时，EXPLAIN 出现
`Using filesort` —— 因为 `idx_status_create(status, create_time)` 与
`idx_status_visit(status, visit_time)` 的排序键都不是 `cancel_time`。

**暂不建索引的理由**：取消单目前仅 3 条，排序开销可忽略；且「按取消时间排序」
是临时试出来的查询，不在 `docs/api/` 的既有接口里，为一个未确认的需求加索引属于过度设计。

**现状约定**：订单列表（含已取消、已评价等终态）**统一按 `create_time` 排序**，
复用 `idx_status_create`，可避免 filesort。若 M9 管理端确实需要按取消时间排序，
再加 `KEY idx_status_cancel (status, cancel_time)` 即可。

## 四、后续优化预案（M10 之后）

数据量增大后优先考虑：① `companion_order` 按月分区；
② `order_review` 评分聚合改由异步任务冗余到 `companion_profile`（已预留字段）；
③ `companion_track` 轨迹点属于时序数据，可迁到 Redis / 时序库并 TTL 淘汰；
④ 按需补 `idx_status_cancel(status, cancel_time)` 等终态列表索引。
