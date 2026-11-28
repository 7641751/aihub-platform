package com.aihub.gateway.relay;

import com.aihub.common.config.ConfigSnapshot;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.gateway.config.ConfigClient;
import com.aihub.gateway.meter.MeteringProperties;
import com.aihub.gateway.meter.MeteringPublisher;
import com.aihub.gateway.quota.QuotaCorrector;
import com.aihub.gateway.route.ChannelCircuitBreaker;
import com.aihub.gateway.route.RouteResolver;
import com.aihub.gateway.upstream.UpstreamClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ChatRelaySchedulingTest {

    private static final String BODY = "{\"model\":\"missing\",\"stream\":false}";

    private final Scheduler eventLoop = Schedulers.newSingle("relay-test-loop");
    private final CompletableFuture<Void> entered = new CompletableFuture<>();
    private final CompletableFuture<Void> release = new CompletableFuture<>();
    private final CompletableFuture<Void> lookupReleased = new CompletableFuture<>();
    private final CompletableFuture<MeteringEvent> corrected = new CompletableFuture<>();
    private final UpstreamClientFactory clients = mock(UpstreamClientFactory.class);
    private final MeteringPublisher meter = mock(MeteringPublisher.class);
    private final QuotaCorrector quota = mock(QuotaCorrector.class);
    private ChatRelayController controller;

    @BeforeEach
    void blockCandidateLookup() {
        var config = mock(ConfigClient.class);
        when(config.current()).thenReturn(ConfigSnapshot.empty());
        var routes = mock(RouteResolver.class);
        when(routes.candidates("missing")).thenAnswer(invocation -> {
            entered.complete(null);
            release.join();
            lookupReleased.complete(null);
            return List.of();
        });
        doAnswer(invocation -> {
            corrected.complete(invocation.getArgument(0));
            return null;
        }).when(quota).correct(any(MeteringEvent.class));
        var properties = new MeteringProperties(false, 65536, 100, 10,
                30_000L, 5_000L, 5_000L, "unused-spool-dir");
        controller = new ChatRelayController(clients, config, routes, mock(ChannelCircuitBreaker.class),
                mock(ChannelKeyDecryptor.class), meter, properties, quota);
    }

    @AfterEach
    void closeLoop() {
        release.complete(null);
        eventLoop.dispose();
    }

    @Test
    void stalledLookupLeavesTheEventLoopFreeAndStillReturnsModelNotFound() throws Exception {
        var exchange = newExchange();
        var response = request(exchange);
        entered.get(5, TimeUnit.SECONDS);
        try {
            assertThat(Mono.just("available").subscribeOn(eventLoop)
                    .block(Duration.ofSeconds(5))).isEqualTo("available");
        } finally {
            release.complete(null);
        }
        response.get(5, TimeUnit.SECONDS);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5)))
                .contains("\"code\":\"model_not_found\"");
        var event = corrected.get(5, TimeUnit.SECONDS);
        assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_ERROR);
        assertThat(event.errorCode()).isEqualTo(MeteringEvent.ERROR_GATEWAY);
        verify(meter).publish(same(event));
        verify(quota).correct(same(event));
        verifyNoMoreInteractions(meter, quota);
        verifyNoInteractions(clients);
    }

    @Test
    void cancellationDuringLookupFinalizesMeteringAndQuotaOnce() throws Exception {
        var exchange = newExchange();
        var httpResponse = spy(exchange.getResponse());
        var response = request(exchange.mutate().response(httpResponse).build());
        entered.get(5, TimeUnit.SECONDS);
        try {
            assertThat(response.cancel(true)).isTrue();
            var event = corrected.get(5, TimeUnit.SECONDS);
            assertThat(event.status()).isEqualTo(MeteringEvent.STATUS_CANCELLED);
            assertThat(event.errorCode()).isEqualTo(MeteringEvent.ERROR_CLIENT_DISCONNECTED);
            release.complete(null);
            lookupReleased.get(5, TimeUnit.SECONDS);
            verify(httpResponse, after(250).never()).setStatusCode(any());
            verify(meter).publish(same(event));
            verify(quota).correct(same(event));
            verifyNoMoreInteractions(meter, quota);
            verifyNoInteractions(clients);
        } finally {
            release.complete(null);
        }
    }

    private CompletableFuture<Void> request(ServerWebExchange exchange) {
        return Mono.defer(() -> controller.chatCompletions(BODY, exchange))
                .subscribeOn(eventLoop)
                .toFuture();
    }

    private static MockServerWebExchange newExchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions").body(BODY));
    }
}
