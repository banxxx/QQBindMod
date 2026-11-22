package com.poso.qqbind.official;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static com.poso.qqbind.official.OfficialMirrorService.*;
import com.poso.qqbind.official.OfficialWhitelistFile.Profile;
import com.poso.qqbind.official.OfficialWhitelistFile.Snapshot;

/** Shared staged whitelist pipeline. No Minecraft/driver imports or owned threads.
 * The runtime schedules this after a loader installs its native whitelist host.
 */
public final class OfficialWhitelistQueue implements AutoCloseable {
    @FunctionalInterface public interface ProfileResolver { Profile resolveOnIo(String gameId) throws Exception; }
    public interface GameAccess {
        List<Profile> snapshotOnGame() throws Exception;
        void applyOnGame(Profile profile, boolean active) throws Exception;
        default void validateProfileOnGame(Profile profile) throws Exception { Objects.requireNonNull(profile); }
    }
    private interface Step { }
    private record Resolved(Ticket ticket, Profile profile, Outcome problem) { }
    private record Prepared(List<Resolved> rows) implements Step { }
    private record Work(Execution execution, Profile profile, boolean ran) { }
    private record ToDisk(List<Work> work, Snapshot snapshot, boolean attempted) implements Step { }
    private record ToVerify(List<Work> work, Snapshot snapshot) implements Step { }
    private record ToAck(List<Execution> executions) implements Step { }
    private static final Step CLOSED=new Step() { };
    private final OfficialMirrorService mirror;
    private final OfficialWhitelistFile file;
    private final ProfileResolver resolver;
    private final GameAccess game;
    private final BooleanSupplier active,onGameThread;
    private final AtomicReference<Step> handoff=new AtomicReference<>();
    private final AtomicBoolean ioBusy=new AtomicBoolean(),gameBusy=new AtomicBoolean();
    private volatile boolean closed;
    private volatile Set<String> dirty=Set.of();
    private String cursor;

    public OfficialWhitelistQueue(OfficialScope scope, OfficialMirrorService mirror, OfficialWhitelistFile file,
                                  ProfileResolver resolver, GameAccess game, BooleanSupplier active, BooleanSupplier onGameThread) {
        this.mirror=Objects.requireNonNull(mirror); this.file=Objects.requireNonNull(file);
        this.resolver=Objects.requireNonNull(resolver); this.game=Objects.requireNonNull(game);
        this.active=Objects.requireNonNull(active); this.onGameThread=Objects.requireNonNull(onGameThread);
        if (!mirror.matchesScope(scope) || !file.matchesScope(scope)) throw new OfficialException("SCOPE_MISMATCH");
        if (!sourceActive() || !file.readyFast()) throw new OfficialException("NOT_SUPPORTED");
        mirror.claimWhitelist(this);
    }
    private boolean sourceActive() { try { return !closed && active.getAsBoolean(); } catch (RuntimeException ignored) { return false; } }
    private boolean running() { return sourceActive() && mirror.ownsWhitelist(this); }
    private void guard() { if (!running()) throw new OfficialException("NOT_SUPPORTED"); }
    private boolean gameThread() { try { return onGameThread.getAsBoolean(); } catch (RuntimeException ignored) { throw new OfficialException("NOT_SUPPORTED"); } }
    public boolean needsIo() {
        if (!running()) return false; Step step=handoff.get();
        return file.needsRecoveryFast() || step instanceof ToDisk || step instanceof ToAck || step == null && mirror.hasPendingWhitelist();
    }
    public boolean needsGame() { return running() && (handoff.get() instanceof Prepared || handoff.get() instanceof ToVerify); }

