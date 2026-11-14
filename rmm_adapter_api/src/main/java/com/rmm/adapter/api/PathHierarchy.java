package com.rmm.adapter.api;

/**
 * The configured path attribute of a tool with
 * {@link HierarchyModel#PATH_ATTRIBUTE}: which field carries the directory path
 * and how its segments are separated (for example a custom field with
 * {@code "/"} paths in a tool such as Jira). It is knowledge of the instance,
 * not a capability: the concrete field identifier is known only after binding
 * with configuration, so {@link AdapterInstance#pathHierarchy()} provides it,
 * not the descriptor.
 *
 * <p>The sync core looks the field up by its label first and by its name as a
 * fallback; tools that have only one identifier state it in both.
 *
 * @param attributeName  technical field name in the tool (for example {@code customfield_10212}).
 * @param attributeLabel display name of the field (for example {@code Test Repository Path}).
 * @param separator      separator of the path segments (for example {@code /}).
 */
public record PathHierarchy(String attributeName, String attributeLabel, String separator) {

    public PathHierarchy {
        if (attributeName == null || attributeName.isBlank()) {
            throw new IllegalArgumentException("attributeName must not be empty");
        }
        if (attributeLabel == null || attributeLabel.isBlank()) {
            throw new IllegalArgumentException("attributeLabel must not be empty");
        }
        if (separator == null || separator.isEmpty()) {
            throw new IllegalArgumentException("separator must not be empty");
        }
    }
}
