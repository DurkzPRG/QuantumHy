package com.durkz.quantumhy.permissions;

import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
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

    /** Update notices go to operators, explicit admins and the owner of a singleplayer world. */
    public static boolean canReceiveUpdateNotice(PlayerRef playerRef) {
        return isAdmin(playerRef) || isWorldOwner(playerRef);
    }

    /** The player who opened a singleplayer world. Always false on a dedicated server. */
    static boolean isWorldOwner(PlayerRef playerRef) {
        if (playerRef == null || playerRef.getUuid() == null) {
            return false;
        }
        try {
            return SingleplayerModule.isOwner(playerRef.getUuid());
        } catch (Throwable ignored) {
            return false;
        }
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
