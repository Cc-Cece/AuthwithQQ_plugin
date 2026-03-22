package com.cccece.authwithqq.velocity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Lightweight Velocity proxy plugin for AuthWithQq.
 * Intercepts player logins and verifies QQ binding via the Paper backend API.
 * Install this on your Velocity proxy and set the Paper plugin to proxy-backend mode.
 */
@Plugin(
    id = "authwithqq-velocity",
    name = "AuthWithQq-Velocity",
    version = "1.11-SNAPSHOT",
    description = "Velocity proxy plugin for AuthWithQq QQ-binding authentication",
    authors = {"CCCECE"}
)
public class AuthWithQqVelocity {

  private final ProxyServer server;
  private final Logger logger;
  private final Path dataDirectory;

  private ConfigurationNode config;

  // Shared HttpClient for connection reuse and pooling
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  /**
   * Constructor injected by Velocity's Guice container.
   *
   * @param server The proxy server instance.
   * @param logger The SLF4J logger.
   * @param dataDirectory The plugin's data directory.
   */
  @Inject
  public AuthWithQqVelocity(ProxyServer server, Logger logger,
      @DataDirectory Path dataDirectory) {
    this.server = server;
    this.logger = logger;
    this.dataDirectory = dataDirectory;

    loadConfig();
    String apiUrl = config.node("paper-api", "url").getString("http://127.0.0.1:8081");
    String publicWebUrl = config.node("public-web-url").getString(apiUrl + "/web/auth.html");
    boolean failOpen = config.node("fail-open").getBoolean(false);
    logger.info("AuthWithQq-Velocity has been initialized. paperApiUrl={}, publicWebUrl={}, failOpen={}", apiUrl, publicWebUrl, failOpen);
  }

  private void loadConfig() {
    try {
      if (!Files.exists(dataDirectory)) {
        Files.createDirectories(dataDirectory);
      }

      Path configPath = dataDirectory.resolve("config.yml");
      if (!Files.exists(configPath)) {
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
          if (in != null) {
            Files.copy(in, configPath);
          }
        }
      }

      config = YamlConfigurationLoader.builder()
          .path(configPath)
          .build()
          .load();
    } catch (IOException e) {
      logger.error("Failed to load config: {}", e.getMessage());
    }
  }

  /**
   * Intercepts the login event to enforce QQ-binding before granting access.
   *
   * @param event The LoginEvent fired by Velocity.
   * @return An async EventTask so the blocking HTTP call does not stall the Netty thread.
   */
  @Subscribe
  public EventTask onLogin(LoginEvent event) {
    return EventTask.async(() -> {
      String username = event.getPlayer().getUsername();
      UUID uuid = event.getPlayer().getUniqueId();

      // Whitelist bypass
      try {
        List<String> bypass = config.node("whitelist", "bypass-usernames")
            .getList(String.class, List.of());
        if (bypass.contains(username)) {
          return;
        }
      } catch (SerializationException e) {
        logger.warn("Could not read whitelist bypass list: {}", e.getMessage());
      }

      String apiUrl = config.node("paper-api", "url").getString("http://127.0.0.1:8081");
      String apiToken = config.node("paper-api", "token").getString("changeme");
      String publicWebUrl = config.node("public-web-url").getString(apiUrl + "/web/auth.html");
      boolean failOpen = config.node("fail-open").getBoolean(false);

      try {
        boolean isBound = checkBinding(uuid, apiUrl, apiToken);
        if (!isBound) {
          String authUrl = publicWebUrl;
          String message = config.node("messages", "not-bound")
              .getString("&c你的账号尚未绑定QQ！\n&6请访问 %auth_url% 进行绑定。");
          message = message.replace("%auth_url%", authUrl);
          Component kickMessage = LegacyComponentSerializer.legacyAmpersand()
              .deserialize(message);
          event.setResult(ResultedEvent.ComponentResult.denied(kickMessage));
        }
      } catch (IOException e) {
        logger.warn("Failed to check binding for {} via {} (failOpen={}): {}", username, apiUrl, failOpen, e.getMessage());
        if (!failOpen) {
          Component errorMessage = LegacyComponentSerializer.legacyAmpersand()
              .deserialize("&c无法验证账号绑定状态，请联系管理员。");
          event.setResult(ResultedEvent.ComponentResult.denied(errorMessage));
        }
      }
    });
  }

  /**
   * Calls the Paper plugin's /api/proxy/check endpoint to determine whether the given
   * player UUID has a bound QQ account.
   *
   * @param uuid The player's UUID.
   * @param apiUrl The base URL of the Paper plugin's web server.
   * @param apiToken The API token (must match Paper's server.token).
   * @return {@code true} if the player is bound; {@code false} otherwise.
   * @throws IOException If the HTTP request fails or times out.
   */
  private boolean checkBinding(UUID uuid, String apiUrl, String apiToken) throws IOException {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(apiUrl + "/api/proxy/check?uuid=" + uuid))
        .header("X-API-Token", apiToken)
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IOException("Paper API returned HTTP " + response.statusCode());
      }
      JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
      return json.has("bound") && json.get("bound").getAsBoolean();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("HTTP request interrupted", e);
    }
  }
}
