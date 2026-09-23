# aihub-platform M0（地基）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭出 aihub-platform 的多模块骨架，让 admin 与 gateway 两个服务能编译、能启动、能通过健康检查，并让数据库表结构与三层基础设施（MySQL / Redis / RabbitMQ）真实可用。

**Architecture:** Maven 多模块。`aihub-admin` 聚合 `common / dao / service / mq / web` 五个模块（Servlet 技术栈），`aihub-gateway` 是独立的 WebFlux 服务且不依赖 admin 任何模块，两者只共享 `aihub-common`。M0 只交付地基：统一响应与异常、Flyway 表结构、基础设施连通与健康检查、一个最小 SSE 转发的可行性验证、以及一条命令起全栈的 Docker Compose。

**Tech Stack:** Java 21（编译目标）、Spring Boot 3.5.16、MyBatis-Plus 3.5.17、MySQL 8.4、Flyway、Redis 7、RabbitMQ 3.13、WebFlux、Testcontainers、Maven 3.9.12、Docker Compose。

## Global Constraints

- 项目根目录：`D:\PycharmProjects\aihub-platform`（git 仓库已初始化，已有一次 commit）。
- 编译目标固定 Java 21：`<maven.compiler.release>21</maven.compiler.release>`；本机运行 JVM 为 JDK 25.0.2（Spring Boot 3.5.16 官方支持 Java 17–25）。不要改成 25，也不要降到 17。
- Spring Boot 版本固定 `3.5.16`；MyBatis-Plus 固定 `3.5.17`（groupId `com.baomidou`，artifactId `mybatis-plus-spring-boot3-starter`）。除此之外所有依赖版本由 Spring Boot BOM 管理，**不要写 `<version>`**。
- 包名前缀：`com.aihub.common` / `com.aihub.dao` / `com.aihub.service` / `com.aihub.mq` / `com.aihub.admin` / `com.aihub.gateway`。
- 端口：admin `8081`，gateway `8080`，MySQL 宿主机 `3307`，Redis `6379`，RabbitMQ `5672` / 管理台 `15672`。
- 统一响应体固定为 `{"code","message","data"}`，`code` 取 `ErrorCode` 枚举名；成功时 `code = "OK"`。
- 健康检查端点固定为 `/healthz`（由 Actuator health 端点映射而来），不带鉴权。
- 集成测试一律使用 Testcontainers，**执行前必须确认 Docker Desktop 正在运行**。
- 任何密钥、口令都不得写进仓库：一律通过环境变量或 `.env`（`.env` 已在 `.gitignore` 中）。
- 每个 Task 完成后立即 commit，commit message 用 conventional commits（`feat:` / `test:` / `chore:` / `docs:`）。
- 命令一律在项目根目录执行；本计划不使用 Maven wrapper，统一使用本机 `mvn`（3.9.12）。

---

## File Structure

M0 结束后，新增/修改的文件如下（后续 Task 会在此表基础上扩展）：

| 文件 | 职责 |
|---|---|
| `pom.xml` | 父 POM：继承 `spring-boot-starter-parent:3.5.16`，声明模块与依赖版本 |
| `aihub-admin/pom.xml` | admin 聚合 POM，声明五个子模块 |
| `aihub-admin/aihub-common/` | 零依赖模块：统一响应体、错误码、业务异常 |
| `aihub-admin/aihub-dao/` | 持久层：MyBatis-Plus + MySQL 驱动 + Flyway 与迁移脚本 |
| `aihub-admin/aihub-dao/src/main/resources/db/migration/V1__init_schema.sql` | 全部 10 张表的建表脚本 |
| `aihub-admin/aihub-service/` | 业务服务层（M0 仅占位 + Redis 依赖） |
| `aihub-admin/aihub-mq/` | 消息模块（M0 仅占位 + RabbitMQ 依赖） |
| `aihub-admin/aihub-web/` | 启动模块：Controller、全局异常处理、配置、测试基类 |
| `aihub-gateway/` | 独立 WebFlux 服务：健康检查与最小 SSE 转发 |
| `docker-compose.yml` | 一条命令起 MySQL + Redis + RabbitMQ + admin + gateway |
| `docs/CONVENTIONS.md` | 项目约定（包名、端口、响应体、测试纪律） |

---

## Task 1: Maven 多模块骨架与两个可启动的服务

**Files:**
- Create: `pom.xml`
- Create: `aihub-admin/pom.xml`
- Create: `aihub-admin/aihub-common/pom.xml`
- Create: `aihub-admin/aihub-dao/pom.xml`
- Create: `aihub-admin/aihub-service/pom.xml`
- Create: `aihub-admin/aihub-mq/pom.xml`
- Create: `aihub-admin/aihub-web/pom.xml`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/package-info.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/AihubAdminApplication.java`
- Create: `aihub-admin/aihub-web/src/main/resources/application.yml`
- Create: `aihub-gateway/pom.xml`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/AihubGatewayApplication.java`
- Create: `aihub-gateway/src/main/resources/application.yml`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/AihubAdminApplicationTests.java`
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/AihubGatewayApplicationTests.java`

**Interfaces:**
- Consumes: 无（这是第一个 Task）。
- Produces: 两个 Spring Boot 启动类 `com.aihub.admin.AihubAdminApplication`（端口 8081）与 `com.aihub.gateway.AihubGatewayApplication`（端口 8080），以及两端统一的 `/healthz` 健康检查端点。后续所有 Task 都在这两个模块内工作。

- [ ] **Step 1: 写 admin 的失败测试**

创建 `aihub-admin/aihub-web/src/test/java/com/aihub/admin/AihubAdminApplicationTests.java`：

```java
package com.aihub.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AihubAdminApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthzReturnsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/healthz", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
```

创建 `aihub-gateway/src/test/java/com/aihub/gateway/AihubGatewayApplicationTests.java`：

```java
package com.aihub.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AihubGatewayApplicationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void healthzReturnsUp() {
        webTestClient.get().uri("/healthz")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("\"status\":\"UP\""));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```powershell
