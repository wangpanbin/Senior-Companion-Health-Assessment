# 0008. 移动专属页面清单 · Mobile-only Pages

| | |
|---|---|
| 状态 | 提议 (Proposed) |
| 日期 | 2026-09-18 |
| 关联 | ADR-0007 · Q7=Ⅱ 用户决策 |

## 背景

Q1=B 拍板了"F/E/C 全员适配电脑布局",但 Q7=Ⅱ 进一步按"真实使用场景"分级:

- FAMILY 全部页面做响应式(主战场)
- COMPANION 仅 hall / order-list / income 做响应式
- ELDER 仅 home / medication 做响应式

这意味着 ELDER / COMPANION 角色下还有部分页面在桌面端是**没有响应式实现的**。如果直接访问这些 URL,会出现两种情况:

1. 桌面端强行套 `NlDesktopShell` → view 内部仍按手机布局渲染,留白多但能看
2. 桌面端拒绝访问 → 跳转 `403` 错误页,语义不准

需要为这些页面在桌面端的行为做一个清晰的契约。

## 决策

引入 **`mobileOnly`** 路由 meta 字段:

- `meta.mobileOnly: true` 的路由,在 Desktop 形态下访问时,渲染一个**轻量提示页**(而非 view 本身)
- 提示页内容:"此功能更适合手机端使用,请用手机扫码或在移动设备上访问"
- 提示页提供:① 二维码占位(可后续接 URL → QR) ③ "继续查看" 按钮(放行,降级到 Mobile 形态)

落地方式:

- `router/routes.js` 给相关路由加 `meta.mobileOnly: true`
- `NlDesktopShell` 检测当前路由是否 mobileOnly,若是则渲染 `<NlMobileOnlyNotice>` 组件
- `<NlMobileOnlyNotice>` 是一个轻量占位,放在 `components/`

### Mobile-only 页面清单(本轮)

| 角色 | 路由 | view 文件 | 原因 |
|---|---|---|---|
| ELDER | `/elder/message` | `views/companion/message.vue` | 老人看消息用手机即可 |
| COMPANION | `/companion/entry` | `views/companion/entry.vue` | 资质入驻需拍照上传,电脑不实用 |
| COMPANION | `/companion/execute/:id` | `views/companion/execute.vue` | 实时打卡依赖 GPS,电脑无场景 |
| (通用) | `/profile` | `views/profile/index.vue` | 手机端"我的"页面不需要居中限宽(资料类,手机壳即可) |

> 上述 4 个路由在 `routes.js` 中加 `meta.mobileOnly: true`。
> 其它 ELDER / COMPANION / FAMILY 路由(共 17 个)按 ADR-0007 走"view 内响应式"路径,不进 mobile-only 清单。

## 选项

### A. 全员响应式(无 mobile-only)

- **优点**:语义最简单,view 全部走同一形态切换。
- **缺点**:Q7=Ⅱ 已拍板分级,这条违反用户决策;且 elder/profile、companion/execute 等低频场景投入产出比低。

### B. 按场景分级 + mobile-only 元数据 ← **本次选**

- **优点**:工作量聚焦在 FAMILY 主战场;mobile-only 路由有明确契约;未来如需扩展,只需加 meta 字段。
- **缺点**:新增 1 个 `<NlMobileOnlyNotice>` 组件 + router meta 检测逻辑。

### C. elder 端完全跳过(整体不响应式)

- **优点**:最省事。
- **缺点**:与 Q1=B "全员" 承诺冲突;elder/home / elder/medication 在桌面端会有空白页。

## 后果

### 正面

- 工作量从"全员响应式 28 view"收敛到"17 view 响应式 + 4 view 提示页 + 7 view 完全不动"
- 未来如果某些 mobile-only 路由要升级,只需移除 meta,无需重构 view
- `meta.mobileOnly` 字段是 1 行 metadata,无侵入

### 负面

- "继续查看" 按钮放行后,用户从 Desktop 降级到 Mobile 形态,体验有跳跃感(URL 不变,但布局变化)
- 需要在 `useDevice()` 之外再加 1 个 `<router-link>` / `useRouter()` 判断,稍增心智

### 后续可优化(不在本轮范围)

- "继续查看"按钮点击后,可以用 sessionStorage 临时存 `force-mobile=true`,刷新也保持 Mobile 形态
- 二维码占位后续可接 `qrcode` 库生成真实 URL → QR