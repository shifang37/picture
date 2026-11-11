# 云图库 picture

一个前后端分离的图片素材管理与分享平台。普通用户登录后可以上传图片、管理自己的图片并查看审核状态；支持分类标签检索、内容审核，以及管理端的用户与图片管理。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5.16、MyBatis-Plus 3.5.18.1、MySQL 8、Flyway、Redis + Spring Session、Caffeine、RabbitMQ、数据万象内容审核、DeepSeek |
| 对象存储 | 腾讯云 COS（含数据万象图片处理与内容审核） |
| 接口文档 | springdoc-openapi 2.10.0（Swagger UI） |
| 前端 | Vue 3.5、Vite 7、TypeScript 5.9、Ant Design Vue 4、Pinia、Vue Router、ESLint |
| 构建 | Maven Wrapper 3.9.16、npm |

## 目录结构

```
picture/
├── picture-backend/                 # Spring Boot 后端
│   ├── sql/legacy/                  # 接入 Flyway 前旧库的升级脚本（仅历史库使用）
│   └── src/main/java/com/tzy/picturebackend/
│       ├── controller/              # 接口层
│       ├── service/                 # 业务层（接口 + impl）
│       ├── mapper/                  # MyBatis-Plus Mapper
│       ├── manager/                 # COS 上传、数据万象机审、DeepSeek 标注与限流封装
│       ├── event/                   # 图片事件模型与 Spring 事件转发
│       ├── mq/                      # RabbitMQ 拓扑与队列消费者
│       ├── model/                   # entity / dto / vo / enums
│       ├── aop/                     # @AuthCheck 权限切面
│       ├── config/                  # CORS、MyBatis-Plus、Jackson、springdoc
│       └── exception/               # 统一异常与错误码
├── picture-frontend/                # Vue 前端
│   └── src/
│       ├── api/                     # OpenAPI 生成的前端请求客户端 + pictureExtra.ts / userExtra.ts 手工封装
│       ├── pages/                   # 页面（含「我的图片」与 admin 下的管理页）
│       ├── components/              # 公共组件
│       ├── router/  stores/  utils/
│       └── request.ts               # axios 封装
└── .gitignore
```

## 快速开始

### 环境要求

- JDK 21
- Node.js `^20.19.0 || >=22.12.0`
- MySQL 8.x
- Redis 7、RabbitMQ 4（本地用 `docker-compose.infra.yml` 启动）
- 一个腾讯云 COS 存储桶（用于图片存储）

### 1. 初始化数据库

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS picture DEFAULT CHARACTER SET utf8mb4"
```

只需建库，四张表由后端启动时通过 Flyway 自动创建（基线脚本为 `picture-backend/src/main/resources/db/migration/V1__baseline.sql`）。

默认连接配置为 `localhost:3306/picture`，账号 `root` / `root`，如不一致请修改配置文件。旧库（Flyway 之前创建的库）的升级方式见「生产部署」一节。

### 2. 配置对象存储

后端需要腾讯云 COS 配置才能上传图片。仓库中只保留了脱敏模板：

```bash
cd picture-backend/src/main/resources
cp application-local.yml.example application-local.yml
```

然后在 `application-local.yml` 中填入自己的 `host`、`secretId`、`secretKey`、`region`、`bucket`。

> 该文件已被 `.gitignore` 排除，不会进入版本库。请勿把真实密钥提交到仓库。
>
> 存储桶需要开启**公有读**（前端直接以 URL 展示图片）并**绑定数据万象**（上传时用于获取图片宽高信息）。
> 如需开启机审，还需在数据万象开通内容审核，并给访问密钥授予 `ci:CreateAuditingPictureJob` 权限。

### 3. 启动本地基础设施

登录态、会话失效版本、接口限流和图片事件都依赖中间件：

```bash
docker compose -f docker-compose.infra.yml up -d
```

本地 Redis 只绑定 `127.0.0.1:6379` 且默认无密码，与 `application.yml` 中的默认配置一致；RabbitMQ 只绑定 `127.0.0.1:5672`，管理界面为 `http://127.0.0.1:15672`（默认 `guest / guest`，仅本机可登录）。

### 4. 启动后端

```bash
cd picture-backend
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

服务地址 `http://localhost:8123/api`。

### 5. 启动前端

```bash
cd picture-frontend
npm install
npm run dev
```

访问 `http://localhost:5173`，开发服务器已把 `/api` 代理到 `http://localhost:8123`。

## 默认账号

系统没有内置账号，需自行注册，或直接在 `user` 表中插入管理员：

