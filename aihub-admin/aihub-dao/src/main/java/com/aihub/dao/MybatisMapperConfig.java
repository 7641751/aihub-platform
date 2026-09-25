package com.aihub.dao;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus mapper 的扫描入口。
 *
 * <p>刻意不放在 {@code AihubAdminApplication} 上：{@code @MapperScan} 是通过 {@code @Import}
 * 注册的，不受 {@code @WebMvcTest} 的 {@code TypeExcludeFilter} 约束，放在主类上会连带
 * import 到任何一个 {@code classes = AihubAdminApplication} 的 Web 切片测试里 —— 切片里没有
 * {@code SqlSessionFactory}，mapper 工厂 bean 直接创建失败（"Property 'sqlSessionFactory' or
 * 'sqlSessionTemplate' are required"）。独立成一个 {@code @Configuration} 后，切片测试按
 * 「非 Web 组件」把它过滤掉，完整应用上下文（{@code @SpringBootTest}）照旧扫描到它。
 */
@Configuration
@MapperScan("com.aihub.dao.mapper")
public class MybatisMapperConfig {
}
