package com.io_elements.layer_infrastructure;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProjectId {
    private String id;

    public ProjectId(String id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return id;
    }
}
