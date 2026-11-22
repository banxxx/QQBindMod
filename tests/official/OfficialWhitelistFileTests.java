package com.poso.qqbind.official;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.poso.qqbind.official.OfficialWhitelistFile.*;

/** Only new temp directories and synthetic UUID/name arrays; no game or real data. */
public final class OfficialWhitelistFileTests {
    private static final OfficialScope S=new OfficialScope("11111111-1111-4111-8111-111111111111","SYNTHETIC_GROUP_A","33333333-3333-4333-8333-333333333333");
    private static final Profile A=new Profile(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),"Synthetic_A");
    private static final Profile B=new Profile(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),"Synthetic_B");
    private static final Profile C=new Profile(UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),"Synthetic_C");
    private static int passed;
    interface Checked { void run() throws Exception; }
    static void check(boolean value) { if (!value) throw new AssertionError("Synthetic whitelist-file assertion failed"); }
    static void expect(String code, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected "+code); }
        catch (OfficialException error) { check(error.code().equals(code)); }
    }
    static void test(String name, Checked action) throws Exception { action.run(); passed++; System.out.println("PASS "+name); }
    static String json(List<Profile> profiles) {
        JsonArray array=new JsonArray();
        for (Profile profile : profiles) { JsonObject row=new JsonObject(); row.addProperty("uuid",profile.uuid().toString()); row.addProperty("name",profile.name()); array.add(row); }
        return new GsonBuilder().disableHtmlEscaping().create().toJson(array);
    }
    static final class Case implements AutoCloseable {
        final Path directory=Files.createTempDirectory("official-whitelist-file-synthetic-");
        final Path path=directory.resolve("whitelist.json");
        final Thread game=Thread.currentThread();
        final AtomicBoolean active=new AtomicBoolean(true);
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final OfficialWhitelistFile file;
        Case() throws Exception { this(phase -> { }); }
        Case(OfficialWhitelistFile.WriteHook hook) throws Exception { file=new OfficialWhitelistFile(path,S,active::get,() -> Thread.currentThread() == game,hook); }
        <T> T io(Callable<T> action) throws Exception { return await(worker.submit(action)); }
        Snapshot capture(Profile... profiles) { return file.captureOnGame(List.of(profiles)); }
        Snapshot initialize(Profile... profiles) throws Exception {
            Files.writeString(path,json(List.of(profiles)),StandardCharsets.UTF_8);
            Snapshot initial=capture(profiles); io(() -> file.initializeOnIo(initial)); return initial;
        }
        String text() throws Exception { return Files.readString(path,StandardCharsets.UTF_8); }
        @Override public void close() throws Exception { file.close(); worker.shutdownNow(); check(worker.awaitTermination(2,TimeUnit.SECONDS)); }
    }
    static <T> T await(Future<T> future) throws Exception {
        try { return future.get(5,TimeUnit.SECONDS); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new AssertionError("Unexpected synthetic worker failure");
        }
    }

    public static void main(String[] args) throws Exception {
        test("construction_and_capture_are_memory_only_and_redacted",() -> {
            try (Case c=new Case()) {
                List<Profile> input=new ArrayList<>(List.of(A)); Snapshot snapshot=c.file.captureOnGame(input); input.add(B);
                check(snapshot.profiles().size() == 1 && !Files.exists(c.path));
                check(!Files.exists(c.directory.resolve(".whitelist.qqbind-official.lock")));
                check(!c.file.readyFast() && c.file.lastDurableFast() == null);
                check(!snapshot.toString().contains(A.name()) && !c.file.toString().contains(c.directory.toString()));
                try { snapshot.profiles().clear(); throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
            }
        });
        test("only_explicit_whitelist_filename_and_existing_parent_are_accepted",() -> {
            try (Case c=new Case()) {
                expect("INVALID_INPUT",() -> new OfficialWhitelistFile(c.directory.resolve("other.json"),S,() -> true,() -> true));
                var missing=new OfficialWhitelistFile(c.directory.resolve("missing").resolve("whitelist.json"),S,() -> true,() -> Thread.currentThread() == c.game);
                Snapshot snapshot=missing.captureOnGame(List.of());
                expect("STORAGE_ERROR",() -> c.io(() -> missing.initializeOnIo(snapshot))); missing.close();
                check(!Files.exists(c.directory.resolve("missing")));
            }
        });
        test("file_io_and_native_hooks_reject_wrong_thread",() -> {
            try (Case c=new Case()) {
                Snapshot snapshot=c.capture();
                expect("NOT_SUPPORTED",() -> c.file.initializeOnIo(snapshot));
                Files.writeString(c.path,"[]");
                c.io(() -> c.file.initializeOnIo(snapshot));
                expect("NOT_SUPPORTED",() -> c.file.writeOnIo(snapshot));
                expect("CONFLICT",() -> c.io(() -> c.file.captureOnGame(List.of(A))));
                AtomicInteger callbacks=new AtomicInteger();
                expect("CONFLICT",() -> c.io(() -> c.file.nativeSave(snapshot,() -> callbacks.incrementAndGet())));
                expect("CONFLICT",() -> c.io(() -> c.file.nativeLoad(() -> { callbacks.incrementAndGet(); return snapshot; })));
                check(callbacks.get() == 0);
            }
        });
        test("initial_disk_must_match_the_native_memory_snapshot",() -> {
            try (Case c=new Case()) {
                String before=json(List.of(A)); Files.writeString(c.path,before); Snapshot empty=c.capture();
                expect("CONFLICT",() -> c.io(() -> c.file.initializeOnIo(empty)));
                check(c.text().equals(before) && !c.file.readyFast());
            }
        });
        test("unconfirmed_initial_or_native_reload_flush_never_publishes_a_durable_receipt",() -> {
            AtomicBoolean fail=new AtomicBoolean(true);
            try (Case c=new Case(phase -> { if (phase.equals("after_file_force") && fail.get()) throw new IOException(); })) {
                Files.writeString(c.path,json(List.of(A))); Snapshot initial=c.capture(A);
                expect("STORAGE_ERROR",() -> c.io(() -> c.file.initializeOnIo(initial)));
                check(!c.file.readyFast() && c.text().equals(json(List.of(A))));
                fail.set(false); c.io(() -> c.file.initializeOnIo(initial));
                Files.writeString(c.path,json(List.of(B))); fail.set(true);
                expect("RESULT_UNKNOWN",() -> c.file.nativeLoad(() -> c.capture(B)));
                expect("RESULT_UNKNOWN",() -> c.io(c.file::recoverOnIo));
                check(c.file.lastDurableFast() == null && c.text().equals(json(List.of(B))));
                fail.set(false); check(c.io(c.file::recoverOnIo).outcome() == Outcome.APPLIED && c.file.readyFast());
            }
        });
        test("missing_initial_file_requires_explicit_opt_in_and_cannot_hide_file_loss",() -> {
            try (Case c=new Case()) {
                Snapshot empty=c.capture();
                expect("STORAGE_ERROR",() -> c.io(() -> c.file.initializeOnIo(empty)));
                c.io(() -> c.file.initializeOnIo(empty,true));
                check(c.file.readyFast() && !Files.exists(c.path));
                Receipt saved=c.io(() -> c.file.writeOnIo(empty));
                check(saved.outcome() == Outcome.APPLIED && c.text().equals("[]"));
                check(!Files.exists(c.directory.resolve("official-state.json")));
                Files.delete(c.path);
                AtomicInteger loads=new AtomicInteger();
                expect("RESULT_UNKNOWN",() -> c.file.nativeLoad(() -> { loads.incrementAndGet(); return c.capture(); }));
                check(loads.get() == 1 && !c.file.readyFast());
                expect("CONFLICT",() -> c.io(c.file::recoverOnIo));
                check(!Files.exists(c.path));
                c.file.close();
                try (var reopened=new OfficialWhitelistFile(c.path,S,() -> true,() -> Thread.currentThread() == c.game)) {
                    Snapshot fresh=reopened.captureOnGame(List.of());
                    expect("STORAGE_ERROR",() -> c.io(() -> reopened.initializeOnIo(fresh)));
                    check(!Files.exists(c.path));
                }
            }
            try (Case c=new Case()) { Snapshot nonempty=c.capture(A); expect("CONFLICT",() -> c.io(() -> c.file.initializeOnIo(nonempty,true))); check(!Files.exists(c.path)); }
        });
        test("a_second_writer_cannot_take_over_the_same_native_file",() -> {
            try (Case c=new Case()) {
                c.initialize(); var second=new OfficialWhitelistFile(c.path,S,() -> true,() -> Thread.currentThread() == c.game);
                Snapshot empty=second.captureOnGame(List.of());
                expect("CONFLICT",() -> c.io(() -> second.initializeOnIo(empty))); second.close(); check(c.file.readyFast());
            }
        });
        test("snapshots_are_bound_to_the_exact_coordinator_not_just_scope",() -> {
            try (Case first=new Case(); Case second=new Case()) {
                Snapshot foreign=second.capture();
                expect("SCOPE_MISMATCH",() -> first.io(() -> first.file.initializeOnIo(foreign)));
                first.initialize();
                expect("SCOPE_MISMATCH",() -> first.io(() -> first.file.writeOnIo(foreign)));
                check(first.text().equals("[]"));
            }
        });
        test("uuid_name_shape_unicode_and_unrelated_entries_are_preserved",() -> {
            Profile extra=new Profile(C.uuid(),"合成原版条目\"\\🙂");
            try (Case c=new Case()) {
                c.initialize(extra); Snapshot next=c.capture(A,extra); Receipt saved=c.io(() -> c.file.writeOnIo(next));
                JsonArray data=JsonParser.parseString(c.text()).getAsJsonArray();
                check(data.size() == 2 && saved.durable().profiles().contains(extra));
                for (var element : data) check(element.getAsJsonObject().keySet().equals(Set.of("uuid","name")));
                Snapshot removed=c.capture(extra); c.io(() -> c.file.writeOnIo(removed));
                check(JsonParser.parseString(c.text()).getAsJsonArray().size() == 1 && c.file.lastDurableFast().profiles().equals(List.of(extra)));
            }
        });
        test("unrelated_duplicate_names_are_not_merged_or_rebound",() -> {
            Profile sameName=new Profile(B.uuid(),A.name());
            try (Case c=new Case()) { c.initialize(A,sameName); Snapshot next=c.capture(A,sameName,C); c.io(() -> c.file.writeOnIo(next)); check(c.file.lastDurableFast().profiles().size() == 3); }
        });
        test("older_queued_snapshot_cannot_overwrite_a_newer_confirmed_file",() -> {
            try (Case c=new Case()) {
                c.initialize(); Snapshot old=c.capture(A), latest=c.capture(B); c.io(() -> c.file.writeOnIo(latest));
                String before=c.text(); Receipt skipped=c.io(() -> c.file.writeOnIo(old));
                check(skipped.outcome() == Outcome.NEWER_SNAPSHOT && skipped.durable().sequence() == latest.sequence() && c.text().equals(before));
            }
        });
        test("same_content_and_same_snapshot_are_idempotently_confirmed",() -> {
            try (Case c=new Case()) {
                Snapshot first=c.initialize(A); String before=c.text(); Snapshot next=c.capture(A);
                check(c.io(() -> c.file.writeOnIo(next)).outcome() == Outcome.APPLIED);
                check(c.io(() -> c.file.writeOnIo(next)).durable().sequence() == next.sequence());
                check(c.io(() -> c.file.writeOnIo(first)).outcome() == Outcome.NEWER_SNAPSHOT && c.text().equals(before));
            }
        });
        test("corrupt_duplicate_extra_or_noncanonical_rows_are_not_dropped",() -> {
            String a=json(List.of(A));
            for (String bad : List.of("{}","[{\"uuid\":\"x\",\"uuid\":\"y\",\"name\":\"bad\"}]",
                    a.substring(0,a.length()-1)+","+a.substring(1),
                    a.replace("\"name\":","\"extra\":true,\"name\":"),
                    a.replace(A.uuid().toString(),"1-1-1-1-1"), a.replace("\"Synthetic_A\"","null"),
                    a.replace("Synthetic_A","\\ud800"), a+" true")) {
                try (Case c=new Case()) {
                    Files.writeString(c.path,bad); Snapshot empty=c.capture();
                    expect("STORAGE_ERROR",() -> c.io(() -> c.file.initializeOnIo(empty))); check(c.text().equals(bad));
                }
            }
        });
        test("invalid_utf8_and_excess_bytes_fail_without_repairing_the_file",() -> {
            for (byte[] raw : List.of(new byte[]{(byte)0xc3,(byte)0x28},new byte[MAX_BYTES+1])) {
                try (Case c=new Case()) {
                    Files.write(c.path,raw); Snapshot empty=c.capture();
                    expect(raw.length > MAX_BYTES ? "LIMIT_EXCEEDED" : "STORAGE_ERROR",() -> c.io(() -> c.file.initializeOnIo(empty)));
                    check(Arrays.equals(Files.readAllBytes(c.path),raw));
                }
            }
        });
        test("profile_count_and_duplicate_uuid_input_are_bounded",() -> {
            try (Case c=new Case()) {
                expect("LIMIT_EXCEEDED",() -> c.file.captureOnGame(Collections.nCopies(MAX_PROFILES+1,A)));
                expect("CONFLICT",() -> c.file.captureOnGame(List.of(A,A)));
                Files.writeString(c.path,"["+json(List.of(A)).substring(1,json(List.of(A)).length()-1).concat(",").repeat(MAX_PROFILES)+json(List.of(A)).substring(1));
                Snapshot empty=c.capture(); expect("LIMIT_EXCEEDED",() -> c.io(() -> c.file.initializeOnIo(empty)));
            }
        });
        test("record_limit_stops_before_building_or_parsing_an_extra_object",() -> {
            try (Case c=new Case()) {
                String row=json(List.of(A)); row=row.substring(1,row.length()-1);
                String oversized="["+(row+",").repeat(MAX_PROFILES)+"{";
                Files.writeString(c.path,oversized); Snapshot empty=c.capture();
                expect("LIMIT_EXCEEDED",() -> c.io(() -> c.file.initializeOnIo(empty)));
                check(c.text().equals(oversized));
            }
        });
        test("external_content_or_format_changes_are_not_overwritten",() -> {
            try (Case c=new Case()) {
                c.initialize(); Snapshot next=c.capture(A); Files.writeString(c.path,"[]\n");
                expect("CONFLICT",() -> c.io(() -> c.file.writeOnIo(next))); check(c.text().equals("[]\n") && !c.file.readyFast());
                expect("STORAGE_ERROR",() -> c.io(() -> c.file.writeOnIo(next)));
                expect("RESULT_UNKNOWN",() -> c.file.nativeLoad(() -> c.capture()));
                check(c.io(c.file::recoverOnIo).outcome() == Outcome.APPLIED && c.file.readyFast());
            }
        });
        test("pre_replace_failure_preserves_the_original_and_allows_explicit_retry",() -> {
            AtomicBoolean once=new AtomicBoolean();
            try (Case c=new Case(phase -> { if (phase.equals("before_replace") && !once.getAndSet(true)) throw new IOException("SYNTHETIC_PRIVATE_PATH"); })) {
                c.initialize(); Snapshot next=c.capture(A);
                expect("STORAGE_ERROR",() -> c.io(() -> c.file.writeOnIo(next)));
                check(c.text().equals("[]") && c.file.readyFast() && !c.file.needsRecoveryFast());
                check(c.io(() -> c.file.writeOnIo(next)).outcome() == Outcome.APPLIED);
            }
        });
        test("unknown_move_attempt_is_queried_not_assumed_applied_or_replayed",() -> {
            AtomicBoolean once=new AtomicBoolean(); AtomicInteger attempts=new AtomicInteger();
            try (Case c=new Case(phase -> { if (phase.equals("move_attempt")) { attempts.incrementAndGet(); if (!once.getAndSet(true)) throw new IOException(); } })) {
                c.initialize(); Snapshot next=c.capture(A);
                expect("RESULT_UNKNOWN",() -> c.io(() -> c.file.writeOnIo(next)));
                check(c.file.needsRecoveryFast() && c.file.lastDurableFast() == null);
                Receipt recovered=c.io(c.file::recoverOnIo);
                check(recovered.outcome() == Outcome.NOT_APPLIED && attempts.get() == 1 && c.text().equals("[]"));
                c.io(() -> c.file.writeOnIo(next)); check(attempts.get() == 2);
            }
        });
        test("post_replace_unknown_is_confirmed_at_the_original_sequence",() -> {
            AtomicInteger moves=new AtomicInteger();
            try (Case c=new Case(phase -> { if (phase.equals("move_attempt")) moves.incrementAndGet(); if (phase.equals("after_replace")) throw new IOException(); })) {
                c.initialize(); Snapshot next=c.capture(A); expect("RESULT_UNKNOWN",() -> c.io(() -> c.file.writeOnIo(next)));
                check(!c.file.readyFast() && JsonParser.parseString(c.text()).getAsJsonArray().size() == 1);
                Receipt recovered=c.io(c.file::recoverOnIo);
                check(recovered.outcome() == Outcome.APPLIED && recovered.durable().sequence() == next.sequence() && moves.get() == 1 && c.file.readyFast());
            }
        });
        test("a_third_file_after_unknown_requires_explicit_native_reload_and_worker_confirmation",() -> {
            try (Case c=new Case(phase -> { if (phase.equals("after_replace")) throw new IOException(); })) {
                c.initialize(); Snapshot next=c.capture(A); expect("RESULT_UNKNOWN",() -> c.io(() -> c.file.writeOnIo(next)));
                Files.writeString(c.path,json(List.of(B))); expect("CONFLICT",() -> c.io(c.file::recoverOnIo)); check(!c.file.readyFast());
                expect("RESULT_UNKNOWN",() -> c.file.nativeLoad(() -> c.capture(B)));
                check(c.io(c.file::recoverOnIo).outcome() == Outcome.APPLIED && c.file.readyFast());
                check(c.file.readyFast() && c.io(() -> c.file.writeOnIo(next)).outcome() == Outcome.NEWER_SNAPSHOT && c.text().equals(json(List.of(B))));
            }
        });
        test("native_save_reports_unknown_then_worker_confirms_actual_callback_bytes",() -> {
            try (Case c=new Case()) {
                c.initialize(); Snapshot old=c.capture(A), nativeSnapshot=c.capture(A,B); AtomicInteger calls=new AtomicInteger();
                expect("RESULT_UNKNOWN",() -> c.file.nativeSave(nativeSnapshot,() -> { calls.incrementAndGet(); Files.writeString(c.path,json(List.of(A,B))); }));
                Receipt saved=c.io(c.file::recoverOnIo);
                check(saved.outcome() == Outcome.APPLIED && saved.durable().sequence() == nativeSnapshot.sequence());
                check(c.io(() -> c.file.writeOnIo(old)).outcome() == Outcome.NEWER_SNAPSHOT && calls.get() == 1 && c.text().equals(json(List.of(A,B))));
            }
        });
        test("unknown_native_save_does_not_reinvoke_its_callback",() -> {
            try (Case c=new Case()) {
                c.initialize(); Snapshot next=c.capture(A); AtomicInteger calls=new AtomicInteger();
                expect("RESULT_UNKNOWN",() -> c.file.nativeSave(next,() -> { calls.incrementAndGet(); Files.writeString(c.path,json(List.of(A))); throw new IOException("SYNTHETIC_PRIVATE"); }));
                Receipt recovered=c.io(c.file::recoverOnIo);
                check(recovered.outcome() == Outcome.APPLIED && calls.get() == 1 && recovered.durable().sequence() == next.sequence());
            }
        });
        test("native_save_failure_before_writing_is_not_reported_as_success",() -> {
            try (Case c=new Case()) {
                c.initialize(); Snapshot next=c.capture(A); AtomicInteger calls=new AtomicInteger();
                expect("RESULT_UNKNOWN",() -> c.file.nativeSave(next,() -> { calls.incrementAndGet(); throw new IOException(); }));
                check(c.io(c.file::recoverOnIo).outcome() == Outcome.NOT_APPLIED && calls.get() == 1 && c.text().equals("[]"));
            }
        });
        test("explicit_native_reload_adopts_only_a_matching_complete_snapshot",() -> {
            try (Case c=new Case()) {
                c.initialize(); Snapshot old=c.capture(A); Files.writeString(c.path,json(List.of(B))); AtomicInteger loads=new AtomicInteger();
                expect("RESULT_UNKNOWN",() -> c.file.nativeLoad(() -> { loads.incrementAndGet(); return c.capture(B); }));
                check(c.io(c.file::recoverOnIo).outcome() == Outcome.APPLIED);
                check(loads.get() == 1 && c.io(() -> c.file.writeOnIo(old)).outcome() == Outcome.NEWER_SNAPSHOT);
                expect("RESULT_UNKNOWN",() -> c.file.nativeLoad(() -> { Files.writeString(c.path,json(List.of(C))); return c.capture(C); }));
                check(c.io(c.file::recoverOnIo).outcome() == Outcome.APPLIED && c.file.readyFast() && c.text().equals(json(List.of(C))));
            }
        });
        test("native_reload_cannot_relabel_an_old_snapshot_as_a_fresh_load",() -> {
            try (Case c=new Case()) {
                c.initialize(); Snapshot stale=c.capture(A); Files.writeString(c.path,json(List.of(A)));
                expect("CONFLICT",() -> c.file.nativeLoad(() -> stale));
                check(c.file.readyFast() && c.text().equals(json(List.of(A))));
            }
        });
        test("failing_host_guards_are_sanitized_and_never_assume_an_io_thread",() -> {
            try (Case c=new Case()) {
                AtomicBoolean sourceBroken=new AtomicBoolean(), threadBroken=new AtomicBoolean();
                try (var file=new OfficialWhitelistFile(c.path,S,() -> { if (sourceBroken.get()) throw new IllegalStateException("SYNTHETIC_PRIVATE_SOURCE"); return true; },
                        () -> { if (threadBroken.get()) throw new IllegalStateException("SYNTHETIC_PRIVATE_THREAD"); return Thread.currentThread() == c.game; })) {
                    Snapshot initial=file.captureOnGame(List.of()); sourceBroken.set(true);
                    check(!file.readyFast() && !file.needsRecoveryFast());
                    expect("NOT_SUPPORTED",() -> c.io(() -> file.initializeOnIo(initial))); sourceBroken.set(false); threadBroken.set(true);
                    expect("NOT_SUPPORTED",() -> c.io(() -> file.initializeOnIo(initial)));
                    check(!Files.exists(c.path));
                }
            }
        });
        test("revoked_source_is_checked_before_any_file_creation_or_replace",() -> {
            try (Case c=new Case()) {
                Snapshot empty=c.capture(); c.active.set(false); expect("NOT_SUPPORTED",() -> c.io(() -> c.file.initializeOnIo(empty)));
                check(!Files.exists(c.path) && !Files.exists(c.directory.resolve(".whitelist.qqbind-official.lock")));
            }
            AtomicReference<Case> owner=new AtomicReference<>();
            try (Case c=new Case(phase -> { if (phase.equals("before_replace")) owner.get().active.set(false); })) {
                owner.set(c); c.initialize(); Snapshot next=c.capture(A); expect("NOT_SUPPORTED",() -> c.io(() -> c.file.writeOnIo(next))); check(c.text().equals("[]"));
            }
        });
        test("fast_game_snapshot_does_not_wait_for_the_disk_mutex",() -> {
            CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
            try (Case c=new Case(phase -> { if (phase.equals("before_replace")) { entered.countDown(); try { release.await(2,TimeUnit.SECONDS); } catch (InterruptedException error) { throw new IOException(); } } })) {
                c.initialize(); Snapshot next=c.capture(A); Future<Receipt> writing=c.worker.submit(() -> c.file.writeOnIo(next));
                try {
                    check(entered.await(1,TimeUnit.SECONDS)); long start=System.nanoTime(); Snapshot later=c.capture(A,B);
                    check(c.file.lastDurableFast() != null && later.sequence() > next.sequence() && System.nanoTime()-start < 1_000_000_000L);
                } finally { release.countDown(); }
                await(writing);
            }
        });
        test("native_command_never_waits_behind_worker_file_io_or_partially_dispatches",() -> {
            CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1); AtomicBoolean replaced=new AtomicBoolean(); AtomicInteger calls=new AtomicInteger();
            try (Case c=new Case(phase -> {
                if (phase.equals("before_replace")) { entered.countDown(); try { release.await(2,TimeUnit.SECONDS); } catch (InterruptedException error) { throw new IOException(); } }
                if (phase.equals("after_replace")) replaced.set(true);
            })) {
                c.initialize(); Snapshot old=c.capture(A), latest=c.capture(A,B); Future<Receipt> writing=c.worker.submit(() -> c.file.writeOnIo(old)); check(entered.await(1,TimeUnit.SECONDS));
                Thread unlock=new Thread(() -> { try { Thread.sleep(50); } catch (InterruptedException ignored) { } release.countDown(); }); unlock.start();
                long start=System.nanoTime();
                try { expect("CONFLICT",() -> c.file.nativeSave(latest,() -> { calls.incrementAndGet(); Files.writeString(c.path,json(List.of(A,B))); }));
                    check(System.nanoTime()-start < 20_000_000L && calls.get() == 0); }
                finally { release.countDown(); unlock.join(1000); }
                await(writing); expect("RESULT_UNKNOWN",() -> c.file.nativeSave(latest,() -> { calls.incrementAndGet(); check(replaced.get()); Files.writeString(c.path,json(List.of(A,B))); }));
                check(c.io(c.file::recoverOnIo).outcome() == Outcome.APPLIED);
                check(c.file.lastDurableFast().sequence() == latest.sequence() && c.text().equals(json(List.of(A,B))) && calls.get() == 1);
            }
        });
        test("reopen_requires_a_fresh_owner_snapshot_and_keeps_the_native_contents",() -> {
            try (Case c=new Case()) {
                c.initialize(); Snapshot old=c.capture(A); c.io(() -> c.file.writeOnIo(old)); c.file.close();
                expect("NOT_SUPPORTED",() -> c.io(() -> c.file.writeOnIo(old))); check(c.file.lastDurableFast() == null);
                try (var reopened=new OfficialWhitelistFile(c.path,S,() -> true,() -> Thread.currentThread() == c.game)) {
                    Snapshot fresh=reopened.captureOnGame(List.of(A)); c.io(() -> reopened.initializeOnIo(fresh));
                    expect("SCOPE_MISMATCH",() -> c.io(() -> reopened.writeOnIo(old))); check(reopened.readyFast());
                }
            }
        });
        System.out.println("OfficialWhitelistFileTests passed="+passed);
    }
}
