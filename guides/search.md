# 搜索与检索

> 本页是 [README](../README.md) 的展开说明，覆盖搜索联想与热词、Elasticsearch 全文检索、BM25 + kNN 混合检索，以及 RAG 相似图、近似去重与带引用问答。

## 搜索体验

首页搜索框支持联想、本地历史与热词，结果列表里关键词高亮。

### 接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/search/suggest?q=&limit=10` | 公开 | 名称 / 标签 / 分类三组前缀联想；`q` 1–32 字符，`limit` 1–20 |
| GET | `/api/search/hot?limit=10` | 公开 | 热门标签与热门分类（带公开图片数量），`limit` 1–20 |

- **固定走 MySQL**，不放进 `PictureSearchService`：`SEARCH_ENGINE=mysql`（默认）时同样可用，Elasticsearch 只是列表召回的另一种实现；
- 只匹配公开图片（`isDelete = 0 AND reviewStatus = 1`）：待审核 / 已拒绝内容的名称不会通过联想被探测到；
- 只用前缀匹配（`LIKE 'kw%'`）才能命中 `idx_name` / `idx_tag_picture`，不做 `%kw%` 全表扫描；名称完全匹配优先、更短的名称靠前；
- 结果进 Redis `searchMeta` 缓存（TTL 10 分钟），重复输入直接命中；空结果同样缓存，避免用随机关键词打穿数据库；
- 指标 `picture_search_meta_total{type=suggest|hot}`。

### 前端

- `SearchBox` 用 `a-auto-complete` 分组展示联想（图片 / 标签 / 分类）：输入 1 个字符触发、防抖 250ms、空输入不请求；同一关键词在三个分组里只出现一次（图片 > 标签 > 分类），点击建议直接按该关键词搜索；
- 搜索历史只存 localStorage（`picture:search-history`，最多 10 条、去重、可一键清空），不上报后端；隐私模式写入失败时静默降级；
- 热词来自 `/api/search/hot`，常驻搜索框下方；空结果文案会带上当前关键词并指回这一行热词；
- 高亮用 `utils/highlight.ts` 把文本切成 `{ text, matched }` 片段后逐段文本插值渲染，**不使用 `v-html`**：`<script>`、`&`、正则元字符等输入天然安全；
- 只有真正发起查询时才更新高亮关键词，边输入边联想不会让列表闪烁。

## 全文搜索（Elasticsearch）

公开列表（`POST /api/picture/list/page/vo`）默认仍走 MySQL 模糊查询。设置 `SEARCH_ENGINE=es` 后，改由 Elasticsearch 召回图片 id，再回 MySQL 取数据并再次过滤 `reviewStatus = 1`：

- **MySQL 是唯一事实来源**：索引里只有已通过审核的图片，索引延迟最多导致「少搜到」，不会泄露未过审内容；
- **只接管公开列表**：「我的图片」和管理端列表继续走 MySQL；
- **可降级**：Elasticsearch 调用异常时自动回落到原 MySQL 查询并记录 warn 日志，首页搜索保持可用；
- **索引**：`picture_v1`，别名 `picture`；名称、简介使用内置 `cjk` 分析器（不需要 IK 插件），名称权重为 3；分类、标签、格式等使用 keyword；
- **排序**：有搜索词时按相关度，否则按请求字段（默认 `createTime` 倒序）；排序字段沿用 MySQL 白名单，包含 `downloadCount` / `favoriteCount` / `likeCount` 三个热度字段；
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

### 混合检索（BM25 + kNN，M3）

`RAG_ENABLED=true` 且 `SEARCH_ENGINE=es` 时，公开列表额外支持 `searchMode`（默认 `keyword`，行为与未接入 RAG 完全一致）：

| searchMode | 行为 |
|---|---|
| `keyword` | 只走 BM25，与接入 RAG 前逐条一致 |
| `hybrid` | BM25 与 kNN（`captionVector`）各取 top 50，RRF（k=60）融合后分页 |
| `semantic` | 纯 kNN，用于以文搜图和调试对比 |

