package unit;

import com.io_elements.layer_infrastructure.io_mapping.CrossToolReferenceRule;
import com.io_elements.layer_infrastructure.io_mapping.CrossToolReferenceRule.ExtractedId;
import com.io_elements.layer_infrastructure.io_mapping.IdentifierForm;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading a reference field: an identifier counts once per identifier form.
 * Form and identifier are told apart by the character NUL, which occurs in
 * neither, so a form name that continues into the identifier does not merge
 * two different references.
 */
public class TestCrossToolReferenceExtraction {

    private static final String UUID_ONE = "123e4567-e89b-12d3-a456-426614174000";

    private static CrossToolReferenceRule rule(String identifier, List<String> readIdentifiers, String idPattern) {
        return new CrossToolReferenceRule("Trace", "JIRA", "refines", true, CrossToolReferenceRule.Authority.TOOL,
                identifier, readIdentifiers, idPattern == null ? null : Pattern.compile(idPattern), null, null,
                null);
    }

    @Test
    public void anIdentifierThatAppearsTwiceCountsOnce() {
        assertEquals(List.of(new ExtractedId("externalId", "PROJ-1"), new ExtractedId("externalId", "PROJ-2")),
                rule("externalId", List.of(), "([A-Z]+-\\d+)").extractReferences("PROJ-1, PROJ-2 and PROJ-1"));
    }

    @Test
    public void theSameIdentifierInTwoFormsCountsOncePerForm() {
        assertEquals(List.of(new ExtractedId("externalId", UUID_ONE), new ExtractedId("rmmId", UUID_ONE)),
                rule("externalId", List.of("rmmId"), "([0-9a-f-]{36})").extractReferences(UUID_ONE));
    }

    @Test
    public void aValueWithoutAPatternIsOneIdentifierOfTheFirstFormWithoutAPattern() {
        assertEquals(List.of(new ExtractedId("externalId", "PROJ-1, PROJ-2")),
                rule("rmmId", List.of("externalId"), null).extractReferences("  PROJ-1, PROJ-2 "));
    }

    /**
     * Two tool forms whose names and identifiers would read the same when
     * written one after the other ({@code key} + {@code A1} and {@code keyA}
     * + {@code 1}) stay two references.
     */
    @Test
    public void aFormNameThatContinuesIntoTheIdentifierDoesNotMergeTwoReferences() {
        CrossToolReferenceRule resolved = rule("key", List.of("keyA"), null).withIdentifierForms(Map.of(
                "key", new IdentifierForm("key", null, Pattern.compile("(A1)"), null),
                "keyA", new IdentifierForm("keyA", null, Pattern.compile("A(1)"), null)));

        assertEquals(List.of(new ExtractedId("key", "A1"), new ExtractedId("keyA", "1")),
                resolved.extractReferences("A1"));
    }
}
