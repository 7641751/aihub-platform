package com.aihub.gateway.relay;

import com.aihub.common.config.ChannelDescriptor;
import io.netty.handler.timeout.TimeoutException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.util.ArrayList;
import java.util.List;

/**
 * 失败转移的**纯规则**：哪些上游结果该换渠道、哪些候选值得一试、一次请求最多试几次、model 该截到多长。
 *
 * <p>抽成纯函数（而不是塞在控制器的 lambda 里）的唯一理由是**可证伪**：这些规则是 M3 的核心
 * 正确性，必须能被单元测试逐条钉住。控制器只负责把它们接到 Reactor 链上。
 */
public final class RelayAttempts {

    /** {@code request_log.model} 是 {@code VARCHAR(128)}（V1）。超长会让那一行 INSERT 失败并进 DLQ。 */
    public static final int MODEL_MAX_LENGTH = 128;

    /**
     * 一次客户端请求最多尝试几条候选渠道（**首选 + 两条备用**）。
     *
     * <p>{@code RouteResolver.candidates(model)} 会返回该模型**全部优先级组**的渠道（含熔断的
     * last-resort 层），它的设计目标就是「还有下一个」。但没有任何上界时，一次上游故障会把**一个**
     * 客户端请求放大成 N 次上游调用（N = 配置里该模型的全部渠道数）：这既是对已经出问题的上游的
     * 二次打击，也让「一次请求 = 一次计费」的直觉彻底失真。3 覆盖了实际的「主 / 备 / 第三备」拓扑，
     * 同时把放大上界钉死在 3 倍。
     *
     * <p>上界由**两半**共同兑现，缺一不可：
     * <ol>
     *   <li>进入尝试循环之前把候选列表截断到本值（{@code ChatRelayController#candidateList}）：
     *       这让「最后一个候选」的语义唯一确定 —— 无论上界是否把它截掉，最后一个候选都照原样回写
     *       它自己的响应；</li>
     *   <li>尝试循环**结构上**保证每条候选最多被订阅一次（{@code ChatRelayController#attempt}）：
     *       截断只管得住「候选有几条」，管不住「同一条被订阅几次」—— 而后者曾让 3 条候选的混合故障
     *       打出 7 次上游调用。</li>
     * </ol>
     */
    public static final int MAX_ATTEMPTS = 3;

    private RelayAttempts() {
    }

    /**
     * 上游响应的**分类结果**（见 {@link #decision(int)}）。三个分支对应三种收尾方式，没有第四种。
     *
     * <p>{@code clientError} 与 {@code failover} 在**结构上**互斥（枚举常量决定，而不是两个恰好不重叠的
     * {@code if}）：这正是「客户端的错绝不换渠道」这条铁律能被单点守住的原因。
     */
    public enum FailoverDecision {
        /** 5xx 与 429：换下一个候选渠道；429 另外还要打熔断标记（由控制器负责）。 */
        FAILOVER(true, false),
        /** 400-499（429 除外）：客户端的错，换渠道一样错，原样透传给客户端。 */
        PASS_THROUGH_CLIENT_ERROR(false, true),
        /** 其余（2xx/3xx 等）：原样透传。 */
        PASS_THROUGH_UPSTREAM_ERROR(false, false);

        private final boolean failover;
        private final boolean clientError;

        FailoverDecision(boolean failover, boolean clientError) {
            this.failover = failover;
            this.clientError = clientError;
        }

        /** 是否允许「换下一个候选渠道」。 */
        public boolean failover() {
            return failover;
        }

        /** 是否是「客户端的错，必须原样透传、不得重试」。 */
        public boolean clientError() {
            return clientError;
        }

        /** 是否原样透传（= 不换渠道）。恒等于 {@code !failover()}。 */
        public boolean passOn() {
            return !failover;
        }
    }

