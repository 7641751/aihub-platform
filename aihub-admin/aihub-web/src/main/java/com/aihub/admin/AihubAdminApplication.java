package com.aihub.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// scanBasePackages 放宽到 com.aihub：aihub-service / aihub-dao 的 bean 都在 com.aihub.service /
// com.aihub.dao 下，不属于本类的子包，默认扫描范围（com.aihub.admin）看不到它们。
// mapper 的扫描入口在 com.aihub.dao.MybatisMapperConfig，不写在这里（原因见该类注释）。
@SpringBootApplication(scanBasePackages = "com.aihub")
public class AihubAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AihubAdminApplication.class, args);
    }
}
