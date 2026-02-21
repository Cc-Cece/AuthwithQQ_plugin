package com.cccece.authwithqq;

import com.cccece.authwithqq.database.DatabaseManager;
import com.cccece.authwithqq.listener.GuestListener;
import com.cccece.authwithqq.util.CsvManager;
import com.cccece.authwithqq.web.InternalWebServer;
import com.cccece.authwithqq.web.OneBotWebSocketServer;
import java.io.File;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.security.SecureRandom; // Placed after java.util.Objects for CustomImportOrder
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.cccece.authwithqq.util.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.mindrot.jbcrypt.BCrypt; // BCrypt for password hashing

/**
 * Main class for the AuthWithQq plugin.
 */
public class AuthWithQqPlugin extends JavaPlugin {
  private FileConfiguration messagesConfig;
  private String currentLocale;

  private DatabaseManager databaseManager;
  private InternalWebServer webServer;
  private GuestListener guestListener;
  private CsvManager csvManager;
  private MessageManager messageManager; // Add this line
  private OneBotWebSocketServer oneBotWebSocketServer;
  private final SecureRandom random = new SecureRandom();
  private final long serverStartTime = System.currentTimeMillis(); // Server start timestamp

  // --- Today's Online Statistics (Memory-based) ---
  private final Map<UUID, Long> todayFirstJoinTime = new ConcurrentHashMap<>(); // UUID -> first join timestamp today
  private final Map<UUID, Long> todayTotalOnlineTime = new ConcurrentHashMap<>(); // UUID -> total online time in milliseconds
  private int todayUniquePlayers = 0; // Count of unique players who joined today
  private long todayResetTime = System.currentTimeMillis(); // When today's stats were reset

  // --- Recent Player Activities (Memory Queue) ---
  public static class ActivityEntry {
    public final String playerName;
    public final String activityType; // "join" or "quit"
    public final long timestamp;

    public ActivityEntry(String playerName, String activityType, long timestamp) {
      this.playerName = playerName;
      this.activityType = activityType;
      this.timestamp = timestamp;
    }
  }

  private final LinkedList<ActivityEntry> recentActivities = new LinkedList<>(); // Recent player activities
  private static final int MAX_RECENT_ACTIVITIES = 50; // Maximum number of activities to keep

  /** Web resource filenames to copy from JAR. Add new files here when adding web pages. */
  private static final String[] WEB_RESOURCE_FILES = {
    "admin_edit_player.css", "admin_edit_player.html", "admin_edit_player.js",
    "admin.css", "admin.html", "admin.js", "auth.css", "auth.html", "auth.js",
    "common.css", "components.css", "components.js", "dashboard.css", "dashboard.html",
    "dashboard.js", "edit_bot.html", "edit_bot.js", "index.html", "login.html",
    "player_dashboard.html", "profile.css", "profile.html", "profile.js", "success.html"
  };

  @Override
  public void onEnable() {
    // Save default config
    saveDefaultConfig();
    saveDefaultLang();
    saveDefaultWebResources();
    loadMessages();

    // Initialize MessageManager
    messageManager = new MessageManager(this); // Add this line

    // Initialize Database
    File dataFolder = getDataFolder();
    if (!dataFolder.exists()) {
      if (!dataFolder.mkdirs()) {
        getLogger().severe("Could not create plugin data folder!");
        getServer().getPluginManager().disablePlugin(this);
        return;
      }
    }
    databaseManager = new DatabaseManager(new File(dataFolder, "data.db").getAbsolutePath(),
        getLogger());

    // Initialize CsvManager
    csvManager = new CsvManager(this, databaseManager, getLogger());

    // Initialize Listeners
    guestListener = new GuestListener(this);
    getServer().getPluginManager().registerEvents(guestListener, this);

    // Initialize Commands
    registerCommands();

    // Start Web Server (if enabled)
    if (getConfig().getBoolean("server.enabled", true)) {
      int port = getConfig().getInt("server.port", 8081);
      String token = getConfig().getString("server.token", "changeme");
      webServer = new InternalWebServer(this, port, token);
      getServer().getScheduler().runTaskAsynchronously(this, () -> webServer.start());
    } else {
      getLogger().info("Web server disabled by config (server.enabled=false)");
    }

    // Start OneBot v11 WebSocket server (independent port)
    if (getConfig().getBoolean("onebot.enabled", false)) {
      int wsPort = getConfig().getInt("onebot.ws-port", 8080);
      String wsToken = getConfig().getString("onebot.ws-token", "");
      oneBotWebSocketServer = new OneBotWebSocketServer(this, wsPort, "/onebot/v11/ws", wsToken);
      // Java-WebSocket starts its own thread internally, this call is non-blocking.
      oneBotWebSocketServer.start();
      getLogger().info("OneBot v11 WebSocket integration enabled on port " + wsPort);
    }

    // Schedule daily reset task for today's statistics
    scheduleDailyReset();

    getLogger().info("AuthWithQq has been enabled!");
  }

