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
  private final SecureRandom random = new SecureRandom();

  @Override
  public void onEnable() {
    // Save default config
    saveDefaultConfig();

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
}
