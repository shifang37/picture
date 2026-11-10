# picture-frontend

云图库前端，基于 Vue 3 + Vite + TypeScript + Ant Design Vue。

## 环境要求

- Node.js `^20.19.0 || >=22.12.0`
- 后端服务已在 `http://localhost:8123` 启动

## 启动

```bash
npm install
npm run dev
```

访问 `http://localhost:5173`。开发服务器已配置代理，`/api` 开头的请求会转发到 `http://localhost:8123`（见 `vite.config.ts`）。

## 构建

```bash
npm run build        # 类型检查 + 生产构建，产物在 dist/
npm run preview      # 本地预览构建产物
npm run type-check   # 仅类型检查
```

## 目录说明

```
src/
├── api/             # OpenAPI 生成的请求客户端与类型定义
├── assets/          # 静态资源
├── components/      # 公共组件（顶部导航、图片上传等）
├── layouts/         # 页面布局
├── pages/           # 页面
│   ├── user/        # 登录、注册
│   └── admin/       # 图片审核、图片管理、用户管理
├── router/          # 路由与全局守卫（access.ts）
├── stores/          # Pinia 状态
├── utils/           # 工具方法
├── request.ts       # axios 实例与响应拦截（未登录跳转）
└── main.ts
```

## 接口客户端

`src/api/` 下的文件由后端 OpenAPI 文档生成，**不要手工修改**。后端接口变更后重新生成：

```bash
npm run openapi
```

该命令会请求 `http://localhost:8123/api/v3/api-docs`，因此需要后端处于运行状态。

## 说明

- 后端会把 `Long` 类型序列化为字符串（避免雪花 ID 精度丢失），因此前端所有 ID 均为 `string`，请勿再转成 `Number`。
- 页面权限通过 `src/access.ts` 中的全局路由守卫控制，`/admin` 前缀的路由仅管理员可进入。