  private void registerCommands() {
    BindCommand bindCommand = new BindCommand(this);
    PluginCommand authCmd = getCommand("auth");
    if (authCmd != null) {
      authCmd.setExecutor(new AuthCommand(this));
    }
    // Enable in-game /bind command
    PluginCommand bindCmd = getCommand("bind");
    if (bindCmd != null) {
      bindCmd.setExecutor(bindCommand);
    }
    PluginCommand aliasCmd = getCommand("绑定"); // Assuming "绑定" is an alias for "bind"
    if (aliasCmd != null) {
      aliasCmd.setExecutor(bindCommand);
      aliasCmd.setTabCompleter(bindCommand); // Set tab completer for alias
    }
    bindCmd.setTabCompleter(bindCommand); // Set tab completer for /bind command
  }

  @Override
  public void onDisable() {
    if (oneBotWebSocketServer != null) {
      try {
        oneBotWebSocketServer.stop();
      } catch (InterruptedException e) {
        getLogger().log(java.util.logging.Level.WARNING, "Error while stopping OneBot WebSocket server", e);
        Thread.currentThread().interrupt();
      }
    }
    if (webServer != null) {
      webServer.stop();
    }
    getLogger().info("AuthWithQq has been disabled!");
  }

  private void loadMessages() {
    if (currentLocale == null || currentLocale.isEmpty()) {
      currentLocale = getConfig().getString("lang", "zh_CN");
    }
    java.io.File langDir = new java.io.File(getDataFolder(), "lang");
    if (!langDir.exists()) {
      langDir.mkdirs();
    }
    java.io.File messagesFile = new java.io.File(langDir, currentLocale + ".yml");
    if (!messagesFile.exists()) {
      // fallback to zh_CN bundled default
      saveResource("lang/zh_CN.yml", false);
      messagesFile = new java.io.File(langDir, "zh_CN.yml");
    }
    messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
  }

  public FileConfiguration getMessagesConfig() {
    if (messagesConfig == null) {
      loadMessages();
    }
    return messagesConfig;
  }

  public String getMessage(String path) {
    return getMessage(path, null);
  }

  public String getMessage(String path, java.util.Map<String, String> placeholders) {
    if (messagesConfig == null) {
      loadMessages();
    }
    String raw = messagesConfig.getString(path);
    if (raw == null) {
      // Fallback to path itself to make missing keys显眼
      raw = path;
    }
    if (placeholders != null) {
      for (java.util.Map.Entry<String, String> entry : placeholders.entrySet()) {
        raw = raw.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
      }
    }
    return raw;
  }

  private void saveDefaultLang() {
    // Ensure default language files exist
    java.io.File langDir = new java.io.File(getDataFolder(), "lang");
    if (!langDir.exists()) {
      langDir.mkdirs();
    }
    // Save all bundled language files if they don't exist
    String[] locales = {"zh_CN", "zh_TW", "en_US"};
    for (String locale : locales) {
      java.io.File langFile = new java.io.File(langDir, locale + ".yml");
      if (!langFile.exists()) {
        saveResource("lang/" + locale + ".yml", false);
      }
    }
  }

