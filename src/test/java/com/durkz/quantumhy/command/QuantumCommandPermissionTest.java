package com.durkz.quantumhy.command;

import com.durkz.quantumhy.permissions.QuantumHyPermissions;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QuantumCommandPermissionTest {
    @Test
    void reloadRequiresAdminButPersonalCommandsRemainPublic() {
        QuantumCommand command = new QuantumCommand(null);
        var reload = command.getSubCommand("reload");
        assertEquals(QuantumHyPermissions.ADMIN, reload.getPermission());
        assertFalse(reload.hasPermission(sender(false)));
        assertTrue(reload.hasPermission(sender(true)));
        assertTrue(command.getSubCommand("status").hasPermission(sender(false)));
        assertTrue(command.getSubCommand("optimize").hasPermission(sender(false)));
    }

    private static CommandSender sender(boolean admin) {
        return new CommandSender() {
            @Override
            public String getUsername() { return "test"; }
            @Override
            public UUID getUuid() { return new UUID(0, 1); }
            @Override
            public void sendMessage(Message message) { }
            @Override
            public boolean hasPermission(String permission) {
                return admin && QuantumHyPermissions.ADMIN.equals(permission);
            }
            @Override
            public boolean hasPermission(String permission, boolean defaultValue) {
                return hasPermission(permission);
            }
        };
    }
}
