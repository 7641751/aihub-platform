# 生产部署（单机 Docker Compose，阿里云 ECS）

这套文件**不含任何密钥**：密钥来自 `deploy/.env.production`，证书来自 `deploy/certs/` —— 两者都已 gitignore。

| 文件 | 作用 |
|---|---|
| `docker-compose.prod.yml` | **自包含**生产编排（只有 nginx 发布宿主端口）|
| `nginx/aihub.conf.template` | 反代规则 + 控制面 IP 白名单（由 nginx 镜像 entrypoint 用 envsubst 渲染）|
| `.env.production.example` | 变量模板 + 生成命令 |
| `gen-cert.sh` | 自签 TLS 证书（有域名+备案后换真证书）|
| `backup.sh` | MySQL dump + KB 原件卷备份 |

---

## 0. 暴露面（先看这张表，再动手）

| 服务 | 宿主端口 | 谁能连 |
|---|---|---|
| **nginx** | `${PUBLIC_TLS_PORT}`（默认 **8443**）| **公网** —— 这是唯一暴露的服务 |
| MySQL / Redis / RabbitMQ(含 mgmt) / Chroma / admin / gateway | **不发布** | 只在 compose 网络内 |

nginx 的路由：

| 路径 | 目标 | 访问控制 |
|---|---|---|
| `/v1/**` | `gateway:8080` | **公网**（鉴权 = API Key，`CONVENTIONS.md` §6）|
| `/api/**`、`/console/**` | `admin:8081` | **仅 `CONSOLE_ALLOW_CIDR`**（`deny all` 兜底 ⇒ 其它来源 403）|
| `/internal/**` | 不反代 | **显式 404**（它只对 compose 网络内的 gateway 开放）|
| 其余 | 不反代 | 404 |

**安全组最小放行**：只放 `PUBLIC_TLS_PORT` 与你自己的 SSH 端口。

> ⚠️ 注意**开发编排**发布的这几个：MySQL `3307`（绑在 `0.0.0.0`！）、Chroma `8000`（**它没有任何鉴权**）、
> RabbitMQ 管理台 `15672`、admin `8081`。生产**不要**把它们放进安全组 —— 本编排也根本不发布它们。

---

## 1. 前置

- Docker Engine + Compose v2（`docker compose version`）
- 能拉到镜像：大陆节点默认走 daocloud 代理（`AIHUB_IMAGE_*` 可覆盖）；也可以配 `/etc/docker/daemon.json` 的 `registry-mirrors`
- 资源：**≥ 2 vCPU / 4 GB**（6 容器；本编排给 mysql/admin/gateway 各 1C/1G 上限，另有 rabbitmq/chroma/nginx）。2C2G 会非常紧张
- （可选）域名 + **ICP 备案**。没有也能上：用 **IP + 8443 + 自签证书**

## 2. 生成密钥与证书

```sh
cd deploy
cp .env.production.example .env.production
chmod 600 .env.production

# 生成 7 条随机密钥（模板头部有一行式命令；下面的 for 循环是同一件事）
for k in MYSQL_ROOT_PASSWORD MYSQL_PASSWORD REDIS_PASSWORD RABBITMQ_PASSWORD \
         AIHUB_INTERNAL_SECRET AIHUB_CHANNEL_MASTER_KEY AIHUB_CONSOLE_SECRET; do
  printf '%s=%s\n' "$k" "$(openssl rand -hex 32)"
done

# 必改：控制台白名单 = 你自己的出口 IP（例如 1.2.3.4 或 1.2.3.0/24，只能写一个）
#   CONSOLE_ALLOW_CIDR=...

./gen-cert.sh 47.110.253.156          # 或你的域名
```

## 3. 拿到镜像

**A. 在服务器上构建**（推荐）
```sh
docker build --network=host -t aihub-platform-admin   -f aihub-admin/aihub-web/Dockerfile .
docker build --network=host -t aihub-platform-gateway -f aihub-gateway/Dockerfile .
```
`--network=host` 是**必需**的：构建容器解析不了 DNS（`repo.maven.apache.org: No address associated with hostname`）——
除非所有依赖都已在缓存里。这是 M5/M6 实测踩过的坑。

