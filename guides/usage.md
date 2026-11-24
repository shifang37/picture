# 用户功能手册

> 本页是 [README](../README.md) 的展开说明，覆盖上传与配额、个人中心、审核流程、回收站、下载、收藏点赞、站内通知、作者主页、批量处理与专辑。

## 普通用户上传图片

1. 登录后通过顶部「创建图片」进入上传页，也可以直接访问 `/add_picture`
2. 支持 jpg / jpeg / png / webp / heic，单张不超过 10MB；前端会先把 HEIC 转成 JPG、把超过 2MB 的图片压到 2MB 以内，后端再同时校验后缀、MIME 类型和文件头
3. 上传成功后填写名称、简介、分类、标签，提交后跳转到图片详情
4. 「我的图片」（`/my_pictures`）可以查看自己上传的全部图片，按待审核 / 已通过 / 已拒绝筛选，并支持编辑、删除；删除后进入回收站，保留期内可由管理员恢复

上传时前后端都会按需压缩原图，降低存储与下载流量：

- 前端：长边超过 `1920` 或体积超过 `2MB` 时用 Canvas 压缩，PNG 保留透明通道，JPEG 按质量阶梯降到目标体积；压缩失败回退原文件；
- 后端：长边超过 `1920` 的图片等比缩小；JPEG 超过约 `1.5MB` 时以 `0.85` 质量重新编码；
- PNG 只缩放、保留透明通道；WebP 当前保持原文件（JDK 没有内置 WebP 编码器）；
- 压缩前只读取图片头校验像素数，超过 `2000 万` 像素直接拒绝上传，避免解压炸弹耗尽内存；
- 大图按长边用 `ImageReadParam.setSourceSubsampling` 降采样解码，上传接口再用信号量限制同时解码的图片数（默认 2，可用 `UPLOAD_MAX_CONCURRENT_DECODES` 调整），避免 768MB 容器被并发解码打爆；等待解码许可超时返回 `42900`；
- 压缩失败会自动回退原文件，不阻断上传；数据库中的 `picSize` 为压缩后的大小；
- 手机竖拍照片会按 EXIF 方向纠正，含 GPS 的 JPEG 会重新编码剥离元数据，详见下文「上传体验与存储配额」。

上传接口 `POST /api/picture/upload` 使用 `multipart/form-data`：`file` 为图片文件，`id` 可选（重新上传已有图片时传）。该接口只要求登录，普通用户也可以调用；响应中的 `duplicated=true` 表示命中了同用户秒传。

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
8. 机审开启时，上传后几秒内会写回机审结果，审核页按机审疑似 / 违规优先展示；默认的 `assist` 模式不改变审核状态，详见 [内容审核与 AI 辅助](moderation.md)
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

## 上传体验与存储配额

### 前端上传链路

1. 选择文件后先做格式与体积校验（jpg / jpeg / png / webp / heic，单张 ≤10MB），不合格直接提示、不发请求；
2. HEIC / HEIF 用 `heic2any` 转成 JPEG，库通过动态 `import()` 加载（约 1.35MB 的独立 chunk），只在选择 HEIC 时才下载；
3. 长边超过 1920 或体积超过 2MB 时用 Canvas 压缩：PNG 保留透明通道、WebP 交给浏览器编码、其余输出 JPEG；JPEG 仍超目标体积时按 0.85 → 0.70 → 0.55 → 0.40 逐级降质量，压缩结果反而更大时回退原文件；
4. 单张与批量上传都显示百分比进度（axios `onUploadProgress`），批量上传每个文件一行进度与阶段文案；
5. 上传前读取 `/api/user/quota` 做客户端预检查，配额不足时直接提示「已用 / 上限」，不发起上传；服务端仍会做最终校验；
6. 详情页预览改用 `imageMogr2/thumbnail/1600x` 的缩略图（`getThumbnailUrl(url, 1600)`），不再是原图直出；「免费下载」仍走 `downloadPicture(id)` 拿原图。

### EXIF 方向与元数据

- 使用 `metadata-extractor` 读取 EXIF Orientation 与 GPS IFD；
- 方向 3 / 6 / 8 分别旋转 180° / 90° / 270°，**先旋转再缩放**，竖拍照片不会变成横的；
- 存在方向或 GPS 信息时重新编码 JPEG，顺带剥离 EXIF（包括拍摄地点坐标）；PNG / WebP 不做重编码，元数据保持原样；
- 解析失败或重编码失败都回退原文件，不阻断上传；指标 `picture_exif_total{action=rotated|stripped}`。

