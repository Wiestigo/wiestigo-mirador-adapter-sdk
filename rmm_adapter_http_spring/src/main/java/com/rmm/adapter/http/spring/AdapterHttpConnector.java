package com.rmm.adapter.http.spring;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.StreamResponse;
import com.rmm.adapter.api.SyncRunContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpResponse;
import org.springframework.http.client.reactive.ClientHttpResponseDecorator;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The Spring facade over the host's HTTP path: a {@link ClientHttpConnector}
 * on top of {@link AdapterHttp}. An adapter that uses Spring builds its
 * {@code WebClient} with it, and its {@code @HttpExchange} interfaces stay
 * unchanged, including the synchronous ones ({@code ResponseEntity<…>}):
 *
 * <pre>{@code
 * //in ToolAdapter.bind(context): one connector per binding
 * WebClient.Builder builder = WebClient.builder();
 * this.connector = AdapterHttpConnector.configure(builder, context.http());
 * WebClient client = builder.build();
 *
 * //in AdapterInstance.close()
 * connector.close();
 * }</pre>
 *
 * <ul>
 *   <li><b>Threads:</b> the HTTP facade blocks. Opening a request, collecting
 *       a buffered request body and every read of the response body
 *       therefore run on the threads of this connector, never on the
 *       subscribing thread (event loop, timer of {@code Mono.delay}). Each
 *       connector has its own threads, at most {@code threads} (default
 *       {@link #DEFAULT_THREADS}, the parallelism of a binding), with a
 *       bounded queue. A thread is started only for work that finds no idle
 *       thread (sending sequentially usually needs two, one to open and one
 *       to read the body; rarely more, in a dense sequence up to
 *       {@code threads}) and ends after {@value #IDLE_SECONDS} s without
 *       work: an idle binding holds no thread, and there is no housekeeping
 *       thread besides the working ones. The adapter builds <b>one connector
 *       per binding</b> and closes it in {@code AdapterInstance.close()},
 *       which ends the threads at once. Bindings and tenants therefore share
 *       no threads, and a slow tool occupies only its own.</li>
 *   <li><b>Run:</b> the run travels in the Reactor context and is read anew
 *       on every subscription, so it survives {@code Mono.delay},
 *       {@code retryWhen} and every thread switch; the facade passes it on as
 *       {@link AdapterRequest#run()}. It gets into the context in one of two
 *       ways:
 *       <ul>
 *         <li>{@link #configure} installs a filter as the outermost one
 *             ({@code filters.add(0, …)}). On subscription, on the hook
 *             thread, it reads the run bound there
 *             ({@link AdapterHttp#currentRun()}) or, if none is bound,
 *             records "no run" explicitly. This carries the run through
 *             synchronous {@code @HttpExchange} methods, whose proxy calls
 *             {@code block()} itself, and through filters with
 *             {@code Mono.delay} or {@code retryWhen}. A filter that the
 *             adapter later puts at position 0 itself lies outside of it and
 *             must not switch threads.</li>
 *         <li>{@code contextWrite(AdapterHttpConnector.run(run))} at the end
 *             of a reactive chain. It takes precedence over the bound run
 *             and is required if the chain switches threads and subscribes
 *             again outside the {@code WebClient} (a {@code retryWhen} on
 *             the result): no run is bound there.</li>
 *       </ul>
 *       If {@link #connect} finds neither a run nor "no run", the request
 *       fails with {@link IllegalStateException} instead of being sent
 *       silently without a run.</li>
 *   <li><b>Tenant:</b> the threads of the connector carry none. The host
 *       runs a request that arrives on a thread without a tenant in the
 *       tenant of the binding that {@code context.http()} belongs to; it
 *       rejects a thread with another tenant. The facade has nothing to do
 *       for that.</li>
 *   <li><b>Deadline clock and soft limit:</b> the host counts the request on
 *       the connector thread as a call of the run in its own right. The
 *       thread that subscribes and then waits for it in {@code block()}
 *       (also inside the proxy of a synchronous {@code @HttpExchange}
 *       method) takes the handle {@link AdapterHttp#awaitingOwnRequests()}
 *       on subscription, in the filter from {@link #configure}; the handle
 *       is closed when the exchange ends (response with headers, error or
 *       cancellation), on whatever thread. Until then only the request
 *       counts: while it waits at the rate limit, at the per-host cap or
 *       after a 429, the clock stands still, as it does through
 *       {@link AdapterHttp} directly; reading the response body afterwards
 *       counts as work. A task from {@code executor().submit} waits at its
 *       soft limit when it takes the handle, for as long as its run is
 *       paused; a stopped run makes the request fail before it is sent (the
 *       host's exception as the error of the {@code Mono}; if the wait was
 *       interrupted, with the interrupt flag set). A non-blocking thread
 *       (event loop, timer of {@code Mono.delay}) does not wait and takes
 *       no handle, and neither does a connector thread: it counts as working
 *       even if it subscribes to a request, for example in a {@code flatMap}
 *       over a body it is reading. Without {@link #configure} (only
 *       {@code contextWrite(run(…))}) there is no filter and no handle.
 *       These limitations remain:
 *       <ul>
 *         <li><b>Subscribing and continuing to work.</b> The facade assumes
 *             that the subscribing thread waits. A thread that subscribes
 *             and keeps working does not count as working until the exchange
 *             ends: the clock can then stand still although the thread
 *             works, but only while every other open slot of the run waits
 *             throttled. This is bounded by the deadline of the request at
 *             the host up to the headers (default 120 s; the handle ends
 *             with them) and by the end of the call in which the thread
 *             subscribed; a hook call during that time counts with a slot
 *             of its own. To work this way, subscribe on a non-blocking
 *             thread ({@code subscribeOn(Schedulers.parallel())}) and pass
 *             the run with {@code contextWrite(run(…))}, because none is
 *             bound there.</li>
 *         <li><b>Concurrency in {@code flatMap}.</b> With
 *             {@code Flux.flatMap(…, n)} the subscribing thread subscribes
 *             only to the first {@code n} requests; {@code flatMap}
 *             subscribes to every further one on the connector thread on
 *             which the previous one ended. No run is bound there: without
 *             {@code contextWrite(run(…))} these requests go without a run,
 *             take no handle and, in a task from {@code submit}, pass no
 *             soft limit. An adapter therefore sends concurrently through
 *             {@code executor().submit}, not through {@code flatMap}.</li>
 *         <li><b>{@code retryWhen} on the result.</b> The handle of the
 *             first subscription ends with its error; a timer subscribes to
 *             the further attempts and takes none. During these attempts the
 *             thread that waits in {@code block()} counts as working: the
 *             clock then also runs while an attempt waits at the rate limit.
 *             Retries belong in a filter (where the one handle spans all
 *             attempts) or are left to the host.</li>
 *       </ul></li>
 *   <li><b>Per request</b> (attributes of the {@code WebClient}, for example
 *       {@code .attribute(AdapterHttpConnector.IDEMPOTENT, true)},
 *       {@code @RequestAttribute} or a filter per path):
 *       {@link #IDEMPOTENT} marks a reading POST as repeatable
 *       ({@link AdapterRequest.Builder#idempotent}); an upload is still
 *       never repeated. {@link #TIMEOUT} sets the deadline of the request at
 *       the host ({@link AdapterRequest.Builder#timeout}); its expiry is
 *       transient and may be retried. These are deliberately not context
 *       keys: the context applies to the whole chain, so a create call in
 *       the same chain as a search would be marked as well.</li>
 *   <li><b>Streams in both directions:</b> the response always comes through
 *       {@link AdapterHttp#open}; its body flows chunk by chunk with
 *       backpressure, and Spring itself limits buffered bodies
 *       ({@code maxInMemorySize}). A request body with a
 *       {@code Content-Length} above {@link #BUFFERED_BODY_LIMIT} flows as a
 *       {@link com.rmm.adapter.api.Body#ofStream stream}. If the host waits
 *       for it longer than the deadline of the request (without
 *       {@link #TIMEOUT}: {@link #BODY_WAIT}), its read ends with an
 *       {@link IOException}; if the request fails or is cancelled, the
 *       stream is closed (after a response the host closes it once it has
 *       sent it). The facade buffers smaller bodies as bytes, so that the
 *       host may repeat them ({@link AdapterRequest#repeatable()}). Peak
 *       memory per buffered request is about twice the body (the collected
 *       bytes and the copy made by {@link com.rmm.adapter.api.Body#ofBytes}),
 *       that is 8 MiB at the limit; buffering happens before a connector
 *       thread is occupied, so the number of threads does not bound it. A
 *       body without {@code Content-Length} is buffered up to this limit; a
 *       larger one fails. {@code multipart/*} always counts as an upload and
 *       is never repeated. {@link AdapterRequest} rejects GET and HEAD with
 *       a non-empty body ({@link IllegalStateException}).</li>
 *   <li><b>Cancellation:</b> unsubscribing ({@code dispose}, {@code take},
 *       {@code timeout}) cancels the request: before the headers the facade
 *       interrupts the open call and closes a response that arrives anyway;
 *       afterwards it closes the stream ({@link StreamResponse#close()} =
 *       abort), even if reading has not started yet.</li>
 *   <li><b>Headers:</b> several values per name are preserved, in both
 *       directions. The host sets whatever determines the transport
 *       ({@code Host}, {@code Content-Length}, {@code Transfer-Encoding},
 *       {@code Connection}, {@code Accept-Encoding} …); the facade drops
 *       such headers set by Spring or the adapter instead of letting the
 *       request fail. {@code Content-Type} becomes the media type of the
 *       body. The response always arrives decompressed: its
 *       {@code Content-Encoding} is removed, and {@code Content-Length} is
 *       present only if the host knows the length
 *       ({@link StreamResponse#length()}).</li>
 *   <li><b>Cookies:</b> the cookie store of the host
 *       ({@link AdapterHttp#cookies()}) is the right place; the host sends
 *       its cookies anyway. Cookies set through the {@code WebClient}
 *       ({@code .cookie(…)}) go out in addition, as a {@code Cookie} header,
 *       which the host, like every header with credentials, sends to the
 *       origin only; they can duplicate those of the store. The facade reads
 *       {@code Set-Cookie} of the response leniently: an invalid cookie is
 *       missing only from {@code cookies()}, and the header stays in
 *       {@code getHeaders()}.</li>
 *   <li><b>Status:</b> exactly the one the tool sent, including one without
 *       a name (299, 599). A 3xx arrives as a response; the facade follows
 *       no redirect ({@link com.rmm.adapter.api.RedirectPolicy#never()}).</li>
 *   <li><b>Errors:</b> an {@link AdapterException} of the HTTP path
 *       (unreachable, not allowed, deadline, stop) and an
 *       {@link IllegalStateException} (run not active, no run in the
 *       context, connector closed) arrive as the error of the {@code Mono};
 *       the {@code WebClient} passes them on as the cause of a
 *       {@code WebClientRequestException}.</li>
 *   <li><b>Deadlines:</b> those of the host ({@link AdapterRequest}; for
 *       {@code open} up to the headers and afterwards as idle timeout), per
 *       request through {@link #TIMEOUT}. The adapter sets an overall
 *       deadline with {@code timeout(…)} in its Reactor code; its expiry
 *       unsubscribes and thereby cancels the request (never retried).</li>
 *   <li><b>{@code getNativeRequest()}</b> of the request is the
 *       {@link AdapterRequest}, but only once it is built; in callbacks of
 *       {@code httpRequest(…)} it is still {@code null}.</li>
 * </ul>
 *
 * The library ships inside the plugin jar; it is not a host object. Its only
 * threads are those of the connectors.
 */
public final class AdapterHttpConnector implements ClientHttpConnector, AutoCloseable {

    private static final Log LOG = LogFactory.getLog(AdapterHttpConnector.class);

    /** The facade buffers a request body up to this size (4 MiB). */
    public static final int BUFFERED_BODY_LIMIT = 4 * 1024 * 1024;

    /** Threads per connector if the adapter names no number: the parallelism of a binding. */
    public static final int DEFAULT_THREADS = 8;

    /**
     * Request attribute ({@link Boolean}): {@code true} marks the request as
     * repeatable (a reading POST), {@code false} withdraws the default of the
     * method. {@code true} has no effect for an upload.
     */
    public static final String IDEMPOTENT = AdapterHttpConnector.class.getName() + ".idempotent";

    /** Request attribute ({@link Duration}): the deadline of the request at the host. */
    public static final String TIMEOUT = AdapterHttpConnector.class.getName() + ".timeout";

    /**
     * The longest time a read of a streamed request body waits for the next
     * chunk if the request names no {@link #TIMEOUT} (the host's default for
     * responses).
     */
    public static final Duration BODY_WAIT = Duration.ofSeconds(120);

    /** At most this many tasks wait for a thread per connector. */
    static final int QUEUED_TASKS = 1024;

    /** A connector thread lives this long without work, then it ends. */
    static final long IDLE_SECONDS = 60;

    /** Key of the run in the Reactor context. */
    private static final String RUN_KEY = AdapterHttpConnector.class.getName() + ".run";
    /** Explicitly without a run: none was bound on subscription. */
    private static final Object NO_RUN = new Object();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AdapterHttp http;
    private final Scheduler scheduler;
    /**
     * Completes with {@link #close()}: the scheduler discards a task that is
     * still queued for a thread at that point without a signal; its request
     * ends through this sink.
     */
    private final Sinks.Empty<Void> closed = Sinks.empty();
    private final DataBufferFactory bufferFactory = DefaultDataBufferFactory.sharedInstance;

    private AdapterHttpConnector(AdapterHttp http, int threads) {
        this(http, threads, TimeUnit.SECONDS.toMillis(IDLE_SECONDS));
    }

    /** With a custom idle time of the threads (for tests). */
    AdapterHttpConnector(AdapterHttp http, int threads, long idleMillis) {
        this.http = Objects.requireNonNull(http, "http");
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive: " + threads);
        }
        this.scheduler = Schedulers.fromExecutorService(executor(threads, idleMillis, TimeUnit.MILLISECONDS),
                "rmm-http");
    }

    /**
     * The threads of a connector: at most {@code threads}, each started only
     * for work and ended after {@code idle} without work
     * ({@code allowCoreThreadTimeOut}), with at most {@link #QUEUED_TASKS}
     * waiting tasks (beyond that {@code RejectedExecutionException}). Unlike
     * {@code Schedulers.newBoundedElastic} this starts no additional, permanent
     * housekeeping thread. Unsubscribing interrupts the thread
     * ({@code Future.cancel(true)}), {@link #close()} ends all of them
     * ({@code shutdownNow}). A new task first takes an idle thread; only if
     * none is idle does it start another one (see {@link ConnectorExecutor}).
     */
    static ThreadPoolExecutor executor(int threads, long idle, TimeUnit unit) {
        return new ConnectorExecutor(threads, idle, unit,
                ConnectorThread.factory("rmm-http-" + SEQUENCE.incrementAndGet()));
    }

    /**
     * A {@link ThreadPoolExecutor} that reuses idle threads before it starts
     * new ones. With equal core and maximum size, a {@code ThreadPoolExecutor}
     * starts a new thread per task until the core size is reached, even if a
     * thread is idle: a binding that sends sequentially would hold all of them.
     * With core size 0 and a {@code SynchronousQueue} it would instead reject
     * a task that finds all {@code threads} busy rather than let it wait.
     * Therefore:
     * <ul>
     *   <li>{@link #unfinished} counts the tasks that are accepted and not yet
     *       finished (running or waiting; finished means after
     *       {@code afterExecute}).</li>
     *   <li>The queue ({@link Backlog}) accepts a task only if a thread is free
     *       for it ({@code unfinished <= live threads}) or all {@code threads}
     *       are running already; otherwise its {@code offer} declines, and the
     *       {@code ThreadPoolExecutor} starts a new thread (up to the maximum).
     *       The case "all running" is only a shortcut: without it the task
     *       would reach the queue through the rejection handler all the same.
     *       The rejection handler is still needed for the race described
     *       next.</li>
     *   <li>If that start fails because the maximum was reached at the same
     *       moment, the rejection handler ({@link #intoTheBacklog}) puts the
     *       task into the queue after all; only if the queue is full as well
     *       ({@link #QUEUED_TASKS}) or the connector is closed does the task
     *       fail with {@link RejectedExecutionException}.</li>
     *   <li>Core size 1 (not 0) with {@code allowCoreThreadTimeOut}: this
     *       thread ends without work, too; the core size only makes it possible
     *       to start a thread after the rejection handler has queued a task
     *       ({@code prestartCoreThread}), in case the last thread ended at that
     *       very moment.</li>
     *   <li><b>End of the idle time:</b> a thread whose wait has just ended
     *       without a task still counts until it is really gone, and the
     *       {@code ThreadPoolExecutor} lets it go without looking into the
     *       queue as long as another thread is alive. A task that arrived at
     *       that moment would otherwise wait behind a busy thread until that
     *       one finishes. Every thread therefore checks after its end
     *       ({@link #threadEnded}): if a task waits for which no live thread is
     *       free, it starts a new one. The check in {@code offer} together with
     *       the enqueueing, and this check, run under one lock
     *       ({@link Backlog#admission}): either the task was already queued
     *       when the thread checked, or {@code offer} saw the thread gone and
     *       had a new one started. (A thread that merely announces that it is
     *       leaving would not be enough at the maximum: there the
     *       {@code ThreadPoolExecutor} still counts it, the new thread would
     *       not be created, and the rejection handler would queue the task
     *       behind the busy threads again.)</li>
     * </ul>
     * Limitation: a task counts until after its end. A caller that waits for
     * its result can be woken before that (the signal is emitted inside the
     * task); if it submits the next task at once, that task does not find the
     * thread free yet and starts another one. This is rare per request; if it
     * repeats in a dense sequence, the number of threads can grow up to
     * {@code threads}. Idle threads end after the idle time; there are never
     * more than {@code threads}.
     */
    static final class ConnectorExecutor extends ThreadPoolExecutor {

        /** A nudge without work: starts a thread, which then reads the queue. */
        private static final Runnable KICK = () -> {
        };

        private final AtomicInteger unfinished = new AtomicInteger();

        ConnectorExecutor(int threads, long idle, TimeUnit unit, ThreadFactory factory) {
            this(threads, idle, unit, factory, new ConnectorExecutor[1]);
        }

        /** {@code self}: this executor, for the threads it starts (it does not exist before {@code super}). */
        private ConnectorExecutor(int threads, long idle, TimeUnit unit, ThreadFactory factory,
                                  ConnectorExecutor[] self) {
            super(1, threads, idle, unit, new Backlog(QUEUED_TASKS),
                    work -> factory.newThread(() -> {
                        try {
                            work.run();
                        } finally {
                            self[0].threadEnded();
                        }
                    }), ConnectorExecutor::intoTheBacklog);
            self[0] = this;
            ((Backlog) getQueue()).pool = this;
            allowCoreThreadTimeOut(true);
        }

        @Override
        public void execute(Runnable task) {
            unfinished.incrementAndGet();
            try {
                super.execute(task);
            } catch (RuntimeException | Error rejected) {
                //Also an Error (no further thread available): otherwise the task would count forever
                unfinished.decrementAndGet();
                throw rejected;
            }
        }

        @Override
        protected void afterExecute(Runnable task, Throwable failure) {
            unfinished.decrementAndGet();
        }

        /** The accepted tasks that are not finished yet (for tests). */
        int unfinished() {
            return unfinished.get();
        }

        /** Whether an idle thread can take the task, or all threads are running already. */
        boolean takesFromTheBacklog() {
            int threads = getPoolSize();
            return threads >= getMaximumPoolSize() || unfinished.get() <= threads;
        }

        /**
         * After a thread has ended (it is no longer a worker): if a task waits
         * for which no live thread is free, a nudge starts a new one (class
         * comment, "End of the idle time").
         */
        private void threadEnded() {
            if (isShutdown()) {
                return;
            }
            Backlog backlog = (Backlog) getQueue();
            boolean stranded;
            backlog.admission.lock();
            try {
                stranded = !backlog.isEmpty() && unfinished.get() > getPoolSize();
            } finally {
                backlog.admission.unlock();
            }
            if (stranded) {
                try {
                    execute(KICK);
                } catch (RejectedExecutionException closed) {
                    //Closed in the meantime: the connector ends the requests (closed)
                }
            }
        }

        /**
         * The rejection handler: the queue declined because no thread was free,
         * and the new thread was not created because all are running by now.
         * The task then waits after all, as long as there is room.
         */
        private static void intoTheBacklog(Runnable task, ThreadPoolExecutor executor) {
            Backlog backlog = (Backlog) executor.getQueue();
            if (executor.isShutdown() || !backlog.force(task)) {
                throw new RejectedExecutionException("Task " + task + " rejected from " + executor);
            }
            //Closed between the checks: take the task out again. If shutdownNow came first, the task sits in a
            //terminated executor, and the connector ends its request (closed)
            if (executor.isShutdown() && backlog.remove(task)) {
                throw new RejectedExecutionException("Task " + task + " rejected from " + executor);
            }
            //If the last thread has just ended, nobody else would take the task
            executor.prestartCoreThread();
        }
    }

    /**
     * The queue of a {@link ConnectorExecutor}, bounded to
     * {@link #QUEUED_TASKS}: {@link #offer} accepts only if an idle thread will
     * take the task or no further thread may start.
     */
    static final class Backlog extends LinkedBlockingQueue<Runnable> {

        /** The check and enqueueing in {@link #offer} and the check of an ending thread never run at the same time. */
        final transient ReentrantLock admission = new ReentrantLock();
        private transient volatile ConnectorExecutor pool;
        /**
         * For tests only: runs when the wait of a thread has ended without a
         * task, before the thread leaves (holds it exactly at that point).
         */
        transient volatile Runnable afterIdleTimeout;

        Backlog(int capacity) {
            super(capacity);
        }

        @Override
        public boolean offer(Runnable task) {
            ConnectorExecutor executor = pool;
            if (executor == null) {
                return super.offer(task);
            }
            admission.lock();
            try {
                return executor.takesFromTheBacklog() && super.offer(task);
            } finally {
                admission.unlock();
            }
        }

        @Override
        public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
            Runnable task = super.poll(timeout, unit);
            Runnable hook = afterIdleTimeout;
            if (task == null && hook != null) {
                hook.run();
            }
            return task;
        }

        /** Enqueues without asking for free threads (the rejection handler). */
        boolean force(Runnable task) {
            return super.offer(task);
        }
    }

    /**
     * A thread of a connector. It sends and reads on behalf of others; a
     * request that it subscribes to (for example in a {@code flatMap} over a
     * body it is reading) does not wait for it. It therefore takes no handle
     * {@link AdapterHttp#awaitingOwnRequests()}: otherwise the host would
     * suspend the slot that the thread currently holds for the run (while
     * reading a stream or sending) although it keeps working. If it does wait
     * for a request once (a provider fetches its token through a facade while
     * the thread sends for a request), it counts as working: the clock then
     * runs, and it never stands still wrongly. It is a daemon thread like the
     * threads of Reactor's schedulers and, like them, logs an exception that a
     * task does not catch as an error ({@link #LOGGING}) instead of leaving it
     * to the {@code ThreadGroup} (which writes to {@code System.err}).
     */
    private static final class ConnectorThread extends Thread {

        /**
         * Like {@code Schedulers.defaultUncaughtException} of Reactor (which is
         * not public): logs the exception as an error.
         */
        private static final UncaughtExceptionHandler LOGGING = (thread, failure) ->
                LOG.error("Thread " + thread.getName() + " of an AdapterHttpConnector failed with an uncaught"
                        + " exception", failure);

        private ConnectorThread(Runnable work, String name) {
            super(work, name);
            setDaemon(true);
            setUncaughtExceptionHandler(LOGGING);
        }

        /** The threads of a connector: {@code <name>-1}, {@code <name>-2}, … */
        static ThreadFactory factory(String name) {
            AtomicInteger count = new AtomicInteger();
            return work -> new ConnectorThread(work, name + "-" + count.incrementAndGet());
        }
    }

    /**
     * The connector over the HTTP path of the binding, with
     * {@link #DEFAULT_THREADS} threads. Without {@link #configure} every request
     * needs {@code contextWrite(run(…))}.
     */
    public static AdapterHttpConnector of(AdapterHttp http) {
        return of(http, DEFAULT_THREADS);
    }

    /** Like {@link #of(AdapterHttp)}, with at most {@code threads} threads. */
    public static AdapterHttpConnector of(AdapterHttp http, int threads) {
        return new AdapterHttpConnector(http, threads);
    }

    /**
     * Sets a new connector with {@link #DEFAULT_THREADS} threads on the
     * {@code builder} and, in front of it as the outermost filter, the capture
     * of the bound run (see the class description). The caller closes the
     * connector together with its binding.
     */
    public static AdapterHttpConnector configure(WebClient.Builder builder, AdapterHttp http) {
        return configure(builder, http, DEFAULT_THREADS);
    }

    /** Like {@link #configure(WebClient.Builder, AdapterHttp)}, with at most {@code threads} threads. */
    public static AdapterHttpConnector configure(WebClient.Builder builder, AdapterHttp http, int threads) {
        AdapterHttpConnector connector = of(http, threads);
        ExchangeFilterFunction capture = connector.captureRun();
        builder.clientConnector(connector).filters(filters -> filters.add(0, capture));
        return connector;
    }

    /**
     * The run for the Reactor context: {@code contextWrite(run(run))} at the
     * end of the chain. Every request in the chain goes to the host with this
     * run.
     */
    public static ContextView run(SyncRunContext run) {
        return Context.of(RUN_KEY, Objects.requireNonNull(run, "run"));
    }

    /**
     * The outermost filter: on subscription, on the subscribing thread, it
     * takes the handle "waiting for own requests" (see the class description)
     * and then reads the bound run if the context does not carry one yet.
     * Inner filters and every new subscription inside them see the run in the
     * context; they take no second handle. The handle ends with the exchange,
     * also on an error and on cancellation.
     */
    ExchangeFilterFunction captureRun() {
        return (request, next) -> Mono.deferContextual(context -> {
            AdapterHttp.Awaiting awaiting;
            try {
                awaiting = awaitingOwnRequests();
            } catch (RuntimeException stopped) {
                //Soft limit: the run is stopped, or the wait was interrupted; nothing is sent
                return Mono.error(stopped);
            }
            Mono<ClientResponse> exchange;
            try {
                if (context.hasKey(RUN_KEY)) {
                    exchange = next.exchange(request);
                } else {
                    Object run = http.currentRun().<Object>map(bound -> bound).orElse(NO_RUN);
                    exchange = next.exchange(request).contextWrite(Context.of(RUN_KEY, run));
                }
            } catch (RuntimeException failed) {
                awaiting.close();
                throw failed;
            }
            return exchange.doFinally(signal -> awaiting.close());
        });
    }

    /**
     * The handle of the subscribing thread; a non-blocking thread does not
     * wait and takes none, and neither does a connector thread
     * ({@link ConnectorThread}).
     */
    private AdapterHttp.Awaiting awaitingOwnRequests() {
        if (Schedulers.isInNonBlockingThread() || Thread.currentThread() instanceof ConnectorThread) {
            return AdapterHttp.Awaiting.NOTHING;
        }
        return http.awaitingOwnRequests();
    }

    @Override
    public Mono<ClientHttpResponse> connect(HttpMethod method, URI uri,
                                            Function<? super ClientHttpRequest, Mono<Void>> requestCallback) {
        //Anew on every subscription (also after retryWhen), on whatever thread
        return Mono.deferContextual(context -> {
            if (scheduler.isDisposed()) {
                return Mono.error(new IllegalStateException("AdapterHttpConnector is closed"));
            }
            Object run = context.getOrDefault(RUN_KEY, null);
            if (run == null) {
                return Mono.error(new IllegalStateException("no run in the Reactor context: build the WebClient"
                        + " with AdapterHttpConnector.configure(builder, http) or add"
                        + " contextWrite(AdapterHttpConnector.run(run))"));
            }
            SyncRunContext bound = run instanceof SyncRunContext explicit ? explicit : null;
            AdapterClientHttpRequest request = new AdapterClientHttpRequest(method, uri, bufferFactory, scheduler);
            return requestCallback.apply(request)
                    .then(Mono.<AdapterRequest>defer(() -> request.toAdapterRequest(bound, context)))
                    .flatMap(this::open)
                    .flatMap(this::response)
                    //A streamed request body ends when the request fails or is cancelled; the host stops reading
                    //then. NOT with the response: it can be there before the host has read the end of the body
                    //(the tool answers after the last byte). If the facade closed the stream at that point, the
                    //body would fail at the host, and the host's HTTP client would drop the response it has
                    //already received (under load: an empty body instead of the response). After a success the
                    //host closes the stream itself once it has sent it (Body.ofStream).
                    .doFinally(signal -> {
                        if (signal != SignalType.ON_COMPLETE) {
                            request.closeStreams();
                        }
                    })
                    //If collecting or opening was still queued at close(), no signal would ever come otherwise
                    .or(closed.asMono().then(Mono.error(() -> new IllegalStateException(
                            "AdapterHttpConnector is closed"))));
        });
    }

    /**
     * The response for Spring; if building it fails, the stream is closed
     * instead of being left open. Its body ends with {@link #close()}: if its
     * read was still queued for a thread at that point, the scheduler would
     * discard it without a signal, and the reader would wait forever. Instead
     * the reader gets an {@link IllegalStateException}, and the stream is
     * closed.
     */
    private Mono<ClientHttpResponse> response(StreamResponse response) {
        ClientHttpResponse adapted;
        try {
            adapted = new AdapterClientHttpResponse(response, bufferFactory, scheduler);
        } catch (RuntimeException failure) {
            closeQuietly(response);
            return Mono.error(failure);
        }
        return Mono.just(new ClientHttpResponseDecorator(adapted) {
            @Override
            public Flux<DataBuffer> getBody() {
                return endingWithTheConnector(super.getBody());
            }
        });
    }

    /**
     * The body up to its end or until {@link #close()} (also if that came
     * earlier): then it unsubscribes (the stream is closed) and ends with an
     * {@link IllegalStateException}.
     */
    private Flux<DataBuffer> endingWithTheConnector(Flux<DataBuffer> body) {
        return Flux.defer(() -> {
            AtomicBoolean byClose = new AtomicBoolean();
            return body.takeUntilOther(closed.asMono().then(Mono.fromCallable(() -> {
                        byClose.set(true);
                        return Boolean.TRUE;
                    })))
                    .concatWith(Mono.defer(() -> byClose.get()
                            ? Mono.error(new IllegalStateException("AdapterHttpConnector is closed"))
                            : Mono.empty()));
        });
    }

    /**
     * Opens on a connector thread. Unsubscribing before the headers interrupts
     * the waiting thread; if the response arrives anyway, the cancelled sink
     * discards it, and {@code doOnDiscard} closes it instead of leaving it
     * open.
     */
    private Mono<StreamResponse> open(AdapterRequest request) {
        return Mono.<StreamResponse>create(sink -> {
            AtomicBoolean settled = new AtomicBoolean();
            Disposable task = scheduler.schedule(() -> {
                StreamResponse response;
                try {
                    response = http.open(request);
                } catch (Throwable failure) {
                    //AdapterException, IllegalStateException, also an Error: otherwise the subscriber waits forever
                    if (settled.compareAndSet(false, true)) {
                        sink.error(failure);
                    }
                    return;
                }
                //No interrupting from here on; after cancellation the sink discards the response
                settled.set(true);
                sink.success(response);
            });
            sink.onCancel(() -> {
                if (settled.compareAndSet(false, true)) {
                    task.dispose();
                }
            });
        }).doOnDiscard(StreamResponse.class, AdapterHttpConnector::closeQuietly);
    }

    /**
     * Releases the threads (end of the binding,
     * {@code AdapterInstance.close()}). Requests that have not received their
     * headers yet are interrupted and fail, including one that was still
     * waiting for a thread; new ones fail with an
     * {@link IllegalStateException} as the cause. The handle of the waiting
     * thread ends with that. A response body that is still being read, or
     * whose read still waits for a thread, ends with an
     * {@link IllegalStateException}, and its stream is closed. This applies
     * only to a body that the adapter has subscribed to: the stream of a
     * response whose body it never subscribes to is closed only by the host,
     * at the end of the binding or after the longest lifetime of an open
     * stream (1 h); {@code retrieve()} always reads the body. If
     * {@code close()} coincides with the last chunk, a body that was already
     * read completely can end with an {@link IllegalStateException} as well;
     * that is harmless, because the binding ends anyway. (A full queue, 1024
     * tasks per connector, makes a request fail with a
     * {@code RejectedExecutionException}.)
     */
    @Override
    public void close() {
        scheduler.dispose();
        closed.tryEmitEmpty();
    }

    static void closeQuietly(StreamResponse response) {
        try {
            response.close();
        } catch (IOException | RuntimeException ignored) {
            //Abort: a failure while closing changes nothing anymore
        }
    }
}
