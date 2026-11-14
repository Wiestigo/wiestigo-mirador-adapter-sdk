package com.io_elements.layer_infrastructure.database_schema.element.field;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.Element;
import lombok.Getter;
import lombok.Setter;

/**
 * A typed field (attribute) of a transfer element. Equality is over
 * (name, label), as for all transfer elements.
 */
@Getter
@Setter
public abstract class ElementField<T> extends Element {

    protected T value;
    /** Whether the field is mandatory in the external tool. */
    protected boolean required;

    protected ElementField() {
    }

    protected ElementField(ElementId id, ElementName name, ElementLabel label, T value) {
        this.id = id;
        this.name = name;
        this.label = label;
        this.value = value;
    }

    protected ElementField(ElementName name, ElementLabel label, T value) {
        this.name = name;
        this.label = label;
        this.value = value;
    }

    abstract public String getValueAsString();
    abstract public void setValue(String value);
    abstract public T getValue();
    abstract public void setValue(T value);
}