```sql
-- 用注册接口创建账号后，将其角色改为 admin 即可获得管理权限
UPDATE user SET userRole = 'admin' WHERE userAccount = 'your-account';
```

## 接口文档

启动后端后访问：

- Swagger UI：`http://localhost:8123/api/swagger-ui/index.html`
- OpenAPI JSON：`http://localhost:8123/api/v3/api-docs`
- 默认配置关闭 Swagger 与 MyBatis SQL 日志；本地 `application-local.yml` 中开启，生产环境请保持关闭。

## 普通用户上传图片

1. 登录后通过顶部「创建图片」进入上传页，也可以直接访问 `/add_picture`
2. 支持 jpg / jpeg / png / webp，单张不超过 2MB；后端会同时校验后缀、MIME 类型和文件头
3. 上传成功后填写名称、简介、分类、标签，提交后跳转到图片详情
4. 「我的图片」（`/my_pictures`）可以查看自己上传的全部图片，按待审核 / 已通过 / 已拒绝筛选，并支持编辑、删除

上传时后端会按需压缩原图，降低存储与下载流量：

- 长边超过 `1920` 的图片等比缩小；
- JPEG 超过约 `1.5MB` 时以 `0.85` 质量重新编码；
- PNG 只缩放、保留透明通道；WebP 当前保持原文件（JDK 没有内置 WebP 编码器）；
- 压缩前只读取图片头校验像素数，超过 `4000 万` 像素直接拒绝上传，避免解压炸弹耗尽内存；
- 压缩失败会自动回退原文件，不阻断上传；数据库中的 `picSize` 为压缩后的大小。

上传接口 `POST /api/picture/upload` 使用 `multipart/form-data`：`file` 为图片文件，`id` 可选（重新上传已有图片时传）。该接口只要求登录，普通用户也可以调用。

## 个人中心与用户管理

- 普通用户登录后从右上角用户菜单进入「个人中心」（`/user/profile`），可以修改昵称、头像地址和简介，也可以修改密码
- 修改资料接口 `POST /api/user/update/profile`：只作用于当前登录用户，不允许修改角色、账号和密码
- 修改密码接口 `POST /api/user/update/password`：原密码错误、新密码长度不合法会返回明确的 `40000` 业务码；修改成功后其他设备上的旧会话立即失效，当前页面保持登录
- 管理员在「用户管理」（`/admin/userManage`）可以新增用户、编辑昵称/头像/简介/角色、删除用户（删除前二次确认）
- 管理员可以重置任意用户密码：`POST /api/user/reset/password`，body 为 `{ "id": "用户 id", "newPassword": "6-72 位新密码" }`；重置后旧密码立即失效，该用户的所有已有登录会话也会立即失效
- 删除用户时不能删除自己；目标用户还有图片时会被拒绝，需要先处理该用户的图片
- 新用户默认密码 `12345678`；管理端用户查询和列表接口不会返回密码哈希

## 图片审核流程

本项目实现了完整的图片审核闭环：

1. 普通用户上传的图片进入**待审核**状态（`reviewStatus = 0`）；管理员上传的图片自动通过（`reviewStatus = 1`）
2. 待审核与被拒绝的图片**不会出现在首页列表**，只有管理员和作者本人能通过直链访问详情
3. 普通用户重新上传已通过的图片会**重新进入待审核**，并清空上一次的审核信息
4. 普通用户编辑已通过或已拒绝的图片时，只要名称、简介、分类、标签**确有变化**就会重新进入待审核并清空审核信息；只打开编辑页不改内容直接保存不会触发。管理员编辑不改变审核状态
5. 管理员在「图片审核」页面（`/admin/pictureReview`）通过或拒绝，可填写审核意见
6. 审核通过（`reviewStatus = 1`）后，图片才对所有访客可见；作者可在「我的图片」中看到拒绝原因
7. 审核历史会记录审核人（昵称/账号，用户已删除时显示 `用户#id`）、动作、时间和意见；管理员上传/重传自动通过时也会写入一条「管理员上传，自动通过」记录
8. 机审开启时，上传后几秒内会写回机审结果，审核页按机审疑似 / 违规优先展示；默认的 `assist` 模式不改变审核状态，详见下一节

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
- 自动处置使用 `WHERE id = ? AND reviewStatus = 待审核`，管理员在机审返回前已手动审核的图片只写机审字段，**不会被覆盖**；`doPictureReview` 也改成了带原状态条件的更新，人工与机审同时操作时只有一方成功；
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
- 文字内容（名称、简介）的文本审核尚未接入，作为后续可选扩展。