    /** One bounded stage. false requests the host's existing failure backoff. */
    public boolean advanceOnIo() {
        if (gameThread()) throw new OfficialException("NOT_SUPPORTED");
        if (!ioBusy.compareAndSet(false,true)) return false;
        try {
            if (gameBusy.get() || !running()) return false;
            Step step=handoff.get();
            if (file.needsRecoveryFast()) {
                var receipt=file.recoverOnIo();
                if (step instanceof ToDisk disk && disk.attempted()) {
                    handoff.compareAndSet(step,new ToVerify(disk.work(),disk.snapshot()));
                    return receipt.outcome() != OfficialWhitelistFile.Outcome.NOT_APPLIED;
                }
            }
            guard();
            if (step instanceof ToAck receipts) {
                var ready=receipts.executions().stream().filter(e -> e.outcome() != Outcome.PENDING).toList();
                if (!ready.isEmpty()) mirror.acknowledgeBatch(ready);
                handoff.compareAndSet(step,null);
                return receipts.executions().stream().allMatch(e -> e.outcome() != Outcome.PENDING && e.outcome() != Outcome.FAILED);
            }
            if (step instanceof ToDisk disk) {
                ToDisk attempted=new ToDisk(disk.work(),disk.snapshot(),true);
                if (!handoff.compareAndSet(step,attempted)) return false;
                try {
                    var tickets=disk.work().stream().filter(Work::ran).map(w -> w.execution().ticket()).toList();
                    mirror.withWhitelistFile(this,tickets,() -> {
                        guard(); if (!file.currentCapture(disk.snapshot())) return null;
                        return file.writeOnIo(disk.snapshot());
                    });
                    handoff.compareAndSet(attempted,new ToVerify(disk.work(),disk.snapshot())); return true;
                } catch (OfficialException error) {
                    if (error.code().equals("CONFLICT")) { handoff.compareAndSet(attempted,new ToVerify(disk.work(),disk.snapshot())); return false; }
                    // Unknown retains the exact candidate. Query before any retry.
                    if (!file.needsRecoveryFast()) handoff.compareAndSet(attempted,new ToVerify(disk.work(),disk.snapshot()));
                    throw error;
                }
            }
            if (step != null || !mirror.hasPendingWhitelist()) return true;
            if (!file.readyFast()) throw new OfficialException("STORAGE_ERROR");
            var tickets=mirror.pendingWhitelist(cursor,dirty);
            if (tickets.isEmpty() && !dirty.isEmpty()) throw new OfficialException("CONFLICT");
            List<Resolved> rows=new ArrayList<>(tickets.size()); boolean good=true;
            for (Ticket ticket : tickets) {
                guard(); Profile profile=null; Outcome problem=null;
                try {
                    profile=resolver.resolveOnIo(ticket.gameId());
                    if (profile == null) problem=Outcome.PENDING;
                    else if (!ticket.gameKey().equals(key(profile))) problem=Outcome.CONFLICT;
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); problem=Outcome.PENDING; }
                catch (Exception ignored) { problem=Outcome.FAILED; }
                rows.add(new Resolved(ticket,profile,problem)); good &= problem == null;
            }
            if (!rows.isEmpty() && handoff.compareAndSet(null,new Prepared(List.copyOf(rows)))) {
                var last=tickets.get(tickets.size()-1); cursor=OfficialMirrorModel.taskKey(last.operationId(),last.gameKey());
            }
            return good;
        } finally { ioBusy.set(false); releaseClosed(); }
    }

    public void advanceOnGame() {
        if (!gameThread()) throw new OfficialException("CONFLICT");
        if (!gameBusy.compareAndSet(false,true)) return;
        try {
            if (ioBusy.get() || !running()) return; Step step=handoff.get();
            if (step instanceof Prepared batch) {
                List<Work> work=new ArrayList<>(batch.rows().size()); boolean mutated=false,safe=true;
                for (Resolved row : batch.rows()) {
                    if (!running() || handoff.get() != batch) return;
                    Outcome status=mirror.whitelistStatusOnGame(row.ticket());
                    if (status != null || row.problem() != null || !file.readyFast()) {
                        Outcome result=status != null ? status : row.problem() != null ? row.problem() : Outcome.PENDING;
                        work.add(new Work(mirror.completeWhitelistOnGame(row.ticket(),result),row.profile(),false)); continue;
                    }
                    try { validateBefore(row.ticket(),row.profile(),snapshotMap()); }
                    catch (Exception error) {
                        Outcome problem=error instanceof OfficialException o && o.code().equals("CONFLICT") ? Outcome.CONFLICT : Outcome.FAILED;
                        work.add(new Work(mirror.completeWhitelistOnGame(row.ticket(),problem),row.profile(),false)); continue;
                    }
                    boolean[] ran={false}; Outcome[] problem={null};
                    Execution execution=mirror.execute(row.ticket(),new GameEffects() {
                        public void whitelist(String gameId,boolean enabled) throws Exception {
                            guard(); ran[0]=true; markDirty(row.ticket().gameKey()); game.applyOnGame(row.profile(),enabled);
                            if (!matches(row.ticket(),row.profile(),snapshotMap())) { problem[0]=Outcome.CONFLICT; throw new OfficialException("CONFLICT"); }
                        }
                        public void online(String gameId,boolean enabled) { throw new OfficialException("NOT_SUPPORTED"); }
                    });
                    if (problem[0] != null && execution.outcome() == Outcome.FAILED) execution=mirror.completeWhitelistOnGame(row.ticket(),problem[0]);
                    work.add(new Work(execution,row.profile(),ran[0])); mutated |= ran[0]; safe &= !ran[0] || execution.outcome() == Outcome.APPLIED;
                }
                Snapshot candidate=null;
                var coherent=new HashSet<String>();
                for (Work row : work) if (row.ran() && row.execution().outcome() == Outcome.APPLIED) coherent.add(row.execution().ticket().gameKey());
                if (mutated && safe && coherent.containsAll(dirty)) { try { candidate=file.captureOnGame(new ArrayList<>(snapshotMap().values())); } catch (Exception ignored) { } }
                if (candidate != null) handoff.compareAndSet(batch,new ToDisk(List.copyOf(work),candidate,false));
                else handoff.compareAndSet(batch,new ToVerify(List.copyOf(work),null));
            } else if (step instanceof ToVerify written) {
                List<Execution> results=new ArrayList<>(written.work().size()); Map<UUID,Profile> memory=null;
                try { memory=snapshotMap(); } catch (Exception ignored) { }
                Snapshot confirmed=file.lastDurableFast();
                for (Work work : written.work()) {
                    if (!running() || handoff.get() != step) return;
                    Outcome result=work.execution().outcome();
                    if (work.ran() && result == Outcome.APPLIED) {
                        boolean persisted=written.snapshot() != null && confirmed != null && confirmed.sequence() >= written.snapshot().sequence();
                        try { game.validateProfileOnGame(work.profile()); } catch (Exception ignored) { persisted=false; }
                        result=persisted && memory != null && matches(work.execution().ticket(),work.profile(),memory)
                            && matches(work.execution().ticket(),work.profile(),map(confirmed.profiles())) ? Outcome.APPLIED : Outcome.PENDING;
                    }
                    Execution complete=mirror.completeWhitelistOnGame(work.execution().ticket(),result);
                    results.add(complete);
                    if (work.ran() && result == Outcome.APPLIED && complete.outcome() == Outcome.APPLIED) clearDirty(work.execution().ticket().gameKey());
                }
                handoff.compareAndSet(step,new ToAck(List.copyOf(results)));
            }
        } finally { gameBusy.set(false); releaseClosed(); }
    }
    private Map<UUID,Profile> snapshotMap() throws Exception { return map(game.snapshotOnGame()); }
    private void markDirty(String key) { var next=new HashSet<>(dirty); next.add(key); dirty=Set.copyOf(next); }
    private void clearDirty(String key) { var next=new HashSet<>(dirty); next.remove(key); dirty=Set.copyOf(next); }
    private static Map<UUID,Profile> map(List<Profile> rows) {
        if (rows == null || rows.size() > OfficialWhitelistFile.MAX_PROFILES) throw new OfficialException("LIMIT_EXCEEDED");
        Map<UUID,Profile> result=new HashMap<>();
        for (Profile row : rows) if (row == null || result.put(row.uuid(),row) != null) throw new OfficialException("CONFLICT");
        return result;
    }
    private static String key(Profile profile) { try { return OfficialScope.key(profile.name()); } catch (OfficialException ignored) { return null; } }
    private static void validateBefore(Ticket ticket, Profile profile, Map<UUID,Profile> rows) {
        Profile current=rows.get(profile.uuid());
        if (current != null && !ticket.gameKey().equals(key(current))) throw new OfficialException("CONFLICT");
        for (Profile row : rows.values()) if (ticket.gameKey().equals(key(row)) && !profile.uuid().equals(row.uuid())) throw new OfficialException("CONFLICT");
        if (ticket.state().equals("active") && current == null && rows.size() == OfficialWhitelistFile.MAX_PROFILES) throw new OfficialException("LIMIT_EXCEEDED");
    }
    private static boolean matches(Ticket ticket, Profile profile, Map<UUID,Profile> rows) {
        if (profile == null) return false;
        boolean active=ticket.state().equals("active"); Profile current=rows.get(profile.uuid());
        if (active && (current == null || !ticket.gameKey().equals(key(current))) || !active && current != null) return false;
        for (Profile row : rows.values()) if (ticket.gameKey().equals(key(row)) && (!active || !profile.uuid().equals(row.uuid()))) return false;
        return true;
    }
    private void releaseClosed() { if (closed && !ioBusy.get() && !gameBusy.get()) mirror.releaseWhitelist(this); }
    @Override public void close() { closed=true; handoff.set(CLOSED); releaseClosed(); }
    @Override public String toString() { return "OfficialWhitelistQueue[redacted]"; }
}
