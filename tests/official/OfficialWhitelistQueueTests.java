package com.poso.qqbind.official;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.poso.qqbind.official.OfficialMirrorService.*;
import com.poso.qqbind.official.OfficialWhitelistFile.Profile;

/** Real mirror/file pipeline, fake native memory/resolver, new temporary files only. */
public final class OfficialWhitelistQueueTests {
    private static final OfficialScope S=new OfficialScope("11111111-1111-4111-8111-111111111111","SYNTHETIC_GROUP_A","33333333-3333-4333-8333-333333333333");
    private static final String A="66666666-6666-4666-8666-666666666666", B="77777777-7777-4777-8777-777777777777", C="88888888-8888-4888-8888-888888888888";
    private static final Profile PA=new Profile(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),"Player_A"),
        PB=new Profile(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),"Player_B");
    private static int passed;
    interface Checked { void run() throws Exception; }
    static void check(boolean value) { if (!value) throw new AssertionError("Synthetic whitelist-queue assertion failed"); }
    static void expect(String code, Checked action) throws Exception { try { action.run(); throw new AssertionError("Expected "+code); } catch (OfficialException error) { check(error.code().equals(code)); } }
    static void test(String name, Checked action) throws Exception { action.run(); passed++; System.out.println("PASS "+name); }
    static final class Case implements AutoCloseable {
        final Path root=Files.createTempDirectory("official-whitelist-queue-synthetic-"), nativePath=root.resolve("whitelist.json");
        final Thread main=Thread.currentThread();
        final AtomicBoolean active=new AtomicBoolean(true), ackFault=new AtomicBoolean();
        final AtomicInteger applications=new AtomicInteger(), writes=new AtomicInteger();
        final List<String> lookups=new CopyOnWriteArrayList<>();
        final Map<UUID,Profile> memory=new LinkedHashMap<>();
        final Map<String,Profile> profiles=new ConcurrentHashMap<>();
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        OfficialModState store; OfficialMirrorService mirror; OfficialWhitelistFile file; OfficialWhitelistQueue queue;
        volatile OfficialWhitelistFile.WriteHook fileHook=phase -> { };
        volatile Checked afterApply=() -> { }, beforeResolve=() -> { };
        final OfficialWhitelistQueue.GameAccess game=new OfficialWhitelistQueue.GameAccess() {
            public List<Profile> snapshotOnGame() { check(Thread.currentThread() == main); return List.copyOf(memory.values()); }
            public void applyOnGame(Profile profile,boolean enabled) throws Exception {
                check(Thread.currentThread() == main); applications.incrementAndGet();
                if (enabled) memory.put(profile.uuid(),profile); else memory.remove(profile.uuid()); afterApply.run();
            }
        };
        Case() throws Exception { profiles.put("player_a",PA); profiles.put("player_b",PB); Files.writeString(nativePath,"[]"); open(); }
        <T> T io(Callable<T> action) throws Exception {
            try { return worker.submit(action).get(5,TimeUnit.SECONDS); }
            catch (ExecutionException error) { if (error.getCause() instanceof Exception cause) throw cause; if (error.getCause() instanceof Error cause) throw cause; throw new AssertionError(); }
        }
        void open() throws Exception {
            store=new OfficialModState(root.resolve("official"),S,!Files.exists(root.resolve("official")),phase -> { if (ackFault.get() && phase.equals("before_replace")) throw new IOException("SYNTHETIC_PRIVATE_ACK"); });
            mirror=new OfficialMirrorService(S,store,new Confirmation() {
                public void committed(OfficialScope scope,String id,JsonArray effects) { check(scope.equals(S)); }
                public void snapshot(OfficialScope scope,JsonArray rows) { check(scope.equals(S)); }
            },() -> Thread.currentThread() == main);
            file=new OfficialWhitelistFile(nativePath,S,active::get,() -> Thread.currentThread() == main,phase -> { if (phase.equals("move_attempt")) writes.incrementAndGet(); fileHook.at(phase); });
            var initial=file.captureOnGame(List.copyOf(memory.values())); io(() -> file.initializeOnIo(initial)); queue=newQueue();
        }
        OfficialWhitelistQueue newQueue() { return new OfficialWhitelistQueue(S,mirror,file,gameId -> {
            check(Thread.currentThread() != main); lookups.add(gameId); beforeResolve.run(); return profiles.get(OfficialScope.key(gameId));
        },game,active::get,() -> Thread.currentThread() == main); }
        void seed(String id,String game,String state,String revision) throws Exception {
            JsonArray effects=new JsonArray(); effects.add(effect(game,state,revision)); io(() -> mirror.sync(S,id,effects));
        }
        JsonObject effect(String game,String state,String revision) {
            JsonObject row=new JsonObject(); row.addProperty("game_id",game); row.addProperty("game_key",OfficialScope.key(game));
            row.addProperty("user_id","SYNTHETIC_USER_A"); row.addProperty("state",state); row.addProperty("revision",revision); return row;
        }
        boolean stepIo() throws Exception { return io(queue::advanceOnIo); }
        void cycle() throws Exception { stepIo(); queue.advanceOnGame(); stepIo(); queue.advanceOnGame(); stepIo(); }
        String status(String id,String part) throws Exception { return io(() -> mirror.operationResults(id)).getAsJsonArray("results").get(0).getAsJsonObject().get(part).getAsString(); }
        void nativeSave() throws Exception {
            var snapshot=file.captureOnGame(List.copyOf(memory.values()));
            expect("RESULT_UNKNOWN",() -> file.nativeSave(snapshot,() -> Files.writeString(nativePath,json(memory.values()))));
            io(file::recoverOnIo);
        }
        void restart() throws Exception { queue.close(); mirror.close(); store.close(); file.close(); ackFault.set(false); open(); }
        @Override public void close() throws Exception { queue.close(); mirror.close(); store.close(); file.close(); worker.shutdownNow(); check(worker.awaitTermination(2,TimeUnit.SECONDS)); }
    }
    static String json(Collection<Profile> rows) { JsonArray result=new JsonArray(); for (Profile profile : rows) { JsonObject row=new JsonObject(); row.addProperty("uuid",profile.uuid().toString()); row.addProperty("name",profile.name()); result.add(row); } return new Gson().toJson(result); }
    public static void main(String[] args) throws Exception {
        test("memory_and_file_success_are_not_acked_before_final_game_verification",() -> {
            try (Case c=new Case()) {
                c.seed(A,"Player_A","active","1"); c.stepIo(); c.queue.advanceOnGame();
                check(c.memory.containsKey(PA.uuid()) && c.status(A,"whitelist").equals("PENDING"));
                c.stepIo(); check(c.status(A,"whitelist").equals("PENDING") && Files.readString(c.nativePath).contains("Player_A"));
                c.queue.advanceOnGame(); check(c.status(A,"whitelist").equals("PENDING")); c.stepIo();
                check(c.status(A,"whitelist").equals("APPLIED") && c.status(A,"online").equals("PENDING") && c.applications.get() == 1);
            }
        });
        test("one_queue_owner_and_explicit_thread_boundaries",() -> {
            try (Case c=new Case()) {
                expect("CONFLICT",c::newQueue); expect("NOT_SUPPORTED",c.queue::advanceOnIo);
                expect("CONFLICT",() -> c.io(() -> { c.queue.advanceOnGame(); return null; }));
                check(c.lookups.isEmpty() && c.applications.get() == 0);
            }
        });
        test("unknown_profile_does_not_mean_unbound_or_mutate_memory_file_or_ack",() -> {
            try (Case c=new Case()) {
                c.seed(A,"Player_A","active","1"); c.profiles.remove("player_a"); String revision=c.io(c.store::snapshot).get("store_revision").getAsString();
                c.cycle(); check(c.applications.get() == 0 && c.writes.get() == 0 && c.status(A,"whitelist").equals("PENDING"));
                check(c.io(c.store::snapshot).get("store_revision").getAsString().equals(revision));
            }
        });
        test("resolved_account_mismatch_is_conflict_without_a_native_action",() -> {
            try (Case c=new Case()) {
                c.seed(A,"Player_A","active","1"); c.profiles.put("player_a",PB); c.cycle();
                check(c.status(A,"whitelist").equals("CONFLICT") && c.applications.get() == 0 && c.writes.get() == 0);
            }
        });
        test("same_name_different_uuid_and_same_uuid_renamed_are_not_merged",() -> {
            for (Profile other : List.of(new Profile(PB.uuid(),"Player_A"),new Profile(PA.uuid(),"Other_Name"))) {
                try (Case c=new Case()) {
                    c.memory.put(other.uuid(),other); c.nativeSave(); c.seed(A,"Player_A","active","1"); c.cycle();
                    check(c.status(A,"whitelist").equals("CONFLICT") && c.applications.get() == 0 && c.memory.get(other.uuid()).equals(other));
                }
            }
        });
        test("one_account_revocation_keeps_the_same_users_other_account_and_native_entry",() -> {
            try (Case c=new Case()) {
                Profile extra=new Profile(UUID.randomUUID(),"Native_Extra"); c.memory.put(extra.uuid(),extra); c.nativeSave();
                c.seed(A,"Player_A","active","1"); c.cycle(); c.seed(B,"Player_B","active","1"); c.cycle();
                c.seed(C,"Player_A","revoked","2"); c.cycle();
                check(!c.memory.containsKey(PA.uuid()) && c.memory.containsKey(PB.uuid()) && c.memory.containsKey(extra.uuid()));
                check(c.status(C,"whitelist").equals("APPLIED") && !Files.readString(c.nativePath).contains("Player_A"));
            }
        });
        test("stale_ticket_before_game_never_applies_its_old_memory_action",() -> {
            try (Case c=new Case()) {
                c.seed(A,"Player_A","active","1"); c.stepIo(); c.seed(B,"Player_A","revoked","2");
                c.queue.advanceOnGame(); c.stepIo(); c.queue.advanceOnGame(); c.stepIo();
                check(c.applications.get() == 0 && c.writes.get() == 0 && c.status(A,"whitelist").equals("SUPERSEDED"));
            }
        });
        test("a_new_revision_before_file_write_discards_the_old_candidate_and_repairs_dirty_memory_first",() -> {
            try (Case c=new Case()) {
                c.seed(A,"Player_A","active","1"); c.stepIo(); c.queue.advanceOnGame(); c.seed(B,"Player_A","revoked","2");
                c.stepIo(); c.queue.advanceOnGame(); c.stepIo(); check(c.writes.get() == 0 && c.status(A,"whitelist").equals("SUPERSEDED"));
                c.cycle(); check(!c.memory.containsKey(PA.uuid()) && c.status(B,"whitelist").equals("APPLIED") && Files.readString(c.nativePath).equals("[]"));
            }
        });
        test("revision_change_during_a_preempted_game_action_cannot_persist_the_resurrected_entry",() -> {
            try (Case c=new Case()) {
                c.seed(A,"Player_A","active","1"); AtomicBoolean once=new AtomicBoolean();
                // Synthetic scheduling preemption; the sync itself runs on I/O.
                c.afterApply=() -> { if (!once.getAndSet(true)) c.seed(B,"Player_A","revoked","2"); };
                c.cycle(); check(c.writes.get() == 0); c.afterApply=() -> { }; c.cycle();
                check(c.status(A,"whitelist").equals("SUPERSEDED") && c.status(B,"whitelist").equals("APPLIED") && !c.memory.containsKey(PA.uuid()));
            }
        });
        test("unknown_file_commit_is_queried_without_reapplying_memory_or_rewriting",() -> {
            try (Case c=new Case()) {
                AtomicBoolean once=new AtomicBoolean(); c.fileHook=phase -> { if (phase.equals("after_replace") && !once.getAndSet(true)) throw new IOException(); };
                c.seed(A,"Player_A","active","1"); c.stepIo(); c.queue.advanceOnGame(); expect("RESULT_UNKNOWN",c::stepIo);
                check(c.status(A,"whitelist").equals("PENDING")); c.stepIo(); c.queue.advanceOnGame(); c.stepIo();
                check(c.status(A,"whitelist").equals("APPLIED") && c.applications.get() == 1 && c.writes.get() == 1);
            }
        });
        test("known_not_applied_file_result_waits_for_a_new_bounded_correction_cycle",() -> {
            try (Case c=new Case()) {
                AtomicBoolean once=new AtomicBoolean(); c.fileHook=phase -> { if (phase.equals("move_attempt") && !once.getAndSet(true)) throw new IOException(); };
                c.seed(A,"Player_A","active","1"); c.stepIo(); c.queue.advanceOnGame(); expect("RESULT_UNKNOWN",c::stepIo);
                c.stepIo(); c.queue.advanceOnGame(); c.stepIo();
                check(c.status(A,"whitelist").equals("PENDING") && c.writes.get() == 1 && c.applications.get() == 1);
                c.cycle(); check(c.status(A,"whitelist").equals("APPLIED") && c.writes.get() == 2);
            }
        });
        test("newer_file_snapshot_is_not_a_business_superseded_ack",() -> {
            try (Case c=new Case()) {
                c.seed(A,"Player_A","active","1"); c.stepIo(); c.queue.advanceOnGame(); c.memory.remove(PA.uuid()); c.nativeSave();
                c.stepIo(); c.queue.advanceOnGame(); c.stepIo();
                check(c.status(A,"whitelist").equals("PENDING") && c.writes.get() == 0);
                c.cycle(); check(c.status(A,"whitelist").equals("APPLIED"));
            }
        });
        test("a_newer_native_save_preserves_unrelated_changes_without_old_file_overwrite",() -> {
            try (Case c=new Case()) {
                c.seed(A,"Player_A","active","1"); c.stepIo(); c.queue.advanceOnGame(); c.memory.put(PB.uuid(),PB); c.nativeSave();
                c.stepIo(); c.queue.advanceOnGame(); c.stepIo();
                check(c.status(A,"whitelist").equals("APPLIED") && c.writes.get() == 0 && Files.readString(c.nativePath).contains("Player_B"));
            }
        });
        test("ack_failure_keeps_receipts_and_restart_reconciles_without_new_business_revision",() -> {
            try (Case c=new Case()) {
                c.seed(A,"Player_A","active","1"); c.stepIo(); c.queue.advanceOnGame(); c.stepIo(); c.queue.advanceOnGame(); c.ackFault.set(true);
                expect("RESULT_UNKNOWN",c::stepIo); expect("STORAGE_ERROR",c::stepIo); check(c.applications.get() == 1 && c.writes.get() == 1);
                c.restart(); c.cycle(); check(c.status(A,"whitelist").equals("APPLIED") && c.io(() -> c.mirror.operationResults(A)).getAsJsonArray("results").get(0).getAsJsonObject().get("requested_revision").getAsString().equals("1"));
            }
        });
        test("a_missing_resolver_for_one_account_does_not_block_another_valid_target",() -> {
            try (Case c=new Case()) {
                JsonArray effects=new JsonArray(); effects.add(c.effect("Player_A","active","1")); effects.add(c.effect("Player_B","active","1")); c.io(() -> c.mirror.sync(S,A,effects)); c.profiles.remove("player_a"); c.cycle();
                var results=c.io(() -> c.mirror.operationResults(A)).getAsJsonArray("results");
                check(results.get(0).getAsJsonObject().get("whitelist").getAsString().equals("PENDING") && results.get(1).getAsJsonObject().get("whitelist").getAsString().equals("APPLIED"));
                check(!c.memory.containsKey(PA.uuid()) && c.memory.containsKey(PB.uuid()));
            }
        });
        test("bounded_batches_rotate_and_do_not_execute_online_effects",() -> {
            try (Case c=new Case()) {
                JsonArray effects=new JsonArray();
                for (int i=0;i<6;i++) { String name="Player_"+i; c.profiles.put(OfficialScope.key(name),new Profile(new UUID(1,i+1),name)); effects.add(c.effect(name,"active","1")); }
                c.io(() -> c.mirror.sync(S,A,effects)); c.cycle(); check(c.lookups.size() == 4 && c.memory.size() == 4);
                c.cycle(); check(c.lookups.size() == 6 && c.memory.size() == 6 && !c.queue.needsIo());
                for (var result : c.io(() -> c.mirror.operationResults(A)).getAsJsonArray("results")) check(result.getAsJsonObject().get("online").getAsString().equals("PENDING"));
            }
        });
        test("closing_during_a_resolver_retains_owner_until_quiescent_and_dispatches_no_old_action",() -> {
            try (Case c=new Case()) {
                c.seed(A,"Player_A","active","1"); CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
                c.beforeResolve=() -> { entered.countDown(); check(release.await(2,TimeUnit.SECONDS)); };
                Future<Boolean> lookup=c.worker.submit(c.queue::advanceOnIo); check(entered.await(1,TimeUnit.SECONDS));
                c.queue.close(); try { expect("CONFLICT",c::newQueue); } finally { release.countDown(); }
                lookup.get(3,TimeUnit.SECONDS); c.queue.advanceOnGame(); check(c.applications.get() == 0 && !c.queue.needsGame());
                c.beforeResolve=() -> { }; c.queue=c.newQueue(); c.cycle(); check(c.status(A,"whitelist").equals("APPLIED"));
            }
        });
        test("file_lease_orders_later_mirror_publish_while_game_fast_reads_stay_nonblocking",() -> {
            try (Case c=new Case()) {
                c.seed(A,"Player_A","active","1"); c.stepIo(); c.queue.advanceOnGame();
                CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
                c.fileHook=phase -> { if (phase.equals("before_replace")) { entered.countDown(); try { release.await(2,TimeUnit.SECONDS); } catch (InterruptedException error) { throw new IOException(); } } };
                Future<Boolean> writing=c.worker.submit(c.queue::advanceOnIo); check(entered.await(1,TimeUnit.SECONDS));
                ExecutorService sync=Executors.newSingleThreadExecutor(); JsonArray revoked=new JsonArray(); revoked.add(c.effect("Player_A","revoked","2"));
                Future<?> newer=sync.submit(() -> c.mirror.sync(S,B,revoked));
                try { long start=System.nanoTime(); check(c.mirror.isBoundFast("Player_A") && System.nanoTime()-start < 1_000_000_000L); check(!newer.isDone()); }
                finally { release.countDown(); }
                writing.get(3,TimeUnit.SECONDS); newer.get(3,TimeUnit.SECONDS); sync.shutdownNow(); check(sync.awaitTermination(1,TimeUnit.SECONDS));
                c.queue.advanceOnGame(); c.stepIo(); c.fileHook=phase -> { }; c.cycle(); check(!c.memory.containsKey(PA.uuid()) && c.status(B,"whitelist").equals("APPLIED"));
            }
        });
        System.out.println("OfficialWhitelistQueueTests passed="+passed);
    }
}
