# 云图库 picture

一个前后端分离的图片素材管理与分享平台。普通用户登录后可以上传图片、管理自己的图片并查看审核状态；支持分类标签检索、内容审核，以及管理端的用户与图片管理。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5.16、MyBatis-Plus 3.5.18.1、MySQL 8、Flyway、Redis + Spring Session、Caffeine、RabbitMQ、Elasticsearch、数据万象内容审核、DeepSeek |
| 对象存储 | 腾讯云 COS（含数据万象图片处理与内容审核） |
| 接口文档 | springdoc-openapi 2.10.0（Swagger UI） |
| 前端 | Vue 3.5、Vite 7、TypeScript 5.9、Ant Design Vue 4、Pinia、Vue Router、VueUse、ECharts、ESLint |
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
│       ├── manager/                 # COS 上传、数据万象机审、DeepSeek 标注、搜索查询构建与限流封装
│       ├── event/                   # 图片事件模型与 Spring 事件转发
│       ├── mq/                      # RabbitMQ 拓扑与队列消费者
│       ├── task/                    # 定时任务（回收站到期物理清理等）
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
- Redis 7、RabbitMQ 4、Elasticsearch 8.18（本地用 `docker-compose.infra.yml` 启动；搜索默认关闭，不启用时可只启动 Redis 与 RabbitMQ）
- 一个腾讯云 COS 存储桶（用于图片存储）

### 1. 初始化数据库

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS picture DEFAULT CHARACTER SET utf8mb4"
```

只需建库，所需表由后端启动时通过 Flyway 自动创建（基线脚本为 `picture-backend/src/main/resources/db/migration/V1__baseline.sql`，标签关系表由 `V8__picture_tag_relation.sql` 创建，回收站字段由 `V9__picture_recycle_bin.sql` 创建）。

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
>
> 防盗链由应用启动时自动写入 COS 桶：配置 `cos.client.referer.domains`（或环境变量 `COS_REFERER_DOMAINS`，逗号分隔，不带协议），域名列表为空时自动跳过；本地开发可填 `localhost,127.0.0.1`。该功能需要密钥具备 `cos:PutBucketReferer` 权限。

### 3. 启动本地基础设施

登录态、会话失效版本、接口限流和图片事件都依赖中间件：

```bash
docker compose -f docker-compose.infra.yml up -d
```

本地 Redis 只绑定 `127.0.0.1:6379` 且默认无密码，与 `application.yml` 中的默认配置一致；RabbitMQ 只绑定 `127.0.0.1:5672`，管理界面为 `http://127.0.0.1:15672`（默认 `guest / guest`，仅本机可登录）。如需启用全文搜索，再启动 Elasticsearch：`docker compose -f docker-compose.infra.yml up -d elasticsearch`，它只绑定 `127.0.0.1:9200` 且关闭安全认证。

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
4. 「我的图片」（`/my_pictures`）可以查看自己上传的全部图片，按待审核 / 已通过 / 已拒绝筛选，并支持编辑、删除；删除后进入回收站，保留期内可由管理员恢复

上传时后端会按需压缩原图，降低存储与下载流量：

- 长边超过 `1920` 的图片等比缩小；
- JPEG 超过约 `1.5MB` 时以 `0.85` 质量重新编码；
- PNG 只缩放、保留透明通道；WebP 当前保持原文件（JDK 没有内置 WebP 编码器）；
- 压缩前只读取图片头校验像素数，超过 `2000 万` 像素直接拒绝上传，避免解压炸弹耗尽内存；
- 大图按长边用 `ImageReadParam.setSourceSubsampling` 降采样解码，上传接口再用信号量限制同时解码的图片数（默认 2，可用 `UPLOAD_MAX_CONCURRENT_DECODES` 调整），避免 768MB 容器被并发解码打爆；等待解码许可超时返回 `42900`；
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
9. 批量审核（`POST /api/picture/review/batch`）整批加事务：一条 `UPDATE ... WHERE id IN (...) AND reviewStatus = 0`、一条批量审核历史、结束时只失效一次公开列表缓存、只发一条 `picture.batch.changed` 事件；已不在待审核状态的图片计入失败明细，不会被覆盖

## 图片回收站与删除策略

删除图片不再立即销毁数据，而是先进入回收站；保留期结束后才物理删除：

