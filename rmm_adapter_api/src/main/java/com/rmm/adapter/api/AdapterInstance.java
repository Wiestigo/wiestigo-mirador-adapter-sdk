package com.rmm.adapter.api;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A bound adapter: the working ports of one binding. Separate from
 * {@link ToolAdapter} so that binding (per binding, with configuration) and
 * operation (per run) stay apart.
 *
 * <p>Consistency rule (the host checks it after binding): {@link #reader()}
 * must be present if the capabilities declare {@link SyncDirection#TO_RMM};
 * {@link #writer()} must be present if they declare
 * {@link SyncDirection#FROM_RMM}.
 */
public interface AdapterInstance extends AutoCloseable {

    /** Read port for synchronization from the tool to Mirador. */
    Optional<SourceReader> reader();

    /** Write port for synchronization from Mirador to the tool. */
    Optional<TargetWriter> writer();

    /**
     * Type, field and relationship type descriptors of the tool: the basis for
     * mapping validation and the administration views. Without a provider,
     * these diagnostics simply stay empty for the tool.
     */
    default Optional<MetadataProvider> metadata() {
        return Optional.empty();
    }

    /**
     * The configured path attribute. Required if the capabilities declare
     * {@link HierarchyModel#PATH_ATTRIBUTE} (the host checks this after
     * binding); otherwise empty. It is known to the instance, not the
     * descriptor, because the concrete field comes from configuration.
     */
    default Optional<PathHierarchy> pathHierarchy() {
        return Optional.empty();
    }

    /**
     * Lightweight connection check for diagnostics and the health view. Throws
     * an {@link AdapterException} with an understandable cause if the tool is
     * not reachable.
     */
    default void checkHealth() throws AdapterException {
    }

    /**
     * Capabilities of this binding if they differ from the descriptor's (a
     * remote service with its own descriptor). Empty: those of
     * {@link AdapterDescriptor#capabilities()} apply. The host reads them
     * after binding and uses them for runs, views, webhook and rate limit of
     * this connection only.
     *
     * <p>They may only <b>narrow</b> the descriptor's:
     * <ul>
     *   <li>directions, phases, bulk operations and image reference forms: a
     *       subset;</li>
     *   <li>a {@code supports...} flag only if the descriptor has it;</li>
     *   <li>the hierarchy model at most as rich
     *       ({@link HierarchyModel#NATIVE_TREE} over
     *       {@link HierarchyModel#PATH_ATTRIBUTE} over
     *       {@link HierarchyModel#FLAT});</li>
     *   <li>the rate limit at most as fast and with at most as many retries.
     *       A descriptor without a rate limit accepts any; a binding without
     *       one inherits the descriptor's.</li>
     * </ul>
     * Returning {@code null} or widening anything makes the connection
     * {@code DEFECT INSTANCE_INCONSISTENT}; the TCK checks the rule
     * ({@code theBindingDeclarationOnlyNarrowsItsType}).
     */
    default Optional<AdapterCapabilities> capabilities() {
        return Optional.empty();
    }

    /**
     * Additional configuration keys of this binding beyond the descriptor's (a
     * remote service). They apply to this connection only. The host reads them
     * after binding: the administration validates the connection's values
     * against them, and {@code config()} serves them from then on, also in the
     * next {@code bind}.
     *
     * <p>Rules (a violation makes the connection
     * {@code DEFECT INSTANCE_INCONSISTENT}; the TCK checks them in
     * {@code theBindingDeclarationOnlyNarrowsItsType}):
     * <ul>
     *   <li>never {@code null}, no {@code null} entry, at most 64 keys;</li>
     *   <li>names match {@code [a-z0-9.-]{1,64}}, are unique, are not declared
     *       by the descriptor or the host and are not reserved
     *       ({@code http.rate.per.second}, {@code http.retries},
     *       {@code <name>.max}, {@code rmm.…});</li>
     *   <li>scope {@code BINDING} and type {@code STRING}, {@code INTEGER},
     *       {@code BOOLEAN} or {@code SECRET} only; {@code CERTIFICATE},
     *       {@code CLIENT_IDENTITY}, {@code PATH} and {@code URL} can only be
     *       declared by the descriptor;</li>
     *   <li>exactly the {@code SECRET} keys have a name ending in
     *       {@code password}, {@code passwd}, {@code secret}, {@code token},
     *       {@code credential(s)}, {@code apikey} or {@code api.key}; a
     *       {@code SECRET} has no default;</li>
     *   <li>a description has at most 500 characters.</li>
     * </ul>
     */
    default List<ConfigKey> connectionKeys() {
        return List.of();
    }

    /**
     * Identifier kinds of this binding if they differ from the descriptor's (a
     * remote service with its own descriptor). Since contract 1.1. Empty:
     * those of {@link AdapterDescriptor#identifierKinds()} apply; an empty
     * list: the binding has none.
     *
     * <p>The host reads them after binding and uses them for the reference
     * rules of this connection only. They replace the descriptor's entirely;
     * the mapping file still overrides each kind by name. The descriptor's
     * rules apply (no {@code null} list or entry, unique names), plus limits,
     * because the host runs the patterns over artifact values: at most 32
     * kinds, a pattern of at most 256 characters, any other field at most 500.
     * A violation makes the connection {@code DEFECT INSTANCE_INCONSISTENT}.
     */
    default Optional<List<IdentifierKind>> identifierKinds() {
        return Optional.empty();
    }

    /**
     * Cleans up the adapter's own helper objects at the tool (webhook
     * registrations, sessions) when the connection is removed for good. Never
     * content: {@code purge()} writes and deletes no elements. Default: nothing
     * to do.
     */
    default void purge() throws AdapterException {
    }

    /**
     * Interprets an event in the tool's own format (a webhook) as the read
     * scope of an event run, usually {@link ReadScope#ofIds}. Empty: not a
     * tool format; the host's generic event format applies. Called per
     * connection, for the webhook of that connection.
     *
     * <p><b>The host guarantees:</b> it calls the method last, after all its
     * checks (key, state of the connection, secret, license, binding,
     * timestamp and nonce, size), under the plugin's class loader. The body is
     * no larger than the host's webhook limit (1 MiB by default), and the
     * header carrying the secret is removed. The host answers any exception
     * with 400.
     *
     * <p><b>The host expects</b> (TCK case
     * {@code parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest}):
     * empty, a scope with explicitly named identifiers, or an
     * {@code AdapterException}, also for an empty or nonsensical body. Never
     * {@code null}, never a full scope, no request to the tool, no waiting.
     *
     * @param headers request headers, without the one carrying the secret
     * @param body    request body; the host closes it
     */
    default Optional<ReadScope> parseEvent(Map<String, List<String>> headers, InputStream body)
            throws AdapterException {
        return Optional.empty();
    }

    /**
     * Releases resources (clients, pools). Default: nothing to do.
     *
     * <p>The host and the TCK call {@code close()} only after the binding's
     * tasks from {@link AdapterContext#executor()} have ended: waiting tasks
     * are cancelled, running ones get 10 s and are then interrupted.
     */
    @Override
    default void close() {
    }
}
