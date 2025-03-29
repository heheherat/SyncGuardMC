package com.example.syncguard;

import org.bukkit.entity.Player;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import java.sql.SQLException;
import java.util.Random;

public class VerificationManager {
    private final SyncGuardPlugin plugin;
    private final DatabaseManager dbManager;
    private final Random random = new Random();

    public VerificationManager(SyncGuardPlugin plugin, DatabaseManager dbManager) {
        this.plugin = plugin;
        this.dbManager = dbManager;
    }

    public void verifyPlayer(Player player, String input) {
        try {
            if (dbManager.isPending2FV(player.getUniqueId().toString())) {
                complete2FV(player, input);
                return;
            }

            VerificationData data = dbManager.getVerificationData(input);
            if (data == null || data.expiry < System.currentTimeMillis()) {
                player.sendMessage("§cInvalid or expired code!");
                dbManager.logEvent(player.getName(), null, input, "invalid-code");
                return;
            }
            if (!player.getName().equalsIgnoreCase(data.username)) {
                player.sendMessage("§cThis code is for " + data.username + ", not you!");
                dbManager.logEvent(player.getName(), null, input, "wrong-user");
                return;
            }
            if (!data.serverId.equals(plugin.getConfig().getString("server-id"))) {
                player.sendMessage("§cThis code is for a different server!");
                dbManager.logEvent(player.getName(), null, input, "wrong-server");
                return;
            }

            dbManager.logEvent(player.getName(), data.discordId, input, "code-accepted");

            if (plugin.getConfig().getBoolean("two-factor-enabled", false)) {
                int correctCode = random.nextInt(100);
                int fake1 = generateFakeCode(correctCode);
                int fake2 = generateFakeCode(correctCode, fake1);
                String codeStr = String.format("%02d", correctCode);
                dbManager.store2FV(player.getUniqueId().toString(), codeStr);
                send2FVMenu(player, correctCode, fake1, fake2);
                if (dbManager.redis != null) {
                    dbManager.redis.publish("syncguard:2fv", player.getUniqueId() + ";" + codeStr + ";" + data.discordId);
                }
            } else {
                finishVerification(player, input);
            }
        } catch (SQLException e) {
            player.sendMessage("§cAn error occurred during verification! Please try again later.");
            plugin.getLogger().severe("Verification error for " + player.getName() + ": " + e.getMessage());
            try {
                dbManager.logEvent(player.getName(), null, input, "error:" + e.getMessage());
            } catch (SQLException ex) {
                plugin.getLogger().severe("Failed to log error: " + ex.getMessage());
            }
        }
    }

    private void complete2FV(Player player, String selectedCode) throws SQLException {
        String correctCode = dbManager.get2FV(player.getUniqueId().toString());
        if (correctCode == null) {
            player.sendMessage("§cYour 2FV code has expired!");
            dbManager.logEvent(player.getName(), null, selectedCode, "2fv-expired");
            return;
        }
        if (!selectedCode.equals(correctCode)) {
            player.sendMessage("§cIncorrect code! Verification failed.");
            dbManager.logEvent(player.getName(), null, selectedCode, "2fv-failed");
            dbManager.remove2FV(player.getUniqueId().toString());
            return;
        }

        VerificationData data = dbManager.getVerificationDataByUsername(player.getName());
        if (data != null) {
            finishVerification(player, data.code);
            dbManager.remove2FV(player.getUniqueId().toString());
            dbManager.logEvent(player.getName(), data.discordId, selectedCode, "2fv-success");
        } else {
            player.sendMessage("§cVerification data not found! Please start over.");
            dbManager.logEvent(player.getName(), null, selectedCode, "2fv-no-data");
        }
    }

    private void finishVerification(Player player, String code) throws SQLException {
        player.sendMessage("§aVerification successful! Welcome aboard!");
        plugin.playSuccessSound(player);
        dbManager.removeCode(code);

        String command = plugin.getConfig().getString("commands.on-verify", "lp user %player% group add verified")
                .replace("%player%", player.getName());
        if (!plugin.executeCommand(command)) {
            if (command.startsWith("lp ") || command.startsWith("luckperms ")) {
                player.sendMessage("§eCouldn’t execute LuckPerms command. Is LuckPerms installed?");
            } else {
                player.sendMessage("§eCouldn’t execute post-verification command. Contact an admin.");
            }
            plugin.getLogger().warning("Command '" + command + "' failed.");
            dbManager.logEvent(player.getName(), null, code, "command-failed");
        }

        if (dbManager.redis != null) {
            dbManager.redis.publish("syncguard:verified", player.getUniqueId() + ";" + player.getName());
        }
        VerificationData data = dbManager.getVerificationData(code);
        dbManager.logEvent(player.getName(), data != null ? data.discordId : null, code, "verified");
    }

    private void send2FVMenu(Player player, int correct, int fake1, int fake2) {
        String[] options = new String[]{String.format("%02d", correct), String.format("%02d", fake1), String.format("%02d", fake2)};
        shuffleArray(options);

        player.sendMessage("§eSelect the correct code sent to your Discord DMs:");
        for (String option : options) {
            TextComponent message = new TextComponent("§a[" + option + "]");
            message.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/verify " + option));
            player.spigot().sendMessage(message);
        }
        player.sendMessage("§eYou have 2 minutes to choose!");
    }

    private int generateFakeCode(int correct, int... excludes) {
        int fake;
        do {
            fake = random.nextInt(100);
        } while (fake == correct || contains(excludes, fake));
        return fake;
    }

    private boolean contains(int[] array, int value) {
        for (int i : array) if (i == value) return true;
        return false;
    }

    private void shuffleArray(String[] array) {
        for (int i = array.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String temp = array[i];
            array[i] = array[j];
            array[j] = temp;
        }
    }
}