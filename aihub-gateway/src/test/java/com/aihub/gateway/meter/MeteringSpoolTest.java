package com.aihub.gateway.meter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 磁盘 spool 是「RabbitMQ 不可用」时的兜底（设计文档 §9）。它必须
 * ① 一事件一文件（不存在部分写入 / 行歧义）、② 能按时间序重投、③ 有上界（绝不把磁盘写满）。
 */
class MeteringSpoolTest {

    @TempDir
    Path dir;

    @Test
    void appendsAndListsInAgeOrder() throws IOException {
        MeteringSpool spool = new MeteringSpool(dir.resolve("spool"), 100);

        spool.append("first");
        spool.append("second");
        spool.append("third");

        List<Path> files = spool.list();
        assertThat(files).hasSize(3);
        // 按 list() 返回的顺序（也就是重投顺序）把内容读回来，断言的才是「时间序」本身。
        // 旧写法比较两个文件名的字典序 —— 而 list() 本来就排序，那个断言恒真、抓不到顺序回归。
        assertThat(readAll(spool, files)).containsExactly("first", "second", "third");
        assertThat(spool.count()).isEqualTo(3);
    }

    @Test
    void readsBackTheExactPayload() throws IOException {
        MeteringSpool spool = new MeteringSpool(dir.resolve("spool"), 100);
        String payload = "r-1|7|42|99|deepseek-chat|12|34|46|1200|250|SUCCESS||1800000000123";

        spool.append(payload);

        assertThat(spool.read(spool.list().get(0))).isEqualTo(payload);
    }

    @Test
    void deletesDeliveredFiles() throws IOException {
        MeteringSpool spool = new MeteringSpool(dir.resolve("spool"), 100);
        spool.append("only");

        spool.delete(spool.list().get(0));

        assertThat(spool.count()).isZero();
        assertThat(spool.list()).isEmpty();
    }

    @Test
    void throwsSpoolFullAtTheLimit() throws IOException {
        MeteringSpool spool = new MeteringSpool(dir.resolve("spool"), 2);
        spool.append("a");
        spool.append("b");

        assertThatThrownBy(() -> spool.append("c"))
                .isInstanceOf(MeteringSpool.SpoolFullException.class);

        assertThat(spool.count()).isEqualTo(2);
    }

    @Test
    void listReturnsEmptyWhenTheDirectoryDoesNotExist() throws IOException {
        MeteringSpool spool = new MeteringSpool(dir.resolve("missing"), 100);

        assertThat(spool.list()).isEmpty();
        assertThat(spool.count()).isZero();
    }

    /**
     * 上界判定用的是内存计数，它的初值必须来自**启动时对目录的扫描**：否则网关重启后
     * 能往同一个 spool 目录里再塞一整个 {@code maxFiles}（上界形同虚设）。
     */
    @Test
    void countsFilesThatWereAlreadyOnDiskAtStartup() throws IOException {
        Path spoolDir = dir.resolve("spool");
        new MeteringSpool(spoolDir, 1).append("existing");

        MeteringSpool afterRestart = new MeteringSpool(spoolDir, 1);

        assertThat(afterRestart.count()).isEqualTo(1);
        assertThatThrownBy(() -> afterRestart.append("new"))
                .isInstanceOf(MeteringSpool.SpoolFullException.class);
    }

    /**
     * 内存计数不能在 append / delete 路径上漂移：用一个**新实例**（重新扫目录）作为磁盘真值，
     * 与老实例的内存计数对账。计数一旦偏小，上界就会失效。
     */
    @Test
    void deletesKeepTheTrackedCountInSyncWithTheDirectory() throws IOException {
        Path spoolDir = dir.resolve("spool");
        MeteringSpool spool = new MeteringSpool(spoolDir, 10);
        spool.append("first");
        spool.append("second");
        spool.append("third");

        spool.delete(spool.list().get(0));

        assertThat(spool.count()).isEqualTo(2);
        assertThat(new MeteringSpool(spoolDir, 10).count()).isEqualTo(2);
    }

    private static List<String> readAll(MeteringSpool spool, List<Path> files) throws IOException {
        List<String> payloads = new ArrayList<>(files.size());
        for (Path file : files) {
            payloads.add(spool.read(file));
        }
        return payloads;
    }
}