    /**
     * 把上游状态码分类成**唯一**的处置方式：只有 5xx 与 429 换渠道，4xx（429 除外）是客户端的错，
     * 其余原样透传。
     *
     * <p><b>为什么是一个枚举而不是两个布尔方法</b>（本轮评审的清理）：旧实现有
     * {@code shouldFailoverBeforeCommit(s) = s >= 500 || s == 429} 与
     * {@code isClientErrorThatMustNotBeRetried(s) = 400 <= s < 500 && s != 429} 两个方法，
     * 控制器读的是 {@code 可切换 && ! 客户端的错}。这两条规则在**每一个 int** 上都互斥，因此
     * 那个 {@code !} 是惰性的：删掉它不会有任何用例变红。也就是说「客户端的错不得重试」这条铁律
     * 只活在测试里，而**不在**决策路径上 —— 一旦有人将来放宽可切换规则（例如把 408 也算进去），
     * 那个多余的判断也救不了：真正的防线必须写在决策发生的地方。
     *
     * <p>塌成一个分类器之后，每个状态码恰好落在一个分支上，控制器的决策就是
     * {@code decision(status).failover()} 这一读。{@code RelayAttemptsTest} 逐条覆盖 400-599
     * 全区间（外加 2xx/3xx），因此每个分支都是 load-bearing 的。
     *
     * <p>行为与改造前**逐位相同**：5xx/429 换渠道，4xx（429 除外）原样透传，2xx/3xx 原样透传。
     *
     * @param upstreamStatus 上游返回的状态码（任何 {@code int} 都有定义，不会返回 {@code null}）
     */
    public static FailoverDecision decision(int upstreamStatus) {
        if (upstreamStatus >= 500 || upstreamStatus == 429) {
            return FailoverDecision.FAILOVER;
        }
        if (upstreamStatus >= 400) {
            // 429 已在上面被拿走，因此这里就是「4xx 且非 429」。
            return FailoverDecision.PASS_THROUGH_CLIENT_ERROR;
        }
        return FailoverDecision.PASS_THROUGH_UPSTREAM_ERROR;
    }

    /**
     * 过滤掉**密钥解不开**的候选（它们注定失败，没必要浪费一次往返）。
     *
     * <p>但如果过滤之后一个都不剩，就**返回原列表**：主密钥配错时应该让客户端看到真实的上游错误
     * （或者至少一条与密钥有关的日志），而不是一个伪装成「模型不存在」的 404。
     */
    public static List<ChannelDescriptor> servable(List<ChannelDescriptor> candidates,
                                                   ChannelKeyDecryptor decryptor) {
        List<ChannelDescriptor> servable = new ArrayList<>(candidates.size());
        for (ChannelDescriptor channel : candidates) {
            if (decryptor.canServe(channel)) {
                servable.add(channel);
            }
        }
        return servable.isEmpty() ? candidates : servable;
    }

    /**
     * 「这次失败是不是**上游**的错」——**按原因链判定，不按异常类型**（G14）。
     *
     * <p>为什么不能只看类型：响应头已经收到（状态 200）之后发生的**读超时**，WebClient 会把它包成
     * {@code WebClientResponseException}（Task 8 在 {@code ChannelClientIsolationTest} 里实测过：
     * 「响应头已收到，失败发生在读响应体的中途，因此 WebClient 把它包成 WebClientResponseException」），
     * 而不是 {@code WebClientRequestException}。按类型判定就会把一次被掐断的流当成「客户端断连」
     * （甚至当成成功的 200），计量与告警同时失真。
     *
     * <p>因此这里同时认两件事：
     * <ul>
     *   <li>{@code WebClientRequestException}：请求根本没拿到响应（连不上 / 首字节前的超时），
     *       WebClient 用它表达这一类；</li>
     *   <li>原因链里的 {@link TimeoutException}（Netty 的读/写超时，含 {@code ReadTimeoutException}）
     *       或 {@link java.util.concurrent.TimeoutException}（Reactor 的 {@code timeout()} 算子）：
     *       已经进入读响应体阶段的超时只能这样认出来。</li>
     * </ul>
     *
     * <p>刻意**不**把任意 {@code IOException} 也算进来：响应已提交之后的写回失败（客户端断连）
     * 就是 {@code IOException}，它是另一条分支（按客户端断连计量，设计文档 §8.1 ⑤）。
     *
     * @param error 控制器拿到的异常，可以为 {@code null}
     */
    public static boolean isUpstreamFailure(Throwable error) {
        if (error == null) {
            return false;
        }
        if (error instanceof WebClientRequestException) {
            return true;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            if (cause == cause.getCause()) {
                // 自引用的原因链（异常实现写错时的兜底）：不无线循环。
                break;
            }
        }
        return false;
    }

    /**
     * 计量事件里的 model 必须能落进 {@code VARCHAR(128)}（决策 15）。
     *
     * <p>按**码点**而不是 UTF-16 码元截断：utf8mb4 下的 {@code VARCHAR(128)} 按字符计，而一个非 BMP
     * 字符（emoji、CJK 扩展区汉字）在 Java 字符串里占**两个**码元。按码元切到第 128 个会把一个代理对
     * 从中间劈开，孤立代理写进库时退化成 {@code ?}；按码点切既保证结果都是完整字符，也让非 BMP 名字
     * 真正填满 128 个字符（旧实现只填 64 个）。码点数 ≤ 码元数，因此列宽上界只会更安全。
     */
    public static String truncateModel(String model) {
        if (model == null || model.codePointCount(0, model.length()) <= MODEL_MAX_LENGTH) {
            return model;
        }
        return model.substring(0, model.offsetByCodePoints(0, MODEL_MAX_LENGTH));
    }
}