mvn -B -q test
```

预期：构建失败，报错类似 `Could not find the selected project in the reactor` 或 `pom.xml does not exist` —— 因为此刻还没有任何 POM。

- [ ] **Step 3: 写父 POM 与五个 admin 模块 POM**

创建 `pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.16</version>
        <relativePath/>
    </parent>

    <groupId>com.aihub</groupId>
    <artifactId>aihub-platform</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>aihub-platform</name>

    <modules>
        <module>aihub-admin</module>
        <module>aihub-gateway</module>
    </modules>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <mybatis-plus.version>3.5.17</mybatis-plus.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.aihub</groupId>
                <artifactId>aihub-common</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.aihub</groupId>
                <artifactId>aihub-dao</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.aihub</groupId>
                <artifactId>aihub-service</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.aihub</groupId>
                <artifactId>aihub-mq</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

创建 `aihub-admin/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.aihub</groupId>
        <artifactId>aihub-platform</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>aihub-admin</artifactId>
    <packaging>pom</packaging>
    <name>aihub-admin</name>

    <modules>
        <module>aihub-common</module>
        <module>aihub-dao</module>
        <module>aihub-service</module>
        <module>aihub-mq</module>
        <module>aihub-web</module>
    </modules>
</project>
```

创建 `aihub-admin/aihub-common/pom.xml`（零依赖）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.aihub</groupId>
        <artifactId>aihub-admin</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>aihub-common</artifactId>
    <name>aihub-common</name>
</project>
```

创建 `aihub-admin/aihub-dao/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.aihub</groupId>
        <artifactId>aihub-admin</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>aihub-dao</artifactId>
    <name>aihub-dao</name>

    <dependencies>
        <dependency>
            <groupId>com.aihub</groupId>
            <artifactId>aihub-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>
    </dependencies>
</project>
```

创建 `aihub-admin/aihub-service/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.aihub</groupId>
        <artifactId>aihub-admin</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>aihub-service</artifactId>
    <name>aihub-service</name>

    <dependencies>
        <dependency>
            <groupId>com.aihub</groupId>
            <artifactId>aihub-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.aihub</groupId>
            <artifactId>aihub-dao</artifactId>
        </dependency>
        <dependency>
            <groupId>com.aihub</groupId>
            <artifactId>aihub-mq</artifactId>
        </dependency>
    </dependencies>
</project>
```

创建 `aihub-admin/aihub-mq/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.aihub</groupId>
        <artifactId>aihub-admin</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>aihub-mq</artifactId>
    <name>aihub-mq</name>

    <dependencies>
        <dependency>
            <groupId>com.aihub</groupId>
            <artifactId>aihub-common</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
    </dependencies>
</project>
```

创建 `aihub-admin/aihub-web/pom.xml`。注意 M0 阶段 `aihub-web` **只依赖 `aihub-common`**，dao/service/mq 会在 Task 3、Task 4 依次接入，这样每个 Task 结束时测试都是绿的：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.aihub</groupId>
        <artifactId>aihub-admin</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>aihub-web</artifactId>
    <name>aihub-web</name>

    <dependencies>
        <dependency>
            <groupId>com.aihub</groupId>
            <artifactId>aihub-common</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

创建 `aihub-admin/aihub-common/src/main/java/com/aihub/common/package-info.java`：

```java
/**
 * 跨服务共享的零依赖基础类型：统一响应体、错误码、业务异常。
 * 本模块不得引入任何第三方依赖，admin 与 gateway 都依赖它。
 */
package com.aihub.common;
```

- [ ] **Step 4: 写启动类与配置**

创建 `aihub-admin/aihub-web/src/main/java/com/aihub/admin/AihubAdminApplication.java`：

```java
package com.aihub.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AihubAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AihubAdminApplication.class, args);
    }
}
```

创建 `aihub-admin/aihub-web/src/main/resources/application.yml`：

```yaml
server:
  port: 8081

spring:
  application:
    name: aihub-admin

management:
  endpoints:
    web:
      base-path: /
      exposure:
        include: health,info
      path-mapping:
        health: healthz
  endpoint:
    health:
      show-details: always
```

创建 `aihub-gateway/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.aihub</groupId>
        <artifactId>aihub-platform</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>aihub-gateway</artifactId>
    <name>aihub-gateway</name>

    <dependencies>
        <dependency>
            <groupId>com.aihub</groupId>
            <artifactId>aihub-common</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/AihubGatewayApplication.java`：

```java
package com.aihub.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AihubGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AihubGatewayApplication.class, args);
    }
}
```

创建 `aihub-gateway/src/main/resources/application.yml`：

```yaml
server:
  port: 8080

spring:
  application:
    name: aihub-gateway

management:
  endpoints:
    web:
      base-path: /
      exposure:
        include: health,info
      path-mapping:
        health: healthz
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 5: 运行测试，确认通过**

```powershell
mvn -B test
```

预期：`BUILD SUCCESS`，两个模块各 1 个测试通过，输出含 `Tests run: 1, Failures: 0, Errors: 0`（admin 与 gateway 各一行）。

- [ ] **Step 6: 提交**

```powershell
git add pom.xml aihub-admin aihub-gateway
git commit -m "feat: bootstrap multi-module skeleton with health endpoints"
```

---

## Task 2: 统一响应体、错误码与全局异常处理