审核页（`/admin/pictureReview`）新增「机审」列，标签悬浮可看命中标签、置信度与机审时间；默认按机审状态倒序（疑似 → 违规 → 正常 → 未机审），同状态内按创建时间倒序。

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

## 标签与分类字典

标签和分类由管理员维护的字典表 `tag_category` 提供，不再从图片动态汇总：

- 首页与上传页仍然调用 `GET /api/picture/tag_category`，响应结构保持 `{ tagList, categoryList }`
- 管理页：`/admin/tagCategoryManage`，支持新增、删除和「从现有图片同步」
- 管理接口（均需管理员）：
  - `POST /api/tag_category/add`：`{ "type": "tag|category", "name": "名称" }`
  - `POST /api/tag_category/delete`：`{ "id": "字典 id" }`
  - `POST /api/tag_category/sync`：把已通过审核图片中缺失的标签/分类补进字典，返回新增数量
  - `GET /api/tag_category/list`：返回带 id 的完整字典
- 删除字典项不会修改已有图片的 `category` / `tags` 字段；图片仍在使用时只是不再作为新选项出现
- 旧库升级到该结构：执行 `picture-backend/sql/legacy/upgrade_v3.sql`，会建表并初始化现有图片的标签/分类（新库无需执行，Flyway 会自动建表）

## 下载次数统计口径

详情页的「下载次数」按**下载按钮点击次数**统计：点击后前端请求 `POST /api/picture/download/{id}` 自增，然后由浏览器直接下载 COS 原图。因此重复点击、统计请求成功但用户取消保存等情况都会被计入，它不代表「实际完成的下载次数」。如果后续需要精确统计，可改为后端下载代理后再自增。

## 登录态与会话（Redis）

- 登录态存放在 Redis（Spring Session），键前缀 `picture:session:`，**重启后端不会掉线**；
- 会话 Cookie 名为 `SESSION`，有效期 7 天（`spring.session.timeout`）；
- 修改或重置密码会让该用户的其他会话立即失效；失效版本同样在 Redis 中，重启后依旧有效；
- 限流计数在 `picture:ratelimit:` 下：登录按 IP 10 次/分钟，上传按用户 60 次/分钟（未登录时按 IP）；
- Redis 是硬依赖：没有 Redis 时登录与鉴权不可用，本地开发请先执行 `docker compose -f docker-compose.infra.yml up -d`。

## 缓存策略

- **标签/分类字典**（`GET /api/picture/tag_category`）：Spring Cache 存 Redis（key 为 `tagCategory::…`），TTL 30 分钟；管理员新增、删除、同步字典后立即失效，首页分类 Tab 马上更新。
- **公开图片列表**（`POST /api/picture/list/page/vo`）：两级缓存，L1 是 Caffeine（最多 500 项、写入后 30 秒过期），L2 是 Redis（5 分钟 + 0~60 秒随机抖动）。「我的图片」、管理列表、详情页都不缓存。
- **失效**：审核通过/拒绝、编辑、删除、上传成功后对 `picture:list:ns` 自增，新请求换用新命名空间，旧 key 自然过期。因此审核结果立即可见，L1 最多比数据库滞后 30 秒。
- **降级**：缓存只保存公开可见的数据；Redis 读写异常时直接回源数据库，列表接口不受影响。
- 实测（300 张图片、50 次请求）：公开列表热缓存 p95 约 8.5 ms，每次失效回源 p95 约 19 ms。

## 图片事件与消息队列（RabbitMQ）

上传请求只做「校验、压缩、存 COS、入库」；机审、AI 标签与搜索索引同步由图片事件驱动，在请求之外完成，失败可重试且互不影响。

### 拓扑

- 业务交换机 `picture.event`（topic），死信交换机 `picture.dlx`；
- 每条业务队列都绑定自己的死信队列 `*.dlq`，消费者重试耗尽后转入死信，不会无限重新入队。

| 路由键 | 触发时机 | 消费队列 |
| --- | --- | --- |
| `picture.uploaded` | 新上传或重传成功 | `picture.moderation.q`、`picture.search.q`、`picture.ai.q`（机审开启时 AI 消费者会跳过，等 `moderated`） |
| `picture.moderated` | 机审完成 | `picture.ai.q` |
| `picture.changed` | 人工审核、编辑、删除 | `picture.search.q` |

### 事件模型

