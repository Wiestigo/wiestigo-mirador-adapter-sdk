package unit_tests;

import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourcePage;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Minimal example adapter: synchronization into Mirador only, a flat tool, a
 * single page with one artifact. Serves the contract tests (ServiceLoader
 * discovery, minimum scope of an adapter) and shows how small a valid
 * adapter can be.
 */
public class DummyAdapter implements ToolAdapter {

    @Override
    public AdapterDescriptor descriptor() {
        return new AdapterDescriptor(
                "dummy",
                "Dummy-Tool",
                "0.1.0",
                AdapterApiVersion.MAJOR,
                new AdapterCapabilities(
                        Set.of(SyncDirection.TO_RMM),
                        HierarchyModel.FLAT,
                        false, false, false, false, com.rmm.adapter.api.SyncPhase.artifactsOnly(), java.util.Set.of(),
                        Set.of(),
                        null),
                List.of(ConfigKey.optional("greeting", ConfigType.STRING, "hello", "For the test only")), List.of());
    }

    @Override
    public AdapterInstance bind(AdapterContext context) {
        return new AdapterInstance() {

            @Override
            public Optional<SourceReader> reader() {
                return Optional.of((SyncRunContext runContext, ReadScope scope, PageCursor cursor) -> {
                    ElementArtifact artifact =
                            ElementArtifact.ofNameAndLabel(new ElementName("req-1"), new ElementLabel("Requirement 1"));
                    return new SourcePage<>(List.of(artifact), List.of(), 1, null, true);
                });
            }

            @Override
            public Optional<TargetWriter> writer() {
                return Optional.empty();
            }
        };
    }
}
