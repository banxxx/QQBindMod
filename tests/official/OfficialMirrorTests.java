package com.poso.qqbind.official;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.poso.qqbind.official.OfficialMirrorService.*;

/** File/network/game state is synthetic; no Minecraft classes are loaded. */
public final class OfficialMirrorTests {
    private static final OfficialScope S = new OfficialScope("11111111-1111-4111-8111-111111111111", "SYNTHETIC_GROUP_A", "33333333-3333-4333-8333-333333333333");
    private static final String A = "66666666-6666-4666-8666-666666666666", B = "77777777-7777-4777-8777-777777777777",
        C = "88888888-8888-4888-8888-888888888888", D = "99999999-9999-4999-8999-999999999999";
    private static final String USER = "SYNTHETIC_USER_A", OTHER = "SYNTHETIC_USER_B";
    private static int passed;
    interface Checked { void run() throws Exception; }
    static void check(boolean result) { if (!result) throw new AssertionError("Synthetic mirror assertion failed"); }
    static void expect(String code, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (OfficialException e) { check(e.code().equals(code)); }
    }
    static void test(String name, Checked action) throws Exception { action.run(); passed++; System.out.println("PASS " + name); }
    static Confirmation trusted() {
        return new Confirmation() {
            public void committed(OfficialScope source, String operation, JsonArray effects) { check(source.equals(S)); }
            public void snapshot(OfficialScope source, JsonArray rows) { check(source.equals(S)); }
        };
    }
    static JsonObject effect(String game, String user, String state, String revision) {
        JsonObject value = new JsonObject(); value.addProperty("game_key", OfficialScope.key(game));
        value.addProperty("game_id", game); value.addProperty("user_id", user); value.addProperty("state", state); value.addProperty("revision", revision);
        return value;
    }
    static JsonArray array(JsonObject... values) { JsonArray result = new JsonArray(); for (var v : values) result.add(v.deepCopy()); return result; }
    static JsonObject result(JsonObject reply) { return reply.getAsJsonArray("results").get(0).getAsJsonObject(); }
    static JsonObject row(String operation, JsonObject effect) { JsonObject row = effect.deepCopy(); row.addProperty("last_operation_id", operation); return row; }
    static class Sink implements GameEffects {
        final List<String> calls = new CopyOnWriteArrayList<>(); boolean failWhitelist;
        volatile boolean whitelist, online;
        public void whitelist(String game, boolean active) {
            calls.add("whitelist:" + game + ":" + active);
            if (failWhitelist) throw new IllegalStateException("SYNTHETIC_PRIVATE_DETAIL");
            whitelist = active;
        }
        public void online(String game, boolean active) { calls.add("online:" + game + ":" + active); online = active; }
    }
    static class Case implements AutoCloseable {
        final Path dir; final OfficialModState store; final OfficialMirrorService mirror; final Sink sink = new Sink();
        Case() throws Exception { this(Files.createTempDirectory("official-mirror-synthetic-"), phase -> {}, trusted()); }
        Case(Path dir, OfficialModState.WriteHook hook, Confirmation confirmation) {
            this.dir = dir; store = new OfficialModState(dir, S, true, hook);
            mirror = new OfficialMirrorService(S, store, confirmation, () -> true);
        }
        JsonObject bind() { return mirror.sync(S, A, array(effect("Player_A", USER, "active", "1"))); }
        void revoke() { mirror.sync(S, B, array(effect("Player_A", USER, "revoked", "2"))); }
        Ticket ticket(String operation, Part part) {
            return mirror.pending(100).stream().filter(t -> t.operationId().equals(operation) && t.part() == part).findFirst().orElseThrow();
        }
        void drain() { for (Ticket t : mirror.pending(100)) mirror.acknowledge(mirror.execute(t, sink)); }
        long outstanding(Part part) { return mirror.pending(100).stream().filter(ticket -> ticket.part() == part).count(); }
        public void close() { mirror.close(); store.close(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Explicit synthetic fixture path required");
        JsonObject fixture = OfficialModState.parse(Files.readString(Path.of(args[0]))).getAsJsonObject();
        check(fixture.get("synthetic").getAsBoolean());
        test("one_user_multiple_accounts_revoke_one_preserves_other", () -> {
            try (Case c = new Case()) {
                c.bind(); c.mirror.sync(S, C, array(effect("Player_B", USER, "active", "1")));
                check(c.mirror.activeGames(USER).equals(Set.of("player_a", "player_b")));
                c.revoke(); check(c.mirror.activeGames(USER).equals(Set.of("player_b")));
                JsonObject tombstone = c.store.snapshot().getAsJsonObject("bindings").getAsJsonObject("player_a");
                check(tombstone.get("state").getAsString().equals("revoked")); check(tombstone.get("revision").getAsString().equals("2"));
                check(c.mirror.activeGames(USER.toLowerCase(Locale.ROOT)).isEmpty());
            }
        });
        test("mirror_ack_is_separate_from_pending_game_effects", () -> {
            try (Case c = new Case()) {
                JsonObject item = result(c.bind()); check(item.get("persisted").getAsBoolean());
                check(item.get("whitelist").getAsString().equals("PENDING") && c.sink.calls.isEmpty());
                Ticket whitelist = c.ticket(A, Part.WHITELIST); c.mirror.acknowledge(c.mirror.execute(whitelist, c.sink));
                item = result(c.mirror.operationResults(A)); check(item.get("whitelist").getAsString().equals("APPLIED"));
                check(item.get("online").getAsString().equals("PENDING"));
                c.drain(); check(result(c.mirror.operationResults(A)).get("online").getAsString().equals("APPLIED"));
            }
        });
        test("online_queue_skips_captured_old_version_and_acks_the_latest_target", () -> {
            try (Case c = new Case(); OfficialEffectQueue queue = new OfficialEffectQueue(c.mirror)) {
                c.bind();
                queue.captureOnIo();
                c.revoke();
                queue.executeOnGame(c.sink);
                check(c.sink.calls.isEmpty());
                queue.captureOnIo();
                check(!queue.needsIo());
                queue.executeOnGame(c.sink);
                queue.captureOnIo();
                check(!c.sink.online && !queue.needsIo());
                queue.captureOnIo();
                check(!queue.needsIo());
                check(result(c.mirror.operationResults(A)).get("online").getAsString().equals("SUPERSEDED"));
                check(result(c.mirror.operationResults(B)).get("online").getAsString().equals("APPLIED"));
                check(result(c.mirror.operationResults(A)).get("whitelist").getAsString().equals("SUPERSEDED"));
            }
        });
        test("online_queue_replays_unacknowledged_action_after_restart", () -> {
            Path directory;
            Sink serverEffect = new Sink();
            try (Case c = new Case(); OfficialEffectQueue queue = new OfficialEffectQueue(c.mirror)) {
                directory = c.dir;
                c.bind(); queue.captureOnIo(); queue.executeOnGame(serverEffect);
                check(serverEffect.online && queue.needsIo());
                // Closing before the next I/O phase models an applied action whose receipt was lost.
            }
            try (OfficialModState state = new OfficialModState(directory, S, false);
                 OfficialMirrorService mirror = new OfficialMirrorService(S, state, trusted(), () -> true);
                 OfficialEffectQueue restarted = new OfficialEffectQueue(mirror)) {
                check(result(mirror.operationResults(A)).get("online").getAsString().equals("PENDING"));
                restarted.captureOnIo(); restarted.executeOnGame(serverEffect); restarted.captureOnIo();
                check(serverEffect.online && !restarted.needsIo());
                check(result(mirror.operationResults(A)).get("online").getAsString().equals("APPLIED"));
            }
        });
        test("failed_bind_v1_then_unbind_v2_old_retry_is_superseded", () -> {
            try (Case c = new Case()) {
                c.bind(); Ticket old = c.ticket(A, Part.WHITELIST); c.sink.failWhitelist = true;
                c.mirror.acknowledge(c.mirror.execute(old, c.sink)); c.revoke(); c.sink.failWhitelist = false; c.drain();
                int count = c.sink.calls.size(); Execution late = c.mirror.execute(old, c.sink);
                check(late.outcome() == Outcome.SUPERSEDED && c.sink.calls.size() == count);
                c.mirror.acknowledge(late); check(!c.sink.whitelist && !c.sink.online);
                JsonObject ack = result(c.mirror.operationResults(A));
                check(ack.get("result").getAsString().equals("ignored") && ack.get("effective_revision").getAsString().equals("2"));
            }
        });
        test("old_unbind_task_cannot_revoke_new_owner_v3", () -> {
            try (Case c = new Case()) {
                c.bind(); c.revoke(); Ticket old = c.ticket(B, Part.WHITELIST);
                c.mirror.sync(S, D, array(effect("Player_A", OTHER, "active", "3"))); c.drain();
                int count = c.sink.calls.size(); check(c.mirror.execute(old, c.sink).outcome() == Outcome.SUPERSEDED);
                check(c.sink.calls.size() == count && c.sink.whitelist);
                check(c.mirror.activeGames(USER).isEmpty() && c.mirror.activeGames(OTHER).equals(Set.of("player_a")));
            }
        });
        test("duplicate_version_identical_payload_is_idempotent", () -> {
            try (Case c = new Case()) {
                JsonObject first = c.bind(), before = c.store.snapshot();
                check(c.bind().equals(first)); check(c.store.snapshot().equals(before));
                c.drain(); int count = c.sink.calls.size(); c.bind(); c.drain(); check(c.sink.calls.size() == count);
            }
        });
        test("same_operation_different_payload_rejected_without_overwrite", () -> {
            try (Case c = new Case()) {
                c.bind(); JsonObject before = c.store.snapshot();
                expect("CONFLICT", () -> c.mirror.sync(S, A, array(effect("Player_A", OTHER, "active", "2"))));
                check(c.store.snapshot().equals(before));
            }
        });
        test("same_revision_content_conflict_pauses_automatic_overwrite", () -> {
            try (Case c = new Case()) {
                c.bind(); Ticket old = c.ticket(A, Part.WHITELIST);
                JsonObject conflict = c.mirror.sync(S, B, array(effect("Player_A", OTHER, "active", "1")));
                check(result(conflict).get("result").getAsString().equals("conflict"));
                check(c.mirror.execute(old, c.sink).outcome() == Outcome.CONFLICT && c.sink.calls.isEmpty());
                check(c.mirror.activeGames(USER).isEmpty());
                c.mirror.sync(S, C, array(effect("Player_A", OTHER, "active", "2")));
                check(c.store.snapshot().getAsJsonObject("bindings").getAsJsonObject("player_a").get("revision").getAsString().equals("1"));
                check(c.mirror.pending(100).isEmpty());
            }
        });
        test("old_full_snapshot_cannot_replace_newer_incremental_or_delete_missing", () -> {
            try (Case c = new Case()) {
                c.bind(); c.revoke(); c.mirror.sync(S, C, array(effect("Player_B", USER, "active", "1")));
                c.mirror.mergeSnapshot(S, array(row(A, effect("Player_A", USER, "active", "1"))));
                check(c.mirror.activeGames(USER).equals(Set.of("player_b")));
                c.mirror.mergeSnapshot(S, new JsonArray()); check(c.mirror.activeGames(USER).equals(Set.of("player_b")));
            }
        });
        test("snapshot_partial_operation_can_be_completed_by_fixed_incremental", () -> {
            try (Case c = new Case()) {
                c.mirror.mergeSnapshot(S, array(row(A, effect("Player_A", USER, "active", "1"))));
                expect("NOT_SUPPORTED", () -> c.mirror.operationResults(A));
                JsonObject result = c.mirror.sync(S, A, array(effect("Player_A", USER, "active", "1"), effect("Player_B", USER, "active", "1")));
                check(result.getAsJsonArray("results").size() == 2);
                check(c.mirror.activeGames(USER).size() == 2);
            }
        });
        test("mirror_and_unfinished_effects_survive_restart_and_ack_loss", () -> {
            Path dir; Ticket original;
            try (Case c = new Case()) { c.bind(); dir = c.dir; original = c.ticket(A, Part.WHITELIST); check(c.mirror.execute(original, c.sink).outcome() == Outcome.APPLIED); }
            try (OfficialModState store = new OfficialModState(dir, S, false);
                 OfficialMirrorService mirror = new OfficialMirrorService(S, store, trusted(), () -> true)) {
                Sink sink = new Sink(); Ticket restored = mirror.pending(100).stream().filter(t -> t.part() == Part.WHITELIST).findFirst().orElseThrow();
                check(restored.equals(original)); mirror.acknowledge(mirror.execute(restored, sink));
                check(sink.whitelist && result(mirror.operationResults(A)).get("whitelist").getAsString().equals("APPLIED"));
            }
        });
        test("old_captured_task_after_restart_and_new_revoked_version_is_ignored", () -> {
            Path dir; Ticket old;
            try (Case c = new Case()) { c.bind(); dir = c.dir; old = c.ticket(A, Part.WHITELIST); c.revoke(); }
            try (OfficialModState store = new OfficialModState(dir, S, false);
                 OfficialMirrorService mirror = new OfficialMirrorService(S, store, trusted(), () -> true)) {
                Sink sink = new Sink(); check(mirror.execute(old, sink).outcome() == Outcome.SUPERSEDED && sink.calls.isEmpty());
            }
        });
        test("late_ack_cannot_overwrite_new_operation_effect_status", () -> {
            try (Case c = new Case()) {
                c.bind(); Ticket old = c.ticket(A, Part.WHITELIST); Execution applied = c.mirror.execute(old, c.sink);
                c.revoke(); c.drain(); c.mirror.acknowledge(applied);
                check(result(c.mirror.operationResults(A)).get("whitelist").getAsString().equals("SUPERSEDED"));
                check(result(c.mirror.operationResults(B)).get("whitelist").getAsString().equals("APPLIED"));
            }
        });
        test("foreign_scope_and_missing_confirmation_cannot_modify_mirror", () -> {
            try (Case c = new Case()) {
                JsonObject before = c.store.snapshot();
                expect("SCOPE_MISMATCH", () -> c.mirror.sync(new OfficialScope(S.environmentId(), "OTHER", S.serverId()), A, array(effect("Player_A", USER, "active", "1"))));
                check(c.store.snapshot().equals(before));
            }
            try (Case c = new Case(Files.createTempDirectory("official-no-source-"), phase -> {}, null)) {
                expect("NOT_SUPPORTED", c::bind); check(c.store.snapshot().getAsJsonObject("bindings").size() == 0);
            }
        });
        test("closed_or_wrong_game_thread_never_runs_effect", () -> {
            try (Case c = new Case()) {
                c.bind(); Ticket ticket = c.ticket(A, Part.WHITELIST); c.mirror.close();
                check(c.mirror.execute(ticket, c.sink).outcome() == Outcome.PENDING && c.sink.calls.isEmpty());
            }
            Path dir = Files.createTempDirectory("official-wrong-thread-");
            try (OfficialModState store = new OfficialModState(dir, S, true);
                 OfficialMirrorService mirror = new OfficialMirrorService(S, store, trusted(), () -> false)) {
                mirror.sync(S, A, array(effect("Player_A", USER, "active", "1"))); Ticket ticket = mirror.pending(1).get(0);
                expect("CONFLICT", () -> mirror.execute(ticket, new Sink()));
            }
        });
        test("disk_write_inflight_defers_game_callback_without_waiting", () -> {
            CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean block = new AtomicBoolean();
            OfficialModState.WriteHook hook = phase -> {
                if (block.get() && phase.equals("before_replace")) {
                    writing.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(); }
                }
            };
            ExecutorService background = Executors.newSingleThreadExecutor(), game = Executors.newSingleThreadExecutor();
            try (Case c = new Case(Files.createTempDirectory("official-mirror-fence-"), hook, trusted())) {
                c.bind(); Ticket old = c.ticket(A, Part.WHITELIST); block.set(true);
                Future<?> update = background.submit(c::revoke); check(writing.await(5, TimeUnit.SECONDS));
                try { check(game.submit(() -> c.mirror.execute(old, c.sink)).get(2, TimeUnit.SECONDS).outcome() == Outcome.PENDING); }
                finally { release.countDown(); }
                update.get(5, TimeUnit.SECONDS); check(c.sink.calls.isEmpty());
                check(game.submit(() -> c.mirror.execute(old, c.sink)).get(2, TimeUnit.SECONDS).outcome() == Outcome.SUPERSEDED);
            } finally { release.countDown(); background.shutdownNow(); game.shutdownNow(); }
        });
        test("lock_free_pending_online_flag_stays_true_while_persisting_ack", () -> {
            CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicBoolean block = new AtomicBoolean();
            OfficialModState.WriteHook hook = phase -> {
                if (block.get() && phase.equals("before_replace")) {
                    writing.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(); }
                }
            };
            ExecutorService io = Executors.newSingleThreadExecutor();
            try (Case c = new Case(Files.createTempDirectory("official-online-ack-handoff-"), hook, trusted())) {
                c.bind(); Ticket ticket = c.ticket(A, Part.ONLINE);
                Execution applied = c.mirror.execute(ticket, c.sink);
                block.set(true);
                Future<?> ack = io.submit(() -> c.mirror.acknowledge(applied));
                check(writing.await(5, TimeUnit.SECONDS));
                try { check(c.mirror.hasPendingOnline()); } finally { release.countDown(); }
                ack.get(5, TimeUnit.SECONDS);
                check(!c.mirror.hasPendingOnline());
            } finally { release.countDown(); io.shutdownNow(); }
        });
        test("new_version_during_old_action_keeps_new_correction_last", () -> {
            ExecutorService game = Executors.newSingleThreadExecutor();
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            try (Case c = new Case()) {
                c.bind(); Ticket old = c.ticket(A, Part.WHITELIST);
                GameEffects slow = new GameEffects() {
                    public void whitelist(String name, boolean active) throws Exception { started.countDown(); release.await(5, TimeUnit.SECONDS); c.sink.whitelist(name, active); }
                    public void online(String name, boolean active) { c.sink.online(name, active); }
                };
                Future<Execution> effect = game.submit(() -> c.mirror.execute(old, slow)); check(started.await(5, TimeUnit.SECONDS));
                c.revoke(); release.countDown(); Execution outdated = effect.get(5, TimeUnit.SECONDS);
                check(outdated.outcome() == Outcome.SUPERSEDED); c.mirror.acknowledge(outdated);
                Ticket latest = c.ticket(B, Part.WHITELIST);
                Execution corrected = game.submit(() -> c.mirror.execute(latest, c.sink)).get(5, TimeUnit.SECONDS);
                c.mirror.acknowledge(corrected); check(!c.sink.whitelist);
                check(game.submit(() -> c.mirror.execute(old, c.sink)).get(5, TimeUnit.SECONDS).outcome() == Outcome.SUPERSEDED);
            } finally { release.countDown(); game.shutdownNow(); }
        });
        test("atomic_mirror_failure_is_not_reported_applied_and_reopen_queries_disk", () -> {
            Path dir = Files.createTempDirectory("official-mirror-loss-"); AtomicBoolean fail = new AtomicBoolean();
            try (Case c = new Case(dir, phase -> { if (fail.get() && phase.equals("after_replace")) throw new IOException("synthetic"); }, trusted())) {
                fail.set(true); expect("RESULT_UNKNOWN", c::bind); check(c.mirror.activeGames(USER).isEmpty());
            }
            try (OfficialModState store = new OfficialModState(dir, S, false);
                 OfficialMirrorService mirror = new OfficialMirrorService(S, store, trusted(), () -> true)) {
                check(mirror.activeGames(USER).equals(Set.of("player_a")));
                check(result(mirror.operationResults(A)).get("whitelist").getAsString().equals("PENDING"));
            }
        });
        test("corrupt_effect_receipt_is_not_overwritten_on_reopen", () -> {
            Path dir; JsonObject corrupt;
            try (Case c = new Case()) { c.bind(); dir = c.dir; corrupt = c.store.snapshot(); }
            corrupt.getAsJsonObject("pending_effects").getAsJsonObject(A + ":player_a").addProperty("revision", "999");
            Path file = dir.resolve("official-state.json"); Files.writeString(file, corrupt.toString()); byte[] before = Files.readAllBytes(file);
            expect("STORAGE_ERROR", () -> new OfficialModState(dir, S, false)); check(Arrays.equals(before, Files.readAllBytes(file)));
        });
        test("one_effect_coordinator_per_state_file", () -> {
            try (Case c = new Case()) {
                expect("CONFLICT", () -> new OfficialMirrorService(S, c.store, trusted(), () -> true));
                c.mirror.close();
                try (OfficialMirrorService next = new OfficialMirrorService(S, c.store, trusted(), () -> true)) { check(next.activeGames(USER).isEmpty()); }
            }
        });
        test("token_reservations_and_mirror_share_one_atomic_state", () -> {
            try (Case c = new Case()) {
                var token = new OfficialTokenService(S, c.store, id -> null, new OfficialTokenTests.TestClock(), () -> "654321");
                var issued = token.issue("Player_A"); c.bind();
                check(token.validate(issued.tokenCode()).get("game_id").getAsString().equals("Player_A"));
                check(c.store.snapshot().getAsJsonObject("bindings").size() == 1);
            }
        });
        test("shared_wire_fixture_is_exact_sync_reply_without_game_completion", () -> {
            try (Case c = new Case()) {
                var token = new OfficialTokenService(S, c.store, id -> null);
                var api = new OfficialTokenApi(token, (scope, path) -> true, c.mirror);
                JsonObject request = S.wire();
                for (var entry : fixture.getAsJsonObject("request").entrySet()) request.add(entry.getKey(), entry.getValue().deepCopy());
                var reply = api.dispatch("POST", "/api/v2/binding/sync", request, true);
                check(reply.status() == 200 && reply.body().equals(fixture.get("response")));
                check(c.sink.calls.isEmpty());
                request.remove("effects"); request.add("results", new JsonArray());
                check(api.dispatch("POST", "/api/v2/binding/sync", request, true).body().get("code").getAsString().equals("INVALID_INPUT"));
            }
        });
        test("part_failure_retries_only_failed_part_without_regressing_other_ack", () -> {
            try (Case c = new Case()) {
                c.bind(); c.sink.failWhitelist = true; c.drain();
                JsonObject reply = result(c.mirror.operationResults(A));
                check(reply.get("whitelist").getAsString().equals("FAILED"));
                check(reply.get("online").getAsString().equals("APPLIED"));
                check(c.mirror.pending(100).size() == 1); c.sink.failWhitelist = false; c.drain();
                check(result(c.mirror.operationResults(A)).get("whitelist").getAsString().equals("APPLIED"));
                check(c.sink.calls.stream().filter(v -> v.startsWith("online:")).count() == 1);
            }
        });
        test("fixed_batch_payload_cannot_be_shrunk_by_later_sync", () -> {
            try (Case c = new Case()) {
                c.mirror.sync(S, A, array(effect("Player_A", USER, "active", "1"), effect("Player_B", USER, "active", "1")));
                JsonObject before = c.store.snapshot();
                expect("CONFLICT", c::bind); check(c.store.snapshot().equals(before));
                check(c.mirror.activeGames(USER).equals(Set.of("player_a", "player_b")));
            }
        });
        test("input_and_result_mutation_cannot_change_published_mirror_index", () -> {
            try (Case c = new Case()) {
                JsonArray payload = array(effect("Player_A", USER, "active", "1"));
                JsonObject reply = c.mirror.sync(S, A, payload);
                payload.get(0).getAsJsonObject().addProperty("user_id", OTHER);
                reply.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("effective_revision", "100");
                check(c.mirror.activeGames(USER).equals(Set.of("player_a")) && c.mirror.activeGames(OTHER).isEmpty());
                try { c.mirror.activeGames(USER).clear(); throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
                check(result(c.mirror.operationResults(A)).get("effective_revision").getAsString().equals("1"));
            }
        });
        test("batch_cache_conflict_does_not_half_apply_snapshot", () -> {
            try (Case c = new Case()) {
                c.bind(); JsonObject before = c.store.snapshot();
                expect("CONFLICT", () -> c.mirror.mergeSnapshot(S, array(row(C, effect("Player_B", USER, "active", "1")),
                    row(A, effect("Player_A", OTHER, "active", "1")))));
                check(c.store.snapshot().equals(before)); check(c.mirror.activeGames(USER).equals(Set.of("player_a")));
            }
        });
        test("pre_replace_failure_never_publishes_new_authorization_or_effect_ack", () -> {
            Path dir = Files.createTempDirectory("official-mirror-before-"); AtomicBoolean fail = new AtomicBoolean();
            try (Case c = new Case(dir, phase -> { if (fail.get() && phase.equals("before_replace")) throw new IOException("synthetic"); }, trusted())) {
                fail.set(true); expect("RESULT_UNKNOWN", c::bind); check(c.mirror.activeGames(USER).isEmpty());
            }
            try (OfficialModState store = new OfficialModState(dir, S, false);
                 OfficialMirrorService mirror = new OfficialMirrorService(S, store, trusted(), () -> true)) {
                check(mirror.activeGames(USER).isEmpty() && mirror.pending(100).isEmpty());
            }
        });
        test("old_coordinator_remains_disabled_after_replacement", () -> {
            try (Case c = new Case()) {
                c.bind(); Ticket old = c.ticket(A, Part.WHITELIST); c.mirror.close();
                try (OfficialMirrorService next = new OfficialMirrorService(S, c.store, trusted(), () -> true)) {
                    next.sync(S, B, array(effect("Player_A", USER, "revoked", "2")));
                    check(c.mirror.execute(old, c.sink).outcome() == Outcome.PENDING && c.sink.calls.isEmpty());
                    check(next.execute(old, c.sink).outcome() == Outcome.SUPERSEDED);
                }
            }
        });
        test("detected_disk_corruption_stops_all_published_game_authorization", () -> {
            try (Case c = new Case()) {
                c.bind(); Ticket old = c.ticket(A, Part.WHITELIST);
                Files.writeString(c.dir.resolve("official-state.json"), "{bad synthetic storage}");
                expect("STORAGE_ERROR", () -> c.mirror.pending(10));
                check(c.mirror.activeGames(USER).isEmpty());
                check(c.mirror.execute(old, c.sink).outcome() == Outcome.PENDING && c.sink.calls.isEmpty());
            }
        });
        test("wire_numeric_revision_or_duplicate_game_rows_cannot_enter_state", () -> {
            try (Case c = new Case()) {
                JsonObject numeric = effect("Player_A", USER, "active", "1"); numeric.addProperty("revision", 1);
                JsonObject before = c.store.snapshot();
                expect("INVALID_INPUT", () -> c.mirror.sync(S, A, array(numeric)));
                expect("INVALID_INPUT", () -> c.mirror.sync(S, A, array(effect("Player_A", USER, "active", "1"), effect("Player_A", USER, "active", "1"))));
                check(c.store.snapshot().equals(before));
            }
        });
        test("unconfirmed_center_payload_cannot_create_mirror_or_game_tasks", () -> {
            Confirmation unavailable = new Confirmation() {
                public void committed(OfficialScope s, String op, JsonArray effects) { throw new OfficialException("DB_UNAVAILABLE"); }
                public void snapshot(OfficialScope s, JsonArray rows) { throw new OfficialException("DB_UNAVAILABLE"); }
            };
            try (Case c = new Case(Files.createTempDirectory("official-mirror-unconfirmed-"), phase -> {}, unavailable)) {
                expect("DB_UNAVAILABLE", c::bind);
                check(c.store.snapshot().getAsJsonObject("bindings").size() == 0);
                check(c.mirror.pending(100).isEmpty() && c.sink.calls.isEmpty());
            }
        });
        test("incremental_update_during_snapshot_read_is_preserved", () -> {
            CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
            Confirmation source = new Confirmation() {
                public void committed(OfficialScope scope, String op, JsonArray effects) { }
                public void snapshot(OfficialScope scope, JsonArray rows) {
                    reading.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new OfficialException("RESULT_UNKNOWN"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OfficialException("RESULT_UNKNOWN"); }
                }
            };
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try (Case c = new Case(Files.createTempDirectory("official-snapshot-inflight-"), phase -> {}, source)) {
                c.bind(); Future<?> snapshot = worker.submit(() -> c.mirror.mergeSnapshot(S, array(row(A, effect("Player_A", USER, "active", "1")))));
                check(reading.await(5, TimeUnit.SECONDS)); c.revoke(); release.countDown(); snapshot.get(5, TimeUnit.SECONDS);
                check(c.mirror.activeGames(USER).isEmpty());
                check(c.store.snapshot().getAsJsonObject("bindings").getAsJsonObject("player_a").get("revision").getAsString().equals("2"));
            } finally { release.countDown(); worker.shutdownNow(); }
        });
        test("trusted_committed_downlink_does_not_depend_on_mod_token_center_connection", () -> {
            try (Case c = new Case()) {
                OfficialTokenService tokens = new OfficialTokenService(S, c.store, op -> { throw new OfficialException("DB_UNAVAILABLE"); });
                var issued = tokens.issue("Other_Player");
                expect("DB_UNAVAILABLE", () -> tokens.reserve(A, USER, "other_player", issued.tokenGeneration(), issued.tokenCode()));
                // Authenticated fixed COMMITTED payload already confirmed by the
                // plugin uses its own source verifier, not the failed token DB.
                check(result(c.bind()).get("persisted").getAsBoolean());
                check(c.mirror.activeGames(USER).equals(Set.of("player_a")));
            }
        });
        System.out.println("PASS " + passed + " standalone mirror/effect tests; no game, center DB, network or user data");
    }
}
