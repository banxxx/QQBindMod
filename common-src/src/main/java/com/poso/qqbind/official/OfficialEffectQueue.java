package com.poso.qqbind.official;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** I/O captures/ACKs; the game thread executes. One bounded batch can be in flight. */
final class OfficialEffectQueue implements AutoCloseable {
    private interface Step { }
    private record Batch(List<OfficialMirrorService.Ticket> tickets) implements Step { }
    private record Receipts(List<OfficialMirrorService.Execution> executions) implements Step { }
    private static final Step CLOSED = new Step() { };

    private final OfficialMirrorService mirror;
    private final AtomicReference<Step> handoff = new AtomicReference<>();
    private String cursor;

    OfficialEffectQueue(OfficialMirrorService mirror) { this.mirror = mirror; }

    boolean needsIo() {
        Step current = handoff.get();
        return current instanceof Receipts || current == null && mirror.hasPendingOnline();
    }

    void captureOnIo() {
        Step current = handoff.get();
        if (current instanceof Receipts receipts) {
            // Leave the receipts in the handoff if persistence fails.
            mirror.acknowledgeBatch(receipts.executions());
            if (!handoff.compareAndSet(current, null)) return;
        } else if (current != null) {
            return;
        }
        if (!mirror.hasPendingOnline()) return;
        var tickets = mirror.pendingOnline(cursor);
        if (!tickets.isEmpty() && handoff.compareAndSet(null, new Batch(tickets))) {
            var last = tickets.get(tickets.size() - 1);
            cursor = OfficialMirrorModel.taskKey(last.operationId(), last.gameKey());
        }
    }

    /** Native tick caller supplies the actual main-thread check and synchronous effects. */
    void executeOnGame(OfficialMirrorService.GameEffects effects) {
        if (!(handoff.get() instanceof Batch batch)) return;
        List<OfficialMirrorService.Execution> results = new ArrayList<>(batch.tickets().size());
        for (var ticket : batch.tickets()) {
            if (handoff.get() != batch) return;
            results.add(mirror.execute(ticket, effects));
        }
        handoff.compareAndSet(batch, new Receipts(List.copyOf(results)));
    }

    @Override public void close() { handoff.set(CLOSED); }
}
