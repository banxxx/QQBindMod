package com.poso.qqbind.official;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.poso.qqbind.official.OfficialCenterTests.*;

/** Simulated connections, deterministic clock, fresh state and no game or database. */
public final class OfficialLoginCodeTests {
    static final UUID PLAYER = UUID.fromString("01111111-1111-4111-8111-111111111111");
    static final String USER = "SYNTHETIC_USER_A", GAME = "Player_A", KEY = "player_a";

    private static final class Harness implements AutoCloseable {
        final OfficialTokenTests.TestClock clock = new OfficialTokenTests.TestClock();
        final OfficialTokenTests.Center center = new OfficialTokenTests.Center();
        final BlockingQueue<OfficialLoginCodes.Notice> notices = new LinkedBlockingQueue<>();
        final AtomicInteger counter = new AtomicInteger(200000);
        final OfficialModState state;
        final OfficialTokenService tokens;
        final OfficialLoginCodes codes;

        Harness() throws Exception { this(null, null); }
        Harness(Supplier<String> codeSource, OfficialTokenService.CenterInspector inspector) throws Exception {
            Path root = Files.createTempDirectory("official-login-synthetic-");
            state = new OfficialModState(root, S, true);
            tokens = new OfficialTokenService(S, state, inspector == null ? center : inspector, clock,
                    codeSource == null ? () -> Integer.toString(counter.incrementAndGet()) : codeSource);
            codes = new OfficialLoginCodes(tokens, ignored -> true, tokens::issue, notices::add);
        }

        OfficialLoginCodes.Login login() { return codes.login(PLAYER, GAME); }

        OfficialLoginCodes.Notice notice() throws InterruptedException {
            OfficialLoginCodes.Notice result = notices.poll(5, TimeUnit.SECONDS);
            if (result == null) throw new AssertionError("Expected a synthetic login completion");
            return result;
        }

        OfficialTokenService.Issued request(OfficialLoginCodes.Login login) throws Exception {
            check(codes.request(login));
            var notice = notice();
            var issued = codes.current(login);
            check(issued != null && notice.login() == login && issued.tokenGeneration().equals(notice.tokenGeneration()));
            return issued;
        }

        public void close() { codes.close(); state.close(); }
    }

