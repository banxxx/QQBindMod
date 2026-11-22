package com.poso.qqbind.official;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** One bounded I/O queue per runtime. Worker tasks carry no game objects. */
public final class OfficialLoginCodes implements AutoCloseable {
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(3);

    public static final class Login {
        private final OfficialLoginCodes owner;
        private final UUID playerId;
        final String gameId, gameKey;
        final AtomicBoolean connected = new AtomicBoolean(true);
        private final AtomicBoolean pending = new AtomicBoolean();
        private long retryAfter;
        private boolean requested;

        private Login(OfficialLoginCodes owner, UUID playerId, String gameId) {
            this.owner = owner;
            this.playerId = Objects.requireNonNull(playerId);
            this.gameId = gameId;
            this.gameKey = OfficialScope.key(gameId);
        }

        public UUID playerId() { return playerId; }

        public void leave() { owner.leave(this); }
    }

    public record Notice(Login login, String tokenGeneration, boolean awaitingConfirmation) { }

    private final OfficialTokenService tokens;
    private final Predicate<String> eligible;
    private final Function<Login, OfficialTokenService.Issued> issue;
    private final Consumer<Notice> notifyGame;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), task -> {
                Thread thread = new Thread(task, "QQBind-OfficialLoginCodes");
                thread.setDaemon(true);
                return thread;
            });
    private volatile boolean closed;

    Future<?> submitMaintenance(Runnable task) {
        if (closed) throw new RejectedExecutionException();
        return worker.submit(task);
    }

    OfficialLoginCodes(OfficialTokenService tokens, Predicate<String> eligible,
                       Function<Login, OfficialTokenService.Issued> issue, Consumer<Notice> notifyGame) {
        this.tokens = Objects.requireNonNull(tokens);
        this.eligible = Objects.requireNonNull(eligible);
        this.issue = Objects.requireNonNull(issue);
        this.notifyGame = Objects.requireNonNull(notifyGame);
    }

    public Login login(UUID playerId, String gameId) { return new Login(this, playerId, gameId); }

    public boolean eligible(Login login) {
        return login.owner == this && !closed && login.connected.get() && eligible.test(login.gameId);
    }

    /** Game-thread read only. The generation can change before a queued notice runs. */
    public OfficialTokenService.Issued current(Login login) {
        return eligible(login) ? tokens.current(login) : null;
    }

    /** Called by the game thread. Repeated prompts share one queued request. */
    public boolean request(Login login) {
        if (!eligible(login)) return false;
        if (login.pending.get()) return true;
        long now = System.nanoTime();
        if (login.requested && now - login.retryAfter < 0) return false;
        if (!login.pending.compareAndSet(false, true)) return true;
        login.requested = true;
        login.retryAfter = now + RETRY_NANOS;
        try {
            worker.execute(() -> prepare(login));
            return true;
        } catch (RejectedExecutionException ignored) {
            login.pending.set(false);
            return false;
        }
    }

    private void prepare(Login login) {
        try {
            if (!eligible(login)) return;
            Notice notice;
            try {
                OfficialTokenService.Issued issued = issue.apply(login);
                notice = new Notice(login, issued.tokenGeneration(), false);
            } catch (OfficialException e) {
                notice = new Notice(login, null, e.code().equals("RESULT_UNKNOWN"));
            } catch (RuntimeException e) {
                notice = new Notice(login, null, false);
            }
            if (eligible(login)) {
                try { notifyGame.accept(notice); }
                catch (RuntimeException ignored) { /* The next regular game prompt can read the live generation. */ }
            }
        } finally {
            login.pending.set(false);
            if (!closed) tokens.purgeInactiveCodes();
        }
    }

    /** Hide and invalidate the code immediately, even if the cleanup queue is full. */
    private void leave(Login login) {
        if (!login.connected.getAndSet(false) || closed) return;
        try {
            worker.execute(() -> {
                if (!closed) tokens.purgeInactiveCodes();
            });
        } catch (RejectedExecutionException ignored) {
            // Every queued issue also sweeps disconnected codes. It never touches reservations.
        }
    }

    @Override public void close() {
        closed = true;
        worker.shutdown();
        worker.getQueue().clear();
    }
}