- **生效条件**：只有带 `searchText` 的请求才启用；RAG 或 ES 未开启、以及非法之外的降级场景，`hybrid` / `semantic` 自动按 `keyword` 处理，并记 `picture.rag.degraded{reason="disabled"}`；
- **filter 对齐**：kNN 带 `reviewStatus=1`、分类、标签、用户；向量索引没有格式/尺寸等字段，出现这类精确条件时整体回落 BM25（`reason="unsupported_filter"`），宁可不融合也不返回不满足条件的结果；
- **缓存**：查询向量在进程内缓存 10 分钟；hybrid 结果单独缓存在 Redis 5 分钟（`picture:rag:cache:*`，**不进**公开列表缓存，避免与 keyword 串结果），重建向量索引后清空；
- **降级**：embedding 超时/失败或 kNN 异常时回落 BM25，BM25 也异常时继续回落 MySQL；查询向量单次调用、默认 3 秒超时、不重试，避免故障时把一次搜索拖到数秒；
- **分页**：融合窗口默认覆盖前 500 条，超过后回落 BM25；`total` 取两路并集大小（仅用于展示）；
- **指标**：`picture.search.result{hit,mode}` 区分 `hybrid` / `semantic` / `es`；新增 `picture.rag.search{mode}`、`picture.rag.degraded{reason}` 与查询向量缓存命中率；
- **模式校验**：非法 `searchMode` 返回 40000；首页在 `GET /api/rag/config` 返回 `hybridSearch=true` 时自动带 `searchMode=hybrid`，RAG 关闭时不传，保持关键字搜索。

本地评估与逐条对照见 `docs/rag/m3-report.md` 与 `docs/rag/tools/eval-hybrid.mjs`（`docs/` 为本地留存，不随仓库提交）。

## RAG 检索与问答

在 BM25 之外增加一条**默认关闭**的向量链路：语料与图片双塔向量化、ES `dense_vector` + HNSW、RRF 融合、相似图、近似去重与带引用问答。关闭时不会注册任何向量 Bean、不产生外部调用，公开搜索仍走原来的 BM25 / MySQL，行为与接入前逐条一致。

### 能力与入口

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| 混合检索 | 首页搜索框 | RAG + ES 开启时，带关键词的公开列表自动传 `searchMode=hybrid`；占位文案改为「描述你想要的图，例如：暖色调日落海报」 |
| 图库问答 | `/ask` 页面 / 顶栏「图库问答」 | 登录后提问，回答带 1–4 张引用卡片，可点回详情；拒答与降级是独立状态 |
| 相似图片 | 详情页底部 | 用源图 `imageVector` 做 kNN，最多 6 张；无数据时整个区块不渲染 |
| 近似重复提示 | 审核通过后异步检测 | 余弦 ≥0.985 才发 `RAG_DUPLICATE` 站内通知，只提示不自动拒绝；0.97–0.985 只记指标 |
| 运维状态 | 管理端数据看板 | 索引文档数、待处理/失败、文本/图片模型、语料版本、别名 `stale` 与「重建向量索引」按钮 |

### 接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/rag/config` | 公开 | 前端开关：`enabled` / `hybridSearch` / `askEnabled` / 每日限额；只返回布尔值与公开限额 |
| POST | `/api/rag/ask` | 登录 | 请求 `{question, topK?}`；返回 `answer`、`citations`、`refused`、`degraded` |
| GET | `/api/picture/similar?id=&limit=6` | 公开 | 源图不可见返回 40400；RAG 关闭 / 无图片向量返回空数组 |
| GET | `/api/rag/status` | 管理员 | 索引与向量化状态；RAG 关闭或搜索非 ES 时返回 50001 |
| POST | `/api/rag/reindex` | 管理员 | 全量重建并原子切换别名；有失败时不切换，旧索引继续可用 |

### 开关与配置

