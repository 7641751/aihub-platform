package com.aihub.admin.time;

import com.aihub.admin.support.TestContainers;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间基准位移的**可重跑夹具**：把「把 {@code expire_at} 从『连接时区墙钟』改读成 UTC」这件事的
 * 位移量按**连接时区**钉下来。
 *
 * <p><b>为什么它必须待在仓库里</b>（2026-09-30 从一次性探针升级而来）：{@code README.md} 与
 * {@code docs/CONVENTIONS.md} §7 引用了三个具体毫秒数，而按 §8 的纪律，**文档里的数字必须点名产生它的
 * 产物**。原先是探针、且躺在 gitignore 覆盖的 {@code .superpowers/} 下 —— 读文档的人拿不到，等于没出处。
 * 这个用例就是那三个数字的产物。
 *
 * <p><b>为什么断言与跑测试的 JVM 时区无关</b>：三条用例都把**连接时区显式钉死**（{@code connectionTimeZone}），
 * 于是写入与读回走的是同一个（被钉死的）偏移，{@code ZoneId.systemDefault()} 不参与。夹具用的瞬时也是固定的
 * （{@code FIXED}），所以结果可复现。
 *
 * <p><b>它证明的是什么</b>：位移量 {@code new − old} 的**符号等于写入连接偏移的符号** —— 东为正
 * （瞬时后移 ⇒ 已过期的 key 仍可用 = fail-open），西为负（瞬时时移 ⇒ key 提前过期 = fail-closed），
 * UTC 为零。因此迁移指令必须**按符号**处理，而不是一句"推后 8 小时"。
 */
class TimeBasisOffsetFixtureTest {

    /** 固定的瞬时：与评审夹具同一个值，不随跑测试的机器变化。 */
    private static final Instant FIXED = Instant.parse("2026-01-01T12:00:00.123456789Z");

    private static Connection open(String connectionParam) throws SQLException {
        String url = TestContainers.MYSQL.getJdbcUrl();
        if (!connectionParam.isEmpty()) {
            url = url + (url.contains("?") ? "&" : "?") + connectionParam;
        }
        return DriverManager.getConnection(url, TestContainers.MYSQL.getUsername(), TestContainers.MYSQL.getPassword());
    }

