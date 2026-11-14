package unit;

import com.io_elements.layer_infrastructure.io_mapping.CrossToolReferenceRule;
import com.io_elements.layer_infrastructure.io_mapping.ExceptionIoMapping;
import com.io_elements.layer_infrastructure.io_mapping.IoMapping;
import com.io_elements.layer_infrastructure.io_mapping.IoMappingJsonReader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code crossToolReferences} section of a mapping file: what a rule
 * becomes with its defaults, and which finding rejects the file. A rule is
 * validated on loading, in a fixed order, so that the first finding named is
 * always the same.
 */
public class TestCrossToolReferenceRuleReading {

    private static final String UUID_ONE = "123e4567-e89b-12d3-a456-426614174000";

    private final IoMappingJsonReader reader = new IoMappingJsonReader();

    private IoMapping mappingOf(String rules) throws ExceptionIoMapping {
        return reader.read("{ \"crossToolReferences\": { \"toolToRmm\": [" + rules + "] } }", "test");
    }

    private CrossToolReferenceRule ruleOf(String rule) throws ExceptionIoMapping {
        return mappingOf(rule).getCrossToolReferenceRules().get(0);
    }

    private String findingOf(String rule) {
        return assertThrows(ExceptionIoMapping.class, () -> mappingOf(rule)).getMessage();
    }

    @Test
    public void aMinimalRuleReadsTheExternalIdOfItsTargetSystemAsOutgoing() throws ExceptionIoMapping {
        CrossToolReferenceRule rule = ruleOf("{ \"attribute\": \" Trace \", \"relationshipType\": \"refines\","
                + " \"targetSystem\": \"JIRA\" }");

        assertEquals("Trace", rule.getAttributeName());
        assertEquals("refines", rule.getRelationshipTypeName());
        assertEquals("JIRA", rule.getTargetSystem());
        assertTrue(rule.isAttributeOwnerSource(), "the default direction is outgoing");
        assertEquals(CrossToolReferenceRule.Authority.TOOL, rule.getAuthority());
        assertEquals(CrossToolReferenceRule.IDENTIFIER_EXTERNAL_ID, rule.getIdentifier());
        assertEquals(List.of(), rule.getReadIdentifiers());
        assertNull(rule.getIdPattern());
        assertEquals(CrossToolReferenceRule.DEFAULT_ID_FORMAT, rule.getIdFormat());
        assertEquals(CrossToolReferenceRule.DEFAULT_SEPARATOR, rule.getSeparator());
        assertEquals(CrossToolReferenceRule.DEFAULT_EMPTY_VALUE, rule.getEmptyValue());
        assertFalse(rule.isListField());
    }

    @Test
    public void everyKeyOfARuleIsTakenOver() throws ExceptionIoMapping {
        CrossToolReferenceRule rule = ruleOf("{ \"attribute\": \"Links\", \"relationshipType\": \"verifies\","
                + " \"direction\": \"incoming\", \"authority\": \" Shared \", \"targetSystem\": \"JAMA\","
                + " \"identifier\": \"externalId\", \"readIdentifiers\": [\" rmmId \", \"\", 7, \"documentKey\"],"
                + " \"idPattern\": \"#([A-Z]+-\\\\d+)\", \"idFormat\": \"#{id}\", \"separator\": \" | \","
                + " \"emptyValue\": \"-\", \"attributeType\": \"list\", \"sample\": \"REQ-7\" }");

        assertFalse(rule.isAttributeOwnerSource());
        assertEquals(CrossToolReferenceRule.Authority.SHARED, rule.getAuthority());
        assertEquals("externalId", rule.getIdentifier());
        assertEquals(List.of("rmmId", "documentKey"), rule.getReadIdentifiers());
        assertEquals("#([A-Z]+-\\d+)", rule.getIdPattern().pattern());
        assertEquals("#{id}", rule.getIdFormat());
        assertEquals(" | ", rule.getSeparator());
        assertEquals("-", rule.getEmptyValue());
        assertTrue(rule.isListField());
    }

    @Test
    public void aWritingRuleWithoutAnIdentifierWritesTheMiradorIdAndNeedsNoTargetSystem() throws ExceptionIoMapping {
        CrossToolReferenceRule rule = ruleOf("{ \"attribute\": \"Links\", \"relationshipType\": \"verifies\","
                + " \"authority\": \"rmm\" }");

        assertEquals(CrossToolReferenceRule.IDENTIFIER_RMM_ID, rule.getIdentifier());
        assertNull(rule.getTargetSystem());
        assertTrue(rule.isWrittenByRmm());
    }

    @Test
    public void aRuleAgainstAnExternallySynchronisedSystemRunsReadOnlyAndSaysSo() throws ExceptionIoMapping {
        IoMapping mapping = reader.read("{ \"crossToolReferences\": { \"externallySynchronised\": [\"jama\"],"
                + " \"toolToRmm\": [ { \"attribute\": \"Links\", \"relationshipType\": \"verifies\","
                + " \"authority\": \"rmm\", \"targetSystem\": \"JAMA\" } ] } }", "test");

        CrossToolReferenceRule rule = mapping.getCrossToolReferenceRules().get(0);
        assertEquals(CrossToolReferenceRule.Authority.TOOL, rule.getAuthority());
        assertEquals(CrossToolReferenceRule.IDENTIFIER_EXTERNAL_ID, rule.getIdentifier(),
                "the default follows the forced authority");
        assertEquals(List.of("Cross-tool reference rule for field <Links> asks for authority <rmm>, but the pair"
                + " with JAMA is externally synchronised — the rule runs as <tool> (read only)"),
                mapping.getWarnings());
    }

