package com.poso.qqbind.official;

import com.google.gson.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Official-only code/reservation state machine, with no Minecraft or config I/O. */
public final class OfficialTokenService {
    public interface CenterInspector { CenterOperation find(String operationId); }

    /** A trusted center read. Finalize never trusts the decision in an HTTP body. */
    public record CenterOperation(OfficialScope scope, String operationId, String tokenGeneration,
                                  String gameId, String userId, String state,
                                  JsonArray effects, JsonObject delivery) {
        public CenterOperation {
            Objects.requireNonNull(scope);
            OfficialScope.uuid(operationId);
            OfficialScope.uuid(tokenGeneration);
            OfficialScope.game(gameId);
            OfficialScope.identifier(userId);
            if (!Set.of("PREPARED", "RESERVED", "COMMITTED", "REJECTED", "ABORTED").contains(state)) {
                throw new OfficialException("INVALID_INPUT");
            }
            effects = effects.deepCopy();
            delivery = delivery.deepCopy();
        }
        @Override public JsonArray effects() { return effects.deepCopy(); }
        @Override public JsonObject delivery() { return delivery.deepCopy(); }
    }

    public record Issued(String tokenCode, String gameId, String tokenGeneration, Instant expiresAt) {
        @Override public String toString() { return "OfficialIssuedToken[redacted]"; }
    }
    private record Live(String code, String gameId, String generation, Instant expiresAt, OfficialLoginCodes.Login login) {
        @Override public String toString() { return "OfficialLiveToken[redacted]"; }
    }

    private static final long VALID_SECONDS = 300;
    private final OfficialScope scope;
    private final OfficialModState store;
    private final CenterInspector center;
    private final Clock clock;
    private final Supplier<String> codeSource;
    private final Map<String, Live> liveCodes = new HashMap<>();
    private final Map<String, Instant> retiredCodes = new HashMap<>();
    // Writers are serialized with reservation persistence. The game thread only does get().
    private final Map<String, Live> liveGames = new ConcurrentHashMap<>();
    private volatile boolean pendingReservations;
    private String recoveryCursor;

    public OfficialTokenService(OfficialScope scope, OfficialModState store, CenterInspector center) {
        this(scope, store, center, Clock.systemUTC(), secureCodes());
    }
    private static Supplier<String> secureCodes() {
        SecureRandom random = new SecureRandom();
        return () -> String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }
    OfficialTokenService(OfficialScope scope, OfficialModState store, CenterInspector center,
                         Clock clock, Supplier<String> codeSource) {
        this.scope = Objects.requireNonNull(scope);
        this.store = Objects.requireNonNull(store);
        this.center = Objects.requireNonNull(center);
        this.clock = Objects.requireNonNull(clock);
        this.codeSource = Objects.requireNonNull(codeSource);
        refreshPendingReservations();
    }

    public OfficialScope scope() { return scope; }

    synchronized Issued issue(String gameId) {
        return issue(gameId, null);
    }

    synchronized Issued issue(OfficialLoginCodes.Login login) {
        return issue(login.gameId, login);
    }

    private Issued issue(String gameId, OfficialLoginCodes.Login login) {
        OfficialScope.game(gameId);
        String key = OfficialScope.key(gameId);
        purgeInactiveCodes();
        JsonObject records = store.snapshot().getAsJsonObject("reservations");
        if (records.size() >= OfficialModState.MAX_RESERVATIONS) throw new OfficialException("LIMIT_EXCEEDED");
        for (JsonElement value : records.asMap().values()) {
            JsonObject record = value.getAsJsonObject();
            if (key.equals(text(record, "game_key")) && text(record, "state").equals("RESERVED")) {
                throw new OfficialException("RESULT_UNKNOWN");
            }
        }
        Live old = liveGames.get(key);
        if (old != null && usable(old, clock.instant())) {
            if (old.login() == login) return issued(old);
            if (old.login() != null && login != null) throw new OfficialException("CONFLICT");
        }
        if (login != null && !login.connected.get()) throw new OfficialException("TOKEN_INVALID");
        drop(old);
        if (liveCodes.size() + retiredCodes.size() >= 1000) throw new OfficialException("LIMIT_EXCEEDED");
        String code = null;
        for (int attempt = 0; attempt < 64; attempt++) {
            String next = codeSource.get();
            if (next == null || !next.matches("[0-9]{6}")) throw new OfficialException("INVALID_INPUT");
            if (!liveCodes.containsKey(next) && !retiredCodes.containsKey(next)) { code = next; break; }
        }
        if (code == null) throw new OfficialException("LIMIT_EXCEEDED");
        Live live = new Live(code, gameId, UUID.randomUUID().toString(), clock.instant().plusSeconds(VALID_SECONDS), login);
        liveGames.put(key, live);
        liveCodes.put(code, live);
        return issued(live);
    }

