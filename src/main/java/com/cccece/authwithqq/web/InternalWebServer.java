package com.cccece.authwithqq.web;

import com.cccece.authwithqq.AuthWithQqPlugin;
import com.google.gson.Gson;
import com.google.gson.JsonArray; // ADDED
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
import java.util.List; // ADDED THIS IMPORT
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
      server.createContext("/api/admin/bind", new AdminBindHandler()); // New: API for admin binding operations
      server.createContext("/api/profile", new ProfileViewHandler()); // New: API for viewing player profile
      server.createContext("/api/profile/update", new ProfileUpdateHandler()); // New: API for updating player profile
      server.createContext("/", new RedirectHandler("/web/dashboard.html")); // Redirect to dashboard
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
    if (token.equals(requestToken)) {
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
          return json;
        });
        JsonObject json = future.get(); // This blocks until the main thread runs the code

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
      if (!authenticate(exchange)) {
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
      if (!authenticate(exchange)) {
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
      if (!authenticate(exchange)) {
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
        boolean action = body.get("add").getAsBoolean();

        Bukkit.getScheduler().runTask(plugin, () -> {
          org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
          offlinePlayer.setWhitelisted(action);
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
      if (!authenticate(exchange)) {
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
      if (!authenticate(exchange)) {
        return;
      }
      exchange.getResponseHeaders().set("Location", targetPath);
      exchange.sendResponseHeaders(302, -1); // 302 Found (temporary redirect)
    }
  }

  private class MetaHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      List<Map<?, ?>> customFields = plugin.getConfig().getMapList("binding.custom-fields");
      
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
      if (!authenticate(exchange)) {
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
      if (!authenticate(exchange)) {
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

  // New: Handles binding a bot to an owner
  private class BotBindHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "Method not allowed");
        return;
      }

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
        JsonObject body = gson.fromJson(reader, JsonObject.class);
        String ownerUuidString = body.get("owner_uuid").getAsString();
        String botName = body.get("bot_name").getAsString();

        UUID ownerUuid;
        try {
          ownerUuid = UUID.fromString(ownerUuidString);
        } catch (IllegalArgumentException e) {
          sendResponse(exchange, 400, "Invalid owner_uuid format");
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
        if (maxBotsPerPlayer > 0 && currentBotCount >= maxBotsPerPlayer) {
          sendResponse(exchange, 400, "{\"success\":false, \"error\":\"达到假人绑定上限\"}");
          return;
        }

        // Generate a UUID for the bot (deterministic based on name for consistency if needed, or random)
        UUID botUuid = UUID.nameUUIDFromBytes(("Bot-" + botName).getBytes(StandardCharsets.UTF_8));

        plugin.getDatabaseManager().markPlayerAsBot(botUuid, ownerUuid, botName);
        
        sendResponse(exchange, 200, "{\"success\":true}");
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

      // Construct the resource path within the JAR (e.g., "web/auth.html")
      String resourcePath = "web" + path;
      if (resourcePath.endsWith("/")) { // Default to index.html for directories
        resourcePath += "index.html";
      }

      try (java.io.InputStream is = plugin.getResource(resourcePath)) {
        if (is == null) {
          plugin.getLogger().warning("Resource not found: " + resourcePath);
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

        byte[] responseBytes = is.readAllBytes();
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
      if (!authenticate(exchange)) {
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
      String token = query.get("token"); // Player session token
      String playerUuidString = query.get("uuid"); // Player UUID for admin access

      UUID uuid = null; // Final UUID to fetch profile for

      if (token != null && !token.isEmpty()) {
        // Player access using session token
        uuid = plugin.validateProfileSessionToken(token);
        if (uuid == null) {
          sendResponse(exchange, 401, "{\"error\":\"Invalid or expired session token\"}");
          return;
        }
      } else if (playerUuidString != null && !playerUuidString.isEmpty()) {
        // Admin access using X-API-Token and player UUID
        if (!authenticate(exchange)) { // Authenticate admin
          return;
        }
        try {
          uuid = UUID.fromString(playerUuidString);
        } catch (IllegalArgumentException e) {
          sendResponse(exchange, 400, "Invalid player UUID format");
          return;
        }
      } else {
        // Neither token nor uuid provided
        sendResponse(exchange, 400, "Missing session token or player UUID");
        return;
      }

      // At this point, 'uuid' should be valid whether from token or direct UUID
      final UUID finalUuid = uuid; // For use in lambda

      try {
        Future<JsonObject> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
          JsonObject json = new JsonObject();
          long qq = plugin.getDatabaseManager().getQq(finalUuid);
          String name = plugin.getDatabaseManager().getNameByUuid(finalUuid);
          Map<String, String> meta = plugin.getDatabaseManager().getAllMeta(finalUuid);
          
          json.addProperty("uuid", finalUuid.toString());
          json.addProperty("name", name);
          json.addProperty("qq", qq);

          JsonObject metaJson = new JsonObject();
          meta.forEach(metaJson::addProperty);
          json.add("meta", metaJson);
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
        String tokenFromBody = body.has("token") ? body.get("token").getAsString() : null; // Token for player session
        String playerUuidStringFromBody = body.has("uuid") ? body.get("uuid").getAsString() : null; // UUID for admin context

        UUID uuidToUpdate = null; // Final UUID to update profile for

        // Try to authenticate as admin first
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
          // Player update: UUID derived from session token
          if (tokenFromBody == null || tokenFromBody.isEmpty()) {
            sendResponse(exchange, 400, "Missing session token");
            return;
          }
          uuidToUpdate = plugin.validateProfileSessionToken(tokenFromBody);
          if (uuidToUpdate == null) {
            sendResponse(exchange, 401, "{\"error\":\"Invalid or expired session token\"}");
            return;
          }
        }

        final UUID finalUuid = uuidToUpdate; // For use in lambda
        long newQq = body.get("qq").getAsLong();
        JsonObject meta = body.getAsJsonObject("meta");

        Future<Boolean> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
          plugin.getDatabaseManager().updateBinding(finalUuid, newQq); // Update QQ

          // Update custom meta fields
          for (Map.Entry<String, com.google.gson.JsonElement> entry : meta.entrySet()) {
            // Remove meta field if value is null or empty string
            if (entry.getValue().isJsonNull() || (entry.getValue().isJsonPrimitive() && entry.getValue().getAsString().isEmpty())) {
                plugin.getDatabaseManager().deleteMeta(finalUuid, entry.getKey());
            } else {
                plugin.getDatabaseManager().setMeta(finalUuid, entry.getKey(), entry.getValue().getAsString());
            }
          }
          plugin.handleBindingChange(finalUuid, newQq); // Update guest status if online
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
}