  /**
   * Copies web resources from JAR to plugin directory if they don't exist.
   * Allows server owners to customize HTML/CSS/JS. Missing files fall back to JAR.
   */
  private void saveDefaultWebResources() {
    java.io.File webDir = new java.io.File(getDataFolder(), "web");
    if (!webDir.exists()) {
      webDir.mkdirs();
    }
    for (String filename : WEB_RESOURCE_FILES) {
      java.io.File dest = new java.io.File(webDir, filename);
      if (!dest.exists()) {
        saveResource("web/" + filename, false);
      }
    }
  }

  /**
   * Returns the web resources directory. Files here override JAR resources.
   */
  public java.io.File getWebResourcesDir() {
    return new java.io.File(getDataFolder(), "web");
  }

  /**
   * Gets the DatabaseManager instance.
   *
   * @return The DatabaseManager.
   */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "DatabaseManager is a shared service, not meant for defensive copying.")
  public DatabaseManager getDatabaseManager() {
    return databaseManager;
  }

  /**
   * Gets the CsvManager instance.
   *
   * @return The CsvManager.
   */
  public CsvManager getCsvManager() {
    return csvManager;
  }

  /**
   * Gets the MessageManager instance.
   *
   * @return The MessageManager.
   */
  public com.cccece.authwithqq.util.MessageManager getMessageManager() {
    return messageManager;
  }

  /**
   * Gets the GuestListener instance.
   *
   * @return The GuestListener.
   */
  public GuestListener getGuestListener() {
    return guestListener;
  }

  /**
   * Handles successful binding from the web API.
   *
   * @param uuid The player's UUID.
   */
  public void handleBindingSuccess(UUID uuid) {
    guestListener.unmarkGuest(uuid);
  }

  /**
   * Handles changes in binding status for an online player, updating their guest status accordingly.
   * This method should be called on the main server thread.
   *
   * @param uuid The player's UUID.
   * @param newQq The new QQ number (0 for unbound).
   */
  public void handleBindingChange(UUID uuid, long newQq) {
    // Schedule on main thread to interact with Bukkit API
    getServer().getScheduler().runTask(this, () -> {
      Player player = getServer().getPlayer(uuid);
      if (player != null) { // Only update status for online players
        if (newQq == 0) { // Unbound
          guestListener.markGuest(player);
        } else { // Bound
          guestListener.unmarkGuest(uuid);
        }
      }
    });
  }

  /**
   * Helper to parse query parameters from a URI.
   *
   * @param query The query string.
   * @return A map of parameters.
   */
  public static Map<String, String> parseQuery(String query) {
    Map<String, String> result = new HashMap<>();
    if (query == null) {
      return result;
    }
    for (String param : query.split("&")) {
      String[] entry = param.split("=");
      if (entry.length > 1) {
        result.put(entry[0], entry[1]);
      } else {
        result.put(entry[0], "");
      }
    }
    return result;
  }

  /**
   * Generates a random numeric code for binding.
   *
   * @return A numeric string code.
   */
  public String generateCode() {
    int length = getConfig().getInt("binding.code-length", 6);
    if (length < 4 || length > 8) {
      getLogger().warning("Invalid code-length configured. Defaulting to 6.");
      length = 6;
    }
    StringBuilder code = new StringBuilder();
    for (int i = 0; i < length; i++) {
      code.append(random.nextInt(10)); // 0-9
    }
    return code.toString();
  }

  // --- Verification Code Management ---
  private final Map<UUID, VerificationCodeEntry> playerVerificationCodes = new HashMap<>();

  // Inner class to hold verification code and its generation timestamp
  private static class VerificationCodeEntry {
    final String code;
    final long timestamp;

    VerificationCodeEntry(String code, long timestamp) {
      this.code = code;
      this.timestamp = timestamp;
    }
  }

  /**
   * Retrieves an existing verification code for a player or generates a new one if expired or not found.
   *
   * @param uuid The player's UUID.
   * @return The verification code.
   */
  public String getOrCreateCode(UUID uuid) {
    int codeExpiration = getConfig().getInt("binding.code-expiration", 300); // Default 300 seconds
    VerificationCodeEntry entry = playerVerificationCodes.get(uuid);
    String verificationCode;

    if (entry != null && (System.currentTimeMillis() - entry.timestamp) < (codeExpiration * 1000L)) {
      // Use existing code if not expired
      verificationCode = entry.code;
    } else {
      // Generate new code and store with current timestamp
      verificationCode = generateCode();
      playerVerificationCodes.put(uuid, new VerificationCodeEntry(verificationCode, System.currentTimeMillis()));
    }
    return verificationCode;
  }

  /**
   * Checks if a given verification code is valid for a player's UUID and is not expired.
   *
   * @param code The code to validate.
   * @param uuid The player's UUID.
   * @return true if the code is valid and not expired, false otherwise.
   */
  public boolean isValidCode(String code, UUID uuid) {
    int codeExpiration = getConfig().getInt("binding.code-expiration", 300); // Default 300 seconds
    VerificationCodeEntry entry = playerVerificationCodes.get(uuid);

    return entry != null
        && entry.code.equals(code)
        && (System.currentTimeMillis() - entry.timestamp) < (codeExpiration * 1000L);
  }

  /**
   * Invalidates a verification code for a player, typically after a successful bind.
   *
   * @param uuid The player's UUID.
   */
  public void invalidateCode(UUID uuid) {
    playerVerificationCodes.remove(uuid);
  }

  /**
   * Finds a player's UUID and name by their verification code.
   * This method is designed to be thread-safe for read operations.
   *
   * @param code The verification code to search for.
   * @return A map containing "uuid" and "name", or null if not found or expired.
   */
  public Map<String, String> findPlayerInfoByCode(String code) {
    if (code == null || code.isEmpty()) {
      return null;
    }
    int codeExpiration = getConfig().getInt("binding.code-expiration", 300); // Default 300 seconds

    for (Map.Entry<UUID, VerificationCodeEntry> entry : playerVerificationCodes.entrySet()) {
      VerificationCodeEntry verificationEntry = entry.getValue();
      if (verificationEntry.code.equals(code)) {
        // Check if the code is expired
        if ((System.currentTimeMillis() - verificationEntry.timestamp) < (codeExpiration * 1000L)) {
          UUID playerUuid = entry.getKey();
          String playerName = Bukkit.getOfflinePlayer(playerUuid).getName();

          Map<String, String> playerInfo = new HashMap<>();
          playerInfo.put("uuid", playerUuid.toString());
          playerInfo.put("name", playerName != null ? playerName : "");
          return playerInfo;
        } else {
          // Code found but expired
          return null;
        }
      }
    }
    return null; // Code not found
  }

  // --- Profile Session Token Management ---
  private final Map<String, ProfileSessionTokenEntry> playerProfileSessionTokens = new HashMap<>(); // Token -> Entry
  private final Map<UUID, String> playerToTokenMap = new HashMap<>(); // Player UUID -> Token for quick lookup

  // Inner class to hold profile session token and its generation timestamp
  private static class ProfileSessionTokenEntry {
    final UUID uuid;
    final String token;
    final long timestamp;

    ProfileSessionTokenEntry(UUID uuid, String token, long timestamp) {
      this.uuid = uuid;
      this.token = token;
      this.timestamp = timestamp;
    }
  }

  /**
   * Generates a new, time-limited session token for a player to access their web profile.
   * Invalidates any existing token for this player.
   *
   * @param uuid The player's UUID.
   * @return The generated session token string.
   */
  public String createProfileSessionToken(UUID uuid) {
    // Invalidate any existing token for this player
    String existingToken = playerToTokenMap.remove(uuid);
    if (existingToken != null) {
      playerProfileSessionTokens.remove(existingToken);
    }

    int tokenExpiration = getConfig().getInt("binding.profile-token-expiration", 300); // Default 300 seconds
    String token = UUID.randomUUID().toString(); // Generate a random UUID as token

    playerProfileSessionTokens.put(token, new ProfileSessionTokenEntry(uuid, token, System.currentTimeMillis()));
    playerToTokenMap.put(uuid, token); // Store reverse mapping

    // Schedule task to remove token after expiration
    getServer().getScheduler().runTaskLater(this, () -> {
      ProfileSessionTokenEntry entry = playerProfileSessionTokens.get(token);
      if (entry != null && entry.uuid.equals(uuid)) { // Ensure it's the same token
        playerProfileSessionTokens.remove(token);
        playerToTokenMap.remove(uuid);
        getLogger().info("Profile session token for " + uuid + " expired and removed.");
      }
    }, tokenExpiration * 20L); // 20 ticks per second

    return token;
  }

  /**
   * Gets the UUID associated with a profile session token without consuming it.
   * Used for viewing profile data.
   *
   * @param token The session token string.
   * @return The UUID of the player associated with the token, or null if invalid or expired.
   */
  public UUID getProfileSessionTokenUuid(String token) {
    int tokenExpiration = getConfig().getInt("binding.profile-token-expiration", 300); // Default 300 seconds
    ProfileSessionTokenEntry entry = playerProfileSessionTokens.get(token);

    if (entry != null && (System.currentTimeMillis() - entry.timestamp) < (tokenExpiration * 1000L)) {
      // Token is valid, but don't remove it (for viewing)
      return entry.uuid;
    }
    return null;
  }

  /**
   * Validates a profile session token and returns the associated player UUID if valid and not expired.
   * The token is considered single-use and is invalidated after a successful validation.
   * Use this method when updating profile data to ensure the token is consumed.
   *
   * @param token The session token string.
   * @return The UUID of the player associated with the token, or null if invalid or expired.
   */
  public UUID validateProfileSessionToken(String token) {
    int tokenExpiration = getConfig().getInt("binding.profile-token-expiration", 300); // Default 300 seconds
    ProfileSessionTokenEntry entry = playerProfileSessionTokens.get(token);

    if (entry != null && (System.currentTimeMillis() - entry.timestamp) < (tokenExpiration * 1000L)) {
      // Token is valid, remove it after first use for security (single-use)
      playerProfileSessionTokens.remove(token);
      playerToTokenMap.remove(entry.uuid);
      return entry.uuid;
    }
    return null;
  }

  // --- Web Login Session Management ---
  private final Map<String, WebLoginSessionEntry> webLoginSessions = new HashMap<>(); // Token -> Entry

  // Inner class to hold web login session token and its expiration
  public static class WebLoginSessionEntry {
    public final UUID uuid;
    public final String token;
    public final long createdAt;
    public final long expiresAt;

    WebLoginSessionEntry(UUID uuid, String token, long createdAt, long expiresAt) {
      this.uuid = uuid;
      this.token = token;
      this.createdAt = createdAt;
      this.expiresAt = expiresAt;
    }
  }

  /**
   * Creates a new web login session token for a player.
   *
   * @param uuid The player's UUID.
   * @return The generated session token string.
   */
  public String createWebLoginSession(UUID uuid) {
    int sessionExpiration = getConfig().getInt("binding.web-session-expiration", 1440); // Default 1440 minutes (24 hours)
    String token = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    long expiresAt = sessionExpiration > 0 ? now + (sessionExpiration * 60L * 1000L) : Long.MAX_VALUE;

    webLoginSessions.put(token, new WebLoginSessionEntry(uuid, token, now, expiresAt));

    // Schedule task to remove token after expiration (if expiration is set)
    if (sessionExpiration > 0) {
      getServer().getScheduler().runTaskLater(this, () -> {
        WebLoginSessionEntry entry = webLoginSessions.get(token);
        if (entry != null && entry.token.equals(token)) {
          webLoginSessions.remove(token);
          getLogger().info("Web login session token for " + uuid + " expired and removed.");
        }
      }, sessionExpiration * 60L * 20L); // Convert minutes to ticks
    }

    return token;
  }

  /**
   * Validates a web login session token and returns the associated player UUID if valid and not expired.
   *
   * @param token The session token string.
   * @return The UUID of the player associated with the token, or null if invalid or expired.
   */
  public UUID validateWebLoginSessionToken(String token) {
    if (token == null || token.isEmpty()) {
      return null;
    }
    WebLoginSessionEntry entry = webLoginSessions.get(token);
    if (entry != null && System.currentTimeMillis() < entry.expiresAt) {
      return entry.uuid;
    }
    // Token expired or not found, remove it
    if (entry != null) {
      webLoginSessions.remove(token);
    }
    return null;
  }

  /**
   * Revokes a web login session token.
   *
   * @param token The session token string.
   */
  public void revokeWebLoginSession(String token) {
    webLoginSessions.remove(token);
  }

  /**
   * Gets session information for a token.
   *
   * @param token The session token string.
   * @return The session entry, or null if not found or expired.
   */
  public WebLoginSessionEntry getWebLoginSession(String token) {
    WebLoginSessionEntry entry = webLoginSessions.get(token);
    if (entry != null && System.currentTimeMillis() < entry.expiresAt) {
      return entry;
    }
    if (entry != null) {
      webLoginSessions.remove(token);
    }
    return null;
  }

  /**
   * Verifies a password against a stored hash.
   *
   * @param password The plain text password.
   * @param hash The BCrypt hash.
   * @return true if password matches, false otherwise.
   */
  public boolean verifyPassword(String password, String hash) {
    if (password == null || hash == null) {
      return false;
    }
    try {
      return BCrypt.checkpw(password, hash);
    } catch (Exception e) {
      getLogger().warning("Error verifying password: " + e.getMessage());
      return false;
    }
  }

  /**
   * Hashes a password using BCrypt.
   *
   * @param password The plain text password.
   * @return The BCrypt hash.
   */
  public String hashPassword(String password) {
    return BCrypt.hashpw(password, BCrypt.gensalt());
  }

  /**
   * Gets the server start time in milliseconds.
   *
   * @return The server start timestamp.
   */
  public long getServerStartTime() {
    return serverStartTime;
  }

  /**
   * Records a player join event for today's statistics and recent activities.
   *
   * @param uuid The player's UUID.
   * @param playerName The player's name.
   */
  public void recordPlayerJoin(UUID uuid, String playerName) {
    long now = System.currentTimeMillis();
    
    // Check if we need to reset today's stats (new day)
    if (now - todayResetTime >= 24 * 60 * 60 * 1000L) {
      resetTodayStats();
    }
    
    // Record first join time if not already recorded today
    if (!todayFirstJoinTime.containsKey(uuid)) {
      todayFirstJoinTime.put(uuid, now);
      todayUniquePlayers++;
    }
    
    // Add to recent activities
    addRecentActivity(playerName, "join", now);
  }

  /**
   * Records a player quit event for today's statistics and recent activities.
   *
   * @param uuid The player's UUID.
   * @param playerName The player's name.
   */
  public void recordPlayerQuit(UUID uuid, String playerName) {
    long now = System.currentTimeMillis();
    
    // Calculate online time for this session
    Long firstJoinTime = todayFirstJoinTime.get(uuid);
    if (firstJoinTime != null) {
      long sessionTime = now - firstJoinTime;
      todayTotalOnlineTime.merge(uuid, sessionTime, Long::sum);
      // Remove from first join time map (will be re-added on next join)
      todayFirstJoinTime.remove(uuid);
    }
    
    // Add to recent activities
    addRecentActivity(playerName, "quit", now);
  }

  /**
   * Adds an activity to the recent activities queue.
   *
   * @param playerName The player's name.
   * @param activityType The type of activity ("join" or "quit").
   * @param timestamp The timestamp of the activity.
   */
  private void addRecentActivity(String playerName, String activityType, long timestamp) {
    synchronized (recentActivities) {
      recentActivities.addLast(new ActivityEntry(playerName, activityType, timestamp));
      // Keep only the most recent activities
      while (recentActivities.size() > MAX_RECENT_ACTIVITIES) {
        recentActivities.removeFirst();
      }
    }
  }

  /**
   * Resets today's statistics (called at midnight or server start if needed).
   */
  private void resetTodayStats() {
    todayFirstJoinTime.clear();
    todayTotalOnlineTime.clear();
    todayUniquePlayers = 0;
    todayResetTime = System.currentTimeMillis();
  }

  /**
   * Schedules a daily reset task for today's statistics.
   */
  private void scheduleDailyReset() {
    // Calculate milliseconds until next midnight
    long now = System.currentTimeMillis();
    java.util.Calendar cal = java.util.Calendar.getInstance();
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
    cal.set(java.util.Calendar.MINUTE, 0);
    cal.set(java.util.Calendar.SECOND, 0);
    cal.set(java.util.Calendar.MILLISECOND, 0);
    cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
    long nextMidnight = cal.getTimeInMillis();
    long delay = nextMidnight - now;
    
    // Schedule task to run at midnight
    getServer().getScheduler().runTaskLater(this, () -> {
      resetTodayStats();
      scheduleDailyReset(); // Schedule next reset
    }, delay / 50); // Convert to ticks (50ms per tick)
  }

  /**
   * Gets today's unique player count.
   *
   * @return The number of unique players who joined today.
   */
  public int getTodayUniquePlayers() {
    return todayUniquePlayers;
  }

  /**
   * Gets today's total online time in milliseconds.
   *
   * @return The total online time in milliseconds.
   */
  public long getTodayTotalOnlineTime() {
    // Calculate current online time for players who are still online
    long now = System.currentTimeMillis();
    long currentOnlineTime = 0;
    for (Map.Entry<UUID, Long> entry : todayFirstJoinTime.entrySet()) {
      currentOnlineTime += now - entry.getValue();
    }
    
    // Sum all recorded online times
    long totalRecorded = todayTotalOnlineTime.values().stream().mapToLong(Long::longValue).sum();
    
    return totalRecorded + currentOnlineTime;
  }

  /**
   * Gets the list of recent player activities.
   *
   * @return A copy of the recent activities list.
   */
  public LinkedList<ActivityEntry> getRecentActivities() {
    synchronized (recentActivities) {
      return new LinkedList<>(recentActivities);
    }
  }

  /**
   * Validates if a bot name matches the configured prefix requirement.
   *
   * @param botName The bot name to validate.
   * @return true if the bot name is valid (prefix check passes or prefix is not configured), false otherwise.
   */
  public boolean validateBotName(String botName) {
    String prefix = getConfig().getString("binding.bot-name-prefix", "");
    if (prefix == null || prefix.isEmpty()) {
      return true; // No prefix configured, skip validation
    }
    return botName != null && botName.startsWith(prefix);
  }

  /**
   * Gets today's online time for each player.
   * Returns a map of player names to their online time in milliseconds.
   * This includes both currently online players and players who have quit.
   *
   * @return A map of player names to online time in milliseconds.
   */
  public Map<String, Long> getTodayPlayerOnlineTimes() {
    Map<String, Long> result = new HashMap<>();
    long now = System.currentTimeMillis();
    
    // Add online time for currently online players
    for (Map.Entry<UUID, Long> entry : todayFirstJoinTime.entrySet()) {
      UUID uuid = entry.getKey();
      long joinTime = entry.getValue();
      long onlineTime = now - joinTime;
      
      // Get player name
      String playerName = getDatabaseManager().getNameByUuid(uuid);
      if (playerName == null) {
        // Try to get from online players
        Player player = getServer().getPlayer(uuid);
        if (player != null) {
          playerName = player.getName();
        } else {
          playerName = uuid.toString(); // Fallback to UUID
        }
      }
      
      // Add to existing time if player has quit before
      Long existingTime = todayTotalOnlineTime.get(uuid);
      if (existingTime != null) {
        result.put(playerName, existingTime + onlineTime);
      } else {
        result.put(playerName, onlineTime);
      }
    }
    
    // Add online time for players who have quit (not currently online)
    for (Map.Entry<UUID, Long> entry : todayTotalOnlineTime.entrySet()) {
      UUID uuid = entry.getKey();
      if (!todayFirstJoinTime.containsKey(uuid)) {
        // Player has quit, only use recorded time
        String playerName = getDatabaseManager().getNameByUuid(uuid);
        if (playerName == null) {
          playerName = uuid.toString(); // Fallback to UUID
        }
        result.put(playerName, entry.getValue());
      }
    }
    
    return result;
  }
}
