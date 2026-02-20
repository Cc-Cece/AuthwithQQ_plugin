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
      // Add web_password_hash column if it doesn't exist
      try {
        stmt.execute("ALTER TABLE auth_players ADD COLUMN web_password_hash VARCHAR(255)");
      } catch (SQLException e) {
        // Column already exists, ignore
      }
      // Table for dynamic player metadata
      stmt.execute("CREATE TABLE IF NOT EXISTS player_meta ("
          + "uuid VARCHAR(36), "
          + "meta_key VARCHAR(64), "
          + "meta_value TEXT, "
          + "PRIMARY KEY(uuid, meta_key)"
          + ")");
      // Table for bots
      stmt.execute("CREATE TABLE IF NOT EXISTS auth_bots ("
          + "bot_uuid VARCHAR(36) PRIMARY KEY, "
          + "bot_name VARCHAR(32), "
          + "owner_uuid VARCHAR(36), "
          + "created_at LONG"
          + ")");
      // Index for owner_uuid in auth_bots
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_auth_bots_owner ON auth_bots(owner_uuid)");
      // Ensure indexes on auth_players
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_auth_players_name ON auth_players(name)");
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_auth_players_qq ON auth_players(qq)");
      // Table for QQ group members (for force-group-binding feature)
      stmt.execute("CREATE TABLE IF NOT EXISTS group_members ("
          + "group_id BIGINT NOT NULL, "
          + "qq BIGINT NOT NULL, "
          + "PRIMARY KEY(group_id, qq)"
          + ")");
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_group_members_qq ON group_members(qq)");
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not initialize database", e);
    }
  }

  private Connection getConnection() throws SQLException {
    return DriverManager.getConnection(url);
  }

  // ------------------------ QQ Group Members (force-group-binding) ------------------------

  /**
   * Replaces all members of a specific group with the given list.
   *
   * @param groupId The QQ group ID.
   * @param members A list of QQ numbers that are currently members of the group.
   */
  public void replaceGroupMembers(long groupId, List<Long> members) {
    String deleteSql = "DELETE FROM group_members WHERE group_id = ?";
    String insertSql = "INSERT OR IGNORE INTO group_members (group_id, qq) VALUES (?, ?)";
    try (Connection conn = getConnection()) {
      conn.setAutoCommit(false);
      try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql);
           PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
        // Delete existing
        deleteStmt.setLong(1, groupId);
        deleteStmt.executeUpdate();

        // Insert new members
        if (members != null) {
          for (Long qq : members) {
            insertStmt.setLong(1, groupId);
            insertStmt.setLong(2, qq);
            insertStmt.addBatch();
          }
          insertStmt.executeBatch();
        }
        conn.commit();
      } catch (SQLException e) {
        conn.rollback();
        throw e;
      } finally {
        conn.setAutoCommit(true);
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not replace group members for group " + groupId, e);
    }
  }

  /**
   * Adds or updates a single group member record.
   *
   * @param groupId The QQ group ID.
   * @param qq The QQ number.
   */
  public void upsertGroupMember(long groupId, long qq) {
    String sql = "INSERT OR IGNORE INTO group_members (group_id, qq) VALUES (?, ?)";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setLong(1, groupId);
      pstmt.setLong(2, qq);
      pstmt.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not upsert group member", e);
    }
  }

  /**
   * Removes a single group member record.
   *
   * @param groupId The QQ group ID.
   * @param qq The QQ number.
   */
  public void removeGroupMember(long groupId, long qq) {
    String sql = "DELETE FROM group_members WHERE group_id = ? AND qq = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setLong(1, groupId);
      pstmt.setLong(2, qq);
      pstmt.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not remove group member", e);
    }
  }

  /**
   * Checks if a QQ number is in any of the specified groups.
   *
   * @param qq The QQ number to check.
   * @param groupIds A list of allowed group IDs.
   * @return true if qq is found in any of the groups, false otherwise.
   */
  public boolean isQqInGroups(long qq, List<Long> groupIds) {
    if (groupIds == null || groupIds.isEmpty()) {
      return false;
    }
    StringBuilder sql = new StringBuilder("SELECT 1 FROM group_members WHERE qq = ? AND group_id IN (");
    for (int i = 0; i < groupIds.size(); i++) {
      if (i > 0) {
        sql.append(",");
      }
      sql.append("?");
    }
    sql.append(") LIMIT 1");

    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
      pstmt.setLong(1, qq);
      for (int i = 0; i < groupIds.size(); i++) {
        pstmt.setLong(i + 2, groupIds.get(i));
      }
      try (ResultSet rs = pstmt.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not check QQ in groups", e);
    }
    return false;
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
   * Deletes a player and all their associated metadata and bots.
   *
   * @param uuid The player's UUID.
   */
  public void deletePlayer(UUID uuid) {
    String uuidStr = uuid.toString();
    try (Connection conn = getConnection()) {
      conn.setAutoCommit(false);
      try {
        // Delete from auth_players
        try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM auth_players WHERE uuid = ?")) {
          pstmt.setString(1, uuidStr);
          pstmt.executeUpdate();
        }
        // Delete from player_meta
        try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM player_meta WHERE uuid = ?")) {
          pstmt.setString(1, uuidStr);
          pstmt.executeUpdate();
        }
        // Delete associated bots from auth_bots
        try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM auth_bots WHERE owner_uuid = ?")) {
          pstmt.setString(1, uuidStr);
          pstmt.executeUpdate();
        }
        conn.commit();
      } catch (SQLException e) {
        conn.rollback();
        throw e;
      } finally {
        conn.setAutoCommit(true);
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not delete player", e);
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
   * For bots, returns the bot name from auth_bots table.
   * For real players, returns the name from auth_players table.
   *
   * @param uuid The player's UUID.
   * @return The name, or null if not found.
   */
  public String getNameByUuid(UUID uuid) {
    // First check if it's a bot
    if (isBot(uuid)) {
      String sql = "SELECT bot_name FROM auth_bots WHERE bot_uuid = ?";
      try (Connection conn = getConnection();
           PreparedStatement pstmt = conn.prepareStatement(sql)) {
        pstmt.setString(1, uuid.toString());
        try (ResultSet rs = pstmt.executeQuery()) {
          if (rs.next()) {
            return rs.getString("bot_name");
          }
        }
      } catch (SQLException e) {
        logger.log(Level.SEVERE, "Could not get bot name by UUID", e);
      }
    }
    
    // If not a bot or bot name not found, try auth_players table
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
   * Finds a player's UUID by name or QQ number.
   *
   * @param identifier The player's name, UUID string, or QQ number.
   * @return The UUID, or null if not found.
   */
  public UUID findUuidByNameOrQq(String identifier) {
    // Try to parse as UUID first
    try {
      return UUID.fromString(identifier);
    } catch (IllegalArgumentException e) {
      // Not a UUID, try by name
      UUID uuidByName = getPlayerUuid(identifier);
      if (uuidByName != null) {
        return uuidByName;
      }

      // Not a name, try by QQ
      try {
        long qq = Long.parseLong(identifier);
        return findUuidByQq(qq);
      } catch (NumberFormatException e2) {
        // Not a QQ number either
        return null;
      }
    }
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
        return 0; // Added for clarity, though rs.getInt(1) would be 0 if no results
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
    String sql = "INSERT OR REPLACE INTO auth_bots (bot_uuid, bot_name, owner_uuid, created_at) VALUES (?, ?, ?, ?)";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, botUuid.toString());
      pstmt.setString(2, botName);
      pstmt.setString(3, ownerUuid != null ? ownerUuid.toString() : null);
      pstmt.setLong(4, System.currentTimeMillis());
      pstmt.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not mark player as bot in auth_bots", e);
    }
  }

  /**
   * Counts the number of bots associated with a specific owner UUID.
   *
   * @param ownerUuid The UUID of the owner player.
   * @return The count of bots for the owner.
   */
  public int getBotCountForOwner(UUID ownerUuid) {
    String sql = "SELECT COUNT(*) FROM auth_bots WHERE owner_uuid = ?";
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

  /**
   * Checks if a given UUID belongs to a bot.
   *
   * @param uuid The UUID to check.
   * @return true if the UUID is associated with a bot, false otherwise.
   */
  public boolean isBot(UUID uuid) {
    String sql = "SELECT COUNT(*) FROM auth_bots WHERE bot_uuid = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, uuid.toString());
      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          return rs.getInt(1) > 0;
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not check if UUID is bot", e);
    }
    return false;
  }

  /**
   * Deletes a bot from the auth_bots table.
   *
   * @param botUuid The UUID of the bot to delete.
   */
  public void deleteBot(UUID botUuid) {
    String sql = "DELETE FROM auth_bots WHERE bot_uuid = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, botUuid.toString());
      pstmt.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not delete bot from auth_bots", e);
    }
  }

  /**
   * Gets the owner UUID of a bot.
   *
   * @param botUuid The UUID of the bot.
   * @return The owner's UUID, or null if not found or not a bot.
   */
  public UUID getBotOwner(UUID botUuid) {
    String sql = "SELECT owner_uuid FROM auth_bots WHERE bot_uuid = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, botUuid.toString());
      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          String ownerUuidStr = rs.getString("owner_uuid");
          return ownerUuidStr != null ? UUID.fromString(ownerUuidStr) : null;
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get bot owner", e);
    }
    return null;
  }

  /**
   * Gets all bots associated with a specific owner UUID.
   *
   * @param ownerUuid The UUID of the owner player.
   * @return A list of maps, each containing bot data.
   */
  public List<Map<String, String>> getBotsByOwner(UUID ownerUuid) {
    List<Map<String, String>> bots = new ArrayList<>();
    String sql = "SELECT * FROM auth_bots WHERE owner_uuid = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, ownerUuid.toString());
      try (ResultSet rs = pstmt.executeQuery()) {
        while (rs.next()) {
          Map<String, String> botMap = new HashMap<>();
          botMap.put("bot_uuid", rs.getString("bot_uuid"));
          botMap.put("bot_name", rs.getString("bot_name"));
          botMap.put("owner_uuid", rs.getString("owner_uuid"));
          botMap.put("created_at", String.valueOf(rs.getLong("created_at")));
          bots.add(botMap);
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get bots by owner", e);
    }
    return bots;
  }

  /**
   * Gets a bot's owner UUID by its name.
   *
   * @param botName The name of the bot.
   * @return The owner's UUID, or null if not found.
   */
  public UUID getOwnerByBotName(String botName) {
    String sql = "SELECT owner_uuid FROM auth_bots WHERE bot_name = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, botName);
      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          String ownerUuidStr = rs.getString("owner_uuid");
          return ownerUuidStr != null ? UUID.fromString(ownerUuidStr) : null;
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get owner by bot name", e);
    }
    return null;
  }

  /**
   * Gets a bot's owner UUID by its UUID.
   *
   * @param botUuid The UUID of the bot.
   * @return The owner's UUID, or null if not found.
   */
  public UUID getOwnerByBotUuid(UUID botUuid) {
    return getBotOwner(botUuid);
  }

  /**
   * Gets a bot's UUID by its name.
   *
   * @param botName The name of the bot.
   * @return The bot's UUID, or null if not found.
   */
  public UUID getBotUuidByName(String botName) {
    String sql = "SELECT bot_uuid FROM auth_bots WHERE bot_name = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, botName);
      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          return UUID.fromString(rs.getString("bot_uuid"));
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get bot UUID by name", e);
    }
    return null;
  }

  /**
   * Gets all bots with their owner information.
   *
   * @return A list of maps, each containing bot data with owner information.
   */
  public List<Map<String, String>> getAllBotsData() {
    List<Map<String, String>> bots = new ArrayList<>();
    String sql = "SELECT b.bot_uuid, b.bot_name, b.owner_uuid, b.created_at, "
        + "p.name as owner_name, p.qq as owner_qq "
        + "FROM auth_bots b "
        + "LEFT JOIN auth_players p ON b.owner_uuid = p.uuid";
    try (Connection conn = getConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        Map<String, String> botMap = new HashMap<>();
        botMap.put("bot_uuid", rs.getString("bot_uuid"));
        botMap.put("bot_name", rs.getString("bot_name"));
        botMap.put("owner_uuid", rs.getString("owner_uuid"));
        botMap.put("created_at", String.valueOf(rs.getLong("created_at")));
        botMap.put("owner_name", rs.getString("owner_name"));
        botMap.put("owner_qq", rs.getString("owner_qq") != null ? String.valueOf(rs.getLong("owner_qq")) : "0");
        bots.add(botMap);
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get all bots data", e);
    }
    return bots;
  }

  /**
   * Sets the web login password hash for a player.
   *
   * @param uuid The player's UUID.
   * @param passwordHash The BCrypt hash of the password.
   */
  public void setWebPasswordHash(UUID uuid, String passwordHash) {
    String sql = "UPDATE auth_players SET web_password_hash = ? WHERE uuid = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, passwordHash);
      pstmt.setString(2, uuid.toString());
      pstmt.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not set web password hash", e);
    }
  }

  /**
   * Gets the web login password hash for a player.
   *
   * @param uuid The player's UUID.
   * @return The password hash, or null if not set.
   */
  public String getWebPasswordHash(UUID uuid) {
    String sql = "SELECT web_password_hash FROM auth_players WHERE uuid = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, uuid.toString());
      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          return rs.getString("web_password_hash");
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get web password hash", e);
    }
    return null;
  }

  /**
   * Removes the web login password for a player.
   *
   * @param uuid The player's UUID.
   */
  public void removeWebPassword(UUID uuid) {
    String sql = "UPDATE auth_players SET web_password_hash = NULL WHERE uuid = ?";
    try (Connection conn = getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, uuid.toString());
      pstmt.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not remove web password", e);
    }
  }

  /**
   * Checks if a player has a web login password set.
   *
   * @param uuid The player's UUID.
   * @return true if password is set, false otherwise.
   */
  public boolean hasWebPassword(UUID uuid) {
    String hash = getWebPasswordHash(uuid);
    return hash != null && !hash.isEmpty();
  }
}

