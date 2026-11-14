package com.rmm.adapter.api;

/**
 * How a tool represents the element hierarchy. The sync core chooses its
 * hierarchy strategy from it; the adapter does not have to build one.
 */
public enum HierarchyModel {

    /**
     * The tool keeps a real tree (for example item locations in a tool such as
     * Jama). The adapter delivers and receives the position through the
     * location of the transfer elements.
     */
    NATIVE_TREE,

    /**
     * The tool has no hierarchy; the directory path is a path string in an
     * attribute (for example a custom field in a tool such as Jira). The core
     * splits and creates the paths through its hierarchy staging; the adapter
     * only transfers the attribute faithfully.
     */
    PATH_ATTRIBUTE,

    /** The tool is flat; in Mirador all elements lie directly under the sync point. */
    FLAT
}
