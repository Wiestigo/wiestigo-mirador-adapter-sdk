package com.io_elements.layer_infrastructure.database_schema.element.field;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import lombok.NoArgsConstructor;

import java.math.BigInteger;

@NoArgsConstructor
public class ElementFieldBigInteger extends ElementField<BigInteger> {

    public ElementFieldBigInteger(ElementId id, ElementName name, ElementLabel label, BigInteger value) {
        super(id, name, label, value);
    }

    @Override
    public String getValueAsString() {
        return this.value.toString();
    }

    @Override
    public void setValue(String value) {
        this.value = new BigInteger(value);
    }

    @Override
    public BigInteger getValue() {
        return this.value;
    }

    @Override
    public void setValue(BigInteger value) {
        this.value = value;
    }
}