| 阶段 | 行为 |
| --- | --- |
| 删除（`POST /api/picture/delete`） | 逻辑删除并记录 `deletedAt` / `deletedBy`；**级联删除 AI 建议**（`picture_ai_suggestion`）与标签关系（`picture_tag`）；**不删 COS 对象**，否则回收站恢复后图片会打不开；**不删审核日志**（`picture_review_log` 按合规要求保留） |
| 回收站（`/admin/pictureRecycle`） | 管理员按删除时间倒序查看已删除图片，支持按名称、分类、审核状态筛选，可一键恢复 |
| 恢复（`POST /api/picture/recycle/restore`） | 清除 `isDelete` / `deletedAt` / `deletedBy`，按 `picture.tags` JSON 重建标签关系；保留原审核状态，失效公开列表缓存并重新同步搜索索引；删除时已清理的 AI 建议不会随恢复还原 |
| 到期物理清理 | 默认保留 **7 天**，`PictureRecyclePurgeTask` 每小时执行一次：先删 COS 对象，成功后再物理删除 `picture` 行，并防御性清理该图片的标签关系与 AI 建议；COS 删除失败的图片保留到下一轮重试，避免留下孤儿对象 |

- 回收站保留期由 `app.recycle.retention-days` 控制（环境变量 `RECYCLE_RETENTION_DAYS`，默认 7）；`app.recycle.purge-enabled=false` 可关闭自动清理，`app.recycle.purge-batch-size` 控制单批条数（默认 100）；
- 物理删除时**不删除** `picture_review_log`：审核日志按图片 id 关联，图片被清理后仍可通过 `GET /api/picture/review/log?pictureId=` 追溯；
- 管理端接口：`POST /api/picture/recycle/list/page`（分页查询，参数同图片管理列表）与 `POST /api/picture/recycle/restore`（body `{ "id": "图片 id" }`），均要求管理员权限；
- 结构变更由 `V9__picture_recycle_bin.sql` 完成：新增 `picture.deletedAt`、`picture.deletedBy` 与 `idx_recycle_list(isDelete, deletedAt)`，并把升级前已逻辑删除的历史行按 `updateTime` 回填 `deletedAt`，确保它们也会到期清理。

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

### 图片标签关系表（picture_tag）

图片与标签的权威关系存在 `picture_tag(pictureId, tag)`：

- 主键 `(pictureId, tag)` 保证同一张图不重复；索引 `(tag, pictureId)` 支撑按标签筛选；
- 列表查询（首页、管理端、我的图片）在有标签条件时改为 `picture JOIN picture_tag`，多个标签是「同时包含」语义，不再对 `picture.tags` 做 `LIKE '%"标签"%'` 全表扫描；
- 上传/编辑/管理员更新/删除图片时同步维护关系表，`V8` 迁移会从历史 `picture.tags` JSON 回填；
- `picture.tags` 仍保留 JSON 副本供详情展示和 Elasticsearch 文档使用，但筛选以 `picture_tag` 为准；
- 标签上限统一为**单个 32 字符、最多 20 个**（`PictureTagConstant`），DTO、数据库 `picture_tag.tag`、AI 提示词与模型输出清洗共用同一组值。

## 下载与下载次数统计口径

详情页的「免费下载」走同源后端接口 `GET /api/picture/download/{id}`：

- 后端用 COS SDK 读取原图并**流式写回**，响应带 `Content-Disposition: attachment`，浏览器直接保存文件；不依赖 COS 桶的 CORS 配置，也不会把真实对象地址暴露给前端；
- 只有文件字节完整写给客户端后才自增下载次数，用户取消保存、网络中断不会计数；
- 自增前用 Redis 做「**用户 / IP + 图片 + 天**」去重（key 形如 `picture:download:dedup:20261112:<pictureId>:user:<userId>`，未登录时用 `ip:<remoteAddr>`，TTL 到当天 24:00），同一用户重复点击同一张图每天只计一次；
- Redis 故障时下载仍可正常进行，但本次不计数（宁可少计，也不让刷量在故障期间穿透）；
- 旧的无去重 `POST /api/picture/download/{id}` 计数接口已移除。

> 下载次数现在代表「按用户/IP 按天去重后的成功下载次数」，不再等于按钮点击次数。

## 登录态与会话（Redis）

