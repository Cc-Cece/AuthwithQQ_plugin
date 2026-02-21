package com.cccece.authwithqq.web;

import com.cccece.authwithqq.AuthWithQqPlugin;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * An internal HTTP server for handling QQ bot requests.
 */
public class InternalWebServer {
  private final AuthWithQqPlugin plugin;
  private final int port;
  private final String token;
  private final Gson gson = new Gson();
  private HttpServer server;

  /**
   * Initializes the Web Server.
   *
   * @param plugin The plugin instance.
   * @param port The port to listen on.
   * @param token The API token for authentication.
   */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Plugin instance is a shared service, not meant for defensive copying.")
  public InternalWebServer(AuthWithQqPlugin plugin, int port, String token) {
    this.plugin = plugin;
    this.port = port;
    this.token = token;
  }

  /**
   * Starts the HTTP server.
   */
  public void start() {
    try {
      server = HttpServer.create(new InetSocketAddress(port), 0);
      server.createContext("/api/status", new StatusHandler());
      server.createContext("/api/check", new CheckHandler());
      server.createContext("/api/bind", new BindHandler());
      server.createContext("/api/kick", new KickHandler());
      server.createContext("/api/whitelist", new WhitelistHandler());
      server.createContext("/api/meta", new MetaHandler()); // API for custom fields
      server.createContext("/api/players", new PlayersHandler()); // API for players data
      server.createContext("/api/unbind", new UnbindHandler()); // API for unbinding players
      server.createContext("/api/config", new ConfigHandler()); // API for plugin configuration
      server.createContext("/api/bot/bind", new BotBindHandler()); // New: API for binding fake players
      server.createContext("/api/bot/unbind", new BotUnbindHandler()); // New: API for unbinding fake players
      server.createContext("/api/admin/bind", new AdminBindHandler()); // New: API for admin binding operations
      server.createContext("/api/profile", new ProfileViewHandler()); // New: API for viewing player profile
      server.createContext("/api/profile/update", new ProfileUpdateHandler()); // New: API for updating player profile
      server.createContext("/api/query", new QueryHandler()); // New: API for querying player data
      server.createContext("/api/user/bots", new UserBotsHandler());
      server.createContext("/api/user/bot/bind", new UserBotBindHandler());
      server.createContext("/api/user/bot/unbind", new UserBotUnbindHandler());
      server.createContext("/api/user/bot/update", new UserBotUpdateHandler()); // Bot owner update bot profile
      server.createContext("/api/user/bot/profile", new UserBotProfileHandler()); // Bot owner get bot profile
      server.createContext("/api/bots", new AllBotsHandler()); // New: Get all bots
      server.createContext("/api/csv/export", new CsvExportHandler()); // New: Export CSV
      server.createContext("/api/csv/import", new CsvImportHandler()); // New: Import CSV
      server.createContext("/api/auth/login", new AuthLoginHandler()); // New: Web login
      server.createContext("/api/auth/logout", new AuthLogoutHandler()); // New: Web logout
      server.createContext("/api/auth/verify", new AuthVerifyHandler()); // New: Verify session
      server.createContext("/", new RedirectHandler("/web/index.html")); // Redirect to index
      server.createContext("/dashboard", new RedirectHandler("/web/dashboard.html")); // Explicit dashboard route
      server.createContext("/admin", new AuthenticatedRedirectHandler("/web/admin.html")); // Admin console
      server.createContext("/web", new StaticFileHandler()); // Serve static web resources
      server.setExecutor(null);
      server.start();
      plugin.getLogger().info("Web server started on port " + port);
    } catch (IOException e) {
      plugin.getLogger().log(Level.SEVERE, "Could not start web server", e);
    }
  }

  /**
   * Stops the HTTP server.
   */
  public void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private boolean authenticate(HttpExchange exchange) throws IOException {
    String requestToken = exchange.getRequestHeaders().getFirst("X-API-Token");
    return token.equals(requestToken);
  }

  private boolean authenticateWithResponse(HttpExchange exchange) throws IOException {
    if (authenticate(exchange)) {
      return true;
    }
    sendResponse(exchange, 401, " Unauthorized");
    return false;
  }

  private void sendResponse(HttpExchange exchange, int statusCode, String response)
      throws IOException {
    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  /**
   * Gets player UUID from request using multiple authentication methods.
   * Priority:
   * 1. X-Session-Token header (web login session)
   * 2. token query parameter (profile session token)
   * 3. X-API-Token + uuid query parameter (admin)
   *
   * @param exchange The HTTP exchange.
   * @return The player UUID, or null if not authenticated.
   */
  private UUID getPlayerUuidFromRequest(HttpExchange exchange) throws IOException {
    // 1. Check X-Session-Token header (web login session)
    String sessionToken = exchange.getRequestHeaders().getFirst("X-Session-Token");
    if (sessionToken != null && !sessionToken.isEmpty()) {
      UUID uuid = plugin.validateWebLoginSessionToken(sessionToken);
      if (uuid != null) {
        return uuid;
      }
    }

    // 2. Check token query parameter (profile session token)
    Map<String, String> query = AuthWithQqPlugin.parseQuery(exchange.getRequestURI().getQuery());
    String profileToken = query.get("token");
    if (profileToken != null && !profileToken.isEmpty()) {
      // Use getProfileSessionTokenUuid for viewing (doesn't consume token)
      // validateProfileSessionToken will be used in ProfileUpdateHandler to consume token
      UUID uuid = plugin.getProfileSessionTokenUuid(profileToken);
      if (uuid != null) {
        return uuid;
      }
    }

    // 3. Check admin token + uuid
    if (authenticate(exchange)) {
      String uuidStr = query.get("uuid");
      if (uuidStr != null && !uuidStr.isEmpty()) {
        try {
          return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
          return null;
        }
      }
    }

    return null;
  }

  private class StatusHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      try {
        Future<JsonObject> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
          JsonObject json = new JsonObject();
          json.addProperty("online_players", Bukkit.getOnlinePlayers().size());
          json.addProperty("max_players", Bukkit.getMaxPlayers());
          json.addProperty("tps", Bukkit.getTPS()[0]);

          com.google.gson.JsonArray onlinePlayerNames = new com.google.gson.JsonArray();
          for (Player p : Bukkit.getOnlinePlayers()) {
            onlinePlayerNames.add(p.getName());
          }
          json.add("online_player_names", onlinePlayerNames);

          // Server version information
          json.addProperty("server_version", Bukkit.getVersion());
          json.addProperty("bukkit_version", Bukkit.getBukkitVersion());
          json.addProperty("minecraft_version", Bukkit.getMinecraftVersion());

          // World count
          json.addProperty("world_count", Bukkit.getWorlds().size());

          // Entity statistics
          int totalEntities = 0;
          int playerEntities = 0;
          int livingEntities = 0;
          int itemEntities = 0;
          int otherEntities = 0;

          for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
              totalEntities++;
              if (entity instanceof Player) {
                playerEntities++;
              } else if (entity instanceof org.bukkit.entity.LivingEntity) {
                livingEntities++;
              } else if (entity instanceof org.bukkit.entity.Item) {
                itemEntities++;
              } else {
                otherEntities++;
              }
            }
          }

          json.addProperty("total_entities", totalEntities);
          json.addProperty("player_entities", playerEntities);
          json.addProperty("living_entities", livingEntities);
          json.addProperty("item_entities", itemEntities);
          json.addProperty("other_entities", otherEntities);

          // Today's online statistics
          json.addProperty("today_unique_players", plugin.getTodayUniquePlayers());
          long todayTotalOnlineTime = plugin.getTodayTotalOnlineTime();
          json.addProperty("today_total_online_time_ms", todayTotalOnlineTime);
          
          // Player online times list
          com.google.gson.JsonArray playerTimesArray = new com.google.gson.JsonArray();
          Map<String, Long> playerOnlineTimes = plugin.getTodayPlayerOnlineTimes();
          for (Map.Entry<String, Long> entry : playerOnlineTimes.entrySet()) {
            JsonObject playerTimeObj = new JsonObject();
            playerTimeObj.addProperty("player_name", entry.getKey());
            playerTimeObj.addProperty("online_time_ms", entry.getValue());
            playerTimesArray.add(playerTimeObj);
          }
          json.add("today_player_online_times", playerTimesArray);
          
          // Recent player activities
          com.google.gson.JsonArray activitiesArray = new com.google.gson.JsonArray();
          for (AuthWithQqPlugin.ActivityEntry activity : plugin.getRecentActivities()) {
            JsonObject activityObj = new JsonObject();
            activityObj.addProperty("player_name", activity.playerName);
            activityObj.addProperty("activity_type", activity.activityType);
            activityObj.addProperty("timestamp", activity.timestamp);
            activitiesArray.add(activityObj);
          }
          json.add("recent_activities", activitiesArray);

