package com.io_elements.layer_infrastructure;

import lombok.Data;

@Data
public class ElementLabel {

    private String label;

    public ElementLabel(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
