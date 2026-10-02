package com.aihub.admin.console;

import com.aihub.admin.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 16 的**线上契约**：零构建管理台静态页（{@code /console/{index.html,console.js,console.css}}）
 * 的可访问性、以及页面自身的 hygiene。
 *
 * <p><b>上下文预算（裁定 7）</b>：本类**只**继承 {@link AbstractIntegrationTest}
 * （{@code @SpringBootTest(RANDOM_PORT)}），**不**声明 {@code @TestPropertySource}/{@code @Import}，
 * **不**用 {@code @WebMvcTest} —— 于是它与既有集成测试**共用默认 Spring 上下文**，套件里的
 * {@code Tomcat started on port} 次数**仍是 7**（实测见报告）。
 *
 * <p><b>助手形状（裁定 1）</b>：计划原稿给的 {@code get(String)} / {@code read(String)} 在本项目里
 * **编译不过**：{@code get(String)} 不是基类提供的，而是**每个测试类各自私有定义**的
 * （照 {@code ChannelAdminIntegrationTest:854} 与 {@code ProbeAndQueryIntegrationTest:602} 的形状），
 * 返回 {@link ResponseEntity} ⇒ 用 {@code getStatusCode()}/{@code getBody()}，**没有**
 * {@code .statusCode()}/{@code .body()}；{@code read(String)} **全仓不存在**，本类自己定义：
 * 读 **classpath 上的真实产物**（{@code static/console/<name>}），而不是去读源码目录。
 *
 * <p><b>RED 的自然形态（裁定 2）</b>：这三个文件在实现之前不存在 ⇒ 三条用例都红在**资源不存在 / 404**，
 * **没有判别力**。判别力**由变异体提供**（报告中的 4 条变异），**不**把 404 式的红当成"断言有效"。
 *
 * <p><b>本任务唯一无法由 JUnit 覆盖的验收（裁定 3）</b>：「登录后能完成『建渠道 / 建 Key / 查日志』」
 * —— 页面逻辑是**客户端 JS**，本套件**没有 JS 引擎** ⇒ 报告里明写"未由测试覆盖、只能人工核对"，
 * **不**写成"已验证"。可覆盖的部分已折成**可证伪的结构断言**：{@code console.js} 必须含五个端点
 * （{@code /api/auth/login}、{@code /api/channels}、{@code /api/api-keys}、{@code /api/logs}、
 * {@code /api/ping}）＋ {@code sessionStorage} ＋ {@code textContent} ＋ {@code plaintextKey}。
 */
class ConsoleStaticResourceTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void theConsoleAssetsAreServed() {
        assertThat(get("/console/index.html").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/console/console.js").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/console/console.css").getStatusCode()).isEqualTo(HttpStatus.OK);
        // 不断言 `/console/`：Spring Boot 不为子目录提供 welcome page（裁定「Produces」）。
    }

    @Test
    void theConsoleIsSelfContainedAndCarriesNoInlineCode() {
        String html = get("/console/index.html").getBody();
        assertThat(html).as("不引第三方脚本、不用内联脚本（CSP 友好，也少一个 XSS 面）")
                .contains("src=\"/console/console.js\"")   // 正向锚：这一读路径必须先真的读到东西
                .doesNotContain("<script>")            // 裸标签（无 src）＝内联脚本
                .doesNotContain("onclick=").doesNotContain("onload=")
                .doesNotContain("onerror=").doesNotContain("onfocus=")  // 内联事件处理器同样是内联脚本
                .doesNotContain("javascript:")
                .doesNotContain("//cdn")                                // 协议相对 URL 也是外部 CDN（裁定 4）
                .doesNotContain("http://").doesNotContain("https://");  // 无外部 URL / CDN

        // 大小写/空白绕过（评审 C-2）：`<script >`、`<SCRIPT>`、多出来的内联 `<script>` 全都躲不过
        // 「`<script` 的出现次数必须等于唯一那个带 src 的标签的出现次数」这条**计数**不变量。
        String lower = html.toLowerCase(Locale.ROOT);
        assertThat(count(lower, "<script"))
                .as("除唯一的 <script src=\"/console/console.js\"> 之外不许有第二个 <script（含大小写/空白变体）")
                .isEqualTo(count(lower, "<script src=\"/console/console.js\""));
    }

    @Test
    void theConsoleNeverRendersUntrustedHtmlAndKeepsTheTokenInSessionStorage() {
        // innerHTML 两个文件都要查（裁定 4）；`isNotEmpty()` 是**正向对照**：读空了不许算通过（评审 D）
        for (String asset : new String[] {"index.html", "console.js"}) {
            assertThat(read(asset)).as("%s：所有服务端文本都必须走 textContent，不许 innerHTML", asset)
                    .isNotEmpty()
                    .doesNotContain("innerHTML")
                    .doesNotContain("outerHTML")
                    .doesNotContain("eval(")
                    .doesNotContain("document.write")
                    .doesNotContain("localStorage");          // D9：令牌只许在 sessionStorage
        }
        // 结构引用（删掉任一视图即红 —— 本任务唯一有判别力的部分，裁定 2/5/6）
        assertThat(read("console.js")).as("五个端点必须都在（含 /api/ping：日志视图要靠它拿 tenantId）")
                .contains("/api/auth/login").contains("/api/channels").contains("/api/api-keys")
                .contains("/api/logs").contains("/api/ping")
                .contains("sessionStorage").contains("textContent").contains("plaintextKey");
    }

    @Test
    void theLogsQueryAlwaysCarriesAWellFormedFromAndTo() {
        // 评审 B-1 的回归钉：服务端的 from/to **必填**且必须是可被 Instant.parse 解析的**带 Z 的 UTC**
        // 字面量（缺省或解析失败 ⇒ 400），而 <input type="datetime-local"> 的值没有秒、没有时区。
        String js = read("console.js");
        assertThat(js).as("必须把 datetime-local 的值规范化成带 Z 的 UTC 字面量")
                .contains("utcInstant(");
        assertThat(js).as("原缺陷形状：只在输入非空时才带 from/to（空输入 ⇒ 必然 400）—— 不许回来")
                .doesNotContain("if (byId(\"logs-from\").value)")
                .doesNotContain("if (byId(\"logs-to\").value)");
    }

    // --- 助手（裁定 1：项目同款形状 + 自建的 read）----------------------------

    private ResponseEntity<String> get(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, null, String.class);
    }

    /** 读 **classpath 上的真实产物**（{@code static/console/<name>}），不是源码目录。 */
    private static String read(String name) {
        ClassPathResource resource = new ClassPathResource("static/console/" + name);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("classpath:static/console/" + name + " 不存在", e);
        }
    }

    /** 不重叠地数 {@code needle} 的出现次数（评审 C-2 的计数不变量用它）。 */
    private static int count(String haystack, String needle) {
        int hits = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            hits++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return hits;
    }
}
