# YG 消息转发中继

把手机上的通知（微信、短信、QQ、任何应用）转发到自己的服务器，服务器统一成标准 JSON 落库，
并开一个**必须输密码**的端口在网页上实时查看。

```
┌─────────────────┐   NotificationListenerService（系统推事件，零轮询）
│  Android 手机   │──┐
│  YG 转发 App    │  │  本地 JSONL 队列 → WorkManager 合并去抖 → gzip POST
└─────────────────┘  │
                     ▼
        ┌──────────────────────────────┐
        │  服务器  server/serve.py      │   Bearer 令牌鉴权
        │  · 规范化成 yg.relay.batch.v1 │   SQLite(WAL) 去重落库
        │  · SSE 实时推送               │
        └──────────────┬───────────────┘
                       │ 端口 8787（密码 + 会话 Cookie）
                       ▼
              ┌──────────────────┐
              │  网页控制台       │  消息 / 设备 / 设置
              │  玻璃拟态 UI      │  搜索、筛选、导出、清空
              └──────────────────┘
```

## 三条设计约束

1. **省电优先**。安卓端不轮询、不常驻连接、不开前台服务。通知由系统推送 → 追加一行本地文件 →
   WorkManager 择机批量上传（默认合并 2.5 秒、指数退避）。这条路径与智能手环取通知是同一个机制，
   额外耗电接近零。
2. **隐私优先**。消息只发往你自己填的那台服务器，不经过任何第三方；服务器只有一个 SQLite 文件，
   日志不记录消息正文；手机端令牌与网页密码是两套独立凭据。
3. **零依赖优先**。服务端只用 Python 标准库（不需要 pip install），一个 `serve.py` + 三个静态文件；
   安卓端只依赖 Compose / Miuix / OkHttp / WorkManager。

## 快速开始

### 1. 起服务器（推荐 Docker）

```bash
git clone https://github.com/ZXEB/yg-notify-relay.git
cd yg-notify-relay

docker compose up -d --build
docker compose logs -f          # 首次启动会打印初始网页密码
```

首次启动会在数据卷里生成 `config.json`：网页密码（PBKDF2 哈希）与上传令牌。
**初始密码只在 `logs` 里打印一次**，登录后可以在网页「设置」里重置令牌。

想固定密码，用环境变量：

```bash
YG_PASSWORD='你的密码' docker compose up -d --build
```

> 数据在命名卷 `yg-data` 里，`docker compose down` 不会删。想看/备份文件就把 compose 里的
> `yg-data:/data` 换成 `./data:/data`，并在宿主机执行 `sudo chown -R 10001:10001 ./data`
> （容器以 uid 10001 的非 root 用户运行）。

### 2. 起服务器（不装 Docker）

```bash
cd server
python3 serve.py --port 8787 --data-dir ./data      # Linux / macOS
# Windows: powershell -ExecutionPolicy Bypass -File server\run.ps1
```

需要 Python 3.9+，没有任何第三方包。系统服务示例见 [`server/systemd/yg-relay.service`](server/systemd/yg-relay.service)。

### 3. 装 App

从 Actions 产物下载 `app-debug.apk`（或 `app-release.apk`，取决于仓库是否配置了签名 secret）：

- 仓库页 → **Actions** → 最新一次 `Build Android APK` → **Artifacts** → `yg-relay-apk`
- 或打一个 `v*` tag，会自动发 Release，里面直接有 APK

### 4. 手机端四步

1. 安装 APK（Android 13+）。
2. 打开 App →「概览」→「前往系统设置开启」，在 **通知使用权** 列表里勾选「YG 转发 · 通知中继」。
3. 「设置」→ 填 **服务器地址**（如 `http://192.168.1.10:8787`，可省略 `http://`）与 **上传令牌**，
   点「保存并立即上传一次」。
4. 回到「概览」，看到「已获得通知使用权」+「最近成功上传」有北京时间，就通了。

### 5. 看网页

浏览器打开 `http://<服务器IP>:8787`，输入密码。三个页签：

- **消息**：卡片流、搜索、按应用/设备筛选、SSE 实时插入、点卡片展开正文、点「原始 JSON」看原文
- **设备**：每台手机的电量、网络、系统版本、最后在线、累计条数
- **设置**：导出 JSON/JSONL、清空（需输入 `ALL` 确认）、重置上传令牌、退出登录

