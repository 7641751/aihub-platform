package com.aihub.gateway.relay;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.upstream.UpstreamProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelsControllerSchedulingTest {

    @Test
    void readsTheSnapshotOnABlockingWorker() {
        var config = mock(ConfigClient.class);
        when(config.current()).thenAnswer(invocation -> {
            assertThat(Schedulers.isInNonBlockingThread())
                    .as("读取快照的线程可以执行阻塞 I/O")
                    .isFalse();
            return ConfigSnapshot.empty();
        });
        var controller = new ModelsController(
                new UpstreamProperties("http://127.0.0.1:1", null, "legacy"), config);
        var eventLoop = Schedulers.newSingle("models-test-loop");
        try {
            var response = Mono.defer(() -> {
                        var models = controller.listModels();
                        verifyNoInteractions(config);
                        return models;
                    })
                    .subscribeOn(eventLoop)
                    .block(Duration.ofSeconds(5));
            assertThat(response).containsEntry("object", "list");
            assertThat(response.get("data")).isEqualTo(List.of(
                    Map.of("id", "legacy", "object", "model", "owned_by", "aihub")));
            verify(config).current();
        } finally {
            eventLoop.dispose();
        }
    }
}
