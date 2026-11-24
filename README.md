# 云图库 picture

[![CI](https://github.com/shifang37/picture/actions/workflows/ci.yml/badge.svg)](https://github.com/shifang37/picture/actions/workflows/ci.yml)

前后端分离的图片素材管理与分享平台：用户上传的图片经人工与机器审核后进入公开图库，支持分类标签检索、瀑布流浏览、收藏点赞、专辑、分享卡片、站内通知，以及带引用的 AI 图库问答。

## 主要功能

- **上传与存储**：HEIC 转 JPG、EXIF 方向纠正、原图按需压缩、同用户秒传、按用户的存储与数量配额，删除先进回收站并保留 7 天
- **审核**：人工审核闭环（待审核 / 通过 / 拒绝 + 审核历史），可选的腾讯云数据万象机审与文审（默认关闭，`assist` 只记录、`auto-reject` / `auto` 自动处置），DeepSeek 生成名称、简介、分类、标签建议
- **检索**：分类与标签筛选、搜索联想 / 热词 / 历史、Elasticsearch 全文检索（`cjk` 分析器），以及 BM25 + kNN 的混合检索
- **AI 问答**：图库问答（回答带 1–4 张引用卡片）、详情页相似图片、审核通过后的近似重复提示
- **互动与组织**：收藏 / 点赞与热度排序、我的图片、批量编辑与批量删除、个人专辑、作者公开主页
- **分享与下载**：链接 / 二维码 / 系统分享，爬虫按 UA 分流输出 OG 卡片，下载原图并按用户按天去重计数
- **通知**：审核结论、密码重置、角色变更与系统公告，顶栏未读数与公告广播
- **管理端**：图片审核、用户管理、图片回收站、标签分类字典、数据看板与运维操作
- **可观测性**：Prometheus 业务指标、Grafana 看板、traceId 串起请求与消息消费、告警规则

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
│       ├── api/                     # OpenAPI 生成的前端请求客户端 + 手工封装（pictureExtra.ts 等）
│       ├── pages/                   # 页面（含「我的图片」与 admin 下的管理页）
│       ├── components/              # 公共组件（卡片、互动按钮、通知铃铛等）
│       ├── composables/             # 组合式函数（收藏/点赞交互等）
│       ├── router/  stores/  utils/
│       └── request.ts               # axios 封装
├── guides/                          # 分主题手册（随仓库提交）
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

只需建库，所需表由后端启动时通过 Flyway 自动创建（基线脚本为 `picture-backend/src/main/resources/db/migration/V1__baseline.sql`，标签关系表由 `V8__picture_tag_relation.sql` 创建，回收站字段由 `V9__picture_recycle_bin.sql` 创建，文本审核字段由 `V14__picture_text_review.sql` 创建）。

默认连接配置为 `localhost:3306/picture`，账号 `root` / `root`，如不一致请修改配置文件。旧库（Flyway 之前创建的库）的升级方式见 [部署与可观测性](guides/operations.md)。

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
> 它只会覆盖 OpenAPI 派生的客户端；`src/api/pictureExtra.ts` 中手工维护的 multipart 上传、「我的图片」与批量编辑/删除封装，`src/api/userExtra.ts` 中的个人中心/修改密码/作者主页封装，以及 `src/api/albumExtra.ts`、`src/api/interactionExtra.ts`、`src/api/notificationExtra.ts`、`src/api/searchExtra.ts`、`src/api/ragExtra.ts` 不会被覆盖。

## 测试与 CI

- 后端 824 个单元测试（Mockito / 临时 HTTP Server，不依赖数据库）+ Testcontainers 集成测试（`./mvnw verify`，本机无 Docker 时自动跳过）
- 前端 133 个 Vitest 用例 + 41 条 Playwright 端到端用例（全部 Mock 接口，不依赖后端）
- CI 在推送 `main` 与 PR 时执行：后端 `./mvnw verify`、前端格式/ESLint/构建/Vitest/Playwright，之后构建镜像并用 Trivy 扫描 `HIGH,CRITICAL` 漏洞

细节与用例清单见 [测试与持续集成](guides/development.md)。

## 文档

README 只保留上手所需的部分，实现细节按主题拆到 `guides/`：

| 文档 | 内容 |
| --- | --- |
| [用户功能手册](guides/usage.md) | 上传与配额、个人中心、审核流程、回收站、下载计数、收藏点赞、站内通知、作者主页、批量处理、专辑 |
| [内容审核与 AI 辅助](guides/moderation.md) | 机审初筛、文本审核、DeepSeek 自动建议的开关、触发链路、成本与失败处理 |
| [搜索与检索](guides/search.md) | 联想与热词、Elasticsearch 全文检索、混合检索、RAG 问答、相似图与近似去重 |
| [分享卡片与 SEO](guides/share-seo.md) | 爬虫分流、OG 标签、防盗链注意事项、前端分享弹窗 |
| [数据模型与运行时架构](guides/architecture.md) | 标签字典与关系表、登录态、缓存分级、RabbitMQ 拓扑、虚拟线程压测结论 |
| [前端实现](guides/frontend.md) | 首页瀑布流与无限滚动、管理端数据看板、构建拆包与代码检查 |
| [测试与持续集成](guides/development.md) | 测试范围、CI 流水线、依赖更新策略 |
| [部署与可观测性](guides/operations.md) | Docker 生产部署、数据库迁移、Prometheus 指标、日志链路、告警规则 |

## 注意事项

- `picture-backend/src/main/resources/application-local.yml` 存放真实密钥，已在 `.gitignore` 中排除，请勿提交。
- `docs/` 目录只在本机留存（已在 `.gitignore` 中排除），对外手册放在 `guides/`。
- 生产部署前请调整 `app.cors.allowed-origin-patterns` 为正式域名，并关闭或限制 Swagger 端点。
