package com.io_elements.layer_infrastructure.database_schema.element.field;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public class ElementFieldBoolean extends ElementField<Boolean>{

    public ElementFieldBoolean(ElementId id, ElementName name, ElementLabel label, Boolean value) {
        super(id, name, label, value);
    }

    @Override
    public String getValueAsString() {
        return this.value.toString();
    }

    @Override
    public void setValue(String value) {
        this.value = Boolean.valueOf(value);
    }

    @Override
    public Boolean getValue() {
        return this.value;
    }

    @Override
    public void setValue(Boolean value) {
        this.value = value;
    }
}
