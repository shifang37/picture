# 数据模型与运行时架构

> 本页是 [README](../README.md) 的展开说明，覆盖标签字典与关系表、登录态、缓存分级、RabbitMQ 事件拓扑，以及虚拟线程压测结论。

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

## 登录态与会话（Redis）

- 登录态存放在 Redis（Spring Session），键前缀 `picture:session:`，**重启后端不会掉线**；
- 会话 Cookie 名为 `SESSION`，有效期 7 天（`spring.session.timeout`）；
- 修改或重置密码会让该用户的其他会话立即失效；失效版本同样在 Redis 中，重启后依旧有效；
- 限流计数在 `picture:ratelimit:` 下：登录按 IP 10 次/分钟，上传按用户 60 次/分钟，收藏/点赞按用户 120 次/分钟（未登录均退化为按 IP）；
- Redis 是硬依赖：没有 Redis 时登录与鉴权不可用，本地开发请先执行 `docker compose -f docker-compose.infra.yml up -d`。

## 缓存策略

- **标签/分类字典**（`GET /api/picture/tag_category`）：Spring Cache 存 Redis（key 为 `tagCategory::…`），TTL 30 分钟；管理员新增、删除、同步字典后立即失效，首页分类 Tab 马上更新。
- **公开图片列表**（`POST /api/picture/list/page/vo`）：两级缓存，L1 是 Caffeine（最多 500 项、写入后 30 秒过期），L2 是 Redis（5 分钟 + 0~60 秒随机抖动）。「我的图片」、管理列表、详情页都不缓存。
- **失效**：审核通过/拒绝（批量审核整批只失效一次）、编辑、删除、回收站恢复、上传成功后对 `picture:list:ns` 自增，新请求换用新命名空间，旧 key 自然过期。因此审核结果立即可见，L1 最多比数据库滞后 30 秒。
- **互动不失效列表**：收藏/点赞只改计数列，列表缓存里的计数最多滞后一个周期（L1 30 秒 / L2 5 分钟），详情页与互动状态接口实时读取；这样热门图片的互动不会把列表缓存反复打穿。
- **搜索联想 / 热词**（`GET /api/search/suggest`、`/api/search/hot`）：Spring Cache 存 Redis（`searchMeta::…`），TTL 10 分钟；空结果同样缓存，避免随机关键词穿透到数据库。
- **降级**：缓存只保存公开可见的数据；Redis 读写异常时直接回源数据库，列表接口不受影响。
- 实测（300 张图片、50 次请求）：公开列表热缓存 p95 约 8.5 ms，每次失效回源 p95 约 19 ms。

## 图片事件与消息队列（RabbitMQ）

上传请求只做「校验、压缩、存 COS、入库」；机审、AI 标签与搜索索引同步由图片事件驱动，在请求之外完成，失败可重试且互不影响。

### 拓扑

- 业务交换机 `picture.event`（topic），死信交换机 `picture.dlx`；
- 每条业务队列都绑定自己的死信队列 `*.dlq`，消费者重试耗尽后转入死信，不会无限重新入队。

| 路由键 | 触发时机 | 消费队列 |
| --- | --- | --- |
| `picture.uploaded` | 新上传或重传成功 | `picture.moderation.q`、`picture.textreview.q`、`picture.search.q`、`picture.ai.q`（机审开启时 AI 消费者会跳过，等 `moderated`） |
| `picture.moderated` | 机审完成 | `picture.ai.q` |
| `picture.changed` | 人工审核、编辑、删除、回收站恢复 | `picture.textreview.q`（只有名称/简介变化时才会真正调用接口）、`picture.search.q` |
| `picture.batch.changed` | 批量审核 / 批量编辑完成（一条事件带整批 id） | `picture.textreview.q`、`picture.search.q` |

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

## 并发模型（虚拟线程评估）

JDK 21 的虚拟线程已评估但**未启用**，`spring.threads.virtual.enabled` 保持默认关闭：

- 压测对象 `GET /api/picture/get/vo`（每次请求 2 条 SQL），本地 500 张图片；
- 300 并发 × 3000 请求：平台线程 2.0–2.8k rps、p95 308–408 ms；虚拟线程 1.5–1.7k rps、p95 424–495 ms；
- 500 并发 × 3000 请求：两者吞吐接近（约 1.4–1.5k rps），虚拟线程 p95 略高（746 ms vs 604–694 ms）；
- 上传路径加 `-Djdk.tracePinnedThreads=short` 后观察到 `org.apache.http.pool.AbstractConnPool`（COS SDK 使用的 Apache HttpClient 连接池）与 `ConcurrentHashMap.computeIfAbsent` 的 MONITOR pinning；
- 结论：瓶颈是 HikariCP 的 10 个数据库连接而不是线程数，虚拟线程没有可测收益且上传路径存在 pinning，因此不保留该配置。并发模型变化后可复用同一压测方法复测。
