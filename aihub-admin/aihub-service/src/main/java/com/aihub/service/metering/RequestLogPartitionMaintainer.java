package com.aihub.service.metering;

import com.aihub.service.metering.RequestLogPartitionPlanner.Partition;
import com.aihub.service.metering.RequestLogPartitionPlanner.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * {@code request_log} 的月分区维护：**启动补齐 + 每日定时**（计划「决策登记」第 3/11/15 条）。
 *
 * <p>为什么必须有：V1 只建到 {@code p202611}，之后所有行都会静默落进 {@code pmax} ——
 * 查询还能查到（所以不会有人发现），但按月归档/裁剪分区的路被堵死，pmax 会无限膨胀。
 * 「静默」是这里唯一的敌人，因此每次补齐都打日志，补不齐就抛。
 *
 * <p>时间基准是 **UTC**（与 {@code created_at} 的存储口径一致），不要用 JVM 默认时区。
 */
@Component
public class RequestLogPartitionMaintainer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RequestLogPartitionMaintainer.class);

    private final RequestLogPartitionRepository repository;
    private final int monthsAhead;

    public RequestLogPartitionMaintainer(RequestLogPartitionRepository repository,
                                        @Value("${aihub.metering.partition-months-ahead:2}") int monthsAhead) {
        this.repository = repository;
        this.monthsAhead = monthsAhead;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 启动即校验/补齐：起不来比「悄悄把用量写进 pmax」好（这是 fail-fast 的一处有意选择）。
        ensureCoverage(LocalDate.now(ZoneOffset.UTC));
    }

    /**
     * cron 显式钉在 {@code UTC}：日期算术用 {@link ZoneOffset#UTC}，触发时刻也必须同口径，
     * 否则「03:10」取决于 JVM 默认时区（月粒度下无害，但口径必须显式——本里程碑的硬规则）。
     */
    @Scheduled(cron = "${aihub.metering.partition-cron:0 10 3 * * *}", zone = "UTC")
    public void scheduledEnsureCoverage() {
        try {
            ensureCoverage(LocalDate.now(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            // 定时任务抛出会终止后续调度，必须吞掉并等下一轮。
            // 记日志必须带上 throwable，否则 SQL state / error code / 原始 SQL 全丢（e.toString() 只剩一行）。
            log.error("定时分区维护失败（下一轮重试）", e);
        }
    }

    /**
     * 保证 {@code [today, today + monthsAhead]} 的每个自然月都有**专属**分区。
     *
     * @throws IllegalStateException 表结构不符（缺 pmax）或补建后仍不覆盖
     */
    public void ensureCoverage(LocalDate today) {
        Plan plan = RequestLogPartitionPlanner.plan(repository.partitions(), today, monthsAhead);
        if (!plan.pmaxPresent()) {
            throw new IllegalStateException("request_log 缺少 pmax 分区：表结构不符合 V1 约定，请人工确认");
        }
        if (!plan.gaps().isEmpty()) {
            log.error("request_log 分区存在中间空洞 {}：这些月份的行会落进下一个分区（按日期查询仍正确，"
                    + "但无法按月归档）；补齐空洞需要 REORGANIZE 已有数据的分区，留待运维决策", plan.gaps());
        }
        DataAccessException ddlFailure = null;
        if (!plan.toCreate().isEmpty()) {
            List<String> names = plan.toCreate().stream().map(Partition::name).toList();
            long pmaxRowsBefore = repository.pmaxRowCount();
            log.warn("request_log 未来分区缺失，正在补建 {}（补建前 pmax 行数 {}）", names, pmaxRowsBefore);
            try {
                repository.reorganizePmax(plan.toCreate());
            } catch (DataAccessException e) {
                // 多实例同时启动会撞 DDL；这里不立刻失败，交给下面那次重读校验判定。
                // 带上 throwable：SQL state / error code / 原始语句只在栈里，e.toString() 会把它们丢掉。
                ddlFailure = e;
                log.warn("补建分区失败（可能是另一实例并发补建）", e);
            }
            log.info("分区补建后 pmax 行数 {} -> {}", pmaxRowsBefore, repository.pmaxRowCount());
        }
        List<Partition> current = repository.partitions();
        Plan after = RequestLogPartitionPlanner.plan(current, today, monthsAhead);
        if (!after.toCreate().isEmpty()) {
            // DDL 失败要接进 cause 链：否则运维只看到「补建后仍不覆盖」，看不到 1493 / SQL state 这些定位信息。
            throw new IllegalStateException("request_log 分区补建后仍不覆盖 " + today + " + " + monthsAhead
                    + " 个月，缺失: " + after.toCreate().stream().map(Partition::name).toList(), ddlFailure);
        }
        log.info("request_log 分区已覆盖今天往后 {} 个月（UTC 基准日 {}），最后上界 {}",
                monthsAhead, today, current.stream()
                        .filter(partition -> !partition.isMaxValue())
                        .map(Partition::upperBound)
                        .max(Comparator.naturalOrder())
                        .orElse(null));
    }
}