**Files:**
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/api/ApiResponse.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/api/ErrorCode.java`
- Create: `aihub-admin/aihub-common/src/main/java/com/aihub/common/exception/BizException.java`
- Create: `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/error/GlobalExceptionHandler.java`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/web/error/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: Task 1 建立的 `aihub-common` 与 `aihub-web` 模块。
- Produces:
  - `com.aihub.common.api.ApiResponse<T>`，静态工厂 `ApiResponse.ok(T data)` 与 `ApiResponse.fail(ErrorCode errorCode, String message)`
  - `com.aihub.common.api.ErrorCode` 枚举，方法 `int httpStatus()`、`String code()`（等于枚举名）
  - `com.aihub.common.exception.BizException(ErrorCode errorCode, String message)`，方法 `ErrorCode errorCode()`
  - `com.aihub.admin.web.error.GlobalExceptionHandler`：所有 admin 接口的错误响应格式由它统一产出，后续模块直接 `throw new BizException(...)` 即可。

- [ ] **Step 1: 写失败测试**

创建 `aihub-admin/aihub-web/src/test/java/com/aihub/admin/web/error/GlobalExceptionHandlerTest.java`：

```java
package com.aihub.admin.web.error;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        GlobalExceptionHandlerTest.ProbeController.class,
        GlobalExceptionHandlerTest.ValidatedProbeController.class})
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void bizExceptionMapsToItsHttpStatusAndCode() throws Exception {
        mockMvc.perform(get("/__probe/biz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("tenant not found"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unexpectedExceptionBecomesInternalErrorWithoutLeakingMessage() throws Exception {
        mockMvc.perform(get("/__probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("internal error"));
    }

    @Test
    void requestBodyValidationBecomesInvalidParam() throws Exception {
        mockMvc.perform(post("/__probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"n\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void requestParamValidationBecomesInvalidParam() throws Exception {
        mockMvc.perform(get("/__probe/param").param("n", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void unknownPathStaysNotFoundInsteadOfInternalError() throws Exception {
        mockMvc.perform(get("/__probe/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @RestController
    static class ProbeController {

        @GetMapping("/__probe/biz")
        String biz() {
            throw new BizException(ErrorCode.NOT_FOUND, "tenant not found");
        }

        @GetMapping("/__probe/boom")
        String boom() {
            throw new IllegalStateException("database credentials leaked here");
        }

        @PostMapping("/__probe/validate")
        String validate(@RequestBody @Valid Payload payload) {
            return "ok:" + payload.n();
        }
    }

    record Payload(@Min(1) int n) {
    }

    @RestController
    @Validated
    static class ValidatedProbeController {

        @GetMapping("/__probe/param")
        String param(@RequestParam("n") @Min(1) int n) {
            return "ok:" + n;
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```powershell
mvn -B -pl aihub-admin/aihub-web -am test -Dtest=GlobalExceptionHandlerTest
```

预期：编译失败，报 `cannot find symbol: class ApiResponse` / `class ErrorCode` / `class BizException` / `class GlobalExceptionHandler`。

- [ ] **Step 3: 写 common 模块的三个类型**

创建 `aihub-admin/aihub-common/src/main/java/com/aihub/common/api/ErrorCode.java`：

```java
package com.aihub.common.api;

/**
 * 全局错误码。枚举名即响应体中的 code 字段，httpStatus 决定 HTTP 状态码。
 */
public enum ErrorCode {

    INVALID_PARAM(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    RATE_LIMITED(429),
    QUOTA_EXCEEDED(429),
    UPSTREAM_ERROR(502),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return name();
    }
}
```

创建 `aihub-admin/aihub-common/src/main/java/com/aihub/common/api/ApiResponse.java`：

```java
package com.aihub.common.api;

/**
 * 所有 HTTP 接口的统一响应体。字段顺序固定为 code / message / data。
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "success", data);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null);
    }
}
```

创建 `aihub-admin/aihub-common/src/main/java/com/aihub/common/exception/BizException.java`：

```java
package com.aihub.common.exception;

import com.aihub.common.api.ErrorCode;

/**
 * 业务异常：携带错误码，由 GlobalExceptionHandler 统一转换为响应体。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
```

- [ ] **Step 4: 写全局异常处理器**

创建 `aihub-admin/aihub-web/src/main/java/com/aihub/admin/web/error/GlobalExceptionHandler.java`：

```java
package com.aihub.admin.web.error;

import com.aihub.common.api.ApiResponse;
import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException ex) {
        ErrorCode errorCode = ex.errorCode();
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.fail(errorCode, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + defaultMessage(fieldError))
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_PARAM, message));
    }

    /**
     * 方法参数上的约束（如 @RequestParam @Min）由 @Validated 触发，
     * 抛的是 ConstraintViolationException，与 @RequestBody 校验走的不是同一条路径。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_PARAM, message));
    }

    /**
     * Spring 自己抛出的异常（404、405、415 等）已经带了正确状态码，必须保留，
     * 否则会被下面的兜底分支统一变成 500。
     * 注意：注解值必须是 Throwable 的子类，所以这里用 ErrorResponseException
     * （NoResourceFoundException、ResponseStatusException 都是它的子类）。
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ApiResponse<Void>> handleErrorResponse(ErrorResponseException ex) {
        ErrorCode errorCode = ex.getStatusCode().value() == 404 ? ErrorCode.NOT_FOUND : ErrorCode.INVALID_PARAM;
        String detail = ex.getBody().getDetail();
        String message = detail == null ? "request rejected" : detail;
        return ResponseEntity.status(ex.getStatusCode()).body(ApiResponse.fail(errorCode, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "internal error"));
    }

    private String defaultMessage(FieldError fieldError) {
        String message = fieldError.getDefaultMessage();
        return message == null ? "invalid" : message;
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

```powershell
mvn -B -pl aihub-admin/aihub-web -am test -Dtest=GlobalExceptionHandlerTest
```

预期：`Tests run: 5, Failures: 0, Errors: 0`。

- [ ] **Step 6: 跑全量测试，确认没有破坏 Task 1**

```powershell
mvn -B test
```

预期：`BUILD SUCCESS`。

- [ ] **Step 7: 提交**

```powershell
git add aihub-admin
git commit -m "feat: add unified response envelope and global exception handling"
```

---

## Task 3: Flyway 建表脚本与真实 MySQL 验证

**Files:**
- Create: `aihub-admin/aihub-dao/src/main/resources/db/migration/V1__init_schema.sql`
- Modify: `aihub-admin/aihub-web/pom.xml`（新增 `aihub-dao`、Testcontainers 依赖）
- Modify: `aihub-admin/aihub-web/src/main/resources/application.yml`（新增 datasource 与 flyway 配置）
- Modify: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/AihubAdminApplicationTests.java`（改为继承容器基类）
- Create: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/support/AbstractIntegrationTest.java`
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/dao/SchemaMigrationTest.java`

**Interfaces:**
- Consumes: Task 1 的 `aihub-web` 模块与 `ApplicationContext`。
- Produces:
  - 数据库 `aihub` 中 10 张表：`tenant`、`sys_user`、`api_key`、`channel`、`model_route`、`quota`、`rate_limit_policy`、`request_log`、`kb_document`、`billing_daily`
  - `com.aihub.admin.support.AbstractIntegrationTest`：所有需要真实基础设施的测试都继承它；Task 4 会往它的容器列表里再加 Redis 与 RabbitMQ
  - `request_log` 的幂等语义：`(request_id, created_at)` 唯一键，重复插入抛 `DuplicateKeyException`

- [ ] **Step 1: 写失败测试**

创建 `aihub-admin/aihub-web/src/test/java/com/aihub/admin/support/AbstractIntegrationTest.java`（此时只启 MySQL）：

```java
package com.aihub.admin.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 需要真实基础设施的集成测试的统一基类。
 * 容器使用单例模式：整个 JVM 只启动一次，被所有测试类共享，由 Testcontainers 的 Ryuk 负责回收。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("aihub")
                    .withUsername("aihub")
                    .withPassword("aihub");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
```

创建 `aihub-admin/aihub-web/src/test/java/com/aihub/admin/dao/SchemaMigrationTest.java`：

```java
package com.aihub.admin.dao;

import com.aihub.admin.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesExactlyOneMigration() {
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = 1", Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void allTenTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = database() and table_name <> 'flyway_schema_history'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "tenant", "sys_user", "api_key", "channel", "model_route", "quota",
                "rate_limit_policy", "request_log", "kb_document", "billing_daily");
    }

    @Test
    void requestLogIsPartitionedByCreatedAt() {
        Integer partitions = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.partitions "
                        + "where table_schema = database() and table_name = 'request_log' "
                        + "and partition_name is not null",
                Integer.class);

        assertThat(partitions).isGreaterThan(1);
    }

    @Test
    void requestLogRejectsDuplicateRequestIdWithinSameCreatedAt() {
        insertRequestLog("req-dup-1");

        assertThatThrownBy(() -> insertRequestLog("req-dup-1"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private void insertRequestLog(String requestId) {
        jdbcTemplate.update(
                "insert into request_log (request_id, tenant_id, status, created_at) values (?, ?, ?, ?)",
                requestId, 1L, "SUCCESS", Timestamp.from(Instant.parse("2026-09-23T10:00:00Z")));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

先确认 Docker Desktop 正在运行：

```powershell
docker info --format '{{.ServerVersion}}'
```

预期：输出一个版本号（例如 `27.3.1`）。若报错，先启动 Docker Desktop 再继续。

```powershell
mvn -B -pl aihub-admin/aihub-web -am test -Dtest=SchemaMigrationTest
```

预期：编译失败，报 `package org.testcontainers.containers does not exist`（因为还没加依赖，也还没有建表脚本）。

- [ ] **Step 3: 写建表脚本**

创建 `aihub-admin/aihub-dao/src/main/resources/db/migration/V1__init_schema.sql`：

```sql
-- aihub-platform 初始表结构
-- 约定：字符集 utf8mb4；时间统一 datetime(3)，按 UTC 存储

CREATE TABLE tenant (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(128) NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(72)  NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'ADMIN',
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    KEY idx_sys_user_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE api_key (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    key_id       VARCHAR(32)  NOT NULL,
    tenant_id    BIGINT       NOT NULL,
    key_hash     CHAR(64)     NOT NULL,
    name         VARCHAR(128) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    expire_at    DATETIME(3)  NULL,
    last_used_at DATETIME(3)  NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_key_key_id (key_id),
    UNIQUE KEY uk_api_key_key_hash (key_hash),
    KEY idx_api_key_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE channel (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    name           VARCHAR(128)  NOT NULL,
    provider       VARCHAR(32)   NOT NULL,
    base_url       VARCHAR(255)  NOT NULL,
    api_key_cipher VARCHAR(1024) NOT NULL,
    key_version    INT           NOT NULL DEFAULT 1,
    models_json    JSON          NULL,
    weight         INT           NOT NULL DEFAULT 100,
    priority       INT           NOT NULL DEFAULT 0,
    timeout_ms     INT           NOT NULL DEFAULT 60000,
    status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE model_route (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    model_name VARCHAR(128) NOT NULL,
    channel_id BIGINT       NOT NULL,
    weight     INT          NOT NULL DEFAULT 100,
    priority   INT          NOT NULL DEFAULT 0,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_route (model_name, channel_id),
    KEY idx_model_route_model (model_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE quota (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    period        VARCHAR(8)  NOT NULL,
    token_limit   BIGINT      NOT NULL DEFAULT 0,
    token_used    BIGINT      NOT NULL DEFAULT 0,
    request_limit BIGINT      NOT NULL DEFAULT 0,
    request_used  BIGINT      NOT NULL DEFAULT 0,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_quota_tenant_period (tenant_id, period)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE rate_limit_policy (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    api_key_id BIGINT      NULL,
    qps        INT         NOT NULL DEFAULT 10,
    burst      INT         NOT NULL DEFAULT 20,
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_rate_limit_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- request_log 按月分区。
-- 注意：MySQL 要求分区列必须出现在表的每一个唯一索引中，因此主键与 request_id
-- 唯一键都带上了 created_at。结果是「同一个 request_id 且同一个 created_at」才构成
-- 幂等冲突 —— 这正好是计量事件重投时的形态。
CREATE TABLE request_log (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    request_id        VARCHAR(64)  NOT NULL,
    tenant_id         BIGINT       NOT NULL,
    api_key_id        BIGINT       NULL,
    channel_id        BIGINT       NULL,
    model             VARCHAR(128) NULL,
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    total_tokens      INT          NOT NULL DEFAULT 0,
    latency_ms        INT          NOT NULL DEFAULT 0,
    ttft_ms           INT          NULL,
    status            VARCHAR(16)  NOT NULL,
    error_code        VARCHAR(64)  NULL,
    created_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id, created_at),
    UNIQUE KEY uk_request_log_request_id (request_id, created_at),
    KEY idx_request_log_tenant_created (tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
PARTITION BY RANGE COLUMNS (created_at) (
    PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
    PARTITION p202610 VALUES LESS THAN ('2026-11-01'),
    PARTITION p202611 VALUES LESS THAN ('2026-12-01'),
    PARTITION pmax    VALUES LESS THAN (MAXVALUE)
);

CREATE TABLE kb_document (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT        NOT NULL,
    filename    VARCHAR(255)  NOT NULL,
    size_bytes  BIGINT        NOT NULL DEFAULT 0,
    sha256      CHAR(64)      NOT NULL,
    status      VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    chunk_count INT           NOT NULL DEFAULT 0,
    error_msg   VARCHAR(1024) NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_document_tenant_sha (tenant_id, sha256),
    KEY idx_kb_document_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE billing_daily (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT         NOT NULL,
    stat_date  DATE           NOT NULL,
    requests   BIGINT         NOT NULL DEFAULT 0,
    tokens     BIGINT         NOT NULL DEFAULT 0,
    cost       DECIMAL(18, 6) NOT NULL DEFAULT 0,
    created_at DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_billing_daily (tenant_id, stat_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

- [ ] **Step 4: 接入 dao 依赖、数据源与 Flyway 配置**

修改 `aihub-admin/aihub-web/pom.xml`，在 `<dependencies>` 中 `aihub-common` 之后插入：

```xml
        <dependency>
            <groupId>com.aihub</groupId>
            <artifactId>aihub-dao</artifactId>
        </dependency>
```

并在 `spring-boot-starter-test` 之后追加：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mysql</artifactId>
            <scope>test</scope>
        </dependency>
```

修改 `aihub-admin/aihub-web/src/main/resources/application.yml`，在 `spring:` 下新增：

```yaml
spring:
  application:
    name: aihub-admin
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://127.0.0.1:3307/aihub?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}
    username: ${SPRING_DATASOURCE_USERNAME:aihub}
    password: ${SPRING_DATASOURCE_PASSWORD:aihub}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false
```

- [ ] **Step 5: 让 Task 1 的上下文测试改用容器**

修改 `aihub-admin/aihub-web/src/test/java/com/aihub/admin/AihubAdminApplicationTests.java`：删掉原来的 `@SpringBootTest` 注解与 `TestRestTemplate` 的 WebEnvironment 参数（改为继承基类，`RANDOM_PORT` 与容器属性由基类提供）。最终内容为：

```java
package com.aihub.admin;

import com.aihub.admin.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AihubAdminApplicationTests extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthzReturnsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/healthz", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
```

- [ ] **Step 6: 运行测试，确认通过**

```powershell
mvn -B -pl aihub-admin/aihub-web -am test -Dtest=SchemaMigrationTest
```

预期：`Tests run: 4, Failures: 0, Errors: 0`（首次运行需拉取 `mysql:8.4` 镜像，可能耗时数分钟）。

- [ ] **Step 7: 跑全量测试**

```powershell
mvn -B test
```

预期：`BUILD SUCCESS`。

- [ ] **Step 8: 提交**

```powershell
git add aihub-admin
git commit -m "feat: add flyway schema for ten core tables with testcontainers coverage"
```

---

## Task 4: 接入 Redis 与 RabbitMQ，让健康检查反映真实状态

**Files:**
- Modify: `aihub-admin/aihub-service/pom.xml`（新增 Redis 与 Caffeine）
- Modify: `aihub-admin/aihub-web/pom.xml`（新增 `aihub-service`、`aihub-mq`、RabbitMQ 测试容器）
- Modify: `aihub-admin/aihub-web/src/main/resources/application.yml`（新增 Redis 与 RabbitMQ 配置）
- Modify: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/support/AbstractIntegrationTest.java`（加入 Redis 与 RabbitMQ 容器）
- Test: `aihub-admin/aihub-web/src/test/java/com/aihub/admin/health/InfrastructureHealthTest.java`

**Interfaces:**
- Consumes: Task 3 的 `AbstractIntegrationTest` 与 `application.yml`。
- Produces:
  - admin 进程内可用的 `StringRedisTemplate` 与 `RabbitTemplate`（由 Spring Boot 自动配置）
  - `/healthz` 的 `components` 中出现 `db`、`redis`、`rabbit` 三个键，供 M1 之后作为就绪探针使用

- [ ] **Step 1: 写失败测试**

创建 `aihub-admin/aihub-web/src/test/java/com/aihub/admin/health/InfrastructureHealthTest.java`：

```java
package com.aihub.admin.health;

import com.aihub.admin.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class InfrastructureHealthTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthzReportsAllThreeInfrastructureComponents() {
        ResponseEntity<String> response = restTemplate.getForEntity("/healthz", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"status\":\"UP\"");
        assertThat(body).contains("\"db\"");
        assertThat(body).contains("\"redis\"");
        assertThat(body).contains("\"rabbit\"");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```powershell
mvn -B -pl aihub-admin/aihub-web -am test -Dtest=InfrastructureHealthTest
```

预期：测试失败，断言 `contains("\"redis\"")` 不成立（此时还没有 Redis 与 RabbitMQ 依赖，`components` 里只有 `db` 与 `diskSpace`）。

- [ ] **Step 3: 加依赖**

修改 `aihub-admin/aihub-service/pom.xml`，在 `aihub-mq` 依赖之后追加：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>
```

修改 `aihub-admin/aihub-web/pom.xml`，在 `aihub-dao` 依赖之后追加：

```xml
        <dependency>
            <groupId>com.aihub</groupId>
            <artifactId>aihub-service</artifactId>
        </dependency>
        <dependency>
            <groupId>com.aihub</groupId>
            <artifactId>aihub-mq</artifactId>
        </dependency>
```

并在 `org.testcontainers:mysql` 之后追加：

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>rabbitmq</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 4: 加配置**

修改 `aihub-admin/aihub-web/src/main/resources/application.yml`，在 `spring:` 下 `flyway:` 之后新增：

```yaml
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:127.0.0.1}
      port: ${SPRING_DATA_REDIS_PORT:6379}
      timeout: 2s
  rabbitmq:
    host: ${SPRING_RABBITMQ_HOST:127.0.0.1}
    port: ${SPRING_RABBITMQ_PORT:5672}
    username: ${SPRING_RABBITMQ_USERNAME:aihub}
    password: ${SPRING_RABBITMQ_PASSWORD:aihub}
    connection-timeout: 5s
```

- [ ] **Step 5: 把容器加入测试基类**

替换 `aihub-admin/aihub-web/src/test/java/com/aihub/admin/support/AbstractIntegrationTest.java` 的全部内容：

```java
package com.aihub.admin.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 需要真实基础设施的集成测试的统一基类。
 * 容器使用单例模式：整个 JVM 只启动一次，被所有测试类共享，由 Testcontainers 的 Ryuk 负责回收。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final int REDIS_PORT = 6379;

    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("aihub")
                    .withUsername("aihub")
                    .withPassword("aihub");

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    protected static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    static {
        MYSQL.start();
        REDIS.start();
        RABBITMQ.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }
}
```

- [ ] **Step 6: 运行测试，确认通过**

```powershell
mvn -B -pl aihub-admin/aihub-web -am test -Dtest=InfrastructureHealthTest
```

预期：`Tests run: 1, Failures: 0, Errors: 0`。

- [ ] **Step 7: 跑全量测试，确认三个测试类共享容器且全绿**

```powershell
mvn -B test
```

预期：`BUILD SUCCESS`，admin 侧 4 个测试类共 11 个用例全部通过（`AihubAdminApplicationTests` 1 + `GlobalExceptionHandlerTest` 5 + `SchemaMigrationTest` 4 + `InfrastructureHealthTest` 1），gateway 侧 2 个用例通过。以实际输出为准，关键要求是 `Failures: 0, Errors: 0`。

- [ ] **Step 8: 提交**

```powershell
git add aihub-admin
git commit -m "feat: wire redis and rabbitmq with health reporting"
```

---

## Task 5: gateway 最小 SSE 转发（可行性验证）

> 这个 Task 对应设计文档第 13 节的风险应对：在进入 M1 之前，先用最小实现验证 WebFlux 转发流式响应的可行性。

**Files:**
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamProperties.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamClientConfig.java`
- Create: `aihub-gateway/src/main/java/com/aihub/gateway/relay/ChatRelayController.java`
- Modify: `aihub-gateway/src/main/resources/application.yml`（新增 `aihub.upstream.base-url`）
- Test: `aihub-gateway/src/test/java/com/aihub/gateway/relay/ChatRelayControllerTest.java`

**Interfaces:**
- Consumes: Task 1 的 `aihub-gateway` 模块。
- Produces:
  - `POST /v1/chat/completions`：请求体原样透传给上游，响应以 `text/event-stream` 逐帧回写
  - `com.aihub.gateway.upstream.UpstreamProperties`（前缀 `aihub.upstream`，字段 `baseUrl`）
  - `WebClient` bean，名为 `upstreamWebClient`，M1 的鉴权、限流、路由都会拿它做转发
  - 注意：本 Task 只做「能转发」，**不含**鉴权、限流、配额、多渠道路由与计量，那些是 M1–M3 的内容

- [ ] **Step 1: 写失败测试**

创建 `aihub-gateway/src/test/java/com/aihub/gateway/relay/ChatRelayControllerTest.java`：

```java
package com.aihub.gateway.relay;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatRelayControllerTest {

    private static final String SSE_BODY = """
            data: {"choices":[{"delta":{"content":"你"}}]}

            data: {"choices":[{"delta":{"content":"好"}}]}

            data: [DONE]

            """;

    private static final HttpServer UPSTREAM;
    private static final int UPSTREAM_PORT;

    static {
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            UPSTREAM.createContext("/v1/chat/completions", exchange -> {
                byte[] payload = SSE_BODY.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            UPSTREAM.start();
            UPSTREAM_PORT = UPSTREAM.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start fake upstream", e);
        }
    }

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void upstreamBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("aihub.upstream.base-url", () -> "http://127.0.0.1:" + UPSTREAM_PORT);
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @Test
    void relaysUpstreamSseFramesToClient() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + gatewayPort + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":true,\"messages\":[]}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/event-stream"));
        assertThat(response.body()).contains("你").contains("好").contains("[DONE]");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```powershell
mvn -B -pl aihub-gateway -am test -Dtest=ChatRelayControllerTest
```

预期：测试失败，`response.statusCode()` 为 404（`/v1/chat/completions` 还没有对应的 Controller）。

- [ ] **Step 3: 写配置属性与 WebClient**

创建 `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamProperties.java`：

```java
package com.aihub.gateway.upstream;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上游模型服务配置。M0 只有单渠道的 base-url；
 * M3 引入多渠道后，这里会扩展为「渠道快照」的一部分。
 */
@ConfigurationProperties(prefix = "aihub.upstream")
public record UpstreamProperties(String baseUrl) {
}
```

创建 `aihub-gateway/src/main/java/com/aihub/gateway/upstream/UpstreamClientConfig.java`：

```java
package com.aihub.gateway.upstream;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(UpstreamProperties.class)
public class UpstreamClientConfig {

    @Bean
    public WebClient upstreamWebClient(UpstreamProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(120));

        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
```

- [ ] **Step 4: 写转发 Controller**

创建 `aihub-gateway/src/main/java/com/aihub/gateway/relay/ChatRelayController.java`：

```java
package com.aihub.gateway.relay;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * M0 的最小流式转发：把请求体原样交给上游，把上游的 SSE 帧原样交回客户端。
 * 鉴权、限流、配额、路由与计量在 M1–M3 加在它前面。
 */
@RestController
public class ChatRelayController {

    private final WebClient upstreamWebClient;

    public ChatRelayController(WebClient upstreamWebClient) {
        this.upstreamWebClient = upstreamWebClient;
    }

    @PostMapping(path = "/v1/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatCompletions(@RequestBody String body) {
        return upstreamWebClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                });
    }
}
```

- [ ] **Step 5: 加上游地址配置**

修改 `aihub-gateway/src/main/resources/application.yml`，在文件末尾追加：

```yaml
aihub:
  upstream:
    base-url: ${AIHUB_UPSTREAM_BASE_URL:http://127.0.0.1:11434}
```

- [ ] **Step 6: 运行测试，确认通过**

```powershell
mvn -B -pl aihub-gateway -am test -Dtest=ChatRelayControllerTest
```

预期：`Tests run: 1, Failures: 0, Errors: 0`。

- [ ] **Step 7: 提交**

```powershell
git add aihub-gateway
git commit -m "feat: relay upstream sse frames through webflux gateway"
```

---

## Task 6: Docker Compose 一条命令起全栈

**Files:**
- Create: `.env.example`
- Create: `aihub-admin/aihub-web/Dockerfile`
- Create: `aihub-gateway/Dockerfile`
- Create: `docker-compose.yml`
- Create: `.dockerignore`

**Interfaces:**
- Consumes: Task 1–5 产出的两个可执行 jar 与全部配置项。
- Produces: `docker compose up -d --build` 后，`http://localhost:8081/healthz`（admin）与 `http://localhost:8080/healthz`（gateway）均返回 `UP`；MySQL 暴露在宿主机 `3307`。

- [ ] **Step 1: 写 Dockerfile 与 .dockerignore**

创建 `.dockerignore`：

```
**/target
**/.git
**/.idea
**/*.iml
.env
logs
docs
```

创建 `aihub-admin/aihub-web/Dockerfile`：

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY aihub-admin ./aihub-admin
COPY aihub-gateway ./aihub-gateway
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl aihub-admin/aihub-web -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/aihub-admin/aihub-web/target/aihub-web-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

创建 `aihub-gateway/Dockerfile`：

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY aihub-admin ./aihub-admin
COPY aihub-gateway ./aihub-gateway
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl aihub-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/aihub-gateway/target/aihub-gateway-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 2: 写 .env.example**

创建 `.env.example`：

```ini
# 复制为 .env 后再修改；.env 已被 .gitignore 忽略，不要提交真实口令
MYSQL_ROOT_PASSWORD=change-me-root
MYSQL_PASSWORD=change-me
RABBITMQ_USER=aihub
RABBITMQ_PASSWORD=change-me

# gateway 转发的上游模型服务地址（OpenAI 兼容）
# 本地 Ollama: http://host.docker.internal:11434
AIHUB_UPSTREAM_BASE_URL=http://host.docker.internal:11434
```

- [ ] **Step 3: 写 docker-compose.yml**

创建 `docker-compose.yml`：

```yaml
services:
  mysql:
    image: mysql:8.4
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?set MYSQL_ROOT_PASSWORD in .env}
      MYSQL_DATABASE: aihub
      MYSQL_USER: aihub
      MYSQL_PASSWORD: ${MYSQL_PASSWORD:?set MYSQL_PASSWORD in .env}
    ports:
      - "3307:3306"
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-uroot", "-p$$MYSQL_ROOT_PASSWORD"]
      interval: 5s
      timeout: 3s
      retries: 30
      start_period: 30s

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 20

  rabbitmq:
    image: rabbitmq:3.13-management-alpine
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER:-aihub}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:?set RABBITMQ_PASSWORD in .env}
    ports:
      - "5672:5672"
      - "15672:15672"
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 5s
      retries: 20
      start_period: 30s

  admin:
    build:
      context: .
      dockerfile: aihub-admin/aihub-web/Dockerfile
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/aihub?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
      SPRING_DATASOURCE_USERNAME: aihub
      SPRING_DATASOURCE_PASSWORD: ${MYSQL_PASSWORD:?set MYSQL_PASSWORD in .env}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_USERNAME: ${RABBITMQ_USER:-aihub}
      SPRING_RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:?set RABBITMQ_PASSWORD in .env}
    ports:
      - "8081:8081"

  gateway:
    build:
      context: .
      dockerfile: aihub-gateway/Dockerfile
    depends_on:
      admin:
        condition: service_started
    environment:
      AIHUB_UPSTREAM_BASE_URL: ${AIHUB_UPSTREAM_BASE_URL:-http://host.docker.internal:11434}
    ports:
      - "8080:8080"

volumes:
  mysql-data:
```

- [ ] **Step 4: 手工验收：起全栈并检查健康**

```powershell
Copy-Item .env.example .env
docker compose up -d --build
```

首次构建会拉镜像并下载 Maven 依赖，可能超过 10 分钟。等待结束后：

```powershell
docker compose ps
```

预期：`mysql`、`redis`、`rabbitmq` 三个服务显示为 `healthy`；`admin` 与 `gateway` 显示为 `running`（这两个服务在 M0 尚未配置容器级 healthcheck，其健康状态以 `/healthz` 为准）。

```powershell
Start-Sleep -Seconds 20
curl.exe -s http://localhost:8081/healthz
curl.exe -s http://localhost:8080/healthz
```

预期：两条命令各返回一段 JSON，均含 `"status":"UP"`；admin 的返回中还含 `"db"`、`"redis"`、`"rabbit"` 三个组件。

若 admin 返回 `DOWN`，用下面这条看具体原因：

```powershell
docker compose logs admin --tail 50
```

- [ ] **Step 5: 手工验收：清理**

```powershell
docker compose down
```

预期：所有容器停止并删除；数据保留在 `mysql-data` 卷中，下次 `up` 仍可看到已建的表。

- [ ] **Step 6: 提交**

```powershell
git add .env.example .dockerignore docker-compose.yml aihub-admin/aihub-web/Dockerfile aihub-gateway/Dockerfile
git commit -m "chore: add docker compose full-stack deployment"
```

---

## Task 7: README、项目约定与里程碑标签

**Files:**
- Create: `README.md`
- Create: `docs/CONVENTIONS.md`

**Interfaces:**
- Consumes: Task 1–6 的全部产出。
- Produces: 项目对外说明与团队约定；`m0` git tag 作为 M0 验收标记。

- [ ] **Step 1: 写项目约定文档**

创建 `docs/CONVENTIONS.md`：

```markdown
# 项目约定

## 1. 模块与包名

| 模块 | 包名前缀 | 职责 |
|---|---|---|
| `aihub-common` | `com.aihub.common` | 零依赖共享类型：响应体、错误码、业务异常 |
| `aihub-dao` | `com.aihub.dao` | Entity、Mapper、Flyway 迁移脚本 |
| `aihub-service` | `com.aihub.service` | 业务服务 |
| `aihub-mq` | `com.aihub.mq` | 消息生产与消费 |
| `aihub-web` | `com.aihub.admin` | Controller、配置、启动类 |
| `aihub-gateway` | `com.aihub.gateway` | 数据面：鉴权、限流、路由、转发、计量 |

依赖方向严格单向：`web → service → dao`，`web → mq`。`aihub-gateway` 不得依赖 admin 的任何模块。

## 2. 端口

admin `8081`；gateway `8080`；MySQL 宿主机 `3307`；Redis `6379`；RabbitMQ `5672`（管理台 `15672`）。

## 3. 接口约定

- 所有 admin 接口返回 `{"code","message","data"}`；`code` 为 `ErrorCode` 枚举名，成功为 `OK`。
- 业务错误直接 `throw new BizException(ErrorCode.X, "说明")`，不要自己拼响应体。
- 健康检查统一为 `/healthz`，不带鉴权。
- 数据面接口（`/v1/**`）遵循 OpenAI 兼容协议，错误响应使用标准 HTTP 状态码。

## 4. 数据库约定

- 字符集 `utf8mb4`，时间字段 `datetime(3)` 且按 UTC 存储。
- 表结构变更一律新增 `V{n}__{描述}.sql`，禁止修改已执行过的迁移脚本。
- `request_log` 按月分区；由于 MySQL 要求分区列出现在每个唯一索引中，其主键为 `(id, created_at)`。

## 5. 测试纪律

- 需要真实基础设施的测试必须继承 `AbstractIntegrationTest`（Testcontainers 单例容器），执行前确认 Docker Desktop 在运行。
- 纯 Web 层测试用 `@WebMvcTest`，不要为了省事拖起整个上下文。
- 同一个 bug 的修复必须先补一个会失败的测试。

## 6. 提交约定

- conventional commits：`feat:` / `fix:` / `test:` / `chore:` / `docs:` / `refactor:`。
- 每个里程碑完成后打 tag：`m0`、`m1`……
- 密钥、口令一律不进仓库，走环境变量或 `.env`。
```

- [ ] **Step 2: 写 README**

创建 `README.md`：

```markdown
# aihub-platform

面向「大模型应用平台」场景的后端系统：一个统一的 OpenAI 兼容网关 + 一套控制面业务平台。

- **aihub-gateway**（数据面）：统一入口，负责鉴权、限流、配额、多渠道路由、SSE 流式转发与 token 计量。
- **aihub-admin**（控制面）：租户、API Key、渠道、配额、知识库文档、审计与账单的唯一真相源。

设计文档见 [`docs/superpowers/specs/2026-09-23-aihub-platform-design.md`](docs/superpowers/specs/2026-09-23-aihub-platform-design.md)。

## 当前进度

- [x] **M0 地基**：多模块骨架、统一响应与异常、Flyway 表结构、基础设施连通与健康检查、最小 SSE 转发验证、Docker Compose 全栈
- [ ] **M1 网关直通**：API Key 鉴权 + 单渠道非流式转发
- [ ] **M2 流式与计量**：SSE 转发 + usage 捕获 + 计量落库
- [ ] **M3 流量治理**：Lua 令牌桶限流 + 多渠道路由 + 故障转移
- [ ] **M4 业务平台**：租户 / API Key / 渠道管理 / 配额 / 审计
- [ ] **M5 异步流水线**：文档上传 → 解析 → 嵌入 → 向量库
- [ ] **M6 压测与打磨**：压测报告、故障注入报告、上线

## 技术栈

Java 21（编译目标）· Spring Boot 3.5.16 · MyBatis-Plus 3.5.17 · MySQL 8 · Flyway · Redis 7 · RabbitMQ 3.13 · WebFlux · Testcontainers · Docker Compose

## 快速开始

### 方式一：Docker Compose（推荐）

```powershell
Copy-Item .env.example .env   # 修改里面的口令
docker compose up -d --build
```

启动后：

- admin 健康检查：<http://localhost:8081/healthz>
- gateway 健康检查：<http://localhost:8080/healthz>
- RabbitMQ 管理台：<http://localhost:15672>

停止：`docker compose down`

### 方式二：本机运行

需要 JDK 17+（本仓库编译目标为 21）、Maven 3.6.3+，以及本机可访问的 MySQL / Redis / RabbitMQ。

```powershell
mvn -B clean package
java -jar aihub-admin/aihub-web/target/aihub-web-0.0.1-SNAPSHOT.jar
```

## 测试

需要 Docker Desktop 处于运行状态（集成测试使用 Testcontainers 起真实容器）。

```powershell
mvn -B test
```

## 目录结构

```text
aihub-platform/
├── aihub-admin/           控制面（Spring MVC 单体，多模块）
│   ├── aihub-common/      零依赖共享类型
│   ├── aihub-dao/         持久层与 Flyway 迁移
│   ├── aihub-service/     业务服务
│   ├── aihub-mq/          消息生产与消费
│   └── aihub-web/         Controller 与启动模块
├── aihub-gateway/         数据面（WebFlux 独立服务）
├── docs/                  设计文档、实施计划与项目约定
└── docker-compose.yml     全栈编排
```

项目约定见 [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md)。
```

- [ ] **Step 3: 提交并打标签**

```powershell
git add README.md docs/CONVENTIONS.md
git commit -m "docs: add readme and project conventions"
git tag -a m0 -m "M0 地基完成：多模块骨架、表结构、基础设施连通、SSE 转发验证、全栈编排"
git tag --list
```

预期：`git tag --list` 输出 `m0`。

- [ ] **Step 4: 最终验收**

```powershell
mvn -B clean test
```

预期：`BUILD SUCCESS`，所有测试通过，无 `Failures` / `Errors`。
