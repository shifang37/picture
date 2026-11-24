# 内容审核与 AI 辅助

> 本页是 [README](../README.md) 的展开说明，覆盖机审初筛、文本审核与 DeepSeek 自动建议的开关、触发链路、成本与失败处理。

## 机审初筛（数据万象）

图片上传成功后，`picture.moderation.q` 消费者会按 id 回查图片并调用数据万象图片审核，把结论写入 `picture` 表的四个机审字段：

| 字段 | 含义 |
| --- | --- |
| `machineReviewStatus` | 0 正常、1 违规、2 疑似 |
| `machineReviewLabel` | 命中标签，主标签与子标签用 `/` 拼接 |
| `machineReviewScore` | 置信度 0-100（接口未返回时为 null） |
| `machineReviewTime` | 机审完成时间 |

### 开关与效力

```yaml
app:
  review:
    machine:
      enabled: ${MACHINE_REVIEW_ENABLED:false}
      mode: ${MACHINE_REVIEW_MODE:assist}
```

- **默认关闭**：关闭时消费者直接确认消息，不查库、不调用数据万象，上传与人工审核行为与接入前完全一致；
- 决策 D3：默认 `assist`，机审只作为辅助标记，观察误判率后再考虑自动处置。

| 模式 | 行为 |
| --- | --- |
| `assist`（默认） | 只记录机审结果，审核状态仍由人工决定 |
| `auto-reject` | 违规自动拒绝；正常和疑似仍走人工 |
| `auto` | 违规自动拒绝、正常自动通过；疑似走人工 |

### 并发与安全

- 写机审字段使用 `WHERE id = ? AND machineReviewStatus IS NULL`，重复投递或兜底任务并发时只处理一次；
- 自动处置使用 `WHERE id = ? AND reviewStatus = 待审核`，管理员在机审返回前已手动审核的图片只写机审字段，**不会被覆盖**；`doPictureReview` 也改成了带原状态条件的更新，人工与机审同时操作时只有一方成功；批量审核同样只更新仍为待审核的图片，已被处理的图片计入失败明细；
- 机审自动处置会写一条审核历史，`reviewerId = 0`，展示为「系统机审」；
- 重传新文件会清空四个机审字段，避免沿用旧文件的结论。

### 兜底与失败处理

- 机审开启时，`MachineReviewFallbackTask` 每 5 分钟扫描「创建超过 5 分钟、仍待审核、机审字段为空」的图片并重新发布上传事件；多实例用 Redis `SET NX EX` 互斥（`picture:machine-review:fallback:lock`，4 分钟过期）；
- 数据万象调用失败或结果无法识别时，消费者抛异常交给 RabbitMQ 重试；重试耗尽进入 `picture.moderation.q.dlq`，兜底任务仍会重新补发；
- 对象键无法从图片 URL 解析时只记录日志并确认消息，不会陷入重试循环。

### 前置条件与费用

- COS 桶必须已绑定数据万象（上传取图片宽高也依赖该绑定）；
- 子账号调用审核接口需要 `ci:CreateAuditingPictureJob` 权限，仅有 COS 权限会返回 `AccessDenied`；
- 图片审核**按张、按审核场景计费**，开启前请先在控制台确认价格。默认关闭就是为了避免误产生费用；
- 文字内容（名称、简介）由单独的文审链路处理，见下一节「文本审核（数据万象）」，默认关闭。

审核页（`/admin/pictureReview`）新增「机审」列，标签悬浮可看命中标签、置信度与机审时间；默认按机审状态倒序（疑似 → 违规 → 正常 → 未机审），同状态内按创建时间倒序。

## 文本审核（数据万象）

图片的**名称与简介**会交给数据万象文字审核，结论写入 `picture` 表的四个 `textReview*` 字段。与图片机审一样默认关闭，默认 `assist` 只记录、不改变人工审核结论。

### 开关与模式

```yaml
app:
  review:
    text:
      enabled: ${TEXT_REVIEW_ENABLED:false}
      mode: ${TEXT_REVIEW_MODE:assist}
      biz-type: ${TEXT_REVIEW_BIZ_TYPE:}
```

| 模式 | 行为 |
| --- | --- |
| `assist`（默认） | 只记录文审结果，审核仍由人工完成 |
| `auto-reject` | 文审违规自动拒绝；正常与疑似仍走人工 |

文本没有「自动通过」的语义（图片本身仍需人工审核），因此不提供机审的 `auto` 模式。

### 触发与执行

