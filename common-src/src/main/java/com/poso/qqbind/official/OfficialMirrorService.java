package com.poso.qqbind.official;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import static com.poso.qqbind.official.OfficialMirrorModel.*;

/** Center-owned mirror and versioned effect outbox. No game/framework imports.
 * All persistence methods run on I/O workers. execute() is a separate lock-free,
 * memory-only callback for one game thread; it never reads a file or database.
 */
public final class OfficialMirrorService implements AutoCloseable {
    public interface Confirmation {
        void committed(OfficialScope scope, String operationId, JsonArray fixedEffects);
        void snapshot(OfficialScope scope, JsonArray consistentRows);
    }
    public interface GameEffects {
        void whitelist(String gameId, boolean active) throws Exception;
        void online(String gameId, boolean active) throws Exception;
    }
    public enum Part { WHITELIST, ONLINE;
        String field() { return this == WHITELIST ? "whitelist" : "online"; }
    }
    public enum Outcome { PENDING, APPLIED, SUPERSEDED, FAILED, CONFLICT }
    public record Ticket(OfficialScope scope, String operationId, String gameKey, String gameId,
                         String userId, String state, String revision, Part part) { }
    public record Execution(Ticket ticket, Outcome outcome) { }
    private record View(JsonObject state, boolean updating, Map<String, Set<String>> active, boolean pendingOnline, boolean pendingWhitelist) {
        View(JsonObject state) { this(state, false, index(state), hasWork(state,Part.ONLINE),hasWork(state,Part.WHITELIST)); }
        View writing() { return new View(state, true, active, pendingOnline,pendingWhitelist); }

        private static boolean hasWork(JsonObject state, Part part) {
            for (var value : state.getAsJsonObject("pending_effects").asMap().values()) {
                if (isOutstanding(value.getAsJsonObject(),part)) return true;
            }
            return false;
        }
        private static Map<String, Set<String>> index(JsonObject state) {
            Map<String, Set<String>> result = new HashMap<>();
            for (JsonElement element : state.getAsJsonObject("bindings").asMap().values()) {
                JsonObject row = element.getAsJsonObject();
                if (text(row, "state").equals("active") && !bool(row, "conflicted")) {
                    result.computeIfAbsent(text(row, "user_id"), k -> new TreeSet<>()).add(text(row, "game_key"));
                }
            }
            result.replaceAll((user, games) -> Collections.unmodifiableSet(games));
            return Collections.unmodifiableMap(result);
        }
    }
    private final OfficialScope scope;
    private final OfficialModState store;
    private final Confirmation confirmation;
    private final BooleanSupplier onGameThread;
    private final Object io = new Object();
    private final AtomicReference<View> view = new AtomicReference<>();
    private final AtomicReference<Thread> gameThread = new AtomicReference<>();
    private final AtomicReference<Object> whitelistOwner = new AtomicReference<>();
    private volatile boolean closed;

    public OfficialMirrorService(OfficialScope scope, OfficialModState store,
                                  Confirmation confirmation, BooleanSupplier onGameThread) {
        this.scope = Objects.requireNonNull(scope);
        this.store = Objects.requireNonNull(store);
        this.confirmation = confirmation;
        this.onGameThread = Objects.requireNonNull(onGameThread);
        JsonObject initial = store.snapshot();
        requireScope(initial.getAsJsonObject("scope"));
        store.claimMirror(this);
        view.set(new View(initial));
    }

    private void requireScope(JsonObject incoming) {
        if (!scope.wire().equals(incoming)) throw new OfficialException("SCOPE_MISMATCH");
    }
    private void requireSource(OfficialScope source) {
        if (!scope.equals(source)) throw new OfficialException("SCOPE_MISMATCH");
        if (closed) throw new OfficialException("STORAGE_ERROR");
        if (confirmation == null) throw new OfficialException("NOT_SUPPORTED");
    }
    private void open() { if (closed || !store.available()) throw new OfficialException("STORAGE_ERROR"); }

    public JsonObject sync(OfficialScope source, String operationId, JsonArray incoming) {
        requireSource(source); OfficialScope.uuid(operationId);
        JsonArray fixed = effects(incoming, 500);
        confirmation.committed(scope, operationId, fixed.deepCopy()); // No I/O transaction held.
        synchronized (io) {
            open();
            JsonObject before = store.snapshot(), next = before.deepCopy();
            cache(next, operationId, fixed, true);
            for (JsonElement element : fixed) merge(next, operationId, element.getAsJsonObject());
            persist(before, next);
            return results(store.snapshot(), operationId);
        }
    }