## 标准 JSON 契约

手机 → 服务器：`POST /api/v1/ingest`，`Authorization: Bearer <token>`，`Content-Encoding: gzip`

```json
{
  "schema": "yg.relay.batch.v1",
  "batch_id": "b6f1c0a8-...",
  "sent_at": 1760000000123,
  "device": {
    "id": "and_5f3a91c2d7e04b18",
    "alias": "我的手机",
    "manufacturer": "Xiaomi",
    "model": "24129PN74C",
    "android": "15",
    "app_version": "1.0.0",
    "battery": 87,
    "charging": false,
    "network": "wifi"
  },
  "events": [
    {
      "id": "9c1f7a2b4e6d8f0a1b2c3d4e",
      "type": "notification",
      "posted_at": 1760000000000,
      "app": { "package": "com.tencent.mm", "label": "微信" },
      "title": "张三",
      "text": "在吗",
      "big_text": null,
      "sub_text": null,
      "category": "msg",
      "channel": "message",
      "priority": 0,
      "ongoing": false,
      "group_summary": false,
      "seq": 12,
      "client_ts": 1760000000000
    }
  ]
}
```

服务端响应 `{"accepted":1,"duplicates":0,"rejected":0}`。主键是 `(device.id, event.id)`，
重复上传自动忽略（手机重发、进程重启、网络重试都不会产生重复消息）。

`event.id` 的口径：`sha256(deviceId|package|notificationKey|postTime|title|text)[:24]`。
通知更新（正文变了）算新事件，纯粹重复回调自动合并。

## 省电是怎么做到的

| 手段 | 说明 |
|---|---|
| 事件驱动 | `NotificationListenerService` 由系统在通知到达时回调，**没有轮询**，没有常驻 socket |
| 合并上传 | 连续通知只在最后一条之后等 `合并延迟`（默认 2.5s）再合并成一批，最多 100 条/批 |
| 交给系统调度 | WorkManager 负责网络约束、Doze 攒批唤醒、指数退避（30s 起）；不需要前台服务保活 |
| 落盘不丢 | 队列是 `filesDir/outbox/pending.jsonl`，进程被杀/重启/断网都不丢，联网后自动补发 |
| gzip | 批量正文压缩后传输，一条通知通常压到几十字节 |
| 心跳极稀 | 设备状态（电量/网络）24 小时一次，避免网页设备列表长期显示「几天前」 |
| 可选更省 | 打开「只在 Wi-Fi 上传」，移动网络下完全不发 |

国产 ROM 的后台限制：本应用不依赖前台服务，系统通常会在有新通知时重新绑定监听服务。若发现
通知长时间不同步，把「YG 转发」加入电池优化白名单 / 允许自启动（设置页有一键入口）。

## 安全

- **两套凭据彼此独立**：手机用 `ingest_token`（可随时在网页重置，旧令牌立刻失效），
  浏览器用密码换 `HttpOnly + SameSite=Strict` 会话 Cookie（8 小时）。拿到手机令牌看不到网页，反之亦然。
- **登录爆破防护**：同 IP 连续 5 次失败锁 5 分钟（内存计数）。
- **密码只存 PBKDF2-HMAC-SHA256（20 万次）哈希**，`config.json` 权限 0600。
- **静态资源白名单**：网页只发 `index.html / app.js / style.css / favicon.svg`，路径不参与拼接，
  不存在目录穿越。
- **请求体上限** 2MB、gzip 解压上限 8MB（防压缩炸弹）、统一 JSON 错误、日志不记录消息正文。
- **建议**：不要把 8787 直接暴露在公网。用 Caddy / Nginx 反代 + HTTPS：

  ```caddy
  relay.example.com {
      reverse_proxy 127.0.0.1:8787
  }
  ```

  也可以给 `serve.py` 传 `--cert/--key` 自带 TLS。

## 目录结构

