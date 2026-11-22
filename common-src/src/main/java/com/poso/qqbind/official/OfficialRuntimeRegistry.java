package com.poso.qqbind.official;

import java.sql.Driver;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.*;
import java.util.function.Supplier;

/** Actual process-local selection/configuration leases, not a caller auth flag.
 * Source changes after pinning require a full process restart. Main-thread
 * configuration attempts never wait for an I/O reader to release its lease.
 */
public final class OfficialRuntimeRegistry {
    private final ReentrantReadWriteLock lock=new ReentrantReadWriteLock(true);
    private volatile OfficialRuntimeSettings current;
    private volatile Object owner;
    private volatile boolean stopping;
    private volatile boolean pinned;
    public void apply(OfficialRuntimeSettings candidate,Runnable publishLegacyFields) {
        Objects.requireNonNull(candidate); Objects.requireNonNull(publishLegacyFields);
        if (!lock.writeLock().tryLock()) throw new OfficialException("CONFLICT");
        try {
            if (pinned && current == null) throw new OfficialException("RESTART_REQUIRED");
            if (pinned && current != null && (current.official() != candidate.official()
                    || current.official() && !current.sameRuntime(candidate))) throw new OfficialException("RESTART_REQUIRED");
            publishLegacyFields.run();
            if (!(pinned && current != null && current.official())) current=candidate;
        } finally { lock.writeLock().unlock(); }
    }
    public OfficialRuntimeSettings pin() {
        if (!lock.writeLock().tryLock()) throw new OfficialException("CONFLICT");
        try { pinned=true; if (current == null) throw new OfficialException("NOT_SUPPORTED"); return current; }
        finally { lock.writeLock().unlock(); }
    }
    public boolean legacyAllowed() { OfficialRuntimeSettings value=current; return value != null && !value.official(); }
    public boolean officialSelected() { OfficialRuntimeSettings value=current; return value != null && value.official(); }
    public boolean configured() { return current != null; }
    public boolean canCreateDefault() { return current == null && !pinned; }
    public boolean active(Object expected) { return expected != null && owner == expected && !stopping; }
    void register(Object expected,OfficialRuntimeSettings settings) {
        if (!lock.writeLock().tryLock()) throw new OfficialException("CONFLICT");
        try {
            if (!pinned || current != settings || !settings.official() || owner != null) throw new OfficialException("CONFLICT"); owner=expected; stopping=false;
        } finally { lock.writeLock().unlock(); }
    }
    final class Lease implements OfficialMirrorAuthority.Lease {
        private final Object expected;
        private final OfficialRuntimeSettings settings;
        private final Thread thread=Thread.currentThread();
        private boolean closed;
        Lease(Object expected,OfficialRuntimeSettings settings) { this.expected=expected; this.settings=settings; }
        public OfficialScope scope() { return settings.scope(); }
        public String authority() { return "center"; }
        public String configurationRevision() { return settings.revision(); }
        public boolean active() { return !closed && OfficialRuntimeRegistry.this.active(expected); }
        public boolean mayPublishCommitted() { return settings.allowCenterSync(); }
        public String bearerToken() { if (!active()) throw new OfficialException("NOT_SUPPORTED"); return settings.bearerToken(); }
        public void close() {
            if (Thread.currentThread() != thread) throw new OfficialException("CONFLICT");
            if (!closed) { closed=true; lock.readLock().unlock(); }
        }
        @Override public String toString() { return "OfficialConfigurationLease[redacted]"; }
    }
    Lease acquire(Object expected,OfficialScope scope) {
        lock.readLock().lock();
        try {
            OfficialRuntimeSettings settings=current;
            if (!active(expected) || settings == null || !settings.official() || settings.createFresh()) throw new OfficialException("NOT_SUPPORTED");
            if (!settings.scope().equals(scope)) throw new OfficialException("SCOPE_MISMATCH"); return new Lease(expected,settings);
        } catch (Exception e) { lock.readLock().unlock(); throw e; }
    }
    OfficialJdbcCenter.Connections connections(Object expected,OfficialRuntimeSettings settings,Supplier<Driver> driver,OfficialJdbcConnections.Access access) {
        return () -> {
            try (Lease ignored=acquire(expected,settings.scope())) {
                return new OfficialJdbcConnections(driver.get(),settings::databaseProperties,() -> active(expected),access).open();
            } catch (OfficialException e) { throw e; }
            catch (Exception e) { throw new OfficialException("DB_UNAVAILABLE"); }
        };
    }
    boolean stop(Object expected,Runnable release,long timeoutMillis) {
        if (timeoutMillis < 0 || timeoutMillis > 5000) throw new OfficialException("INVALID_INPUT");
        if (owner != expected) return owner == null; stopping=true;
        boolean acquired=false;
        try {
            acquired=lock.writeLock().tryLock(timeoutMillis,TimeUnit.MILLISECONDS);
            if (!acquired) return false;
            if (owner == expected) { release.run(); owner=null; } return true;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        finally { if (acquired) lock.writeLock().unlock(); }
    }
    /** Official mode only exposes the already-approved read queries on old API. */
    public boolean allowsLegacyHttp(String method,String path) {
        if (legacyAllowed()) return true;
        return officialSelected() && "GET".equals(method) && path != null
            && (path.equals("/api/status") || path.equals("/api/tps") || path.equals("/api/stats")
                || path.startsWith("/api/stats/") && path.substring("/api/stats/".length()).matches("[A-Za-z0-9_]{1,16}"));
    }
}
