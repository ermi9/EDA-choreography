package com.eda.choreography.infra.participant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** STUB, test only: a participant whose answers the test decides and whose calls it keeps. */
public final class StubParticipant implements AutoCloseable {

    public static final String SPEC = """
            {"openapi": "3.0.3", "paths": {
              "/choreography/steps/{step}": {"post": {}}, "/choreography/undo/{step}": {"post": {}}}}""";

    public record Call(boolean undo, String step, String key, String body) {}

    public record Answer(int status, String body, long afterMillis) {
        public static Answer of(int status, String body) {
            return new Answer(status, body, 0);
        }
    }

    public final List<Call> calls = new CopyOnWriteArrayList<>();
    public volatile Function<Call, Answer> answers = call -> Answer.of(200, "{}");
    private final HttpServer server;

    public StubParticipant() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/choreography/", this::answer);
        server.createContext("/openapi.json", exchange -> send(exchange, 200, SPEC));
        server.start();
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public List<Call> undos() {
        return calls.stream().filter(Call::undo).toList();
    }

    public List<Call> steps() {
        return calls.stream().filter(call -> !call.undo()).toList();
    }

    private void answer(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        var call = new Call(path.startsWith("/choreography/undo/"), path.substring(path.lastIndexOf('/') + 1),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        calls.add(call);
        var answer = answers.apply(call);
        try {
            Thread.sleep(answer.afterMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        send(exchange, answer.status(), answer.body());
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