**B. 本机构建 + 搬运**
```sh
docker save aihub-platform-admin aihub-platform-gateway | gzip > images.tgz
scp images.tgz root@47.110.253.156:/tmp/ && ssh root@47.110.253.156 'gunzip -c /tmp/images.tgz | docker load'
```

## 4. 启动

```sh
cd deploy
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
docker compose --env-file .env.production -f docker-compose.prod.yml ps
```
首次启动：MySQL 初始化 + Flyway 迁移约 1 分钟；healthcheck 的 `start_period` 已留出余量。

## 5. 第一步：造第一个控制台账号 + 第一把 API Key

生产库里**没有** `sys_user`（控制台登不进去）、也**没有** API Key（数据面调不了）。这一步不能跳。

### 5.1 控制台账号（`sys_user`）

口令是 **bcrypt**（`$2a$` / `$2b$` / `$2y$` 三种 Spring 都接受），列：`tenant_id/username/password_hash/role/status`。

```sh
# 生成哈希（二选一）
htpasswd -bnBC 10 "" '你的口令' | tr -d ':\n'     # Debian: apt install apache2-utils；RHEL: dnf install httpd-tools
openssl passwd -6 '你的口令'                        # ⚠️ 这个产出的是 SHA-512 crypt，**不是** bcrypt，不要用
```
> 哈希工具这条**我没有在服务器上实测过**（本机没有 `htpasswd`）；落地时我会在那台机器上先验一次"能登录"，
> 或者改用本仓库验收时那套（`spring-security-crypto` jar + 单文件程序产 `$2a$` 串）。

```sh
# 经文件 + 容器内重定向写入（避开各处引号与编码坑；这是本仓库踩出来的稳路）
printf "%s\n" "INSERT INTO sys_user (tenant_id, username, password_hash, role, status) VALUES (1,'ops-admin','<粘贴哈希>','ADMIN','ACTIVE');" > /tmp/seed-user.sql
docker compose --env-file .env.production -f docker-compose.prod.yml cp /tmp/seed-user.sql mysql:/tmp/seed-user.sql
docker compose --env-file .env.production -f docker-compose.prod.yml exec -T mysql \
  sh -c 'mysql -uaihub -p"$MYSQL_PASSWORD" -D aihub < /tmp/seed-user.sql'
```

### 5.2 第一把 API Key（二选一）

- **(a) mint runner**（不需要控制台账号）：`.env.production` 里设 `AIHUB_MINT_KEY_ENABLED=true` +
  `AIHUB_MINT_KEY_TENANT_NAME` / `_NAME` / `_VALID_DAYS` → `up -d admin` → 明文**只出现在 admin 日志**里
  （`docker logs aihub-prod-admin-1 | grep -i 'ak_'`）⇒ **把 ENABLED 改回 false 再重启 admin**。
- **(b) 控制台**：登录 → 「API Key」Tab → 新建（明文只显示一次）。

> 明文**只在创建/铸造那一次**出现；库里只有 `SHA-256(secret)`（`CONVENTIONS.md` §6）。丢了只能重铸。

## 6. 验证清单

```sh
IP=47.110.253.156; PORT=8443

curl -k https://$IP:$PORT/nginx-health                       # 期望 200 "ok"
curl -k -o /dev/null -w '%{http_code}\n' https://$IP:$PORT/v1/models                  # 期望 401 invalid_api_key（能到网关）
curl -k https://$IP:$PORT/v1/models -H "Authorization: Bearer <ak_...>"               # 期望 200 模型列表
curl -k -o /dev/null -w '%{http_code}\n' https://$IP:$PORT/api/ping                   # 白名单内=401(缺令牌)；白名单外=**403**
curl -k -o /dev/null -w '%{http_code}\n' https://$IP:$PORT/internal/config/snapshot    # 期望 404（nginx 显式挡）
docker compose --env-file .env.production -f docker-compose.prod.yml ps                # 除 nginx **无 published port**
docker exec aihub-prod-redis-1 redis-cli ping                # 期望 (error) NOAUTH ⇒ requirepass 生效
```
**流式没被缓冲**（这是 M2/M6 专门证明过的一条，别在这里退化）：
```sh
curl -k -N https://$IP:$PORT/v1/chat/completions -H "Authorization: Bearer <ak_...>" \
  -H 'Content-Type: application/json' \
  -d '{"model":"<路由里的模型>","messages":[{"role":"user","content":"hi"}],"stream":true}'
# 期望：逐帧 data: {...} 到达（TTFT 明显小于整条流时长）
```
控制台：浏览器打开 `https://$IP:$PORT/console/index.html`（自签证书会警告一次）→ 登录 → 应看到 **4 个 Tab**（渠道 / API Key / 请求日志 / 用量）。

