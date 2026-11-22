package com.poso.qqbind.official;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Request-bound production source gate around a center-owned mirror.
 * The host supplies a real configuration lease. No config/credential lookup,
 * HTTP server, database connection, or worker is started by this class.
 */
public final class OfficialMirrorAuthority implements AutoCloseable {
    public static final String SYNC_PATH="/api/v2/binding/sync";
    static final long PERMIT_NANOS=30_000_000_000L, SNAPSHOT_NANOS=60_000_000_000L;
    public interface Lease extends AutoCloseable {
        OfficialScope scope();
        String authority();
        String configurationRevision();
        boolean active();
        boolean mayPublishCommitted();
        String bearerToken();
        @Override void close();
    }
    public interface LeaseProvider { Lease acquire(OfficialScope scope); }
    /** Not constructible from JSON or an authenticated=true flag. No token retained. */
    public static final class Permit {
        private final OfficialMirrorAuthority owner;
        private final String revision;
        private final long issued;
        private final AtomicBoolean used=new AtomicBoolean();
        private Permit(OfficialMirrorAuthority owner,String revision,long issued) { this.owner=owner; this.revision=revision; this.issued=issued; }
        @Override public String toString() { return "OfficialMirrorPermit[opaque]"; }
    }
    private record Proof(String operation,JsonArray rows,Lease lease,String revision) { }
    private final OfficialScope scope;
    private final LeaseProvider provider;
    private final LongSupplier nanos;
    private final ThreadLocal<Proof> proof=new ThreadLocal<>();
    private final OfficialMirrorService mirror;
    private volatile boolean closed;

    public OfficialMirrorAuthority(OfficialScope scope,OfficialModState state,LeaseProvider provider,BooleanSupplier onGameThread) {
        this(scope,state,provider,onGameThread,System::nanoTime);
    }
    OfficialMirrorAuthority(OfficialScope scope,OfficialModState state,LeaseProvider provider,BooleanSupplier onGameThread,LongSupplier nanos) {
        this.scope=Objects.requireNonNull(scope); this.provider=provider; this.nanos=Objects.requireNonNull(nanos);
        this.mirror=new OfficialMirrorService(scope,state,new OfficialMirrorService.Confirmation() {
            public void committed(OfficialScope source,String operation,JsonArray effects) { confirm(source,operation,effects); }
            public void snapshot(OfficialScope source,JsonArray rows) { confirm(source,null,rows); }
        },onGameThread);
    }
    /** Game/outbox operations remain available; direct sync/merge has no proof. */
    public OfficialMirrorService mirror() { return mirror; }
    OfficialScope scope() { return scope; }
    long now() { return nanos.getAsLong(); }
    static boolean expired(long issued,long current,long budget) { long elapsed=current-issued; return elapsed < 0 || elapsed >= budget; }
    static void release(Lease lease) { if (lease != null) try { lease.close(); } catch (Exception ignored) { } }
    private static String revision(Lease lease) { String value=lease.configurationRevision(); OfficialScope.revision(value); return value; }
    void check(Lease lease,String expected,boolean publish) {
        try {
            if (closed || !lease.active()) throw new OfficialException("NOT_SUPPORTED");
            if (!scope.equals(lease.scope()) || !"center".equals(lease.authority())) throw new OfficialException("SCOPE_MISMATCH");
            if (!revision(lease).equals(expected) || publish && !lease.mayPublishCommitted()) throw new OfficialException("UNAUTHORIZED");
        } catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("UNAUTHORIZED"); }
    }
    Lease acquire() {
        if (closed || provider == null) throw new OfficialException("NOT_SUPPORTED"); Lease lease=null;
        try {
            lease=provider.acquire(scope);
            if (lease == null) throw new OfficialException("UNAUTHORIZED");
            check(lease,revision(lease),false); return lease;
        } catch (OfficialException e) { release(lease); throw e; }
        catch (Exception e) { release(lease); throw new OfficialException("UNAUTHORIZED"); }
    }
    private void requestScope(OfficialScope source) { if (!scope.equals(source)) throw new OfficialException("SCOPE_MISMATCH"); }

    /** Verify the configured center-publisher credential, not a general read flag.
     * Token rotation/source changes must increment configurationRevision.
     */
    public Permit authorize(OfficialScope source,String method,String path,String authorization) {
        requestScope(source);
        if (!"POST".equals(method) || !SYNC_PATH.equals(path)) throw new OfficialException("NOT_SUPPORTED");
        if (authorization == null || authorization.length() > 1031 || !authorization.startsWith("Bearer ")
                || authorization.chars().anyMatch(c -> c < 32 || c >= 127)) throw new OfficialException("UNAUTHORIZED");
        Lease lease=acquire();
        try {
            String current=revision(lease); check(lease,current,true); String token=lease.bearerToken();
            if (token == null || token.length() < 16 || token.length() > 1024 || token.equals("change-me-to-a-random-token")
                    || token.chars().anyMatch(c -> c <= 32 || c >= 127)
                    || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.US_ASCII),authorization.getBytes(StandardCharsets.US_ASCII))) {
                throw new OfficialException("UNAUTHORIZED");
            }
            check(lease,current,true); return new Permit(this,current,now());
        } catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("UNAUTHORIZED"); }
        finally { release(lease); }
    }

    private interface Work<T> { T run(); }
    private <T> T verified(String operation,JsonArray rows,Lease lease,String revision,Work<T> work) {
        if (proof.get() != null) throw new OfficialException("CONFLICT");
        check(lease,revision,operation != null); proof.set(new Proof(operation,rows.deepCopy(),lease,revision));
        try { return work.run(); }
        catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("STORAGE_ERROR"); }
        finally { proof.remove(); }
    }
    private void confirm(OfficialScope source,String operation,JsonArray rows) {
        Proof current=proof.get();
        if (current == null || !scope.equals(source) || !Objects.equals(current.operation(),operation) || !current.rows().equals(rows)) throw new OfficialException("UNAUTHORIZED");
        check(current.lease(),current.revision(),operation != null);
    }
    /** A permit is consumed even when persistence fails; retry authenticates anew. */
    public JsonObject sync(Permit permit,String operation,JsonArray incoming) {
        if (permit == null || permit.owner != this || !permit.used.compareAndSet(false,true)
                || expired(permit.issued,now(),PERMIT_NANOS)) throw new OfficialException("UNAUTHORIZED");
        OfficialScope.uuid(operation); JsonArray fixed;
        try { fixed=OfficialMirrorModel.effects(incoming,500); }
        catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("INVALID_INPUT"); }
        Lease lease=acquire();
        try { return verified(operation,fixed,lease,permit.revision,() -> mirror.sync(scope,operation,fixed)); }
        finally { release(lease); }
    }
    public OfficialJdbcSnapshot snapshots(OfficialJdbcCenter.Connections connections) { return new OfficialJdbcSnapshot(this,connections); }
    /** Only a private, single-use result from this authority's JDBC reader enters. */
    public void merge(OfficialJdbcSnapshot.Snapshot snapshot) {
        if (snapshot == null) throw new OfficialException("UNAUTHORIZED");
        JsonArray rows=snapshot.claim(this,now()); Lease lease=acquire();
        try { verified(null,rows,lease,snapshot.revision(),() -> { mirror.mergeSnapshot(scope,rows); return null; }); }
        finally { release(lease); }
    }
    @Override public void close() { closed=true; mirror.close(); proof.remove(); }
}
