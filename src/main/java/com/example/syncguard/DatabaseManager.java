package com.example.syncguard;

import org.bukkit.configuration.file.FileConfiguration;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DatabaseManager {
    private final SyncGuardPlugin plugin;
    private Connection sqlConnection;
    public Jedis redis;

    public DatabaseManager(SyncGuardPlugin plugin) {
        this.plugin = plugin;
        initializeDatabase();
        initializeRedis();
    }

    private void initializeDatabase() {
        FileConfiguration config = plugin.getConfig();
        String dbType = config.getString("database.type", "mysql").toLowerCase();
        String url;
        try {
            switch (dbType) {
                case "mysql":
                    url = "jdbc:mysql://" + config.getString("database.host") + ":" +
                          config.getInt("database.port") + "/" +
                          config.getString("database.name") + "?useSSL=false";
                    sqlConnection = DriverManager.getConnection(url,
                            config.getString("database.username"),
                            config.getString("database.password"));
                    break;
                case "h2":
                    url = "jdbc:h2:" + plugin.getDataFolder().getAbsolutePath() + "/syncguard;MODE=MySQL";
                    sqlConnection = DriverManager.getConnection(url);
                    break;
                case "sqlite":
                    url = "jdbc:sqlite:" + plugin.getDataFolder().getAbsolutePath() + "/syncguard.db";
                    sqlConnection = DriverManager.getConnection(url);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported database type: " + dbType);
            }
            createTables();
            plugin.getLogger().info("Connected to " + dbType + " database.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to connect to database: " + e.getMessage());
            sqlConnection = null;
        }
    }

    private void createTables() throws SQLException {
        if (sqlConnection == null) return;
        try (PreparedStatement stmt = sqlConnection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS verifications (" +
                "code VARCHAR(8) PRIMARY KEY, username VARCHAR(16), server_id VARCHAR(36), " +
                "expiry BIGINT, ip VARCHAR(45), discord_id VARCHAR(18))")) {
            stmt.execute();
        }
        try (PreparedStatement stmt = sqlConnection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS two_factor (" +
                "uuid VARCHAR(36) PRIMARY KEY, code VARCHAR(2), expiry BIGINT)")) {
            stmt.execute();
        }
        try (PreparedStatement stmt = sqlConnection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS audit_logs (" +
                "id INT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(16), discord_id VARCHAR(18), " +
                "code VARCHAR(8), status VARCHAR(20), timestamp BIGINT)")) {
            stmt.execute();
        }
    }

    private void initializeRedis() {
        FileConfiguration config = plugin.getConfig();
        if (config.getBoolean("redis.enabled", false)) {
            try {
                redis = new Jedis(config.getString("redis.host", "localhost"),
                                 config.getInt("redis.port", 6379));
                if (config.isSet("redis.password")) redis.auth(config.getString("redis.password"));
                redis.ping();
                plugin.getLogger().info("Connected to Redis.");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to connect to Redis: " + e.getMessage());
                redis = null;
            }
        }
    }

    public VerificationData getVerificationData(String code) throws SQLException {
        if (sqlConnection == null) throw new SQLException("Database not connected.");
        if (redis != null) {
            String cached = redis.get("syncguard:code:" + code);
            if (cached != null) {
                String[] parts = cached.split(";");
                return new VerificationData(code, parts[0], parts[1], Long.parseLong(parts[2]), parts[3], parts[4]);
            }
        }
        try (PreparedStatement stmt = sqlConnection.prepareStatement(
                "SELECT username, server_id, expiry, ip, discord_id FROM verifications WHERE code = ?")) {
            stmt.setString(1, code);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                VerificationData data = new VerificationData(code, rs.getString("username"), rs.getString("server_id"),
                        rs.getLong("expiry"), rs.getString("ip"), rs.getString("discord_id"));
                if (redis != null) {
                    redis.setex("syncguard:code:" + code, 600, data.username + ";" + data.serverId + ";" +
                            data.expiry + ";" + data.ip + ";" + data.discordId);
                }
                return data;
            }
            return null;
        }
    }

    public VerificationData getVerificationDataByUsername(String username) throws SQLException {
        if (sqlConnection == null) throw new SQLException("Database not connected.");
        try (PreparedStatement stmt = sqlConnection.prepareStatement(
                "SELECT code, username, server_id, expiry, ip, discord_id FROM verifications WHERE username = ?")) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new VerificationData(rs.getString("code"), rs.getString("username"), rs.getString("server_id"),
                        rs.getLong("expiry"), rs.getString("ip"), rs.getString("discord_id"));
            }
            return null;
        }
    }

    public void removeCode(String code) throws SQLException {
        if (sqlConnection == null) return;
        try (PreparedStatement stmt = sqlConnection.prepareStatement(
                "DELETE FROM verifications WHERE code = ?")) {
            stmt.setString(1, code);
            stmt.execute();
        }
        if (redis != null) redis.del("syncguard:code:" + code);
    }

    public void store2FV(String uuid, String code) throws SQLException {
        if (sqlConnection == null) throw new SQLException("Database not connected.");
        try (PreparedStatement stmt = sqlConnection.prepareStatement(
                "REPLACE INTO two_factor (uuid, code, expiry) VALUES (?, ?, ?)")) {
            stmt.setString(1, uuid);
            stmt.setString(2, code);
            stmt.setLong(3, System.currentTimeMillis() + 120000);
            stmt.execute();
        }
        if (redis != null) redis.setex("syncguard:2fv:" + uuid, 120, code);
    }

    public String get2FV(String uuid) throws SQLException {
        if (sqlConnection == null) throw new SQLException("Database not connected.");
        if (redis != null) {
            String code = redis.get("syncguard:2fv:" + uuid);
            if (code != null) return code;
        }
        try (PreparedStatement stmt = sqlConnection.prepareStatement(
                "SELECT code FROM two_factor WHERE uuid = ? AND expiry > ?")) {
            stmt.setString(1, uuid);
            stmt.setLong(2, System.currentTimeMillis());
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getString("code") : null;
        }
    }

    public void remove2FV(String uuid) throws SQLException {
        if (sqlConnection == null) return;
        try (PreparedStatement stmt = sqlConnection.prepareStatement(
                "DELETE FROM two_factor WHERE uuid = ?")) {
            stmt.setString(1, uuid);
            stmt.execute();
        }
        if (redis != null) redis.del("syncguard:2fv:" + uuid);
    }

    public boolean isPending2FV(String uuid) throws SQLException {
        return get2FV(uuid) != null;
    }

    public void logEvent(String username, String discordId, String code, String status) throws SQLException {
        if (sqlConnection == null) return;
        try (PreparedStatement stmt = sqlConnection.prepareStatement(
                "INSERT INTO audit_logs (username, discord_id, code, status, timestamp) VALUES (?, ?, ?, ?, ?)")) {
            stmt.setString(1, username);
            stmt.setString(2, discordId);
            stmt.setString(3, code);
            stmt.setString(4, status);
            stmt.setLong(5, System.currentTimeMillis());
            stmt.execute();
        }
        if (redis != null) {
            redis.publish("syncguard:log", (discordId != null ? discordId : "") + ";" +
                    (username != null ? username : "") + ";" +
                    (code != null ? code : "") + ";" + status);
        }
    }

    public void close() {
        try {
            if (sqlConnection != null && !sqlConnection.isClosed()) sqlConnection.close();
            if (redis != null) redis.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error closing database connection: " + e.getMessage());
        }
    }
}

class VerificationData {
    String code, username, serverId, ip, discordId;
    long expiry;

    VerificationData(String code, String username, String serverId, long expiry, String ip, String discordId) {
        this.code = code;
        this.username = username;
        this.serverId = serverId;
        this.expiry = expiry;
        this.ip = ip;
        this.discordId = discordId;
    }
}