```yaml
app:
  rag:
    enabled: ${RAG_ENABLED:false}          # 总开关，关闭时不注册向量 Bean
    embedding:
      api-key: ${DASHSCOPE_API_KEY:}       # 只从环境变量读，不写仓库
      text-model: ${RAG_TEXT_MODEL:text-embedding-v4}
      text-dim: ${RAG_TEXT_DIM:1024}
      image-model: ${RAG_IMAGE_MODEL:multimodal-embedding-v1}
      image-input: ${RAG_IMAGE_INPUT:base64}  # 私密图/防盗链场景默认 base64
    search:
      fusion: rrf
      rrf-k: 60
      bm25-top: 50
      knn-top: 50
      num-candidates: 200
    ask:
      daily-limit: ${RAG_ASK_DAILY_LIMIT:10}
      top-k: 8
      max-answer-chars: 400
      cache-seconds: 3600
    duplicate:
      suspect-threshold: 0.97              # 只记指标
      duplicate-threshold: 0.985           # 才发站内通知
```

- 生效条件：`RAG_ENABLED=true` 且 `SEARCH_ENGINE=es`；问答另需 `DEEPSEEK_API_KEY`（复用 `app.ai` 的 DeepSeek 地址与模型）；
- `RAG_ENABLED=true` 但 `SEARCH_ENGINE=mysql` 时只打 WARN，不装配向量实现，也不调用外部服务；
- 配置优先级沿用「内置默认 < `application-local.yml` < 环境变量」。

### 成本与限流

- **向量化**：文本 `text-embedding-v4`（1024 维）；图片 `multimodal-embedding-v1`（1024 维，实测 `image_tokens=128/张`）。108 张图全量回填约 1.4 万图片 token + 文本 token，单次重建耗时 30–44s；
- **问答**：真实回答平均约 1158 prompt + 47 completion tokens（M5 实测），无候选时不调用模型（0 token）；回答缓存 1 小时，同一问题第二次命中缓存约 15ms；
- **限额**：每用户每日 10 次（Redis `picture:rag:ask:limit:<userId>:<yyyyMMdd>`），限额在缓存之前判断，缓存命中同样计数；超限返回 `42900`；
- **缓存**：查询向量进程内 10 分钟，hybrid 结果 Redis 5 分钟，回答 Redis 1 小时；重建索引后清空 hybrid 缓存，回答缓存 key 含索引名，切别名后自然失效。

### 降级与隐私

| 场景 | 行为 | 指标 |
| --- | --- | --- |
| RAG / ES 未开启 | 搜索按 keyword，问答返回固定文案 `degraded=true` | `picture.rag.degraded{reason="disabled"}` |
| 查询向量化超时 / 失败 | 搜索回落 BM25 当前页；问答 `degraded=true` | `degraded{reason=embed_timeout/embed_error}` |
| kNN / ES 异常 | 搜索回落 BM25；相似图返回空；问答降级 | `degraded{reason=es_error}` |
| 不支持的精确过滤 / 深分页 | 搜索整体回落 BM25 | `degraded{reason=unsupported_filter/deep_page}` |
| 模型输出非法 / 引用越界 | 整条回答替换为拒答，不返回任何模型文本 | `picture.rag.ask{result="invalid_citation"}` |

隐私边界由代码保证，而不是靠提示词：ES kNN 只召回 `reviewStatus=1` → MySQL 回表再次过滤 → 只把公开字段拼进 prompt → 引用 id 必须 ⊆ 本次候选集 → 引用再回表过滤。未过审 / 私密图片的名称、简介和 id 不会出现在回答或引用中。

### 可观测

三个 Grafana 面板（`云图库 · 服务概览` 看板底部）：**搜索零结果率（按检索路径）**、**hybrid vs keyword 召回命中率**、**问答失败 / 降级**。新增 RAG 告警见 [部署与可观测性 · 告警规则](operations.md#告警规则)；`picture_rag_index_stale` 由 `RagIndexHealthTask` 每 5 分钟把 `RagStatusVO.stale` 同步成 Gauge，别名被外部改坏时可直接告警。

> 评估与验收记录：`docs/rag/baseline.md`、`m2-report.md` ~ `m6-report.md`、`final-report.md`（`docs/` 为本地留存，不随仓库提交）。
