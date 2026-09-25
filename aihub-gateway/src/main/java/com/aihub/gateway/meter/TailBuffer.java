package com.aihub.gateway.meter;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * 响应字节的**尾部**滑窗缓冲。
 *
 * <p>为什么是尾部：流式响应里 {@code usage} 出现在最后一帧（`stream_options.include_usage` 的约定），
 * 「超限就停止累积」会把最需要的那一段永远丢掉；「保留头部」同理。因此超限时丢头保尾。
 *
 * <p>上限的意义：客户端断连的估算、日志、以及恶意/异常的超长响应都不允许把网关内存吃光。
 * 丢弃发生时 {@link #truncated()} 变 true（调用方可据此打计数器）。
 *
 * <p>线程模型：{@code onChunk} 在 Netty event loop 上调用，{@code toByteArray} 在收尾线程上调用，
 * 因此方法都是 {@code synchronized}（每次只复制一个小数组，不存在长时间持锁）。
 */
public final class TailBuffer {

    private final int maxBytes;
    private final Deque<byte[]> chunks = new ArrayDeque<>();
    private int size;
    private boolean dropped;

    public TailBuffer(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes 必须为正数");
        }
        this.maxBytes = maxBytes;
    }

    public synchronized void append(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        chunks.addLast(chunk);
        size += chunk.length;
        while (size > maxBytes && !chunks.isEmpty()) {
            byte[] head = chunks.peekFirst();
            int overflow = size - maxBytes;
            if (head.length <= overflow) {
                chunks.pollFirst();
                size -= head.length;
            } else {
                // 只丢前 overflow 字节，剩下的部分放回队首。
                chunks.pollFirst();
                chunks.addFirst(Arrays.copyOfRange(head, overflow, head.length));
                size -= overflow;
            }
            dropped = true;
        }
    }

    public synchronized byte[] toByteArray() {
        byte[] out = new byte[size];
        int position = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, position, chunk.length);
            position += chunk.length;
        }
        return out;
    }

    public synchronized int size() {
        return size;
    }

    /** 是否因为超过上限丢过字节（丢过就说明捕获不完整）。 */
    public synchronized boolean truncated() {
        return dropped;
    }
}