### 服务端上限

| 配置 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `app.upload.max-file-size-bytes` | `UPLOAD_MAX_FILE_SIZE_BYTES` | 10485760（10MB） | 单张图片上限，`FileManager` 校验 |
| `app.upload.max-request-size-bytes` | `UPLOAD_MAX_REQUEST_SIZE_BYTES` | 12582912（12MB） | multipart 请求体上限 |
| `spring.servlet.multipart.max-file-size` | — | 10MB | 必须与上面保持一致 |
| `spring.servlet.multipart.max-request-size` | — | 12MB | — |

- nginx 反向代理 `/api/` 的 `client_max_body_size` 已同步为 `12m`，否则请求会先被 nginx 拒绝；
- 放宽体积后，`20MP` 像素上限与 `ImageDecodeLimiter` 并发闸门保持不变，避免并发解码把 768MB 容器打爆。

### 存储配额

`V13__user_quota_and_picture_hash.sql`：

- `user` 新增 `storageQuota` / `storageUsed` / `pictureQuota` / `pictureUsed`，默认 **1GB / 1000 张**；
- `picture` 新增 `fileHash`（SHA-256）与 `idx_user_hash(userId, fileHash)`；
- 迁移末尾按 `picture` 回填历史用量，老用户不会显示 0 已用。

语义与一致性：

- 配额只统计**未删除**图片：软删除（进回收站）立即释放额度，恢复时重新占用；回收站保留期内 COS 仍占用存储，因此**配额 ≠ COS 实际用量**；
- 预留用一条原子 SQL 同时校验存储与数量（`UPDATE user ... WHERE ... AND storageUsed + ? <= storageQuota AND ...`），并发上传不会把用量加超；
- 先预留、再上传 COS；上传或入库失败按相反增量补偿；释放走独立的 `adjustQuota`（不做额度校验），保证「删图一定能腾出空间」；
- 管理员默认不受配额限制（`QUOTA_EXEMPT_ADMIN=true`）；`QUOTA_ENABLED=false` 可全局关闭配额；
- 删除用户/批量删除按作者聚合释放；恢复时额度不足返回 `40000`，提示先调整配额。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/user/quota` | 登录 | 我的配额：`storageUsed/storageQuota/pictureUsed/pictureQuota/exempt` |
| POST | `/api/user/quota/update` | 管理员 | `{ userId, storageQuota?, pictureQuota? }`，立即生效 |
| POST | `/api/user/quota/recalc` | 管理员 | `{ userId? }`，按 `picture` 重算已用；不传 userId 时全量分批 |

上线顺序：执行 `V13` → 发布后端 → 调用一次全量 `recalc`（修正发布窗口内旧代码上传的偏差）→ 观察 `picture_quota_rejected_total` 与上传失败率。

### 同用户秒传

- 哈希在“准备阶段”对**最终要上传的文件**计算，即先旋转、再压缩、最后算 SHA-256，保证哈希与实际存储内容一致；
- 命中条件：同一 `userId` + 相同 `fileHash` + `isDelete = 0`，重传时排除当前图片 id；
- 命中后直接返回已有图片并置 `duplicated = true`：不写 COS、不新增行、不重复占用配额，前端提示「已存在相同图片，已为你复用」；
- 不同用户上传同一文件**不**去重（避免越权与版权争议）；历史数据 `fileHash` 为 `NULL`，天然不命中；
- 指标 `picture_upload_duplicate_total`。

### 验证

```powershell
# 后端：上传上限、EXIF、配额、秒传与迁移用例
cd picture-backend; .\mvnw.cmd -o test