- 登录态存放在 Redis（Spring Session），键前缀 `picture:session:`，**重启后端不会掉线**；
- 会话 Cookie 名为 `SESSION`，有效期 7 天（`spring.session.timeout`）；
- 修改或重置密码会让该用户的其他会话立即失效；失效版本同样在 Redis 中，重启后依旧有效；
- 限流计数在 `picture:ratelimit:` 下：登录按 IP 10 次/分钟，上传按用户 60 次/分钟（未登录时按 IP）；
- Redis 是硬依赖：没有 Redis 时登录与鉴权不可用，本地开发请先执行 `docker compose -f docker-compose.infra.yml up -d`。

## 缓存策略

- **标签/分类字典**（`GET /api/picture/tag_category`）：Spring Cache 存 Redis（key 为 `tagCategory::…`），TTL 30 分钟；管理员新增、删除、同步字典后立即失效，首页分类 Tab 马上更新。
- **公开图片列表**（`POST /api/picture/list/page/vo`）：两级缓存，L1 是 Caffeine（最多 500 项、写入后 30 秒过期），L2 是 Redis（5 分钟 + 0~60 秒随机抖动）。「我的图片」、管理列表、详情页都不缓存。
- **失效**：审核通过/拒绝（批量审核整批只失效一次）、编辑、删除、回收站恢复、上传成功后对 `picture:list:ns` 自增，新请求换用新命名空间，旧 key 自然过期。因此审核结果立即可见，L1 最多比数据库滞后 30 秒。
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
| `picture.changed` | 人工审核、编辑、删除、回收站恢复 | `picture.search.q` |
| `picture.batch.changed` | 批量审核完成（一条事件带整批 id） | `picture.search.q` |

### 事件模型

- 消息体只带标识：`{ eventId, type, pictureIds, occurredAt }`，不带名称、简介、状态等业务数据；单图事件只带一个 id，批量审核用一条事件带整批 id。消费者一律按 `pictureIds` 回查数据库，重复投递和乱序都不会写入过期数据；
- `PictureServiceImpl` 只发布 Spring 应用事件（`PictureEventPublisher`），由 `PictureEventRelay` 用 `@TransactionalEventListener(fallbackExecution = true)` 转发到 RabbitMQ。Service 单元测试只需要验证事件是否发出，不依赖 RabbitMQ；
- `editPicture` / `updatePicture` / 批量审核已加事务（批量审核同时更新整批图片并写一条批量历史），事件会在事务提交后转发；上传、单张审核、删除仍无事务，事件立即转发。

### 可靠性

- 生产者开启 `publisher-confirm-type: correlated` 与 `publisher-returns`，确认失败或无法路由只记录日志；RabbitMQ 停机时上传、审核等主流程仍然成功（发布失败只留错误日志，接口正常返回）；
- 消费者失败最多尝试 3 次（首次后按 1s、2s 退避），仍失败则拒绝消息，由队列的 `x-dead-letter-exchange` 转入 `${queue}.dlq`；消息体无法解析等「毒消息」同样进死信，不会无限重入队；
- 消费者按 id 回查并检查状态：图片已删除、已审核或机审字段已有值时直接确认消息，避免重复处理；
- 机审消费者在开关开启时调用数据万象并写回机审字段，另有每 5 分钟的兜底扫描；AI 消费者机审开启时只处理机审正常的图片，机审关闭时直接消费上传事件；搜索索引消费者按 id 批量回查后写入或删除 Elasticsearch 文档（搜索未启用时空实现，不产生外部调用），批量事件只查一次库、只失效一次列表缓存。

管理界面可以查看队列、绑定与死信：`http://127.0.0.1:15672`（本地默认 `guest / guest`）。生产环境不映射 15672 到宿主机。

## 全文搜索（Elasticsearch）

公开列表（`POST /api/picture/list/page/vo`）默认仍走 MySQL 模糊查询。设置 `SEARCH_ENGINE=es` 后，改由 Elasticsearch 召回图片 id，再回 MySQL 取数据并再次过滤 `reviewStatus = 1`：

