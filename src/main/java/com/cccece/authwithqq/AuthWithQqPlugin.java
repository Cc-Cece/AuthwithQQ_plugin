package com.cccece.authwithqq;

import com.cccece.authwithqq.database.DatabaseManager;
import com.cccece.authwithqq.listener.GuestListener;
import com.cccece.authwithqq.util.CsvManager;
import com.cccece.authwithqq.web.InternalWebServer;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.security.SecureRandom; // Placed after java.util.Objects for CustomImportOrder
import java.util.UUID;
import com.cccece.authwithqq.util.MessageManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Main class for the AuthWithQq plugin.
 */
public class AuthWithQqPlugin extends JavaPlugin {
  private DatabaseManager databaseManager;
  private InternalWebServer webServer;
  private GuestListener guestListener;
  private CsvManager csvManager;
  private MessageManager messageManager; // Add this line
  private final SecureRandom random = new SecureRandom();

  @Override
  public void onEnable() {
    // Save default config
    saveDefaultConfig();

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
    csvManager = new CsvManager(databaseManager, getLogger());

    // Initialize Listeners
    guestListener = new GuestListener(this);
    getServer().getPluginManager().registerEvents(guestListener, this);

    // Initialize Commands
    registerCommands();

    // Start Web Server
    int port = getConfig().getInt("server.port", 8081);
    String token = getConfig().getString("server.token", "changeme");
    webServer = new InternalWebServer(this, port, token);
    getServer().getScheduler().runTaskAsynchronously(this, () -> webServer.start());

    getLogger().info("AuthWithQq has been enabled!");
  }

  private void registerCommands() {
    BindCommand bindCommand = new BindCommand(this);
    PluginCommand authCmd = getCommand("auth");
    if (authCmd != null) {
      authCmd.setExecutor(new AuthCommand(this));
    }
    PluginCommand bindCmd = getCommand("bind");
    if (bindCmd != null) {
      bindCmd.setExecutor(bindCommand);
    }
    PluginCommand aliasCmd = getCommand("绑定");
    if (aliasCmd != null) {
      aliasCmd.setExecutor(bindCommand);
    }
  }

  @Override
  public void onDisable() {
    if (webServer != null) {
      webServer.stop();
    }
    getLogger().info("AuthWithQq has been disabled!");
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
   * Handles successful binding from the web API.
   *
   * @param uuid The player's UUID.
   */
  public void handleBindingSuccess(UUID uuid) {
    guestListener.unmarkGuest(uuid);
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
}

