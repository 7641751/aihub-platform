package com.aihub.gateway.config;

import com.aihub.common.config.ChannelDescriptor;
import com.aihub.gateway.relay.ChannelKeyDecryptor;
import com.aihub.gateway.upstream.UpstreamClientFactory;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策 3 的另一半：**没有主密钥时网关照常启动**。
 *
 * <p>{@code aihub.channel.master-key} 在这里被显式置空，而不是「指望环境变量恰好没设」——
 * 否则本机设了 {@code AIHUB_CHANNEL_MASTER_KEY} 的开发者会跑出一个与 CI 不同的结论。
 *
 * <p>空主密钥下的正确形态是**可启动的降级**，不是启动失败：
 * <ul>
 *   <li>真实渠道（带密文）一律「解不开」→ {@code Optional.empty()} / {@code canServe=false}，
 *       路由层据此跳过它（**绝不抛异常**，否则一次配置事故就是全量 500）；</li>
 *   <li>冷启动兜底的遗留单渠道照常可服务，且「未配置上游密钥」表达为**空串**（不注入 Authorization），
 *       而不是「不可用」—— 本地 Ollama 这类无鉴权上游不能被空主密钥误伤。</li>
 * </ul>
 *
 * <p>这个类同时是装配层的证据：{@code ChannelKeyDecryptor} / {@code UpstreamClientFactory} 两个 bean
 * 在主密钥为空的上下文里**真的被创建出来了**（构造失败会让整个上下文起不来，本类就不可能有测试结果）。
 */
@SpringBootTest(properties = "aihub.channel.master-key=")
class BlankMasterKeyStartupTest {

    @Autowired
    private ChannelKeyDecryptor decryptor;

    @Autowired
    private UpstreamClientFactory clientFactory;

    @Autowired
    private UpstreamProperties upstream;

    @Test
    void theContextStartsWithABlankMasterKeyAndOnlyTheLegacyChannelStaysServable() {
        ChannelDescriptor real = new ChannelDescriptor(11L, "primary", "https://primary.example.com",
                "v1:QUJD", 1, 60_000, ChannelDescriptor.STATUS_ACTIVE, 100, 0);

        assertThat(decryptor.upstreamKey(real)).isEmpty();
        assertThat(decryptor.canServe(real)).isFalse();

        assertThat(decryptor.upstreamKey(LegacyChannel.of(upstream))).contains("");
        assertThat(decryptor.canServe(LegacyChannel.of(upstream))).isTrue();

        assertThat(clientFactory.legacy()).isNotNull();
    }
}