- 消费 `picture.uploaded`、`picture.changed`、`picture.batch.changed`：按 id 回查，只处理 `textReviewStatus IS NULL` 的图片，已删除或已审的直接确认；
- 审核文本 = 名称 + 简介（各去首尾空白、换行拼接，最长 2000 字符；两者都为空时不发请求，不产生费用）；
- 数据万象文字审核是**异步任务**：消费者创建任务后每 500ms 查询一次，最多等 8 秒；超时、任务失败或响应无法识别都抛异常交给 RabbitMQ 重试，重试耗尽进 `picture.textreview.q.dlq`；
- 重传文件、单张编辑（含管理员更新）改动名称或简介、批量编辑改动简介时，会先清空四个 `textReview*` 字段再触发重新文审；只改分类/标签不会重复审核。

### 结果与处置

- 写库使用 `WHERE id = ? AND textReviewStatus IS NULL`，重复投递只处理一次；
- `auto-reject` 自动拒绝使用 `WHERE id = ? AND reviewStatus = 待审核`，管理员先审核的图片不会被覆盖；拒绝时写一条审核历史（`reviewerId = 0`，展示为「系统机审」）、给作者发审核通知、失效公开列表缓存并同步搜索索引；
- 审核页在「机审」列后新增「文审」列，悬浮可看命中标签、置信度与文审时间。

### 前置条件与费用

- COS 桶必须绑定数据万象，密钥需要 `ci:CreateAuditingTextJob` 权限（只有 COS 权限会返回 `AccessDenied`）；
- 文字审核按次计费，开启前请先确认价格；`biz-type` 留空时使用控制台默认策略；
- 文审只覆盖名称与简介，分类、标签与审核意见不在审核范围内。

## AI 自动建议（DeepSeek）

上传成功后，后端调用 DeepSeek 的 OpenAI 兼容接口，为图片生成名称、简介、分类和标签**建议**。建议只用于辅助填写，不会修改图片数据，也不参与审核结论。

### 开关与模型

```yaml
app:
  ai:
    enabled: ${AI_TAG_ENABLED:false}
    base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}
    api-key: ${DEEPSEEK_API_KEY:}
    model: ${AI_MODEL:deepseek-flash}
    daily-limit: ${AI_DAILY_LIMIT:20}
```

- 默认关闭；关闭时消费者不查库、不调用任何外部接口，上传页也不会出现建议区域；
- `deepseek-flash` 支持图片输入与 JSON Output，请求显式关闭思考模式，降低延迟与费用；
- API Key 只从环境变量 `DEEPSEEK_API_KEY` 读取，不写入仓库。

### 触发与输入

- 机审开启时消费 `picture.moderated`，**只处理机审为「正常」的图片**，疑似/违规内容不会发给第三方模型；管理员直传的图片没有机审事件，改由 `picture.uploaded` 直接生成；
- 机审关闭时直接消费 `picture.uploaded`；
- 已拒绝的图片不生成建议；
- 图片地址追加 `imageMogr2/thumbnail/1024x` 后再发送，减少图片 token；分类与标签字典会放进提示词，要求模型优先从字典选择。

### 输出校验与存储

- 建议单独存表 `picture_ai_suggestion`，`pictureId` 唯一，状态为 0 处理中 / 1 成功 / 2 失败；
- 模型输出一律视为不可信输入，入库前再次校验：名称 ≤128 字符、简介 ≤512、分类必须来自字典（否则忽略）、标签去重且最多 20 个、单个 ≤32 字符；
- 图片重传后旧建议会按 `editTime` 判定为过期并重新生成；处理中记录超过 10 分钟也会被视为中断并允许重试；
- 查询接口 `GET /api/picture/ai_suggestion?id=` 仅作者和管理员可读，尚未生成时返回 `data: null`。

### 前端

上传成功（含重传）后，上传页最多轮询约 10 秒。拿到成功建议后显示「AI 建议」卡片和「一键填入」按钮；超时、失败或开关关闭时不显示，也**不阻塞**用户手动填写。一键填入只会覆盖名称、简介、分类、标签四个表单项，提交前仍可修改。

### 成本、重试与隐私

- 每用户每日调用上限由 Redis 计数控制（`picture:ai:limit:<userId>:<yyyyMMdd>`，默认 20 次），超限不调用模型；
- 429、5xx、网络异常最多重试 2 次（首次后按 1s、2s 退避，429 优先使用 `Retry-After`）；模型拒答、输出为空或 JSON 解析失败直接记失败，不重试；
- DeepSeek 按 token 计费，开启前请确认预算；用户上传的图片会发送给 DeepSeek，生产环境需在用户协议/隐私政策中说明，或保持默认关闭；
- 真实调用需要配置 `DEEPSEEK_API_KEY`，可用 `DEEPSEEK_BASE_URL` 指向兼容网关。
