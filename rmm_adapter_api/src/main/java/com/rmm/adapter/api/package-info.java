/**
 * The adapter contract of Wiestigo Mirador: the published interfaces, records
 * and enums against which tool adapters for external systems are built, for
 * established products as well as an operator's in-house tools.
 *
 * <p><b>Entry point.</b> A {@link com.rmm.adapter.api.ToolAdapter} is
 * discovered through {@link java.util.ServiceLoader}. It describes itself with
 * an {@link com.rmm.adapter.api.AdapterDescriptor} (identity, version,
 * {@link com.rmm.adapter.api.AdapterCapabilities}, declared
 * {@link com.rmm.adapter.api.ConfigKey}s) and is bound once per connection:
 * {@link com.rmm.adapter.api.ToolAdapter#bind} receives an
 * {@link com.rmm.adapter.api.AdapterContext} and returns an
 * {@link com.rmm.adapter.api.AdapterInstance}. Nothing is shared between
 * bindings.
 *
 * <p><b>Working contracts.</b> {@link com.rmm.adapter.api.SourceReader} reads
 * from the tool to Mirador page by page
 * ({@link com.rmm.adapter.api.ReadScope}, {@link com.rmm.adapter.api.PageCursor},
 * {@link com.rmm.adapter.api.SourcePage}); {@link com.rmm.adapter.api.TargetWriter}
 * writes from Mirador to the tool and reports a
 * {@link com.rmm.adapter.api.TargetOutcome} per element. Both directions use
 * the same transfer classes from {@code com.io_elements} in
 * {@code rmm_adapter_model} ({@code ElementArtifact},
 * {@code ElementRelationship}, {@code ElementComment},
 * {@code ElementAttachment}). The sync core orchestrates according to the
 * declared capabilities and phases ({@link com.rmm.adapter.api.SyncPhase}); an
 * optional {@link com.rmm.adapter.api.MetadataProvider} describes the types and
 * fields of the tool.
 *
 * <p><b>Host services.</b> An adapter gets its environment only through the
 * {@link com.rmm.adapter.api.AdapterContext}: typed configuration
 * ({@link com.rmm.adapter.api.AdapterConfig}), the only permitted network path
 * ({@link com.rmm.adapter.api.AdapterHttp} with
 * {@link com.rmm.adapter.api.AdapterRequest}, {@link com.rmm.adapter.api.Body},
 * {@link com.rmm.adapter.api.Auth} and {@link com.rmm.adapter.api.RedirectPolicy}),
 * stores ({@link com.rmm.adapter.api.AdapterStore},
 * {@link com.rmm.adapter.api.AdapterCaches},
 * {@link com.rmm.adapter.api.AdapterRunStore}), concurrency
 * ({@link com.rmm.adapter.api.AdapterExecutor}), logging and metrics. The
 * module is deliberately minimal: no Spring, no database, no host internals.
 *
 * <p><b>Errors.</b> A hook fails with the checked
 * {@link com.rmm.adapter.api.AdapterException}, binding with
 * {@link com.rmm.adapter.api.AdapterInitialisationException}.
 *
 * <p><b>Versioning.</b> This is contract major 1
 * ({@link com.rmm.adapter.api.AdapterApiVersion}). It is frozen: members are
 * neither removed nor changed, and additions arrive only with a new minor
 * version.
 *
 * <p>{@code docs/DEVELOPER_GUIDE.md} explains how to build an adapter;
 * {@code rmm_adapter_tck} checks an adapter against this contract.
 */
package com.rmm.adapter.api;
