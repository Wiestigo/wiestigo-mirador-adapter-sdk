package com.io_elements.layer_infrastructure.database_schema.element.field;

import com.io_elements.layer_infrastructure.*;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public class ElementFieldDouble extends ElementField<Double> {

    public ElementFieldDouble(ElementId id, ElementName name, ElementLabel label, Double value) {
        super(id, name, label, value);
    }

    public ElementFieldDouble(Double value) {
        this.value = value;
    }

    public ElementFieldDouble(ElementName name, ElementLabel label, Double value) {
        super(name, label, value);
    }

    @Override
    public String getValueAsString() {
        return this.value.toString();
    }

    @Override
    public void setValue(String value) {
        this.value = Double.valueOf(value);
    }

    @Override
    public Double getValue() {
        return this.value;
    }

    @Override
    public void setValue(Double value) {
        this.value = value;
    }
}
