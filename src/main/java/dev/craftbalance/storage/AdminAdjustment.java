package dev.craftbalance.storage;

import java.util.UUID;

/** Persisted audit entry for one administrative balance adjustment. */
public record AdminAdjustment(UUID adminUuid, UUID playerUuid, String type, long changedAtEpochMillis,
                              long normalBeforeMillis, long normalAfterMillis,
                              long recoveredBeforeMillis, long recoveredAfterMillis) {}