- **MySQL 是唯一事实来源**：索引里只有已通过审核的图片，索引延迟最多导致「少搜到」，不会泄露未过审内容；
- **只接管公开列表**：「我的图片」和管理端列表继续走 MySQL；
- **可降级**：Elasticsearch 调用异常时自动回落到原 MySQL 查询并记录 warn 日志，首页搜索保持可用；
- **索引**：`picture_v1`，别名 `picture`；名称、简介使用内置 `cjk` 分析器（不需要 IK 插件），名称权重为 3；分类、标签、格式等使用 keyword；
- **排序**：有搜索词时按相关度，否则按请求字段（默认 `createTime` 倒序），排序字段沿用原白名单；
- **同步**：`picture.uploaded` / `picture.changed` / `picture.batch.changed` 事件驱动，`picture.search.q` 消费者按 id 回查：公开可见就写入索引，待审核、拒绝、删除则删除文档；
- **全量重建**：`POST /api/search/reindex`（仅管理员），每批 500 条从 MySQL 写入临时索引，完成后原子切换别名再删除旧索引；首次启用、索引损坏或巡检不一致时使用；
- **一致性巡检**：`SEARCH_ENGINE=es` 时每天 03:00 对比 MySQL 公开图片数与索引文档数，不一致时记录「搜索索引与数据库不一致」告警日志；
- **健康检查**：默认不启用 Elasticsearch 健康项，避免 MySQL 搜索模式下 Actuator health 被误判为 DOWN；启用搜索后可设置 `SEARCH_HEALTH_ENABLED=true`。

本地启用步骤：

```powershell
docker compose -f docker-compose.infra.yml up -d elasticsearch
cd picture-backend
$env:SEARCH_ENGINE = 'es'
.\mvnw.cmd spring-boot:run
```

启动后先以管理员身份调用一次全量重建（写接口需要 CSRF，先 `GET /api/health` 拿 `XSRF-TOKEN`），把历史图片补进索引；此后新上传、审核、编辑、删除都由消息队列自动同步。

> 单节点开发堆内存 512MB。生产 compose 开启基础认证（`ES_USERNAME` / `ES_PASSWORD`），只在内网可达，不映射 9200 到宿主机。

## 首页瀑布流与无限滚动

首页不再是固定栅格分页，改为按图片比例排布的瀑布流：

- **布局**：用接口返回的 `picWidth` / `picHeight` 计算高宽比，按「放入当前最矮的一列」分配到 1–6 列，列数随容器宽度变化；封面用 `aspect-ratio` 在图片加载前占位，横图、竖图都不裁切，滚动时没有布局跳动；
- **加载**：VueUse `useInfiniteScroll` 监听窗口滚动，距底部 400px 时请求下一页（每页 20 条），按 id 去重，避免滚动期间有新图过审导致重复；加载失败显示重试按钮，全部加载完显示「没有更多了」；
- **图片**：`loading="lazy"`、`decoding="async"`，缩略图尺寸按列宽 × 设备像素比取 300–600 的档位，减少列表流量；
- **状态进 URL**：搜索词、分类、标签写入查询参数，刷新或分享链接都能还原；切换条件时回到页面顶部；
- **返回恢复**：首页通过路由 `meta.keepAlive` 缓存，从详情页返回时恢复原来的滚动位置；其他页面仍沿用浏览器前进后退的滚动位置。

## 管理端数据看板

`/admin/dashboard` 页面（仅管理员可见）展示运营数据，后端接口统一要求管理员权限，结果用 Spring Cache 缓存 5 分钟：

| 接口 | 内容 |
| --- | --- |
| `GET /api/analyze/overview` | 用户数、图片数、待审核数、总存储体积、总下载次数 |
| `GET /api/analyze/upload_trend?days=30` | 按天的上传数量，没有上传的日期补 0 |
| `GET /api/analyze/category` | 各分类的图片数与体积 |
| `GET /api/analyze/tags?limit=20` | 使用次数最多的标签（聚合 `picture_tag` 关系表） |
| `GET /api/analyze/review` | 已通过/已拒绝数量、平均审核耗时 |
| `GET /api/analyze/top_download?limit=10` | 下载次数最多的图片 |

- 聚合 SQL 写在 `AnalyzeMapper.xml`，自定义 SQL 手动加 `isDelete = 0`，不会经过 MyBatis-Plus 的逻辑删除插件；
- 天数限制 1–365，排行限制 1–50，越界返回 `40000`；
- 前端按需注册 ECharts，`echarts` 与 `vue-echarts` 只打进看板页面的懒加载分包，首页不会加载；
- 图表：上传趋势折线图、审核结果环形图、分类柱状图、标签横向条形图，另有下载排行表格；指标卡片显示用户数、图片数、待审核、存储体积和下载次数。

## 前端构建与代码检查