    private static Issued issued(Live live) { return new Issued(live.code(), live.gameId(), live.generation(), live.expiresAt()); }

    private static boolean usable(Live live, Instant now) {
        return live.expiresAt().isAfter(now) && (live.login() == null || live.login().connected.get());
    }

    Issued current(OfficialLoginCodes.Login login) {
        Live live = liveGames.get(login.gameKey);
        return live != null && live.login() == login && store.available() && usable(live, clock.instant())
                ? issued(live) : null;
    }

    synchronized void purgeInactiveCodes() {
        Instant now = clock.instant();
        retiredCodes.values().removeIf(expiry -> !expiry.isAfter(now));
        List<Live> inactive = new ArrayList<>();
        for (Live live : liveCodes.values()) if (!usable(live, now)) inactive.add(live);
        inactive.forEach(this::drop);
    }

    public synchronized JsonObject validate(String code) {
        store.snapshot();
        Live live = valid(code);
        JsonObject data = base();
        data.addProperty("game_id", live.gameId());
        data.addProperty("game_key", OfficialScope.key(live.gameId()));
        data.addProperty("token_generation", live.generation());
        data.addProperty("expires_at", live.expiresAt().toString());
        return data;
    }

    private Live valid(String code) {
        if (code == null || !code.matches("[0-9]{6}")) throw new OfficialException("INVALID_INPUT");
        Live live = liveCodes.get(code);
        if (live == null || !usable(live, clock.instant())) {
            drop(live);
            throw new OfficialException("TOKEN_INVALID");
        }
        return live;
    }

    /** The standalone token harness has no connection handle. */
    synchronized void removeLiveCode(String gameId) {
        Live live = liveGames.get(OfficialScope.key(gameId));
        if (live != null && live.login() == null) drop(live);
    }

    private void drop(Live live) {
        if (live != null) {
            liveGames.remove(OfficialScope.key(live.gameId()), live);
            if (liveCodes.remove(live.code(), live) && live.login() != null && live.expiresAt().isAfter(clock.instant())) {
                retiredCodes.put(live.code(), live.expiresAt());
            }
        }
    }

    private CenterOperation inspect(String operationId) { return inspect(operationId, false); }

