# 共度 · Zeabur 小红书后台

使用上游 `xpzouying/xiaohongshu-mcp` 的 **v2.7.0**，源码提交 `d6352e5435b45ebb48fe240dc03a2bec0476bd56`。镜像按 digest 固定，避免 `latest` 或同名标签变化后无意升级；本清单适用 Linux amd64。2026-11-02 已通过 GitHub release/tag API 和 Docker Hub tag API 核对，详细来源见 [upstream-evidence.json](upstream-evidence.json)。清单存在不代表服务已部署。

## 部署

1. 在 Zeabur 新建独立服务，使用 [zeabur-service.schema.json](zeabur-service.schema.json) 的镜像、端口、环境变量和挂载目录。保留镜像默认启动命令，使内置 `tini` 回收 Chrome 子进程。
2. **提交部署前必须生成并填入非空 `AUTH_TOKEN`**，例如用 `secrets.token_urlsafe(32)` 生成；令牌仅保存到 Zeabur 环境变量和私有交接文件，不能提交 Git 或写日志。`spec.py` 的 `build_schema(token)` 会拒绝空值和短令牌。仓库中的 JSON 是待填模板，不可直接提交创建服务。
3. 将 `data` 挂载至 `/app/data`，`images` 挂载至 `/app/images`。`COOKIES_PATH=/app/data/cookies.json` 保存登录状态及上游的固定浏览器 seed；HOME/config 同处持久卷。不要挂载 `/app/cache`，该目录包含镜像预置浏览器，空卷会遮住它。
4. 公开 HTTP 端口 `18060`，由 Zeabur 域名提供 HTTPS。APP 服务地址填 `https://所选域名/mcp`，令牌栏填相同访问令牌。无需开放 Chrome 调试端口或额外的 noVNC 入口。
5. 仅运行一个实例。登录数据应保持同一份，不应在多个独立副本上轮流生成会话。上游没有在此次核对的 Docker/Compose 文件中声明 CPU/内存最低值；按现有服务器可用资源选择限制，并通过真实 Chrome 启动、扫码和请求时的内存峰值验收，不能把 `/health` 存活视为浏览器压力测试通过。

上游默认 headless，已包含运行所需的浏览器及中文字体，不再设置旧版 `ROD_BROWSER_BIN`。`headless_browser v0.4.0` 使用 `--no-sandbox`，底层 `go-rod v0.116.2` 默认启用 `disable-dev-shm-usage`；使用镜像原有配置，不额外拼接未经核实的 Chrome 参数。

## 部署验收

- `GET /health` 无需鉴权，应成功并报告 `xiaohongshu-mcp` 和版本。它仅表示服务存活，不能证明账号登录或浏览器访问小红书成功。
- `/mcp` 和 `/api/v1/*` 使用 `Authorization: Bearer <令牌>`。缺失或错误令牌应返回 401；空令牌会关闭上游鉴权，所以不得空值部署到公开域名。
- 向 `/mcp` 发送 MCP `initialize`，随后发送 `notifications/initialized` 和 `tools/list`；声明 `Accept: application/json, text/event-stream`。上游使用官方 Streamable HTTP SDK，JSON 响应、stateless 模式；不要将 HTTP 200 当作工具成功，应验证 JSON-RPC 结果且不含 error。
- 至少应发现 `check_login_status`、`get_login_qrcode`、`list_feeds`、`search_feeds`、`get_feed_detail`、`get_unread_count`、`list_notifications`。初始化和列工具不会公开发帖。此版本包含门铃需要的 notifications 工具。
- 首次扫码由用户在 APP 完成；随后检查登录状态，才能验收推荐、搜索、视频及门铃。查询 `list_notifications` 会清除对应平台分区的未读标记；只核对未读数量应调用 `get_unread_count`。
- 未经用户另行要求，不用公开发帖、点赞或评论验证服务。也不要为了验收删除 cookies、重建持久卷或覆盖现有部署。

公开发布的图片/视频仍需服务可访问的媒体 URL 或服务侧路径。手机的本地路径不属于服务器文件系统，此部署清单不自动增加手机媒体上传桥接。

来源：[上游固定版本](https://github.com/xpzouying/xiaohongshu-mcp/tree/d6352e5435b45ebb48fe240dc03a2bec0476bd56)、[镜像 tag API](https://hub.docker.com/v2/repositories/xpzouying/xiaohongshu-mcp/tags/v2.7.0)、[Zeabur 自定义 Docker 镜像](https://zeabur.com/docs/en-US/deploy/methods/custom-docker-image)。
