package unit;

import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldJson;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldListString;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldStringUser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every field has a typed value and a text form. What the text form of a
 * field writes, the same field reads back: a host or an adapter that only
 * knows the text form loses nothing.
 */
public class TestElementFieldTextForms {

    private static final ElementName NAME = new ElementName("field");
    private static final ElementLabel LABEL = new ElementLabel("Field");

    @Test
    public void aJsonFieldDeliversItsTextAsValueAndAsTextForm() {
        ElementFieldJson field = new ElementFieldJson(NAME, LABEL, "{ \"zeta\": \"z\",\n  \"alpha\": [1, 2] }");

        assertEquals("{\"zeta\":\"z\",\"alpha\":[1,2]}", field.getValue(), "compact, the order of the keys kept");
        assertEquals(field.getValue(), field.getValueAsString());

        ElementFieldJson copy = new ElementFieldJson();
        copy.setValue(field.getValueAsString());
        assertEquals(field.getValue(), copy.getValue());
    }

    @Test
    public void aJsonFieldTakesEveryJsonValueAndNothingElse() {
        ElementFieldJson field = new ElementFieldJson();
        for (String json : List.of("\"text\"", "12", "true", "null", "[]", "{}")) {
            field.setValue(json);
            assertEquals(json, field.getValue());
        }

        String secret = "{\"token\": not json";
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> field.setValue(secret));
        assertFalse(refused.getMessage().contains("token"), "the message does not repeat the text");
        assertEquals("{}", field.getValue(), "a refused text leaves the value as it was");
        assertThrows(IllegalArgumentException.class, () -> new ElementFieldJson(NAME, LABEL, "{"));
    }

    @Test
    public void aJsonFieldWithoutAValueHasNoTextForm() {
        ElementFieldJson field = new ElementFieldJson();
        assertNull(field.getValue());
        assertNull(field.getValueAsString());

        field.setValue("{}");
        field.setValue(" ");
        assertNull(field.getValue(), "blank text clears the value");
        field.setValue("{}");
        field.setValue(null);
        assertNull(field.getValue());
    }

    @Test
    public void aListReadsBackTheEntriesItsTextFormWrote() {
        for (List<String> entries : List.of(
                List.<String>of(),
                List.of("one"),
                List.of("one", "two", "three"),
                List.of("a;b", "c"),
                List.of("C:\\temp\\x", "back\\;slash", ";", "\\"),
                List.of("", "middle", ""),
                List.of("one", ""))) {
            ElementFieldListString field = new ElementFieldListString(NAME, LABEL, entries);

            ElementFieldListString copy = new ElementFieldListString();
            copy.setValue(field.getValueAsString());

            assertEquals(entries, copy.getValue(), field.getValueAsString());
        }
    }

    @Test
    public void aListWithoutSpecialCharactersKeepsThePlainTextForm() {
        ElementFieldListString field = new ElementFieldListString(NAME, LABEL, List.of("red", "green", "blue"));
        assertEquals("red;green;blue", field.getValueAsString());

        field.setValue("x;y");
        assertEquals(List.of("x", "y"), field.getValue());
        field.addValue("z");
        assertEquals(List.of("x", "y", "z"), field.getValue(), "the list read from text takes further entries");
    }

    @Test
    public void aListWithoutAValueHasTheEmptyTextForm() {
        ElementFieldListString field = new ElementFieldListString();
        assertEquals("", field.getValueAsString());

        field.setValue("");
        assertEquals(new ArrayList<String>(), field.getValue(), "the empty text is the empty list, not one empty entry");
        field.setValue((String) null);
        assertNull(field.getValue());
    }

    @Test
    public void aUserNameReadsBackItsOwnDisplayName() {
        for (String[] firstAndLast : new String[][] {{"Jane", "Doe"}, {null, "jenkins"}, {"Jane Mary", "Doe"}}) {
            ElementFieldStringUser field = new ElementFieldStringUser(null, NAME, LABEL, "account",
                    firstAndLast[0], firstAndLast[1]);

            ElementFieldStringUser copy = new ElementFieldStringUser();
            copy.setValue(field.getValue());

            assertEquals(firstAndLast[0], copy.getFirstName());
            assertEquals(firstAndLast[1], copy.getLastName());
            assertEquals(field.getValue(), copy.getValue());
        }
    }

    /** The display name is stored as returned: a missing part leaves neither a space nor the text "null". */
    @Test
    public void aDisplayNameJoinsOnlyThePartsThatExist() {
        assertEquals("Jane Doe", new ElementFieldStringUser(null, NAME, LABEL, "account", "Jane", "Doe").getValue());
        assertEquals("svc_sync", new ElementFieldStringUser(null, NAME, LABEL, "account", "", "svc_sync").getValue());
        assertEquals("svc_sync", new ElementFieldStringUser(null, NAME, LABEL, "account", null, "svc_sync").getValue());
        assertEquals("", new ElementFieldStringUser(null, NAME, LABEL, "account", null, null).getValue());
    }

    @Test
    public void aUserNameWithoutTextIsCleared() {
        ElementFieldStringUser field = new ElementFieldStringUser(null, NAME, LABEL, "account", "Jane", "Doe");

        field.setValue(" ");

        assertNull(field.getFirstName());
        assertNull(field.getLastName());
        assertEquals("account", field.getUserName(), "the user name is not part of the display name");
        assertEquals("", field.getValue());
    }

    @Test
    public void anElementWithoutFieldsHasNoFieldForALabel() {
        ElementArtifact artifact = new ElementArtifact();
        artifact.setFields(null);

        assertNull(artifact.getFieldByLabel(LABEL));
        assertNull(artifact.getFieldByLabel("Field"));
    }
}
