package com.rmm.adapter.api;

/** Direction of a synchronization, seen from Mirador. */
public enum SyncDirection {

    /** Inbound: the tool is the source, Mirador the target ({@link SourceReader}). */
    TO_RMM,

    /** Outbound: Mirador is the source, the tool the target ({@link TargetWriter}). */
    FROM_RMM
}
