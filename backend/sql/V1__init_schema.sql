-- =============================================================================
-- 银龄伴诊 —— 数据库初始化 · 建表脚本
-- -----------------------------------------------------------------------------
-- 版本      : V1
-- 作者      : D（PM / 数据库）
-- 覆盖模块  : M1 数据库设计与数据初始化
-- 字符集    : utf8mb4 / utf8mb4_general_ci
-- 引擎      : InnoDB
--
-- 约定（全表统一，验收项）：
--   1. 主键          BIGINT AUTO_INCREMENT，业务不使用 UUID 主键
--   2. 金额          DECIMAL(10,2)，禁止 FLOAT / DOUBLE
--   3. 时间          DATETIME，统一 GMT+8（连接串 serverTimezone=Asia/Shanghai）
--   4. 经纬度        DECIMAL(10,6)，禁止 FLOAT
--   5. 审计字段      create_time / update_time / deleted
--   6. 布尔字段      TINYINT，0-否 1-是
--   7. 枚举字段      VARCHAR 存大写英文枚举名，禁止存中文
--   8. 所有业务表加逻辑删除 deleted，禁止物理删除
--
-- 幂等：全部使用 CREATE TABLE IF NOT EXISTS，可重复执行不中断。
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;


-- =============================================================================
-- 一、账号与权限域
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. sys_user —— 用户主表（四类角色共用一张表，靠 role 区分）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
  `username`             VARCHAR(50)  NOT NULL                COMMENT '登录用户名，4-20 位字母数字下划线',
  `password`             VARCHAR(100) NOT NULL                COMMENT '登录密码，BCrypt 哈希（$2a$ 开头），禁止明文/可逆加密',
  `nickname`             VARCHAR(50)  NOT NULL                COMMENT '昵称（如「张大爷」「李阿姨」），面向老人的称呼',
  `real_name`            VARCHAR(50)           DEFAULT NULL   COMMENT '真实姓名，陪诊员/管理员使用，老人与家属可空',
  `phone`                VARCHAR(20)  NOT NULL                COMMENT '手机号，唯一，接口返回时须脱敏',
  `avatar`               VARCHAR(255)          DEFAULT NULL   COMMENT '头像 URL',
  `role`                 VARCHAR(20)  NOT NULL                COMMENT '角色：ELDER-老年患者 / FAMILY-家属 / COMPANION-陪诊员 / ADMIN-管理员',
  `status`               VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT '账号状态：NORMAL-正常 / DISABLED-已封禁',
  `need_change_password` TINYINT      NOT NULL DEFAULT 0      COMMENT '是否需强制改密：0-否 1-是（管理员重置密码后置 1）',
  `last_login_time`      DATETIME              DEFAULT NULL   COMMENT '最后登录时间',
  `last_login_ip`        VARCHAR(50)           DEFAULT NULL   COMMENT '最后登录 IP',
  `remark`               VARCHAR(255)          DEFAULT NULL   COMMENT '备注（封禁原因等）',
  `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`              TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_role_status` (`role`, `status`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_nickname` (`nickname`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户主表（四类角色共用）';


-- -----------------------------------------------------------------------------
-- 2. sys_login_log —— 登录日志（M2 安全审计 / 答辩演示「登录行为可追溯」）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_login_log` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志 ID',
  `user_id`     BIGINT                DEFAULT NULL   COMMENT '用户 ID，账号不存在时为 NULL',
  `username`    VARCHAR(50)           DEFAULT NULL   COMMENT '登录时输入的用户名',
  `login_type`  VARCHAR(20)           DEFAULT NULL   COMMENT '类型：LOGIN-登录 / LOGOUT-登出 / REFRESH-刷新令牌',
  `status`      VARCHAR(20)  NOT NULL                COMMENT '结果：SUCCESS-成功 / FAIL-失败',
  `fail_reason` VARCHAR(200)          DEFAULT NULL   COMMENT '失败原因（账号不存在/密码错误/验证码错误/账号封禁/锁定）',
  `ip`          VARCHAR(50)           DEFAULT NULL   COMMENT '客户端 IP',
  `user_agent`  VARCHAR(255)          DEFAULT NULL   COMMENT '浏览器 UA',
  `login_time`  DATETIME     NOT NULL                COMMENT '发生时间',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `login_time`),
  KEY `idx_username` (`username`),
  KEY `idx_status_time` (`status`, `login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='登录日志表（只增不改）';


-- -----------------------------------------------------------------------------
-- 3. sys_dict —— 数据字典（医院、科室、投诉类型等下拉项，避免前端硬编码）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_dict` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '字典项 ID',
  `dict_type`  VARCHAR(50)  NOT NULL                COMMENT '字典类型：HOSPITAL-医院 / DEPARTMENT-科室 / SERVICE_TYPE-陪诊服务类型 / COMPLAINT_TYPE-投诉类型',
  `dict_code`  VARCHAR(50)  NOT NULL                COMMENT '字典编码（类型内唯一）',
  `dict_label` VARCHAR(100) NOT NULL                COMMENT '显示文本',
  `dict_value` VARCHAR(100)          DEFAULT NULL   COMMENT '附加值（如排序权重、附加说明）',
  `sort_no`    INT          NOT NULL DEFAULT 0      COMMENT '排序号，越小越靠前',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：0-停用 1-启用',
  `remark`     VARCHAR(255)          DEFAULT NULL   COMMENT '备注',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`    TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_code` (`dict_type`, `dict_code`),
  KEY `idx_type_sort` (`dict_type`, `sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='数据字典表';


-- -----------------------------------------------------------------------------
-- 4. sys_file —— 附件统一登记表（资质证件、打卡照片、投诉证据、头像）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_file` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录 ID',
  `file_id`      VARCHAR(64)  NOT NULL                COMMENT '对外暴露的文件标识（上传接口返回的 fileId）',
  `origin_name`  VARCHAR(255)          DEFAULT NULL   COMMENT '原始文件名（仅记录，不用于落盘路径）',
  `stored_name`  VARCHAR(255) NOT NULL                COMMENT '磁盘存储文件名（UUID 重命名，防路径穿越）',
  `url`          VARCHAR(255) NOT NULL                COMMENT '可访问 URL，如 /uploads/202609/xxx.jpg',
  `store_path`   VARCHAR(255)          DEFAULT NULL   COMMENT '磁盘相对路径 ./uploads/{yyyyMM}/{uuid}.{ext}',
  `size`         BIGINT                DEFAULT NULL   COMMENT '字节数',
  `ext`          VARCHAR(20)           DEFAULT NULL   COMMENT '扩展名：jpg / png / webp / pdf',
  `content_type` VARCHAR(100)          DEFAULT NULL   COMMENT '服务端探测到的真实 MIME（按文件头判定，不信任前端）',
  `biz_type`     VARCHAR(20)           DEFAULT NULL   COMMENT '业务类型：COMPANION_CERT-资质证件 / CHECKIN-打卡照片 / COMPLAINT-投诉证据 / AVATAR-头像 / REVIEW-评价图片',
  `biz_id`       BIGINT                DEFAULT NULL   COMMENT '业务主键 ID',
  `uploader_id`  BIGINT                DEFAULT NULL   COMMENT '上传人用户 ID',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_id` (`file_id`),
  KEY `idx_biz` (`biz_type`, `biz_id`),
  KEY `idx_uploader` (`uploader_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='附件统一登记表';


-- -----------------------------------------------------------------------------
-- 5. admin_oper_log —— 管理员操作日志（M9 硬性约束：每次写操作必须留痕）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `admin_oper_log` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志 ID',
  `operator_id`    BIGINT       NOT NULL                COMMENT '操作人用户 ID（管理员）',
  `operator_name`  VARCHAR(50)           DEFAULT NULL   COMMENT '操作人姓名快照',
  `oper_type`      VARCHAR(30)  NOT NULL                COMMENT '操作类型：AUDIT_COMPANION-审核资质 / DISABLE_USER-封禁 / ENABLE_USER-解封 / RESET_PASSWORD-重置密码 / ARBITRATE_ORDER-订单纠纷 / HANDLE_COMPLAINT-处理投诉 / PUBLISH_NOTICE-发布公告',
  `target_type`    VARCHAR(20)           DEFAULT NULL   COMMENT '目标类型：USER-用户 / ORDER-订单 / COMPANION-陪诊员 / COMPLAINT-投诉',
  `target_id`      BIGINT                DEFAULT NULL   COMMENT '目标主键 ID',
  `target_desc`    VARCHAR(200)          DEFAULT NULL   COMMENT '目标描述（如订单号 NL20260915000001）',
  `before_status`  VARCHAR(30)           DEFAULT NULL   COMMENT '变更前状态',
  `after_status`   VARCHAR(30)           DEFAULT NULL   COMMENT '变更后状态',
  `remark`         VARCHAR(500)          DEFAULT NULL   COMMENT '操作备注 / 原因',
  `request_url`    VARCHAR(200)          DEFAULT NULL   COMMENT '请求路径',
  `request_method` VARCHAR(10)           DEFAULT NULL   COMMENT '请求方法 GET/POST/PUT/DELETE',
  `ip`             VARCHAR(50)           DEFAULT NULL   COMMENT '操作 IP',
  `oper_time`      DATETIME     NOT NULL                COMMENT '操作时间',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`        TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除（代码层不提供删改接口）',
  PRIMARY KEY (`id`),
  KEY `idx_operator_time` (`operator_id`, `oper_time`),
  KEY `idx_oper_type_time` (`oper_type`, `oper_time`),
  KEY `idx_target` (`target_type`, `target_id`),
  KEY `idx_oper_time` (`oper_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='管理员操作日志表（只增不改不删）';


-- =============================================================================
-- 二、老人档案与陪诊员资质域
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 6. elder_profile —— 老人档案（**身份证号密文存储**）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `elder_profile` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '档案 ID',
  `user_id`           BIGINT                DEFAULT NULL   COMMENT '关联的老人登录账号 ID；由家属代建的档案可为空',
  `name`              VARCHAR(50)  NOT NULL                COMMENT '姓名',
  `gender`            VARCHAR(10)  NOT NULL                COMMENT '性别：MALE-男 / FEMALE-女',
  `birth_date`        DATE         NOT NULL                COMMENT '出生日期（年龄由后端计算，不落库，避免年度失效）',
  `id_card`           VARCHAR(255)          DEFAULT NULL   COMMENT '身份证号（**AES 密文**），接口返回脱敏串，日志禁止打印',
  `phone`             VARCHAR(20)           DEFAULT NULL   COMMENT '联系电话，返回脱敏',
  `address`           VARCHAR(200)          DEFAULT NULL   COMMENT '常用地址（文字地址，一期不做地图导航），返回门牌号打码',
  `emergency_contact` VARCHAR(50)           DEFAULT NULL   COMMENT '紧急联系人姓名',
  `emergency_phone`   VARCHAR(20)           DEFAULT NULL   COMMENT '紧急联系人电话，返回脱敏',
  `medical_history`   VARCHAR(500)          DEFAULT NULL   COMMENT '病史备注（**仅记录，系统不做诊断、不给用药建议**）',
  `allergy_history`   VARCHAR(500)          DEFAULT NULL   COMMENT '过敏史备注（仅记录）',
  `mobility_level`    VARCHAR(20)           DEFAULT NULL   COMMENT '行动能力：SELF-自理 / ASSIST-需搀扶 / WHEELCHAIR-轮椅，用于匹配陪诊员',
  `favorite_hospital` VARCHAR(100)          DEFAULT NULL   COMMENT '常去医院',
  `bind_status`       VARCHAR(20)  NOT NULL DEFAULT 'UNBOUND' COMMENT '绑定状态：BOUND-已绑定家属 / UNBOUND-未绑定',
  `create_by`         BIGINT                DEFAULT NULL   COMMENT '建档人（家属）用户 ID',
  `remark`            VARCHAR(255)          DEFAULT NULL   COMMENT '备注',
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`           TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除（历史订单仍关联）',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_bind_status` (`bind_status`),
  KEY `idx_create_by` (`create_by`),
  KEY `idx_name` (`name`),
  KEY `idx_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='老人档案表';


-- -----------------------------------------------------------------------------
-- 7. family_elder_relation —— 家属与老人绑定关系
--   业务约束：一个老人同时只允许被一位主要家属绑定（并发场景在 Service 层加锁校验，
--   MySQL 不支持部分唯一索引，故未做 DB 层近似约束）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `family_elder_relation` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '关系 ID',
  `family_id`  BIGINT       NOT NULL                COMMENT '家属用户 ID',
  `elder_id`   BIGINT       NOT NULL                COMMENT '老人档案 ID',
  `relation`   VARCHAR(20)  NOT NULL                COMMENT '与老人关系：SON-儿子 / DAUGHTER-女儿 / RELATIVE-亲属 / OTHER-其他',
  `bind_type`  VARCHAR(20)           DEFAULT NULL   COMMENT '绑定方式：PHONE-按手机号 / INVITE_CODE-按邀请码',
  `is_default` TINYINT      NOT NULL DEFAULT 0      COMMENT '是否主要联系人：0-否 1-是',
  `status`     VARCHAR(20)  NOT NULL DEFAULT 'BOUND' COMMENT '关系状态：BOUND-已绑定 / UNBOUND-已解绑',
  `bind_time`  DATETIME     NOT NULL                COMMENT '绑定时间',
  `unbind_time` DATETIME             DEFAULT NULL   COMMENT '解绑时间',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`    TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family_elder` (`family_id`, `elder_id`),
  KEY `idx_elder_status` (`elder_id`, `status`),
  KEY `idx_family_status` (`family_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='家属-老人绑定关系表';


-- -----------------------------------------------------------------------------
-- 8. companion_audit_record —— 陪诊员资质申请记录（每次提交一条，保留审核历史）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `companion_audit_record` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申请 ID',
  `applicant_user_id` BIGINT       NOT NULL                COMMENT '申请人用户 ID',
  `real_name`         VARCHAR(50)  NOT NULL                COMMENT '真实姓名',
  `id_card`           VARCHAR(255)          DEFAULT NULL   COMMENT '身份证号（**AES 密文**）',
  `service_area`      VARCHAR(100) NOT NULL                COMMENT '服务区域，如「海口市美兰区」',
  `available_time`    VARCHAR(100) NOT NULL                COMMENT '可服务时段，如「周一至周五 08:00-18:00」',
  `certificates`      JSON                  DEFAULT NULL   COMMENT '证件材料 [{name,url}]，至少 1 项',
  `apply_remark`      VARCHAR(200)          DEFAULT NULL   COMMENT '申请补充说明',
  `audit_status`      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '审核状态：PENDING-待审核 / APPROVED-已通过 / REJECTED-已驳回',
  `reject_reason`     VARCHAR(200)          DEFAULT NULL   COMMENT '驳回原因（驳回时必填，5-200 字）',
  `audit_admin_id`    BIGINT                DEFAULT NULL   COMMENT '审核管理员用户 ID',
  `audit_remark`      VARCHAR(200)          DEFAULT NULL   COMMENT '管理员内部备注',
  `submit_time`       DATETIME     NOT NULL                COMMENT '提交时间',
  `audit_time`        DATETIME              DEFAULT NULL   COMMENT '审核时间',
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`           TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_applicant_status` (`applicant_user_id`, `audit_status`),
  KEY `idx_status_submit` (`audit_status`, `submit_time`),
  KEY `idx_real_name` (`real_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='陪诊员资质申请记录表';


-- -----------------------------------------------------------------------------
-- 9. companion_profile —— 陪诊员业务资料（审核通过后才有意义；评分/接单数在此聚合）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `companion_profile` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '资料 ID',
  `user_id`            BIGINT       NOT NULL                COMMENT '关联用户 ID（唯一，一个账号一份资料）',
  `real_name`          VARCHAR(50)  NOT NULL                COMMENT '真实姓名，返回脱敏',
  `id_card`            VARCHAR(255)          DEFAULT NULL   COMMENT '身份证号（**AES 密文**）',
  `gender`             VARCHAR(10)           DEFAULT NULL   COMMENT '性别：MALE / FEMALE',
  `birth_date`         DATE                  DEFAULT NULL   COMMENT '出生日期',
  `phone`              VARCHAR(20)           DEFAULT NULL   COMMENT '联系电话，返回脱敏',
  `service_area`       VARCHAR(100) NOT NULL                COMMENT '服务区域',
  `available_time`     VARCHAR(100) NOT NULL                COMMENT '可服务时段',
  `introduction`       VARCHAR(500)          DEFAULT NULL   COMMENT '个人简介 / 服务说明',
  `certificate_no`     VARCHAR(100)          DEFAULT NULL   COMMENT '资质证书编号',
  `health_cert_expire` DATE                  DEFAULT NULL   COMMENT '健康证有效期，过期自动置为不可接单',
  `audit_status`       VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '资质状态：PENDING-待审核 / APPROVED-已通过 / REJECTED-已驳回',
  `reject_reason`      VARCHAR(200)          DEFAULT NULL   COMMENT '最近一次驳回原因',
  `audit_admin_id`     BIGINT                DEFAULT NULL   COMMENT '审核管理员用户 ID',
  `audit_time`         DATETIME              DEFAULT NULL   COMMENT '审核时间',
  `work_status`        VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE' COMMENT '接单状态：AVAILABLE-可接单 / REST-休息中',
  `score`              DECIMAL(3,2) NOT NULL DEFAULT 0.00   COMMENT '平均评分（0.00-5.00），由评价聚合后冗余更新，避免实时聚合',
  `review_count`       INT          NOT NULL DEFAULT 0      COMMENT '有效评价数',
  `order_count`        INT          NOT NULL DEFAULT 0      COMMENT '累计已完成订单数（仅 COMPLETED/REVIEWED 计入）',
  `accept_count`       INT          NOT NULL DEFAULT 0      COMMENT '累计接单数（含进行中）',
  `remark`             VARCHAR(255)          DEFAULT NULL   COMMENT '备注',
  `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`            TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  KEY `idx_audit_work` (`audit_status`, `work_status`),
  KEY `idx_service_area` (`service_area`),
  KEY `idx_score` (`score`),
  KEY `idx_order_count` (`order_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='陪诊员业务资料表';


-- =============================================================================
-- 三、陪诊订单与执行域（系统核心）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 10. companion_order —— 陪诊订单主表（含乐观锁 version，防并发超卖接单）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `companion_order` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单 ID',
  `order_no`         VARCHAR(32)   NOT NULL                COMMENT '订单号，格式 NL + yyyyMMdd + 6 位序列，如 NL20260915000001',
  `family_id`        BIGINT        NOT NULL                COMMENT '下单家属用户 ID',
  `elder_id`         BIGINT        NOT NULL                COMMENT '就诊老人档案 ID',
  `companion_id`     BIGINT                 DEFAULT NULL   COMMENT '接单陪诊员用户 ID，未接单为 NULL',
  `hospital`         VARCHAR(100)  NOT NULL                COMMENT '医院名称',
  `department`       VARCHAR(50)   NOT NULL                COMMENT '就诊科室',
  `visit_time`       DATETIME      NOT NULL                COMMENT '就诊时间，必须晚于下单时间',
  `address`          VARCHAR(200)  NOT NULL                COMMENT '医院地址（**文字地址，一期不做地图导航**）',
  `longitude`        DECIMAL(10,6)          DEFAULT NULL   COMMENT '订单地址经度，用于打卡距离校验（不渲染地图）',
  `latitude`         DECIMAL(10,6)          DEFAULT NULL   COMMENT '订单地址纬度',
  `remark`           VARCHAR(500)           DEFAULT NULL   COMMENT '家属备注（如「老人听力不好，请大声沟通」）',
  `status`           VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '订单状态：PENDING-待接单 / ACCEPTED-已接单 / IN_SERVICE-服务中 / COMPLETED-已完成 / REVIEWED-已评价 / CANCELLED-已取消',
  `fee`              DECIMAL(10,2) NOT NULL DEFAULT 0.00   COMMENT '服务费（元），一期不做在线支付，仅线上记账 + 线下结算',
  `actual_fee`       DECIMAL(10,2)          DEFAULT NULL   COMMENT '实际结算金额（线下结算回填）',
  `payment_status`   VARCHAR(20)   NOT NULL DEFAULT 'UNPAID' COMMENT '结算状态：UNPAID-未结算 / SETTLED-已结算',
  `service_summary`  VARCHAR(500)           DEFAULT NULL   COMMENT '服务小结（**只记录过程，禁止出现诊断与用药建议**）',
  `service_photos`   JSON                   DEFAULT NULL   COMMENT '服务现场/取药凭证照片 URL 数组',
  `accept_time`      DATETIME               DEFAULT NULL   COMMENT '接单时间',
  `start_time`       DATETIME               DEFAULT NULL   COMMENT '开始服务时间',
  `finish_time`      DATETIME               DEFAULT NULL   COMMENT '完成时间',
  `cancel_time`      DATETIME               DEFAULT NULL   COMMENT '取消时间',
  `cancel_reason`    VARCHAR(200)           DEFAULT NULL   COMMENT '取消/纠纷原因',
  `cancel_by`        BIGINT                 DEFAULT NULL   COMMENT '取消操作人用户 ID',
  `arbitrate_flag`   TINYINT       NOT NULL DEFAULT 0      COMMENT '是否被管理员强制处理：0-否 1-是',
  `arbitrate_result` VARCHAR(500)           DEFAULT NULL   COMMENT '管理员纠纷处理结果说明',
  `version`          INT           NOT NULL DEFAULT 0      COMMENT '乐观锁版本号（**接单防超卖**，MyBatis-Plus @Version）',
  `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_status_create` (`status`, `create_time`),
  -- 订单大厅：status = 'PENDING' AND visit_time > NOW() ORDER BY visit_time
  KEY `idx_status_visit` (`status`, `visit_time`),
  KEY `idx_family_create` (`family_id`, `create_time`),
  KEY `idx_companion_status` (`companion_id`, `status`),
  KEY `idx_elder_id` (`elder_id`),
  KEY `idx_visit_time` (`visit_time`),
  KEY `idx_payment_status` (`payment_status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='陪诊订单主表';


-- -----------------------------------------------------------------------------
-- 11. order_status_log —— 订单状态流转日志（状态机审计 / 时间线 / 纠纷取证）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_status_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志 ID',
  `order_id`      BIGINT       NOT NULL                COMMENT '订单 ID',
  `from_status`   VARCHAR(20)           DEFAULT NULL   COMMENT '变更前状态，首次下单为 NULL',
  `to_status`     VARCHAR(20)  NOT NULL                COMMENT '变更后状态',
  `operator_id`   BIGINT                DEFAULT NULL   COMMENT '操作人用户 ID，系统自动流转为 NULL',
  `operator_name` VARCHAR(50)           DEFAULT NULL   COMMENT '操作人姓名快照',
  `operator_role` VARCHAR(20)           DEFAULT NULL   COMMENT '操作人角色：ELDER / FAMILY / COMPANION / ADMIN / SYSTEM',
  `remark`        VARCHAR(500)          DEFAULT NULL   COMMENT '备注（如「管理员强制变更」）',
  `is_force`      TINYINT      NOT NULL DEFAULT 0      COMMENT '是否管理员强制变更：0-否 1-是',
  `operate_time`  DATETIME     NOT NULL                COMMENT '发生时间',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_order_time` (`order_id`, `operate_time`),
  KEY `idx_to_status` (`to_status`),
  KEY `idx_operator` (`operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单状态流转日志表（只增不改）';


-- -----------------------------------------------------------------------------
-- 12. order_reject_log —— 陪诊员拒单记录（**不改变订单状态**，仅让该陪诊员不再看到该单）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_reject_log` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录 ID',
  `order_id`     BIGINT       NOT NULL                COMMENT '订单 ID',
  `companion_id` BIGINT       NOT NULL                COMMENT '拒单陪诊员用户 ID',
  `reason`       VARCHAR(200)          DEFAULT NULL   COMMENT '拒单原因',
  `reject_time`  DATETIME     NOT NULL                COMMENT '拒单时间',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_companion` (`order_id`, `companion_id`),
  KEY `idx_companion_id` (`companion_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='陪诊员拒单记录表';


-- -----------------------------------------------------------------------------
-- 13. order_checkin —— 打卡记录（唯一索引 uk_order_node 保证同一节点只打一次）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_checkin` (
  `id`           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '打卡记录 ID',
  `order_id`     BIGINT        NOT NULL                COMMENT '订单 ID',
  `companion_id` BIGINT        NOT NULL                COMMENT '打卡陪诊员用户 ID',
  `node`         VARCHAR(20)   NOT NULL                COMMENT '打卡节点：DEPART-出发 / ARRIVE-到院 / IN_CONSULT-就诊中 / TAKE_MEDICINE-取药 / LEAVE-离院 / FINISH-完成',
  `node_sort`    TINYINT       NOT NULL                COMMENT '节点顺序值 1-6，用于校验「可跳过不可回退」',
  `longitude`    DECIMAL(10,6) NOT NULL                COMMENT '打卡经度',
  `latitude`     DECIMAL(10,6) NOT NULL                COMMENT '打卡纬度',
  `address`      VARCHAR(200)           DEFAULT NULL   COMMENT '打卡位置文字描述（一期由前端传入或留空，不做逆地理编码）',
  `distance`     INT                    DEFAULT NULL   COMMENT '与订单地址的直线距离（米），Haversine 计算',
  `is_abnormal`  TINYINT       NOT NULL DEFAULT 0      COMMENT '是否异常打卡（超出阈值）：0-正常 1-异常',
  `photos`       JSON                   DEFAULT NULL   COMMENT '现场照片 URL 数组，最多 6 张',
  `remark`       VARCHAR(200)           DEFAULT NULL   COMMENT '备注',
  `checkin_time` DATETIME      NOT NULL                COMMENT '打卡时间',
  `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_node` (`order_id`, `node`),
  KEY `idx_order_time` (`order_id`, `checkin_time`),
  KEY `idx_companion_id` (`companion_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='陪诊打卡记录表';


-- -----------------------------------------------------------------------------
-- 14. companion_track —— 陪诊轨迹点（一期不渲染地图，用于文字摘要 / 申诉取证）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `companion_track` (
  `id`           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '轨迹点 ID',
  `order_id`     BIGINT        NOT NULL                COMMENT '订单 ID',
  `companion_id` BIGINT        NOT NULL                COMMENT '陪诊员用户 ID',
  `node`         VARCHAR(20)            DEFAULT NULL   COMMENT '关联打卡节点，非打卡产生的点为 NULL',
  `longitude`    DECIMAL(10,6) NOT NULL                COMMENT '经度',
  `latitude`     DECIMAL(10,6) NOT NULL                COMMENT '纬度',
  `accuracy`     DECIMAL(6,2)           DEFAULT NULL   COMMENT '定位精度（米），用于剔除漂移点',
  `speed`        DECIMAL(6,2)           DEFAULT NULL   COMMENT '速度（米/秒），用于判断是否在移动',
  `record_time`  DATETIME      NOT NULL                COMMENT '定位记录时间',
  `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_order_time` (`order_id`, `record_time`),
  KEY `idx_companion_id` (`companion_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='陪诊轨迹点表';


-- =============================================================================
-- 四、用药管理域（合规红线密集区：只做记录与提醒）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 15. medicine_dict —— 药品字典（**只存通用信息，不存剂量建议/适应症判断/替代药**）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `medicine_dict` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '药品 ID',
  `name`         VARCHAR(100) NOT NULL                COMMENT '通用名，如「苯磺酸氨氯地平片」',
  `trade_name`   VARCHAR(100)          DEFAULT NULL   COMMENT '商品名，如「络活喜」，可为空',
  `specification` VARCHAR(100)         DEFAULT NULL   COMMENT '规格，如「5mg × 28 片」',
  `dosage_form`  VARCHAR(20)  NOT NULL                COMMENT '剂型：TABLET-片剂 / CAPSULE-胶囊 / INJECTION-注射剂 / LIQUID-口服液 / OTHER-其他',
  `common_usage` VARCHAR(500)          DEFAULT NULL   COMMENT '通用服用说明（**仅「口服，具体用法用量请遵医嘱」这类通用表述**）',
  `precautions`  VARCHAR(500)          DEFAULT NULL   COMMENT '注意事项（如「可能引起嗜睡」「需避光保存」）',
  `storage`      VARCHAR(200)          DEFAULT NULL   COMMENT '储存条件',
  `manufacturer` VARCHAR(100)          DEFAULT NULL   COMMENT '生产厂家',
  `approval_no`  VARCHAR(50)           DEFAULT NULL   COMMENT '批准文号',
  `otc_type`     VARCHAR(20)           DEFAULT NULL   COMMENT '处方类别：OTC-非处方药 / PRESCRIPTION-处方药',
  `is_common`    TINYINT      NOT NULL DEFAULT 0      COMMENT '是否常用药（前端优先展示）：0-否 1-是',
  `disclaimer`   VARCHAR(500) NOT NULL                COMMENT '免责声明（**接口必返、前端必展示**）',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_name` (`name`),
  KEY `idx_trade_name` (`trade_name`),
  KEY `idx_dosage_form` (`dosage_form`),
  KEY `idx_is_common` (`is_common`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='药品字典表（仅通用信息，不含用药建议）';


-- -----------------------------------------------------------------------------
-- 16. medication_plan —— 用药计划（用量由家属按医嘱填写，系统不生成、不校验合理性）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `medication_plan` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '计划 ID',
  `elder_id`      BIGINT       NOT NULL                COMMENT '老人档案 ID',
  `medicine_id`   BIGINT       NOT NULL                COMMENT '药品 ID',
  `medicine_name` VARCHAR(100) NOT NULL                COMMENT '药品通用名快照（药品字典变更后历史计划仍可读）',
  `dosage`        VARCHAR(50)  NOT NULL                COMMENT '单次用量，如「1 片」（**家属按医嘱填写**）',
  `frequency`     TINYINT      NOT NULL                COMMENT '每日次数 1-4',
  `time_points`   VARCHAR(100) NOT NULL                COMMENT '服药时间点 JSON 数组，如 ["08:00","12:00","18:00"]，长度须等于 frequency',
  `start_date`    DATE         NOT NULL                COMMENT '开始日期',
  `end_date`      DATE                  DEFAULT NULL   COMMENT '结束日期，NULL 表示长期用药',
  `meal_relation` VARCHAR(20)  NOT NULL                COMMENT '与饭点关系：BEFORE_MEAL-饭前 / AFTER_MEAL-饭后 / ANY-不限',
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '计划状态：ACTIVE-进行中 / DISABLED-已停用（**不物理删除，保留服药历史**）',
  `remark`        VARCHAR(200)          DEFAULT NULL   COMMENT '备注（如「医生让吃两周」）',
  `created_by`    BIGINT       NOT NULL                COMMENT '创建人（家属）用户 ID',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_elder_status` (`elder_id`, `status`),
  KEY `idx_medicine_id` (`medicine_id`),
  KEY `idx_start_end` (`start_date`, `end_date`),
  KEY `idx_created_by` (`created_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用药计划表';


-- -----------------------------------------------------------------------------
-- 17. medication_task —— 每日服药任务（定时任务生成）
--   唯一索引 uk_plan_time 保证定时任务重复触发不产生重复行（幂等）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `medication_task` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '任务 ID',
  `plan_id`        BIGINT       NOT NULL                COMMENT '所属用药计划 ID',
  `elder_id`       BIGINT       NOT NULL                COMMENT '老人档案 ID（冗余，避免查任务时再关联计划表）',
  `medicine_id`    BIGINT       NOT NULL                COMMENT '药品 ID（冗余）',
  `medicine_name`  VARCHAR(100) NOT NULL                COMMENT '药品名快照',
  `dosage`         VARCHAR(50)           DEFAULT NULL   COMMENT '单次用量快照',
  `meal_relation`  VARCHAR(20)           DEFAULT NULL   COMMENT '饭点关系快照（历史任务不受计划修改影响）',
  `plan_date`      DATE         NOT NULL                COMMENT '计划日期（按天聚合用）',
  `plan_time`      DATETIME     NOT NULL                COMMENT '计划服药时间（精确到分钟）',
  `status`         VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING-待服用 / TAKEN-已服用 / MISSED-漏服',
  `was_missed`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否曾判定漏服后补记：0-否 1-是（统计漏服率用）',
  `confirm_time`   DATETIME              DEFAULT NULL   COMMENT '实际确认/服药时间',
  `confirm_by`     BIGINT                DEFAULT NULL   COMMENT '确认人用户 ID',
  `confirm_remark` VARCHAR(200)          DEFAULT NULL   COMMENT '确认备注（如「今天外出，晚了 1 小时」）',
  `notify_sent`    TINYINT      NOT NULL DEFAULT 0      COMMENT '漏服提醒是否已推送：0-否 1-是（防重复推送）',
  `notify_time`    DATETIME              DEFAULT NULL   COMMENT '提醒推送时间',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`        TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_time` (`plan_id`, `plan_time`),
  KEY `idx_elder_date_status` (`elder_id`, `plan_date`, `status`),
  KEY `idx_status_plan_time` (`status`, `plan_time`),
  KEY `idx_plan_id` (`plan_id`),
  KEY `idx_plan_date` (`plan_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='每日服药任务表（定时任务生成，唯一索引保证幂等）';


-- =============================================================================
-- 五、评价、投诉与消息域
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 18. order_review —— 订单评价（唯一索引 uk_order_id 防重复评价；is_valid 供管理员判定无效）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_review` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '评价 ID',
  `order_id`        BIGINT       NOT NULL                COMMENT '订单 ID（唯一，一单一评）',
  `order_no`        VARCHAR(32)           DEFAULT NULL   COMMENT '订单号快照',
  `family_id`       BIGINT       NOT NULL                COMMENT '评价人（下单家属）用户 ID',
  `elder_id`        BIGINT                DEFAULT NULL   COMMENT '被服务老人档案 ID',
  `companion_id`    BIGINT       NOT NULL                COMMENT '被评价陪诊员用户 ID',
  `score`           TINYINT      NOT NULL                COMMENT '评分 1-5 星',
  `tags`            VARCHAR(200)          DEFAULT NULL   COMMENT '评价标签 JSON 数组，如 ["准时","耐心"]，最多 5 个',
  `content`         VARCHAR(500)          DEFAULT NULL   COMMENT '评价文字，5-500 字',
  `is_anonymous`    TINYINT      NOT NULL DEFAULT 0      COMMENT '是否匿名：0-否 1-是（匿名时列表与详情均不暴露家属姓名）',
  `companion_reply` VARCHAR(500)          DEFAULT NULL   COMMENT '陪诊员回复',
  `reply_time`      DATETIME              DEFAULT NULL   COMMENT '回复时间',
  `is_valid`        TINYINT      NOT NULL DEFAULT 1      COMMENT '是否有效评价：0-管理员判定无效（不计入评分聚合）1-有效',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`         TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_id` (`order_id`),
  KEY `idx_companion_valid` (`companion_id`, `is_valid`),
  KEY `idx_family_id` (`family_id`),
  KEY `idx_score` (`score`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单评价表';


-- -----------------------------------------------------------------------------
-- 19. complaint —— 投诉（投诉人/被投诉人由订单关系推导，不由前端传入）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `complaint` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '投诉 ID',
  `order_id`         BIGINT        NOT NULL                COMMENT '关联订单 ID',
  `order_no`         VARCHAR(32)            DEFAULT NULL   COMMENT '订单号快照',
  `complainant_id`   BIGINT        NOT NULL                COMMENT '投诉人用户 ID',
  `complainant_role` VARCHAR(20)            DEFAULT NULL   COMMENT '投诉人角色',
  `target_user_id`   BIGINT        NOT NULL                COMMENT '被投诉人用户 ID',
  `target_role`      VARCHAR(20)            DEFAULT NULL   COMMENT '被投诉人角色',
  `type`             VARCHAR(20)   NOT NULL                COMMENT '投诉类型：LATE-迟到/未按时到达 / ATTITUDE-服务态度差 / INCOMPLETE-服务未完成 / FEE_DISPUTE-费用纠纷 / PRIVACY-隐私泄露 / OTHER-其他',
  `content`          VARCHAR(1000) NOT NULL                COMMENT '投诉内容，10-1000 字',
  `evidence`         JSON                   DEFAULT NULL   COMMENT '证据材料 URL 数组，最多 6 张',
  `status`           VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '处理状态：PENDING-待处理 / PROCESSING-处理中 / RESOLVED-已结案 / REJECTED-已驳回（**只可正向流转**）',
  `handle_admin_id`  BIGINT                 DEFAULT NULL   COMMENT '处理管理员用户 ID',
  `handle_result`    VARCHAR(500)           DEFAULT NULL   COMMENT '处理结果说明（10-500 字）',
  `penalty_to_target` TINYINT      NOT NULL DEFAULT 0      COMMENT '是否对被投诉人计违规：0-否 1-是',
  `handle_time`      DATETIME               DEFAULT NULL   COMMENT '处理时间',
  `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_status_create` (`status`, `create_time`),
  KEY `idx_complainant` (`complainant_id`),
  KEY `idx_target_user` (`target_user_id`),
  KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='投诉表';


-- -----------------------------------------------------------------------------
-- 20. internal_message —— 站内信（一期替代 IM，仅「系统 → 用户」单向通知）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `internal_message` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息 ID',
  `receiver_id`      BIGINT       NOT NULL                COMMENT '接收人用户 ID',
  `sender_id`        BIGINT                DEFAULT NULL   COMMENT '发送人用户 ID，NULL 表示系统发送',
  `type`             VARCHAR(30)  NOT NULL                COMMENT '消息类型：ORDER_CREATED / ORDER_ACCEPTED / ORDER_PROGRESS / ORDER_COMPLETED / ORDER_CANCELLED / AUDIT_RESULT / MEDICATION_REMIND / COMPLAINT_HANDLED / SYSTEM_NOTICE',
  `title`            VARCHAR(50)  NOT NULL                COMMENT '标题，最长 50 字',
  `content`          VARCHAR(500) NOT NULL                COMMENT '正文，最长 500 字，**由后端模板填充并脱敏，前端禁止拼文案**',
  `biz_type`         VARCHAR(20)           DEFAULT NULL   COMMENT '关联业务类型：ORDER-订单 / AUDIT-资质 / MEDICATION-用药 / COMPLAINT-投诉 / SYSTEM-系统',
  `biz_id`           BIGINT                DEFAULT NULL   COMMENT '关联业务 ID，用于点击跳转',
  `link_url`         VARCHAR(200)          DEFAULT NULL   COMMENT '前端跳转路径，如 /family?orderId=1001',
  `is_read`          TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读：0-未读 1-已读',
  `read_time`        DATETIME              DEFAULT NULL   COMMENT '阅读时间',
  `receiver_deleted` TINYINT      NOT NULL DEFAULT 0      COMMENT '接收人侧删除标记：0-正常 1-已删除（仅影响接收人视图）',
  `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
  `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_receiver_read` (`receiver_id`, `is_read`, `create_time`),
  KEY `idx_receiver_type` (`receiver_id`, `type`),
  KEY `idx_biz` (`biz_type`, `biz_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='站内信表';


SET FOREIGN_KEY_CHECKS = 1;
