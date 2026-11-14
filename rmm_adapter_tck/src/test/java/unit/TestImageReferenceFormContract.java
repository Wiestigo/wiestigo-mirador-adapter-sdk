package unit;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.ImageReferenceForm;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.TckViolation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the TCK case for image reference forms: a form within the host's grammar passes, and a form that
 * is too wide is a violation, even when the descriptor itself already rejects it.
 */
public class TestImageReferenceFormContract {

    private static ToolAdapter adapterWith(Supplier<Set<ImageReferenceForm>> forms) {
        return new ToolAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return new AdapterDescriptor("IMGTEST", "Image Test", "0.1", AdapterApiVersion.MAJOR,
                        new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT, false, false,
                                false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null, forms.get()),
                        List.of(), List.of());
            }

            @Override
            public AdapterInstance bind(AdapterContext context) {
                return new AdapterInstance() {
                    @Override
                    public Optional<SourceReader> reader() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<TargetWriter> writer() {
                        return Optional.empty();
                    }
                };
            }
        };
    }

    private static AdapterContractTest tckFor(Supplier<Set<ImageReferenceForm>> forms) {
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return adapterWith(forms);
            }
        };
    }

    @Test
    public void formsInTheGrammarOfTheCorePass() {
        assertDoesNotThrow(() -> tckFor(() -> Set.of(ImageReferenceForm.scheme("attachment"),
                ImageReferenceForm.pathTemplate("/{ctx}/attachment/{number}/{file}")))
                .declaredImageReferenceFormsKeepToTheGrammarOfTheCore());
        assertDoesNotThrow(() -> tckFor(Set::of).declaredImageReferenceFormsKeepToTheGrammarOfTheCore());
    }

    @Test
    public void aTooWideFormIsAViolation() {
        for (Supplier<Set<ImageReferenceForm>> tooWide : List.<Supplier<Set<ImageReferenceForm>>>of(
                () -> Set.of(ImageReferenceForm.pathTemplate("/{ctx}/{file}")),
                () -> Set.of(ImageReferenceForm.scheme("https")),
                () -> Set.of(ImageReferenceForm.pathTemplate("/{ctx}/api/{file}")))) {
            TckViolation violation = assertThrows(TckViolation.class,
                    () -> tckFor(tooWide).declaredImageReferenceFormsKeepToTheGrammarOfTheCore());

            assertTrue(violation.getMessage().contains("grammar of the core"), violation.getMessage());
        }
    }
}