- 消息体只带标识：`{ eventId, type, pictureId, occurredAt }`，不带名称、简介、状态等业务数据；消费者一律按 `pictureId` 回查数据库，重复投递和乱序都不会写入过期数据；
- `PictureServiceImpl` 只发布 Spring 应用事件（`PictureEventPublisher`），由 `PictureEventRelay` 用 `@TransactionalEventListener(fallbackExecution = true)` 转发到 RabbitMQ。Service 单元测试只需要验证事件是否发出，不依赖 RabbitMQ；
- 当前代码没有事务，事件会立即转发；将来给业务方法加上事务后，会自动变成「事务提交后再发」，避免消息先于数据落库。

### 可靠性

- 生产者开启 `publisher-confirm-type: correlated` 与 `publisher-returns`，确认失败或无法路由只记录日志；RabbitMQ 停机时上传、审核等主流程仍然成功（发布失败只留错误日志，接口正常返回）；
- 消费者失败最多尝试 3 次（首次后按 1s、2s 退避），仍失败则拒绝消息，由队列的 `x-dead-letter-exchange` 转入 `${queue}.dlq`；消息体无法解析等「毒消息」同样进死信，不会无限重入队；
- 消费者按 id 回查并检查状态：图片已删除、已审核或机审字段已有值时直接确认消息，避免重复处理；
- 机审消费者在开关开启时调用数据万象并写回机审字段，另有每 5 分钟的兜底扫描；AI 消费者机审开启时只处理机审正常的图片，机审关闭时直接消费上传事件；搜索索引消费者目前仍只做状态校验与事件记录，后续接入搜索能力。

管理界面可以查看队列、绑定与死信：`http://127.0.0.1:15672`（本地默认 `guest / guest`）。生产环境不映射 15672 到宿主机。

## 前端构建与代码检查

- **路由懒加载**：只有首页静态导入，其余页面（含全部管理端页面）按路由拆包，首屏不再加载管理端代码；
- **组件按需引入**：Ant Design Vue 由 `unplugin-vue-components` 按需解析，`main.ts` 不再 `app.use(Antd)`；生成的 `components.d.ts` 已提交，模板里的 `a-xxx` 有完整类型；
- **路由元信息**：权限写在路由 `meta.requiresLogin` / `meta.requiresAdmin`，`access.ts` 只读 `to.meta`，新增页面不用再改权限判断；
- **ESLint**：扁平配置 `eslint.config.js`，`npm run lint` 检查，CI 在格式检查之后执行；
- **构建体积**：入口 chunk 从改造前的 1,664 kB（gzip 518 kB）降到 740 kB（gzip 238 kB），管理端页面均为独立 chunk；
- 前端测试：10 个 Vitest 用例 + 5 个 Playwright 用例。

## 并发模型（虚拟线程评估）

JDK 21 的虚拟线程已评估但**未启用**，`spring.threads.virtual.enabled` 保持默认关闭：

- 压测对象 `GET /api/picture/get/vo`（每次请求 2 条 SQL），本地 500 张图片；
- 300 并发 × 3000 请求：平台线程 2.0–2.8k rps、p95 308–408 ms；虚拟线程 1.5–1.7k rps、p95 424–495 ms；
- 500 并发 × 3000 请求：两者吞吐接近（约 1.4–1.5k rps），虚拟线程 p95 略高（746 ms vs 604–694 ms）；
- 上传路径加 `-Djdk.tracePinnedThreads=short` 后观察到 `org.apache.http.pool.AbstractConnPool`（COS SDK 使用的 Apache HttpClient 连接池）与 `ConcurrentHashMap.computeIfAbsent` 的 MONITOR pinning；
- 结论：瓶颈是 HikariCP 的 10 个数据库连接而不是线程数，虚拟线程没有可测收益且上传路径存在 pinning，因此不保留该配置。并发模型变化后可复用同一压测方法复测。

## 生产部署（Docker / 反向代理 / 监控）

1. 复制 `.env.example` 为 `.env`，填写数据库密码、Redis 密码、RabbitMQ 账号密码、腾讯云 COS 配置；如开启 AI 建议，还需填写 `DEEPSEEK_API_KEY`
2. 执行 `docker compose up -d --build`
3. 访问 `http://localhost:8080`；前端 nginx 会把 `/api` 反向代理到后端 `8123`

说明：

