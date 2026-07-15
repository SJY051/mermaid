package com.mermaid.common;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runs a handful of blocking upstream calls at once and waits for all of them.
 *
 * <p>data.go.kr answers in about 1.5 seconds. Assembling one drug takes six calls and one chat turn
 * assembles three, so done in sequence a single question spends half a minute waiting on a network
 * that was idle almost the whole time.
 *
 * <p>This is a servlet application: the request thread is going to block anyway. What it must not do
 * is block <i>once per call</i>. {@code boundedElastic} exists for exactly this — parking a blocking
 * call on a worker so several can be in flight together — and we bound the fan-out ourselves so a
 * burst of chat requests cannot open thirty sockets to the ministry at once.
 *
 * <p>Order is preserved: {@code flatMapSequential} runs the calls concurrently but emits results in
 * the order the inputs were given. Ranking upstream of this class therefore still means something,
 * and the tests stay deterministic.
 */
public final class Parallel {

    private Parallel() {}

    /**
     * One blocking call, parked on a worker so a caller can start several before waiting.
     *
     * <p>Pair it with {@link Mono#zip} when the calls return different types:
     *
     * <pre>{@code
     * var both = Mono.zip(Parallel.async(() -> a.fetch()), Parallel.async(() -> b.fetch())).block();
     * }</pre>
     *
     * @param call <b>must not return null</b> — {@code Mono.fromCallable} treats null as "empty", and
     *     an empty leg makes {@code Mono.zip} emit nothing at all. Return an Optional instead.
     */
    public static <T> Mono<T> async(Callable<T> call) {
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        return Mono.fromCallable(() -> withMdc(callerMdc, call))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * @param concurrency how many calls may be in flight at once
     * @param fn <b>must not return null</b> — a null would be silently dropped from the result,
     *     shifting every later index. Return an empty Optional or an empty list instead.
     * @return one result per input, in input order
     */
    public static <T, R> List<R> map(List<T> items, int concurrency, Function<T, R> fn) {
        if (items.isEmpty()) {
            return List.of();
        }
        // One item is not worth a thread hop, and staying on the caller's thread keeps its stack
        // trace and its logging context intact.
        if (items.size() == 1) {
            return List.of(fn.apply(items.get(0)));
        }
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        List<R> results =
                Flux.fromIterable(items)
                        .flatMapSequential(
                                item -> Mono.fromCallable(() -> withMdc(callerMdc, () -> fn.apply(item)))
                                        .subscribeOn(Schedulers.boundedElastic()),
                                concurrency)
                        .collectList()
                        .block();
        return results == null ? List.of() : results;
    }

    static <T> T withMdc(Map<String, String> callerMdc, Callable<T> call) throws Exception {
        Map<String, String> workerMdc = MDC.getCopyOfContextMap();
        restoreMdc(callerMdc);
        try {
            return call.call();
        } finally {
            restoreMdc(workerMdc);
        }
    }

    private static void restoreMdc(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