    /** Merge, never replaceAll: absent rows do not mean deletion. */
    public void mergeSnapshot(OfficialScope source, JsonArray rows) {
        requireSource(source);
        if (rows == null || rows.size() > MAX_BINDINGS) throw new OfficialException("LIMIT_EXCEEDED");
        TreeMap<String, JsonObject> sorted = new TreeMap<>();
        Map<String, JsonArray> operations = new TreeMap<>();
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject(); fields(row, plus(EFFECT, "last_operation_id"));
            JsonObject effect = effect(project(row)); String operation = OfficialScope.uuid(text(row, "last_operation_id"));
            if (sorted.put(text(effect, "game_key"), row.deepCopy()) != null) throw new OfficialException("INVALID_INPUT");
            operations.computeIfAbsent(operation, k -> new JsonArray()).add(effect);
        }
        confirmation.snapshot(scope, rows.deepCopy());
        synchronized (io) {
            open();
            JsonObject before = store.snapshot(), next = before.deepCopy();
            for (var entry : operations.entrySet()) cache(next, entry.getKey(), effects(entry.getValue(), 500), false);
            for (JsonObject row : sorted.values()) merge(next, text(row, "last_operation_id"), project(row));
            persist(before, next);
        }
    }

    static void cache(JsonObject root, String operation, JsonArray incoming, boolean complete) {
        JsonObject operations = root.getAsJsonObject("operations"), old = operations.getAsJsonObject(operation);
        JsonObject indexedIncoming = indexed(incoming), combined = old == null ? new JsonObject() : indexed(old.getAsJsonArray("effects"));
        if (old != null && bool(old, "complete")) {
            if (complete && !old.get("effects").equals(incoming)) throw new OfficialException("CONFLICT");
            for (var entry : indexedIncoming.entrySet()) {
                if (!entry.getValue().equals(combined.get(entry.getKey()))) throw new OfficialException("CONFLICT");
            }
            return;
        }
        if (complete) {
            for (var entry : combined.entrySet()) {
                if (!entry.getValue().equals(indexedIncoming.get(entry.getKey()))) throw new OfficialException("CONFLICT");
            }
            combined = indexedIncoming;
        } else {
            for (var entry : indexedIncoming.entrySet()) {
                if (combined.has(entry.getKey()) && !combined.get(entry.getKey()).equals(entry.getValue())) throw new OfficialException("CONFLICT");
                combined.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        JsonArray values = new JsonArray();
        for (String key : new TreeSet<>(combined.keySet())) values.add(combined.get(key));
        if (values.size() > 500) throw new OfficialException("LIMIT_EXCEEDED");
        JsonObject record = new JsonObject(); record.addProperty("complete", complete); record.add("effects", values);
        operations.add(operation, record);
    }

    static void merge(JsonObject root, String operation, JsonObject incoming) {
        JsonObject bindings = root.getAsJsonObject("bindings"), tasks = root.getAsJsonObject("pending_effects");
        String key = text(incoming, "game_key"); JsonObject current = bindings.getAsJsonObject(key);
        if (current == null || !bool(current, "conflicted") && version(incoming) > version(current)) {
            JsonObject replacement = incoming.deepCopy(); replacement.addProperty("last_operation_id", operation);
            replacement.addProperty("conflicted", false); bindings.add(key, replacement); current = replacement;
        } else if (!bool(current, "conflicted") && version(incoming) == version(current) && !incoming.equals(project(current))) {
            current.addProperty("conflicted", true);
        }
        String id = taskKey(operation, key);
        if (!tasks.has(id)) {
            JsonObject task = incoming.deepCopy(); task.addProperty("operation_id", operation);
            task.addProperty("whitelist", "PENDING"); task.addProperty("online", "PENDING"); tasks.add(id, task);
        }
        // A newly durable version supersedes every old action/ACK for this account.
        for (JsonElement element : tasks.asMap().values()) {
            JsonObject task = element.getAsJsonObject();
            if (!key.equals(text(task, "game_key"))) continue;
            String resolution = bool(current, "conflicted") ? "CONFLICT" : version(task) < version(current) ? "SUPERSEDED" : null;
            if (resolution != null) { task.addProperty("whitelist", resolution); task.addProperty("online", resolution); }
        }
    }

    private void persist(JsonObject before, JsonObject next) {
        if (next.equals(before)) return;
        View previous = view.get();
        view.set(previous.writing()); // Game actions defer; login can still read the last durable version.
        try {
            store.commit(next);
            view.set(new View(store.snapshot()));
        } catch (Exception e) {
            if (store.available()) {
                try { view.set(new View(store.snapshot())); }
                catch (Exception ignored) { view.set(previous.writing()); }
            }
            if (e instanceof OfficialException official) throw official;
            throw new OfficialException("STORAGE_ERROR");
        }
    }
    /** Local authority and its outbox share this exact atomic file commit. */
    <T> T atomicOnIo(java.util.function.Function<JsonObject,T> work) {
        if (onGameThread.getAsBoolean()) throw new OfficialException("NOT_SUPPORTED");
        synchronized (io) {
            open(); JsonObject before=store.snapshot(), next=before.deepCopy();
            T result=work.apply(next); persist(before,next); return result;
        }
    }
    JsonObject durableOnIo() {
        if (onGameThread.getAsBoolean()) throw new OfficialException("NOT_SUPPORTED");
        synchronized (io) { open(); return store.snapshot(); }
    }

    /** An account index derived only from published, valid active rows. */
    public boolean isBoundFast(String gameId) {
        String key=OfficialScope.key(gameId); View published=view.get();
        if (closed || !store.available()) return false;
        JsonObject row=published.state().getAsJsonObject("bindings").getAsJsonObject(key);
        return row != null && text(row,"state").equals("active") && !bool(row,"conflicted");
    }

    /** Reapply a durable active online target at login; no state I/O or pending ACK. */
    void reconcileActiveOnline(String gameId, Runnable restore) {
        requireGameThread();
        String key = OfficialScope.key(gameId);
        View before = view.get();
        if (closed || !store.available() || before.updating()) return;
        JsonObject row = before.state().getAsJsonObject("bindings").getAsJsonObject(key);
        if (row == null || !text(row, "state").equals("active") || bool(row, "conflicted")) return;
        JsonObject task = before.state().getAsJsonObject("pending_effects")
                .getAsJsonObject(taskKey(text(row, "last_operation_id"), key));
        if (task == null || !project(task).equals(project(row))) return;
        Outcome current = resolution(view.get(), ticket(task, Part.ONLINE));
        if (current != null && current != Outcome.APPLIED) return;
        try { restore.run(); }
        catch (RuntimeException ignored) { /* Keep the host restriction for the next reminder or outbox action. */ }
    }

    /** Unknown or conflicted mirror state must not be treated as a new unbound account. */
    boolean mayIssueCodeFast(String gameId) {
        String key = OfficialScope.key(gameId);
        View published = view.get();
        if (closed || !store.available() || published.updating()) return false;
        JsonObject row = published.state().getAsJsonObject("bindings").getAsJsonObject(key);
        return row == null || text(row, "state").equals("revoked") && !bool(row, "conflicted");
    }

    /** An account index derived only from published, valid active rows. */
    public Set<String> activeGames(String userId) {
        OfficialScope.identifier(userId); View published = view.get();
        if (closed || !store.available() || published.updating()) return Set.of();
        return published.active().getOrDefault(userId, Set.of());
    }

    /** I/O worker captures bounded tickets. No raw player object is retained. */
    public List<Ticket> pending(int limit) {
        if (limit < 1 || limit > 100) throw new OfficialException("INVALID_INPUT");
        synchronized (io) {
            open();
            JsonObject state = store.snapshot();
            List<Ticket> result = new ArrayList<>(); JsonObject tasks = state.getAsJsonObject("pending_effects");
            for (String id : new TreeSet<>(tasks.keySet())) {
                JsonObject task = tasks.getAsJsonObject(id);
                for (Part part : Part.values()) {
                    if (!isOutstanding(task, part)) continue;
                    result.add(ticket(task, part)); if (result.size() == limit) return List.copyOf(result);
                }
            }
            return List.copyOf(result);
        }
    }
    private static boolean isOutstanding(JsonObject task, Part part) {
        String status = text(task, part.field());
        return status.equals("PENDING") || status.equals("FAILED");
    }

    boolean hasPendingOnline() { return !closed && store.available() && view.get().pendingOnline(); }
    boolean hasPendingWhitelist() { return !closed && store.available() && view.get().pendingWhitelist(); }
    boolean matchesScope(OfficialScope source) { return scope.equals(source); }
    void claimWhitelist(Object owner) {
        if (closed || !whitelistOwner.compareAndSet(null,Objects.requireNonNull(owner))) throw new OfficialException("CONFLICT");
    }
    boolean ownsWhitelist(Object owner) { return !closed && whitelistOwner.get() == owner; }
    void releaseWhitelist(Object owner) { whitelistOwner.compareAndSet(owner,null); }

    List<Ticket> pendingOnline(String cursor) {
        return pendingPart(cursor,Part.ONLINE,16);
    }
    List<Ticket> pendingWhitelist(String cursor) {
        return pendingPart(cursor,Part.WHITELIST,4);
    }
    List<Ticket> pendingWhitelist(String cursor, Set<String> dirty) {
        if (dirty.isEmpty()) return pendingWhitelist(cursor);
        if (dirty.size() > 4) throw new OfficialException("LIMIT_EXCEEDED");
        synchronized (io) {
            open(); var state=store.snapshot(); List<Ticket> result=new ArrayList<>(4);
            for (String key : new TreeSet<>(dirty)) {
                var row=state.getAsJsonObject("bindings").getAsJsonObject(key); if (row == null) continue;
                var task=state.getAsJsonObject("pending_effects").getAsJsonObject(taskKey(text(row,"last_operation_id"),key));
                if (task != null && isOutstanding(task,Part.WHITELIST)) {
                    Ticket ticket=ticket(task,Part.WHITELIST);
                    if (resolution(view.get(),ticket) == null) result.add(ticket);
                }
            }
            return List.copyOf(result);
        }
    }
    private List<Ticket> pendingPart(String cursor, Part part, int limit) {
        synchronized (io) {
            open();
            var tasks = store.snapshot().getAsJsonObject("pending_effects");
            var ids = new TreeSet<>(tasks.keySet());
            List<Ticket> batch = new ArrayList<>(limit);
            if (cursor == null) {
                collectPart(tasks,ids,batch,part,limit);
            } else {
                collectPart(tasks,ids.tailSet(cursor,false),batch,part,limit);
                if (batch.size() < limit) collectPart(tasks,ids.headSet(cursor,true),batch,part,limit);
            }
            return List.copyOf(batch);
        }
    }

    private void collectPart(JsonObject tasks, Iterable<String> ids, List<Ticket> batch, Part part, int limit) {
        for (String id : ids) {
            var task = tasks.getAsJsonObject(id);
            if (isOutstanding(task,part)) batch.add(ticket(task,part));
            if (batch.size() == limit) return;
        }
    }
    private Ticket ticket(JsonObject task, Part part) {
        return new Ticket(scope, text(task, "operation_id"), text(task, "game_key"), text(task, "game_id"),
                          text(task, "user_id"), text(task, "state"), text(task, "revision"), part);
    }
    private static JsonObject task(View published, Ticket ticket) {
        return published.state().getAsJsonObject("pending_effects").getAsJsonObject(taskKey(ticket.operationId(), ticket.gameKey()));
    }
    private Outcome resolution(View published, Ticket ticket) {
        if (closed || !store.available() || published.updating()) return Outcome.PENDING;
        if (!scope.equals(ticket.scope()) || ticket.part() == null) return Outcome.CONFLICT;
        JsonObject task = task(published, ticket);
        if (task == null || !ticket(task, ticket.part()).equals(ticket)) return Outcome.CONFLICT;
        JsonObject current = published.state().getAsJsonObject("bindings").getAsJsonObject(ticket.gameKey());
        if (current == null || bool(current, "conflicted")) return Outcome.CONFLICT;
        if (version(task) < version(current)) return Outcome.SUPERSEDED;
        if (!project(task).equals(project(current))) return Outcome.CONFLICT;
        return switch (text(task, ticket.part().field())) {
            case "APPLIED" -> Outcome.APPLIED;
            case "SUPERSEDED" -> Outcome.SUPERSEDED;
            case "CONFLICT" -> Outcome.CONFLICT;
            default -> null;
        };
    }

    /** Game-thread-only. No monitors, file/DB I/O, confirmation callback, or wait. */
    public Execution execute(Ticket ticket, GameEffects effects) {
        requireGameThread();
        View before = view.get(); Outcome skip = resolution(before, ticket);
        if (skip != null) return new Execution(ticket, skip);
        try {
            boolean active = ticket.state().equals("active");
            if (ticket.part() == Part.WHITELIST) effects.whitelist(ticket.gameId(), active);
            else effects.online(ticket.gameId(), active);
        } catch (Exception ignored) { return new Execution(ticket, Outcome.FAILED); }
        Outcome after = resolution(view.get(), ticket);
        return new Execution(ticket, after == null ? Outcome.APPLIED : after);
    }

    Outcome whitelistStatusOnGame(Ticket ticket) {
        requireGameThread();
        if (ticket.part() != Part.WHITELIST) return Outcome.CONFLICT;
        return resolution(view.get(),ticket);
    }
    Execution completeWhitelistOnGame(Ticket ticket, Outcome result) {
        Outcome current=whitelistStatusOnGame(ticket);
        return new Execution(ticket,current == null ? Objects.requireNonNull(result) : current);
    }
    /** I/O lease orders a fixed file candidate before any later mirror publish.
     * Fast game reads never take this monitor. The callback must not enter game code.
     */
    <T> T withWhitelistFile(Object owner, List<Ticket> tickets, Supplier<T> write) {
        synchronized (io) {
            open(); if (!ownsWhitelist(owner)) throw new OfficialException("NOT_SUPPORTED");
            store.snapshot();
            for (Ticket ticket : tickets) {
                if (ticket.part() != Part.WHITELIST || resolution(view.get(),ticket) != null) throw new OfficialException("CONFLICT");
            }
            return write.get();
        }
    }

    private void requireGameThread() {
        if (!onGameThread.getAsBoolean()) throw new OfficialException("CONFLICT");
        gameThread.compareAndSet(null, Thread.currentThread());
        if (gameThread.get() != Thread.currentThread()) throw new OfficialException("CONFLICT");
    }

    /** I/O worker persists a part ACK; stale outcomes cannot overwrite new tasks. */
    public void acknowledge(Execution execution) {
        acknowledgeBatch(List.of(execution));
    }

    void acknowledgeBatch(List<Execution> executions) {
        synchronized (io) {
            open();
            JsonObject before = store.snapshot(), next = before.deepCopy();
            View latest = view.get();
            for (Execution execution : executions) {
                Ticket ticket = execution.ticket();
                if (!scope.equals(ticket.scope())) throw new OfficialException("SCOPE_MISMATCH");
                JsonObject task = next.getAsJsonObject("pending_effects").getAsJsonObject(taskKey(ticket.operationId(), ticket.gameKey()));
                if (task == null || !ticket(task, ticket.part()).equals(ticket)) throw new OfficialException("CONFLICT");
                Outcome current = resolution(latest, ticket);
                Outcome result = current == null ? execution.outcome() : current;
                if (result != Outcome.PENDING) task.addProperty(ticket.part().field(), result.name());
            }
            persist(before, next);
        }
    }

    public JsonObject operationResults(String operationId) {
        OfficialScope.uuid(operationId);
        synchronized (io) { open(); return results(store.snapshot(), operationId); }
    }
    private JsonObject results(JsonObject state, String operationId) {
        JsonObject operation = state.getAsJsonObject("operations").getAsJsonObject(operationId);
        if (operation == null || !bool(operation, "complete")) throw new OfficialException("NOT_SUPPORTED");
        JsonArray items = new JsonArray();
        for (JsonElement element : operation.getAsJsonArray("effects")) {
            JsonObject effect = element.getAsJsonObject(); String key = text(effect, "game_key");
            JsonObject binding = state.getAsJsonObject("bindings").getAsJsonObject(key);
            JsonObject task = state.getAsJsonObject("pending_effects").getAsJsonObject(taskKey(operationId, key));
            String result = bool(binding, "conflicted") ? "conflict" : version(effect) < version(binding) ? "ignored" : "applied";
            JsonObject item = new JsonObject(); item.addProperty("game_key", key); item.addProperty("result", result);
            item.addProperty("requested_revision", text(effect, "revision")); item.addProperty("effective_revision", text(binding, "revision"));
            item.addProperty("persisted", true);
            for (Part part : Part.values()) item.addProperty(part.field(), text(task, part.field()));
            items.add(item);
        }
        JsonObject reply = new JsonObject(); reply.addProperty("protocol_version", 2); reply.addProperty("operation_id", operationId);
        reply.add("results", items); return reply;
    }

    @Override public void close() {
        synchronized (io) { closed = true; store.releaseMirror(this); }
    }
}
