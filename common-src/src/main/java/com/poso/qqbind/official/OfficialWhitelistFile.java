package com.poso.qqbind.official;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Explicit vanilla-file I/O boundary. No Minecraft, configuration or driver imports.
 * Binding callbacks may capture immutable snapshots and inspect fast status only.
 * Native-command hooks are separate because vanilla commands already perform I/O.
 * A loader installs this around its vanilla list; this is never binding authority.
 */
public final class OfficialWhitelistFile implements AutoCloseable {
    public static final int MAX_PROFILES = 10000, MAX_BYTES = 4 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    interface WriteHook { void at(String phase) throws IOException; }
    @FunctionalInterface public interface NativeSave { void run() throws IOException; }
    @FunctionalInterface public interface NativeLoad { Snapshot run() throws IOException; }
    public enum Outcome { APPLIED, NEWER_SNAPSHOT, NOT_APPLIED }

    public record Profile(UUID uuid, String name) {
        public Profile {
            if (uuid == null || name == null || name.isEmpty() || name.length() > 64) throw new OfficialException("INVALID_INPUT");
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c < 32 || c == 127 || Character.isLowSurrogate(c)) throw new OfficialException("INVALID_INPUT");
                if (Character.isHighSurrogate(c) && (i + 1 == name.length() || !Character.isLowSurrogate(name.charAt(++i))))
                    throw new OfficialException("INVALID_INPUT");
            }
        }
        @Override public String toString() { return "OfficialWhitelistProfile[redacted]"; }
    }
    public static final class Snapshot {
        private final Object owner;
        private final long sequence;
        private final List<Profile> profiles;
        private Snapshot(Object owner, long sequence, List<Profile> profiles) { this.owner=owner; this.sequence=sequence; this.profiles=profiles; }
        public long sequence() { return sequence; }
        public List<Profile> profiles() { return profiles; }
        @Override public String toString() { return "OfficialWhitelistSnapshot[redacted]"; }
    }
    public record Receipt(Outcome outcome, Snapshot durable, boolean directorySynced) {
        @Override public String toString() { return "OfficialWhitelistReceipt[" + outcome + "]"; }
    }
    private record Content(boolean exists, List<Profile> profiles, byte[] hash) { }
    private record Pending(Snapshot snapshot, Content before, byte[] expectedHash) { }

    private final Path path;
    private final OfficialScope scope;
    private final BooleanSupplier active, onGameThread;
    private final WriteHook hook;
    private final Object owner = new Object();
    private final ReentrantLock io = new ReentrantLock();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<Thread> gameThread = new AtomicReference<>();
    private FileChannel lockChannel;
    private FileLock fileLock;
    private Content baseline;
    private volatile Snapshot durable;
    private volatile Pending pending;
    private volatile boolean initialized, failed, closed, directorySynced;
    private void lockIo() { io.lock(); }
    private void unlockIo() { if (closed) release(); io.unlock(); }

    /** Construction performs no I/O and never discovers a server working directory. */
    public OfficialWhitelistFile(Path file, OfficialScope scope, BooleanSupplier active, BooleanSupplier onGameThread) {
        this(file,scope,active,onGameThread,phase -> { });
    }
    OfficialWhitelistFile(Path file, OfficialScope scope, BooleanSupplier active, BooleanSupplier onGameThread, WriteHook hook) {
        Objects.requireNonNull(file); this.scope=Objects.requireNonNull(scope);
        this.active=Objects.requireNonNull(active); this.onGameThread=Objects.requireNonNull(onGameThread); this.hook=Objects.requireNonNull(hook);
        if (file.getFileName() == null || !file.getFileName().toString().equals("whitelist.json")) throw new OfficialException("INVALID_INPUT");
        this.path=file.toAbsolutePath().normalize();
    }

    /** Pure bounded copying/validation; no file check, monitor or wait. */
    public Snapshot captureOnGame(List<Profile> profiles) {
        requireGame();
        List<Profile> copy=normalize(profiles);
        long value=sequence.updateAndGet(old -> { if (old == Long.MAX_VALUE) throw new OfficialException("LIMIT_EXCEEDED"); return old + 1; });
        return new Snapshot(owner,value,copy);
    }
    public boolean readyFast() { return initialized && !closed && !failed && pending == null && sourceActive(); }
    public boolean needsRecoveryFast() { return !closed && pending != null && sourceActive(); }
    public Snapshot lastDurableFast() { return readyFast() ? durable : null; }
    boolean matchesScope(OfficialScope source) { return scope.equals(source); }
    boolean currentCapture(Snapshot snapshot) { own(snapshot); return snapshot.sequence() == sequence.get(); }

    public Receipt initializeOnIo(Snapshot initial) {
        return initializeOnIo(initial,false);
    }
    /** allowAbsent is a deliberate first-file authorization, never a restart default. */
    public Receipt initializeOnIo(Snapshot initial, boolean allowAbsent) {
        requireIo(); own(initial);
        lockIo(); try {
            guard();
            if (initialized) throw new OfficialException("CONFLICT");
            try {
                paths();
                Path lock=path.resolveSibling(".whitelist.qqbind-official.lock");
                if (Files.isSymbolicLink(lock) || Files.exists(lock,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lock,LinkOption.NOFOLLOW_LINKS)) throw new IOException();
                lockChannel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                fileLock=lockChannel.tryLock();
                if (fileLock == null) throw new OfficialException("CONFLICT");
                Content current=read();
                if (!current.exists() && !allowAbsent) throw new OfficialException("STORAGE_ERROR");
                if (!current.profiles().equals(initial.profiles())) throw new OfficialException("CONFLICT");
                if (current.exists()) forceFile();
                boolean synced=forceDirectory(); Content confirmed=read();
                if (!same(current,confirmed)) throw new OfficialException("CONFLICT");
                guard(); baseline=current; durable=initial; pending=null; failed=false; initialized=true;
                directorySynced=synced;
                return receipt(Outcome.APPLIED);
            } catch (Exception error) {
                release();
                if (error instanceof java.nio.channels.OverlappingFileLockException) throw new OfficialException("CONFLICT");
                throw clean(error,"STORAGE_ERROR");
            }
        } finally { unlockIo(); }
    }

    public Receipt writeOnIo(Snapshot next) {
        requireIo(); own(next);
        lockIo(); try {
            healthy(false);
            if (pending != null) throw new OfficialException("RESULT_UNKNOWN");
            verifyBaseline();
            if (next.sequence() < durable.sequence()) return receipt(Outcome.NEWER_SNAPSHOT);
            byte[] raw=encode(next.profiles());
            if (baseline.exists() && baseline.profiles().equals(next.profiles())) {
                pending=new Pending(next,baseline,baseline.hash());
                try {
                    forceFile(); guard(); boolean synced=forceDirectory();
                    Content current=read();
                    if (!same(current,baseline)) throw new OfficialException("CONFLICT");
                    publish(next,current,synced);
                    return receipt(Outcome.APPLIED);
                } catch (Exception error) { throw new OfficialException("RESULT_UNKNOWN"); }
            }
            Path temp=null;
            try {
                paths(); temp=Files.createTempFile(path.getParent(),".qqbind-whitelist-",".tmp");
                try (FileChannel stream=FileChannel.open(temp,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer buffer=ByteBuffer.wrap(raw); while (buffer.hasRemaining()) stream.write(buffer); stream.force(true);
                }
                hook.at("before_replace"); guard(); verifyBaseline(); paths();
                pending=new Pending(next,baseline,digest(raw));
                hook.at("move_attempt");
                Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                hook.at("after_replace"); guard(); boolean synced=forceDirectory();
                Content current=read();
                if (!candidate(current,pending)) throw new OfficialException("CONFLICT");
                publish(next,current,synced); return receipt(Outcome.APPLIED);
            } catch (Exception error) {
                if (pending != null) throw new OfficialException("RESULT_UNKNOWN");
                throw clean(error,"STORAGE_ERROR");
            } finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { } }
        } finally { unlockIo(); }
    }

    /** Query/force only. It never replays a write or a native-command callback. */
    public Receipt recoverOnIo() {
        requireIo();
        lockIo(); try { healthy(true); return recover(); } finally { unlockIo(); }
    }
    private Receipt recover() {
        if (pending == null) { if (failed) throw new OfficialException("STORAGE_ERROR"); verifyBaseline(); return receipt(Outcome.APPLIED); }
        Pending attempt=pending;
        try {
            Content current=read();
            if (candidate(current,attempt)) {
                forceFile(); guard(); boolean synced=forceDirectory();
                current=read(); if (!candidate(current,attempt)) throw new OfficialException("CONFLICT");
                publish(attempt.snapshot(),current,synced); return receipt(Outcome.APPLIED);
            }
            if (same(current,attempt.before())) {
                baseline=current; pending=null; failed=false; return receipt(Outcome.NOT_APPLIED);
            }
            failed=true; throw new OfficialException("CONFLICT");
        } catch (Exception error) { throw clean(error,"RESULT_UNKNOWN"); }
    }

    /** Only an actual native management command may call this synchronous hook.
     * Binding effects must use captureOnGame -> writeOnIo -> version/memory check.
     */
    public Receipt nativeSave(Snapshot next, NativeSave save) {
        requireGame(); own(next); Objects.requireNonNull(save);
        if (!io.tryLock()) throw new OfficialException("CONFLICT");
        try {
            healthy(true);
            // Do not perform adapter-side file reads, force, replace or recovery on
            // the server thread. The vanilla callback already has synchronous I/O;
            // all verification of its outcome is deferred to recoverOnIo().
            if (pending != null || failed) throw new OfficialException("RESULT_UNKNOWN");
            if (next.sequence() < durable.sequence()) throw new OfficialException("CONFLICT");
            pending=new Pending(next,baseline,null);
            try {
                guard(); save.run(); hook.at("after_native_save");
            } catch (Exception error) { failed=true; throw new OfficialException("RESULT_UNKNOWN"); }
            throw new OfficialException("RESULT_UNKNOWN");
        } finally { unlockIo(); }
    }

    /** Coordinate a vanilla reload callback which itself performs its existing
     * synchronous I/O. This wrapper adds no stat/read/force work on the game
     * thread; its receipt remains UNKNOWN until recoverOnIo verifies the file.
     */
    public Receipt nativeLoad(NativeLoad load) {
        requireGame(); Objects.requireNonNull(load);
        if (!io.tryLock()) throw new OfficialException("CONFLICT");
        try {
            healthy(true);
            // An explicit native reload can resolve a confirmed external drift.
            // It must not replace an attempt the worker has not inspected yet.
            if (pending != null && !failed) throw new OfficialException("RESULT_UNKNOWN");
            try {
                guard();
                if (!baseline.exists()) { failed=true; throw new OfficialException("STORAGE_ERROR"); }
                long before=sequence.get();
                Snapshot next=load.run(); own(next);
                if (next.sequence() <= before || next.sequence() <= durable.sequence()) throw new OfficialException("CONFLICT");
                guard(); pending=new Pending(next,baseline,null); failed=false;
                throw new OfficialException("RESULT_UNKNOWN");
            } catch (OfficialException error) {
                throw error;
            } catch (Exception error) { failed=true; throw new OfficialException("RESULT_UNKNOWN"); }
        } finally { unlockIo(); }
    }

    private void publish(Snapshot next, Content current, boolean synced) { baseline=current; durable=next; directorySynced=synced; failed=false; pending=null; }
    private Receipt receipt(Outcome outcome) { return new Receipt(outcome,durable,directorySynced); }
    private void verifyBaseline() {
        try {
            Content current=read();
            if (!same(current,baseline)) { failed=true; throw new OfficialException("CONFLICT"); }
        } catch (Exception error) { failed=true; throw clean(error,"STORAGE_ERROR"); }
    }
    private boolean candidate(Content content, Pending attempt) {
        return content.exists() && content.profiles().equals(attempt.snapshot().profiles())
            && (attempt.expectedHash() == null || MessageDigest.isEqual(content.hash(),attempt.expectedHash()));
    }
    private static boolean same(Content a, Content b) {
        return a.exists() == b.exists() && (!a.exists() || MessageDigest.isEqual(a.hash(),b.hash()));
    }
    private void own(Snapshot snapshot) { if (snapshot == null || snapshot.owner != owner) throw new OfficialException("SCOPE_MISMATCH"); }
    private boolean sourceActive() {
        try { return !closed && active.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }
    private boolean gameThreadNow() {
        try { return onGameThread.getAsBoolean(); }
        catch (RuntimeException ignored) { throw new OfficialException("NOT_SUPPORTED"); }
    }
    private void guard() { if (!sourceActive()) throw new OfficialException("NOT_SUPPORTED"); }
    private void requireGame() {
        guard(); if (!gameThreadNow()) throw new OfficialException("CONFLICT");
        gameThread.compareAndSet(null,Thread.currentThread());
        if (gameThread.get() != Thread.currentThread()) throw new OfficialException("CONFLICT");
    }
    private void requireIo() { guard(); if (gameThreadNow()) throw new OfficialException("NOT_SUPPORTED"); }
    private void healthy(boolean allowRecovery) {
        guard();
        if (!initialized || fileLock == null || !fileLock.isValid() || failed && !allowRecovery) throw new OfficialException("STORAGE_ERROR");
    }
    private void paths() throws IOException {
        Path current=path.getRoot();
        if (current == null || !Files.isDirectory(path.getParent(),LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        for (Path segment : path) { current=current.resolve(segment); if (Files.isSymbolicLink(current)) throw new IOException(); }
    }
    private Content read() throws IOException {
        guard(); paths();
        if (!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return new Content(false,List.of(),null);
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        byte[] raw;
        try (var stream=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) { raw=stream.readNBytes(MAX_BYTES + 1); }
        if (raw.length > MAX_BYTES) throw new OfficialException("LIMIT_EXCEEDED");
        String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString();
        try (JsonReader reader=new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            reader.beginArray(); List<Profile> result=new ArrayList<>();
            while (reader.hasNext()) {
                if (result.size() >= MAX_PROFILES) throw new OfficialException("LIMIT_EXCEEDED");
                reader.beginObject(); Set<String> fields=new HashSet<>(); String id=null, name=null;
                while (reader.hasNext()) {
                    String key=reader.nextName();
                    if (!Set.of("uuid","name").contains(key) || !fields.add(key) || reader.peek() != JsonToken.STRING)
                        throw new IOException();
                    if (key.equals("uuid")) id=reader.nextString(); else name=reader.nextString();
                }
                reader.endObject();
                if (!fields.equals(Set.of("uuid","name"))) throw new IOException();
                UUID uuid=UUID.fromString(id);
                if (!uuid.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
                result.add(new Profile(uuid,name));
            }
            reader.endArray(); if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException();
            return new Content(true,normalize(result),digest(raw));
        } catch (OfficialException error) { throw new OfficialException(error.code().equals("LIMIT_EXCEEDED") ? "LIMIT_EXCEEDED" : "STORAGE_ERROR"); }
        catch (Exception error) { throw new OfficialException("STORAGE_ERROR"); }
    }
    private static List<Profile> normalize(List<Profile> profiles) {
        if (profiles == null) throw new OfficialException("INVALID_INPUT");
        if (profiles.size() > MAX_PROFILES) throw new OfficialException("LIMIT_EXCEEDED");
        List<Profile> copy=new ArrayList<>(profiles); Set<UUID> ids=new HashSet<>();
        for (Profile profile : copy) if (profile == null || !ids.add(profile.uuid())) throw new OfficialException("CONFLICT");
        copy.sort(Comparator.comparing(profile -> profile.uuid().toString()));
        return List.copyOf(copy);
    }
    private static byte[] encode(List<Profile> profiles) {
        JsonArray array=new JsonArray();
        for (Profile profile : profiles) {
            JsonObject row=new JsonObject(); row.addProperty("uuid",profile.uuid().toString()); row.addProperty("name",profile.name()); array.add(row);
        }
        byte[] raw=JSON.toJson(array).getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_BYTES) throw new OfficialException("LIMIT_EXCEEDED");
        return raw;
    }
    private static byte[] digest(byte[] raw) {
        try { return MessageDigest.getInstance("SHA-256").digest(raw); }
        catch (Exception error) { throw new OfficialException("STORAGE_ERROR"); }
    }
    private void forceFile() throws IOException {
        guard(); paths();
        try (FileChannel file=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) { file.force(true); }
        hook.at("after_file_force");
    }
    private boolean forceDirectory() throws IOException {
        guard(); paths();
        if (System.getProperty("os.name","").startsWith("Windows")) return false;
        try (FileChannel directory=FileChannel.open(path.getParent(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) { directory.force(true); }
        return true;
    }
    private static OfficialException clean(Exception error, String fallback) {
        return error instanceof OfficialException official ? official : new OfficialException(fallback);
    }
    private void release() {
        try { if (fileLock != null) fileLock.close(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
        fileLock=null; lockChannel=null;
    }
    @Override public void close() { closed=true; if (io.tryLock()) { try { release(); } finally { io.unlock(); } } }
    @Override public String toString() { return "OfficialWhitelistFile[redacted]"; }
}