# 前端：压缩/配额工具单测 + 上传主链路 E2E
cd picture-frontend; npm run test; npx playwright test e2e/m4.spec.ts
```

## 下载与下载次数统计口径

详情页的「免费下载」走同源后端接口 `GET /api/picture/download/{id}`：

- 后端用 COS SDK 读取原图并**流式写回**，响应带 `Content-Disposition: attachment`，浏览器直接保存文件；不依赖 COS 桶的 CORS 配置，也不会把真实对象地址暴露给前端；
- 只有文件字节完整写给客户端后才自增下载次数，用户取消保存、网络中断不会计数；
- 自增前用 Redis 做「**用户 / IP + 图片 + 天**」去重（key 形如 `picture:download:dedup:20261112:<pictureId>:user:<userId>`，未登录时用 `ip:<remoteAddr>`，TTL 到当天 24:00），同一用户重复点击同一张图每天只计一次；
- Redis 故障时下载仍可正常进行，但本次不计数（宁可少计，也不让刷量在故障期间穿透）；
- 旧的无去重 `POST /api/picture/download/{id}` 计数接口已移除。

> 下载次数现在代表「按用户/IP 按天去重后的成功下载次数」，不再等于按钮点击次数。

## 收藏与点赞

浏览者可以对**已通过审核**的图片收藏、点赞；作者与浏览者都能从列表/详情看到计数，首页支持按热度排序。

### 数据模型

- `picture_interaction(pictureId, userId, type, createTime)` 是权威关系数据，`type` 为 `favorite` / `like`；唯一键 `(pictureId, userId, type)` 保证同一用户对同一张图只有一条，取消操作直接删行（不做逻辑删除，避免唯一键与「重新收藏」冲突）；
- `picture.favoriteCount` / `picture.likeCount` 是冗余计数，列表展示与排序直接读它，不再 `COUNT` 关系表；
- `V10__picture_interaction.sql` 同时补了 `(isDelete, reviewStatus, favoriteCount|likeCount|downloadCount)` 三个索引，让「最新 / 最热 / 最多下载 / 最多收藏」都能走索引排序。

### 接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/interaction/add` | 登录 | 收藏 / 点赞，幂等；已存在时直接返回当前状态 |
| POST | `/api/interaction/remove` | 登录 | 取消收藏 / 取消点赞，幂等 |
| GET | `/api/interaction/status?pictureId=` | 公开 | 状态与计数；未登录时选中状态恒为 `false` |
| POST | `/api/interaction/my/page` | 登录 | 我的收藏 / 我的点赞，只返回已过审图片，按互动时间倒序 |
| POST | `/api/interaction/recount?pictureId=` | 管理员 | 按关系表重算计数（不传 id 则全量分批重算） |

### 一致性约定

- **先写关系行、后改计数**，两者同事务：`INSERT IGNORE` 返回 0 表示已存在，此时不累加计数；并发重复请求只会加一次；
- 计数用 `UPDATE picture SET x = GREATEST(0, x + ?)` 原子增减，取消操作不会把计数减成负数；图片已被物理删除时不更新；
- 互动**不失效公开列表缓存**：列表里的计数最多滞后一个缓存周期（L1 30 秒 / L2 5 分钟），详情与状态接口实时；但会发一次 `picture.changed` 事件，让 Elasticsearch 的热度排序字段同步；
- 计数漂移可用 `POST /api/interaction/recount` 修复（管理员），全量重算按主键游标每批 500 条，不会长时间锁表；
- 只能对已过审图片互动；待审核/已拒绝图片对他人统一返回 40400（作者自己也一样），避免用互动接口探测未公开内容；
- 写接口按用户限流 120 次/分钟（未登录按 IP），指标 `picture_interaction_total{action,type}`。

### 前端交互

- 详情页与首页卡片共用 `useInteraction` 组合式函数：登录拦截、乐观更新、失败回滚只实现一次，卡片只是把按钮切成紧凑模式（只显示图标与计数）；
- 首页卡片右上角的收藏/点赞入口在悬浮或键盘聚焦时出现（触屏设备常显），按钮 `@click.stop`，点互动不会误进详情页；
- 点击后先本地 +1 / 选中，再用服务端返回的真实计数覆盖；失败回滚到点击前的快照并提示；
- 未登录点击跳转 `/user/login?redirect=<当前地址>`，登录后回到原页面。

### 热度排序

公开列表复用 `POST /api/picture/list/page/vo`，`sortField` 新增三个取值：

```json
{ "sortField": "favoriteCount", "sortOrder": "descend" }
```

- MySQL 与 Elasticsearch 两条路径都支持 `downloadCount` / `favoriteCount` / `likeCount`，非法字段仍返回 40000；
- 热度字段同值极多，排序时自动追加 `id ASC` 次级键，滚动分页不会重复或漏项；
- ES 索引文档新增 `favoriteCount` / `likeCount` 字段（`long`），切换新索引时由全量重建写入；互动发生后会更新该图片的索引快照。

## 站内通知

审核结论、账号安全事件与系统公告会写入站内通知，顶栏铃铛显示未读数，`/notifications` 查看全部。

