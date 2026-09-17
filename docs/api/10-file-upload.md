# 10 · 通用文件上传

> **负责模块**：M5（打卡照片）/ M9（资质证件、投诉证据、头像）
> **对应代码**：`controller/common/FileController`、`service/impl/FileStorageServiceImpl`、`constant/FileBizType`、`vo/FileUploadVO`
> **全局约定**：`docs/api/README.md` §九「文件上传约定」（上限 / 允许类型 / 存储路径 / magic bytes 要求）

---

## 〇、本模块的安全边界（先读这一段）

1. **不接收 `bizId`**。客户端传来的 `bizId` 服务端无从校验真伪，直接写进 `sys_file.biz_id`
   就是脏数据（例如把别人的订单 id 填进来）。业务关联由**业务侧自己持有**本接口返回的 `url` 完成
   （如 `certificates[].url`、`complaint.evidence[]`）。本端点只负责「存」。
2. **`bizType` 是白名单**，不是自由字符串。白名单外的取值一律 `400`，并把允许取值列表回给调用方。
3. **文件类型只认文件头（magic bytes）**，不信任前端传来的 `Content-Type` 与文件扩展名
   —— 把 `Content-Type` 写成 `image/jpeg` 是 `curl` 一行的事。
4. **文件名一律 UUID 重命名**。原名回显在 `originName` 列（仅入库、不外泄），落盘名与对外 URL 都用 UUID，
   避免路径穿越与同名覆盖。
5. **老人账号（ELDER）不能上传**：本端点是写接口，`ElderReadOnlyInterceptor` 对 `/api/**` 的非 GET 请求
   默认拒绝 ELDER。因此**不需要**再写一道 ELDER 专属拦截。

---

## 一、业务类型白名单（`FileBizType`）

| 传值 | 中文 | 典型调用方 | 归属校验 |
|---|---|---|---|
| `COMPANION_CERT` | 资质证件 | 陪诊员申请资质 | 由业务侧持 `url` 关联，见 [02-elder-family.md](./02-elder-family.md) |
| `COMPLAINT` | 投诉证据 | 家属提交投诉 | 由业务侧持 `url` 关联，见 [06-review-complaint.md](./06-review-complaint.md) |
| `AVATAR` | 用户头像 | 全部角色 | 由业务侧持 `url` 关联 |

### 为什么 `CHECKIN` 与 `REVIEW` **不放行**

`sys_file.biz_type` 列注释定义了 5 个取值，本通用端点**刻意只放行其中 3 个**：

| 未放行的取值 | 原因 |
|---|---|
| `CHECKIN`（打卡照片） | 打卡照片有**专属入口** `POST /api/execution/{orderId}/photo`，那里会校验「须为本单陪诊员」，否则返回 `4003`。若通用端点也收 `CHECKIN`，任何人都能往任意订单塞打卡照片，**直接绕过归属校验**。 |
| `REVIEW`（评价图片） | 一期评价无图片字段，放开等于凭空造出一条无法关联的业务数据。 |

> 结论：**需要归属校验的文件走专属端点；没有归属校验需求的三类才走本通用端点。**

---

## 二、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | POST | `/api/file/upload` | FAMILY / COMPANION / ADMIN | 通用文件上传（multipart） |

---

## 1. 上传文件

`POST /api/file/upload`　权限：**FAMILY / COMPANION / ADMIN**（ELDER 被只读拦截器拒绝）

### 请求

`Content-Type: multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file` | File | ✓ | 文件本体。**字段名固定为 `file`**（`@RequestParam("file")`）。**不接收 `bizId`** |
| `bizType` | String | ✓ | 业务类型，白名单见 §一 |

```bash
curl -X POST http://127.0.0.1:8080/api/file/upload \
  -H "Authorization: Bearer <accessToken>" \
  -F "file=@cert.jpg" \
  -F "bizType=COMPANION_CERT"
```

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "fileId": "f_3b9c1d4e7a2f5081",
    "url": "/uploads/202609/9f8e7d6c5b4a39281706fedcba987654.jpg",
    "size": 204800
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `fileId` | String | 文件标识（`f_` + 16 位十六进制），业务表引用它 |
| `url` | String | 可访问 URL，前端可直接预览；业务侧保存它完成关联 |
| `size` | Long | 字节数 |

> 为什么只回这三个字段：`storedName` / `storePath` 是服务端落盘细节，
> 暴露出去等于把目录结构告诉别人。

### 错误场景

| code | HTTP | 场景 |
|---|---|---|
| `400` | 200 | 未选择文件（`file` 为空）；`bizType` 不在白名单（响应会列出允许取值）；文件类型不允许（仅支持 jpg / png / webp / pdf）；文件读取失败 |
| `400` | 200 | 文件超过 **10 MB** 单文件上限（`MaxUploadSizeExceededException` 经 `GlobalExceptionHandler` 翻译） |
| `500` | 200 | 文件落盘失败（`SYSTEM_ERROR`） |
| `403` | 403 | ELDER 调用（只读拦截器）；或未登录 / token 失效 |
| `400` | 200 | `multipart/form-data` 缺 `file` 部分（`MissingServletRequestPartException`）。⚠️ 该异常在**参数解析阶段**抛出，**早于 `@PreAuthorize`**，所以用「不带文件的上传请求」去断言 403 会得到 200 而误判 |

### 实现要点

- 上限与允许类型写在配置与全局约定里（`spring.servlet.multipart.max-file-size: 10MB`、
  `max-request-size: 20MB`），**不要在 Service 里重复写死阈值**。
- 落盘流程：**校验文件头 → UUID 重命名 → 落盘 → 落 `sys_file` 表**。
  识别 WebP 需要看到第 12 字节（`MIN_HEADER_BYTES = 12`）。
- 存储目录由 `nianglin.file.upload-dir` 控制（默认 `./uploads`），
  按 `{yyyyMM}` 分目录，避免单目录文件数膨胀。
- 上传成功后 `log.info` 只打 `bizType` / `uploaderId` / `fileId`，**不打原始文件名**
  （原名可能含真实姓名等个人信息）。

---

## 三、验收标准

- [ ] 用 `COMPANION_CERT` 上传一张 jpg → 返回 `fileId` / `url` / `size`，磁盘与 `sys_file` 均有记录
- [ ] `bizType` 传 `CHECKIN` 或 `CHECKOUT` 等白名单外取值 → `400`，且响应列出允许取值
- [ ] 把 `.txt` 改名为 `.jpg` 后上传 → `400`（magic bytes 判定失败）
- [ ] 上传 11 MB 文件 → `400`「文件超过 10 MB 上限」
- [ ] ELDER 账号调用 → `403`
- [ ] 文件大小 / 类型 / 上传人写入 `sys_file`，`url` 可直接访问

---

## 四、变更记录

| 版本 | 日期 | 变更内容 |
|---|---|---|
| v0.1.0 | 2026-09-17 | 首版：补录已实现但此前未进 `docs/api/` 的通用上传端点（端点、`bizType` 白名单、错误场景、验收标准） |
