# 云图库 picture

一个前后端分离的图片素材管理与分享平台。普通用户登录后可以上传图片、管理自己的图片并查看审核状态；支持分类标签检索、内容审核，以及管理端的用户与图片管理。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5.16、MyBatis-Plus 3.5.18.1、MySQL 8、Flyway、Redis + Spring Session、Caffeine |
| 对象存储 | 腾讯云 COS（含数据万象图片处理） |
| 接口文档 | springdoc-openapi 2.10.0（Swagger UI） |
| 前端 | Vue 3.5、Vite 7、TypeScript 5.9、Ant Design Vue 4、Pinia、Vue Router |
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
│       ├── manager/                 # COS 文件上传封装
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
- Redis 7（本地用 `docker-compose.infra.yml` 启动）
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

### 3. 启动本地基础设施

登录态、会话失效版本和接口限流都依赖 Redis：

```bash
docker compose -f docker-compose.infra.yml up -d
```

本地 Redis 只绑定 `127.0.0.1:6379` 且默认无密码，与 `application.yml` 中的默认配置一致。

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

## 生产部署（Docker / 反向代理 / 监控）

1. 复制 `.env.example` 为 `.env`，填写数据库密码、Redis 密码和腾讯云 COS 配置
2. 执行 `docker compose up -d --build`
3. 访问 `http://localhost:8080`；前端 nginx 会把 `/api` 反向代理到后端 `8123`

说明：

- 后端以 `prod` profile 启动，默认关闭 Swagger 与 MyBatis SQL 日志（见 `application-prod.yml.example`）
- 健康检查：`/api/health`（Docker healthcheck）与 `/api/actuator/health`（Actuator）
- **监控收口**：公网 `/api` 只暴露 `health` 与 `info`，`/api/actuator/metrics` 不再可访问；确需 metrics 时使用独立管理端口（例如 `management.server.port: 8124`），该端口不要经过 nginx，只在内网/监控网络开放
- **限流来源 IP**：登录/上传限流按 Tomcat 解析后的真实 `remoteAddr` 计数；nginx 使用 `$remote_addr` 覆盖 `X-Forwarded-For`，后端不会再直接信任客户端可伪造的转发头。后端端口只应在 Docker 内网暴露，不要直接映射到公网
- **数据库迁移**：表结构由 Flyway 管理，启动时自动执行 `picture-backend/src/main/resources/db/migration` 下未执行过的脚本。新库直接执行 `V1__baseline.sql`；接入前已有表的旧库会被自动标记为基线版本 1（不重复建表），若该库结构还没升到 v3，需先各执行一次 `picture-backend/sql/legacy/upgrade_v2.sql` 和 `upgrade_v3.sql`。迁移执行后不要修改旧脚本，结构变更请新增 `V2__xxx.sql`
- **Redis 与会话**：compose 内置 `redis` 服务（AOF 持久化 + 密码认证，密码由 `REDIS_PASSWORD` 指定，务必改掉默认值）；`redis-data` 卷保存会话与失效版本，删除该卷会让所有用户重新登录
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
npm run type-check             # 类型检查
npm run build                  # 生产构建
npm run openapi                # 依据后端 OpenAPI 重新生成 api/ 客户端
```

> `npm run openapi` 需要后端已启动，它从 `http://localhost:8123/api/v3/api-docs` 拉取接口定义。
> 它只会覆盖 OpenAPI 派生的客户端；`src/api/pictureExtra.ts` 中手工维护的 multipart 上传与「我的图片」封装、以及 `src/api/userExtra.ts` 中的个人中心/修改密码封装不会被覆盖。

## 测试

后端目前包含 **227 个单元测试**，覆盖用户注册/登录/改密、会话失效、管理员重置密码、图片上传权限与审核状态、原图压缩（尺寸/体积/PNG 透明通道/像素上限/回退）、审核历史与审核人补全、标签分类字典增删与同步、下载统计、文件类型与大小校验、「我的图片」归属校验、图片审核可见性与排序字段白名单逻辑，以及 Redis 限流降级、会话失效版本、过滤器顺序、列表两级缓存与缓存失效，全部基于 Mockito 或临时文件，不依赖数据库与外部服务：

```bash
cd picture-backend
./mvnw test
```

## 注意事项

- `picture-backend/src/main/resources/application-local.yml` 存放真实密钥，已在 `.gitignore` 中排除，请勿提交。
- 生产部署前请调整 `app.cors.allowed-origin-patterns` 为正式域名，并关闭或限制 Swagger 端点。
