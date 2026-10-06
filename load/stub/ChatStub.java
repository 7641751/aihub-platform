import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M6 T1: an OpenAI-compatible /v1/chat/completions stub, used as the load-test upstream.
 *
 * Why a stub as the main upstream (M6 decision): with fixed first-token and per-token
 * delays, QPS / P99 / TTFT and the rate-limit (on/off) and cache (cold/warm) comparisons
 * are repeatable and attributable to OUR gateway rather than to upstream jitter.
 *
 * Run:  java ChatStub.java <port>            (JDK single-file mode; no build step)
 * Env:  STUB_FIRST_TOKEN_DELAY_MS (default 120)
 *       STUB_TOKEN_DELAY_MS       (default 5)
 *       STUB_TOKENS               (default 64)
 *       STUB_ABORT_AFTER_TOKENS   (default -1 = never abort; else cut the stream mid-flight,
 *                                  which is the machine signature of a broken connection)
 *
 * NOTE: ASCII only. The single-file launcher decodes the source with the platform default
 * encoding, so non-ASCII comments would break compilation on a GBK console.
 */
public final class ChatStub {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final String MODEL = "stub-chat";

    private static int firstTokenDelayMs = 120;
    private static int tokenDelayMs = 5;
    private static int tokens = 64;
    private static int abortAfterTokens = -1;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8089;
        firstTokenDelayMs = env("STUB_FIRST_TOKEN_DELAY_MS", firstTokenDelayMs);
        tokenDelayMs = env("STUB_TOKEN_DELAY_MS", tokenDelayMs);
        tokens = env("STUB_TOKENS", tokens);
        abortAfterTokens = env("STUB_ABORT_AFTER_TOKENS", abortAfterTokens);

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/healthz", ex -> fixed(ex, 200, "application/json", "{\"status\":\"UP\"}"));
        server.createContext("/v1/chat/completions", ChatStub::chat);
        server.setExecutor(Executors.newFixedThreadPool(64));
        server.start();
        System.out.println("chat stub listening on :" + port
                + " tokens=" + tokens
                + " firstTokenDelayMs=" + firstTokenDelayMs
                + " tokenDelayMs=" + tokenDelayMs
                + " abortAfterTokens=" + abortAfterTokens);
    }

    private static void chat(HttpExchange ex) throws IOException {
        String request = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (request.contains("\"stream\":true")) {
            stream(ex);
        } else {
            nonStream(ex);
        }
    }

    private static void nonStream(HttpExchange ex) throws IOException {
        sleep(firstTokenDelayMs);
        List<String> parts = words();
        String body = "{\"id\":\"chatcmpl-stub-" + SEQ.incrementAndGet() + "\","
                + "\"object\":\"chat.completion\",\"model\":\"" + MODEL + "\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                + String.join(" ", parts) + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":" + parts.size()
                + ",\"total_tokens\":" + (9 + parts.size()) + "}}";
        fixed(ex, 200, "application/json", body);
    }

    private static void stream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0); // 0 => chunked, so every flush is a real SSE chunk
        OutputStream out = ex.getResponseBody();
        try {
            sleep(firstTokenDelayMs);
            int sent = 0;
            for (String part : words()) {
                if (abortAfterTokens >= 0 && sent >= abortAfterTokens) {
                    // Cut the connection mid-flight: no [DONE], no trailing newline contract.
                    out.flush();
                    return;
                }
                chunk(out, part);
                sent++;
                sleep(tokenDelayMs);
            }
            write(out, "data: [DONE]\n\n");
            out.flush();
        } catch (IOException clientWentAway) {
            // The load generator aborted the request; nothing to do but let the exchange close.
        } finally {
            ex.close();
        }
    }

    private static void chunk(OutputStream out, String part) throws IOException {
        write(out, "data: {\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion.chunk\","
                + "\"model\":\"" + MODEL + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                + part + "\"}}]}\n\n");
        out.flush();
    }

    private static List<String> words() {
        List<String> parts = new ArrayList<>(tokens);
        for (int i = 0; i < tokens; i++) {
            parts.add("tok" + i);
        }
        return parts;
    }

    private static void fixed(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static int env(String name, int fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(raw.trim());
    }

    private static void sleep(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
