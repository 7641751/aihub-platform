package com.aihub.gateway.meter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 尾部滑窗：usage 帧在流的**末尾**，所以超过上限时必须丢**头部**字节、保留最近的字节。
 * 若实现成「超过上限就停止累积」，长回答的 usage 帧会被永远丢掉 —— 那正是本类要防的错。
 */
class TailBufferTest {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    @Test
    void keepsEverythingBelowTheCap() {
        TailBuffer buffer = new TailBuffer(1024);

        buffer.append(bytes("abc"));
        buffer.append(bytes("def"));

        assertThat(text(buffer.toByteArray())).isEqualTo("abcdef");
        assertThat(buffer.size()).isEqualTo(6);
        assertThat(buffer.truncated()).isFalse();
    }

    @Test
    void dropsTheHeadWhenOverTheCap() {
        TailBuffer buffer = new TailBuffer(4);

        buffer.append(bytes("abcdef"));

        assertThat(text(buffer.toByteArray())).isEqualTo("cdef");
        assertThat(buffer.size()).isEqualTo(4);
        assertThat(buffer.truncated()).isTrue();
    }

    @Test
    void keepsTheMostRecentBytesExactly() {
        TailBuffer buffer = new TailBuffer(5);

        buffer.append(bytes("ab"));
        buffer.append(bytes("cd"));
        buffer.append(bytes("ef"));

        assertThat(text(buffer.toByteArray())).isEqualTo("bcdef");
        assertThat(buffer.truncated()).isTrue();
    }

    @Test
    void reportsTruncatedOnlyAfterDropping() {
        TailBuffer buffer = new TailBuffer(2);

        buffer.append(bytes("ab"));
        assertThat(buffer.truncated()).isFalse();

        buffer.append(bytes("c"));
        assertThat(buffer.truncated()).isTrue();
    }

    @Test
    void emptyBufferReturnsEmptyArray() {
        TailBuffer buffer = new TailBuffer(8);

        assertThat(buffer.toByteArray()).isEmpty();
        assertThat(buffer.size()).isZero();
        assertThat(buffer.truncated()).isFalse();
    }
}