### 数据模型

`notification` 表（`V11__notification.sql`）：

- `type` 取 `REVIEW_PASS` / `REVIEW_REJECT` / `PASSWORD_RESET` / `ROLE_CHANGED` / `SYSTEM`；
- `dedupeKey` + 唯一键 `(userId, dedupeKey)` 做幂等：审核通知的键是 `REVIEW_PASS:<pictureId>` / `REVIEW_REJECT:<pictureId>`，重复投递、机审兜底重发都不会产生第二条；密码重置、角色变更、公告的 `dedupeKey` 为 `NULL`（MySQL 唯一索引允许多个 `NULL`），每次事件都提醒；
- 索引 `(userId, isRead, createTime)` 同时支撑列表与未读数。

### 触发点与可靠性

| 事件 | 触发位置 | 通知内容 |
| --- | --- | --- |
| 人工审核（单张/批量） | `PictureServiceImpl#doPictureReview` / `#batchReview` | 通过：`你的图片《名称》已通过审核`；拒绝：附审核意见 |
| 机审自动处置 | `MachineReviewServiceImpl#applyMode` | 与人工审核同一份文案与幂等键 |
| 管理员重置密码 | `UserController#resetPassword` | 安全提醒（不包含新密码） |
| 管理员调整角色 | `UserController#updateUser` | 只有角色真的变化时才提醒 |
| 系统公告 | `POST /api/notification/broadcast` | 每个用户一行，限流 1 次/分钟 |

- 业务代码只发 Spring 事件（`NotificationEventPublisher`），由 `NotificationEventListener` 以 `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` 落库：审核回滚不会留下「操作失败但通知已发」，机审这类无事务调用也能收到；
- 通知写入失败只记日志，绝不影响审核、重置密码等主流程；
- 批量审核按作者聚合，整批只发一次事件、一次批量插入（每批 ≤ 500 条）；
- 自己审核自己的图片不通知；`unread` 超过 `app.notification.max-unread`（默认 200）时把最旧的未读自动置为已读，保证红点一定能消掉；
- 每天 04:10 清理「已读且超过 `retention-days`（默认 90 天）」的通知，未读通知永远保留；
- 系统公告的发送入口在 `/notifications` 顶栏的「发公告」（仅管理员可见）：标题 ≤128、正文 ≤1024，弹窗内二次确认后发送，成功后提示「公告已发送给 N 位用户」。

### 接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/notification/page` | 登录 | 分页，`onlyUnread` 只看未读 |
| GET | `/api/notification/unread-count` | 登录 | 未读数 |
| POST | `/api/notification/read` | 登录 | 批量标记已读 |
| POST | `/api/notification/read/all` | 登录 | 全部已读 |
| POST | `/api/notification/delete` | 登录 | 批量删除 |
| POST | `/api/notification/broadcast` | 管理员 | 系统公告 |

- 所有 SQL 都带 `userId` 条件，混入他人的通知 id 会被静默忽略，避免枚举；
- 前端 `useNotificationStore` 登录后每 60 秒轮询一次未读数，页面不可见时暂停、切回前台立即刷新；通知点击跳转 `bizType=PICTURE` 对应的详情页并标记已读。

### 配置项

| 配置 | 环境变量 | 默认 |
| --- | --- | --- |
| `app.notification.retention-days` | `NOTIFICATION_RETENTION_DAYS` | 90 |
| `app.notification.max-unread` | `NOTIFICATION_MAX_UNREAD` | 200 |
| `app.notification.purge-batch-size` | `NOTIFICATION_PURGE_BATCH_SIZE` | 1000 |

## 作者主页

任意访客都可以访问 `/author/:id` 查看作者的公开主页，图片详情页的作者名也可以直接点击进入。

- 数据来自 `GET /api/user/public/{id}`：公开资料（昵称、头像、简介、加入时间）与作品数、总下载、总收藏、总点赞；
- 聚合 SQL 固定为 `isDelete = 0 AND reviewStatus = 1`，账号、角色、配额等内部字段不会出现在响应里；
- 统计结果缓存 5 分钟（`userProfile`），上传/审核/互动带来的变化由 TTL 兜底；删除用户时立即失效，并清理其专辑；
- 作品列表复用公开列表接口（按 `userId` 过滤、`createTime` 倒序），前端用通用 `PictureWaterfall` 组件无限滚动；空状态、404 与加载失败都有独立页面态。

