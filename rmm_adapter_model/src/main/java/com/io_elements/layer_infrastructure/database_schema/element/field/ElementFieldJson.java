package com.io_elements.layer_infrastructure.database_schema.element.field;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import lombok.NoArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A field whose value is a JSON document, held as text in compact form.
 *
 * <p>The value is a {@code String} on purpose: the contract carries no type
 * of a JSON library, so an adapter and the host exchange the text and each
 * parses it with its own library. Any JSON value is accepted (object, array,
 * string, number, boolean, {@code null}); whitespace between tokens is
 * dropped and the order of the keys is kept. Text that is not JSON is
 * refused, so a JSON field never carries anything else.</p>
 */
@NoArgsConstructor
public class ElementFieldJson extends ElementField<String> {

    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    /** @throws IllegalArgumentException if {@code json} is not JSON */
    public ElementFieldJson(ElementId id, ElementName name, ElementLabel label, String json) {
        super(id, name, label, compact(json));
    }

    /** @throws IllegalArgumentException if {@code json} is not JSON */
    public ElementFieldJson(ElementName name, ElementLabel label, String json) {
        super(name, label, compact(json));
    }

    /** The JSON text in compact form; {@code null} without a value. */
    @Override
    public String getValueAsString() {
        return value;
    }

    /** The JSON text in compact form; {@code null} without a value. */
    @Override
    public String getValue() {
        return value;
    }

    /**
     * Sets the JSON text. {@code null} or blank text clears the value.
     *
     * @throws IllegalArgumentException if {@code json} is not JSON; the
     *         message does not repeat the text
     */
    @Override
    public void setValue(String json) {
        this.value = compact(json);
    }

    private static String compact(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json).toString();
        } catch (JacksonException notJson) {
            throw new IllegalArgumentException("The value of a JSON field is not JSON (" + json.length()
                    + " characters)", notJson);
        }
    }
}
