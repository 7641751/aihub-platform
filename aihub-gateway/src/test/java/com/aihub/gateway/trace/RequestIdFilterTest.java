package com.aihub.gateway.trace;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code x-request-id} 是 M2 计量幂等键（{@code request_id}）的来源，因此它必须
 * ① 每次请求都新生成、② 由我们生成而不是客户端、③ 与计量事件里的值严格同一个。
 * 用 {@code MockServerWebExchange} 做单元测试：没有 HTTP 端口、没有 Redis、没有 Docker。
 */
class RequestIdFilterTest {

    private static final String UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final RequestIdFilter filter = new RequestIdFilter();

    private MockServerWebExchange apply(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, e -> Mono.empty()).block();
        return exchange;
    }

    @Test
    void generatesRequestIdForV1Paths() {
        MockServerWebExchange exchange = apply(MockServerHttpRequest.post("/v1/chat/completions").build());

        assertThat(exchange.getResponse().getHeaders().getFirst(RequestIdFilter.HEADER)).matches(UUID_REGEX);
        assertThat(RequestIdFilter.requestId(exchange)).matches(UUID_REGEX);
    }

    @Test
    void generatesADifferentIdPerRequest() {
        String first = RequestIdFilter.requestId(apply(MockServerHttpRequest.post("/v1/chat/completions").build()));
        String second = RequestIdFilter.requestId(apply(MockServerHttpRequest.post("/v1/chat/completions").build()));

        assertThat(first).isNotEqualTo(second);
    }

    /**
     * 客户端自带的 {@code x-request-id} **不得**被回显：它是我们的计量幂等键，
     * 让调用方指定等于允许外部制造重复（甚至冲突）的幂等键。
     */
    @Test
    void doesNotEchoAClientSuppliedRequestId() {
        MockServerWebExchange exchange = apply(MockServerHttpRequest.post("/v1/chat/completions")
                .header(RequestIdFilter.HEADER, "client-supplied-id")
                .build());

        String generated = exchange.getResponse().getHeaders().getFirst(RequestIdFilter.HEADER);

        assertThat(generated).matches(UUID_REGEX).isNotEqualTo("client-supplied-id");
    }

    @Test
    void doesNotAddTheHeaderToNonV1Paths() {
        MockServerWebExchange exchange = apply(MockServerHttpRequest.get("/healthz").build());

        assertThat(exchange.getResponse().getHeaders().getFirst(RequestIdFilter.HEADER)).isNull();
        assertThat(RequestIdFilter.requestId(exchange)).isNull();
    }

    @Test
    void ensureReturnsTheSameValueThatIsAlreadyOnTheResponse() {
        MockServerWebExchange exchange = apply(MockServerHttpRequest.post("/v1/chat/completions").build());

        assertThat(RequestIdFilter.ensure(exchange))
                .isEqualTo(exchange.getResponse().getHeaders().getFirst(RequestIdFilter.HEADER));
    }

    /** 过滤器没跑过（例如直接构造 exchange）时 {@code requestId} 是 null，调用方必须用 {@code ensure}。 */
    @Test
    void requestIdReturnsNullBeforeTheFilterRan() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions").build());

        assertThat(RequestIdFilter.requestId(exchange)).isNull();
        assertThat(RequestIdFilter.ensure(exchange)).matches(UUID_REGEX);
        assertThat(exchange.getResponse().getHeaders().getFirst(RequestIdFilter.HEADER))
                .isEqualTo(RequestIdFilter.requestId(exchange));
    }
}
