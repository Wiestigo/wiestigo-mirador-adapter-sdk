package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.ElementFeatures;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetOutcome;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.VariabilityConstraint;
import com.rmm.adapter.api.VariabilityFeature;
import com.rmm.adapter.api.VariabilityTree;
import com.rmm.adapter.api.VariabilityVariant;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The round trip of the variability phase within one run: a feature tree, a
 * variant and the selection of a freshly created element are written, and
 * the selection is read back, also after it was cleared.
 */
final class VariabilityRoundTrip {

    private VariabilityRoundTrip() {
    }

    /**
     * Runs the round trip and cleans up: the created artifact is deleted,
     * and the complete state is written without trees and variants.
     *
     * @param roundTrip the artifact the tool can create in the test project
     * @param maxPages  the most pages per read
     */
    static Void run(AdapterInstance instance, SyncRunContext runContext,
                    Supplier<Optional<ElementArtifact>> roundTrip, IntSupplier maxPages, ReadContract.Paging paging)
            throws AdapterException {
        TargetWriter writer = BoundInstance.writerOf(instance);
        SourceReader reader = BoundInstance.readerOf(instance);
        String externalId = writer.createArtifact(runContext, roundTrip.get().orElseThrow());
        try {
            VariabilityTree tree = new VariabilityTree(
                    "RMM_TCK", "TCK tree", "Variability round trip of the Mirador TCK", false,
                    List.of(new VariabilityFeature("BASE", "Base", null, "MANDATORY", null),
                            new VariabilityFeature("EXTRA", "Extra", null, "OPTIONAL", null)),
                    List.of(new VariabilityConstraint("REQUIRES", "RMM_TCK.EXTRA",
                            "RMM_TCK.BASE")));
            writer.writeFeatureTrees(runContext, List.of(tree));
            writer.writeVariants(runContext, List.of(new VariabilityVariant(
                    "TCK Premium", "TCKP", null, false, true, List.of("RMM_TCK.BASE", "RMM_TCK.EXTRA"))));
            List<String> selection = List.of("RMM_TCK.BASE", "RMM_TCK.EXTRA");
            requireWritten("writeElementFeatures", writer.writeElementFeatures(runContext,
                    List.of(new ElementFeatures(externalId, selection))));
            requireSelection(reader, runContext, externalId, selection, maxPages, paging);
            //Clearing is a statement: the element belongs to no variant
            requireWritten("writeElementFeatures", writer.writeElementFeatures(runContext,
                    List.of(new ElementFeatures(externalId, List.of()))));
            requireSelection(reader, runContext, externalId, List.of(), maxPages, paging);
        } finally {
            writer.deleteArtifact(runContext, externalId);
            //The contract itself cleans up: the complete state without a tree
            //and without a variant. What the test project mirrored before is
            //gone; the next run of Mirador writes it again. A productively
            //mirrored project is no test project
            writer.writeFeatureTrees(runContext, List.of());
            writer.writeVariants(runContext, List.of());
        }
        return null;
    }

    private static void requireWritten(String hook, List<TargetOutcome> outcomes) {
        WriteOutcomes.requireOnePerElement(hook, outcomes, 1);
        TargetOutcome outcome = WriteOutcomes.outcomeOf(hook, outcomes, 0);
        WriteOutcomes.requireWritten(hook, outcome, null);
        if (!outcome.isSuccess()) {
            throw new TckViolation(hook + " refused the selection of a freshly created element: "
                    + outcome.failure().getMessage() + " — the tool must accept the names it was given");
        }
    }

    private static void requireSelection(SourceReader reader, SyncRunContext runContext, String externalId,
                                         List<String> expected, IntSupplier maxPages, ReadContract.Paging paging)
            throws AdapterException {
        List<ElementFeatures> read = ReadContract.readAll(paging, "readElementFeatures",
                reader::readElementFeatures, runContext, ReadScope.ofIds(List.of(externalId)), maxPages.getAsInt())
                .elements();
        ElementFeatures own = read.stream()
                .filter(element -> externalId.equals(element.externalId())).findFirst().orElse(null);
        if (own == null) {
            throw new TckViolation("readElementFeatures did not deliver the element <" + externalId
                    + "> named in the scope — Mirador could not tell an unread selection from an empty one");
        }
        if (!new HashSet<>(own.featureNames()).equals(new HashSet<>(expected))) {
            throw new TckViolation("readElementFeatures delivered " + own.featureNames() + " for <" + externalId
                    + "> after " + expected + " were written — the selection must round-trip unchanged");
        }
    }
}
