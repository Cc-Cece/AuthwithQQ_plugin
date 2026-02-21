package com.cccece.authwithqq.database;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
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
 * Schema v2: name as primary key, UUID as secondary.
 */
public class DatabaseManager {
  private static final int SCHEMA_VERSION = 2;
  private static final String OFFLINE_UUID_NAMESPACE = "OfflinePlayer:";

  private final String url;
  private final Logger logger;

  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Logger instance is a shared service, not meant for defensive copying.")
  public DatabaseManager(String path, Logger logger) {
    this.url = "jdbc:sqlite:" + path;
    this.logger = logger;
    initialize();
  }

  /** Generates UUID using Minecraft offline mode algorithm. */
  public static UUID offlinePlayerUuid(String name) {
    if (name == null || name.isEmpty()) return UUID.nameUUIDFromBytes(new byte[0]);
    return UUID.nameUUIDFromBytes((OFFLINE_UUID_NAMESPACE + name).getBytes(StandardCharsets.UTF_8));
  }

  public static String normalizeName(String name) {
    return name == null ? "" : name.toLowerCase();
  }

  private void initialize() {
    try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY)");
      ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version LIMIT 1");
      int currentVersion = rs.next() ? rs.getInt(1) : 0;
      rs.close();

      if (currentVersion < SCHEMA_VERSION) {
        migrate(currentVersion, conn);
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO schema_version (version) VALUES (?)")) {
          ps.setInt(1, SCHEMA_VERSION);
          ps.executeUpdate();
        }
      } else if (currentVersion < 2) {
        ensureV2Tables(conn);
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not initialize database", e);
    }
  }

  private void migrate(int fromVersion, Connection conn) throws SQLException {
    if (fromVersion == 0) {
      if (hasOldSchema(conn)) {
        migrateToV2(conn);
      } else {
        createInitialTables(conn);
      }
      return;
    }
    if (fromVersion < 2) {
      migrateToV2(conn);
    }
  }

  /** Returns true if database has pre-v2 schema (auth_bots.owner_uuid). */
  private boolean hasOldSchema(Connection conn) throws SQLException {
    try (ResultSet rs = conn.createStatement().executeQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='auth_bots'")) {
      if (!rs.next()) return false;
    }
    try (ResultSet rs = conn.createStatement().executeQuery("PRAGMA table_info(auth_bots)")) {
      while (rs.next()) {
        if ("owner_uuid".equalsIgnoreCase(rs.getString("name"))) return true;
      }
    }
    return false;
  }

  private void createInitialTables(Connection conn) throws SQLException {
    conn.createStatement().execute("CREATE TABLE IF NOT EXISTS group_members (group_id BIGINT NOT NULL, qq BIGINT NOT NULL, PRIMARY KEY(group_id, qq))");
    conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_group_members_qq ON group_members(qq)");

    conn.createStatement().execute(
        "CREATE TABLE IF NOT EXISTS auth_players (name VARCHAR(32) PRIMARY KEY COLLATE NOCASE, uuid VARCHAR(36), qq BIGINT DEFAULT 0, created_at LONG, web_password_hash VARCHAR(255))");
    conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_auth_players_uuid ON auth_players(uuid)");
    conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_auth_players_qq ON auth_players(qq)");

    conn.createStatement().execute(
        "CREATE TABLE IF NOT EXISTS player_meta (name VARCHAR(32) COLLATE NOCASE, meta_key VARCHAR(64), meta_value TEXT, PRIMARY KEY(name, meta_key))");

    conn.createStatement().execute(
        "CREATE TABLE IF NOT EXISTS auth_bots (bot_name VARCHAR(32) PRIMARY KEY COLLATE NOCASE, owner_name VARCHAR(32) COLLATE NOCASE, bot_uuid VARCHAR(36), created_at LONG)");
    conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_auth_bots_owner ON auth_bots(owner_name)");
  }

  private void ensureV2Tables(Connection conn) throws SQLException {
    try {
      conn.createStatement().execute("ALTER TABLE auth_players ADD COLUMN web_password_hash VARCHAR(255)");
    } catch (SQLException e) {
    }
  }

  private void migrateToV2(Connection conn) throws SQLException {
    logger.info("Migrating database schema to v2 (name as primary key)");
    Statement stmt = conn.createStatement();
    if (!hasOldSchema(conn)) {
      logger.info("Schema already v2-compatible");
      return;
    }
    try {
      stmt.execute("ALTER TABLE auth_players ADD COLUMN web_password_hash VARCHAR(255)");
    } catch (SQLException e) {
    }

    stmt.execute("CREATE TABLE auth_players_new (name VARCHAR(32) PRIMARY KEY COLLATE NOCASE, uuid VARCHAR(36), qq BIGINT DEFAULT 0, created_at LONG, web_password_hash VARCHAR(255))");
    stmt.execute("CREATE INDEX idx_auth_players_new_uuid ON auth_players_new(uuid)");
    stmt.execute("CREATE INDEX idx_auth_players_new_qq ON auth_players_new(qq)");
    stmt.execute("INSERT INTO auth_players_new (name, uuid, qq, created_at, web_password_hash) SELECT LOWER(COALESCE(name, uuid)), uuid, qq, created_at, web_password_hash FROM auth_players");
    stmt.execute("DROP TABLE auth_players");
    stmt.execute("ALTER TABLE auth_players_new RENAME TO auth_players");

    stmt.execute("CREATE TABLE player_meta_new (name VARCHAR(32) COLLATE NOCASE, meta_key VARCHAR(64), meta_value TEXT, PRIMARY KEY(name, meta_key))");
    stmt.execute("INSERT INTO player_meta_new SELECT LOWER(p.name), m.meta_key, m.meta_value FROM player_meta m JOIN auth_players p ON m.uuid = p.uuid");
    stmt.execute("DROP TABLE player_meta");
    stmt.execute("ALTER TABLE player_meta_new RENAME TO player_meta");

    stmt.execute("CREATE TABLE auth_bots_new (bot_name VARCHAR(32) PRIMARY KEY COLLATE NOCASE, owner_name VARCHAR(32) COLLATE NOCASE, bot_uuid VARCHAR(36), created_at LONG)");
    stmt.execute("CREATE INDEX idx_auth_bots_new_owner ON auth_bots_new(owner_name)");
    stmt.execute("INSERT INTO auth_bots_new SELECT LOWER(b.bot_name), LOWER(p.name), b.bot_uuid, b.created_at FROM auth_bots b LEFT JOIN auth_players p ON b.owner_uuid = p.uuid");
    stmt.execute("DROP TABLE auth_bots");
    stmt.execute("ALTER TABLE auth_bots_new RENAME TO auth_bots");
    logger.info("Database migration to v2 completed");
  }

  private Connection getConnection() throws SQLException {
    return DriverManager.getConnection(url);
  }

  public void replaceGroupMembers(long groupId, List<Long> members) {
    try (Connection conn = getConnection()) {
      conn.setAutoCommit(false);
      try (PreparedStatement ds = conn.prepareStatement("DELETE FROM group_members WHERE group_id = ?");
           PreparedStatement is_ = conn.prepareStatement("INSERT OR IGNORE INTO group_members (group_id, qq) VALUES (?, ?)")) {
        ds.setLong(1, groupId);
        ds.executeUpdate();
        if (members != null) {
          for (Long qq : members) {
            is_.setLong(1, groupId);
            is_.setLong(2, qq);
            is_.addBatch();
          }
          is_.executeBatch();
        }
        conn.commit();
      } catch (SQLException e) {
        conn.rollback();
        throw e;
      } finally {
        conn.setAutoCommit(true);
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not replace group members", e);
    }
  }

  public void upsertGroupMember(long groupId, long qq) {
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("INSERT OR IGNORE INTO group_members (group_id, qq) VALUES (?, ?)")) {
      p.setLong(1, groupId);
      p.setLong(2, qq);
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not upsert group member", e);
    }
  }

  public void removeGroupMember(long groupId, long qq) {
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("DELETE FROM group_members WHERE group_id = ? AND qq = ?")) {
      p.setLong(1, groupId);
      p.setLong(2, qq);
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not remove group member", e);
    }
  }

  public boolean isQqInGroups(long qq, List<Long> groupIds) {
    if (groupIds == null || groupIds.isEmpty()) return false;
    StringBuilder sql = new StringBuilder("SELECT 1 FROM group_members WHERE qq = ? AND group_id IN (");
    for (int i = 0; i < groupIds.size(); i++) sql.append(i > 0 ? ",?" : "?");
    sql.append(") LIMIT 1");
    try (Connection conn = getConnection(); PreparedStatement p = conn.prepareStatement(sql.toString())) {
      p.setLong(1, qq);
      for (int i = 0; i < groupIds.size(); i++) p.setLong(i + 2, groupIds.get(i));
      try (ResultSet rs = p.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not check QQ in groups", e);
    }
    return false;
  }

  public void addGuest(UUID uuid, String name) {
    String n = normalizeName(name);
    String u = uuid != null ? uuid.toString() : offlinePlayerUuid(name).toString();
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement(
             "INSERT INTO auth_players (name, uuid, qq, created_at) VALUES (?, ?, 0, ?) ON CONFLICT(name) DO UPDATE SET uuid = excluded.uuid")) {
      p.setString(1, n);
      p.setString(2, u);
      p.setLong(3, System.currentTimeMillis());
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not add guest", e);
    }
  }

  /** Ensures a player record exists (e.g. for QQ registration before first join). Uses OfflinePlayer UUID. */
  public void ensurePlayerExists(String name) {
    if (name == null || name.isEmpty()) return;
    String n = normalizeName(name);
    String u = offlinePlayerUuid(name).toString();
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement(
             "INSERT OR IGNORE INTO auth_players (name, uuid, qq, created_at) VALUES (?, ?, 0, ?)")) {
      p.setString(1, n);
      p.setString(2, u);
      p.setLong(3, System.currentTimeMillis());
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not ensure player exists", e);
    }
  }

  public void ensurePlayerUuidUpdated(String name, UUID realUuid) {
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("UPDATE auth_players SET uuid = ? WHERE name = ?")) {
      p.setString(1, realUuid.toString());
      p.setString(2, normalizeName(name));
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not update player UUID", e);
    }
  }

  public void deletePlayer(UUID uuid) {
    String name = getNameByUuid(uuid);
    if (name != null) deletePlayerByName(name);
  }

  public void deletePlayerByName(String name) {
    if (name == null || name.isEmpty()) return;
    String n = normalizeName(name);
    try (Connection conn = getConnection()) {
      conn.setAutoCommit(false);
      try {
        try (PreparedStatement p = conn.prepareStatement("DELETE FROM auth_players WHERE name = ?")) {
          p.setString(1, n);
          p.executeUpdate();
        }
        try (PreparedStatement p = conn.prepareStatement("DELETE FROM player_meta WHERE name = ?")) {
          p.setString(1, n);
          p.executeUpdate();
        }
        try (PreparedStatement p = conn.prepareStatement("DELETE FROM auth_bots WHERE owner_name = ?")) {
          p.setString(1, n);
          p.executeUpdate();
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

  public void updateBinding(UUID uuid, long qq) {
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("UPDATE auth_players SET qq = ? WHERE uuid = ?")) {
      p.setLong(1, qq);
      p.setString(2, uuid.toString());
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not update binding", e);
    }
  }

  public void updateBindingByName(String name, long qq) {
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("UPDATE auth_players SET qq = ? WHERE name = ?")) {
      p.setLong(1, qq);
      p.setString(2, normalizeName(name));
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not update binding by name", e);
    }
  }

  public long getQq(UUID uuid) {
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT qq FROM auth_players WHERE uuid = ?")) {
      p.setString(1, uuid.toString());
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) return rs.getLong("qq");
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get QQ", e);
    }
    return 0;
  }

  public long getQqByName(String name) {
    if (name == null || name.isEmpty()) return 0;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT qq FROM auth_players WHERE name = ?")) {
      p.setString(1, normalizeName(name));
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) return rs.getLong("qq");
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get QQ by name", e);
    }
    return 0;
  }

  public void setMeta(UUID uuid, String key, String value) {
    String name = getNameByUuid(uuid);
    if (name != null) setMeta(name, key, value);
  }

  public void setMeta(String name, String key, String value) {
    if (name == null || name.isEmpty()) return;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("INSERT OR REPLACE INTO player_meta (name, meta_key, meta_value) VALUES (?, ?, ?)")) {
      p.setString(1, normalizeName(name));
      p.setString(2, key);
      p.setString(3, value);
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not set meta", e);
    }
  }

  public void deleteMeta(UUID uuid, String key) {
    String name = getNameByUuid(uuid);
    if (name != null) deleteMeta(name, key);
  }

  public void deleteMeta(String name, String key) {
    if (name == null || name.isEmpty()) return;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("DELETE FROM player_meta WHERE name = ? AND meta_key = ?")) {
      p.setString(1, normalizeName(name));
      p.setString(2, key);
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not delete meta", e);
    }
  }

  public List<String> getAllMetaKeys() {
    List<String> keys = new ArrayList<>();
    try (Connection conn = getConnection();
         Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery("SELECT DISTINCT meta_key FROM player_meta")) {
      while (rs.next()) keys.add(rs.getString("meta_key"));
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get all meta keys", e);
    }
    return keys;
  }

  public Map<String, String> getAllMeta(UUID uuid) {
    String name = getNameByUuid(uuid);
    return getAllMetaByName(name);
  }

  public Map<String, String> getAllMetaByName(String name) {
    Map<String, String> meta = new HashMap<>();
    if (name == null || name.isEmpty()) return meta;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT meta_key, meta_value FROM player_meta WHERE name = ?")) {
      p.setString(1, normalizeName(name));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) meta.put(rs.getString("meta_key"), rs.getString("meta_value"));
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get meta", e);
    }
    return meta;
  }

  public List<Map<String, String>> getAllPlayersData() {
    List<Map<String, String>> data = new ArrayList<>();
    try (Connection conn = getConnection();
         Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery("SELECT * FROM auth_players")) {
      while (rs.next()) {
        Map<String, String> m = new HashMap<>();
        String name = rs.getString("name");
        m.put("Name", name);
        m.put("UUID", rs.getString("uuid"));
        m.put("QQ", String.valueOf(rs.getLong("qq")));
        m.put("Created", String.valueOf(rs.getLong("created_at")));
        m.putAll(getAllMetaByName(name));
        data.add(m);
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get all players data", e);
    }
    return data;
  }

  public UUID findUuidByQq(long qq) {
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT uuid FROM auth_players WHERE qq = ?")) {
      p.setLong(1, qq);
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) {
          String u = rs.getString("uuid");
          if (u != null && !u.isEmpty()) return UUID.fromString(u);
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not find UUID by QQ", e);
    }
    return null;
  }

  public String findNameByQq(long qq) {
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT name FROM auth_players WHERE qq = ? LIMIT 1")) {
      p.setLong(1, qq);
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) return rs.getString("name");
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not find name by QQ", e);
    }
    return null;
  }

  public String getNameByUuid(UUID uuid) {
    if (uuid == null) return null;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT name FROM auth_players WHERE uuid = ?")) {
      p.setString(1, uuid.toString());
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) return rs.getString("name");
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get name by UUID", e);
    }
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT bot_name FROM auth_bots WHERE bot_uuid = ?")) {
      p.setString(1, uuid.toString());
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) return rs.getString("bot_name");
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get bot name by UUID", e);
    }
    return null;
  }

  public UUID getPlayerUuid(String name) {
    if (name == null || name.isEmpty()) return null;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT uuid FROM auth_players WHERE name = ?")) {
      p.setString(1, normalizeName(name));
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) {
          String u = rs.getString("uuid");
          if (u != null && !u.isEmpty()) return UUID.fromString(u);
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get UUID by name", e);
    }
    return null;
  }

  public UUID findUuidByNameOrQq(String identifier) {
    if (identifier == null || identifier.isEmpty()) return null;
    try {
      return UUID.fromString(identifier);
    } catch (IllegalArgumentException ignored) {
    }
    UUID byName = getPlayerUuid(identifier);
    if (byName != null) return byName;
    try {
      return findUuidByQq(Long.parseLong(identifier));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public int getAccountCountByQq(long qq) {
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT COUNT(*) FROM auth_players WHERE qq = ?")) {
      p.setLong(1, qq);
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) return rs.getInt(1);
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get account count", e);
    }
    return 0;
  }

  public void markPlayerAsBot(UUID botUuid, UUID ownerUuid, String botName) {
    String ownerName = ownerUuid != null ? getNameByUuid(ownerUuid) : null;
    markPlayerAsBot(botName, ownerName);
  }

  public void markPlayerAsBot(String botName, String ownerName) {
    if (botName == null || botName.isEmpty()) return;
    UUID uuid = offlinePlayerUuid(botName);
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("INSERT OR REPLACE INTO auth_bots (bot_name, owner_name, bot_uuid, created_at) VALUES (?, ?, ?, ?)")) {
      p.setString(1, normalizeName(botName));
      p.setString(2, ownerName != null ? normalizeName(ownerName) : "");
      p.setString(3, uuid.toString());
      p.setLong(4, System.currentTimeMillis());
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not mark player as bot", e);
    }
  }

  public int getBotCountForOwner(UUID ownerUuid) {
    String ownerName = getNameByUuid(ownerUuid);
    return getBotCountForOwnerByName(ownerName);
  }

  public int getBotCountForOwnerByName(String ownerName) {
    if (ownerName == null || ownerName.isEmpty()) return 0;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT COUNT(*) FROM auth_bots WHERE owner_name = ?")) {
      p.setString(1, normalizeName(ownerName));
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) return rs.getInt(1);
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get bot count", e);
    }
    return 0;
  }

  public boolean isBot(UUID uuid) {
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT COUNT(*) FROM auth_bots WHERE bot_uuid = ?")) {
      p.setString(1, uuid.toString());
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) return rs.getInt(1) > 0;
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not check if bot", e);
    }
    return false;
  }

  public void deleteBot(UUID botUuid) {
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("DELETE FROM auth_bots WHERE bot_uuid = ?")) {
      p.setString(1, botUuid.toString());
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not delete bot", e);
    }
  }

  public UUID getBotOwner(UUID botUuid) {
    String ownerName = getBotOwnerName(botUuid);
    return ownerName != null ? getPlayerUuid(ownerName) : null;
  }

  public String getBotOwnerName(UUID botUuid) {
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT owner_name FROM auth_bots WHERE bot_uuid = ?")) {
      p.setString(1, botUuid.toString());
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) {
          String o = rs.getString("owner_name");
          if (o != null && !o.isEmpty()) return o;
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get bot owner", e);
    }
    return null;
  }

  public List<Map<String, String>> getBotsByOwner(UUID ownerUuid) {
    return getBotsByOwnerByName(getNameByUuid(ownerUuid));
  }

  public List<Map<String, String>> getBotsByOwnerByName(String ownerName) {
    List<Map<String, String>> bots = new ArrayList<>();
    if (ownerName == null || ownerName.isEmpty()) return bots;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT * FROM auth_bots WHERE owner_name = ?")) {
      p.setString(1, normalizeName(ownerName));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          Map<String, String> m = new HashMap<>();
          m.put("bot_uuid", rs.getString("bot_uuid"));
          m.put("bot_name", rs.getString("bot_name"));
          m.put("owner_name", rs.getString("owner_name"));
          UUID ou = getPlayerUuid(rs.getString("owner_name"));
          m.put("owner_uuid", ou != null ? ou.toString() : "");
          m.put("created_at", String.valueOf(rs.getLong("created_at")));
          bots.add(m);
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get bots by owner", e);
    }
    return bots;
  }

  public UUID getOwnerByBotName(String botName) {
    String ownerName = getOwnerNameByBotName(botName);
    return ownerName != null ? getPlayerUuid(ownerName) : null;
  }

  public String getOwnerNameByBotName(String botName) {
    if (botName == null || botName.isEmpty()) return null;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT owner_name FROM auth_bots WHERE bot_name = ?")) {
      p.setString(1, normalizeName(botName));
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) {
          String o = rs.getString("owner_name");
          if (o != null && !o.isEmpty()) return o;
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get owner by bot name", e);
    }
    return null;
  }

  public UUID getBotUuidByName(String botName) {
    if (botName == null || botName.isEmpty()) return null;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT bot_uuid FROM auth_bots WHERE bot_name = ?")) {
      p.setString(1, normalizeName(botName));
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) {
          String u = rs.getString("bot_uuid");
          if (u != null && !u.isEmpty()) return UUID.fromString(u);
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get bot UUID by name", e);
    }
    return null;
  }

  public List<Map<String, String>> getAllBotsData() {
    List<Map<String, String>> bots = new ArrayList<>();
    String sql = "SELECT b.bot_uuid, b.bot_name, b.owner_name, b.created_at, p.name as owner_display, p.qq as owner_qq FROM auth_bots b LEFT JOIN auth_players p ON b.owner_name = p.name";
    try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        Map<String, String> m = new HashMap<>();
        m.put("bot_uuid", rs.getString("bot_uuid"));
        m.put("bot_name", rs.getString("bot_name"));
        UUID ou = getPlayerUuid(rs.getString("owner_name"));
        m.put("owner_uuid", ou != null ? ou.toString() : "");
        m.put("owner_name", rs.getString("owner_display"));
        m.put("owner_qq", rs.getString("owner_qq") != null ? String.valueOf(rs.getLong("owner_qq")) : "0");
        m.put("created_at", String.valueOf(rs.getLong("created_at")));
        bots.add(m);
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get all bots data", e);
    }
    return bots;
  }

  public void setWebPasswordHash(UUID uuid, String passwordHash) {
    String name = getNameByUuid(uuid);
    if (name != null) setWebPasswordHashByName(name, passwordHash);
  }

  public void setWebPasswordHashByName(String name, String passwordHash) {
    if (name == null || name.isEmpty()) return;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("UPDATE auth_players SET web_password_hash = ? WHERE name = ?")) {
      p.setString(1, passwordHash);
      p.setString(2, normalizeName(name));
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not set web password hash", e);
    }
  }

  public String getWebPasswordHash(UUID uuid) {
    String name = getNameByUuid(uuid);
    return name != null ? getWebPasswordHashByName(name) : null;
  }

  public String getWebPasswordHashByName(String name) {
    if (name == null || name.isEmpty()) return null;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("SELECT web_password_hash FROM auth_players WHERE name = ?")) {
      p.setString(1, normalizeName(name));
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) return rs.getString("web_password_hash");
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not get web password hash", e);
    }
    return null;
  }

  public void removeWebPassword(UUID uuid) {
    String name = getNameByUuid(uuid);
    if (name != null) removeWebPasswordByName(name);
  }

  public void removeWebPasswordByName(String name) {
    if (name == null || name.isEmpty()) return;
    try (Connection conn = getConnection();
         PreparedStatement p = conn.prepareStatement("UPDATE auth_players SET web_password_hash = NULL WHERE name = ?")) {
      p.setString(1, normalizeName(name));
      p.executeUpdate();
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Could not remove web password", e);
    }
  }

  public boolean hasWebPassword(UUID uuid) {
    String hash = getWebPasswordHash(uuid);
    return hash != null && !hash.isEmpty();
  }
}