          return json;
        });
        JsonObject json = future.get(); // This blocks until the main thread runs the code

        // Server uptime
        long uptimeMillis = System.currentTimeMillis() - plugin.getServerStartTime();
        json.addProperty("uptime_millis", uptimeMillis);

        long freeMemory = Runtime.getRuntime().freeMemory() / 1024 / 1024;
        long totalMemory = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        json.addProperty("ram_free", freeMemory);
        json.addProperty("ram_total", totalMemory);

        sendResponse(exchange, 200, gson.toJson(json));
      } catch (InterruptedException | ExecutionException e) {
        plugin.getLogger().log(Level.SEVERE, "Error getting server status", e);
        sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
      }
    }
  }

  private class CheckHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!authenticateWithResponse(exchange)) {
        return;
      }

      Map<String, String> query = AuthWithQqPlugin.parseQuery(exchange.getRequestURI().getQuery());
      String qqStr = query.get("qq");
      if (qqStr == null) {
        sendResponse(exchange, 400, "Missing qq parameter");
        return;
      }

      try {
        long qq = Long.parseLong(qqStr);
        UUID uuid = plugin.getDatabaseManager().findUuidByQq(qq);
        JsonObject json = new JsonObject();
        if (uuid != null) {
          json.addProperty("bound", true);
          json.addProperty("uuid", uuid.toString());
          json.addProperty("name", plugin.getDatabaseManager().getNameByUuid(uuid));
        } else {
          json.addProperty("bound", false);
        }
        sendResponse(exchange, 200, gson.toJson(json));
      } catch (NumberFormatException e) {
        sendResponse(exchange, 400, "Invalid qq parameter");
      }
    }
  }

  private class BindHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "Method not allowed");
        return;
      }

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
        JsonObject body = gson.fromJson(reader, JsonObject.class);
        String playerUuidString = body.get("uuid").getAsString(); // Get actual player UUID
        String numericVerificationCode = body.get("code").getAsString(); // Get numeric verification code
        long qq = body.get("qq").getAsLong();
        
        UUID uuid;
        try {
          uuid = UUID.fromString(playerUuidString); // Parse player UUID correctly
        } catch (IllegalArgumentException e) {
          sendResponse(exchange, 400, "Invalid UUID format");
          return;
        }

        // Validate the numeric verification code using the centralized manager
        if (!plugin.isValidCode(numericVerificationCode, uuid)) {
          sendResponse(exchange, 400, "{\"success\":false, \"error\":\"验证码无效或已过期\"}");
          return;
        }

        // --- NEW: Multi-Account Binding Check ---
        int maxAccountsPerQq = plugin.getConfig().getInt("binding.max-accounts-per-qq", 1);
        long existingQqForUuid = plugin.getDatabaseManager().getQq(uuid);

        if (existingQqForUuid == 0 || existingQqForUuid != qq) { // If not bound or changing QQ
          int currentAccountCount = plugin.getDatabaseManager().getAccountCountByQq(qq);
          if (currentAccountCount >= maxAccountsPerQq) {
            sendResponse(exchange, 400, "{\"success\":false, \"error\":\"此QQ号码已达到绑定上限\"}");
            return;
          }
        }

        // --- NEW: Disallow web bind if QQ already has any account bound ---
        boolean disallowWebBindIfQqExists =
            plugin.getConfig().getBoolean("binding.disallow-web-bind-if-qq-exists", true);
        if (disallowWebBindIfQqExists) {
          int currentAccountCountForQq = plugin.getDatabaseManager().getAccountCountByQq(qq);
          if (currentAccountCountForQq > 0 && existingQqForUuid != qq) {
            sendResponse(exchange, 400,
                "{\"success\":false, \"error\":\"该QQ已绑定过账号，请在QQ群内使用机器人指令进行绑定/换绑\"}");
            return;
          }
        }
        // --- END NEW LOGIC ---

        // --- NEW: Force group binding check (QQ must be in allowed groups) ---
        boolean forceGroupBinding =
            plugin.getConfig().getBoolean("binding.force-group-binding", false);
        if (forceGroupBinding) {
          // Determine which groups to check
          java.util.List<Long> groups =
              plugin.getConfig().getLongList("binding.group-binding-groups");
          if (groups == null || groups.isEmpty()) {
            groups = plugin.getConfig().getLongList("onebot.allowed-groups");
          }

          if (groups == null || groups.isEmpty()) {
            plugin.getLogger().warning(
                "[OneBot-WS] force-group-binding is enabled but no groups configured; skipping group check");
          } else {
            boolean inGroups = plugin.getDatabaseManager().isQqInGroups(qq, groups);
            if (!inGroups) {
              sendResponse(exchange, 400,
                  "{\"success\":false, \"error\":\"该QQ尚未在指定QQ群中，无法通过网页完成绑定，请先加入QQ群并在群内与机器人交互\"}");
              return;
            }
          }
        }
        // --- END NEW LOGIC ---

        plugin.getDatabaseManager().updateBinding(uuid, qq);
        plugin.invalidateCode(uuid); // Invalidate code after successful bind
        
        if (body.has("meta") && body.get("meta").isJsonObject()) {
          JsonObject meta = body.getAsJsonObject("meta");
          for (Map.Entry<String, com.google.gson.JsonElement> entry : meta.entrySet()) {
            plugin.getDatabaseManager().setMeta(uuid, entry.getKey(),
                entry.getValue().getAsString());
          }
        }

        // Notify plugin about binding status change
        Bukkit.getScheduler().runTask(plugin, () -> plugin.handleBindingChange(uuid, qq));
        
        sendResponse(exchange, 200, "{\"success\":true}");
      } catch (Exception e) {
        plugin.getLogger().log(Level.SEVERE, "Error during bind operation", e);
        sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
      }
    }
  }

  private class KickHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!authenticateWithResponse(exchange)) {
        return;
      }
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "Method not allowed");
        return;
      }

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
        JsonObject body = gson.fromJson(reader, JsonObject.class);
        String playerName = body.get("player").getAsString();
        String reason = body.has("reason") ? body.get("reason").getAsString() : "Kicked by admin";

        Bukkit.getScheduler().runTask(plugin, () -> {
          Player player = Bukkit.getPlayer(playerName);
          if (player != null) {
            player.kick(net.kyori.adventure.text.Component.text(reason));
          }
        });
        sendResponse(exchange, 200, "{\"success\":true}");
      } catch (Exception e) {
        plugin.getLogger().log(Level.SEVERE, "Error during kick operation", e);
        sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
      }
    }
  }

  private class WhitelistHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!authenticateWithResponse(exchange)) {
        return;
      }
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "Method not allowed");
        return;
      }

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
        JsonObject body = gson.fromJson(reader, JsonObject.class);
        String playerName = body.get("player").getAsString();
        boolean isAdd = body.get("add").getAsBoolean();

        // Update plugin's whitelist configuration (not Bukkit's native whitelist)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
          List<String> whitelistedPlayers = new java.util.ArrayList<>(
              plugin.getConfig().getStringList("whitelist.players"));
          boolean changed = false;

          if (isAdd) {
            if (!whitelistedPlayers.contains(playerName)) {
              whitelistedPlayers.add(playerName);
              changed = true;
            }
          } else {
            if (whitelistedPlayers.remove(playerName)) {
              changed = true;
            }
          }

          if (changed) {
            plugin.getConfig().set("whitelist.players", whitelistedPlayers);
            plugin.saveConfig();
            plugin.reloadConfig(); // Reload config to ensure in-memory list is updated

            // Apply changes immediately to online players
            Bukkit.getScheduler().runTask(plugin, () -> {
              Player onlinePlayer = Bukkit.getPlayer(playerName);
              if (onlinePlayer != null) {
                if (isAdd) {
                  // Player added to whitelist: remove guest restrictions immediately
                  plugin.getGuestListener().unmarkGuest(onlinePlayer.getUniqueId());
                } else {
                  // Player removed from whitelist: check if they should be marked as guest
                  long qq = plugin.getDatabaseManager().getQq(onlinePlayer.getUniqueId());
                  if (qq == 0) {
                    // Player is not bound, mark as guest
                    plugin.getGuestListener().markGuest(onlinePlayer);
                  }
                }
              }
            });
          }
        });

        sendResponse(exchange, 200, "{\"success\":true}");
      } catch (Exception e) {
        plugin.getLogger().log(Level.SEVERE, "Error during whitelist operation", e);
        sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
      }
    }
  }
  
  private class ConfigHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!authenticateWithResponse(exchange)) {
        return;
      }

      // Create a copy of the config and remove sensitive information
      // ConfigurationSection is a map-like structure, so we can convert it to a Map<String, Object>
      Map<String, Object> configMap = plugin.getConfig().getValues(true);
      JsonObject jsonConfig = new JsonObject();
      for (Map.Entry<String, Object> entry : configMap.entrySet()) {
        if (entry.getKey().equals("server.token")) {
          jsonConfig.addProperty(entry.getKey(), "********"); // Mask the token
        } else if (entry.getValue() instanceof String) {
          jsonConfig.addProperty(entry.getKey(), (String) entry.getValue());
        } else if (entry.getValue() instanceof Number) {
          jsonConfig.addProperty(entry.getKey(), (Number) entry.getValue());
        } else if (entry.getValue() instanceof Boolean) {
          jsonConfig.addProperty(entry.getKey(), (Boolean) entry.getValue());
        } else {
          // Attempt to convert other types to string or use gson to serialize
          jsonConfig.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
        }
      }
      sendResponse(exchange, 200, gson.toJson(jsonConfig));
    }
  }

  private class RedirectHandler implements HttpHandler {
    private final String targetPath;

    public RedirectHandler(String targetPath) {
      this.targetPath = targetPath;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      exchange.getResponseHeaders().set("Location", targetPath);
      exchange.sendResponseHeaders(302, -1); // 302 Found (temporary redirect)
    }
  }

  private class AuthenticatedRedirectHandler implements HttpHandler {
    private final String targetPath;

    public AuthenticatedRedirectHandler(String targetPath) {
      this.targetPath = targetPath;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!authenticateWithResponse(exchange)) {
        return;
      }
      exchange.getResponseHeaders().set("Location", targetPath);
      exchange.sendResponseHeaders(302, -1); // 302 Found (temporary redirect)
    }
  }

  private class MetaHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      // Get type parameter from query string (player, bot, or null for all)
      Map<String, String> query = AuthWithQqPlugin.parseQuery(exchange.getRequestURI().getQuery());
      String type = query != null ? query.get("type") : null; // "player" or "bot"
      
      List<Map<?, ?>> customFields = new java.util.ArrayList<>();
      
      // Reload config from file to get latest changes (avoid cached default values)
      plugin.reloadConfig();
      org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();
      
      // Try new format (classified fields) first
      if ("player".equals(type)) {
        // Check if the configuration path actually exists in the file (not just default value)
        // Use contains() with exact=true to check if the path is explicitly set
        String path = "binding.custom-fields.player";
        if (config.contains(path, true)) {
          // Path exists in config file, read the list
          List<Map<?, ?>> playerFields = config.getMapList(path);
          if (playerFields != null && !playerFields.isEmpty()) {
            customFields = playerFields;
          }
        }
        // If path doesn't exist (commented out or removed), customFields remains empty
      } else if ("bot".equals(type)) {
        // Check if the configuration path actually exists in the file
        String path = "binding.custom-fields.bot";
        if (config.contains(path, true)) {
          // Path exists in config file, read the list
          List<Map<?, ?>> botFields = config.getMapList(path);
          if (botFields != null && !botFields.isEmpty()) {
            customFields = botFields;
          }
        }
        // If path doesn't exist (commented out or removed), customFields remains empty
      } else {
        // No type specified or type is invalid, return empty array
        // Frontend should specify type explicitly
        customFields = new java.util.ArrayList<>();
      }
      
      // Fallback to old format if new format returned empty and no type was specified
      if (customFields.isEmpty() && type == null) {
        String oldPath = "binding.custom-fields";
        if (config.contains(oldPath, true) && config.isList(oldPath)) {
          List<Map<?, ?>> oldFields = config.getMapList(oldPath);
          if (oldFields != null && !oldFields.isEmpty()) {
            customFields = oldFields;
          }
        }
      }
      
      com.google.gson.JsonArray jsonArray = new com.google.gson.JsonArray();
      for (Map<?, ?> field : customFields) {
        JsonObject fieldObject = new JsonObject();
        field.forEach((key, value) -> {
          if (value instanceof String) {
            fieldObject.addProperty(key.toString(), (String) value);
          } else if (value instanceof Boolean) {
            fieldObject.addProperty(key.toString(), (Boolean) value);
          } else if (value instanceof Number) {
            fieldObject.addProperty(key.toString(), (Number) value);
          }
        });
        jsonArray.add(fieldObject);
      }
      sendResponse(exchange, 200, jsonArray.toString());
    }
  }

  private class PlayersHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!authenticateWithResponse(exchange)) {
        return;
      }

      List<Map<String, String>> allPlayersData = plugin.getDatabaseManager().getAllPlayersData();
      com.google.gson.JsonArray jsonArray = new com.google.gson.JsonArray();
      for (Map<String, String> playerData : allPlayersData) {
        JsonObject playerObject = new JsonObject();
        playerData.forEach(playerObject::addProperty);
        jsonArray.add(playerObject);
      }
      sendResponse(exchange, 200, jsonArray.toString());
    }
  }

  private class UnbindHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!authenticateWithResponse(exchange)) {
        return;
      }
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "Method not allowed");
        return;
      }

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
        JsonObject body = gson.fromJson(reader, JsonObject.class);
        String uuidString = body.get("uuid").getAsString();
        
        UUID uuid;
        try {
          uuid = UUID.fromString(uuidString);
        } catch (IllegalArgumentException e) {
          sendResponse(exchange, 400, "Invalid UUID format");
          return;
        }

        // Database modification (set QQ to 0)
        plugin.getDatabaseManager().updateBinding(uuid, 0L);
        // Delete all bots owned by this player
        plugin.getDatabaseManager().deletePlayer(uuid);


        // In-game synchronization
        Bukkit.getScheduler().runTask(plugin, () -> {
          Player player = Bukkit.getPlayer(uuid);
          if (player != null) {
            // Further actions like kicking the player, sending a message, etc.
            player.sendMessage("You have been unbound from QQ."); // Example
            plugin.handleBindingChange(uuid, 0L); // Update player's guest status
          }
        });
        
        sendResponse(exchange, 200, "{\"success\":true}");
      } catch (Exception e) {
        plugin.getLogger().log(Level.SEVERE, "Error during unbind operation", e);
        sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
      }
    }
  }

  // New: Handles binding a bot to an owner via API (QQ bot or similar)
  private class BotBindHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!authenticateWithResponse(exchange)) {
            return;
        }
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "Method not allowed");
        return;
      }

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
        JsonObject body = gson.fromJson(reader, JsonObject.class);
        // Accepts owner_uuid, owner_name, or owner_qq as identifier
        String ownerIdentifier = null;
        if (body.has("owner_uuid")) {
            ownerIdentifier = body.get("owner_uuid").getAsString();
        } else if (body.has("owner_name")) {
            ownerIdentifier = body.get("owner_name").getAsString();
        } else if (body.has("owner_qq")) {
            ownerIdentifier = body.get("owner_qq").getAsString();
        }
        
        String botName = body.has("bot_name") ? body.get("bot_name").getAsString() : null;

        if (ownerIdentifier == null || botName == null || botName.isEmpty()) {
          sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Missing owner identifier or bot_name\"}");
          return;
        }

        final String finalOwnerIdentifier = ownerIdentifier;
        // Resolve owner_uuid from identifier on the main Bukkit thread
        Future<UUID> futureOwnerUuid = Bukkit.getScheduler().callSyncMethod(plugin, () -> 
            plugin.getDatabaseManager().findUuidByNameOrQq(finalOwnerIdentifier)
        );
        UUID ownerUuid = futureOwnerUuid.get();

        if (ownerUuid == null) {
          sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Owner not found from identifier\"}");
          return;
        }
        
        // Ensure owner is bound to a QQ
        long ownerQq = plugin.getDatabaseManager().getQq(ownerUuid);
        if (ownerQq == 0) {
          sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Owner is not bound to a QQ\"}");
          return;
        }

        // Check bot limit
        int maxBotsPerPlayer = plugin.getConfig().getInt("binding.max-bots-per-player", 0);
        int currentBotCount = plugin.getDatabaseManager().getBotCountForOwner(ownerUuid);
        if (maxBotsPerPlayer == 0) {
          // 0 means bot adding is disabled
          sendResponse(exchange, 400, "{\"success\":false, \"error\":\"假人添加功能已禁用\"}");
          return;
        } else if (maxBotsPerPlayer > 0 && currentBotCount >= maxBotsPerPlayer) {
          // Positive number means limit check
          sendResponse(exchange, 400, "{\"success\":false, \"error\":\"达到假人绑定上限\"}");
          return;
        }
        // Negative number means unlimited, allow adding

        // Validate bot name prefix
        if (!plugin.validateBotName(botName)) {
          String prefix = plugin.getConfig().getString("binding.bot-name-prefix", "");
          sendResponse(exchange, 400, "{\"success\":false, \"error\":\"假人名称必须以 \\\"" + prefix + "\\\" 开头\"}");
          return;
        }

        // Generate a UUID for the bot (deterministic based on name for consistency)
        UUID botUuid = UUID.nameUUIDFromBytes(("Bot-" + botName).getBytes(StandardCharsets.UTF_8));

        // Ensure botName is not already bound to another owner
        // (This check is not explicitly requested but is good practice to prevent bot name conflicts)
        // ... potentially add logic here to check if botUuid is already owned by someone else

        plugin.getDatabaseManager().markPlayerAsBot(botUuid, ownerUuid, botName);
        
        sendResponse(exchange, 200, "{\"success\":true, \"message\":\"Bot " + botName + " bound to owner " + ownerUuid + "\"}");
      } catch (Exception e) {
        plugin.getLogger().log(Level.SEVERE, "Error during bot bind operation", e);
        sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
      }
    }
  }

  private class StaticFileHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      // Remove the /web prefix and ensure it's a relative path within 'web' folder
      if (path.startsWith("/web")) {
        path = path.substring("/web".length());
      }
      // Sanitize path to prevent directory traversal
      path = path.replace("..", "").replace("//", "/");

      // Construct the resource path (e.g., "web/auth.html")
      String resourcePath = "web" + path;
      if (resourcePath.endsWith("/") || resourcePath.equals("web")) {
        resourcePath += "index.html";
      }

      byte[] responseBytes = null;
      // 1. Prefer file in plugin directory (allows server owner customization)
      java.io.File webDir = plugin.getWebResourcesDir();
      String fileRelativePath = resourcePath.startsWith("web/")
          ? resourcePath.substring("web/".length())
          : resourcePath;
      fileRelativePath = fileRelativePath.replaceFirst("^/+", ""); // Strip leading slashes
      java.io.File file = new java.io.File(webDir, fileRelativePath);
      // Ensure resolved path stays within webDir (prevent path traversal)
      try {
        String base = webDir.getCanonicalPath() + java.io.File.separator;
        if (!file.getCanonicalPath().startsWith(base) && !file.getCanonicalPath().equals(webDir.getCanonicalPath())) {
          file = null;
        }
      } catch (java.io.IOException e) {
        file = null;
      }
      if (file != null && file.exists() && file.isFile()) {
        try {
          responseBytes = java.nio.file.Files.readAllBytes(file.toPath());
        } catch (IOException e) {
          plugin.getLogger().warning("Could not read web file: " + file.getPath() + " - " + e.getMessage());
        }
      }
      // 2. Fallback to JAR resource
      if (responseBytes == null) {
        try (java.io.InputStream is = plugin.getResource(resourcePath)) {
          if (is != null) {
            responseBytes = is.readAllBytes();
          }
        }
      }

      if (responseBytes == null) {
        sendResponse(exchange, 404, "404 Not Found");
        return;
      }

      // Determine Content-Type based on file extension
      String contentType = "text/plain";
      if (resourcePath.endsWith(".html")) {
        contentType = "text/html";
      } else if (resourcePath.endsWith(".css")) {
        contentType = "text/css";
      } else if (resourcePath.endsWith(".js")) {
        contentType = "application/javascript";
      } else if (resourcePath.endsWith(".json")) {
        contentType = "application/json";
      }

      try {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(200, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(responseBytes);
        }
      } catch (Exception e) {
        plugin.getLogger().log(Level.SEVERE, "Error serving static file: " + resourcePath, e);
        sendResponse(exchange, 500, "500 Internal Server Error");
      }
    }
  }

  private class AdminBindHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!authenticateWithResponse(exchange)) {
        return;
      }
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "Method not allowed");
        return;
      }

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
        JsonObject body = gson.fromJson(reader, JsonObject.class);
        String playerIdentifier = body.get("playerIdentifier").getAsString(); // Can be name or UUID
        long qq = body.get("qq").getAsLong();

        // Resolve playerIdentifier to UUID on the main Bukkit thread
        Future<UUID> futureUuid = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
          UUID uuid = null;
          try {
            // Try parsing as UUID first
            uuid = UUID.fromString(playerIdentifier);
          } catch (IllegalArgumentException e) {
            // If not a UUID, try getting player by name
            Player player = Bukkit.getPlayer(playerIdentifier);
            if (player != null) {
              uuid = player.getUniqueId();
            } else {
              // Try getting offline player by name
              // Note: getOfflinePlayer can be slow and might not return UUID if player never joined
              org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerIdentifier);
              if (offlinePlayer.hasPlayedBefore() || offlinePlayer.isOnline()) {
                uuid = offlinePlayer.getUniqueId();
              }
            }
          }
          return uuid;
        });

        UUID playerUuid = futureUuid.get(); // Blocks until UUID is resolved

        if (playerUuid == null) {
          sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Player not found or invalid identifier\"}");
          return;
        }

        // Update binding
        plugin.getDatabaseManager().updateBinding(playerUuid, qq);
        
        // Notify plugin about binding status change (e.g., clear guest status if online)
        Bukkit.getScheduler().runTask(plugin, () -> plugin.handleBindingChange(playerUuid, qq));

        sendResponse(exchange, 200, "{\"success\":true, \"message\":\"Binding updated successfully\"}");
      } catch (Exception e) {
        plugin.getLogger().log(Level.SEVERE, "Error during admin bind operation", e);
        sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
      }
    }
  }

  // New: Handles viewing player profile
  private class ProfileViewHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "Method not allowed");
        return;
      }

      Map<String, String> query = AuthWithQqPlugin.parseQuery(exchange.getRequestURI().getQuery());
      String playerUuidString = query.get("uuid"); // Player UUID for admin access

      UUID uuid = null; // Final UUID to fetch profile for

      // Try to get UUID from request (supports X-Session-Token, token query, or admin)
      uuid = getPlayerUuidFromRequest(exchange);
      
      if (uuid == null && playerUuidString != null && !playerUuidString.isEmpty()) {
        // Admin access using X-API-Token and player UUID
        if (!authenticateWithResponse(exchange)) { // Authenticate admin
          return;
        }
        try {
          uuid = UUID.fromString(playerUuidString);
        } catch (IllegalArgumentException e) {
          sendResponse(exchange, 400, "Invalid player UUID format");
          return;
        }
      }
      
      if (uuid == null) {
        sendResponse(exchange, 401, "{\"error\":\"Unauthorized. Please provide X-Session-Token header, token query parameter, or admin credentials.\"}");
        return;
      }

      // At this point, 'uuid' should be valid whether from token or direct UUID
      final UUID finalUuid = uuid; // For use in lambda

      try {
        Future<JsonObject> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
          JsonObject json = new JsonObject();
          
          // Check if this is a bot
          boolean isBot = plugin.getDatabaseManager().isBot(finalUuid);
          
          if (isBot) {
            // For bots, get data from auth_bots table
            UUID ownerUuid = plugin.getDatabaseManager().getBotOwner(finalUuid);
            String botName = plugin.getDatabaseManager().getNameByUuid(finalUuid); // This will get bot_name for bots
            Map<String, String> meta = plugin.getDatabaseManager().getAllMeta(finalUuid);
            
            json.addProperty("uuid", finalUuid.toString());
            json.addProperty("name", botName != null ? botName : "Unknown Bot");
            json.addProperty("qq", 0); // Bots don't have QQ
            
            // Add bot-specific meta
            JsonObject metaJson = new JsonObject();
            meta.forEach(metaJson::addProperty);
            metaJson.addProperty("bot.is_bot", "true");
            if (ownerUuid != null) {
              metaJson.addProperty("bot.owner_uuid", ownerUuid.toString());
              // Get owner name and QQ
              String ownerName = plugin.getDatabaseManager().getNameByUuid(ownerUuid);
              long ownerQq = plugin.getDatabaseManager().getQq(ownerUuid);
              if (ownerName != null) {
                metaJson.addProperty("bot.owner_name", ownerName);
              }
              if (ownerQq != 0) {
                metaJson.addProperty("bot.owner_qq", String.valueOf(ownerQq));
              }
            }
            json.add("meta", metaJson);
          } else {
            // For real players, get data from auth_players table
            long qq = plugin.getDatabaseManager().getQq(finalUuid);
            String name = plugin.getDatabaseManager().getNameByUuid(finalUuid);
            Map<String, String> meta = plugin.getDatabaseManager().getAllMeta(finalUuid);
            
            json.addProperty("uuid", finalUuid.toString());
            json.addProperty("name", name != null ? name : "Unknown Player");
            json.addProperty("qq", qq);

            JsonObject metaJson = new JsonObject();
            meta.forEach(metaJson::addProperty);
            json.add("meta", metaJson);
          }
          
          return json;
        });
        JsonObject profileData = future.get();
        sendResponse(exchange, 200, gson.toJson(profileData));
      } catch (InterruptedException | ExecutionException e) {
        plugin.getLogger().log(Level.SEVERE, "Error retrieving profile data", e);
        sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
      }
    }
  }

  // New: Handles updating player profile
  private class ProfileUpdateHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "Method not allowed");
        return;
      }

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
        JsonObject body = gson.fromJson(reader, JsonObject.class);
        String playerUuidStringFromBody = body.has("uuid") ? body.get("uuid").getAsString() : null; // UUID for admin context
        String tokenFromBody = body.has("token") ? body.get("token").getAsString() : null; // Profile session token from body

        UUID uuidToUpdate = null; // Final UUID to update profile for

        // First, try to get UUID from request headers/query (supports X-Session-Token, token query, or admin)
        uuidToUpdate = getPlayerUuidFromRequest(exchange);

        // If not found, try profile session token from body
        if (uuidToUpdate == null && tokenFromBody != null && !tokenFromBody.isEmpty()) {
          uuidToUpdate = plugin.validateProfileSessionToken(tokenFromBody);
        }

        // If still not found, try admin authentication with UUID from body
        if (uuidToUpdate == null) {
          boolean isAdminAuthenticated = authenticate(exchange); // This checks X-API-Token header
          if (isAdminAuthenticated) {
            // Admin update: UUID comes from body (admin_edit_player.js sends it)
            if (playerUuidStringFromBody == null || playerUuidStringFromBody.isEmpty()) {
              sendResponse(exchange, 400, "Missing player UUID in request body for admin update");
              return;
            }
            try {
              uuidToUpdate = UUID.fromString(playerUuidStringFromBody);
            } catch (IllegalArgumentException e) {
              sendResponse(exchange, 400, "Invalid player UUID format in request body for admin update");
              return;
            }
          } else {
            sendResponse(exchange, 401, "{\"error\":\"Unauthorized. Please provide X-Session-Token header, token query parameter, token in request body, or admin credentials.\"}");
            return;
          }
        }

        final UUID finalUuid = uuidToUpdate; // For use in lambda
        long newQq = body.has("qq") ? body.get("qq").getAsLong() : 0;
        JsonObject meta = body.has("meta") ? body.getAsJsonObject("meta") : new JsonObject();

        Future<Boolean> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
          // Check if this is a bot
          boolean isBot = plugin.getDatabaseManager().isBot(finalUuid);
          
          if (isBot) {
            // For bots, update bot-specific fields
            if (meta.has("bot.owner_uuid")) {
              String ownerUuidStr = meta.get("bot.owner_uuid").getAsString();
              UUID ownerUuid = null;
              if (ownerUuidStr != null && !ownerUuidStr.isEmpty()) {
                try {
                  ownerUuid = UUID.fromString(ownerUuidStr);
                } catch (IllegalArgumentException e) {
                  plugin.getLogger().warning("Invalid owner UUID format: " + ownerUuidStr);
                }
              }
              // Get bot name to update
              String botName = plugin.getDatabaseManager().getNameByUuid(finalUuid);
              if (botName != null) {
                plugin.getDatabaseManager().markPlayerAsBot(finalUuid, ownerUuid, botName);
              }
            }
            
            // Update bot name if provided
            if (meta.has("bot.bot_name")) {
              String botName = meta.get("bot.bot_name").getAsString();
              UUID ownerUuid = plugin.getDatabaseManager().getBotOwner(finalUuid);
              if (botName != null && !botName.isEmpty()) {
                plugin.getDatabaseManager().markPlayerAsBot(finalUuid, ownerUuid, botName);
              }
            }
            
            // Don't update QQ for bots (they don't have QQ)
            // Don't call handleBindingChange for bots
          } else {
            // For real players, update QQ binding
            if (newQq != 0) {
              plugin.getDatabaseManager().updateBinding(finalUuid, newQq);
              plugin.handleBindingChange(finalUuid, newQq); // Update guest status if online
            }
          }

          // Update custom meta fields (excluding bot-specific fields that are handled above)
          for (Map.Entry<String, com.google.gson.JsonElement> entry : meta.entrySet()) {
            String key = entry.getKey();
            // Skip bot-specific fields that are handled separately
            if (key.equals("bot.owner_uuid") || key.equals("bot.bot_name")) {
              continue;
            }
            // Remove meta field if value is null or empty string
            if (entry.getValue().isJsonNull() || (entry.getValue().isJsonPrimitive() && entry.getValue().getAsString().isEmpty())) {
                plugin.getDatabaseManager().deleteMeta(finalUuid, key);
            } else {
                plugin.getDatabaseManager().setMeta(finalUuid, key, entry.getValue().getAsString());
            }
          }
          return true;
        });
        future.get(); // Wait for completion

        sendResponse(exchange, 200, "{\"success\":true, \"message\":\"Profile updated successfully\"}");
      } catch (Exception e) {
        plugin.getLogger().log(Level.SEVERE, "Error updating profile", e);
        sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
      }
    }
  }

  // New: Handles unbinding a bot from an owner via API
  private class BotUnbindHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!authenticateWithResponse(exchange)) {
            return;
        }
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "Method not allowed");
        return;
      }

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
        JsonObject body = gson.fromJson(reader, JsonObject.class);
        String botIdentifier = body.has("bot_name") ? body.get("bot_name").getAsString() : null;

        if (botIdentifier == null || botIdentifier.isEmpty()) {
          sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Missing bot_name\"}");
          return;
        }
        
        Future<UUID> futureBotUuid = Bukkit.getScheduler().callSyncMethod(plugin, () -> 
            plugin.getDatabaseManager().getBotUuidByName(botIdentifier)
        );
        UUID botUuid = futureBotUuid.get();

        if (botUuid == null) {
            sendResponse(exchange, 404, "{\"success\":false, \"error\":\"Bot not found\"}");
            return;
        }

        // Delete the bot
        plugin.getDatabaseManager().deleteBot(botUuid);
        
        sendResponse(exchange, 200, "{\"success\":true, \"message\":\"Bot " + botIdentifier + " unbound successfully\"}");
            } catch (Exception e) {
              plugin.getLogger().log(Level.SEVERE, "Error during bot unbind operation", e);
              sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
            }
          }
        }
      
        private class QueryHandler implements HttpHandler {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
              if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                  sendResponse(exchange, 405, "{\"success\":false, \"error\":\"Method not allowed\"}");
                  return;
              }
              if (!authenticateWithResponse(exchange)) {
                  return; // Response already sent
              }
      
              Map<String, String> params = AuthWithQqPlugin.parseQuery(exchange.getRequestURI().getQuery());
              String keyword = params.get("keyword");
              String by = params.get("by");
              String target = params.get("target");
      
              if (keyword == null || by == null || target == null) {
                  sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Missing 'keyword', 'by', or 'target' parameters\"}");
                  return;
              }
      
              try {
                  // Step 1: Resolve Owner UUID
                  UUID ownerUuid = resolveOwnerUuid(by, keyword);
      
                  if (ownerUuid == null) {
                      sendResponse(exchange, 404, "{\"success\":false, \"error\":\"Owner could not be resolved from keyword\"}");
                      return;
                  }
      
                  // Step 2: Assemble Data
                  JsonObject responseData = assembleData(ownerUuid, target);
      
                  sendResponse(exchange, 200, gson.toJson(responseData));
      
              } catch (Exception e) {
                  plugin.getLogger().log(Level.SEVERE, "Error during query operation", e);
                  sendResponse(exchange, 500, "{\"success\":false, \"error\":\"Internal server error: " + e.getMessage() + "\"}");
              }
          }
      
          private UUID resolveOwnerUuid(String by, String keyword) throws ExecutionException, InterruptedException {
              Future<UUID> future;
              switch (by) {
                  case "uuid":
                      return UUID.fromString(keyword);
                  case "name":
                      future = Bukkit.getScheduler().callSyncMethod(plugin, () -> plugin.getDatabaseManager().getPlayerUuid(keyword));
                      return future.get();
                  case "qq":
                      try {
                          long qq = Long.parseLong(keyword);
                          future = Bukkit.getScheduler().callSyncMethod(plugin, () -> plugin.getDatabaseManager().findUuidByQq(qq));
                          return future.get();
                      } catch (NumberFormatException e) {
                          return null;
                      }
                  case "bot_name":
                      future = Bukkit.getScheduler().callSyncMethod(plugin, () -> plugin.getDatabaseManager().getOwnerByBotName(keyword));
                      return future.get();
                  case "bot_uuid":
                      try {
                          UUID botUuid = UUID.fromString(keyword);
                          future = Bukkit.getScheduler().callSyncMethod(plugin, () -> plugin.getDatabaseManager().getOwnerByBotUuid(botUuid));
                          return future.get();
                      } catch (IllegalArgumentException e) {
                          return null;
                      }
                  default:
                      return null;
              }
          }
      
          private JsonObject assembleData(UUID ownerUuid, String target) throws ExecutionException, InterruptedException {
              JsonObject result = new JsonObject();
              boolean fetchAll = "all".equals(target);
      
              // Player Data
              if (fetchAll || "player".equals(target)) {
                Future<JsonObject> playerFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    JsonObject playerData = new JsonObject();
                    String name = plugin.getDatabaseManager().getNameByUuid(ownerUuid);
                    long qq = plugin.getDatabaseManager().getQq(ownerUuid);
                    playerData.addProperty("uuid", ownerUuid.toString());
                    playerData.addProperty("name", name);
                    playerData.addProperty("qq", qq);
                    return playerData;
                });
                result.add("player", playerFuture.get());
              }
      
              // Bots Data
              if (fetchAll || "bots".equals(target)) {
                Future<JsonArray> botsFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    JsonArray botsArray = new JsonArray();
                    List<Map<String, String>> bots = plugin.getDatabaseManager().getBotsByOwner(ownerUuid);
                    for (Map<String, String> bot : bots) {
                        JsonObject botObj = new JsonObject();
                        botObj.addProperty("bot_uuid", bot.get("bot_uuid"));
                        botObj.addProperty("bot_name", bot.get("bot_name"));
                        botObj.addProperty("created_at", bot.get("created_at"));
                        botsArray.add(botObj);
                    }
                    return botsArray;
                });
                result.add("bots", botsFuture.get());
              }
      
              // Meta Data
              if (fetchAll || "meta".equals(target)) {
                Future<JsonObject> metaFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    JsonObject metaJson = new JsonObject();
                    Map<String, String> meta = plugin.getDatabaseManager().getAllMeta(ownerUuid);
                    meta.forEach(metaJson::addProperty);
                    return metaJson;
                });
                result.add("meta", metaFuture.get());
              }
              return result;
          }
      }

      private class UserBotsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            UUID ownerUuid = getPlayerUuidFromRequest(exchange);
            if (ownerUuid == null) {
                sendResponse(exchange, 401, "{\"success\":false, \"error\":\"Unauthorized. Please provide X-Session-Token header or token query parameter.\"}");
                return;
            }

            List<Map<String, String>> bots = plugin.getDatabaseManager().getBotsByOwner(ownerUuid);
            int maxBots = plugin.getConfig().getInt("binding.max-bots-per-player", 0);
            
            JsonObject response = new JsonObject();
            com.google.gson.JsonArray botsArray = new com.google.gson.JsonArray();
            for (Map<String, String> bot : bots) {
                JsonObject botObj = new JsonObject();
                for (Map.Entry<String, String> entry : bot.entrySet()) {
                    botObj.addProperty(entry.getKey(), entry.getValue());
                }
                botsArray.add(botObj);
            }
            response.add("bots", botsArray);
            response.addProperty("current_count", bots.size());
            response.addProperty("max_limit", maxBots);
            // limit_enabled: true if maxBots > 0 (has limit), false if maxBots == 0 (disabled) or maxBots < 0 (unlimited)
            response.addProperty("limit_enabled", maxBots > 0);
            response.addProperty("unlimited", maxBots < 0);
            
            sendResponse(exchange, 200, gson.toJson(response));
        }
    }

    private class UserBotBindHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            UUID ownerUuid = getPlayerUuidFromRequest(exchange);
            if (ownerUuid == null) {
                sendResponse(exchange, 401, "{\"success\":false, \"error\":\"Unauthorized. Please provide X-Session-Token header or token query parameter.\"}");
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                JsonObject body = gson.fromJson(reader, JsonObject.class);
                String botName = body.has("bot_name") ? body.get("bot_name").getAsString() : null;

                if (botName == null || botName.isEmpty()) {
                    sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Missing bot_name\"}");
                    return;
                }

                int maxBots = plugin.getConfig().getInt("binding.max-bots-per-player", 0);
                int currentBotCount = plugin.getDatabaseManager().getBotCountForOwner(ownerUuid);
                if (maxBots == 0) {
                    // 0 means bot adding is disabled
                    JsonObject errorResponse = new JsonObject();
                    errorResponse.addProperty("success", false);
                    errorResponse.addProperty("error", "Bot adding is disabled");
                    errorResponse.addProperty("current_count", currentBotCount);
                    errorResponse.addProperty("max_limit", 0);
                    sendResponse(exchange, 400, gson.toJson(errorResponse));
                    return;
                } else if (maxBots > 0 && currentBotCount >= maxBots) {
                    // Positive number means limit check
                    JsonObject errorResponse = new JsonObject();
                    errorResponse.addProperty("success", false);
                    errorResponse.addProperty("error", "Bot limit reached");
                    errorResponse.addProperty("current_count", currentBotCount);
                    errorResponse.addProperty("max_limit", maxBots);
                    sendResponse(exchange, 400, gson.toJson(errorResponse));
                    return;
                }
                // Negative number means unlimited, allow adding

                // Validate bot name prefix
                if (!plugin.validateBotName(botName)) {
                    String prefix = plugin.getConfig().getString("binding.bot-name-prefix", "");
                    JsonObject errorResponse = new JsonObject();
                    errorResponse.addProperty("success", false);
                    errorResponse.addProperty("error", "Bot name must start with \"" + prefix + "\"");
                    sendResponse(exchange, 400, gson.toJson(errorResponse));
                    return;
                }

                UUID botUuid = UUID.nameUUIDFromBytes(("Bot-" + botName).getBytes(StandardCharsets.UTF_8));
                
                plugin.getDatabaseManager().markPlayerAsBot(botUuid, ownerUuid, botName);

                JsonObject successResponse = new JsonObject();
                successResponse.addProperty("success", true);
                successResponse.addProperty("message", "Bot bound successfully");
                successResponse.addProperty("current_count", currentBotCount + 1);
                successResponse.addProperty("max_limit", maxBots);
                sendResponse(exchange, 200, gson.toJson(successResponse));

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in UserBotBindHandler", e);
                sendResponse(exchange, 500, "{\"success\":false, \"error\":\"Internal server error\"}");
            }
        }
    }

    private class UserBotUnbindHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            UUID ownerUuid = getPlayerUuidFromRequest(exchange);
            if (ownerUuid == null) {
                sendResponse(exchange, 401, "{\"success\":false, \"error\":\"Unauthorized. Please provide X-Session-Token header or token query parameter.\"}");
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                JsonObject body = gson.fromJson(reader, JsonObject.class);
                String botUuidStr = body.has("bot_uuid") ? body.get("bot_uuid").getAsString() : null;

                if (botUuidStr == null) {
                    sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Missing bot_uuid\"}");
                    return;
                }
                
                UUID botUuid = UUID.fromString(botUuidStr);
                UUID actualOwner = plugin.getDatabaseManager().getBotOwner(botUuid);

                if (actualOwner == null || !actualOwner.equals(ownerUuid)) {
                    sendResponse(exchange, 403, "{\"success\":false, \"error\":\"You do not own this bot\"}");
                    return;
                }

                plugin.getDatabaseManager().deleteBot(botUuid);
                sendResponse(exchange, 200, "{\"success\":true, \"message\":\"Bot unbound successfully\"}");

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in UserBotUnbindHandler", e);
                sendResponse(exchange, 500, "{\"success\":false, \"error\":\"Internal server error\"}");
            }
        }
    }

    // New: Handler for bot owner to get bot profile
    private class UserBotProfileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            UUID ownerUuid = getPlayerUuidFromRequest(exchange);
            if (ownerUuid == null) {
                sendResponse(exchange, 401, "{\"success\":false, \"error\":\"Unauthorized\"}");
                return;
            }

            Map<String, String> query = AuthWithQqPlugin.parseQuery(exchange.getRequestURI().getQuery());
            String botUuidStr = query != null ? query.get("bot_uuid") : null;

            if (botUuidStr == null || botUuidStr.isEmpty()) {
                sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Missing bot_uuid parameter\"}");
                return;
            }

            try {
                UUID botUuid = UUID.fromString(botUuidStr);
                UUID actualOwner = plugin.getDatabaseManager().getBotOwner(botUuid);

                if (actualOwner == null || !actualOwner.equals(ownerUuid)) {
                    sendResponse(exchange, 403, "{\"success\":false, \"error\":\"You do not own this bot\"}");
                    return;
                }

                // Get bot data
                String botName = plugin.getDatabaseManager().getNameByUuid(botUuid);
                Map<String, String> meta = plugin.getDatabaseManager().getAllMeta(botUuid);

                JsonObject response = new JsonObject();
                response.addProperty("uuid", botUuid.toString());
                response.addProperty("name", botName);
                response.addProperty("owner_uuid", ownerUuid.toString());
                
                JsonObject metaJson = new JsonObject();
                meta.forEach(metaJson::addProperty);
                response.add("meta", metaJson);

                sendResponse(exchange, 200, gson.toJson(response));

            } catch (IllegalArgumentException e) {
                sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Invalid bot_uuid format\"}");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in UserBotProfileHandler", e);
                sendResponse(exchange, 500, "{\"success\":false, \"error\":\"Internal server error\"}");
            }
        }
    }

    // New: Handler for bot owner to update bot profile
    private class UserBotUpdateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            UUID ownerUuid = getPlayerUuidFromRequest(exchange);
            if (ownerUuid == null) {
                sendResponse(exchange, 401, "{\"success\":false, \"error\":\"Unauthorized\"}");
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                JsonObject body = gson.fromJson(reader, JsonObject.class);
                String botUuidStr = body.has("bot_uuid") ? body.get("bot_uuid").getAsString() : null;

                if (botUuidStr == null || botUuidStr.isEmpty()) {
                    sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Missing bot_uuid\"}");
                    return;
                }

                UUID botUuid = UUID.fromString(botUuidStr);
                UUID actualOwner = plugin.getDatabaseManager().getBotOwner(botUuid);

                if (actualOwner == null || !actualOwner.equals(ownerUuid)) {
                    sendResponse(exchange, 403, "{\"success\":false, \"error\":\"You do not own this bot\"}");
                    return;
                }

                JsonObject meta = body.has("meta") ? body.getAsJsonObject("meta") : new JsonObject();

                // Update custom meta fields (bot owners cannot change bot name or owner)
                for (Map.Entry<String, com.google.gson.JsonElement> entry : meta.entrySet()) {
                    String key = entry.getKey();
                    // Skip system fields that bot owners should not modify
                    if (key.startsWith("bot.")) {
                        continue;
                    }
                    // Remove meta field if value is null or empty string
                    if (entry.getValue().isJsonNull() || (entry.getValue().isJsonPrimitive() && entry.getValue().getAsString().isEmpty())) {
                        plugin.getDatabaseManager().deleteMeta(botUuid, key);
                    } else {
                        plugin.getDatabaseManager().setMeta(botUuid, key, entry.getValue().getAsString());
                    }
                }

                sendResponse(exchange, 200, "{\"success\":true, \"message\":\"Bot profile updated successfully\"}");

            } catch (IllegalArgumentException e) {
                sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Invalid bot_uuid format\"}");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in UserBotUpdateHandler", e);
                sendResponse(exchange, 500, "{\"success\":false, \"error\":\"Internal server error\"}");
            }
        }
    }

    // New: Handler for getting all bots
    private class AllBotsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!authenticateWithResponse(exchange)) {
                return;
            }

            try {
                List<Map<String, String>> allBotsData = plugin.getDatabaseManager().getAllBotsData();
                com.google.gson.JsonArray jsonArray = new com.google.gson.JsonArray();
                for (Map<String, String> botData : allBotsData) {
                    JsonObject botObject = new JsonObject();
                    botData.forEach(botObject::addProperty);
                    jsonArray.add(botObject);
                }
                sendResponse(exchange, 200, jsonArray.toString());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error getting all bots", e);
                sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
            }
        }
    }

    // New: Handler for CSV export
    private class CsvExportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!authenticateWithResponse(exchange)) {
                return;
            }

            try {
                // Parse query parameters to determine export type
                Map<String, String> query = AuthWithQqPlugin.parseQuery(exchange.getRequestURI().getQuery());
                String type = query != null ? query.getOrDefault("type", "players") : "players";
                boolean isBots = "bots".equalsIgnoreCase(type);

                List<String> headers;
                List<Map<String, String>> allData;
                String fileNamePrefix;

                if (isBots) {
                    // Export bots data
                    headers = new java.util.ArrayList<>(java.util.Arrays.asList(
                        "假人UUID", "假人名称", "所有者UUID", "所有者名称", "所有者QQ", "创建时间"
                    ));
                    allData = plugin.getDatabaseManager().getAllBotsData();
                    fileNamePrefix = "bots";
                } else {
                    // Export players data
                    List<String> metaKeys = plugin.getDatabaseManager().getAllMetaKeys();
                    headers = new java.util.ArrayList<>(java.util.Arrays.asList("UUID", "名称", "QQ", "创建时间"));
                    headers.addAll(metaKeys);
                    allData = plugin.getDatabaseManager().getAllPlayersData();
                    fileNamePrefix = "players";
                }

                StringBuilder csv = new StringBuilder();
                // Write header
                csv.append(String.join(",", headers));
                csv.append("\n");

                // Write data
                for (Map<String, String> row : allData) {
                    List<String> line = new java.util.ArrayList<>();
                    for (String header : headers) {
                        String value = "";
                        if (isBots) {
                            // Map Chinese headers to English keys for bots
                            switch (header) {
                                case "假人UUID":
                                    value = row.getOrDefault("bot_uuid", "");
                                    break;
                                case "假人名称":
                                    value = row.getOrDefault("bot_name", "");
                                    break;
                                case "所有者UUID":
                                    value = row.getOrDefault("owner_uuid", "");
                                    break;
                                case "所有者名称":
                                    value = row.getOrDefault("owner_name", "");
                                    break;
                                case "所有者QQ":
                                    value = row.getOrDefault("owner_qq", "");
                                    break;
                                case "创建时间":
                                    value = row.getOrDefault("created_at", "");
                                    break;
                                default:
                                    value = row.getOrDefault(header, "");
                                    break;
                            }
                        } else {
                            // Map Chinese headers to English keys for players
                            switch (header) {
                                case "UUID":
                                    value = row.getOrDefault("UUID", "");
                                    break;
                                case "名称":
                                    value = row.getOrDefault("Name", "");
                                    break;
                                case "QQ":
                                    value = row.getOrDefault("QQ", "");
                                    break;
                                case "创建时间":
                                    value = row.getOrDefault("Created", "");
                                    break;
                                default:
                                    // For meta keys, use as-is
                                    value = row.getOrDefault(header, "");
                                    break;
                            }
                        }
                        // Escape commas and quotes in CSV
                        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                            value = "\"" + value.replace("\"", "\"\"") + "\"";
                        }
                        line.add(value);
                    }
                    csv.append(String.join(",", line));
                    csv.append("\n");
                }

                exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
                exchange.getResponseHeaders().set("Content-Disposition", 
                    "attachment; filename=\"" + fileNamePrefix + "_" + 
                    new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".csv\"");
                
                // Add UTF-8 BOM for Excel compatibility with Chinese characters
                byte[] bom = {(byte)0xEF, (byte)0xBB, (byte)0xBF};
                byte[] csvBytes = csv.toString().getBytes(StandardCharsets.UTF_8);
                byte[] bytes = new byte[bom.length + csvBytes.length];
                System.arraycopy(bom, 0, bytes, 0, bom.length);
                System.arraycopy(csvBytes, 0, bytes, bom.length, csvBytes.length);
                
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error exporting CSV", e);
                sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
            }
        }
    }

    // New: Handler for CSV import
    private class CsvImportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!authenticateWithResponse(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            try {
                // Parse query parameters to determine import type
                Map<String, String> query = AuthWithQqPlugin.parseQuery(exchange.getRequestURI().getQuery());
                String type = query != null ? query.getOrDefault("type", "players") : "players";
                boolean isBots = "bots".equalsIgnoreCase(type);
                
                // Read CSV content from request body
                String csvContent;
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                
                if (contentType != null && contentType.startsWith("multipart/form-data")) {
                    // Handle multipart form data
                    byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
                    String body = new String(bodyBytes, StandardCharsets.UTF_8);
                    
                    String boundary = null;
                    for (String header : exchange.getRequestHeaders().get("Content-Type")) {
                        if (header.contains("boundary=")) {
                            boundary = "--" + header.substring(header.indexOf("boundary=") + 9);
                            break;
                        }
                    }
                    
                    if (boundary == null) {
                        sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Invalid multipart data\"}");
                        return;
                    }
                    
                    csvContent = extractCsvFromMultipart(body, boundary);
                    if (csvContent == null || csvContent.isEmpty()) {
                        sendResponse(exchange, 400, "{\"success\":false, \"error\":\"No CSV file found in upload\"}");
                        return;
                    }
                } else {
                    // Handle plain text CSV
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append("\n");
                        }
                        csvContent = sb.toString();
                    }
                }

                if (csvContent == null || csvContent.trim().isEmpty()) {
                    sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Empty CSV file\"}");
                    return;
                }

                // Remove UTF-8 BOM if present
                if (csvContent.startsWith("\uFEFF")) {
                    csvContent = csvContent.substring(1);
                }

                int importedCount = 0;
                
                if (isBots) {
                    // Import bots data
                    try (BufferedReader reader = new BufferedReader(
                            new java.io.StringReader(csvContent))) {
                        String headerLine = reader.readLine();
                        if (headerLine == null) {
                            sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Empty CSV header\"}");
                            return;
                        }
                        
                        // Parse header - support both Chinese and English headers
                        String[] headers = headerLine.split(",");
                        int botUuidIdx = -1, botNameIdx = -1, ownerUuidIdx = -1, ownerNameIdx = -1, ownerQqIdx = -1;
                        for (int i = 0; i < headers.length; i++) {
                            String header = headers[i].trim().replace("\"", "");
                            if (header.equals("假人UUID") || header.equals("bot_uuid")) {
                                botUuidIdx = i;
                            } else if (header.equals("假人名称") || header.equals("bot_name")) {
                                botNameIdx = i;
                            } else if (header.equals("所有者UUID") || header.equals("owner_uuid")) {
                                ownerUuidIdx = i;
                            } else if (header.equals("所有者名称") || header.equals("owner_name")) {
                                ownerNameIdx = i;
                            } else if (header.equals("所有者QQ") || header.equals("owner_qq")) {
                                ownerQqIdx = i;
                            }
                        }
                        
                        if (botUuidIdx == -1 || botNameIdx == -1) {
                            sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Missing required columns: 假人UUID/bot_uuid and 假人名称/bot_name\"}");
                            return;
                        }
                        
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.trim().isEmpty()) continue;
                            
                            // Parse CSV line (simple split, handle quoted values)
                            List<String> values = parseCsvLine(line);
                            if (values.size() <= Math.max(botUuidIdx, botNameIdx)) {
                                continue;
                            }
                            
                            String botUuidStr = values.get(botUuidIdx).trim().replace("\"", "");
                            String botName = values.get(botNameIdx).trim().replace("\"", "");
                            
                            if (botUuidStr.isEmpty() || botName.isEmpty()) {
                                continue;
                            }
                            
                            UUID botUuid;
                            try {
                                botUuid = UUID.fromString(botUuidStr);
                            } catch (IllegalArgumentException e) {
                                continue;
                            }
                            
                            UUID ownerUuid = null;
                            if (ownerUuidIdx >= 0 && ownerUuidIdx < values.size()) {
                                String ownerUuidStr = values.get(ownerUuidIdx).trim().replace("\"", "");
                                if (!ownerUuidStr.isEmpty()) {
                                    try {
                                        ownerUuid = UUID.fromString(ownerUuidStr);
                                    } catch (IllegalArgumentException e) {
                                        // Try to find by name or QQ
                                        if (ownerNameIdx >= 0 && ownerNameIdx < values.size()) {
                                            String ownerName = values.get(ownerNameIdx).trim().replace("\"", "");
                                            if (!ownerName.isEmpty()) {
                                                ownerUuid = plugin.getDatabaseManager().findUuidByNameOrQq(ownerName);
                                            }
                                        }
                                        if (ownerUuid == null && ownerQqIdx >= 0 && ownerQqIdx < values.size()) {
                                            String ownerQqStr = values.get(ownerQqIdx).trim().replace("\"", "");
                                            if (!ownerQqStr.isEmpty()) {
                                                try {
                                                    long ownerQq = Long.parseLong(ownerQqStr);
                                                    ownerUuid = plugin.getDatabaseManager().findUuidByQq(ownerQq);
                                                } catch (NumberFormatException ignored) {
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (ownerUuid == null) {
                                continue; // Skip if owner cannot be determined
                            }
                            
                            // Validate bot name prefix (skip invalid names during import)
                            if (!plugin.validateBotName(botName)) {
                                continue; // Skip invalid bot names
                            }
                            
                            // Import bot
                            plugin.getDatabaseManager().markPlayerAsBot(botUuid, ownerUuid, botName);
                            importedCount++;
                        }
                    }
                } else {
                    // Import players data using existing CsvManager
                    java.io.File tempFile = java.io.File.createTempFile("csv_import_", ".csv");
                    try {
                        try (java.io.FileWriter writer = new java.io.FileWriter(tempFile, StandardCharsets.UTF_8)) {
                            writer.write(csvContent);
                        }
                        
                        // Use existing CsvManager to import
                        plugin.getCsvManager().importCsv(tempFile);
                        
                        // Count imported records
                        importedCount = csvContent.split("\n").length - 1; // Subtract header
                        if (importedCount < 0) importedCount = 0;
                    } finally {
                        tempFile.delete();
                    }
                }
                
                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                result.addProperty("imported", importedCount);
                result.addProperty("message", String.format("导入完成: 成功导入 %d 条记录", importedCount));
                sendResponse(exchange, 200, gson.toJson(result));

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error importing CSV", e);
                sendResponse(exchange, 500, "{\"success\":false, \"error\":\"Internal server error: " + e.getMessage() + "\"}");
            }
        }

        private List<String> parseCsvLine(String line) {
            List<String> values = new java.util.ArrayList<>();
            boolean inQuotes = false;
            StringBuilder currentValue = new StringBuilder();
            
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        // Escaped quote
                        currentValue.append('"');
                        i++; // Skip next quote
                    } else {
                        // Toggle quote state
                        inQuotes = !inQuotes;
                    }
                } else if (c == ',' && !inQuotes) {
                    // End of field
                    values.add(currentValue.toString());
                    currentValue = new StringBuilder();
                } else {
                    currentValue.append(c);
                }
            }
            // Add last field
            values.add(currentValue.toString());
            return values;
        }
        
        private String extractCsvFromMultipart(String body, String boundary) {
            int startIdx = body.indexOf(boundary);
            if (startIdx == -1) {
                return null;
            }
            
            int contentStart = body.indexOf("\r\n\r\n", startIdx);
            if (contentStart == -1) {
                contentStart = body.indexOf("\n\n", startIdx);
                if (contentStart == -1) {
                    return null;
                }
                contentStart += 2;
            } else {
                contentStart += 4;
            }

            int endIdx = body.indexOf(boundary, contentStart);
            if (endIdx == -1) {
                endIdx = body.length();
            }

            String content = body.substring(contentStart, endIdx);
            content = content.replaceAll("--$", "").trim();
            // Remove trailing \r\n
            while (content.endsWith("\r\n") || content.endsWith("\n")) {
                content = content.substring(0, content.length() - (content.endsWith("\r\n") ? 2 : 1));
            }
            return content;
        }
    }

    // --- Authentication API Handlers ---

    private class AuthLoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"success\":false, \"error\":\"Method not allowed\"}");
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                JsonObject body = gson.fromJson(reader, JsonObject.class);
                String identifier = body.has("identifier") ? body.get("identifier").getAsString() : 
                                   (body.has("uuid") ? body.get("uuid").getAsString() : 
                                   (body.has("name") ? body.get("name").getAsString() : null));
                String password = body.has("password") ? body.get("password").getAsString() : null;

                if (identifier == null || identifier.isEmpty() || password == null || password.isEmpty()) {
                    sendResponse(exchange, 400, "{\"success\":false, \"error\":\"Missing identifier (UUID or player name) or password\"}");
                    return;
                }

                UUID uuid = null;
                String playerName = null;

                // Try to parse as UUID first
                try {
                    uuid = UUID.fromString(identifier);
                    playerName = plugin.getDatabaseManager().getNameByUuid(uuid);
                } catch (IllegalArgumentException e) {
                    // Not a UUID, try as player name
                    uuid = plugin.getDatabaseManager().getPlayerUuid(identifier);
                    if (uuid != null) {
                        // Get the actual player name from database (case-insensitive match)
                        playerName = plugin.getDatabaseManager().getNameByUuid(uuid);
                    }
                }

                // Check if player exists
                if (uuid == null || playerName == null) {
                    sendResponse(exchange, 404, "{\"success\":false, \"error\":\"Player not found\"}");
                    return;
                }

                // Check if password is set
                String passwordHash = plugin.getDatabaseManager().getWebPasswordHash(uuid);
                if (passwordHash == null || passwordHash.isEmpty()) {
                    sendResponse(exchange, 403, "{\"success\":false, \"error\":\"Password not set. Please set password in-game using /bind password set\"}");
                    return;
                }

                // Verify password
                if (!plugin.verifyPassword(password, passwordHash)) {
                    sendResponse(exchange, 401, "{\"success\":false, \"error\":\"Invalid password\"}");
                    return;
                }

                // Create session
                String sessionToken = plugin.createWebLoginSession(uuid);
                long expiresAt = plugin.getWebLoginSession(sessionToken).expiresAt;

                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("session_token", sessionToken);
                response.addProperty("expires_at", expiresAt);
                JsonObject playerObj = new JsonObject();
                playerObj.addProperty("uuid", uuid.toString());
                playerObj.addProperty("name", playerName);
                response.add("player", playerObj);

                sendResponse(exchange, 200, gson.toJson(response));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in AuthLoginHandler", e);
                sendResponse(exchange, 500, "{\"success\":false, \"error\":\"Internal server error\"}");
            }
        }
    }

    private class AuthLogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"success\":false, \"error\":\"Method not allowed\"}");
                return;
            }

            String sessionToken = exchange.getRequestHeaders().getFirst("X-Session-Token");
            if (sessionToken != null && !sessionToken.isEmpty()) {
                plugin.revokeWebLoginSession(sessionToken);
            }

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            sendResponse(exchange, 200, gson.toJson(response));
        }
    }

    private class AuthVerifyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String sessionToken = exchange.getRequestHeaders().getFirst("X-Session-Token");
            if (sessionToken == null || sessionToken.isEmpty()) {
                JsonObject response = new JsonObject();
                response.addProperty("valid", false);
                response.addProperty("error", "Missing session token");
                sendResponse(exchange, 401, gson.toJson(response));
                return;
            }

            AuthWithQqPlugin.WebLoginSessionEntry session = plugin.getWebLoginSession(sessionToken);
            if (session == null) {
                JsonObject response = new JsonObject();
                response.addProperty("valid", false);
                response.addProperty("error", "Invalid or expired session");
                sendResponse(exchange, 401, gson.toJson(response));
                return;
            }

            String playerName = plugin.getDatabaseManager().getNameByUuid(session.uuid);
            JsonObject response = new JsonObject();
            response.addProperty("valid", true);
            response.addProperty("uuid", session.uuid.toString());
            response.addProperty("name", playerName != null ? playerName : "");
            response.addProperty("expires_at", session.expiresAt);
            sendResponse(exchange, 200, gson.toJson(response));
        }
    }
}
      