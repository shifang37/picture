# 云图库 picture

一个前后端分离的图片素材管理与分享平台。支持图片上传、分类标签检索、内容审核，以及管理端的用户与图片管理。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5.16、MyBatis-Plus 3.5.18.1、MySQL 8 |
| 对象存储 | 腾讯云 COS（含数据万象图片处理） |
| 接口文档 | springdoc-openapi 2.10.0（Swagger UI） |
| 前端 | Vue 3.5、Vite 7、TypeScript 5.9、Ant Design Vue 4、Pinia、Vue Router |
| 构建 | Maven Wrapper 3.9.16、npm |

## 目录结构

```
picture/
├── picture-backend/                 # Spring Boot 后端
│   ├── sql/create_table.sql         # 建表脚本
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
│       ├── api/                     # OpenAPI 生成的前端请求客户端
│       ├── pages/                   # 页面（含 admin 下的管理页）
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
- 一个腾讯云 COS 存储桶（用于图片存储）

### 1. 初始化数据库

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS picture DEFAULT CHARACTER SET utf8mb4"
mysql -uroot -p picture < picture-backend/sql/create_table.sql
```

默认连接配置为 `localhost:3306/picture`，账号 `root` / `root`，如不一致请修改配置文件。

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

### 3. 启动后端

```bash
cd picture-backend
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

服务地址 `http://localhost:8123/api`。

### 4. 启动前端

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

## 图片审核流程

本项目实现了完整的图片审核闭环：

1. 上传的图片默认进入**待审核**状态（`reviewStatus = 0`）
2. 待审核与被拒绝的图片**不会出现在首页列表**，只有管理员和作者本人能通过直链访问详情
3. 管理员在「图片审核」页面（`/admin/pictureReview`）通过或拒绝，可填写审核意见
4. 审核通过（`reviewStatus = 1`）后，图片才对所有访客可见

## 常用命令

```bash
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

## 测试

后端目前包含 28 个单元测试，覆盖用户注册/登录/改密与图片审核可见性逻辑，全部基于 Mockito，不依赖数据库与外部服务：

```bash
cd picture-backend
./mvnw test
```

## 注意事项

- `picture-backend/src/main/resources/application-local.yml` 存放真实密钥，已在 `.gitignore` 中排除，请勿提交。
- 生产部署前请调整 `app.cors.allowed-origin-patterns` 为正式域名，并关闭或限制 Swagger 端点。