    @Test
    public void theFirstFindingRejectsTheFile() {
        assertTrue(findingOf("{ \"relationshipType\": \"refines\" }")
                .startsWith("Cross-tool reference rule is missing the required field <attribute>"));
        assertTrue(findingOf("{ \"attribute\": \"Trace\", \"direction\": \"sideways\" }")
                .startsWith("Cross-tool reference rule is missing the required field <relationshipType>"));
        assertEquals("Cross-tool reference rule for field <Trace> has an unknown direction <sideways>"
                + " (outgoing|incoming)", findingOf("{ \"attribute\": \"Trace\", \"relationshipType\": \"refines\","
                + " \"direction\": \"sideways\", \"authority\": \"nobody\" }"));
        assertEquals("Cross-tool reference rule for field <Trace> has an unknown authority <nobody> (tool|rmm|shared)",
                findingOf("{ \"attribute\": \"Trace\", \"relationshipType\": \"refines\", \"authority\": \"nobody\","
                        + " \"identifier\": \"no good\" }"));
        assertEquals("The identifier form <no good> in rule for field <Trace> may only carry letters, digits, '-'"
                + " and '_'", findingOf("{ \"attribute\": \"Trace\", \"relationshipType\": \"refines\","
                + " \"identifier\": \"no good\", \"readIdentifiers\": [\"also bad\"] }"));
        assertEquals("The identifier form <also bad> in rule for field <Trace> may only carry letters, digits, '-'"
                + " and '_'", findingOf("{ \"attribute\": \"Trace\", \"relationshipType\": \"refines\","
                + " \"readIdentifiers\": [\"also bad\"] }"));
        assertEquals("Cross-tool reference rule for field <Trace> needs a <targetSystem>: only rules that read and"
                + " write nothing but the internal Mirador id (identifier rmmId) may leave it out",
                findingOf("{ \"attribute\": \"Trace\", \"relationshipType\": \"refines\", \"idPattern\": \"(\" }"));
        assertEquals("Cross-tool reference rule for field <Trace> needs a <targetSystem>: only rules that read and"
                + " write nothing but the internal Mirador id (identifier rmmId) may leave it out",
                findingOf("{ \"attribute\": \"Trace\", \"relationshipType\": \"refines\", \"identifier\": \"rmmId\","
                        + " \"readIdentifiers\": [\"externalId\"] }"));
        assertTrue(findingOf("{ \"attribute\": \"Trace\", \"relationshipType\": \"refines\","
                + " \"targetSystem\": \"JIRA\", \"idPattern\": \"(\", \"attributeType\": \"table\" }")
                .startsWith("Cross-tool reference rule for field <Trace> has an invalid idPattern: "));
        assertEquals("Cross-tool reference rule for field <Trace> has an unknown attributeType <table> (text|list)",
                findingOf("{ \"attribute\": \"Trace\", \"relationshipType\": \"refines\","
                        + " \"targetSystem\": \"JIRA\", \"attributeType\": \"table\" }"));
    }

    @Test
    public void aWritingRuleMustReadBackWhatItWrites() throws ExceptionIoMapping {
        String decorated = "\"attribute\": \"Trace\", \"relationshipType\": \"refines\", \"authority\": \"rmm\","
                + " \"targetSystem\": \"JIRA\", \"identifier\": \"externalId\", \"idFormat\": \"[{id}]\"";

        assertEquals("Cross-tool reference rule for field <Trace> fails the round-trip check: writing <ABC-123> as"
                + " <[ABC-123]> reads back as [ExtractedId[identifier=externalId, id=[ABC-123]]] instead of one"
                + " <externalId:ABC-123> (an idFormat that decorates the id needs an idPattern that reads it back;"
                + " name a <sample> id the pattern accepts if ABC-123 does not)", findingOf("{ " + decorated + " }"));
        assertEquals("Cross-tool reference rule for field <Trace> fails the round-trip check: writing <7> as <[7]>"
                + " reads back as [] instead of one <externalId:7> (an idFormat that decorates the id needs an"
                + " idPattern that reads it back; name a <sample> id the pattern accepts if ABC-123 does not)",
                findingOf("{ " + decorated + ", \"idPattern\": \"\\\\[([A-Z]+)\\\\]\", \"sample\": \" 7 \" }"));
        assertEquals("[{id}]", ruleOf("{ " + decorated + ", \"idPattern\": \"\\\\[(\\\\d+)\\\\]\", \"sample\": \"7\" }")
                .getIdFormat(), "the rule's own sample is the one checked");
        assertEquals("Cross-tool reference rule for field <Trace> fails the round-trip check: writing <" + UUID_ONE
                + "> as <no id here> reads back as [] instead of one <rmmId:" + UUID_ONE + ">",
                findingOf("{ \"attribute\": \"Trace\", \"relationshipType\": \"refines\", \"authority\": \"rmm\","
                        + " \"idFormat\": \"no id here\", \"sample\": \"ignored for rmmId\" }"));
    }

    @Test
    public void aReadOnlyRuleAndARuleThatWritesAToolFormAreNotCheckedHere() throws ExceptionIoMapping {
        String decorated = "\"attribute\": \"Trace\", \"relationshipType\": \"refines\", \"targetSystem\": \"JIRA\","
                + " \"idFormat\": \"[{id}]\"";

        assertEquals("[{id}]", ruleOf("{ " + decorated + " }").getIdFormat());
        assertEquals("[{id}]", ruleOf("{ " + decorated + ", \"authority\": \"rmm\", \"identifier\": \"key\" }")
                .getIdFormat());
    }
}
