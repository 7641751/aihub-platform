package com.aihub.gateway.meter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
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
        assertThat(files.get(0).getFileName().toString()).isLessThan(files.get(1).getFileName().toString());
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
}
