package com.poso.qqbind.official;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Synthetic only: standalone main, explicit temp directories and fake center. */
public final class OfficialTokenTests {
    private static final OfficialScope S = new OfficialScope("11111111-1111-4111-8111-111111111111", "SYNTHETIC_GROUP_A", "33333333-3333-4333-8333-333333333333");
    private static final String OP = "66666666-6666-4666-8666-666666666666";
    private static final String OP_B = "77777777-7777-4777-8777-777777777777";
    private static final String USER = "SYNTHETIC_USER_A";
    private static int passed;
    private static JsonObject fixture;
    interface Checked { void run() throws Exception; }
    static void check(boolean value) { if (!value) throw new AssertionError("Synthetic assertion failed"); }
    static void expect(String code, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (OfficialException e) { check(e.code().equals(code)); }
    }
    static void test(String name, Checked action) throws Exception {
        action.run(); passed++; System.out.println("PASS " + name);
    }
    static Path temp() throws IOException { return Files.createTempDirectory("official-synthetic-"); }
    static class TestClock extends Clock {
        final AtomicLong seconds = new AtomicLong(Instant.parse("2026-11-18T00:00:00Z").getEpochSecond());
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochSecond(seconds.get()); }
    }
    static class Center implements OfficialTokenService.CenterInspector {
        String generation, state = "PREPARED";
        boolean offline;
        public OfficialTokenService.CenterOperation find(String id) {
            if (offline) throw new OfficialException("DB_UNAVAILABLE");
            JsonArray effects = new JsonArray();
            if (state.equals("COMMITTED")) {
                JsonObject row = new JsonObject();
                row.addProperty("game_id", "Player_A"); row.addProperty("game_key", "player_a");
                row.addProperty("user_id", USER); row.addProperty("state", "active"); row.addProperty("revision", "1");
                effects.add(row);
            }
            return new OfficialTokenService.CenterOperation(S, id, generation, "Player_A", USER, state, effects, new JsonObject());
        }
    }
    static class Case implements AutoCloseable {
        final Path dir;
        final OfficialModState store;
        final Center center = new Center();
        final TestClock clock = new TestClock();
        final OfficialTokenService service;
        final OfficialTokenApi api;
        final OfficialTokenService.Issued issued;
        Case() throws Exception { this(temp(), phase -> {}); }
        Case(Path dir, OfficialModState.WriteHook hook) {
            this.dir = dir;
            store = new OfficialModState(dir, S, true, hook);
            service = new OfficialTokenService(S, store, center, clock, () -> "123456");
            issued = service.issue("Player_A");
            center.generation = issued.tokenGeneration();
            api = new OfficialTokenApi(service, (scope, path) -> true);
        }
        JsonObject reserve() { return service.reserve(OP, USER, "player_a", issued.tokenGeneration(), issued.tokenCode()); }
        public void close() { store.close(); }
    }
    static JsonObject request() { return S.wire(); }
    static JsonObject query(OfficialTokenApi api) { return api.dispatch("GET", "/api/v2/binding/operations/" + OP, request(), true).body(); }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Explicit synthetic fixture path required");
        fixture = OfficialModState.parse(Files.readString(Path.of(args[0]))).getAsJsonObject();
        check(fixture.get("synthetic").getAsBoolean());
        test("code_is_transient_and_safe_to_string", () -> {
            try (Case c = new Case()) {
                check(c.service.validate(c.issued.tokenCode()).get("game_id").getAsString().equals("Player_A"));
                check(!c.issued.toString().contains(c.issued.tokenCode()));
                String raw = Files.readString(c.dir.resolve("official-state.json"));
                check(!raw.contains("token_code") && !raw.contains("123456"));
            }
        });
        test("reserve_is_durable_and_duplicate_has_identical_receipt", () -> {
            try (Case c = new Case()) {
                JsonObject first = c.reserve();
                JsonObject state = c.store.snapshot();
                c.clock.seconds.addAndGet(900);
                check(c.reserve().equals(first)); check(c.store.snapshot().equals(state));
                check(query(c.api).getAsJsonObject("data").getAsJsonObject("reservation").get("state").getAsString().equals("RESERVED"));
            }
        });
        test("reservation_survives_logout_expiry_and_restart", () -> {
            Path dir;
            String generation;
            try (Case c = new Case()) {
                generation=c.issued.tokenGeneration();
                dir = c.dir; c.reserve(); c.service.removeLiveCode("Player_A"); c.clock.seconds.addAndGet(600);
                expect("RESULT_UNKNOWN", () -> c.service.issue("PLAYER_A"));
            }
            try (OfficialModState store = new OfficialModState(dir, S, false)) {
                Center offline = new Center(); offline.offline = true;
                OfficialTokenService service = new OfficialTokenService(S, store, offline);
                check(service.operation(OP).getAsJsonObject("reservation").get("state").getAsString().equals("RESERVED"));
                expect("RESULT_UNKNOWN", () -> service.issue("Player_A"));
                expect("TOKEN_INVALID", () -> service.validate("123456"));
                check(service.hasPendingReservationsFast() && !service.recoverTerminalReservationsOnIo(4));
                offline.generation=generation; offline.offline=false;
                check(!service.recoverTerminalReservationsOnIo(4));
                check(service.operation(OP).getAsJsonObject("reservation").get("state").getAsString().equals("RESERVED"));
                offline.state="COMMITTED";
                check(service.recoverTerminalReservationsOnIo(4) && !service.hasPendingReservationsFast());
                check(service.operation(OP).getAsJsonObject("reservation").get("state").getAsString().equals("CONSUMED"));
            }
        });
        test("unreserved_codes_are_lost_on_restart", () -> {
            Path dir;
            String generation;
            try (Case c = new Case()) { dir = c.dir; generation = c.issued.tokenGeneration(); }
            try (OfficialModState store = new OfficialModState(dir, S, false)) {
                OfficialTokenService service = new OfficialTokenService(S, store, id -> null, new TestClock(), () -> "654321");
                expect("TOKEN_INVALID", () -> service.validate("123456"));
                check(!service.issue("Player_A").tokenGeneration().equals(generation));
            }
        });
        test("same_generation_two_operations_has_one_owner", () -> {
            try (Case c = new Case()) {
                ExecutorService pool = Executors.newFixedThreadPool(2);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> futures = new ArrayList<>();
                for (String op : List.of(OP, OP_B)) futures.add(pool.submit(() -> {
                    start.await();
                    try { c.service.reserve(op, USER, "player_a", c.issued.tokenGeneration(), c.issued.tokenCode()); return "OK"; }
                    catch (OfficialException e) { return e.code(); }
                }));
                start.countDown(); List<String> codes = new ArrayList<>();
                for (Future<String> f : futures) codes.add(f.get(5, TimeUnit.SECONDS));
                pool.shutdownNow(); Collections.sort(codes); check(codes.equals(List.of("CONFLICT", "OK")));
                check(c.store.snapshot().getAsJsonObject("reservations").size() == 1);
            }
        });
        test("center_unavailable_pauses_new_reserve", () -> {
            try (Case c = new Case()) {
                c.center.offline = true; expect("DB_UNAVAILABLE", c::reserve);
                check(c.store.snapshot().getAsJsonObject("reservations").size() == 0);
            }
        });
        test("client_claimed_commit_is_not_authority", () -> {
            try (Case c = new Case()) {
                JsonObject reserved = c.reserve(); String rid = reserved.get("reservation_id").getAsString();
                expect("CONFLICT", () -> c.service.finalizeToken(OP, rid, "COMMITTED", null));
                c.center.state = "COMMITTED";
                expect("CONFLICT", () -> c.service.finalizeToken(OP, rid, "ABORTED", null));
                c.center.offline = true;
                expect("DB_UNAVAILABLE", () -> c.service.finalizeToken(OP, rid, "COMMITTED", null));
            }
        });
        test("commit_consumption_is_idempotent_and_persistent", () -> {
            try (Case c = new Case()) {
                String rid = c.reserve().get("reservation_id").getAsString(); c.center.state = "COMMITTED";
                JsonObject receipt = c.service.finalizeToken(OP, rid, "COMMITTED", null);
                check(receipt.get("token_state").getAsString().equals("CONSUMED"));
                check(receipt.equals(c.service.finalizeToken(OP, rid, "COMMITTED", null)));
                c.service.issue("Player_A");
            }
        });
        test("scope_and_wrong_owner_generation_are_rejected", () -> {
            try (Case c = new Case()) {
                expect("CONFLICT", () -> c.service.reserve(OP, "OTHER_USER", "player_a", c.issued.tokenGeneration(), c.issued.tokenCode()));
                expect("INVALID_INPUT", () -> c.service.reserve(OP, USER, "PLAYER_A", c.issued.tokenGeneration(), c.issued.tokenCode()));
                OfficialTokenApi.Reply reply = c.api.dispatch("GET", "/api/v2/binding/operations/" + OP,
                    new OfficialScope(S.environmentId(), "Other_Group", S.serverId()).wire(), true);
                check(reply.status() == 404); check(reply.body().get("code").getAsString().equals("SCOPE_MISMATCH"));
            }
        });
        test("absent_query_does_not_persist_or_invent_reservation", () -> {
            try (Case c = new Case()) {
                JsonObject before = c.store.snapshot(); c.center.offline = true;
                OfficialTokenApi.Reply response = c.api.dispatch("GET", "/api/v2/binding/operations/" + OP, request(), true);
                check(response.status() == 202); check(response.body().equals(fixture.getAsJsonObject("absent_response")));
                check(c.store.snapshot().equals(before));
            }
        });
        test("absent_finalize_requires_center_abort_and_blocks_late_reserve", () -> {
            try (Case c = new Case()) {
                expect("CONFLICT", () -> c.service.finalizeToken(OP, null, "ABORTED", c.issued.tokenGeneration()));
                c.center.state = "ABORTED";
                JsonObject receipt = c.service.finalizeToken(OP, null, "ABORTED", c.issued.tokenGeneration());
                check(receipt.get("token_state").getAsString().equals("INVALIDATED"));
                check(receipt.has("reservation_id"));
                c.center.state = "PREPARED";
                expect("CONFLICT", c::reserve);
            }
        });
        test("abort_racing_stale_center_read_cannot_resurrect_code", () -> {
            try (Case c = new Case()) {
                CountDownLatch read = new CountDownLatch(1), release = new CountDownLatch(1);
                OfficialTokenService racing = new OfficialTokenService(S, c.store, id -> {
                    var old = c.center.find(id); read.countDown();
                    try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return old;
                }, c.clock, () -> "654321");
                var live = racing.issue("Player_A"); c.center.generation = live.tokenGeneration();
                ExecutorService pool = Executors.newSingleThreadExecutor();
                Future<String> pending = pool.submit(() -> {
                    try { racing.reserve(OP, USER, "player_a", live.tokenGeneration(), live.tokenCode()); return "OK"; }
                    catch (OfficialException e) { return e.code(); }
                });
                check(read.await(5, TimeUnit.SECONDS)); c.center.state = "ABORTED";
                c.service.finalizeToken(OP, null, "ABORTED", live.tokenGeneration()); release.countDown();
                check(pending.get(5, TimeUnit.SECONDS).equals("CONFLICT")); pool.shutdownNow();
            }
        });
        test("before_atomic_replace_failure_is_not_acknowledged", () -> {
            Path dir = temp(); AtomicBoolean fail = new AtomicBoolean();
            try (Case c = new Case(dir, phase -> { if (fail.get() && phase.equals("before_replace")) throw new IOException("synthetic"); })) {
                fail.set(true); expect("RESULT_UNKNOWN", c::reserve); expect("STORAGE_ERROR", () -> c.service.validate("123456"));
            }
            try (OfficialModState recovered = new OfficialModState(dir, S, false)) {
                check(recovered.snapshot().getAsJsonObject("reservations").size() == 0);
            }
        });
        test("after_atomic_replace_response_loss_recovers_reserved_receipt", () -> {
            Path dir = temp(); AtomicBoolean fail = new AtomicBoolean();
            try (Case c = new Case(dir, phase -> { if (fail.get() && phase.equals("after_replace")) throw new IOException("synthetic"); })) {
                fail.set(true); expect("RESULT_UNKNOWN", c::reserve);
            }
            try (OfficialModState recovered = new OfficialModState(dir, S, false)) {
                check(recovered.snapshot().getAsJsonObject("reservations").getAsJsonObject(OP).get("state").getAsString().equals("RESERVED"));
            }
        });
        test("corrupt_or_foreign_storage_is_not_overwritten", () -> {
            Path dir = temp(), file = dir.resolve("official-state.json");
            Files.writeString(file, "{\"schema_version\":1,\"schema_version\":2}");
            byte[] old = Files.readAllBytes(file);
            expect("STORAGE_ERROR", () -> new OfficialModState(dir, S, false));
            check(Arrays.equals(old, Files.readAllBytes(file)));
            try (Case c = new Case()) {
                JsonObject different = c.store.snapshot(); different.getAsJsonObject("scope").addProperty("group_id", "FOREIGN_GROUP");
                expect("SCOPE_MISMATCH", () -> c.store.commit(different));
            }
        });
        test("second_writer_and_missing_primary_fail_closed", () -> {
            try (Case c = new Case()) {
                expect("STORAGE_ERROR", () -> new OfficialModState(c.dir, S, false));
                Files.delete(c.dir.resolve("official-state.json"));
                expect("STORAGE_ERROR", c::reserve);
            }
            expect("STORAGE_ERROR", () -> new OfficialModState(temp(), S, false));
        });
        test("reserved_query_still_works_while_center_is_offline", () -> {
            try (Case c = new Case()) {
                c.reserve(); c.center.offline = true;
                var response = c.api.dispatch("GET", "/api/v2/binding/operations/" + OP, request(), true);
                check(response.status() == 202 && response.body().get("code").getAsString().equals("RESULT_UNKNOWN"));
                JsonObject data = response.body().getAsJsonObject("data");
                check(!data.get("center_available").getAsBoolean());
                check(data.get("state").getAsString().equals("PREPARED"));
                check(data.getAsJsonObject("reservation").get("state").getAsString().equals("RESERVED"));
            }
        });
        test("api_auth_method_and_legacy_paths_do_not_mutate", () -> {
            try (Case c = new Case()) {
                JsonObject before = c.store.snapshot();
                check(c.api.dispatch("GET", "/api/v2/binding/operations/" + OP, null, false).status() == 401);
                check(c.api.dispatch("POST", "/api/bind", request(), true).body().get("code").getAsString().equals("NOT_SUPPORTED"));
                check(c.api.dispatch("GET", "/api/v2/binding/token/reserve", request(), true).body().get("code").getAsString().equals("NOT_SUPPORTED"));
                check(c.api.dispatch("GET", "/api/v2/binding/capabilities", request(), true).body().get("code").getAsString().equals("NOT_SUPPORTED"));
                check(c.store.snapshot().equals(before));
            }
        });
        test("json_duplicate_keys_invalid_utf8_and_depth_are_rejected", () -> {
            expect("STORAGE_ERROR", () -> {
                Path dir = temp(); Files.write(dir.resolve("official-state.json"), new byte[]{(byte)0xff});
                new OfficialModState(dir, S, false);
            });
            try { OfficialModState.parse("{\"a\":1,\"a\":2}"); throw new AssertionError(); } catch (IOException expected) { }
            try { OfficialModState.parse("[".repeat(18) + "0" + "]".repeat(18)); throw new AssertionError(); } catch (IOException expected) { }
        });
        test("shared_absent_finalize_fixture_matches_persistent_invalidation", () -> {
            try (Case c = new Case()) {
                c.center.generation = fixture.get("token_generation").getAsString(); c.center.state = "ABORTED";
                JsonObject body = request();
                for (var field : fixture.getAsJsonObject("absent_finalize").entrySet()) body.add(field.getKey(), field.getValue());
                var response = c.api.dispatch("POST", "/api/v2/binding/token/finalize", body, true);
                check(response.status() == 200);
                check(response.body().getAsJsonObject("data").get("token_state").getAsString().equals("INVALIDATED"));
                check(response.body().getAsJsonObject("data").has("reservation_id"));
            }
        });
        test("foreign_center_operation_cannot_be_reported_as_absent", () -> {
            try (Case c = new Case()) {
                OfficialTokenService wrong = new OfficialTokenService(S, c.store, id -> new OfficialTokenService.CenterOperation(
                    new OfficialScope(S.environmentId(), "FOREIGN", S.serverId()), id, c.issued.tokenGeneration(), "Player_A", USER,
                    "PREPARED", new JsonArray(), new JsonObject()));
                expect("SCOPE_MISMATCH", () -> wrong.operation(OP));
            }
        });
        test("consumed_confirmation_ack_loss_is_visible_after_restart", () -> {
            Path dir = temp(); AtomicBoolean fail = new AtomicBoolean(); String generation;
            try (Case c = new Case(dir, phase -> { if (fail.get() && phase.equals("after_replace")) throw new IOException("synthetic"); })) {
                generation = c.issued.tokenGeneration(); String rid = c.reserve().get("reservation_id").getAsString();
                c.center.state = "COMMITTED"; fail.set(true);
                expect("RESULT_UNKNOWN", () -> c.service.finalizeToken(OP, rid, "COMMITTED", null));
            }
            try (OfficialModState recovered = new OfficialModState(dir, S, false)) {
                Center center = new Center(); center.offline = true; center.generation = generation;
                var service = new OfficialTokenService(S, recovered, center);
                check(service.operation(OP).getAsJsonObject("reservation").get("state").getAsString().equals("CONSUMED"));
            }
        });
        test("old_generation_cannot_be_consumed_after_invalidation_and_new_issue", () -> {
            try (Case c = new Case()) {
                JsonObject reservation = c.reserve(); c.center.state = "ABORTED";
                c.service.finalizeToken(OP, reservation.get("reservation_id").getAsString(), "ABORTED", null);
                var next = c.service.issue("Player_A"); check(!next.tokenGeneration().equals(c.issued.tokenGeneration()));
                c.center.state = "PREPARED"; expect("CONFLICT", c::reserve);
                expect("CONFLICT", () -> c.service.reserve(OP_B, USER, "player_a", c.issued.tokenGeneration(), next.tokenCode()));
            }
        });
        test("absent_finalize_center_offline_cannot_create_a_tombstone", () -> {
            try (Case c = new Case()) {
                c.center.offline = true;
                expect("DB_UNAVAILABLE", () -> c.service.finalizeToken(OP, null, "ABORTED", c.issued.tokenGeneration()));
                check(c.store.snapshot().getAsJsonObject("reservations").size() == 0);
            }
        });
        test("known_reservation_id_is_preserved_when_recovering_a_missing_local_row", () -> {
            try (Case c = new Case()) {
                c.center.state = "ABORTED";
                String rid = fixture.get("reservation_id").getAsString();
                JsonObject result = c.service.finalizeToken(OP, rid, "ABORTED", null);
                check(result.get("token_state").getAsString().equals("INVALIDATED"));
                check(c.service.operation(OP).getAsJsonObject("reservation").get("reservation_id").getAsString().equals(rid));
            }
        });
        test("unicode_identity_case_and_boundary_spaces_follow_contract", () -> {
            check(!OfficialScope.identifier("SYNTHETIC_USER_A").equals(OfficialScope.identifier("synthetic_user_a")));
            check(OfficialScope.key("Player_A").equals("player_a"));
            expect("INVALID_INPUT", () -> OfficialScope.identifier("USER\u00a0"));
            expect("INVALID_INPUT", () -> OfficialScope.revision("01"));
            expect("INVALID_INPUT", () -> OfficialScope.revision("9223372036854775808"));
        });
        System.out.println("PASS " + passed + " standalone official Java tests; no Gradle, game, config, credentials or network");
    }
}
