package com.io_elements.layer_infrastructure.database_schema.element.field;

import com.io_elements.layer_infrastructure.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
public class ElementFieldString extends ElementField<String> {

    /**
     * Set by the host on the write path if this field carries the value of a
     * formatted (XHTML) attribute of Mirador; otherwise {@code false}. An
     * adapter can use it to decide whether to convert the value as rich text
     * into the tool's format (for example into escaped wiki markup for a
     * multi-line text field in a tool such as Jira) or to write it as plain
     * text.
     *
     * <p>Optional: the field kind stays {@code ElementFieldString}, and an
     * adapter that does not read the flag writes the value as text. The wire
     * format of remote adapters does not carry it.</p>
     */
    @Getter
    @Setter
    private boolean richText;

    public ElementFieldString(ElementId id, ElementName name, ElementLabel label, String value) {
        this.id = id;
        this.name = name;
        this.label = label;
        this.value = value;
    }

    public ElementFieldString(String value) {
        this.value = value;
    }

    public ElementFieldString(ElementName name, ElementLabel label, String value) {
        super(name, label, value);
    }

    @Override
    public String getValueAsString() {
        return value;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public void setValue(String value) {
        this.value = value;
    }
}
