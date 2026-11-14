package com.io_elements.layer_infrastructure.io_mapping;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The budget of an identifier pattern that comes from neither the adapter
 * type nor the mapping: the identifier forms of a binding are declared by
 * the service behind the connection. Such a pattern can backtrack
 * catastrophically ({@code ((a+)+)+$}) and would otherwise run for an
 * arbitrary time on the calling thread.
 *
 * <p>A budgeted pattern reads only the first {@link #MAX_INPUT_LENGTH}
 * characters of a value, and within {@link #BUDGET_NANOS} per value.
 * {@link Pattern} is final, so the clock sits in the character sequence: the
 * matcher asks for every character through {@link CharSequence#charAt}, and
 * every {@value #CHECK_EVERY}th call checks the clock. When the time runs
 * out, the value counts as <b>no match</b> (matches found before that do not
 * count either), and {@code onTimeout} is called; the caller reports the
 * timeout, without the value.</p>
 *
 * <p>Patterns from the adapter type and the mapping stay unbounded: they
 * carry {@code null} instead of a budget.</p>
 */
public final class PatternBudget {

    /** Longest prefix of a value that a budgeted pattern reads. */
    public static final int MAX_INPUT_LENGTH = 4096;
    /** Time budget of a budgeted pattern per value (100 ms). */
    public static final long BUDGET_NANOS = 100_000_000L;
    /** The character sequence checks the clock once per this many character accesses. */
    public static final int CHECK_EVERY = 256;

    private final int maxInputLength;
    private final long budgetNanos;
    private final LongSupplier clock;
    private final Runnable onTimeout;

    /** The default budget: 4096 characters, 100 ms per value. */
    public PatternBudget(Runnable onTimeout) {
        this(MAX_INPUT_LENGTH, BUDGET_NANOS, System::nanoTime, onTimeout);
    }

    /** With a custom length, budget and clock, for tests. */
    public PatternBudget(int maxInputLength, long budgetNanos, LongSupplier clock, Runnable onTimeout) {
        if (maxInputLength < 0 || budgetNanos < 0) {
            throw new IllegalArgumentException("maxInputLength and budgetNanos must not be negative");
        }
        this.maxInputLength = maxInputLength;
        this.budgetNanos = budgetNanos;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.onTimeout = onTimeout == null ? () -> { } : onTimeout;
    }

    public int maxInputLength() {
        return maxInputLength;
    }

    public long budgetNanos() {
        return budgetNanos;
    }

    /**
     * The identifiers a pattern finds in a value (every match; capturing
     * group 1 if the pattern has one, otherwise the whole match; trimmed,
     * blank ones skipped, duplicates once), under {@code budget} if it is not
     * {@code null}.
     */
    public static List<String> ids(Pattern pattern, String value, PatternBudget budget) {
        if (budget == null) {
            return idsIn(pattern.matcher(value));
        }
        return budget.ids(pattern, value);
    }

    /** The first match of a pattern in a value, under {@code budget} if it is not {@code null}. */
    public static Optional<MatchResult> firstMatch(Pattern pattern, String value, PatternBudget budget) {
        if (budget == null) {
            Matcher matcher = pattern.matcher(value);
            return matcher.find() ? Optional.of(matcher.toMatchResult()) : Optional.empty();
        }
        return budget.firstMatch(pattern, value);
    }

    /** The identifiers under this budget; none on timeout. */
    public List<String> ids(Pattern pattern, String value) {
        try {
            return idsIn(pattern.matcher(guarded(value)));
        } catch (Exceeded e) {
            onTimeout.run();
            return List.of();
        }
    }

    /** The first match under this budget; none on timeout. */
    public Optional<MatchResult> firstMatch(Pattern pattern, String value) {
        try {
            Matcher matcher = pattern.matcher(guarded(value));
            return matcher.find() ? Optional.of(matcher.toMatchResult()) : Optional.empty();
        } catch (Exceeded e) {
            onTimeout.run();
            return Optional.empty();
        }
    }

    private static List<String> idsIn(Matcher matcher) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        while (matcher.find()) {
            String id = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
            if (id != null && !id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return new ArrayList<>(ids);
    }

    /** The prefix of the value as a character sequence that checks the clock; the deadline starts now. */
    CharSequence guarded(String value) {
        String text = value == null ? "" : value;
        String head = text.length() > maxInputLength ? text.substring(0, maxInputLength) : text;
        return new DeadlineSequence(head, clock.getAsLong() + budgetNanos, clock);
    }

    /** The deadline has passed; without a stack trace, which would never be read. */
    private static final class Exceeded extends RuntimeException {
        Exceeded() {
            super(null, null, false, false);
        }
    }

    /** The character sequence that checks the clock every {@value #CHECK_EVERY} accesses. */
    private static final class DeadlineSequence implements CharSequence {
        private final String text;
        private final long deadline;
        private final LongSupplier clock;
        private int reads;

        DeadlineSequence(String text, long deadline, LongSupplier clock) {
            this.text = text;
            this.deadline = deadline;
            this.clock = clock;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if (++reads % CHECK_EVERY == 0 && clock.getAsLong() - deadline > 0) {
                throw new Exceeded();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            //Groups of a match: a plain substring, no further matching
            return text.substring(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
