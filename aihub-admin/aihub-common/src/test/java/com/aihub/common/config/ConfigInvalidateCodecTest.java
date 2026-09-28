package com.aihub.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 配置失效消息的线格式（{@code {version}|{escaped reason}}）是 admin（发布方）与 gateway（订阅方）
 * 之间的**跨服务契约**：两侧共用 {@link ConfigInvalidateCodec}，任何一侧私自改分隔符或转义表都不会
 * 编译报错，只会在运行期表现为「消息收到了但版本号/原因全错」——那正好是 M3 那个 10 分钟不收敛的
 * 故障形状（网关把一条错的失效当成有效失效，或者干脆忽略它）。因此固定向量与畸形载荷都压在这里。
 *
 * <p>判别性说明：{@link #escapesTheDelimiterAndNewlinesInTheReason()} 是**转义表的判据** ——
 * 把转义关掉时，线格式上会出现两个**未转义的**分隔符，断言立刻变红（而不是静默地把 reason 截断成半截）。
 *
 * <p>本类不需要 Spring 上下文，也不需要 Docker：codec 只依赖 JDK 类型（{@code aihub-common} 的
 * main 作用域零第三方依赖）。
 */
class ConfigInvalidateCodecTest {

    @Test
    void roundTripsVersionAndReason() {
        var msg = new ConfigInvalidateMessage(1_700_000_000_123L, "channel.update");
        assertThat(ConfigInvalidateCodec.decode(ConfigInvalidateCodec.encode(msg))).isEqualTo(msg);
    }

    /**
     * 转义表的判据。
     *
     * <p><b>为什么不是「数 {@code |} 字符是否只有一个」</b>：brief 里的那句断言（
     * {@code wire.chars().filter(c -> c == '|').count() == 1}）与 brief 自己规定的转义表互相矛盾 ——
     * {@code MeteringEventCodec} 的转义表把 {@code |} 写成 {@code \|}，而那个 {@code |} **仍然是一个
     * 字符**。于是「转义」与「不转义」数出来都是 2（实测 {@code expected: 1L but was: 2L}），
     * 断言永远红、**不具判别性**。这里改成数**未被转义**的 {@code |}（也就是语义上的分隔符）：
     * 关掉转义 → 2 → 红；正确转义 → 1 → 绿。线格式本身另由下面的固定向量逐字节钉住，
     * 因此「改成 {@code %7C} 之类别的转义」也会被抓住。
     */
    @Test
    void escapesTheDelimiterAndNewlinesInTheReason() {
        var msg = new ConfigInvalidateMessage(42L, "weird|reason\\with\nnewline\r");
        String wire = ConfigInvalidateCodec.encode(msg);
        assertThat(countUnescapedDelimiters(wire)).as("分隔符必须只出现一次").isEqualTo(1);
        assertThat(wire)
                .as("线格式是跨服务契约（与 MeteringEventCodec 同一个转义表），逐字节钉住")
                .isEqualTo("42|weird\\|reason\\\\with\\nnewline\\r");
        assertThat(ConfigInvalidateCodec.decode(wire)).isEqualTo(msg);
    }

    @Test
    void malformedPayloadDecodesToNullInsteadOfThrowing() {
        assertThat(ConfigInvalidateCodec.decode(null)).isNull();
        assertThat(ConfigInvalidateCodec.decode("")).isNull();
        assertThat(ConfigInvalidateCodec.decode("not-a-number|x")).isNull();
        assertThat(ConfigInvalidateCodec.decode("1")).as("缺分隔符").isNull();
    }

    /**
     * 发布端**快速失败**的判据：空 reason 编码出的线格式是 {@code "42|"}，而 {@link
     * ConfigInvalidateCodec#decode} 按结构把 {@code "42|"} 判为畸形返回 {@code null} ——
     * 订阅端只打一条 WARN、不做任何失效，其他实例默默等满 TTL（回到 M3 的 10 分钟上界），
     * 而发布端一条都没记。**这种载荷比没有载荷更糟，因为它是静默的**，所以 {@code encode}
     * 必须拒绝产生它的输入，而不是把这份不对称写进文档。
     *
     * <p>空白但非空的 reason（{@code " "}）**允许**：线格式不是清洗层，这里刻意不做 trim 归一化，
     * 且 {@code "42| "} 是一条订阅端能解开的**有效**载荷（与 {@code "42|"} 不同）。
     */
    @Test
    void encodeRejectsNullOrEmptyReasonInsteadOfEmittingAPayloadDecodeWouldReject() {
        assertThatIllegalArgumentException()
                .as("null reason 曾经编码成 \"42|\"，被订阅端判为畸形后静默丢弃")
                .isThrownBy(() -> ConfigInvalidateCodec.encode(new ConfigInvalidateMessage(42L, null)));
        assertThatIllegalArgumentException()
                .as("空 reason 与之等价：同样产生 \"42|\" 这条解不开的载荷")
                .isThrownBy(() -> ConfigInvalidateCodec.encode(new ConfigInvalidateMessage(42L, "")));

        var blankButNonEmpty = new ConfigInvalidateMessage(42L, " ");
        assertThat(ConfigInvalidateCodec.encode(blankButNonEmpty))
                .as("只拒绝 null / 空串；空白按字面量上线，不做 trim 归一化")
                .isEqualTo("42| ");
        assertThat(ConfigInvalidateCodec.decode(ConfigInvalidateCodec.encode(blankButNonEmpty)))
                .as("\"42| \" 是有效载荷（\"42|\" 则不是），因此订阅端不会静默丢弃它")
                .isEqualTo(blankButNonEmpty);
    }

    @Test
    void channelNameIsTheSharedContractConstant() {
        assertThat(ConfigInvalidateTopology.CHANNEL).isEqualTo("aihub:config:invalidate");
    }

    /**
     * 数**未被转义**的 {@code |}：{@code \} 后面的那个字符一律跳过（它要么是转义序列的一部分，
     * 要么是一个孤立的反斜杠字面量，两种情况下都不可能是分隔符）。
     */
    private static long countUnescapedDelimiters(String wire) {
        long count = 0L;
        for (int i = 0; i < wire.length(); i++) {
            char c = wire.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '|') {
                count++;
            }
        }
        return count;
    }
}
