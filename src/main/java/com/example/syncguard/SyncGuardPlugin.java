package com.example.syncguard;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.JedisPubSub;

public class SyncGuardPlugin extends JavaPlugin {
    private DatabaseManager dbManager;
    private VerificationManager verificationManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dbManager = new DatabaseManager(this);
        verificationManager = new VerificationManager(this, dbManager);

        getCommand("verify").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player)) {
                sender.sendMessage("§cThis command is for players only!");
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage("§eUsage: /verify <code>");
                return true;
            }
            verificationManager.verifyPlayer((org.bukkit.entity.Player) sender, args[0]);
            return true;
        });

        if (dbManager.redis != null) {
            new Thread(() -> {
                dbManager.redis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        String[] parts = message.split(";");
                        try {
                            if (parts[0].equals("two-factor-enabled")) {
                                getConfig().set(parts[0], Boolean.parseBoolean(parts[1]));
                            } else {
                                getConfig().set(parts[0], parts[1]);
                            }
                            saveConfig();
                            getLogger().info("Config updated via Redis: " + parts[0] + " = " + parts[1]);
                        } catch (Exception e) {
                            getLogger().warning("Failed to update config: " + e.getMessage());
                        }
                    }
                }, "syncguard:config");
            }).start();
        }

        getLogger().info("SyncGuardMC enabled!");
    }

    @Override
    public void onDisable() {
        if (dbManager != null) dbManager.close();
        getLogger().info("SyncGuardMC disabled!");
    }

    public DatabaseManager getDatabaseManager() {
        return dbManager;
    }

    public void playSuccessSound(org.bukkit.entity.Player player) {
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    public boolean executeCommand(String command) {
        ConsoleCommandSender console = getServer().getConsoleSender();
        try {
            return getServer().dispatchCommand(console, command);
        } catch (Exception e) {
            getLogger().warning("Failed to execute command '" + command + "': " + e.getMessage());
            return false;
        }
    }
}