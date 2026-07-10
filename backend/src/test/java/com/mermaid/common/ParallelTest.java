package com.mermaid.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ParallelTest {

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("results come back in input order, whatever order the calls finish in")
    void preservesOrder() {
        // The slowest call is first. A merge that emitted on completion would put it last, and the
        // whole point of ranking candidates before this call would be lost.
        List<Integer> delays = List.of(120, 60, 10, 80);

        List<Integer> out = Parallel.map(delays, 4, d -> {
            sleep(d);
            return d;
        });

        assertThat(out).containsExactly(120, 60, 10, 80);
    }

    @Test
    @DisplayName("the calls really do overlap — four 100ms calls finish in well under 400ms")
    void actuallyRunsConcurrently() {
        Instant started = Instant.now();

        Parallel.map(List.of(1, 2, 3, 4), 4, i -> {
            sleep(100);
            return i;
        });

        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofMillis(350));
    }

    @Test
    @DisplayName("concurrency is bounded — with a limit of 1 the calls are serial")
    void respectsTheConcurrencyLimit() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        Parallel.map(List.of(1, 2, 3, 4, 5, 6), 2, i -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            sleep(30);
            inFlight.decrementAndGet();
            return i;
        });

        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a single item stays on the caller's thread — no hop, no scheduler")
    void singleItemDoesNotHop() {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        String caller = Thread.currentThread().getName();

        Parallel.map(List.of(1), 4, i -> {
            threads.add(Thread.currentThread().getName());
            return i;
        });

        assertThat(threads).containsExactly(caller);
    }

    @Test
    @DisplayName("an empty input calls nothing and returns nothing")
    void emptyIsEmpty() {
        AtomicInteger calls = new AtomicInteger();

        assertThat(Parallel.<Integer, Integer>map(List.of(), 4, i -> {
            calls.incrementAndGet();
            return i;
        })).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("an exception in one call surfaces to the caller, unwrapped")
    void propagatesFailure() {
        assertThatThrownBy(() -> Parallel.map(List.of(1, 2, 3), 3, i -> {
            if (i == 2) {
                throw new IllegalStateException("upstream said no");
            }
            return i;
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("upstream said no");
    }

    @Test
    @DisplayName("async() lets Mono.zip fetch different types at once")
    void asyncZips() {
        var both = Mono.zip(
                        Parallel.async(() -> {
                            sleep(80);
                            return Optional.of("permitted");
                        }),
                        Parallel.async(() -> {
                            sleep(80);
                            return List.of("warning");
                        }))
                .block();

        assertThat(both).isNotNull();
        assertThat(both.getT1()).contains("permitted");
        assertThat(both.getT2()).containsExactly("warning");
    }

    @Test
    @DisplayName("a null from async() empties the leg and zip yields nothing — hence the javadoc")
    void asyncNullIsNotAValue() {
        // Documenting the trap rather than defending against it: Mono.fromCallable treats null as an
        // empty signal, so a leg returning null makes the whole zip complete without emitting.
        // Every caller returns Optional or a list for this reason.
        var zipped = Mono.zip(Parallel.async(() -> "present"), Parallel.async(() -> (String) null)).block();

        assertThat(zipped).isNull();
    }
}
