package com.io_elements.layer_infrastructure;

import lombok.Data;

@Data
public class ElementKey {

    private String key;

    public ElementKey(String key) {
        this.key = key;
    }

    @Override
    public String toString() {
        return key;
    }
}