## 批量后处理

「我的图片」可以表格多选后批量编辑分类/标签/简介、批量删除；「批量上传」完成后也能对成功项一次性设置信息。

### 接口与语义

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/picture/batch/edit` | 登录 | 批量编辑分类/标签/简介，**整批原子**，任一图片不存在/无权操作则整批不修改 |
| POST | `/api/picture/batch/delete` | 登录 | 批量删除（移入回收站），逐项返回失败明细，单张失败不影响其他图片 |

- 批量编辑只更新请求中出现的字段：`null` / 未传表示不修改，`tags` 传空数组表示清空标签；标签关系表与 `picture.tags` 在同一事务里整体替换；
- 只有值真的发生变化的图片才更新，未变化的图片不刷新 `editTime`、不重置审核状态；普通用户改动已过审图片的内容后重新进入待审核，管理员操作不改变审核状态；
- 批量删除复用回收站逻辑：逻辑删除 + 清理 AI 建议与标签关系，整批只失效一次列表缓存、只发一条批量事件；
- 批量写接口按用户限流 30 次/分钟，指标 `picture_batch_seconds{operation=edit/delete}`；接口返回 `PictureBatchResult`（与审核结果字段一致但独立定义）；
- 前端标签只有「动过但为空」时才提交空数组，转换逻辑收敛在 `utils/batchEdit.ts` 并有 Vitest 用例；批量上传通过上传结果里的 `pictureId` 收集成功项，一次性调用批量编辑。

## 图片专辑

上传者可以把作品按主题分组（如「风景」「人像」），公开专辑可以分享给他人；专辑只负责组织，不会移动或删除图片。

### 数据模型（`V12__album.sql`）

- `album`：`userId`、`name`、`description`、`coverPictureId`、`pictureCount`（作者视角总数，含未过审）、`isPublic`、时间戳与 `isDelete`；索引 `(userId, isDelete, createTime)`、`(isPublic, isDelete, createTime)`；
- `album_picture`：联合主键 `(albumId, pictureId)`，纯关系表不做逻辑删除，重复加图由 `INSERT IGNORE` 跳过；`pictureId` 索引支撑按图片反查与级联清理；
- `pictureCount` 与关系表在同一事务内用 `UPDATE album SET pictureCount = GREATEST(0, pictureCount + ?)` 原子增减；管理员可用 `POST /api/album/recount` 按关系表重算修复漂移；
- 公开口径只统计已过审图片：`publicCount` 在查询时按 `reviewStatus = 1` 聚合，公开图片列表与封面回退同样只看已过审图片；
- 封面：配置了 `coverPictureId` 且图片可见时直接使用；否则公开视角回退到最新一张已过审图片、作者视角回退到最新一张，封面被移除时自动置空/回退；
- 私密专辑对非作者统一返回 40400；删除专辑只删除专辑与关系、不删除图片；删除用户时级联清理其专辑。

### 接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/album/add` | 登录 | 创建专辑（默认私密） |
| POST | `/api/album/update` | 作者/管理员 | 改名、简介、公开状态、封面（封面必须已在专辑内） |
| POST | `/api/album/delete` | 作者/管理员 | 删除专辑与关系，图片保留 |
| GET | `/api/album/get?id=` | 公开 | 专辑详情；私密专辑仅作者/管理员 |
| POST | `/api/album/my/page` | 登录 | 我的专辑（含私密），每页最多 20 |
| GET | `/api/album/public/page?userId=&current=&pageSize=` | 公开 | 公开专辑；`userId` 为空时返回全站 |
| POST | `/api/album/picture/add` | 作者/管理员 | 批量加图，≤100 张，重复自动跳过 |
| POST | `/api/album/picture/remove` | 作者/管理员 | 批量移图，不删除图片 |
| GET | `/api/album/picture/page?albumId=&current=&pageSize=` | 公开/登录 | 专辑内图片；匿名/他人只看到已过审图片 |
| POST | `/api/album/recount?albumId=` | 管理员 | 不传 id 则全量分批重算 `pictureCount` |

### 前端

- `/my/albums`：专辑卡片列表、创建/编辑/删除、公开/私密标记、公开数与总数；
- `/album/:id`：专辑详情，浏览模式为作品瀑布流，作者/管理员可切换到管理模式设置封面、移除作品；
- 「我的图片」多选后可「加入专辑」，弹窗选择目标专辑；加入成功后自动跳过重复项。
