package com.io_elements.layer_infrastructure.database_schema.element.field;

import com.io_elements.layer_infrastructure.*;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A field with a list of texts.
 *
 * <p>Its text form joins the entries with {@code ;}. A {@code ;} or a
 * {@code \} inside an entry is written with a {@code \} in front, so
 * {@link #setValue(String)} reads back exactly the entries
 * {@link #getValueAsString()} wrote. The empty list and a missing value both
 * have the text form {@code ""}; a list that holds only one empty entry
 * therefore reads back as the empty list.</p>
 */
@NoArgsConstructor
public class ElementFieldListString extends ElementField<List<String>> {

    private static final char SEPARATOR = ';';
    private static final char ESCAPE = '\\';

    public ElementFieldListString(ElementId id, ElementName name, ElementLabel label, List<String> value) {
        super(id, name, label, value);
    }

    public void addValue(String value) {
        if (this.value == null) {
            this.value = new ArrayList<>();
        } else if (!(this.value instanceof ArrayList)) {
            this.value = new ArrayList<>(this.value);
        }
        this.value.add(value);
    }

    public ElementFieldListString(ElementName name, ElementLabel label, List<String> value) {
        super(name, label, value);
    }

    @Override
    public String getValueAsString() {
        if (this.value == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < this.value.size(); index++) {
            if (index > 0) {
                text.append(SEPARATOR);
            }
            String entry = this.value.get(index);
            for (int at = 0; entry != null && at < entry.length(); at++) {
                char character = entry.charAt(at);
                if (character == SEPARATOR || character == ESCAPE) {
                    text.append(ESCAPE);
                }
                text.append(character);
            }
        }
        return text.toString();
    }

    /** Reads the text form of {@link #getValueAsString()}; {@code null} clears the value. */
    @Override
    public void setValue(String value) {
        if (value == null) {
            this.value = null;
            return;
        }
        List<String> entries = new ArrayList<>();
        if (!value.isEmpty()) {
            StringBuilder entry = new StringBuilder();
            for (int at = 0; at < value.length(); at++) {
                char character = value.charAt(at);
                if (character == ESCAPE && at + 1 < value.length()) {
                    entry.append(value.charAt(++at));
                } else if (character == SEPARATOR) {
                    entries.add(entry.toString());
                    entry.setLength(0);
                } else {
                    entry.append(character);
                }
            }
            entries.add(entry.toString());
        }
        this.value = entries;
    }

    @Override
    public List<String> getValue() {
        return this.value;
    }

    @Override
    public void setValue(List<String> value) {
        this.value = value;
    }
}
