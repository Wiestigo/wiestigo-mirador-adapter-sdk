package com.io_elements.layer_infrastructure.database_schema.element.field;

import com.io_elements.layer_infrastructure.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
public class ElementFieldHtml extends ElementField<String> {

    /**
     * The text as the tool holds it before the adapter rendered it to HTML,
     * for example the wiki markup of a multi-line text field in a tool such
     * as Jira; {@code null} if the adapter does not provide it. When the host
     * applies the mapping, a plain text attribute (STRING) receives this text
     * instead of the HTML; a formatted attribute (XHTML) receives the HTML
     * ({@link #getValue()}).
     *
     * <p>Optional: an adapter that does not set it delivers only the HTML.
     * The wire format of remote adapters does not carry it.</p>
     */
    @Getter
    @Setter
    private String source;

    public ElementFieldHtml(ElementId id, ElementName name, ElementLabel label, String value) {
        super(id, name, label, value);
    }

    @Override
    public String getValueAsString() {
        //TODO return only plain text instead of HTML
        return this.value;
    }

    @Override
    public String getValue() {
        return this.value;
    }

    @Override
    public void setValue(String value) {
        this.value = value;
    }
}