## 7. 备份、升级、回滚

```sh
./backup.sh                       # → deploy/backups/<UTC 时间戳>/{aihub.sql, admin-files.tgz}
```
- **升级**：构建新镜像 → `up -d admin gateway`；Flyway 自动向前迁移。
- **回滚**：把 `AIHUB_IMAGE_*` 指回旧 tag → `up -d`。⚠️ **数据库迁移不会自动回退**（Flyway 只有前向迁移）——
  跨迁移版本回滚前先想清楚，或先恢复备份。
- **恢复**：见 `backup.sh` 末尾打印的三步。

## 8. ⚠️ 这套编排**没有**解决的（如实登记）

1. **控制台只对白名单开放** ⇒ 不命中 `CONVENTIONS.md` §10 的硬门槛②。
   **一旦要把控制台发给非平台方 / 放公网，必须先落地租户隔离**（第三级角色 + 把租户维度资源的**写**按租户收窄）——
   那是产品级工作，不能靠这份编排解决。
2. **没有高可用**：单机单实例；MySQL/Redis/RabbitMQ 都是容器内单点。
3. **没有监控告警**：只有 healthcheck 与 `/healthz`；actuator metrics 未开放（README 已知边界）。
4. **Redis 是鉴权的信任源**（能写 `aihub:apikey:<sha256>` 就能伪造任意 Key）：本编排给它加了 `requirepass`
   且不发布端口 —— 但**同一宿主上的其它容器仍属信任边界内**。
5. **公网入口没有 WAF / 防刷**：`/v1/**` 只靠 API Key 鉴权 + 库里的限流策略。
6. **`/v1/**` 上无处理器的 404/405 仍是 Spring 默认体**（非 OpenAI 形状，已知缺口）。
7. **Chroma 挂卷是刻意的**（与开发编排相反）：因为本仓库**没有向量重建工具**；但**恢复备份后向量不会自动回来**，
   需要重传原件让它重新嵌入。

## 9. 与开发编排的差异（一表看全）

| 项 | 开发（`docker-compose.yml`）| 生产（本目录）|
|---|---|---|
| 发布端口 | mysql `3307`、rabbitmq `5672/15672`、chroma `8000`、admin `8081`、gateway `8080`、redis `127.0.0.1:6380` | **只有 nginx `${PUBLIC_TLS_PORT}`** |
| TLS | 无 | nginx 终结（自签，或备案后的真证书）|
| Redis 口令 | 无（当年记为"未来里程碑"）| `requirepass` + admin/gateway 两侧注入 |
| 控制台 | 公网可达 | IP 白名单 |
| 日志 | 无限增长 | json-file `10m × 5` |
| Chroma 卷 | 无 | 有 |
| `host.docker.internal` | Docker Desktop 自带 | `extra_hosts: host-gateway`（Linux 默认不解析）|
| 演示 seeder | 可用 | 关闭 |

---

## 10. 排障（下面 4 条都是**本机实测踩到**的，不是推测）

1. **nginx 起来就 `Restarting (1)`**，日志第一行是
   `nginx: [emerg] cannot load certificate "/etc/nginx/certs/server.crt": ... No such file or directory`
   ⇒ 证书没生成（或挂到了空目录）。先跑 `./gen-cert.sh <你的IP>`。
   ⚠️ **注意重启退避**：补齐证书后容器可能还要等一个退避周期才重试 —— 别在窗口内读日志就断定"改了没用"；
   要立刻生效就 `docker restart aihub-prod-nginx-1`。
