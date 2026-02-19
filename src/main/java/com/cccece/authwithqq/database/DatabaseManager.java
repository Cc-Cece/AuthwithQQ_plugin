package com.cccece.authwithqq.database;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the SQLite database for AuthWithQq.
 */
public class DatabaseManager {
  private final String url;
  private final Logger logger;

  /**
   * Initializes the DatabaseManager.
   *
   * @param path The path to the SQLite database file.
   * @param logger The logger for reporting errors.
   */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Logger instance is a shared service, not meant for defensive copying.")
  public DatabaseManager(String path, Logger logger) {
    this.url = "jdbc:sqlite:" + path;
    this.logger = logger;
    initialize();
  }

  private void initialize() {
    try (Connection conn = getConnection();
         Statement stmt = conn.createStatement()) {
      // Table for basic player information
      stmt.execute("CREATE TABLE IF NOT EXISTS auth_players ("
          + "uuid VARCHAR(36) PRIMARY KEY, "
          + "name VARCHAR(32), "
          + "qq BIGINT DEFAULT 0, "
          + "created_at LONG"
          + ")");
      // Table for dynamic player metadata
      stmt.execute("CREATE TABLE IF NOT EXISTS player_meta ("
          + "uuid VARCHAR(36), "
          + "meta_key VARCHAR(64), "
          + "meta_value TEXT, "
          + "PRIMARY KEY(uuid, meta_key)"
          + ")");
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not initialize database", e);
    }
  }

  private Connection getConnection() throws SQLException {
    return DriverManager.getConnection(url);
  }