    /**
     * 用**旧代码的写法**（{@code setTimestamp(Instant)}）往 {@code datetime(3)} 里写一行，再分别用
     * 旧载体（{@code getTimestamp().toInstant()}）与新载体（{@code getObject(LocalDateTime)} 按 UTC 折算）
     * 读回来，返回 {@code new − old} 的毫秒数。
     */
    private static long newMinusOldMillis(String connectionParam) throws SQLException {
        try (Connection c = open(connectionParam)) {
            try (Statement st = c.createStatement()) {
                st.execute("create temporary table fixture_offset (id int primary key, v datetime(3) null)");
                st.execute("insert into fixture_offset (id) values (1)");
            }
            try (PreparedStatement ps = c.prepareStatement("update fixture_offset set v = ? where id = 1")) {
                ps.setTimestamp(1, Timestamp.from(FIXED));
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("select v from fixture_offset where id = 1")) {
                rs.next();
                long oldRead = rs.getTimestamp(1).toInstant().toEpochMilli();
                long newRead = rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC).toEpochMilli();
                return newRead - oldRead;
            }
        }
    }

    @Test
    void aUtcWritingConnectionShowsNoShift() throws Exception {
        assertThat(newMinusOldMillis("connectionTimeZone=UTC"))
                .as("UTC 连接（发布配置：application.yml:8 与 docker-compose.yml:65 都带 serverTimezone=UTC）"
                        + "写下的行，新旧读法逐位相等 —— 所以发布配置下不存在需要迁移的行")
                .isZero();
    }

    @Test
    void anEastwardWritingConnectionMovesTheInstantLaterSoExpiryFailsOpen() throws Exception {
        assertThat(newMinusOldMillis("connectionTimeZone=Asia/Shanghai"))
                .as("+08:00 写下的行改按 UTC 解释后瞬时时移一个偏移（+28800000 ms = 8 小时）："
                        + "已经过期的 key 在该偏移那么长的时间里仍然可用（fail-open，安全洞）")
                .isEqualTo(28_800_000L);
    }

    @Test
    void aWestwardWritingConnectionMovesTheInstantEarlierSoExpiryFailsClosed() throws Exception {
        assertThat(newMinusOldMillis("connectionTimeZone=America/New_York"))
                .as("-05:00（2026-01-01 该时区为 EST）写下的行改按 UTC 解释后瞬时前移一个偏移"
                        + "（-18000000 ms = 5 小时）：key 提前过期（fail-closed，功能回归）——"
                        + "所以迁移指令绝不能写成固定的『推后 8 小时』")
                .isEqualTo(-18_000_000L);
    }

    /**
     * 范围窗口里**与 JVM 时区无关**的那一条性质：**连接时区被钉成 UTC**（发布配置的方言）时，
     * 两种绑定选中**同一批行**。
     *
     * <p><b>为什么只断言 UTC 方言</b>（这是 2026-09-30 实测纠正过的）：{@code connectionTimeZone=Asia/Shanghai}
     * 时两种绑定**并不相等**（本机实测 `LocalDateTime(UTC)=1` 对 `Timestamp=3`）—— 因为本 fixture 混用了
     * `setObject(LocalDateTime@UTC)` 与 `setTimestamp(...)` 两种**写法**，在非 UTC 连接上这两类行本来就落在
     * 不同的墙上时间，两种边界自然选中不同的行。那是 **fixture 的构造**造成的，不是"绑定方式错"。
     * 所以「钉死即相等」是**错的**；能成立、且正是文档要说的那一条是：**只在 UTC 方言下**，
     * 承载方式（`LocalDateTime@UTC` 还是 `Timestamp`）不影响窗口选到哪些行。
     *
     * <p>非 UTC 方言的计数随 JVM 时区与 fixture 变化，因此这里只**打印**、不断言；
     * {@code docs/CONVENTIONS.md} §7 里那组具体计数的 fixture 写在正文里，可照它重建。
     */
    @Test
    void onTheUtcConnectionBothBindingsSelectTheSameRows() throws Exception {
        for (String param : new String[] {
                "connectionTimeZone=UTC", "connectionTimeZone=Asia/Shanghai", "connectionTimeZone=America/New_York"}) {
            try (Connection c = open(param)) {
                try (Statement st = c.createStatement()) {
                    st.execute("create temporary table fixture_range (id int primary key, v datetime(3) null)");
                }
                writeRangeRow(c, 1, ps -> ps.setObject(2, LocalDateTime.ofInstant(FIXED, ZoneOffset.UTC)));
                writeRangeRow(c, 2, ps -> ps.setTimestamp(2, Timestamp.from(FIXED)));
                writeRangeRow(c, 3, ps -> ps.setObject(2, FIXED));
                writeRangeRow(c, 4, ps -> ps.setObject(2, LocalDateTime.ofInstant(FIXED, ZoneId.systemDefault())));

                int byLocalDateTime = countRange(c, true);
                int byTimestamp = countRange(c, false);
                System.out.println("[FIXTURE] " + param + " -> LocalDateTime(UTC)=" + byLocalDateTime
                        + " Timestamp=" + byTimestamp + " of 4");

                if (param.endsWith("=UTC")) {
                    assertThat(byLocalDateTime)
                            .as("连接时区钉成 UTC（发布方言）时，两种承载方式必须选中同一批行 —— 这正是"
                                    + "「生产配置下承载方式不影响结果」这条性质的钉子")
                            .isEqualTo(byTimestamp);
                }
            }
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws Exception;
    }

    private static void writeRangeRow(Connection c, int id, Binder binder) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("insert into fixture_range (id, v) values (?, ?)")) {
            ps.setInt(1, id);
            binder.bind(ps);
            ps.executeUpdate();
        }
    }

    private static int countRange(Connection c, boolean bindAsLocalDateTime) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from fixture_range where v >= ? and v <= ?")) {
            if (bindAsLocalDateTime) {
                ps.setObject(1, LocalDateTime.ofInstant(FIXED.minusSeconds(600), ZoneOffset.UTC));
                ps.setObject(2, LocalDateTime.ofInstant(FIXED.plusSeconds(600), ZoneOffset.UTC));
            }
            else {
                ps.setTimestamp(1, Timestamp.from(FIXED.minusSeconds(600)));
                ps.setTimestamp(2, Timestamp.from(FIXED.plusSeconds(600)));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