2. **机器上没有 `openssl`**（例如 Windows 开发机）时怎么签：用**自带 openssl 的镜像**签，产物落在宿主机：
   ```sh
   docker run --rm -v "$PWD/certs:/certs" --entrypoint sh mysql:8.4 -c \
     'openssl req -x509 -newkey rsa:2048 -sha256 -days 825 -nodes \
        -keyout /certs/server.key -out /certs/server.crt \
        -subj "/CN=<IP>" -addext "subjectAltName=IP:<IP>"'
   ```
   实测：`mysql:8.4`（OpenSSL 3.5.8）与 `eclipse-temurin` 自带 `openssl`；**`nginx:alpine` 与 `alpine` 没有**。
3. **`host not found in upstream "gateway"`**：nginx 在**启动时**就解析 `proxy_pass` 里的主机名 ⇒
   若 nginx 先于 gateway 起来（例如你手动 `docker restart nginx`，而 gateway 当时不可达），它会起不来。
   正常 `up -d` 由 `depends_on` 保证顺序；异常顺序靠 `restart: unless-stopped` **自愈**（依赖就绪后自动恢复）。
4. **HTTP/2 未证实**：模板里写了 `http2 on;`，但本机 `curl -w '%{http_version}'` 报的是 **`1.1`**
   （多半是本机 curl 没编 nghttp2）⇒ 本文**不声称** HTTP/2。SSE 走 HTTP/1.1 完全正常（M2/M6 已证）。

**审暴露面的最快办法**（比看 yaml 可靠）：
```sh
docker compose --env-file .env.production -f docker-compose.prod.yml config | grep published:
# 期望：**只有一行** published: "8443"
```

---

## 11. 本机实测记录（2026-10-11，**同一份编排**，非 ECS）

用一份本地随机密钥（`.env.production`，测完已删）在本机以 `name: aihub-prod` 起全套（与开发栈**端口不冲突**），
逐条实测如下：

| 检查 | 实测 | 结论 |
|---|---|---|
| `config \| grep published:` | **只有** `published: "8443"` | ✅ 暴露面就一个端口 |
| `docker ps`（aihub-prod-*） | 其余 6 个只有容器内端口（`8080/tcp`…），**无** `0.0.0.0:…->` 映射 | ✅ MySQL/RabbitMQ/Chroma/admin/gateway 全不对宿主暴露 |
| `GET /nginx-health` | **200** | ✅ |
| `GET /v1/models`（无鉴权） | **401** | ✅ |
| `GET /v1/models` + `Bearer ak_deadbeef.beef` | **401** `invalid_api_key` —— **不是 503** | ✅ 证明 Redis（**带口令**）→ admin 的回源通路是通的 |
| 从 admin 日志取铸出的真 key → `GET /v1/models` | **200** | ✅ 端到端：nginx TLS → gateway → Redis(口令) → admin → MySQL |
| `GET /internal/config/snapshot` | **404** | ✅ nginx 显式挡掉 `/internal/**` |
| `redis-cli ping` / 带口令 ping | `NOAUTH Authentication required.` / **`PONG`** | ✅ `requirepass` 生效 |
| 白名单正面（`CONSOLE_ALLOW_CIDR=172.16.0.0/12`）| `GET /console/index.html` **200**；`GET /api/ping` 无令牌 **401** | ✅ 能到 admin |
| 白名单反面（换成 `10.99.0.0/24`）| `/api/ping` **403**、`/console/index.html` **403**，同时 `/v1/models` 仍 **401** | ✅ 白名单有判别力且**不影响数据面** |
| TLS | 全程 `https://` + 自签证书可用 | ✅（协商到的**版本号未取到**，见排障 4）|
| `host.docker.internal` 上游 | 未验 | ⬜ 本机是 Docker Desktop 天然可解析；**Linux 上靠 `extra_hosts`**，需在 ECS 上复验 |

> ⚠️ **这份记录是"本机 + 同一份编排"，不是 ECS 上的验收**。在 `47.110.253.156` 上落地时，
> 需要额外验的两条：`extra_hosts` 让 `host.docker.internal` 可用（或把渠道 `baseUrl` 改成真实上游）、
> 以及安全组只放 `PUBLIC_TLS_PORT`。这两条我会在服务器上逐条跑并贴原文。
