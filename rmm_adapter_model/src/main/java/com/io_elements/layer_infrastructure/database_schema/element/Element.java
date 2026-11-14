package com.io_elements.layer_infrastructure.database_schema.element;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementField;
import lombok.Getter;
import lombok.Setter;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Base class of the tool-neutral transfer model: the intermediate structure
 * in which data passes between Mirador and external tools, so that neither
 * side depends on the other's types.
 *
 * <p>Identity: {@code id} carries the identifier in the external tool.
 * Equality is defined over (name, label) on purpose, so that the classes
 * also serve as type and mapping descriptors in which elements of the same
 * name coincide.</p>
 *
 * <p>Values are typed {@link ElementField}s in a map keyed by label;
 * {@link #getFieldByName} searches by technical name. Constructors and
 * setters take the typed wrappers ({@link ElementId}, {@link ElementName},
 * {@link ElementLabel}), so that string parameters cannot be mixed up; the
 * {@code *Value()} helpers unwrap them for reading.</p>
 */
@Getter
@Setter
public abstract class Element {

    protected ElementId id;
    protected ElementName name;
    protected ElementLabel label;

    protected Map<ElementLabel, ElementField<?>> fields;

    protected Element() {
        fields = new HashMap<>();
    }

    protected Element(ElementId id, ElementName name, ElementLabel label, Collection<ElementField<?>> fields) {
        this();
        this.id = id;
        this.name = name;
        this.label = label;
        for (ElementField<?> field : fields) {
            this.fields.put(field.getLabel(), field);
        }
    }

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    public void addField(ElementField<?> elementField) {
        if (fields == null) {
            fields = new HashMap<>();
        }
        fields.put(elementField.getLabel(), elementField);
    }

    public void addField(List<ElementField<?>> elementFields) {
        for (ElementField<?> elementField : elementFields) {
            addField(elementField);
        }
    }

    public ElementField<?> getFieldByLabel(ElementLabel elementLabel) {
        if (fields == null) {
            return null;
        }
        return fields.get(elementLabel);
    }

    public ElementField<?> getFieldByLabel(String label) {
        if (fields == null) {
            return null;
        }
        return fields.get(new ElementLabel(label));
    }

    /** Looks a field up by its technical name, ignoring case (the map itself is keyed by label). */
    public  ElementField<?> getFieldByName(ElementName elementName) {
        return getFieldByName(elementName.getName());
    }

    public ElementField<?> getFieldByName(String name) {
        if (fields == null) {
            return null;
        }
        for (ElementField<?> field : fields.values()) {
            if (field.getName() != null && field.getName().getName().equalsIgnoreCase(name)) {
                return field;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Reading: null-safe helpers that unwrap the typed wrappers. Writing
    // takes the wrappers only, so that string parameters cannot be mixed up.
    // ------------------------------------------------------------------

    public String getIdValue() {
        return id == null ? null : id.getId();
    }

    public String getNameValue() {
        return name == null ? null : name.getName();
    }

    public String getLabelValue() {
        return label == null ? null : label.getLabel();
    }

    // ------------------------------------------------------------------
    // Identity: (name, label), see the class Javadoc
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || obj.getClass() != this.getClass()) {
            return false;
        }
        Element element = (Element) obj;
        return Objects.equals(element.name, this.name) && Objects.equals(element.label, this.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, label);
    }
}
