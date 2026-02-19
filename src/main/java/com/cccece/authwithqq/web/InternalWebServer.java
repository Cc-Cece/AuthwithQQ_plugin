package com.cccece.authwithqq.web;

import com.cccece.authwithqq.AuthWithQqPlugin;
import com.google.gson.Gson;
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
      if (!authenticate(exchange)) {
        return;
      }

      try {
        Future<JsonObject> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
          JsonObject json = new JsonObject();
          json.addProperty("online_players", Bukkit.getOnlinePlayers().size());
          json.addProperty("max_players", Bukkit.getMaxPlayers());
          json.addProperty("tps", Bukkit.getTPS()[0]);
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
        long qq = body.get("qq").getAsLong();
        String code = body.get("code").getAsString(); // UUID string in our case
        
        UUID uuid;
        try {
          uuid = UUID.fromString(code);
        } catch (IllegalArgumentException e) {
          sendResponse(exchange, 400, "Invalid code (UUID expected)");
          return;
        }

        plugin.getDatabaseManager().updateBinding(uuid, qq);
        
        if (body.has("meta") && body.get("meta").isJsonObject()) {
          JsonObject meta = body.getAsJsonObject("meta");
          for (Map.Entry<String, com.google.gson.JsonElement> entry : meta.entrySet()) {
            plugin.getDatabaseManager().setMeta(uuid, entry.getKey(),
                entry.getValue().getAsString());
          }
        }

        // Notify plugin about binding
        Bukkit.getScheduler().runTask(plugin, () -> plugin.handleBindingSuccess(uuid));
        
        sendResponse(exchange, 200, "{\"success\":true}");
      } catch (Exception e) {
        sendResponse(exchange, 500, "Error: " + e.getMessage());
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
        sendResponse(exchange, 500, "Error: " + e.getMessage());
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
        sendResponse(exchange, 500, "Error: " + e.getMessage());
      }
    }
  }
}
