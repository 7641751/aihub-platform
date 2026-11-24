package com.aihub.admin.kb;

import com.aihub.service.kb.KbFileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KbFileStoreTest {

    @Test
    void interruptedReadDiscardsOnlyItsOwnPartialFile(@TempDir Path root) throws IOException {
        var store = new KbFileStore(root.toString());
        Path dir = Files.createDirectories(store.dirFor(7L));
        Path other = Files.writeString(dir.resolve(".upload-other.part"), "keep");
        var failure = new UncheckedIOException(new IOException("stream interrupted"));
        var input = new ByteArrayInputStream(new byte[]{1}) {
            @Override
            public int read(byte[] bytes, int offset, int length) {
                if (available() == 0) {
                    throw failure;
                }
                return super.read(bytes, offset, length);
            }
        };

        assertThatThrownBy(() -> store.stage(7L, input)).isSameAs(failure);
        try (var files = Files.list(dir)) {
            assertThat(files.toList()).containsExactly(other);
        }
        assertThat(Files.readString(other)).isEqualTo("keep");
    }
}
