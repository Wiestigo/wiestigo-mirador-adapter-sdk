package com.io_elements.layer_infrastructure;

import lombok.Data;

@Data
public class ElementId {

    private String id;

    public ElementId(String id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return id;
    }
}
