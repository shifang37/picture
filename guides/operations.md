# 部署与可观测性

> 本页是 [README](../README.md) 的展开说明，覆盖Docker 生产部署、反向代理与迁移约束，Prometheus 指标、日志链路与告警规则。

## 生产部署（Docker / 反向代理 / 监控）

1. 复制 `.env.example` 为 `.env`，填写 `DB_ROOT_PASSWORD`、`DB_PASSWORD`（应用账号 `picture` 的密码）、Redis 密码、RabbitMQ 账号密码、腾讯云 COS 配置；如开启 AI 建议，还需填写 `DEEPSEEK_API_KEY`；如开启全文搜索，设置 `SEARCH_ENGINE=es` 与 `ES_PASSWORD`
2. 执行 `docker compose up -d --build`
3. 访问 `http://localhost:8080`；前端 nginx 会把 `/api` 反向代理到后端 `8123`

说明：

- 后端以 `prod,observability` profile 启动，默认关闭 Swagger 与 MyBatis SQL 日志（见 `application-prod.yml.example`）
- 健康检查：`/api/health`（Docker healthcheck，始终可用）。启用 `observability` profile 后 Actuator 搬到独立管理端口：`http://backend:8124/actuator/health`，该端口不经过 nginx、不映射到宿主机；Elasticsearch 健康项默认关闭，启用搜索后可设 `SEARCH_HEALTH_ENABLED=true`
- **监控收口**：公网 `/api` 只暴露 `health` 与 `info`，`/api/actuator/prometheus` 不可访问；Prometheus 指标只在独立管理端口 8124 暴露，详见下文「可观测性」
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
- **文审费用与权限**：文本审核由 `TEXT_REVIEW_ENABLED` / `TEXT_REVIEW_MODE` 控制，密钥需要 `ci:CreateAuditingTextJob`，按次计费，默认关闭；只审名称与简介
- **COS 防盗链**：生产 `.env` 填写 `COS_REFERER_DOMAINS=your-domain.com`（多个用逗号分隔，可写 `*.your-domain.com`），应用启动时会调用 COS API 写入 Referer 白名单；需给密钥补 `cos:PutBucketReferer` 权限，写入失败只记日志不影响启动。`COS_REFERER_ALLOW_EMPTY` 默认 `false`，即直接打开 COS 原图地址会被拒绝
- 生产环境建议启用 HTTPS，并将 `SESSION_COOKIE_SECURE=true`

## 依赖安全与漏洞门禁

CI 的 `images` job 会构建后端 / 前端镜像，并用 Trivy 扫描 `HIGH,CRITICAL` 漏洞：存在**有修复版本**的漏洞时流水线直接失败，没有修复版本的只展示（`ignore-unfixed: true`）。

处理顺序固定为：

1. **先升级**——能在当前依赖线修好的一律升级，不用忽略清单；
2. **再登记**——确实修不了的才写进根目录 `.trivyignore`，且必须写明原因与跟踪计划，修好即删。

当前版本的修复方式：

| 依赖 | 漏洞数 | 处理 |
| --- | --- | --- |
| `tomcat-embed-core` | 6（含 CVE-2026-53404） | pom 里 `<tomcat.version>10.1.59` |
| `netty-handler` / `netty-codec` | 2（含 CVE-2026-75595 CRITICAL） | `<netty.version>4.1.137.Final` |
| `jackson-core` / `jackson-databind` | 5 | `<jackson-bom.version>2.21.7`（仍在 2.21 线） |
| `amqp-client` | 4 | `dependencyManagement` 提到 5.34.0 |
| `okhttp` / `logging-interceptor` / `okio` | 1（CVE-2021-0341） | 提到 4.9.2 / 4.9.2 / 2.8.0 |
| `spring-webmvc` | 2（CVE-2026-47884 CRITICAL 等） | **无法修**：只在 Spring Framework 7 修，登记在 `.trivyignore`，等 Boot 4 迁移 |

注意点：

- `amqp-client` 5.34 自身声明 netty 4.2.x，但默认阻塞式客户端不走 netty，这里统一按 4.1.137.Final 解析，避免 4.1/4.2 两套共存；
- `okhttp` 升到 4.x 会引入 kotlin-stdlib，且 `okio` 必须显式提到 2.8.0，否则被 `cos_api` 声明的 1.15.0 覆盖、运行期缺类；
- 本地复现：`docker build -t picture-backend:ci ./picture-backend` 后执行 `trivy image --severity HIGH,CRITICAL --ignore-unfixed --ignorefile .trivyignore picture-backend:ci`。

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
- Grafana：<http://127.0.0.1:3000>，默认数据源与「云图库 · 服务概览」看板已预置；看板除收藏/点赞、互动接口 p95、通知等面板外，底部新增 **搜索零结果率**、**hybrid vs keyword 召回命中率**、**问答失败 / 降级** 三个 RAG 面板。
- 后端启动参数等价于 `--management.server.port=8124 --management.endpoints.web.exposure.include=health,info,prometheus`。

