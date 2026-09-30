package com.aihub.admin.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「最小非零时区偏移有多大」的**可重跑夹具**（2026-09-30 从一次性探针升级而来）。
 *
 * <p><b>为什么需要它</b>：{@code ConfigSnapshotServiceTest} 里那条「基准错一定会红、不会被容差吃掉」的
 * 结论依赖一个具体数字 —— 「本 tzdata 里最小的**非零**偏移」是多少秒。按 {@code docs/CONVENTIONS.md} §8
 * 的纪律，文档与注释里的数字必须点名产生它的产物；这个用例就是那个产物。
 *
 * <p><b>它的结论是「瞬时限定」的</b>（这一点曾经被写漏）：下面三个瞬时都取在 <b>2026 年</b>
 * （1/7/10 月，覆盖南北半球夏令时切换）。历史上存在更小的偏移（例如 1900 年前后的 LMT 可能只有几分钟），
 * 所以正确的说法是「**在这三个 2026 瞬时上**，最小非零偏移是 60 分钟」—— 而这正是本应用会写下行的时间范围，
 * 结论因此够用。
 *
 * <p>纯 {@code java.time}，不需要容器，也不需要 Spring 上下文。
 */
class TimeZoneOffsetExtremesTest {

    /** 三个 2026 年的瞬时（Jan / Jul / Oct），用来覆盖夏令时造成的偏移变化。 */
    private static final Instant[] INSTANTS_2026 = {
            Instant.parse("2026-01-15T12:00:00Z"),
            Instant.parse("2026-07-15T12:00:00Z"),
            Instant.parse("2026-10-15T12:00:00Z"),
    };

    /** 断言用的 2 秒：快照水位那条容差的数量级。 */
    private static final long TOLERANCE_MILLIS = 2_000L;

    @Test
    void onTheseThreeTwoThousandTwentySixInstantsTheSmallestNonZeroOffsetIsOneHour() {
        int minAbsSeconds = Integer.MAX_VALUE;
        String where = null;
        int maxAbsSeconds = 0;
        int zones = 0;
        for (String id : ZoneId.getAvailableZoneIds()) {
            ZoneId zone = ZoneId.of(id);
            zones++;
            for (Instant instant : INSTANTS_2026) {
                int seconds = zone.getRules().getOffset(instant).getTotalSeconds();
                if (seconds != 0 && Math.abs(seconds) < minAbsSeconds) {
                    minAbsSeconds = Math.abs(seconds);
                    where = id + " @" + instant + " -> " + seconds + "s";
                }
                maxAbsSeconds = Math.max(maxAbsSeconds, Math.abs(seconds));
            }
        }

        System.out.println("[FIXTURE] zones=" + zones + " minNonZero=" + minAbsSeconds + "s max="
                + maxAbsSeconds + "s where=" + where);

        assertThat(minAbsSeconds)
                .as("2026 年的三个瞬时上，最小的非零偏移是 60 分钟（历史上更小的 LMT 偏移不在本应用写行的范围内）")
                .isEqualTo(3_600);
        assertThat(maxAbsSeconds)
                .as("最大的偏移是 +14:00（不影响结论，只是给出偏移的量级上界）")
                .isEqualTo(50_400);
        assertThat(minAbsSeconds * 1_000L / TOLERANCE_MILLIS)
                .as("2 秒的容差是最小偏移的 1/1800 —— 所以「列按会话时区写」这类基准错一定会红，"
                        + "而不是被容差吃掉（这正是 ConfigSnapshotServiceTest 那条容差的依据）")
                .isEqualTo(1_800L);
    }
}
