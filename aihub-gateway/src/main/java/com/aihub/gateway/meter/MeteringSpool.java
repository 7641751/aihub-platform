package com.aihub.gateway.meter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * 判定用的是**内存计数**（构造时扫一次目录取初值，之后写入成功 +1、删除成功 −1），
 * 因此 {@link #append} 是 O(1)：broker 长时间不可用、spool 涨到上界（默认 50000）时，
 * 每次 append 都扫一遍目录会退化成 O(n²)，反过来把内存队列撑成计数丢弃，spool 就白做了。
 *
 * <p><b>单写者假设</b>：上面的内存计数只有在「只有一个进程写这个 spool 目录」时才与磁盘一致，
 * 这与原设计一致（网关单实例 + 独立卷 + 文件名 = 毫秒时间戳 + 进程内自增序号）。别的进程往里
 * 塞文件不会被计入，上界因此会偏松 —— 多实例共享同一 spool 目录是未支持的场景。
 * {@link #count()} 返回的就是这个内存计数（O(1)，与上界判定同源）。
 */
public final class MeteringSpool {

    private static final String SUFFIX = ".evt";

    private static final Logger log = LoggerFactory.getLogger(MeteringSpool.class);

    /** 内存计数「还没有可信初值」的哨兵：构造时的目录扫描失败时会停在这个值上。 */
    private static final long UNINITIALIZED = -1L;

    /** spool 已达上界：调用方应丢弃该事件并计数（不要无界增长）。 */
    public static final class SpoolFullException extends RuntimeException {
        public SpoolFullException(String message) {
            super(message);
        }
    }

    private final Path dir;
    private final int maxFiles;
    private final AtomicLong sequence = new AtomicLong();
    /** 目录里 {@code .evt} 文件数的内存计数：见类注释的「上界」与「单写者假设」。 */
    private final AtomicLong entryCount = new AtomicLong(UNINITIALIZED);

    public MeteringSpool(Path dir, int maxFiles) {
        this.dir = dir;
        this.maxFiles = maxFiles;
        try {
            entryCount.set(scanEntryCount());
        } catch (IOException e) {
            // 目录存在但读不了：这里**不抛**（计量是派生数据，不该让网关起不来），
            // 但也不能当成 0（那会让上界直接失效）。保持「未初始化」，首次读写时再试一次。
            log.warn("spool 目录初始扫描失败，将在首次写入时重试: {} - {}", dir, e.toString());
        }
    }

    public void append(String payload) throws IOException {
        Files.createDirectories(dir);
        ensureInitialized();
        if (entryCount.get() >= maxFiles) {
            throw new SpoolFullException("spool 已达上界 " + maxFiles + " 个文件：" + dir);
        }
        String name = String.format(Locale.ROOT, "%013d-%06d%s",
                System.currentTimeMillis(), sequence.incrementAndGet(), SUFFIX);
        // CREATE_NEW：同名（同毫秒 + 同序号）时抛 FileAlreadyExistsException，绝不覆盖已有事件。
        Files.writeString(dir.resolve(name), payload, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        // 只有真的写成功才 +1：上面任何一步抛异常都不会让计数漂移。
        entryCount.incrementAndGet();
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
        ensureInitialized();
        boolean tracked = isTrackedEntry(file);
        if (Files.deleteIfExists(file) && tracked) {
            entryCount.decrementAndGet();
        }
    }

    /** spool 里的文件数（内存计数，O(1)；与上界判定同源）。 */
    public long count() throws IOException {
        ensureInitialized();
        return entryCount.get();
    }

    /** 惰性初始化：只有构造时那次扫描失败（计数仍是哨兵）才会真的扫目录。 */
    private void ensureInitialized() throws IOException {
        if (entryCount.get() == UNINITIALIZED) {
            entryCount.set(scanEntryCount());
        }
    }

    private long scanEntryCount() throws IOException {
        try (Stream<Path> files = listFiles()) {
            return files.count();
        }
    }

    /** 只有 {@link #list()} 会给出的条目才计入上界；删别的路径不能把计数算松。 */
    private boolean isTrackedEntry(Path file) {
        Path name = file.getFileName();
        return name != null && name.toString().endsWith(SUFFIX) && dir.equals(file.getParent());
    }

    private Stream<Path> listFiles() throws IOException {
        if (!Files.isDirectory(dir)) {
            return Stream.empty();
        }
        return Files.list(dir).filter(path -> path.getFileName().toString().endsWith(SUFFIX));
    }
}