生产 Compose 启动后 Grafana 同样只监听宿主机回环地址 `127.0.0.1:3000`；
Prometheus、8124 管理端口、RabbitMQ 15692 都只在 Compose 内网可达。

### 自定义指标

| 指标 | 类型 | 埋点位置 | 说明 |
| --- | --- | --- | --- |
| `picture_api_errors_total{code}` | Counter | `GlobalExceptionHandler` | 全部 13 个异常分支按业务码计数 |
| `picture_rate_limited_total{type}` | Counter | `RateLimitFilter` | `type=login/register/captcha/upload/interaction`，限流响应不经过异常处理器 |
| `picture_upload_total{result}` | Counter | `PictureServiceImpl#uploadPicture` | 成功/失败，失败包含校验、COS、入库异常 |
| `picture_compress_seconds` | Timer | `FileManager#compressIfNeeded` | 开启直方图桶，可算 p95 |
| `picture_review_pending` | Gauge | `PictureReviewPendingMetrics` | 每 60 秒查一次待审核数量，抓取时不打库 |
| `picture_ai_tokens_total{direction}` | Counter | `DeepSeekPictureAiTagger` | 按 `prompt/completion` 累计 token，用于估算成本 |
| `picture_search_result_total{hit,mode}` | Counter | `PictureController` | 公开搜索召回结果；`hit=0` 算零结果率，`mode=hybrid/semantic/es/mysql` 区分路径 |
| `picture_rag_embed_total{kind,result}` / `picture_rag_embed_seconds{kind}` | Counter / Timer | `DashScopeEmbeddingClient` | 文本 / 图片向量化成功率与耗时，Timer 带直方图 |
| `picture_rag_index_total{action,result}` | Counter | `ElasticsearchRagIndexService` | 写入 / 删除 / 重建 / 守卫结果，`result=stale` 表示别名不兼容 |
| `picture_rag_index_stale` | Gauge | `RagIndexHealthTask` | 1 = 别名指向不兼容索引，需要 reindex；RAG 关闭时不注册 |
| `picture_rag_search_seconds{mode}` | Timer | `ElasticsearchRagSearchService` | 混合 / 语义检索耗时 |
| `picture_rag_degraded_total{reason}` | Counter | 检索 / 问答降级点 | `disabled` / `embed_timeout` / `embed_error` / `es_error` / `unsupported_filter` / `deep_page` 等 |
| `picture_rag_ask_total{result}` / `picture_rag_ask_duration_seconds{result}` | Counter / Timer | `RagAskServiceImpl` | `answered` / `refused` / `limited` / `invalid_citation` / `error` |
| `picture_rag_tokens_total{direction}` | Counter | `DeepSeekRagAnswerGenerator` | 问答 token 成本，与 AI 标注分开核算 |
| `picture_rag_duplicate_total{result}` | Counter | `RagDuplicateServiceImpl` | 近重复 `hit` / `suspect` / `miss` / `error` |
| `picture_interaction_total{action,type}` | Counter | `PictureInteractionServiceImpl` | 收藏/点赞的 add/remove 量，可用于排查异常刷量 |
| `notification_sent_total{type}` | Counter | `NotificationServiceImpl` | 按通知类型统计发送量，`REVIEW_REJECT` 突增通常意味着审核或机审策略异常 |
| `notification_read_total` | Counter | `NotificationServiceImpl` | 已读条数，粗略反映通知触达效果 |
| `picture_search_meta_total{type}` | Counter | `SearchController` | 联想 / 热词请求量，用于判断搜索入口使用情况 |
| `picture_share_total{result}` | Counter | `ShareController` | 分享卡片渲染量；`not_found` 突增通常意味着有人在批量探测图片 id |
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
| `PictureReviewRejectNotificationSpike` | `notification_sent_total{type="REVIEW_REJECT"}` 5 分钟速率 > 1/s | 10 分钟 |
| `PictureInteractionLatencyHigh` | 收藏/点赞接口 p95 > 200ms | 5 分钟 |
| `PictureRagEmbeddingFailures` | `picture_rag_embed_total{result="fail"}` 5 分钟速率 > 0.2/s | 5 分钟 |
| `PictureRagDegraded` | `picture_rag_degraded_total` 5 分钟速率 > 0 | 5 分钟 |
| `PictureRagInvalidCitation` | `picture_rag_ask_total{result="invalid_citation"}` 1 小时速率 > 0 | 5 分钟 |
| `PictureRagIndexStale` | `picture_rag_index_stale` > 0 | 5 分钟 |
| `PictureRagAskFailureRateHigh` | `picture_rag_ask_total{result=~"error|limited|invalid_citation"}` 5 分钟速率 > 0.1/s | 10 分钟 |

> 8124、9090、15692 都不要映射到公网；Grafana 只绑定回环地址，并务必修改 `.env` 中的默认密码。
