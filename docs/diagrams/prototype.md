# 原型图（真实页面截图）

> 收尾标准要求用真实页面截图而非手绘。以下截图由 `frontend/e2e/capture-screenshots.mjs`
> 对着运行中的系统采集（Playwright + 本机 Chrome，1440×900，全页截图），
> 原图位于 `docs/reports/screenshots/`。

## 登录页

![登录页](../reports/screenshots/login.png)

四角色共用的登录入口，带图形验证码与演示账号预填。

## 家属端

![家属首页](../reports/screenshots/family-home.png)

家属工作台：待办提醒、老人卡片与快捷入口。

![老人档案管理](../reports/screenshots/family-elder.png)

家属为老人建档与绑定（代操作关系的来源，M3）。

![家属用药管理](../reports/screenshots/family-medication.png)

家属侧的用药计划与每日任务视图（M6，月历组件）。

![订单列表](../reports/screenshots/family-order-list.png)

家属名下的陪诊订单列表（M4 状态机贯穿全程）。

![订单详情](../reports/screenshots/family-order-detail.png)

订单详情：状态时间线、服务记录与费用信息。

## 老人端

![老人首页](../reports/screenshots/elder-home.png)

老人端大字号首页（适老化，Mobile 形态生效老人模式）。

![老人用药视图](../reports/screenshots/elder-medication.png)

老人视角的今日用药（只读，写操作由家属代完成）。

## 陪诊员端

![订单大厅](../reports/screenshots/companion-hall.png)

待接单订单大厅（接单走乐观锁防超卖，M4 验收点）。

![陪诊员订单](../reports/screenshots/companion-order.png)

陪诊员名下订单与执行入口。

![陪诊执行打卡](../reports/screenshots/companion-execute.png)

六节点打卡（出发/到院/就诊中/取药/离院/完成，M5）。

![收入记账](../reports/screenshots/companion-income.png)

陪诊员收入与费用明细记账入口（ADR-0009）。

## 管理端

![管理首页](../reports/screenshots/admin-dashboard.png)

管理端统计看板。

![陪诊员资质审核](../reports/screenshots/admin-companion-audit.png)

资质审核工作台（审核通过才升级为 COMPANION）。

![用户管理](../reports/screenshots/admin-user.png)

四角色用户管理列表。
