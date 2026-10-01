package com.aihub.common.quota;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 配额的时间粒度：{@code quota.period} 是 **UTC** 的 {@code YYYYMM}（决策 D13）。
 *
 * <p>为什么是 {@code YYYYMM} 而不是 {@code YYYY-MM}：{@code quota.period} 列是 {@code VARCHAR(8)}，
 * {@code YYYYMM} 恰好 6 字符（留出 2 个字符余量），且按字典序可比较（能精确查、也能做前缀范围查）。
 *
 * <p>为什么**必须**用 UTC：{@code period} 是「哪个自然月」的判断基准，而配额桶的 TTL、对账的
 * {@code stat_date} 都以它为准。若用 JVM 默认时区（本机 Asia/Shanghai），月末最后一秒的请求会落到
 * <em>下一个月</em>的桶里 —— 与数据库里按 UTC 折出的 {@code period} 不一致，于是出现「读写两个桶」。
 * 折算一律显式用 {@link ZoneOffset#UTC}（CONVENTIONS §7 同一条纪律）。
 *
 * <p>只由 JDK 类型组成（{@code java.time}），不破坏 {@code aihub-common} main 作用域的零第三方依赖。
 */
public final class QuotaPeriod {

    /** {@code YYYYMM} 的固定长度。 */
    public static final int LENGTH = 6;

    private QuotaPeriod() {
    }

    /**
     * 把一个 epoch 毫秒折算成 UTC 的 {@code YYYYMM}。
     *
     * @param epochMillis 调用方给的时间基准（配额路径沿用 M3 决策 9：不用 Redis 的 {@code TIME}）
     * @return 形如 {@code "202609"} 的字符串
     */
    public static String of(long epochMillis) {
        LocalDate date = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate();
        return String.format("%04d%02d", date.getYear(), date.getMonthValue());
    }

    /**
     * 「下一个周期的起点」的 epoch 毫秒（UTC）。
     *
     * <p>{@code "202609"} → {@code 2026-10-01T00:00:00Z}。TTL 的公式在此基础上再加 1 天（见
     * {@link QuotaKeys#ttlMillis}），让跨月边界的请求与对账的补扣都被同一个桶罩住。
     *
     * @throws IllegalArgumentException {@code period} 不是合法的 {@code YYYYMM}（长度/非 ASCII 数字/月份越界）
     */
    public static long nextPeriodStartMillis(String period) {
        requireSixAsciiDigits(period);
        int year = Integer.parseInt(period.substring(0, 4));
        int month = Integer.parseInt(period.substring(4, 6));
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("period 的月份必须在 01..12： " + period);
        }
        return LocalDate.of(year, month, 1).plusMonths(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /**
     * 严格校验：长度 6 且每一位都是 **ASCII 数字**。
     *
     * <p>不能用 {@code Character.isDigit}（它接受阿拉伯-印度数字等非 ASCII 数字，而
     * {@code Integer.parseInt} 会拒绝它们 —— 校验与解析必须用同一套字符集），也不能靠
     * {@code NumberFormatException} 兜底（{@code parseInt} 接受前导 {@code +/-}，会让 {@code "+02609"}
     * 这种串蒙混过关）。
     */
    private static void requireSixAsciiDigits(String period) {
        if (period == null || period.length() != LENGTH) {
            throw new IllegalArgumentException("period 必须是 " + LENGTH + " 位的 YYYYMM： " + period);
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = period.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("period 必须是 " + LENGTH + " 位的 ASCII 数字 YYYYMM： " + period);
            }
        }
    }
}