- **路由懒加载**：只有首页静态导入，其余页面（含全部管理端页面）按路由拆包，首屏不再加载管理端代码；
- **组件按需引入**：Ant Design Vue 由 `unplugin-vue-components` 按需解析，`main.ts` 不再 `app.use(Antd)`；生成的 `components.d.ts` 已提交，模板里的 `a-xxx` 有完整类型；
- **路由元信息**：权限写在路由 `meta.requiresLogin` / `meta.requiresAdmin`，`access.ts` 只读 `to.meta`，新增页面不用再改权限判断；
- **ESLint**：扁平配置 `eslint.config.js`，`npm run lint` 检查，CI 在格式检查之后执行；
- **构建体积**：入口 chunk 从改造前的 1,664 kB（gzip 518 kB）降到约 643 kB（gzip 212 kB），管理端页面均为独立 chunk；数据看板的 ECharts 分包约 578 kB（gzip 199 kB），只在进入看板时加载；
- 前端测试：23 个 Vitest 用例 + 10 个 Playwright 用例。

## 并发模型（虚拟线程评估）

JDK 21 的虚拟线程已评估但**未启用**，`spring.threads.virtual.enabled` 保持默认关闭：

- 压测对象 `GET /api/picture/get/vo`（每次请求 2 条 SQL），本地 500 张图片；
- 300 并发 × 3000 请求：平台线程 2.0–2.8k rps、p95 308–408 ms；虚拟线程 1.5–1.7k rps、p95 424–495 ms；
- 500 并发 × 3000 请求：两者吞吐接近（约 1.4–1.5k rps），虚拟线程 p95 略高（746 ms vs 604–694 ms）；
- 上传路径加 `-Djdk.tracePinnedThreads=short` 后观察到 `org.apache.http.pool.AbstractConnPool`（COS SDK 使用的 Apache HttpClient 连接池）与 `ConcurrentHashMap.computeIfAbsent` 的 MONITOR pinning；
- 结论：瓶颈是 HikariCP 的 10 个数据库连接而不是线程数，虚拟线程没有可测收益且上传路径存在 pinning，因此不保留该配置。并发模型变化后可复用同一压测方法复测。

## 生产部署（Docker / 反向代理 / 监控）

1. 复制 `.env.example` 为 `.env`，填写 `DB_ROOT_PASSWORD`、`DB_PASSWORD`（应用账号 `picture` 的密码）、Redis 密码、RabbitMQ 账号密码、腾讯云 COS 配置；如开启 AI 建议，还需填写 `DEEPSEEK_API_KEY`；如开启全文搜索，设置 `SEARCH_ENGINE=es` 与 `ES_PASSWORD`
2. 执行 `docker compose up -d --build`
3. 访问 `http://localhost:8080`；前端 nginx 会把 `/api` 反向代理到后端 `8123`

说明：

- 后端以 `prod,observability` profile 启动，默认关闭 Swagger 与 MyBatis SQL 日志（见 `application-prod.yml.example`）
- 健康检查：`/api/health`（Docker healthcheck，始终可用）。启用 `observability` profile 后 Actuator 搬到独立管理端口：`http://backend:8124/actuator/health`，该端口不经过 nginx、不映射到宿主机；Elasticsearch 健康项默认关闭，启用搜索后可设 `SEARCH_HEALTH_ENABLED=true`
- **监控收口**：公网 `/api` 只暴露 `health` 与 `info`，`/api/actuator/prometheus` 不可访问；Prometheus 指标只在独立管理端口 8124 暴露，详见下一节「可观测性」
- **限流来源 IP**：登录/上传限流按 Tomcat 解析后的真实 `remoteAddr` 计数；nginx 使用 `$remote_addr` 覆盖 `X-Forwarded-For`，后端不会再直接信任客户端可伪造的转发头。后端端口只应在 Docker 内网暴露，不要直接映射到公网
- **数据库迁移**：表结构由 Flyway 管理，启动时自动执行 `picture-backend/src/main/resources/db/migration` 下未执行过的脚本。新库直接执行 `V1__baseline.sql`；接入前已有表的旧库会被自动标记为基线版本 1（不重复建表），若该库结构还没升到 v3，需先各执行一次 `picture-backend/sql/legacy/upgrade_v2.sql` 和 `upgrade_v3.sql`。迁移执行后不要修改旧脚本，结构变更请新增更高版本的迁移脚本（如 `V9__xxx.sql`）
- **数据库账号**：应用不再使用 root。MySQL 首次初始化时会创建专用账号 `picture`（密码 `DB_PASSWORD`），对 `picture` 库有全部权限，Flyway 才能执行 DDL。已有 `mysql-data` 卷需要手工补一次：
  ```sql
  CREATE USER IF NOT EXISTS 'picture'@'%' IDENTIFIED BY '<DB_PASSWORD>';
  GRANT ALL PRIVILEGES ON picture.* TO 'picture'@'%';
  FLUSH PRIVILEGES;
  ```
