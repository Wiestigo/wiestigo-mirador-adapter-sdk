package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.SyncRunContext;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The retries of a binding's HTTP path, decided as in the host: whether an attempt is repeated, how long the host
 * would wait before it, and the waits planned so far. The TCK itself sleeps only a shortened wait.
 */
final class TckHttpRetries {

    /** The waits without {@code Retry-After}: before the first, second and third retry; same as the host. */
    private static final Duration[] BACKOFF = {Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8)};
    /** Response statuses after which the HTTP path retries; same as the host. */
    private static final Set<Integer> RETRIED_STATUS = Set.of(429, 503);

    private final int maxRetries;
    private final Duration longestRealWait;
    /** The planned waits before retries, in order. */
    private final List<Duration> waits = new CopyOnWriteArrayList<>();

    /**
     * @param maxRetries      maximum number of retries of an attempt
     * @param longestRealWait the longest time the TCK really sleeps before a retry
     */
    TckHttpRetries(int maxRetries, Duration longestRealWait) {
        this.maxRetries = maxRetries;
        this.longestRealWait = longestRealWait;
    }

    /** The waits before retries so far, as the host would have waited them. */
    List<Duration> waits() {
        return List.copyOf(waits);
    }

    /**
     * The wait before retry number {@code retry} (from 1) after a failed attempt: 2, 4, 8 s after a transient
     * failure; empty = do not retry.
     */
    Optional<Duration> afterFailure(AdapterRequest request, SyncRunContext run, long deadline,
                                    AdapterException failed, int retry) {
        return permitted(request, run, deadline, failed.isRetryable() ? Optional.of(backOff(retry))
                : Optional.empty(), retry, false);
    }

    /**
     * The wait before retry number {@code retry} (from 1) after a response: {@code Retry-After} or 2, 4, 8 s
     * after 429 or 503; empty = do not retry.
     */
    Optional<Duration> afterAnswer(AdapterRequest request, SyncRunContext run, long deadline, TckHttpAnswer answer,
                                   int retry) {
        return RETRIED_STATUS.contains(answer.status())
                ? permitted(request, run, deadline, Optional.of(retryAfter(answer, retry)), retry,
                answer.status() == 429)
                : Optional.empty();
    }

    /**
     * The wait before retry number {@code retry} (from 1); empty = do not retry. The decision is the host's: only
     * {@link AdapterRequest#repeatable()} requests, at most {@code maxRetries} times, never in an interrupted
     * thread, and never if the wait would end after the request's deadline, the run's deadline has passed or an
     * active wait (not after 429) would exceed it.
     */
    private Optional<Duration> permitted(AdapterRequest request, SyncRunContext run, long deadline,
                                         Optional<Duration> wanted, int retry, boolean throttledByTool) {
        if (wanted.isEmpty() || !request.repeatable() || retry > maxRetries
                || Thread.currentThread().isInterrupted()) {
            return Optional.empty();
        }
        Duration wait = wanted.get();
        if (deadline - System.nanoTime() - wait.toNanos() < 0) {
            return Optional.empty();
        }
        if (run != null && run.deadline() != null) {
            Duration left = Duration.between(Instant.now(), run.deadline());
            if (left.isZero() || left.isNegative() || (!throttledByTool && left.compareTo(wait) <= 0)) {
                return Optional.empty();
            }
        }
        return Optional.of(wait);
    }

    /** The wait without {@code Retry-After} before retry number {@code retry} (from 1): 2, 4, 8 s. */
    private static Duration backOff(int retry) {
        return BACKOFF[Math.min(retry, BACKOFF.length) - 1];
    }

    /**
     * The wait after a 429 or 503: {@code Retry-After} at most {@link TckAdapterHttp#MAX_RETRY_AFTER}, otherwise
     * 2, 4, 8 s.
     */
    private static Duration retryAfter(TckHttpAnswer answer, int retry) {
        Duration backOff = backOff(retry);
        Optional<String> value = answer.firstHeader("Retry-After").map(String::trim);
        if (value.isEmpty() || value.get().isEmpty()) {
            return backOff;
        }
        Duration wanted;
        if (value.get().chars().allMatch(Character::isDigit)) {
            try {
                wanted = Duration.ofSeconds(Long.parseLong(value.get()));
            } catch (NumberFormatException | ArithmeticException huge) {
                wanted = TckAdapterHttp.MAX_RETRY_AFTER;
            }
        } else {
            try {
                Duration left = Duration.between(Instant.now(), ZonedDateTime.parse(value.get(),
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                wanted = left.isNegative() ? Duration.ZERO : left;
            } catch (DateTimeParseException unreadable) {
                return backOff;
            }
        }
        return wanted.compareTo(TckAdapterHttp.MAX_RETRY_AFTER) > 0 ? TckAdapterHttp.MAX_RETRY_AFTER : wanted;
    }

    /**
     * Waits before a retry, really sleeping at most the shortened wait, and returns the request's deadline charged
     * with the whole wait, so that every later decision comes out as in the host.
     */
    long waitBeforeRetry(AdapterRequest request, Duration wait, long deadline) throws AdapterException {
        waits.add(wait);
        Duration real = wait.compareTo(longestRealWait) < 0 ? wait : longestRealWait;
        try {
            TimeUnit.NANOSECONDS.sleep(real.toNanos());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AdapterException(request.method() + " request interrupted while waiting before the next"
                    + " attempt", null, false);
        }
        return deadline - (wait.toNanos() - real.toNanos());
    }
}
