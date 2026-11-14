package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.SourcePage;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.TargetWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Case 1: when a binding fails while the other one reads in parallel, the violation names the failure and
 * carries it as its cause, so that the stack trace of the adapter's own exception is in the test report.
 */
public class TestBindingIsolationKeepsTheCause {

    private static final AdapterDescriptor DESCRIPTOR = new AdapterDescriptor("causetool", "Cause tool", "1.0",
            AdapterApiVersion.MAJOR, new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT,
            false, false, false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null), List.of(), List.of());

    /** A binding whose reader delivers an empty page until its {@code failFrom}-th call, and then fails. */
    private static AdapterInstance failingFrom(int failFrom, RuntimeException failure) {
        AtomicInteger calls = new AtomicInteger();
        SourceReader reader = (context, scope, cursor) -> {
            if (calls.incrementAndGet() >= failFrom) {
                throw failure;
            }
            return SourcePage.<ElementArtifact>empty();
        };
        return new AdapterInstance() {
            @Override
            public Optional<SourceReader> reader() {
                return Optional.of(reader);
            }

            @Override
            public Optional<TargetWriter> writer() {
                return Optional.empty();
            }
        };
    }

    private static BindingIsolation.Side side(String name, AdapterInstance instance, TckAdapterContext context) {
        return new BindingIsolation.Side(name, instance, context, BindingIsolation.Scope.direct(), Set.of(),
                List::of);
    }

    @Test
    public void theFailureOfABindingIsTheCauseOfTheViolation() throws Exception {
        IllegalStateException failure = new IllegalStateException("the client of the tool is closed");
        TckAdapterContext contextA = TckAdapterContext.of(DESCRIPTOR, Map.of());
        TckAdapterContext contextB = TckAdapterContext.of(DESCRIPTOR, Map.of());
        try {
            //A reads alone and once while B is watched; its third read is the first one in parallel
            AdapterInstance a = failingFrom(3, failure);
            AdapterInstance b = failingFrom(Integer.MAX_VALUE, failure);
            ToolReading soloA = ToolReading.of(contextA, a.reader().orElseThrow(), DESCRIPTOR.capabilities(), "PRJ",
                    5);
            ToolReading soloB = ToolReading.of(contextB, b.reader().orElseThrow(), DESCRIPTOR.capabilities(), "PRJ",
                    5);

            TckViolation violation = assertThrows(TckViolation.class, () -> BindingIsolation.require(
                    DESCRIPTOR.capabilities(), side("A", a, contextA), soloA, side("B", b, contextB), soloB, "PRJ",
                    Optional::empty, 5));

            assertTrue(violation.getMessage().startsWith("Binding A failed while another binding read in parallel: "
                    + "java.lang.IllegalStateException: the client of the tool is closed"), violation.getMessage());
            assertSame(failure, violation.getCause());
        } finally {
            contextA.release();
            contextB.release();
        }
    }
}
