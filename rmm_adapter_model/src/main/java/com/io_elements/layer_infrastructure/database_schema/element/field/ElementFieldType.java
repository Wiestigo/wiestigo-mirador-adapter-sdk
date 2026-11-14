package com.io_elements.layer_infrastructure.database_schema.element.field;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.Element;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A field descriptor of the tool ({@code MetadataProvider.fieldTypes}).
 * Besides id, name and label it carries what mapping validation and the
 * write path need to know: the data type in terms of the transfer fields
 * ({@link #DATA_TYPE_STRING} and the other constants; {@code null} =
 * unknown), whether the tool allows writing the field, whether it holds
 * several values, and the options of a field with a restricted value set
 * (empty = none).
 */
@Getter
@Setter
public class ElementFieldType extends Element {

    public static final String DATA_TYPE_STRING = "STRING";
    public static final String DATA_TYPE_HTML = "HTML";
    public static final String DATA_TYPE_JSON = "JSON";
    public static final String DATA_TYPE_INTEGER = "INTEGER";
    public static final String DATA_TYPE_LONG = "LONG";
    public static final String DATA_TYPE_BIG_INTEGER = "BIG_INTEGER";
    public static final String DATA_TYPE_BIG_DECIMAL = "BIG_DECIMAL";
    public static final String DATA_TYPE_DOUBLE = "DOUBLE";
    public static final String DATA_TYPE_BOOLEAN = "BOOLEAN";
    public static final String DATA_TYPE_DATE = "DATE";
    public static final String DATA_TYPE_LIST_STRING = "LIST_STRING";
    public static final String DATA_TYPE_STRING_USER = "STRING_USER";

    /** Data type in terms of the transfer fields; {@code null} if the tool does not state it. */
    private String dataType;
    /** Whether the tool allows setting the field through the write contract; default {@code true}. */
    private boolean writable = true;
    /** Whether the field holds several values (list field). */
    private boolean multiValued;
    /** Options of a field with a restricted value set; empty if unrestricted. */
    private List<String> options = new ArrayList<>();

    public ElementFieldType() {
    }

    public ElementFieldType(ElementId id, ElementName name, ElementLabel label) {
        this.id = id;
        this.name = name;
        this.label = label;
    }

    public ElementFieldType(ElementId id, ElementName name, ElementLabel label, String dataType, boolean writable,
                            boolean multiValued) {
        this(id, name, label);
        this.dataType = dataType;
        this.writable = writable;
        this.multiValued = multiValued;
    }

    /**
     * Whether the field can carry identifiers as text or as a list, which a
     * reference field requires. An unknown data type counts as text.
     */
    public boolean carriesText() {
        return dataType == null || DATA_TYPE_STRING.equals(dataType) || DATA_TYPE_HTML.equals(dataType)
                || DATA_TYPE_LIST_STRING.equals(dataType);
    }
}
