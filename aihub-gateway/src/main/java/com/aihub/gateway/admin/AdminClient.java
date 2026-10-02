package com.aihub.gateway.admin;

import com.aihub.common.apikey.ApiKeyView;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.config.ModelRouteDescriptor;
import com.aihub.common.config.QuotaDescriptor;
import com.aihub.common.config.RatePolicy;
import com.aihub.common.internal.InternalHmac;
import com.aihub.common.quota.QuotaDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 调 admin 内部接口。网关不直连 MySQL（设计文档决策 A），密钥的真相源在 admin。
 * <p>签名算法必须与 admin 的 InternalHmac 一致；两处都改了才算改对。
 *
 * <p><b>本类型是函数式接口</b>：它只有一条契约「给定 keyHash，给出解析结果」，测试里一个 lambda
 * 就是完整替身，不必再为「可替身」而额外造一个接口 + 一个实现类。真实实现见 {@link Http}。
 *
 * <p>约定：{@link #resolve} **不抛异常**。admin 不可达/超时/返回非 2xx/响应体畸形一律折算成
 * {@link Optional#empty()}（视作「这个 key 不存在」）。网络故障与垃圾数据都不该让客户端拿到 500。
 *
 * <p><b>但「视作不存在」只适用于 M1 的兼容形状，不适用于解析路径</b>：{@link #resolve} 把故障与
 * 权威否定 <b>压成了同一个 {@code empty}</b>，调用方无法分辨。解析路径因此必须用
 * {@link #resolveOutcome}（三态）—— 故障进负缓存就是 D1；而「故障对客回 503 而不是 401」
 * （D4）同样只有分辨得出三态才做得到。
 */
@FunctionalInterface
public interface AdminClient {

    /**
     * 内部接口路径。**必须是应用内路径**（不含 context path，也不由 base-url 拼出）：admin 的
     * {@code InternalAuthFilter} 用 {@code UrlPathHelper.getPathWithinApplication} 取路径再验签，
     * 用 base-url 拼出来的带前缀路径永远验不过 —— 这正是 Task 3 最后一个 commit 钉下的契约。
     */
    String RESOLVE_PATH = "/internal/api-keys/resolve";

    /**
     * 配置快照路径。与 {@code RESOLVE_PATH} 一样**必须是应用内路径**（不含 context path）：
     * admin 的 {@code InternalAuthFilter} 用 {@code UrlPathHelper.getPathWithinApplication} 验签。
     */
    String CONFIG_SNAPSHOT_PATH = "/internal/config/snapshot";

    /**
     * 配额兜底预扣路径。与另外两个一样**必须是应用内路径**（不含 context path），METHOD 是 {@code POST}。
     *
     * <p>它只在「Redis 不可用 **且** 部署显式打开了兜底」时被调用（{@code aihub.quota.fallback-enabled}，
     * 默认开启），正常路径上一次都不会打（那会把每个请求的延迟绑到 admin 上）。
     */
    String QUOTA_RESERVE_PATH = "/internal/quota/reserve";

    Mono<Optional<ApiKeyView>> resolve(String keyHash);

    /**
     * 与 {@link #resolve} **同一跳**，但把「admin 权威地说没有这把 key」与「解析不了（平台故障）」
     * 分开（见 {@link AdminResolution}）。解析路径必须用这个而不是 {@link #resolve}：只有这里
     * 才分得清「权威否定」与「我们不知道」—— 前者可以进负缓存、对客 401，后者两者都不行
     * （不进缓存、对客 503，D4）。
     *
     * <p><b>默认实现把 {@code empty} 当作权威否定</b>（即与 {@link #resolve} 完全同义）。这样
     * 所有既有的函数式替身（测试里的 {@code keyHash -> Mono.just(Optional.empty())}）一行都不用改
     * 就仍然编译且语义不变；真实实现 {@link Http} 覆盖它，才真正区分三态。
     */
    default Mono<AdminResolution> resolveOutcome(String keyHash) {
        return resolve(keyHash).map(view -> view.map(AdminResolution::found)
                .orElseGet(AdminResolution::notFound));
    }

    /**
     * 拉取配置快照（渠道 + 路由 + 限流策略 + 版本号）。
     *
     * <p>**默认实现返回空**：这样所有既有的替换实现（测试里的 {@code keyHash -> Mono.just(...)}）
     * 不必改一行就仍然编译通过；真实实现见 {@link Http}。
     * 空 {@link Optional} 的语义是「控制面拿不到快照」，调用方据此走降级（决策 6）。
     */
    default Mono<Optional<ConfigSnapshot>> configSnapshot() {
        return Mono.just(Optional.empty());
    }

    /**
     * 配额兜底预扣（Redis 不可用时的回源）。返回的判定与正常路径**语义逐字相同**：admin 侧跑的是
     * 同一段 {@code QuotaScript.RESERVE}、读的是同一张 {@code quota} 表、写的是同一个桶键。
     *
     * <p>**默认实现返回空 Mono**（与 {@link #configSnapshot()} 同款理由）：这样所有既有的函数式替身
     * （测试里的 {@code keyHash -> Mono.just(...)}）不必改一行就仍然编译通过。
     *
     * <p>空 {@link Mono} 的语义是「**兜底拿不到判定**」（未配兜底 / 非 2xx / 响应畸形 / 传输故障），
     * 调用方据此走 D7 的放行 —— **绝不允许**把它当成拒绝。真实实现见 {@link Http}。
     */
    default Mono<QuotaDecision> reserveQuota(long tenantId, long estimatedTokens) {
        return Mono.empty();
    }

    /** 真实实现：相对路径 + HMAC 签名 + 响应解析。 */
    static AdminClient http(WebClient webClient, String internalSecret) {
        return new Http(webClient, internalSecret);
    }

    final class Http implements AdminClient {

        private static final Logger log = LoggerFactory.getLogger(Http.class);
        private static final ObjectMapper MAPPER = new ObjectMapper();

        /** 平台故障的对客口径（D4）：不进负缓存、不谎报成「key 不存在」，见 {@link AdminResolution}。 */
        private static final String FAULT_LOGGED_AS =
                "本次不写入负缓存、对客 fail-closed 回 503 service_unavailable（结论未知，不是「key 不存在」）";

        private final WebClient webClient;
        private final String internalSecret;

        Http(WebClient webClient, String internalSecret) {
            this.webClient = webClient;
            this.internalSecret = internalSecret;
        }

        @Override
        public Mono<Optional<ApiKeyView>> resolve(String keyHash) {
            // 兼容视图：把三态压回 M1 就有的 Optional 形状（既有调用方与测试都按它断言）。
            // 注意它**丢掉了**「故障 vs 权威否定」这一位，所以解析路径不许用它。
            return resolveOutcome(keyHash)
                    .map(resolution -> resolution.isFound()
                            ? Optional.of(resolution.view())
                            : Optional.empty());
        }

        @Override
        public Mono<AdminResolution> resolveOutcome(String keyHash) {
            // 签名与请求构造**必须**惰性：InternalHmac.sign 在 secret 为空（仓库默认 aihub.internal.secret
            // 就是空串）时会抛 IllegalStateException，急切求值会让它在返回 Mono **之前**就逃出去，
            // 那样下面的 onErrorResume 根本看不见它，客户端拿到的是 500 而不是约定的 401。
            // Mono.defer 之后，签名失败与网络失败走同一条 fail-closed 路径。
            return Mono.defer(() -> {
                String timestamp = String.valueOf(Instant.now().getEpochSecond());
                String signature = InternalHmac.sign(internalSecret, timestamp, "POST", RESOLVE_PATH);

                return webClient.post()
                        .uri(RESOLVE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Timestamp", timestamp)
                        .header("X-Internal-Signature", signature)
                        .bodyValue("{\"keyHash\":\"" + keyHash + "\"}")
                        .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                                .map(body -> parseOutcome(response.statusCode().value(), body)));
            }).onErrorResume(ex -> {
                // 传输层失败（连不上 / 超时 / 签名抛错）**不是**「key 不存在」，是 admin 侧故障。
                // 对客是 503 service_unavailable（D4：不把平台故障谎报成「你的 key 错了」），
                // 所以这行 ERROR 是运维侧唯一的区分信号：admin 全挂时它会持续出现，
                // 而单个坏 key 不会（那条只打 DEBUG）。从 D1 起它还必须**不写负缓存** ——
                // 否则一次瞬时故障会被本地负缓存放大成 30 秒的固定拒绝。
                log.error("admin 回源失败（传输层异常，非「key 不存在」），" + FAULT_LOGGED_AS + ": {}",
                        ex.toString());
                return Mono.just(AdminResolution.unavailable());
            });
        }

        @Override
        public Mono<Optional<ConfigSnapshot>> configSnapshot() {
            // 与 resolve 同一套 fail-closed 纪律：签名/网络/非 2xx/畸形响应一律折算成「没有快照」，
            // 由配置层决定继续用陈旧快照还是回落到遗留单渠道。绝不抛到请求路径上。
            return Mono.defer(() -> {
                String timestamp = String.valueOf(Instant.now().getEpochSecond());
                String signature = InternalHmac.sign(internalSecret, timestamp, "GET", CONFIG_SNAPSHOT_PATH);

                return webClient.get()
                        .uri(CONFIG_SNAPSHOT_PATH)
                        .header("X-Internal-Timestamp", timestamp)
                        .header("X-Internal-Signature", signature)
                        .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                                .map(body -> parseSnapshot(response.statusCode().value(), body)));
            }).onErrorResume(ex -> {
                log.error("admin 配置快照拉取失败（传输层异常），本次用缓存/遗留渠道继续服务: {}", ex.toString());
                return Mono.just(Optional.empty());
            });
        }

        @Override
        public Mono<QuotaDecision> reserveQuota(long tenantId, long estimatedTokens) {
            // 与 resolve / configSnapshot 同一套 fail-open 纪律：签名 / 网络 / 非 2xx / 畸形响应一律折算成
            // **空 Mono**（=「拿不到判定」），由 QuotaFilter 按 D7 放行。绝不抛到请求路径上。
            //
            // 请求体只有两个字段：**额度上限不在里面** —— 它只能由 admin 从 MySQL 读（否则一把被攻破的
            // 网关就能给任何租户开出无限额度）。这里用字面量拼 JSON（没有共享 DTO），因此键名是契约的一部分，
            // admin 侧的 record 组件名必须逐字相同。
            return Mono.defer(() -> {
                String timestamp = String.valueOf(Instant.now().getEpochSecond());
                String signature = InternalHmac.sign(internalSecret, timestamp, "POST", QUOTA_RESERVE_PATH);

                return webClient.post()
                        .uri(QUOTA_RESERVE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Timestamp", timestamp)
                        .header("X-Internal-Signature", signature)
                        .bodyValue("{\"tenantId\":" + tenantId + ",\"estimatedTokens\":" + estimatedTokens + "}")
                        .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(body -> parseReserveDecision(response.statusCode().value(), body)
                                        .map(Mono::just).orElseGet(Mono::empty)));
            }).onErrorResume(ex -> {
                log.error("配额兜底回源失败（传输层异常，非「余额不足」），本次放行（D7）: {}", ex.toString());
                return Mono.empty();
            });
        }

        /**
         * 把兜底响应折成判定。**只有两种结果是「判定」**：2xx + 带布尔 {@code allowed} 的 {@code data}
         * （{@code allowed=true} 放行 / {@code false} 拒绝）；其余一律 {@link Optional#empty()}（拿不到 ⇒ 放行）。
         *
         * <p><b>为什么缺 {@code allowed} 字段必须 fail-open</b>：{@code asBoolean} 的默认值语义太容易写反 ——
         * 一个 {@code path("allowed").asBoolean(false)} 会把「admin 返回了畸形体」静默变成「拒绝」，
         * 于是**平台故障被伪装成配额用尽**（与 D16 同一类错误的镜像）。这里显式要求该字段是布尔，
         * 缺失/非布尔即视为「判不了」。
         */
        static Optional<QuotaDecision> parseReserveDecision(int status, String body) {
            if (status < 200 || status >= 300) {
                log.error("配额兜底回源失败（HTTP {}），本次放行（D7）。响应体: {}", status, body);
                return Optional.empty();
            }
            try {
                JsonNode data = MAPPER.readTree(body).path("data");
                JsonNode allowed = data.path("allowed");
                if (data.isMissingNode() || data.isNull() || !allowed.isBoolean()) {
                    log.error("配额兜底回源响应缺少布尔 data.allowed，本次放行（D7）。响应体: {}", body);
                    return Optional.empty();
                }
                return Optional.of(new QuotaDecision(allowed.asBoolean(),
                        data.path("remainingTokens").asLong(-1L),
                        data.path("remainingRequests").asLong(-1L)));
            } catch (Exception e) {
                log.error("配额兜底回源响应畸形，本次放行（D7）: {}", e.toString());
                return Optional.empty();
            }
        }

        /** 非 2xx / 缺 {@code data} / 字段畸形都折算成「没有快照」。 */
        static Optional<ConfigSnapshot> parseSnapshot(int status, String body) {
            if (status < 200 || status >= 300) {
                log.error("admin 配置快照拉取失败（HTTP {}），本次用缓存/遗留渠道继续服务。响应体: {}",
                        status, body);
                return Optional.empty();
            }
            try {
                JsonNode data = MAPPER.readTree(body).path("data");
                if (data.isMissingNode() || data.isNull()) {
                    log.debug("admin 配置快照：HTTP {} 响应无 data，按「没有快照」处理", status);
                    return Optional.empty();
                }
                long version = data.path("version").asLong(0L);
                long generatedAt = data.path("generatedAtEpochMilli").asLong(0L);
                String defaultModel = data.path("defaultModel").isTextual()
                        ? data.get("defaultModel").asText() : null;

                List<ChannelDescriptor> channels = new ArrayList<>();
                for (JsonNode node : data.path("channels")) {
                    channels.add(new ChannelDescriptor(
                            node.path("id").asLong(), node.path("name").asText(null),
                            node.path("baseUrl").asText(null), node.path("apiKeyCipher").asText(null),
                            node.path("keyVersion").asInt(0), node.path("timeoutMs").asInt(0),
                            node.path("status").asText(null), node.path("weight").asInt(0),
                            node.path("priority").asInt(0)));
                }
                List<ModelRouteDescriptor> routes = new ArrayList<>();
                for (JsonNode node : data.path("routes")) {
                    routes.add(new ModelRouteDescriptor(
                            node.path("modelName").asText(null), node.path("channelId").asLong(),
                            node.path("weight").asInt(0), node.path("priority").asInt(0),
                            node.path("status").asText(null)));
                }
                List<RatePolicy> policies = new ArrayList<>();
                for (JsonNode node : data.path("ratePolicies")) {
                    policies.add(new RatePolicy(
                            node.path("tenantId").isNumber() ? node.get("tenantId").asLong() : null,
                            node.path("apiKeyId").isNumber() ? node.get("apiKeyId").asLong() : null,
                            node.path("qps").asInt(0), node.path("burst").asInt(0)));
                }
                // **Task 13（2026-10-01 修复 Critical-1）**：额度必须在这里搬运 ——
                // `data.quotas` 是**控制面到数据面的唯一通路**（网关不连数据库）。少了这一段，
                // admin 发得出额度、网关却把它解析成**空表**，而空表正好是 D15「所有租户都不限」的
                // 默认值 ⇒ 缺口在生产上**静默不可观测**。独立评审用诊断变异实测证伪过：
                // 夹具带上 `QuotaDescriptor` 后，旧实现下 `quotas=[]` 而期望非空（`AdminClientSnapshotContractTest`）。
                // 缺 `quotas` 字段（旧 admin / 非 2xx 之外的畸形体）→ `MissingNode`/`NullNode` 迭代为空 ⇒ 空表 = 不限。
                List<QuotaDescriptor> quotas = new ArrayList<>();
                for (JsonNode node : data.path("quotas")) {
                    quotas.add(new QuotaDescriptor(
                            node.path("tenantId").asLong(), node.path("period").asText(null),
                            node.path("tokenLimit").asLong(), node.path("requestLimit").asLong()));
                }
                return Optional.of(new ConfigSnapshot(version, generatedAt, channels, routes, policies, defaultModel,
                        quotas));
            } catch (Exception e) {
                log.error("admin 配置快照响应畸形，本次用缓存/遗留渠道继续服务: {}", e.toString());
                return Optional.empty();
            }
        }

        /**
         * 三态分类 —— **D1 的分界线就在这里**。
         *
         * <p>只有两种响应是 admin 的**权威否定**（可以负缓存）：404 + {@code NOT_FOUND} 信封，
         * 以及 2xx 但没有 {@code data}（admin 冷启动的中间状态 / 空信封）。其余一律
         * {@link AdminResolution#unavailable()}：5xx、401/403（内部密钥配错）、没有 NOT_FOUND
         * 信封的 404（路径写错）、响应畸形。它们**永远不该**被当作「这把 key 不存在」缓存下来。
         *
         * <p>日志级别沿用既有约定：权威否定只打 DEBUG（单个坏 key 不刷屏），平台故障打 ERROR
         * —— 那是运维区分「有人拿错 key」与「我们的控制面坏了」的唯一信号。
         */
        static AdminResolution parseOutcome(int status, String body) {
            if (status < 200 || status >= 300) {
                if (status == 404 && hasAdminCode(body, "NOT_FOUND")) {
                    // admin 的 404 + NOT_FOUND 信封 = 「这个 key 不存在」：正常业务结果，不打 ERROR。
                    log.debug("admin 回源：key 不存在（HTTP 404）");
                    return AdminResolution.notFound();
                }
                // 5xx / 401 / 403，以及「路径写错」这类没有 NOT_FOUND 信封的 404：都是**平台故障**。
                // 客户端看到的是 503 service_unavailable（D4，登记在 CONVENTIONS 第 4/5 节），
                // 但结论未知 ⇒ 不写负缓存，故障清除后下一个请求立刻重试。
                log.error("admin 回源失败（HTTP {}，服务不可用或配置错误，非「key 不存在」），"
                        + FAULT_LOGGED_AS + "。响应体: {}", status, body);
                return AdminResolution.unavailable();
            }
            try {
                JsonNode data = MAPPER.readTree(body).path("data");
                if (data.isMissingNode() || data.isNull()) {
                    // 2xx 但没有 data（例如 admin 的 200 空信封）：这是 admin **成功回答**且没有这把
                    // key，属于权威否定，不是故障。
                    log.debug("admin 回源：HTTP {} 响应无 data，按「key 不存在」处理", status);
                    return AdminResolution.notFound();
                }
                JsonNode expireAt = data.path("expireAt");
                return AdminResolution.found(new ApiKeyView(
                        data.path("keyId").asText(),
                        data.path("tenantId").asLong(),
                        data.path("tenantName").asText(),
                        data.path("status").asText(),
                        expireAt.isNull() || expireAt.isMissingNode() ? null : Instant.parse(expireAt.asText()),
                        data.path("apiKeyId").isNumber() ? data.get("apiKeyId").asLong() : null));
            } catch (Exception e) {
                log.error("admin 回源响应畸形（非「key 不存在」），" + FAULT_LOGGED_AS + ": {}",
                        e.toString());
                return AdminResolution.unavailable();
            }
        }

        /** 响应体是不是 admin 的 {@code {code,message,data}} 信封，且 {@code code} 等于给定值。 */
        private static boolean hasAdminCode(String body, String code) {
            try {
                return code.equals(MAPPER.readTree(body).path("code").asText());
            } catch (Exception e) {
                return false;
            }
        }
    }
}