    static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError("Test latch was interrupted"); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No external inputs for the login code tests");
        passed = 0;
        test("expired_generation_is_invisible_to_game_and_http", () -> {
            try (Harness h = new Harness()) {
                var login = h.login();
                var code = h.request(login);
                check(h.tokens.validate(code.tokenCode()).get("token_generation").getAsString().equals(code.tokenGeneration()));
                h.clock.seconds.addAndGet(300);
                check(h.codes.current(login) == null);
                expect("TOKEN_INVALID", () -> h.tokens.validate(code.tokenCode()));
            }
        });
        test("reconnect_does_not_reuse_still_valid_six_digit_value", () -> {
            var draws = new AtomicInteger();
            try (Harness h = new Harness(() -> draws.incrementAndGet() <= 2 ? "123456" : "654321", null)) {
                var first = h.login();
                var code = h.request(first);
                first.leave();
                expect("TOKEN_INVALID", () -> h.tokens.validate(code.tokenCode()));
                var second = h.login();
                var next = h.request(second);
                check(draws.get() == 3 && !next.tokenCode().equals(code.tokenCode()) && !next.tokenGeneration().equals(code.tokenGeneration()));
                first.leave();
                check(h.codes.current(first) == null && h.codes.current(second).equals(next));
            }
        });
        test("a_live_connection_keeps_its_generation_when_another_claims_the_same_game_key", () -> {
            try (Harness h = new Harness()) {
                var first = h.login();
                var code = h.request(first);
                var other = h.codes.login(UUID.fromString("02222222-2222-4222-8222-222222222222"), "PLAYER_A");
                check(h.codes.request(other) && h.notice().tokenGeneration() == null);
                check(h.codes.current(other) == null && code.equals(h.codes.current(first)));
            }
        });
        test("logout_keeps_durable_reservation_and_next_login_waits_for_center", () -> {
            try (Harness h = new Harness()) {
                var first = h.login();
                var code = h.request(first);
                h.center.generation = code.tokenGeneration();
                h.tokens.reserve(OP, USER, KEY, code.tokenGeneration(), code.tokenCode());
                first.leave();
                h.center.offline = true;
                var next = h.login();
                check(h.codes.current(first) == null && h.codes.request(next));
                check(h.notice().awaitingConfirmation());
                check(h.codes.current(next) == null);
                check(h.tokens.operation(OP).getAsJsonObject("reservation").get("state").getAsString().equals("RESERVED"));
            }
        });
        test("logout_while_reserve_waits_for_center_prevents_new_reservation", () -> {
            CountDownLatch centerRead = new CountDownLatch(1), release = new CountDownLatch(1);
            var center = new OfficialTokenTests.Center();
            try (Harness h = new Harness(null, id -> {
                centerRead.countDown(); await(release); return center.find(id);
            })) {
                ExecutorService io = Executors.newSingleThreadExecutor();
                try {
                    var login = h.login();
                    var code = h.request(login);
                    center.generation = code.tokenGeneration();
                    Future<?> reserving = io.submit(() -> {
                        expect("TOKEN_INVALID", () -> h.tokens.reserve(OP, USER, KEY, code.tokenGeneration(), code.tokenCode()));
                        return null;
                    });
                    await(centerRead);
                    login.leave();
                    release.countDown();
                    reserving.get(5, TimeUnit.SECONDS);
                    check(h.state.snapshot().getAsJsonObject("reservations").size() == 0);
                } finally {
                    release.countDown(); io.shutdownNow();
                }
            }
        });
        test("queued_requests_are_bounded_and_logout_invalidates_even_when_queue_full", () -> {
            CountDownLatch preparing = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
            AtomicInteger issues = new AtomicInteger();
            try (Harness h = new Harness(); var codes = new OfficialLoginCodes(h.tokens, game -> true, login -> {
                issues.incrementAndGet();
                var issued = h.tokens.issue(login);
                preparing.countDown(); await(release); finished.countDown();
                return issued;
            }, notice -> { throw new AssertionError("Must not notify for a stopped or departed login"); })) {
                try {
                    var login = codes.login(PLAYER, GAME);
                    check(codes.request(login)); await(preparing);
                    var visible = codes.current(login);
                    check(visible != null && visible.expiresAt().isAfter(Instant.ofEpochSecond(h.clock.seconds.get())));
                    for (int i = 0; i < 200; i++) check(codes.request(login));
                    for (int i = 0; i < 128; i++) check(codes.request(codes.login(new UUID(1, i + 1), "Synthetic_" + i)));
                    check(!codes.request(codes.login(new UUID(2, 1), "Overflow")));
                    login.leave();
                    check(codes.current(login) == null);
                    expect("TOKEN_INVALID", () -> h.tokens.validate(visible.tokenCode()));
                    codes.close();
                    release.countDown(); await(finished);
                    check(issues.get() == 1 && !codes.request(login));
                } finally { release.countDown(); }
            }
        });
        test("game_current_does_not_wait_for_a_blocked_token_writer", () -> {
            CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger draws = new AtomicInteger();
            try (Harness h = new Harness(() -> {
                if (draws.incrementAndGet() == 1) return "123456";
                writing.countDown(); await(release); return "654321";
            }, null)) {
                ExecutorService observer = Executors.newSingleThreadExecutor();
                try {
                    var first = h.login();
                    var code = h.request(first);
                    check(h.codes.request(h.codes.login(new UUID(2, 2), "Player_B")));
                    await(writing);
                    check(observer.submit(() -> h.codes.current(first)).get(2, TimeUnit.SECONDS).equals(code));
                    release.countDown(); h.notice();
                } finally { release.countDown(); observer.shutdownNow(); }
            }
        });
        System.out.println("Official login codes: " + passed + " tests passed (synthetic clocks, connections and state)");
    }
}
