package com.aihub.gateway.upstream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code spring-boot-starter-validation} + {@code @NotBlank} 是本任务新加的护栏：
 * 上游 base-url 配没了要在**启动时**就炸，而不是等第一个请求打到 {@code /v1/chat/completions} 才报
 * "baseUrl must not be null"。这里用 {@link ApplicationContextRunner} 钉住它真的生效
 * （少了 starter-validation，{@code @Validated} 会被安静忽略，护栏形同虚设）。
 */
class UpstreamPropertiesValidationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UpstreamProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void contextFailsWhenBaseUrlIsMissing() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            // 失败原因是绑定校验（而不是别的意外），"baseUrl" 出现在 cause 链里。
            assertThat(causeChain(context.getStartupFailure()))
                    .contains("BindValidationException")
                    .contains("baseUrl");
        });
    }

    @Test
    void contextFailsWhenBaseUrlIsBlank() {
        runner.withPropertyValues("aihub.upstream.base-url=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextStartsWithBaseUrlAndDefaultsApiKeyAndModelToNull() {
        runner.withPropertyValues("aihub.upstream.base-url=http://127.0.0.1:11434").run(context -> {
            assertThat(context).hasNotFailed();
            UpstreamProperties properties = context.getBean(UpstreamProperties.class);
            assertThat(properties.baseUrl()).isEqualTo("http://127.0.0.1:11434");
            assertThat(properties.apiKey()).isNull();
            assertThat(properties.defaultModel()).isNull();
        });
    }

    private static String causeChain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            text.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
        }
        return text.toString();
    }
}
