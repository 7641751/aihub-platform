package com.aihub.service.config;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.service.channel.ChannelKeyService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;

/**
 * 渠道探测（Task 11）：用渠道自己的 {@code base_url} + 密钥向上游打**一次**请求，回报可达性与耗时。
 * 它是**诊断动作**，不是数据面调用 —— 因此**停用的渠道照样可探测**，且**不写审计**
 * （{@code AuditAction} 里没有 PROBE 常量，本任务**不新增**共享常量）。
 *
 * <p><b>绝不回显密钥</b>：明文只在 {@link HttpRequest} 的 {@code Authorization} 头里出现一次（真实打上游
 * 必须认证），**不进** {@link ProbeResult}、不进响应体、不进日志。{@link #message} 只放**简短、非敏感**的
 * 原因串（例如 {@code "upstream returned 502"} / {@code "probe timed out"}），绝不带 baseUrl、密钥或
 * 上游响应体。本类**没有 logger** —— 与 {@link ChannelKeyService} 同款纪律：它手里同时握着明文与密文，
 * 一句 debug 就能让「明文渠道密钥不进日志」在无声中失效。
 *
 * <p><b>边界（控制器裁定 4）</b>：
 * <ul>
 *   <li>渠道不存在 → {@link ErrorCode#NOT_FOUND}（404）；</li>
 *   <li><b>单次</b>请求、<b>超时上限 3 秒</b>（{@link #PROBE_TIMEOUT_CAP}，取渠道 {@code timeout_ms}
 *       与上限的较小值）—— 必须有界，不许跟着渠道的 60s 默认值无限等；</li>
 *   <li>渠道密钥解不开（{@link ChannelKeyService#decrypt} 返回空 Optional）⇒ 按「不可达 + 非敏感原因」
 *       处理，**不让异常穿到控制器**；</li>
 *   <li>{@code base_url} 畸形 ⇒ 同样按「不可达」处理，而不是 500。</li>
 * </ul>
 *
 * <p><b>可达性的定义</b>：收到 HTTP 响应且状态码是 2xx ⇒ {@code reachable=true}；收到响应但状态码非
 * 2xx ⇒ {@code reachable=false} 且回显 {@code httpStatus}；超时 / 连接失败 / 未发出请求 ⇒
 * {@code reachable=false} 且 {@code httpStatus=null}。
 */
@Service
public class ChannelProbeService {

    /** 探测的**硬上界**：任何一次探测都不许超过它（渠道 {@code timeout_ms} 更大也只按它算）。 */
    static final Duration PROBE_TIMEOUT_CAP = Duration.ofSeconds(3);

    /** 上游探针路径：OpenAI 兼容的 {@code GET /models}（M1 的透传目标同源）。 */
    private static final String PROBE_PATH = "/models";

    private final ChannelMapper channelMapper;
    private final ChannelKeyService keyService;
    private final HttpClient httpClient;

    /**
     * HTTP 客户端内建（超时在每次请求上按渠道设定，并有 {@link #PROBE_TIMEOUT_CAP} 的 3s 硬上界）。
     * <b>本类只有一个构造器</b>：多个构造器会让 Spring 找不到注入入口
     * （{@code No default constructor found}），而这里没有需要替身客户端的场景 —— 探测的失败路径
     * 用真 ServerSocket 黑障来构造（见 {@code ChannelAdminIntegrationTest}）。
     */
    public ChannelProbeService(ChannelMapper channelMapper, ChannelKeyService keyService) {
        this.channelMapper = channelMapper;
        this.keyService = keyService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(PROBE_TIMEOUT_CAP)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 探测结果。**刻意没有密钥字段**（明文、密文、主密钥都不许出现）：可达性、上游状态码、耗时、
     * 一句非敏感原因，仅此四样。
     *
     * @param httpStatus 收到响应时为上游状态码，未收到（超时 / 连接失败 / 未发出）为 {@code null}
     */
    public record ProbeResult(boolean reachable, Integer httpStatus, long latencyMs, String message) {
    }

    public ProbeResult probe(long channelId) {
        ChannelEntity channel = channelMapper.selectById(channelId);
        if (channel == null) {
            // 诊断动作对「不存在的渠道」没有意义 ⇒ 404（与其它 /api/** 的 NOT_FOUND 语义一致）。
            throw new BizException(ErrorCode.NOT_FOUND, "渠道不存在: id=" + channelId);
        }

        long startedAtNanos = System.nanoTime();

        Optional<String> plaintext = keyService.decrypt(channel.getApiKeyCipher());
        if (plaintext.isEmpty()) {
            // decrypt 永不抛；解不开时按「不可达 + 非敏感原因」，绝不把异常或密文带出去。
            return new ProbeResult(false, null, elapsedMillis(startedAtNanos), "渠道密钥无法用当前主密钥解开");
        }

        URI uri;
        try {
            uri = probeUri(channel.getBaseUrl());
        } catch (IllegalArgumentException malformed) {
            return new ProbeResult(false, null, elapsedMillis(startedAtNanos), "渠道 baseUrl 不是合法 URL");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(boundedTimeout(channel.getTimeoutMs()))
                .header("Authorization", "Bearer " + plaintext.get())
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            // 单次请求：send(...) 只发一次，且不做重试。
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            boolean reachable = status >= 200 && status < 300;
            return new ProbeResult(reachable, status, elapsedMillis(startedAtNanos),
                    reachable ? "upstream reachable" : "upstream returned " + status);
        } catch (HttpTimeoutException timeout) {
            return new ProbeResult(false, null, elapsedMillis(startedAtNanos), "probe timed out");
        } catch (IOException io) {
            return new ProbeResult(false, null, elapsedMillis(startedAtNanos), "connection failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, null, elapsedMillis(startedAtNanos), "probe interrupted");
        }
    }

    /** @return 渠道配置的超时（毫秒），但**绝不**超过 {@link #PROBE_TIMEOUT_CAP}；非法值回落到上限。 */
    private static Duration boundedTimeout(Integer timeoutMs) {
        if (timeoutMs == null || timeoutMs <= 0) {
            return PROBE_TIMEOUT_CAP;
        }
        Duration configured = Duration.ofMillis(timeoutMs);
        return configured.compareTo(PROBE_TIMEOUT_CAP) > 0 ? PROBE_TIMEOUT_CAP : configured;
    }

    /** {@code base_url} + {@code /models}；去掉尾部斜杠，避免 {@code //models}。 */
    private static URI probeUri(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("empty baseUrl");
        }
        String trimmed = baseUrl.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return URI.create(trimmed + PROBE_PATH);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
