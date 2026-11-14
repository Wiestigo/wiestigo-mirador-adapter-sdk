package com.io_elements.layer_infrastructure.database_schema.element.field;

import com.io_elements.layer_infrastructure.*;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public class ElementFieldInteger extends ElementField<Integer> {

    public ElementFieldInteger(ElementId id, ElementName name, ElementLabel label, Integer value) {
        super(id, name, label, value);
    }

    public ElementFieldInteger(Integer value) {
        this.value = value;
    }

    public ElementFieldInteger(ElementName name, ElementLabel label, Integer value) {
        super(name, label, value);
    }

    @Override
    public String getValueAsString() {
        return this.value.toString();
    }

    @Override
    public void setValue(String value) {
        this.value = Integer.valueOf(value);
    }

    @Override
    public Integer getValue() {
        return this.value;
    }

    @Override
    public void setValue(Integer value) {
        this.value = value;
    }
}
