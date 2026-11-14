package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ToolReading#artifactIds()} returns the identifiers as the tool delivered them, spaces included. */
public class TestToolReadingArtifactIds {

    private static String artifactLine(String id) {
        ElementArtifact artifact = ElementArtifact.ofNameAndLabel(new ElementName("Requirement"),
                new ElementLabel("System Requirement"));
        artifact.setId(new ElementId(id));
        artifact.addField(new ElementFieldString(new ElementName("name"), new ElementLabel("Name"),
                "Brake name=front"));
        return ToolReading.signature(artifact);
    }

    @Test
    public void anIdentifierWithSpacesIsReturnedWhole() {
        ToolReading reading = new ToolReading(List.of("# artifacts (2 pages)", artifactLine("REQ-1"),
                artifactLine("SYS REQ 7"), artifactLine("Brake system / front"),
                "failure row 4: Unreadable CSV row",
                "# relationships (1 pages)", ToolReading.signature(new ElementRelationship(new ElementId("L 1"),
                        new ElementName("relates"), new ElementId("REQ-1"), new ElementId("SYS REQ 7")))));

        assertEquals(List.of("REQ-1", "SYS REQ 7", "Brake system / front"), reading.artifactIds());
    }
}
