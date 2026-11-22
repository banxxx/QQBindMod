package com.poso.qqbind.official;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;

/** One atomic center-mirror state file. No runtime config or Minecraft imports.
 * Reservations, mirrors, operation payloads and effect receipts share one commit.
 */
public final class OfficialModState implements AutoCloseable {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    static final int MAX_RESERVATIONS = 10000;
    interface WriteHook { void at(String phase) throws IOException; }
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final OfficialScope scope;
    private String authority = "center";
    private final Path path;
    private final WriteHook hook;
    private FileChannel lockChannel;
    private FileLock fileLock;
    private JsonObject state;
    private volatile boolean stopped;
    private Object mirrorOwner;

    /** Missing storage may be created ONLY for a deliberate fresh installation. */
    public OfficialModState(Path directory, OfficialScope scope, boolean createFresh) {
        this(directory, scope, createFresh, phase -> {});
    }
    public OfficialModState(Path directory, OfficialScope scope, boolean createFresh, String authority) {
        this(directory,scope,createFresh,phase -> {},authority);
    }

    OfficialModState(Path directory, OfficialScope scope, boolean createFresh, WriteHook hook) {
        this(directory,scope,createFresh,hook,"center");
    }
    private OfficialModState(Path directory, OfficialScope scope, boolean createFresh, WriteHook hook,String authority) {
        this.scope = scope;
        if (!Set.of("center","local").contains(authority)) throw new OfficialException("INVALID_INPUT");
        this.authority = authority;
        this.path = directory.toAbsolutePath().normalize().resolve("official-state.json");
        this.hook = hook;
        try {
            if (scope == null || Files.isSymbolicLink(directory) || Files.isSymbolicLink(path)) throw new IOException();
            if (createFresh) Files.createDirectories(path.getParent());
            Path lockPath = path.resolveSibling("official-state.lock");
            if (Files.isSymbolicLink(lockPath)) throw new IOException();
            lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = lockChannel.tryLock();
            if (fileLock == null) throw new IOException();
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) state = read();
            else if (createFresh) { state = empty(); write(state); }
            else throw new IOException();
        } catch (Exception e) {
            close();
            if (e instanceof OfficialException official) throw official;
            throw new OfficialException("STORAGE_ERROR");
        }
    }

    private JsonObject empty() {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        root.addProperty("authority", authority);
        root.add("scope", scope.wire());
        root.addProperty("store_revision", "1");
        for (String name : new String[]{"bindings", "reservations", "operations", "pending_effects"}) root.add(name, new JsonObject());
        if (authority.equals("local")) {
            JsonObject local=new JsonObject(); local.add("bindings",new JsonObject()); local.add("operations",new JsonObject()); root.add("local_authority",local);
        }
        return root;
    }

    /** Every public read checks that disk still agrees with the committed state. */
    public synchronized JsonObject snapshot() {
        healthy();
        try {
            JsonObject disk = read();
            if (!disk.equals(state)) throw new OfficialException("STORAGE_ERROR");
            return state.deepCopy();
        } catch (Exception e) {
            stopped = true;
            if (e instanceof OfficialException official) throw official;
            throw new OfficialException("STORAGE_ERROR");
        }
    }

    public synchronized void commit(JsonObject candidate) {
        snapshot();
        JsonObject next = candidate.deepCopy();
        long current = OfficialScope.revision(state.get("store_revision").getAsString());
        if (current == Long.MAX_VALUE) throw new OfficialException("LIMIT_EXCEEDED");
        if (!candidate.get("store_revision").equals(state.get("store_revision"))) throw new OfficialException("CONFLICT");
        next.addProperty("store_revision", Long.toString(current + 1));
        validate(next);
        try {
            write(next);
            state = next;
        } catch (Exception e) {
            // Rename may have succeeded. Stop until reopening and querying the
            // operation; never publish the unconfirmed candidate in memory.
            stopped = true;
            throw new OfficialException("RESULT_UNKNOWN");
        }
    }

    private void healthy() {
        if (stopped || fileLock == null || !fileLock.isValid()) throw new OfficialException("STORAGE_ERROR");
    }

    private JsonObject read() throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException();
        byte[] raw;
        try (var stream = Files.newInputStream(path)) { raw = stream.readNBytes(MAX_BYTES + 1); }
        if (raw.length > MAX_BYTES) throw new OfficialException("LIMIT_EXCEEDED");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString(); }
        catch (CharacterCodingException e) { throw new IOException(); }
        JsonObject root = parse(text).getAsJsonObject();
        validate(root);
        return root;
    }

    static JsonElement parse(String text) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement result = element(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException();
            return result;
        }
    }

    private static JsonElement element(JsonReader reader, int depth) throws IOException {
        if (depth > 16) throw new IOException();
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw new IOException();
                    object.add(name, element(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) array.add(element(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException();
        };
    }

    private void validate(JsonObject root) {
        try {
            Set<String> required=new HashSet<>(Set.of("schema_version","authority","scope","store_revision","bindings","reservations","operations","pending_effects"));
            if (authority.equals("local")) required.add("local_authority");
            Set<String> allowed=new HashSet<>(required); allowed.add("command_receipts");
            if (!root.keySet().containsAll(required) || !allowed.containsAll(root.keySet())
                    || !root.get("schema_version").getAsJsonPrimitive().isNumber()
                    || !root.get("schema_version").getAsString().equals("1")
                    || !root.get("authority").getAsString().equals(authority) || !root.get("scope").equals(scope.wire())
                    || !root.getAsJsonObject("scope").get("protocol_version").getAsJsonPrimitive().isNumber()
                    || !root.getAsJsonObject("scope").get("protocol_version").getAsString().equals("2")) {
                throw new OfficialException("SCOPE_MISMATCH");
            }
            OfficialScope.revision(string(root, "store_revision"));
            OfficialMirrorModel.validate(root);
            if (authority.equals("local")) OfficialLocalAuthority.validate(root,scope);
            if (root.has("command_receipts")) OfficialCommandService.validate(root.getAsJsonObject("command_receipts"));
            JsonObject records = root.getAsJsonObject("reservations");
            if (records.size() > MAX_RESERVATIONS) throw new OfficialException("LIMIT_EXCEEDED");
            Set<String> generations = new HashSet<>(), pendingGames = new HashSet<>();
            for (var entry : records.entrySet()) {
                OfficialScope.uuid(entry.getKey());
                JsonObject record = entry.getValue().getAsJsonObject();
                if (!record.keySet().equals(Set.of("operation_id", "token_generation", "reservation_id",
                        "game_key", "game_id", "user_id", "state", "center_state"))) throw new IllegalArgumentException();
                if (!entry.getKey().equals(string(record, "operation_id"))) throw new IllegalArgumentException();
                if (!generations.add(OfficialScope.uuid(string(record, "token_generation")))) throw new IllegalArgumentException();
                OfficialScope.uuid(string(record, "reservation_id"));
                String key = string(record, "game_key");
                if (!key.equals(OfficialScope.key(string(record, "game_id")))) throw new IllegalArgumentException();
                OfficialScope.identifier(string(record, "user_id"));
                String state = string(record, "state"), center = string(record, "center_state");
                if (!Set.of("RESERVED", "CONSUMED", "INVALIDATED").contains(state)
                        || !Set.of("PREPARED", "RESERVED", "COMMITTED", "REJECTED", "ABORTED").contains(center)
                        || state.equals("CONSUMED") && !center.equals("COMMITTED")
                        || state.equals("INVALIDATED") && !Set.of("REJECTED", "ABORTED").contains(center)
                        || state.equals("RESERVED") && !pendingGames.add(key)) throw new IllegalArgumentException();
            }
        } catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("STORAGE_ERROR"); }
    }

    static String string(JsonObject value, String field) {
        JsonElement item = value.get(field);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
            throw new OfficialException("INVALID_INPUT");
        }
        return item.getAsString();
    }

    private void write(JsonObject next) throws IOException {
        byte[] raw = JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_BYTES) throw new OfficialException("LIMIT_EXCEEDED");
        Path temp = Files.createTempFile(path.getParent(), ".official-state-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(raw);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            hook.at("before_replace");
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            hook.at("after_replace");
            if (!System.getProperty("os.name", "").startsWith("Windows")) {
                try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            }
        } finally { Files.deleteIfExists(temp); }
    }

    @Override public synchronized void close() {
        stopped = true;
        try { if (fileLock != null) fileLock.close(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
    }

    /** Lock-free gate for game callbacks; disk checks belong to the I/O worker. */
    public boolean available() { return !stopped; }
    synchronized void claimMirror(Object owner) {
        healthy();
        if (mirrorOwner != null) throw new OfficialException("CONFLICT");
        mirrorOwner = owner;
    }
    synchronized void releaseMirror(Object owner) { if (mirrorOwner == owner) mirrorOwner = null; }
}