- **数据库备份**：`mysql-backup` 服务每天用应用账号执行 `mysqldump --single-transaction --triggers --no-tablespaces` 备份到 `mysql-backup` 卷，保留 7 天。恢复时先从 `mysql-backup` 卷取出 `.sql`，再执行 `docker compose exec -T mysql mysql -uroot -p picture < backup.sql`；该卷本身仍需要纳入宿主机层面的快照/异地备份
- **镜像与资源**：后端镜像使用非 root 用户（UID 10001）运行，堆上限默认为容器内存的 75%（`JAVA_OPTS` 可覆盖），构建时先解析依赖再复制源码以复用缓存；前端镜像固定 `nginx:1.30-alpine` 并在构建时执行 `apk upgrade`，避免基础镜像中可修复的高危漏洞让 CI 扫描失败。compose 为各服务设置了 `restart: unless-stopped` 和内存上限，避免单个服务拖垮整机
- **Redis 与会话**：compose 内置 `redis` 服务（AOF 持久化 + 密码认证，密码由 `REDIS_PASSWORD` 指定，务必改掉默认值）；`redis-data` 卷保存会话与失效版本，删除该卷会让所有用户重新登录
- **RabbitMQ**：compose 内置 `rabbitmq` 服务（`rabbitmq:4-management`，持久化卷 `rabbitmq-data`，只在内网可达，不映射 5672/15672 到宿主机）。账号密码由 `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` 指定；**不要使用 `guest`**，RabbitMQ 的 guest 只允许本机回环地址登录，容器间连不上。账号只在数据卷首次初始化时生效，修改 `.env` 后需要删除 `rabbitmq-data` 卷（会丢失队列与死信）或进入容器手工改密码
- **Elasticsearch**：compose 内置 `elasticsearch` 服务（版本 8.18.8，堆内存 512MB，持久化卷 `elasticsearch-data`，只在内网可达，不映射 9200 到宿主机），开启基础认证（`ES_USERNAME` / `ES_PASSWORD`）并关闭 HTTP TLS（仅内网访问）。`SEARCH_ENGINE` 默认 `mysql`，此时只启动不连接；切换为 `es` 后需管理员调用一次 `POST /api/search/reindex` 初始化索引
- **图片事件**：队列与交换机在应用启动时由 `RabbitAdmin` 幂等声明；RabbitMQ 不可用时上传与审核仍然成功，只是事件投递失败并记录日志。机审开启时每 5 分钟兜底补发遗漏图片（Redis 互斥），可用 `MACHINE_REVIEW_ENABLED` / `MACHINE_REVIEW_MODE` 控制；关闭机审则不会有任何外部审核调用
- **机审费用与权限**：开启机审前确认 COS 桶已绑定数据万象、账号具备 `ci:CreateAuditingPictureJob`；图片审核按张计费，默认关闭
- **COS 防盗链**：生产 `.env` 填写 `COS_REFERER_DOMAINS=your-domain.com`（多个用逗号分隔，可写 `*.your-domain.com`），应用启动时会调用 COS API 写入 Referer 白名单；需给密钥补 `cos:PutBucketReferer` 权限，写入失败只记日志不影响启动。`COS_REFERER_ALLOW_EMPTY` 默认 `false`，即直接打开 COS 原图地址会被拒绝
- 生产环境建议启用 HTTPS，并将 `SESSION_COOKIE_SECURE=true`

## 可观测性（Prometheus / Grafana）

所有业务异常和限流响应都是 **HTTP 200 + 业务码**，`http_server_requests` 里几乎全是 200，
基于 5xx 的告警不会触发。因此这里按业务码自建指标，并用 Prometheus 抓取、Grafana 展示。

### 组件与端口

