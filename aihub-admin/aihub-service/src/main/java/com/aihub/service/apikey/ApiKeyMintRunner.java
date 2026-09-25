package com.aihub.service.apikey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 「最小内部签发」的触发路径：不是 HTTP 接口，因此不会在公网上出现造密钥的入口。
 * 用法（默认关闭）：
 * <pre>
 * java -jar aihub-web.jar --aihub.mint-key.enabled=true \
 *      --aihub.mint-key.tenant-name=demo --aihub.mint-key.name=my-first-key
 * </pre>
 * 明文 token 只打印一次，之后无法再取回（库里只有哈希）。
 * 控制台的完整签发/列表/吊销接口属于 M4。
 */
@Component
public class ApiKeyMintRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyMintRunner.class);

    private final ApiKeyService apiKeyService;
    private final boolean enabled;
    private final String tenantName;
    private final String keyName;
    private final long validDays;

    public ApiKeyMintRunner(ApiKeyService apiKeyService,
                            @Value("${aihub.mint-key.enabled:false}") boolean enabled,
                            @Value("${aihub.mint-key.tenant-name:demo}") String tenantName,
                            @Value("${aihub.mint-key.name:default}") String keyName,
                            @Value("${aihub.mint-key.valid-days:365}") long validDays) {
        this.apiKeyService = apiKeyService;
        this.enabled = enabled;
        this.tenantName = tenantName;
        this.keyName = keyName;
        this.validDays = validDays;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        Instant expireAt = validDays <= 0 ? null : Instant.now().plus(validDays, ChronoUnit.DAYS);
        ApiKeyService.IssuedKey issued = apiKeyService.mint(tenantName, keyName, expireAt);
        log.warn("""

                ==================== API KEY ISSUED (仅显示一次) ====================
                token    : {}
                keyId    : {}
                tenant   : {}
                有效期至 : {}
                ====================================================================
                """, issued.token(), issued.keyId(), tenantName, expireAt);
    }
}
