package com.aihub.gateway.meter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * RabbitMQ 不可用时的本地磁盘队列（设计文档 §9：「计量事件落本地磁盘队列 + 定时补偿重投」）。
 *
 * <p><b>一事件一文件</b>，文件名前缀是毫秒时间戳，因此 {@link #list()} 的字典序就是投递顺序；
 * 不存在「写到一半被读走」或「多条事件挤在一行」的歧义，投递成功后直接删文件即可。
 * 内容就是 {@code MeteringEventCodec} 的 payload（自带转义，不含换行）。
 *
 * <p><b>上界</b>：目录里的文件数达到 {@code maxFiles} 后拒绝再写并抛
 * {@link SpoolFullException}，由调用方丢弃 + 计数 + 打 ERROR —— 磁盘写满比丢几条用量严重得多。
 */
public final class MeteringSpool {

    private static final String SUFFIX = ".evt";

    /** spool 已达上界：调用方应丢弃该事件并计数（不要无界增长）。 */
    public static final class SpoolFullException extends RuntimeException {
        public SpoolFullException(String message) {
            super(message);
        }
    }

    private final Path dir;
    private final int maxFiles;
    private final AtomicLong sequence = new AtomicLong();

    public MeteringSpool(Path dir, int maxFiles) {
        this.dir = dir;
        this.maxFiles = maxFiles;
    }

    public void append(String payload) throws IOException {
        Files.createDirectories(dir);
        if (count() >= maxFiles) {
            throw new SpoolFullException("spool 已达上界 " + maxFiles + " 个文件：" + dir);
        }
        String name = String.format(Locale.ROOT, "%013d-%06d%s",
                System.currentTimeMillis(), sequence.incrementAndGet(), SUFFIX);
        Files.writeString(dir.resolve(name), payload, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public List<Path> list() throws IOException {
        try (Stream<Path> files = listFiles()) {
            return files.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }

    public String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    public long count() throws IOException {
        try (Stream<Path> files = listFiles()) {
            return files.count();
        }
    }

    private Stream<Path> listFiles() throws IOException {
        if (!Files.isDirectory(dir)) {
            return Stream.empty();
        }
        return Files.list(dir).filter(path -> path.getFileName().toString().endsWith(SUFFIX));
    }
}