| 组件 | 端口 | 暴露范围 |
| --- | --- | --- |
| 业务端口 | 8123 | 经 nginx 只暴露 `/api`，不含 Prometheus 端点 |
| Actuator 管理端口 | 8124 | 仅 Compose 内网；`/actuator/health`、`/actuator/prometheus` |
| Prometheus | 9090 | 生产不映射到宿主机；本地仅绑定 `127.0.0.1:9090` |
| Grafana | 3000 | 仅绑定 `127.0.0.1:3000`，通过 `.env` 的 `GRAFANA_ADMIN_PASSWORD` 登录 |
| RabbitMQ Prometheus | 15692 | 仅 Compose 内网，由 `docker/monitoring/rabbitmq/enabled_plugins` 开启插件 |

> RabbitMQ 默认只输出聚合指标，`docker/monitoring/rabbitmq/rabbitmq.conf` 打开了
> `prometheus.return_per_object_metrics`，队列指标才会带 `queue` 标签，死信告警才能按 `*.dlq` 过滤。

可观测性配置放在 `picture-backend/src/main/resources/application-observability.yml`，
生产 Compose 已通过 `SPRING_PROFILES_ACTIVE=prod,observability` 激活。手工部署时需要同时激活
`prod` 与 `observability` 两个 profile，否则 8124 管理端口和 `/actuator/prometheus` 都不会出现。

### 本地启动

```powershell
# 启动 Redis / RabbitMQ / Prometheus / Grafana（按需只起后三个也可以）
docker compose -f docker-compose.infra.yml up -d

cd picture-backend
# 同时激活本地配置与可观测性；日志仍是可读文本，不会变成 ECS JSON
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local,observability"
```

- Prometheus：<http://127.0.0.1:9090>，Targets 页面应看到 `picture-backend` 与 `rabbitmq` 为 UP；
  告警状态在 <http://127.0.0.1:9090/alerts>。
- Grafana：<http://127.0.0.1:3000>，默认数据源与「云图库 · 服务概览」看板已预置。
- 后端启动参数等价于 `--management.server.port=8124 --management.endpoints.web.exposure.include=health,info,prometheus`。

生产 Compose 启动后 Grafana 同样只监听宿主机回环地址 `127.0.0.1:3000`；
Prometheus、8124 管理端口、RabbitMQ 15692 都只在 Compose 内网可达。

### 自定义指标

| 指标 | 类型 | 埋点位置 | 说明 |
| --- | --- | --- | --- |
| `picture_api_errors_total{code}` | Counter | `GlobalExceptionHandler` | 全部 13 个异常分支按业务码计数 |
| `picture_rate_limited_total{type}` | Counter | `RateLimitFilter` | `type=login/upload`，限流响应不经过异常处理器 |
| `picture_upload_total{result}` | Counter | `PictureServiceImpl#uploadPicture` | 成功/失败，失败包含校验、COS、入库异常 |
| `picture_compress_seconds` | Timer | `FileManager#compressIfNeeded` | 开启直方图桶，可算 p95 |
| `picture_review_pending` | Gauge | `PictureReviewPendingMetrics` | 每 60 秒查一次待审核数量，抓取时不打库 |
| `picture_ai_tokens_total{direction}` | Counter | `DeepSeekPictureAiTagger` | 按 `prompt/completion` 累计 token，用于估算成本 |
| `cache_gets_total{cache="picture.list.local"}` | Counter | `PictureListCache` | Caffeine 开启 `recordStats` 后由 Micrometer 导出，可算命中率 |

`http_server_requests_seconds` 已开启 `percentiles-histogram`，接口 p95 延迟可直接用
`histogram_quantile(0.95, ...)` 计算。

### 日志与链路标识

- 依赖 `micrometer-tracing-bridge-brave`，请求、消息发布、消息消费共用一条 trace；
- RabbitMQ 的 `template.observation-enabled` 与 `listener.simple.observation-enabled` 已打开，
  消费者日志里的 `traceId` 与上传请求一致；