```
android/                     Kotlin + Compose + Miuix 客户端
  app/src/main/kotlin/dev/yg/relay/
    service/RelayNotificationService.kt   通知监听（核心入口）
    service/SmsRelayReceiver.kt           可选短信直读
    data/OutboxStore.kt                   本地 JSONL 队列
    net/Uploader.kt                       gzip POST + 结果分类
    work/FlushWorker.kt                   批量上传与重试
    ui/                                   三页 UI + 移植的液态玻璃组件
server/
  serve.py                   HTTP + SSE + SQLite + 鉴权（仅标准库）
  web/                       网页控制台（零外链）
  tests/test_server.py       17 个用例
  Dockerfile  docker-compose  systemd/
tools/
  build-local.ps1            本地离线构建（缓存全部指向仓库内，不写系统盘）
  sync_glass.py              从参考工程同步液态玻璃组件
```

## 本地构建（不污染系统盘）

CI 上构建不需要本地环境。本地想验证编译：

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-local.ps1 -Task ":app:assembleDebug"
powershell -ExecutionPolicy Bypass -File tools\build-local.ps1 -Task ":app:testDebugUnitTest"
```

脚本做三件事：

1. 把系统盘已有的 Gradle 发行版与依赖缓存**复制**到仓库内 `.cache/`（只读源，一次性）；
2. 把 `GRADLE_USER_HOME`、`TMP`、`TEMP` 全部指向仓库内，构建产物只落在仓库里；
3. 若仓库路径含非 ASCII 字符（比如放在中文目录），自动建 junction `D:\yg-build\{gh,src}`
   并改从那里构建 —— 不复制任何文件，但 JVM 看到的是纯 ASCII 路径。

产物在 `android/app/build/outputs/apk/`。

> **为什么需要第 3 步**：Gradle 启动 test worker 时把 classpath 写进一个 `@argfile`，
> Windows 上按系统编码写、JVM 按 UTF-8 读，路径里只要有一个中文字符，
> `:app:testDebugUnitTest` 就会崩在
> `ClassNotFoundException: worker.org.gradle.internal.worker.GradleWorkerMain`。
> 编译（`assembleDebug`）不受影响，只影响跑测试。CI 是 Linux + ASCII 路径，没这个问题。

服务端测试：

```bash
python -m unittest discover -s server/tests -t server -v
python tools/smoke_server.py          # 真起进程跑一遍完整链路
```

## CI

| 工作流 | 触发 | 内容 |
|---|---|---|
| `android.yml` | push / PR / tag / 手动 | 单测 → 构建（有签名 secret 出 release，否则 debug）→ 校验签名 → 上传 artifact `yg-relay-apk` → tag 发 Release |
| `server.yml` | push / PR / 手动 | Python 3.9 + 3.12 单测；`docker build` → 起容器 → 探 `/healthz` → 登录 + 灌数据冒烟 |

签名（可选，不配就只出 debug）：

```bash
openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 10950 -nodes \
  -subj "/CN=YG Relay/O=YG/C=CN"
openssl pkcs12 -export -in cert.pem -inkey key.pem -out release.p12 -name ygrelay -passout pass:YOUR_PASSWORD
base64 -w0 release.p12     # Windows: certutil -encode release.p12 out.txt
```

把结果填进仓库 secret：`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。

## 常见问题

**网页能开，但手机上传 401**
令牌不对。网页「设置 → 接入令牌 → 显示」会轮换出一串新的，回到手机设置里粘进去保存。

**「通知使用权」里找不到本应用**
先启动一次 App 再进系统设置；部分 ROM 需要重新进页面刷新列表。

**手机显示已上传，网页没有新消息**
检查服务器地址是否带端口、手机与服务器是否同网段、服务器防火墙是否放行 8787。
`curl http://<服务器IP>:8787/healthz` 应返回 `{"ok":true,...}`。

**收不到某些应用的通知**
这些应用可能没有发通知（只是后台同步），或在设置里被「应用范围」排除了。系统噪声
（`android`、`com.android.systemui` 等）是刻意忽略的。

**想换一台服务器**
手机上改地址与令牌即可，服务器不用做任何迁移；旧服务器的数据仍在它自己的 SQLite 里。

## 许可

MIT。液态玻璃 UI 组件移植自 [ZXEB/command-code-usage-android](https://github.com/ZXEB/command-code-usage-android)（MIT），
详见 [ATTRIBUTION.md](ATTRIBUTION.md)。
