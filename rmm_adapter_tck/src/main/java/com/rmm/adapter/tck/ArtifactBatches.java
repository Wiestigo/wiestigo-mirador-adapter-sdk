package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.BulkOperation;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetOutcome;
import com.rmm.adapter.api.TargetWriter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The batch contract of the artifact bulk hooks within one run: one outcome
 * per element in input order, created elements with their own ids and
 * readable afterwards, updates with a receipt.
 */
final class ArtifactBatches {

    private ArtifactBatches() {
    }

    /**
     * Creates, updates and deletes a few artifacts, each through its batch
     * hook if {@code declared} names it and through the single hook
     * otherwise. What it creates, it deletes again.
     *
     * @param declared  the bulk operations the binding declares
     * @param roundTrip the artifact the tool can create in the test project
     * @param maxPages  the most pages per read
     */
    static void require(AdapterInstance instance, Set<BulkOperation> declared, SyncRunContext runContext,
                        Supplier<Optional<ElementArtifact>> roundTrip, IntSupplier maxPages) throws AdapterException {
        List<String> created = new ArrayList<>();
        try {
            TargetWriter writer = BoundInstance.writerOf(instance);
            SourceReader reader = BoundInstance.readerOf(instance);
            if (declared.contains(BulkOperation.ARTIFACT_CREATE)) {
                List<ElementArtifact> batch = List.of(roundTrip.get().orElseThrow(),
                        roundTrip.get().orElseThrow(), roundTrip.get().orElseThrow());
                List<TargetOutcome> outcomes = writer.createArtifacts(runContext, batch);
                WriteOutcomes.requireOnePerElement("createArtifacts", outcomes, batch.size());
                for (int position = 0; position < outcomes.size(); position++) {
                    TargetOutcome outcome = WriteOutcomes.outcomeOf("createArtifacts", outcomes, position);
                    if (!outcome.isSuccess()) {
                        throw new TckViolation("createArtifacts failed for element " + position + ": "
                                + outcome.failure());
                    }
                    WriteOutcomes.requireWritten("createArtifacts (element " + position + ")", outcome,
                            batch.get(position));
                    if (outcome.externalId() == null || outcome.externalId().isBlank()) {
                        throw new TckViolation("createArtifacts must return the id the tool assigned in the"
                                + " outcome of element " + position);
                    }
                    created.add(outcome.externalId());
                }
                if (new HashSet<>(created).size() != created.size()) {
                    throw new TckViolation("createArtifacts answered the same external id for two elements: "
                            + created);
                }
                Set<String> readable = new HashSet<>();
                ReadContract.readAll("readArtifacts", reader::readArtifacts, runContext, ReadScope.full(),
                        maxPages.getAsInt()).elements().forEach(artifact -> readable.add(artifact.getIdValue()));
                if (!readable.containsAll(created)) {
                    throw new TckViolation("Elements created in a batch must be readable afterwards — missing: "
                            + created.stream().filter(id -> !readable.contains(id)).toList());
                }
            } else {
                created.add(writer.createArtifact(runContext, roundTrip.get().orElseThrow()));
                created.add(writer.createArtifact(runContext, roundTrip.get().orElseThrow()));
            }
            if (declared.contains(BulkOperation.ARTIFACT_UPDATE)) {
                List<ElementArtifact> updates = new ArrayList<>();
                for (String externalId : created) {
                    ElementArtifact update = roundTrip.get().orElseThrow();
                    update.setId(new ElementId(externalId));
                    updates.add(update);
                }
                List<TargetOutcome> outcomes = writer.updateArtifacts(runContext, updates);
                WriteOutcomes.requireOnePerElement("updateArtifacts", outcomes, updates.size());
                for (int position = 0; position < outcomes.size(); position++) {
                    TargetOutcome outcome = WriteOutcomes.outcomeOf("updateArtifacts", outcomes, position);
                    if (!outcome.isSuccess()) {
                        throw new TckViolation("updateArtifacts failed for element " + position + ": "
                                + outcome.failure());
                    }
                    if (!outcome.reportsFields()) {
                        throw new TckViolation("updateArtifacts must report the accepted fields"
                                + " (TargetOutcome.applied) for element " + position);
                    }
                    WriteOutcomes.requireWritten("updateArtifacts (element " + position + ")", outcome,
                            updates.get(position));
                }
            }
        } finally {
            TargetWriter writer = BoundInstance.writerOf(instance);
            if (declared.contains(BulkOperation.ARTIFACT_DELETE)) {
                List<TargetOutcome> outcomes = writer.deleteArtifacts(runContext, created);
                WriteOutcomes.requireOnePerElement("deleteArtifacts", outcomes, created.size());
                for (int position = 0; position < outcomes.size(); position++) {
                    TargetOutcome outcome = WriteOutcomes.outcomeOf("deleteArtifacts", outcomes, position);
                    if (!outcome.isSuccess()) {
                        throw new TckViolation("deleteArtifacts failed for element " + position + ": "
                                + outcome.failure());
                    }
                    WriteOutcomes.requireWritten("deleteArtifacts (element " + position + ")", outcome, null);
                }
            } else {
                for (String externalId : created) {
                    writer.deleteArtifact(runContext, externalId);
                }
            }
        }
    }
}