- 后端以 `prod` profile 启动，默认关闭 Swagger 与 MyBatis SQL 日志（见 `application-prod.yml.example`）
- 健康检查：`/api/health`（Docker healthcheck）与 `/api/actuator/health`（Actuator）
- **监控收口**：公网 `/api` 只暴露 `health` 与 `info`，`/api/actuator/metrics` 不再可访问；确需 metrics 时使用独立管理端口（例如 `management.server.port: 8124`），该端口不要经过 nginx，只在内网/监控网络开放
- **限流来源 IP**：登录/上传限流按 Tomcat 解析后的真实 `remoteAddr` 计数；nginx 使用 `$remote_addr` 覆盖 `X-Forwarded-For`，后端不会再直接信任客户端可伪造的转发头。后端端口只应在 Docker 内网暴露，不要直接映射到公网
- **数据库迁移**：表结构由 Flyway 管理，启动时自动执行 `picture-backend/src/main/resources/db/migration` 下未执行过的脚本。新库直接执行 `V1__baseline.sql`；接入前已有表的旧库会被自动标记为基线版本 1（不重复建表），若该库结构还没升到 v3，需先各执行一次 `picture-backend/sql/legacy/upgrade_v2.sql` 和 `upgrade_v3.sql`。迁移执行后不要修改旧脚本，结构变更请新增更高版本的迁移脚本（如 `V7__xxx.sql`）
- **Redis 与会话**：compose 内置 `redis` 服务（AOF 持久化 + 密码认证，密码由 `REDIS_PASSWORD` 指定，务必改掉默认值）；`redis-data` 卷保存会话与失效版本，删除该卷会让所有用户重新登录
- **RabbitMQ**：compose 内置 `rabbitmq` 服务（`rabbitmq:4-management`，持久化卷 `rabbitmq-data`，只在内网可达，不映射 5672/15672 到宿主机）。账号密码由 `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` 指定；**不要使用 `guest`**，RabbitMQ 的 guest 只允许本机回环地址登录，容器间连不上。账号只在数据卷首次初始化时生效，修改 `.env` 后需要删除 `rabbitmq-data` 卷（会丢失队列与死信）或进入容器手工改密码
- **图片事件**：队列与交换机在应用启动时由 `RabbitAdmin` 幂等声明；RabbitMQ 不可用时上传与审核仍然成功，只是事件投递失败并记录日志。机审开启时每 5 分钟兜底补发遗漏图片（Redis 互斥），可用 `MACHINE_REVIEW_ENABLED` / `MACHINE_REVIEW_MODE` 控制；关闭机审则不会有任何外部审核调用
- **机审费用与权限**：开启机审前确认 COS 桶已绑定数据万象、账号具备 `ci:CreateAuditingPictureJob`；图片审核按张计费，默认关闭
- 生产环境建议启用 HTTPS，并将 `SESSION_COOKIE_SECURE=true`

## 常用命令

```bash
# 本地基础设施（Redis）
docker compose -f docker-compose.infra.yml up -d
docker compose -f docker-compose.infra.yml down

# 后端
cd picture-backend
./mvnw test                    # 运行单元测试
./mvnw clean package           # 打包
./mvnw spring-boot:run         # 本地启动

# 前端
cd picture-frontend
npm run dev                    # 开发服务器
npm run lint                   # ESLint 检查
npm run type-check             # 类型检查
npm run build                  # 生产构建
npm run test                   # Vitest 单元测试
npm run test:e2e               # Playwright 端到端测试
npm run openapi                # 依据后端 OpenAPI 重新生成 api/ 客户端
```

> `npm run openapi` 需要后端已启动，它从 `http://localhost:8123/api/v3/api-docs` 拉取接口定义。
> 它只会覆盖 OpenAPI 派生的客户端；`src/api/pictureExtra.ts` 中手工维护的 multipart 上传与「我的图片」封装、以及 `src/api/userExtra.ts` 中的个人中心/修改密码封装不会被覆盖。

## 测试

后端目前包含 **310 个单元测试**，覆盖用户注册/登录/改密、会话失效、管理员重置密码、图片上传权限与审核状态、原图压缩（尺寸/体积/PNG 透明通道/像素上限/回退）、审核历史与审核人补全、标签分类字典增删与同步、下载统计、文件类型与大小校验、「我的图片」归属校验、图片审核可见性与排序字段白名单逻辑、图片事件发布与 RabbitMQ 拓扑、消费者幂等判断、机审结果解析与三种处置模式、并发保护与兜底扫描、DeepSeek 请求体与错误分类、AI 建议输出校验/每日上限/重试与查询权限，以及 Redis 限流降级、会话失效版本、过滤器顺序、列表两级缓存与缓存失效，全部基于 Mockito、临时 HTTP Server 或临时文件，不依赖数据库与外部服务：

```bash
cd picture-backend
./mvnw test
```

## 注意事项

- `picture-backend/src/main/resources/application-local.yml` 存放真实密钥，已在 `.gitignore` 中排除，请勿提交。
- 生产部署前请调整 `app.cors.allowed-origin-patterns` 为正式域名，并关闭或限制 Swagger 端点。