- 生产环境通过 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` 输出 ECS JSON，trace 字段为 `traceId` / `spanId`；
- 采样率由 `TRACING_SAMPLING_PROBABILITY` 控制（默认 0.1），只影响链路数据量，不影响日志里的 traceId。

排查示例：先看 Grafana 的「业务错误速率」定位业务码，再用 traceId 过滤日志：

```powershell
docker compose logs backend | Select-String 'traceId'
```

### 告警规则

规则文件：`docker/monitoring/prometheus/alerts.yml`。没有引入 Alertmanager，
Prometheus 的 `/alerts` 页面会显示 pending/firing，可再按需接钉钉、邮件或 Alertmanager。

| 告警 | 条件 | 持续时间 |
| --- | --- | --- |
| `PictureSystemErrorRateHigh` | `picture_api_errors_total{code="50000"}` 速率 > 0.1/s | 5 分钟 |
| `PictureApiLatencyHigh` | 接口 p95 > 1s | 5 分钟 |
| `PictureDatabasePoolWaiting` | HikariCP 等待连接数 > 0 | 5 分钟 |
| `PictureDeadLetterQueueNotEmpty` | 任一 `*.dlq` 队列消息数 > 0 | 1 分钟 |
| `PictureReviewBacklog` | 待审核图片 > 100 张 | 10 分钟 |

> 8124、9090、15692 都不要映射到公网；Grafana 只绑定回环地址，并务必修改 `.env` 中的默认密码。

## 常用命令

```bash
# 本地基础设施（Redis / RabbitMQ / Elasticsearch / Prometheus / Grafana）
docker compose -f docker-compose.infra.yml up -d
docker compose -f docker-compose.infra.yml down

# 后端
cd picture-backend
./mvnw test                    # 运行单元测试
./mvnw verify                  # 单元测试 + 集成测试（需要 Docker）
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

后端目前包含 **453 个单元测试**，覆盖用户注册/登录/改密、会话失效、管理员重置密码、图片上传权限与审核状态、原图压缩（尺寸/体积/PNG 透明通道/像素上限/降采样解码/并发解码限流/回退）、审核历史与审核人补全、批量审核的单条条件更新/批量日志/单次缓存失效/单条事件、回收站的删除级联/列表过滤/恢复重建标签/到期物理清理与 COS 失败重试、标签分类字典增删与同步、标签关系表同步与 JOIN 查询、下载统计、文件类型与大小校验、「我的图片」归属校验、图片审核可见性与排序字段白名单逻辑、图片事件发布与 RabbitMQ 拓扑、消费者幂等判断、机审结果解析与三种处置模式、并发保护与兜底扫描、DeepSeek 请求体与错误分类、AI 建议输出校验/每日上限/重试与查询权限、搜索条件翻译/索引文档映射/搜索降级与索引一致性巡检、Prometheus 指标名与标签/待审核 Gauge 刷新，以及 Redis 限流降级、会话失效版本、过滤器顺序、列表两级缓存与缓存失效，全部基于 Mockito、临时 HTTP Server 或临时文件，不依赖数据库与外部服务：

```bash
cd picture-backend
./mvnw test     # 单元测试，不依赖 Docker
./mvnw verify   # 单元测试 + Testcontainers 集成测试，需要本机 Docker
```

集成测试（`*IT.java`，`mvnw verify` 阶段由 failsafe 执行）覆盖单元测试覆盖不到的部分：应用上下文装配、Flyway 在空库上执行全部迁移、`picture_tag` 回填与多标签 JOIN 查询、重传后审核/机审字段确实被置空、逻辑删除与回收站恢复（含 AI 建议级联清理与审核日志保留）、账号唯一索引、改密后旧会话失效（真实 Redis + Spring Session）、Redis Lua 限流的计数与过期、上传限流按 Session 用户 id 而不是 IP。Docker 不可用时由 `@Testcontainers(disabledWithoutDocker = true)` 自动跳过，不会让本地测试失败。

## 持续集成与依赖更新

CI（`.github/workflows/ci.yml`）在推送到 `main` 和 PR 时执行：

- **后端**：`./mvnw verify`，包含单元测试与 Testcontainers 集成测试；JaCoCo 报告生成在
  `picture-backend/target/site/jacoco`，并作为 `backend-jacoco` artifact 上传（只出报告，不设覆盖率门槛）；
- **前端**：格式检查、ESLint、构建、Vitest、Playwright；
- **镜像**：测试通过后构建 `picture-backend` / `picture-frontend` 镜像，用 Trivy 扫描
  `HIGH,CRITICAL` 漏洞；可修复的高危漏洞会直接让流水线失败（`ignore-unfixed: true`）。

依赖更新由 `.github/dependabot.yml` 按月提出分组 PR：Maven、npm、GitHub Actions、Docker 各一组。
Dependabot 只负责提案，合并与否仍由人工决定，不影响直接推送 `main`。

## 注意事项

- `picture-backend/src/main/resources/application-local.yml` 存放真实密钥，已在 `.gitignore` 中排除，请勿提交。
- 生产部署前请调整 `app.cors.allowed-origin-patterns` 为正式域名，并关闭或限制 Swagger 端点。
