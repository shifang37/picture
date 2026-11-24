# 前端实现

> 本页是 [README](../README.md) 的展开说明，覆盖首页瀑布流与无限滚动、管理端数据看板、构建拆包与代码检查。

## 首页瀑布流与无限滚动

首页不再是固定栅格分页，改为按图片比例排布的瀑布流：

- **布局**：用接口返回的 `picWidth` / `picHeight` 计算高宽比，按「放入当前最矮的一列」分配到 1–6 列，列数随容器宽度变化；封面用 `aspect-ratio` 在图片加载前占位，横图、竖图都不裁切，滚动时没有布局跳动；
- **加载**：VueUse `useInfiniteScroll` 监听窗口滚动，距底部 400px 时请求下一页（每页 20 条），按 id 去重，避免滚动期间有新图过审导致重复；加载失败显示重试按钮，全部加载完显示「没有更多了」；
- **图片**：`loading="lazy"`、`decoding="async"`，缩略图尺寸按列宽 × 设备像素比取 300–600 的档位，减少列表流量；
- **状态进 URL**：搜索词、分类、标签、排序写入查询参数，刷新或分享链接都能还原；切换条件时回到页面顶部；
- **排序切换**：最新（默认）/ 最多收藏 / 最多点赞 / 最多下载，切换后回到顶部并重新分页；卡片上直接展示收藏、点赞、下载计数，悬浮时出现收藏/点赞按钮，让「最热」的排序依据可见且可操作；
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
- 看板顶部新增「运维操作」：**重建搜索索引**（`POST /api/search/reindex`）、**重算互动计数**（`POST /api/interaction/recount`）、**重算专辑计数**（`POST /api/album/recount`），三个按钮都需二次确认，成功后提示实际处理条数；搜索未启用时重建索引会直接给出后端的「未启用 Elasticsearch 搜索」提示，不会静默失败。
- 「RAG 检索与问答」状态卡：展示索引文档数、待处理 / 失败数量、文本 / 图片模型、维度、语料与模型版本、索引名与别名；`stale=true` 时显示红色告警并提供「重建向量索引」（`POST /api/rag/reindex`，二次确认）；RAG 关闭时只显示「未启用」说明，不请求管理员状态接口。

## 前端构建与代码检查

- **路由懒加载**：只有首页静态导入，其余页面（含全部管理端页面）按路由拆包，首屏不再加载管理端代码；
- **组件按需引入**：Ant Design Vue 由 `unplugin-vue-components` 按需解析，`main.ts` 不再 `app.use(Antd)`；生成的 `components.d.ts` 已提交，模板里的 `a-xxx` 有完整类型；
- **路由元信息**：权限写在路由 `meta.requiresLogin` / `meta.requiresAdmin`，`access.ts` 只读 `to.meta`，新增页面不用再改权限判断；
- **ESLint**：扁平配置 `eslint.config.js`，`npm run lint` 检查，CI 在格式检查之后执行；
- **构建体积**：入口 chunk 从改造前的 1,664 kB（gzip 518 kB）降到约 643 kB（gzip 212 kB），管理端页面均为独立 chunk；数据看板的 ECharts 分包约 578 kB（gzip 199 kB），只在进入看板时加载；
- 前端测试：133 个 Vitest 用例 + 41 个 Playwright 用例。
