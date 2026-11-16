package io.github.center2055.developermcp.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Checks the access token in constant time and locks out addresses that keep guessing. */
public final class AuthGuard {

    public enum Result { OK, DENIED, BLOCKED }

    static final int MAX_FAILURES = 30;
    static final long WINDOW_MILLIS = 10 * 60_000L;
    static final long BLOCK_MILLIS = 10 * 60_000L;

    private static final class Attempts {
        long windowStart;
        int failures;
        long blockedUntil;
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final Logger logger;
    private final LongSupplier clock;
    private volatile byte[] tokenHash;

    public AuthGuard(String token, Logger logger) {
        this(token, logger, System::currentTimeMillis);
    }

    AuthGuard(String token, Logger logger, LongSupplier clock) {
        this.logger = logger;
        this.clock = clock;
        setToken(token);
    }

    public void setToken(String token) {
        this.tokenHash = sha256(token);
        attempts.clear();
    }

    /** @param candidates tokens the request carries (Authorization header, ?token=); nulls are ignored */
    public Result check(String address, String... candidates) {
        long now = clock.getAsLong();
        Attempts a = attempts.get(address);
        if (a != null && a.blockedUntil > now) {
            return Result.BLOCKED;
        }
        for (String candidate : candidates) {
            if (candidate != null && MessageDigest.isEqual(sha256(candidate), tokenHash)) {
                return Result.OK;
            }
        }
        return recordFailure(address, now);
    }

    private Result recordFailure(String address, long now) {
        Attempts a = attempts.computeIfAbsent(address, k -> new Attempts());
        synchronized (a) {
            if (now - a.windowStart > WINDOW_MILLIS) {
                a.windowStart = now;
                a.failures = 0;
            }
            a.failures++;
            if (a.failures >= MAX_FAILURES) {
                a.blockedUntil = now + BLOCK_MILLIS;
                a.failures = 0;
                logger.warning("Blocked " + address + " for " + (BLOCK_MILLIS / 60_000)
                        + " minutes after " + MAX_FAILURES + " requests with a wrong token.");
                return Result.BLOCKED;
            }
        }
        if (attempts.size() > 10_000) {
            prune(now);
        }
        return Result.DENIED;
    }

    private void prune(long now) {
        for (Iterator<Attempts> it = attempts.values().iterator(); it.hasNext(); ) {
            Attempts a = it.next();
            if (a.blockedUntil < now && now - a.windowStart > WINDOW_MILLIS) {
                it.remove();
            }
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
