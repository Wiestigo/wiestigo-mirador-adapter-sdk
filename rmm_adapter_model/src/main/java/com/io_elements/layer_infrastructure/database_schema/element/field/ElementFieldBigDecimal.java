package com.io_elements.layer_infrastructure.database_schema.element.field;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@NoArgsConstructor
public class ElementFieldBigDecimal extends ElementField<BigDecimal> {

    public ElementFieldBigDecimal(ElementId id, ElementName name, ElementLabel label, BigDecimal value) {
        super(id, name, label, value);
    }

    @Override
    public String getValueAsString() {
        return this.value.toString();
    }

    @Override
    public void setValue(String value) {
        this.value = new BigDecimal(value);
    }

    @Override
    public BigDecimal getValue() {
        return this.value;
    }

    @Override
    public void setValue(BigDecimal value) {
        this.value = value;
    }
}
