package unit;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString;
import com.rmm.adapter.api.SourcePage;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.TckViolation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the comment round trip. It passes against the in-memory reference tool. It fails for a writer that
 * loses the comment's element ({@code commentToItemId}) or changes the comment's text, and for a reader
 * that delivers the identifier twice. To build these, the tool's writer receives the comment as
 * {@code change} rewrites it, and the case receives the comment pages as {@code reading} rewrites them.
 */
public class TestCommentRoundTripContract {

    /** The reference tool; its {@code createComment} hooks write the comment that {@code change} returns. */
    private static ToolAdapter writing(UnaryOperator<ElementComment> change) {
        return writing(change, UnaryOperator.identity());
    }

    /** Like {@link #writing(UnaryOperator)}; {@code readComments} returns the page that {@code reading} returns. */
    @SuppressWarnings("unchecked")
    private static ToolAdapter writing(UnaryOperator<ElementComment> change,
                                       UnaryOperator<SourcePage<ElementComment>> reading) {
        return InterceptingTool.ofReaderAndWriterHooks(TestInMemoryAdapterContract.seeded(),
                (reader, hook, parameters) -> {
                    Object page = InterceptingTool.forward(reader, hook, parameters);
                    return hook.getName().equals("readComments")
                            ? reading.apply((SourcePage<ElementComment>) page) : page;
                },
                (writer, hook, parameters) -> {
                    if (hook.getName().startsWith("createComment")
                            && parameters[1] instanceof ElementComment comment) {
                        parameters[1] = change.apply(comment);
                    }
                    return InterceptingTool.forward(writer, hook, parameters);
                });
    }

    private static AdapterContractTest tckFor(UnaryOperator<ElementComment> change) {
        return tckFor(writing(change), () -> commentWithTextOf("<p>The brake squeals.</p>"));
    }

    /** Builds the template text with the TCK's helper, which is otherwise only reachable from a subclass. */
    private static ElementComment commentWithTextOf(String text) {
        return new AdapterContractTest() {
            ElementComment of() {
                return commentWithText(text);
            }

            @Override
            protected ToolAdapter adapter() {
                throw new UnsupportedOperationException();
            }
        }.of();
    }

    private static AdapterContractTest tckFor(ToolAdapter tool, Supplier<ElementComment> template) {
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return tool;
            }

            @Override
            protected Optional<SyncRunContext> liveRunContext() {
                return Optional.of(new SyncRunContext(UUID.randomUUID(), "MEM-PRJ"));
            }

            @Override
            protected Optional<ElementArtifact> roundTripArtifact() {
                ElementArtifact artifact = ElementArtifact.ofNameAndLabel(new ElementName("Requirement"),
                        new ElementLabel("Requirement"));
                artifact.addField(new ElementFieldString(new ElementName("name"), new ElementLabel("Name"),
                        "Brake"));
                return Optional.of(artifact);
            }

            @Override
            protected Optional<ElementComment> roundTripComment() {
                return Optional.of(template.get());
            }
        };
    }

    @Test
    void theReferenceToolPassesTheCommentRoundTrip() {
        assertDoesNotThrow(() -> tckFor(UnaryOperator.identity())
                .commentRoundTripCreatesAndFindsTheCommentAtItsArtifact());
    }

    @Test
    void aWriterThatLosesTheParentFailsTheCommentRoundTrip() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(comment -> {
            comment.setCommentToItemId(null);
            return comment;
        }).commentRoundTripCreatesAndFindsTheCommentAtItsArtifact());

        assertTrue(violation.getMessage().contains("was created at") && violation.getMessage().contains("commentToItemId"),
                violation.getMessage());
    }

    @Test
    void aWriterThatChangesTheTextFailsTheCommentRoundTrip() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(comment -> {
            ElementComment other = new ElementComment();
            other.setCommentToItemId(comment.getCommentToItemId());
            other.addField(new ElementFieldString(new ElementName("Body"), new ElementLabel("Body"),
                    "The brake brakes."));
            return other;
        }).commentRoundTripCreatesAndFindsTheCommentAtItsArtifact());

        assertTrue(violation.getMessage().contains("another text"), violation.getMessage());
    }

    /**
     * When {@code readComments} delivers the comment's identifier twice (here first at another element, as
     * with colliding identifiers in a tool), the round trip reports the duplicate itself instead of only
     * complaining about the wrong element of the first hit.
     */
    @Test
    void aReaderThatDeliversTheIdTwiceFailsTheCommentRoundTrip() {
        ToolAdapter tool = writing(UnaryOperator.identity(), page -> {
            List<ElementComment> doubled = new ArrayList<>();
            for (ElementComment comment : page.elements()) {
                doubled.add(new ElementComment(comment.getId(), comment.getName(), comment.getLabel(),
                        comment.getFields().values(), new ElementId("elsewhere")));
                doubled.add(comment);
            }
            return new SourcePage<>(doubled, page.failures(), page.readCount() + page.elements().size(),
                    page.nextCursor(), page.lastPage(), page.deletedIds(), page.completeParentIds());
        });

        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(tool,
                () -> commentWithTextOf("<p>The brake squeals.</p>"))
                .commentRoundTripCreatesAndFindsTheCommentAtItsArtifact());

        assertTrue(violation.getMessage().contains("2 times"), violation.getMessage());
    }

    /** The round trip fetches the template once and writes a copy; the adapter's template keeps no parent element. */
    @Test
    void theCommentRoundTripTakesTheTemplateOnceAndLeavesItAsItIs() {
        ElementComment template = commentWithTextOf("<p>The brake squeals.</p>");
        AtomicInteger taken = new AtomicInteger();

        assertDoesNotThrow(() -> tckFor(writing(UnaryOperator.identity()), () -> {
            taken.incrementAndGet();
            return template;
        }).commentRoundTripCreatesAndFindsTheCommentAtItsArtifact());

        assertEquals(1, taken.get(), "roundTripComment() once");
        assertNull(template.getCommentToItemId(), "the template stays as it is");
    }
}
