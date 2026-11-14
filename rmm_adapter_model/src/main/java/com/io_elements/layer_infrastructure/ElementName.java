package com.io_elements.layer_infrastructure;

import lombok.Data;

@Data
public class ElementName {

    private String name;

    public ElementName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