  /**
   * Adds a guest player to the database if they don't exist.
   *
   * @param uuid The player's UUID.
   * @param name The player's name.
   */
  public void addGuest(UUID uuid, String name) {
    String sql = "INSERT OR IGNORE INTO auth_players (uuid, name, created_at) VALUES (?, ?, ?)";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, uuid.toString());
      pstmt.setString(2, name);
      pstmt.setLong(3, System.currentTimeMillis());
      pstmt.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not add guest", e);
    }
  }

  /**
   * Updates the binding information for a player.
   *
   * @param uuid The player's UUID.
   * @param qq The QQ number to bind.
   */
  public void updateBinding(UUID uuid, long qq) {
    String sql = "UPDATE auth_players SET qq = ? WHERE uuid = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setLong(1, qq);
      pstmt.setString(2, uuid.toString());
      pstmt.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not update binding", e);
    }
  }

  /**
   * Gets the QQ bound to a UUID.
   *
   * @param uuid The player's UUID.
   * @return The bound QQ number, or 0 if not bound.
   */
  public long getQq(UUID uuid) {
    String sql = "SELECT qq FROM auth_players WHERE uuid = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, uuid.toString());
      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          return rs.getLong("qq");
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get QQ", e);
    }
    return 0;
  }

  /**
   * Sets a metadata value for a player.
   *
   * @param uuid The player's UUID.
   * @param key The metadata key.
   * @param value The metadata value.
   */
  public void setMeta(UUID uuid, String key, String value) {
    String sql = "INSERT OR REPLACE INTO player_meta (uuid, meta_key, meta_value) VALUES (?, ?, ?)";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, uuid.toString());
      pstmt.setString(2, key);
      pstmt.setString(3, value);
      pstmt.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not set meta", e);
    }
  }

  /**
   * Deletes a specific metadata entry for a player.
   *
   * @param uuid The player's UUID.
   * @param key The metadata key to delete.
   */
  public void deleteMeta(UUID uuid, String key) {
    String sql = "DELETE FROM player_meta WHERE uuid = ? AND meta_key = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, uuid.toString());
      pstmt.setString(2, key);
      pstmt.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not delete meta", e);
    }
  }

  /**
   * Gets all metadata keys present in the database.
   *
   * @return A list of distinct metadata keys.
   */
  public List<String> getAllMetaKeys() {
    List<String> keys = new ArrayList<>();
    String sql = "SELECT DISTINCT meta_key FROM player_meta";
    try (Connection conn = getConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        keys.add(rs.getString("meta_key"));
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get all meta keys", e);
    }
    return keys;
  }

  /**
   * Gets all player data for CSV export.
   *
   * @return A list of maps, each containing player data.
   */
  public List<Map<String, String>> getAllPlayersData() {
    List<Map<String, String>> data = new ArrayList<>();
    String sql = "SELECT * FROM auth_players";
    try (Connection conn = getConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        Map<String, String> playerMap = new HashMap<>();
        String uuid = rs.getString("uuid");
        playerMap.put("UUID", uuid);
        playerMap.put("Name", rs.getString("name"));
        playerMap.put("QQ", String.valueOf(rs.getLong("qq")));
        playerMap.put("Created", String.valueOf(rs.getLong("created_at")));
        
        // Fetch meta
        Map<String, String> meta = getAllMeta(UUID.fromString(uuid));
        playerMap.putAll(meta);
        
        data.add(playerMap);
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get all players data", e);
    }
    return data;
  }

  /**
   * Gets all metadata for a specific player UUID.
   *
   * @param uuid The player's UUID.
   * @return A map of metadata key-value pairs.
   */
  public Map<String, String> getAllMeta(UUID uuid) {
    Map<String, String> meta = new HashMap<>();
    String sql = "SELECT meta_key, meta_value FROM player_meta WHERE uuid = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, uuid.toString());
      try (ResultSet rs = pstmt.executeQuery()) {
        while (rs.next()) {
          meta.put(rs.getString("meta_key"), rs.getString("meta_value"));
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get player meta", e);
    }
    return meta;
  }

  /**
   * Finds a player's UUID by their QQ number.
   *
   * @param qq The QQ number.
   * @return The UUID, or null if not found.
   */
  public UUID findUuidByQq(long qq) {
    String sql = "SELECT uuid FROM auth_players WHERE qq = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setLong(1, qq);
      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          return UUID.fromString(rs.getString("uuid"));
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not find UUID by QQ", e);
    }
    return null;
  }

  /**
   * Gets a player's name by their UUID.
   *
   * @param uuid The player's UUID.
   * @return The name, or null if not found.
   */
  public String getNameByUuid(UUID uuid) {
    String sql = "SELECT name FROM auth_players WHERE uuid = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, uuid.toString());
      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          return rs.getString("name");
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get name by UUID", e);
    }
    return null;
  }

  /**
   * Gets a player's UUID by their name.
   *
   * @param name The player's name.
   * @return The UUID, or null if not found.
   */
  public UUID getPlayerUuid(String name) {
    String sql = "SELECT uuid FROM auth_players WHERE name = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, name);
      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          return UUID.fromString(rs.getString("uuid"));
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get UUID by name", e);
    }
    return null;
  }

  /**
   * Counts the number of accounts bound to a specific QQ number.
   *
   * @param qq The QQ number.
   * @return The count of bound accounts.
   */
  public int getAccountCountByQq(long qq) {
    String sql = "SELECT COUNT(*) FROM auth_players WHERE qq = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setLong(1, qq);
      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          return rs.getInt(1);
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get account count by QQ", e);
    }
    return 0;
  }

  /**
   * Marks a player as a bot and associates it with an owner UUID.
   *
   * @param botUuid The UUID of the bot player.
   * @param ownerUuid The UUID of the owner player.
   * @param botName The name of the bot.
   */
  public void markPlayerAsBot(UUID botUuid, UUID ownerUuid, String botName) {
    addGuest(botUuid, botName); // Ensure the bot is in auth_players table
    setMeta(botUuid, "bot.is_bot", "true");
    if (ownerUuid != null) {
      setMeta(botUuid, "bot.owner_uuid", ownerUuid.toString());
    }
  }

  /**
   * Counts the number of bots associated with a specific owner UUID.
   *
   * @param ownerUuid The UUID of the owner player.
   * @return The count of bots for the owner.
   */
  public int getBotCountForOwner(UUID ownerUuid) {
    String sql = "SELECT COUNT(DISTINCT uuid) FROM player_meta WHERE meta_key = 'bot.owner_uuid' AND meta_value = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, ownerUuid.toString());
      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          return rs.getInt(1);
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get bot count for owner", e);
    }
    return 0;
  }
}