    private CenterOperation inspect(String operationId, boolean allowMissing) {
        try {
            CenterOperation operation = center.find(operationId);
            if (operation == null) {
                if (allowMissing) return null;
                throw new OfficialException("SCOPE_MISMATCH");
            }
            if (!scope.equals(operation.scope()) || !operationId.equals(operation.operationId())) {
                throw new OfficialException("SCOPE_MISMATCH");
            }
            return operation;
        } catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("DB_UNAVAILABLE"); }
    }

    public JsonObject reserve(String operationId, String userId, String gameKey, String generation, String code) {
        OfficialScope.uuid(operationId);
        OfficialScope.uuid(generation);
        OfficialScope.identifier(userId);
        if (!OfficialScope.key(gameKey).equals(gameKey)) throw new OfficialException("INVALID_INPUT");
        if (code == null || !code.matches("[0-9]{6}")) throw new OfficialException("INVALID_INPUT");
        synchronized (this) {
            JsonObject existing = store.snapshot().getAsJsonObject("reservations").getAsJsonObject(operationId);
            if (existing != null) {
                matches(existing, operationId, userId, gameKey, generation);
                if (!text(existing, "state").equals("RESERVED")) throw new OfficialException("CONFLICT");
                return reserved(existing);
            }
        }
        CenterOperation authority = inspect(operationId); // No remote I/O in a file transaction.
        if (!Set.of("PREPARED", "RESERVED").contains(authority.state())
                || !authority.tokenGeneration().equals(generation) || !authority.userId().equals(userId)
                || !OfficialScope.key(authority.gameId()).equals(gameKey)) throw new OfficialException("CONFLICT");
        synchronized (this) {
            JsonObject next = store.snapshot(), records = next.getAsJsonObject("reservations");
            JsonObject existing = records.getAsJsonObject(operationId);
            if (existing != null) {
                matches(existing, operationId, userId, gameKey, generation);
                if (!text(existing, "state").equals("RESERVED")) throw new OfficialException("CONFLICT");
                return reserved(existing);
            }
            for (JsonElement value : records.asMap().values()) {
                JsonObject record = value.getAsJsonObject();
                if (generation.equals(text(record, "token_generation"))
                        || gameKey.equals(text(record, "game_key")) && text(record, "state").equals("RESERVED")) {
                    throw new OfficialException("CONFLICT");
                }
            }
            Live live = valid(code);
            if (!live.generation().equals(generation) || !OfficialScope.key(live.gameId()).equals(gameKey)) {
                throw new OfficialException("TOKEN_INVALID");
            }
            if (records.size() >= OfficialModState.MAX_RESERVATIONS) throw new OfficialException("LIMIT_EXCEEDED");
            JsonObject record = record(authority, UUID.randomUUID().toString(), "RESERVED");
            records.add(operationId, record);
            pendingReservations = true;
            store.commit(next);
            drop(live); // Only after durable reservation publication.
            return reserved(record);
        }
    }

    public JsonObject finalizeToken(String operationId, String reservationId, String decision, String generation) {
        OfficialScope.uuid(operationId);
        if (reservationId != null) OfficialScope.uuid(reservationId);
        if (!Set.of("COMMITTED", "ABORTED").contains(decision)) throw new OfficialException("INVALID_INPUT");
        if (reservationId == null && (!decision.equals("ABORTED") || generation == null)) {
            throw new OfficialException("INVALID_INPUT");
        }
        if (generation != null) OfficialScope.uuid(generation);
        CenterOperation authority = inspect(operationId);
        boolean committed = authority.state().equals("COMMITTED");
        if (!(committed ? decision.equals("COMMITTED") :
                Set.of("REJECTED", "ABORTED").contains(authority.state()) && decision.equals("ABORTED"))) {
            throw new OfficialException("CONFLICT");
        }
        if (generation != null && !generation.equals(authority.tokenGeneration())) throw new OfficialException("CONFLICT");
        synchronized (this) {
            JsonObject next = store.snapshot(), records = next.getAsJsonObject("reservations");
            JsonObject existing = records.getAsJsonObject(operationId);
            String expected = committed ? "CONSUMED" : "INVALIDATED";
            JsonObject record;
            if (existing == null) {
                // A center CAS abort precedes an absent-generation tombstone.
                // The marker prevents a late in-flight reserve from succeeding.
                if (committed) throw new OfficialException("STORAGE_ERROR");
                for (JsonElement value : records.asMap().values()) {
                    if (authority.tokenGeneration().equals(text(value.getAsJsonObject(), "token_generation"))) {
                        throw new OfficialException("CONFLICT");
                    }
                }
                if (records.size() >= OfficialModState.MAX_RESERVATIONS) throw new OfficialException("LIMIT_EXCEEDED");
                record = record(authority, reservationId == null ? UUID.randomUUID().toString() : reservationId, expected);
            } else {
                matches(existing, operationId, authority.userId(), OfficialScope.key(authority.gameId()), authority.tokenGeneration());
                if (reservationId != null && !reservationId.equals(text(existing, "reservation_id"))) throw new OfficialException("CONFLICT");
                String oldState = text(existing, "state");
                if (!oldState.equals("RESERVED") && !oldState.equals(expected)) throw new OfficialException("CONFLICT");
                if (oldState.equals(expected)) return finalized(existing, reservationId == null);
                record = existing.deepCopy();
                record.addProperty("state", expected);
                record.addProperty("center_state", authority.state());
            }
            records.add(operationId, record);
            store.commit(next);
            refreshPendingReservations();
            Live live = liveGames.get(OfficialScope.key(authority.gameId()));
            if (live != null && live.generation().equals(authority.tokenGeneration())) drop(live);
            return finalized(record, reservationId == null);
        }
    }

    /** Local reservation is independent of center availability. This GET is read-only. */
    public JsonObject operation(String operationId) {
        OfficialScope.uuid(operationId);
        JsonObject record;
        synchronized (this) { record = store.snapshot().getAsJsonObject("reservations").getAsJsonObject(operationId); }
        CenterOperation authority = null;
        try { authority = inspect(operationId, true); }
        catch (OfficialException e) {
            if (!Set.of("DB_UNAVAILABLE", "RESULT_UNKNOWN").contains(e.code())) throw e;
        }
        if (record != null && authority != null) {
            matches(record, operationId, authority.userId(), OfficialScope.key(authority.gameId()), authority.tokenGeneration());
        }
        JsonObject data = base();
        data.addProperty("operation_id", operationId);
        data.addProperty("center_available", authority != null);
        if (authority != null) {
            data.addProperty("state", authority.state());
            data.addProperty("kind", "bind");
            data.add("effects", authority.effects());
            data.add("delivery", authority.delivery());
        } else if (record != null) {
            data.addProperty("state", text(record, "center_state")); // Last confirmed center state, not a new claim.
        }
        JsonObject receipt = base();
        receipt.addProperty("operation_id", operationId);
        receipt.addProperty("state", record == null ? "ABSENT" : text(record, "state"));
        for (String name : new String[]{"token_generation", "reservation_id", "game_key", "user_id"}) {
            if (record == null) receipt.add(name, JsonNull.INSTANCE);
            else receipt.add(name, record.get(name).deepCopy());
        }
        data.add("reservation", receipt);
        return data;
    }

    /** Fast scheduling hint; no game-thread file access or monitor. */
    public boolean hasPendingReservationsFast() { return pendingReservations; }

    private void refreshPendingReservations() {
        pendingReservations = store.snapshot().getAsJsonObject("reservations").asMap().values().stream()
                .anyMatch(value -> text(value.getAsJsonObject(),"state").equals("RESERVED"));
    }

    /** Bounded background reconciliation of durable reservations. It NEVER
     * commits or aborts a PREPARED/RESERVED center operation and never uses a
     * restart, expiry, disconnect or center outage as permission to release it.
     */
    boolean recoverTerminalReservationsOnIo(int limit) {
        if (limit < 1 || limit > 8) throw new OfficialException("INVALID_INPUT");
        List<JsonObject> batch=new ArrayList<>(limit);
        synchronized (this) {
            JsonObject records=store.snapshot().getAsJsonObject("reservations");
            NavigableSet<String> keys=new TreeSet<>();
            for (var entry : records.asMap().entrySet())
                if (text(entry.getValue().getAsJsonObject(),"state").equals("RESERVED")) keys.add(entry.getKey());
            List<String> ordered=new ArrayList<>(keys.size());
            if (recoveryCursor == null) ordered.addAll(keys);
            else { ordered.addAll(keys.tailSet(recoveryCursor,false)); ordered.addAll(keys.headSet(recoveryCursor,true)); }
            for (String id : ordered) {
                if (batch.size() == limit) break;
                batch.add(records.getAsJsonObject(id).deepCopy());
                recoveryCursor=id;
            }
            pendingReservations=!keys.isEmpty();
        }
        boolean complete=true;
        for (JsonObject reservation : batch) {
            String id=text(reservation,"operation_id");
            try {
                CenterOperation authority=inspect(id,true);
                if (authority == null) { complete=false; continue; }
                matches(reservation,id,authority.userId(),OfficialScope.key(authority.gameId()),authority.tokenGeneration());
                if (Set.of("PREPARED","RESERVED").contains(authority.state())) { complete=false; continue; }
                finalizeToken(id,text(reservation,"reservation_id"),
                        authority.state().equals("COMMITTED") ? "COMMITTED" : "ABORTED",authority.tokenGeneration());
            } catch (OfficialException ignored) { complete=false; }
        }
        return complete;
    }

    private static void matches(JsonObject record, String operation, String user, String key, String generation) {
        if (!operation.equals(text(record, "operation_id")) || !user.equals(text(record, "user_id"))
                || !key.equals(text(record, "game_key")) || !generation.equals(text(record, "token_generation"))) {
            throw new OfficialException("CONFLICT");
        }
    }
    private JsonObject record(CenterOperation operation, String reservationId, String state) {
        JsonObject result = new JsonObject();
        result.addProperty("operation_id", operation.operationId());
        result.addProperty("token_generation", operation.tokenGeneration());
        result.addProperty("reservation_id", reservationId);
        result.addProperty("game_id", operation.gameId());
        result.addProperty("game_key", OfficialScope.key(operation.gameId()));
        result.addProperty("user_id", operation.userId());
        result.addProperty("state", state);
        result.addProperty("center_state", operation.state());
        return result;
    }
    private JsonObject base() {
        JsonObject data = new JsonObject();
        data.addProperty("protocol_version", 2);
        data.add("scope", scope.wire());
        return data;
    }
    private JsonObject reserved(JsonObject record) {
        JsonObject data = base();
        for (String field : new String[]{"operation_id", "reservation_id", "token_generation", "game_key"}) {
            data.add(field, record.get(field).deepCopy());
        }
        data.addProperty("state", "RESERVED");
        return data;
    }
    private JsonObject finalized(JsonObject record, boolean absentFinalize) {
        JsonObject data = base();
        data.add("operation_id", record.get("operation_id").deepCopy());
        data.add("token_generation", record.get("token_generation").deepCopy());
        data.add("token_state", record.get("state").deepCopy());
        if (absentFinalize) data.add("reservation_id", record.get("reservation_id").deepCopy());
        return data;
    }
    private static String text(JsonObject value, String key) { return OfficialModState.string(value, key); }
}
