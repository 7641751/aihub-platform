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
     * 上游这个状态码是否允许「换下一个候选渠道」。
     *
     * <p>只有 5xx 与 429。**4xx 不换**：401/403/404/422 是「这个请求本身有问题」，
     * 换渠道也一样错，重试只会把同一个错误在多个上游重复计费一次。
     */
    public static boolean shouldFailoverBeforeCommit(int upstreamStatus) {
        return upstreamStatus >= 500 || upstreamStatus == 429;
    }

    /**
     * 「客户端的错，必须原样透传、不得重试」的判据（429 除外，它是要切换的）。
     *
     * <p><b>它有生产调用方</b>：{@code ChatRelayController#tryCandidate} 的切换决策同时读本方法与
     * {@link #shouldFailoverBeforeCommit(int)}（{@code 可切换 && ! 客户端的错}），因此这条铁律写在
     * 真正决策的地方，而不是只活在测试里。两条规则彼此互斥（同一个状态码不可能既是「该切换」又是
     * 「必须原样透传」），互斥性由 {@code RelayAttemptsTest} 逐条钉住 —— 若将来有人放宽
     * {@link #shouldFailoverBeforeCommit}（例如把 408 也算进去），这里的 {@code !} 仍会把 4xx 挡在
     * 切换之外。
     */
    public static boolean isClientErrorThatMustNotBeRetried(int upstreamStatus) {
        return upstreamStatus >= 400 && upstreamStatus < 500 && upstreamStatus != 429;
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

    /** 计量事件里的 model 必须能落进 {@code VARCHAR(128)}（决策 15）。 */
    public static String truncateModel(String model) {
        if (model == null || model.length() <= MODEL_MAX_LENGTH) {
            return model;
        }
        return model.substring(0, MODEL_MAX_LENGTH);
    }
}
