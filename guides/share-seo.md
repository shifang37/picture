# 分享卡片与 SEO

> 本页是 [README](../README.md) 的展开说明，覆盖社交平台爬虫分流、OpenGraph 输出、防盗链注意事项与前端分享弹窗。

## 分享与 SEO

详情页的「分享」会打开弹窗（复制链接、二维码、系统分享、下载）；分享到微信 / Twitter 时卡片带标题、简介与封面。

### 爬虫分流

SPA 的 HTML 里没有图片信息，而社交平台的爬虫不执行 JS，因此采用「同一路径、按 UA 分流」：

- 人类访问 `/picture/{id}`：nginx 返回 `index.html`，前端渲染后动态写 `document.title` 与 `og:*` / `twitter:*`；
- 爬虫访问同一路径：nginx 代理到后端 `GET /api/share/picture/{id}`，返回带 OG 标签的最小 HTML。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/share/picture/{id}` | 公开 | `text/html`；只对已过审且未删除的图片返回卡片，其余返回 404 页面 |

- 用户内容统一 `HtmlUtils.htmlEscape` 转义；Latin-1 区间标点会变成 `&middot;`、`&hellip;` 这类实体，属正常现象（浏览器与爬虫都会解码）；
- `og:image` 走 COS 缩略图并**固定输出 JPEG**（`imageMogr2/thumbnail/1200x/format/jpeg/quality/85`）：微信等平台对 WebP 支持不稳定，WebP 封面会变成空白；
- 成功响应 `Cache-Control: public, max-age=300`；不存在 / 未过审返回 404 + `no-store`，页面里没有任何图片信息，不能通过分享链接探测未过审内容；
- 指标 `picture_share_total{result=rendered|not_found}`。

### 防盗链注意

微信 / Twitter 抓取封面时**不带 Referer**。开启 COS Referer 白名单（`COS_REFERER_ENABLED=true`）时必须同时设置 `COS_REFERER_ALLOW_EMPTY=true`，否则封面 403、分享卡片没有图。

### 前端

- `ShareModal`：复制链接（Clipboard API，非 HTTPS 回落 `execCommand`）、二维码（`qrcode` **动态 import**，只在打开弹窗时加载，不进首屏包）、系统分享（`navigator.share`，不支持的浏览器隐藏入口）、下载原图；
- `utils/seo.ts`：`applySeoMeta` 写标题与 meta，`resetSeoMeta` 在离开详情页时清理并恢复原标题；脚本创建的标签带 `data-seo="managed"` 标记，不会误删 `index.html` 的静态标签；
- 标题 / 描述截断按 Unicode 码点处理（emoji 不会被切成半个字符）；没有封面时 `twitter:card` 降级为 `summary`，避免平台抓到空白大图。

### 配置

| 配置 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `app.share.frontend-base-url` | `SHARE_FRONTEND_BASE_URL` | `http://localhost:5173` | 站点根地址，用于 `og:url` 与「查看详情」链接；生产必须改成正式域名 |
| `app.share.og-image-width` | `SHARE_OG_IMAGE_WIDTH` | 1200 | `og:image` 缩略图长边 |
| `app.share.cache-seconds` | `SHARE_CACHE_SECONDS` | 300 | 分享页缓存秒数 |

nginx 片段见 `picture-frontend/nginx.conf` 里的 `location ~ ^/picture/(?<sharePictureId>\d+)$`：用命名捕获避免 `if` 内的正则覆盖 `$1`，命中爬虫 UA 时直接 `proxy_pass` 到后端，其余请求仍 `try_files` 回 `index.html`。

### 验证

```powershell
# 模拟爬虫抓取（应返回带 og: 标签的 HTML）
curl.exe -s -H "User-Agent: Twitterbot/1.0" http://localhost:8080/picture/1
# 人类 UA 仍返回 SPA 的 index.html
curl.exe -s -H "User-Agent: Mozilla/5.0" http://localhost:8080/picture/1 | Select-String 'id="app"'
```
