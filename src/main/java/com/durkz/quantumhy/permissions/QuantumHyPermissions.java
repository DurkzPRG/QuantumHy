package com.durkz.quantumhy.permissions;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/** Permissions owned by QuantumHy. Server operators inherit registered permissions through '*'. */
public final class QuantumHyPermissions {

    public static final String ADMIN = "durkz.quantumhy.admin";

    private QuantumHyPermissions() {
    }

    public static void register() {
        PermissionsModule.registerPermission(ADMIN);
    }

    /** Update notices are operational information, so only operators and explicit admins receive them. */
    public static boolean canReceiveUpdateNotice(PlayerRef playerRef) {
        return isAdmin(playerRef);
    }

    public static boolean isAdmin(PlayerRef playerRef) {
        if (playerRef == null || playerRef.getUuid() == null) {
            return false;
        }
        if (playerRef.hasPermission(ADMIN)) {
            return true;
        }
        PermissionsModule permissions = PermissionsModule.get();
        return permissions != null && permissions.hasPermission(playerRef.getUuid(), ADMIN);
    }
}
