package com.cccece.authwithqq.web;

import com.cccece.authwithqq.AuthWithQqPlugin;
import com.cccece.authwithqq.database.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * Lightweight WebSocket server for OneBot v11 reverse WebSocket integration.
 *
 * <p>Supports the following QQ commands:
 * <ul>
 *   <li>"绑定 &lt;验证码&gt;" - Bind QQ using verification code</li>
 *   <li>"/绑定假人 &lt;假人名称&gt;" - Bind bot to current QQ's player</li>
 *   <li>"/解绑假人 &lt;假人名称&gt;" - Unbind bot</li>
 *   <li>"/假人列表" - List bots bound to current QQ's player</li>
 * </ul>
 *
 * <p>Design goals:
 * <ul>
 *   <li>Independent WebSocket port (does not touch existing HTTP server).</li>
 *   <li>No heavy web framework dependencies.</li>
 *   <li>Use OneBot standard action frames for replies (send_group_msg / send_private_msg).</li>
 * </ul>
 */
public class OneBotWebSocketServer extends WebSocketServer {

  private final AuthWithQqPlugin plugin;
  private final Gson gson = new Gson();
  private final String expectedPath;
  private final String expectedToken; // Empty means no auth

  // Pending OneBot action responses (keyed by echo)
  private final Map<String, CompletableFuture<JsonObject>> pendingActions =
      new ConcurrentHashMap<>();

  // Log level constants
  private static final int LOG_LEVEL_COMMANDS = 1; // Only log command execution
  private static final int LOG_LEVEL_ALL = 2;      // Log everything
  private static final int LOG_LEVEL_NONE = 3;     // No logging

  // Command patterns
  // 绑定验证码：绑定1234 / 绑定 1234 / 绑定 1234。 等（不需要 /）
  private static final Pattern BIND_CODE_PATTERN = Pattern.compile("^绑定\\s*(\\d+)$");
  // 绑定假人：必须以 / 开头，防止误触，例如：/绑定假人 xxx
  private static final Pattern BIND_BOT_PATTERN = Pattern.compile("^/绑定假人\\s*(.+)$");
  // 解绑假人：必须以 / 开头，例如：/解绑假人 xxx
  private static final Pattern UNBIND_BOT_PATTERN = Pattern.compile("^/解绑假人\\s*(.+)$");
  // 假人列表：必须以 / 开头，例如：/假人列表
  private static final Pattern LIST_BOTS_PATTERN = Pattern.compile("^/假人列表$");

  /**
   * Creates a new OneBot WebSocket server.
   *
   * @param plugin The plugin instance.
   * @param port The port to listen on.
   * @param path The expected WebSocket path (e.g. "/onebot/v11/ws").
   * @param token The access token to validate (empty string means no validation).
   */
  public OneBotWebSocketServer(AuthWithQqPlugin plugin, int port, String path, String token) {
    super(new InetSocketAddress(port));
    this.plugin = plugin;
    this.expectedPath = path != null && !path.isEmpty() ? path : "/onebot/v11/ws";
    this.expectedToken = token != null ? token : "";
  }

  // ------------------------ Logging Helpers ------------------------

  private int getLogLevel() {
    return plugin.getConfig().getInt("onebot.log-level", LOG_LEVEL_COMMANDS);
  }

  /** Returns true if we should log all messages (connection, raw data, etc). */
  private boolean shouldLogAll() {
    return getLogLevel() == LOG_LEVEL_ALL;
  }

  /** Returns true if we should log command-related messages. */
  private boolean shouldLogCommands() {
    int level = getLogLevel();
    return level == LOG_LEVEL_COMMANDS || level == LOG_LEVEL_ALL;
  }

  /** Log info if log level allows all logs. */
  private void logAll(String message) {
    if (shouldLogAll()) {
      plugin.getLogger().info("[OneBot-WS] " + message);
    }
  }

  /** Log info if log level allows command logs. */
  private void logCommand(String message) {
    if (shouldLogCommands()) {
      plugin.getLogger().info("[OneBot-WS] " + message);
    }
  }

  /** Log warning if log level allows all logs. */
  private void logAllWarning(String message) {
    if (shouldLogAll()) {
      plugin.getLogger().warning("[OneBot-WS] " + message);
    }
  }

  /** Log severe errors always (except when completely silent). */
  private void logError(String message, Throwable e) {
    if (getLogLevel() != LOG_LEVEL_NONE) {
      plugin.getLogger().log(Level.SEVERE, "[OneBot-WS] " + message, e);
    }
  }

  /**
   * 规范化指令文本：
   * - 去掉首尾空白
   * - 合并连续空白为一个空格
   * - 去掉末尾常见标点（。．.!！？?）
   */
  private String normalizeCommandText(String text) {
    if (text == null) {
      return "";
    }
    String result = text.trim();
    // 合并各种空白
    result = result.replaceAll("\\s+", " ");
    // 去掉结尾中文/英文标点
    result = result.replaceAll("[。．\\.！!？?]+$", "");
    return result.trim();
  }

  @Override
  public void onStart() {
    // Always log server start (important for debugging)
    if (getLogLevel() != LOG_LEVEL_NONE) {
      plugin.getLogger().info("[OneBot-WS] Server started on port "
          + getPort() + " with path " + expectedPath);
    }
    setConnectionLostTimeout(60);
  }

  @Override
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
    try {
      String resource = handshake.getResourceDescriptor(); // e.g. "/onebot/v11/ws?access_token=xxx"
      logAll("New connection from " + conn.getRemoteSocketAddress() + " resource=" + resource);

      URI uri = new URI("ws://localhost" + resource);
      String path = uri.getPath();
      String query = uri.getQuery();

      if (!expectedPath.equals(path)) {
        logAllWarning("Rejecting connection with unexpected path: " + path);
        conn.close(1008, "Invalid path");
        return;
      }

      if (!expectedToken.isEmpty()) {
        String token = extractTokenFromQuery(query);
        if (token == null || !expectedToken.equals(token)) {
          logAllWarning("Rejecting connection due to invalid token");
          conn.close(1008, "Unauthorized");
          return;
        }
      }

      // OneBot 客户端连接成功的提示（受 log-level 控制）
      logAll("Connection accepted: " + conn.getRemoteSocketAddress());
      logCommand("OneBot 客户端已连接: " + conn.getRemoteSocketAddress());

      // If force-group-binding is enabled, refresh group member cache asynchronously
      if (plugin.getConfig().getBoolean("binding.force-group-binding", false)) {
        new Thread(() -> refreshGroupMembers(conn), "AuthWithQq-RefreshGroupMembers").start();
      }
    } catch (URISyntaxException e) {
      logError("Invalid URI in handshake", e);
      conn.close(1002, "Bad request");
    }
  }

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    logAll("Connection closed: " + conn.getRemoteSocketAddress()
        + " code=" + code + " reason=" + reason + " remote=" + remote);
  }

  @Override
  public void onMessage(WebSocket conn, String message) {
    try {
      logAll("Received message: " + message);

      JsonObject event = gson.fromJson(message, JsonObject.class);
      if (event == null) {
        logAllWarning("Received null/invalid JSON event");
        return;
      }

      // Handle OneBot API action responses (no post_type, but has echo)
      if (event.has("echo") && !event.has("post_type")) {
        String echo = getString(event, "echo");
        if (echo != null) {
          CompletableFuture<JsonObject> future = pendingActions.remove(echo);
          if (future != null) {
            future.complete(event);
          }
        }
        return;
      }

      String postType = getString(event, "post_type");
      if (!"message".equals(postType) && !"notice".equals(postType)) {
        // Ignore other events
        return;
      }

      // Handle group member increase/decrease notices for cache updates
      if ("notice".equals(postType)) {
        handleNotice(event);
        return;
      }

      String messageType = getString(event, "message_type"); // "group" or "private"
      boolean isGroup = "group".equals(messageType);
      boolean isPrivate = "private".equals(messageType);

      if (!isGroup && !isPrivate) {
        logAll("Ignoring unsupported message_type: " + messageType);
        return;
      }

      if (!event.has("user_id") || !event.has("message")) {
        logAllWarning("Missing user_id or message in event");
        return;
      }

      long qq = event.get("user_id").getAsLong();
      long groupId = isGroup && event.has("group_id") ? event.get("group_id").getAsLong() : 0L;

      // Check group whitelist
      if (isGroup && !isGroupAllowed(groupId)) {
        logAll("Group " + groupId + " not in allowed-groups list, ignoring message");
        return;
      }

      // Check private allowed
      if (isPrivate && !plugin.getConfig().getBoolean("onebot.allow-private", true)) {
        logAll("Private messages disabled, ignoring");
        return;
      }

      // Extract text from message field (string or array format)
      String text = extractMessageText(event.get("message"));
      if (text == null || text.trim().isEmpty()) {
        logAll("Empty message text, ignoring");
        return;
      }
      text = text.trim();

      logAll("Processing message from QQ " + qq
          + " (type=" + messageType + ", group=" + groupId + "): " + text);

      // Process command and get response text
      String response = processCommand(event, qq, text, isGroup, groupId);
      if (response == null || response.isEmpty()) {
        return; // Nothing to reply
      }

      // Send reply via OneBot action
      sendReply(conn, event, response, isGroup, isPrivate, qq, groupId);

    } catch (Exception e) {
      logError("Error handling message", e);
    }
  }

  @Override
  public void onError(WebSocket conn, Exception ex) {
    if (conn != null) {
      logError("Error on connection " + conn.getRemoteSocketAddress(), ex);
    } else {
      logError("Server error", ex);
    }
  }

  // ------------------------ Command Handling ------------------------

  /**
   * Checks if a message looks like a command that we should process.
   * Only messages starting with "绑定"、"登记" 或 "/" 被视为命令。
   */
  private boolean looksLikeCommand(String message) {
    String normalized = normalizeCommandText(message);
    return normalized.startsWith("绑定") || normalized.startsWith("登记") || normalized.startsWith("/");
  }

  /**
   * 判断当前消息发送者是否为群管理员（admin/owner）。
   */
  private boolean isGroupAdmin(JsonObject event) {
    if (!event.has("sender") || !event.get("sender").isJsonObject()) {
      return false;
    }
    JsonObject sender = event.getAsJsonObject("sender");
    String role = getString(sender, "role");
    return "admin".equalsIgnoreCase(role) || "owner".equalsIgnoreCase(role);
  }

  /**
   * 从 OneBot 消息数组中提取 @ 的 QQ 号（如果存在）。
   */
  private Long extractAtQqFromMessage(JsonElement messageElement) {
    if (messageElement == null || !messageElement.isJsonArray()) {
      return null;
    }
    JsonArray arr = messageElement.getAsJsonArray();
    for (JsonElement el : arr) {
      if (el.isJsonObject()) {
        JsonObject obj = el.getAsJsonObject();
        String type = getString(obj, "type");
        if ("at".equals(type) && obj.has("data") && obj.get("data").isJsonObject()) {
          JsonObject data = obj.getAsJsonObject("data");
          if (data.has("qq")) {
            try {
              return data.get("qq").getAsLong();
            } catch (Exception ignored) {
              // Ignore parse errors
            }
          }
        }
      }
    }
    return null;
  }

  private String processCommand(JsonObject event, long qq, String message, boolean isGroup, long groupId) {
    try {
      // 统一处理空格、结尾标点
      String normalized = normalizeCommandText(message);

      // 0. 先做“登记”命令（仅群聊 + 群管理员）
      if (isGroup && normalized.startsWith("登记")) {
        // 仅允许群管理员
        if (!isGroupAdmin(event)) {
          return plugin.getMessage("onebot.register.no-permission");
        }

        // 去掉前缀“登记”，只看后面的部分
        String afterKeyword = normalized.substring("登记".length()).trim();
        if (afterKeyword.isEmpty()) {
          return plugin.getMessage("onebot.register.usage");
        }

        String[] tokens = afterKeyword.split(" ");

        // 优先尝试纯文本格式：登记 <QQ号> <MC名>
        if (tokens.length >= 2 && tokens[0].matches("\\d{5,}")) {
          long targetQq;
          try {
            targetQq = Long.parseLong(tokens[0]);
          } catch (NumberFormatException e) {
            return plugin.getMessage("onebot.register.invalid-qq");
          }
          String mcName = tokens[tokens.length - 1];
          logCommand("Command: 登记 (QQ) from admin QQ " + qq + " targetQQ=" + targetQq + " mc=" + mcName);
          return handleRegisterByAdmin(targetQq, mcName);
        }

        // 尝试 @ 格式：登记 @某人 <MC名>
        Long targetQqFromAt = extractAtQqFromMessage(event.get("message"));
        if (targetQqFromAt != null && tokens.length >= 1) {
          String mcName = tokens[tokens.length - 1];
          logCommand("Command: 登记 (@) from admin QQ " + qq + " targetQQ=" + targetQqFromAt + " mc=" + mcName);
          return handleRegisterByAdmin(targetQqFromAt, mcName);
        }

        // 无法解析 QQ
        return plugin.getMessage("onebot.register.target-qq-not-found");
      }

      // 1. 绑定验证码: "绑定 <验证码>" 或 "绑定<验证码>"
      Matcher bindCodeMatcher = BIND_CODE_PATTERN.matcher(normalized);
      if (bindCodeMatcher.matches()) {
        String code = bindCodeMatcher.group(1);
        logCommand("Command: 绑定验证码 from QQ " + qq + " (code=" + code + ")");
        return handleBindCode(qq, code);
      }

      // 2. 绑定假人: "/绑定假人 <假人名称>"
      Matcher bindBotMatcher = BIND_BOT_PATTERN.matcher(normalized);
      if (bindBotMatcher.matches()) {
        String botName = bindBotMatcher.group(1).trim();
        logCommand("Command: 绑定假人 from QQ " + qq + " (bot=" + botName + ")");
        return handleBindBot(qq, botName);
      }

      // 3. 解绑假人: "/解绑假人 <假人名称>"
      Matcher unbindBotMatcher = UNBIND_BOT_PATTERN.matcher(normalized);
      if (unbindBotMatcher.matches()) {
        String botName = unbindBotMatcher.group(1).trim();
        logCommand("Command: 解绑假人 from QQ " + qq + " (bot=" + botName + ")");
        return handleUnbindBot(qq, botName);
      }

      // 4. 假人列表: "/假人列表"
      if (LIST_BOTS_PATTERN.matcher(normalized).matches()) {
        logCommand("Command: 假人列表 from QQ " + qq);
        return handleListBots(qq);
      }

      // 5. 帮助命令: "/帮助" or "/help"
      if (normalized.equals("/帮助") || normalized.equalsIgnoreCase("/help")) {
        logCommand("Command: 帮助 from QQ " + qq);
        return getHelpMessage();
      }

      // 未匹配到任何已知指令：仅记录日志，不再向 QQ 回复“未知命令”
      if (looksLikeCommand(normalized)) {
        logCommand("Unknown command from QQ " + qq + ": " + normalized);
      }
      // 静默忽略
      return null;

    } catch (Exception e) {
      logError("Error processing command for QQ " + qq, e);
      return plugin.getMessage("onebot.common.error-command-generic");
    }
  }

  private String getHelpMessage() {
    StringBuilder sb = new StringBuilder();
    sb.append(plugin.getMessage("onebot.help.header"));
    java.util.List<?> msgLines = plugin.getMessagesConfig().getList("onebot.help.lines");
    if (msgLines != null && !msgLines.isEmpty()) {
      for (Object lineObj : msgLines) {
        sb.append("\n").append(String.valueOf(lineObj));
      }
      return sb.toString();
    }
    // 退回到简单的默认文案
    return "📖 支持的命令：\n"
        + "━━━━━━━━━━━━━━━\n"
        + "🔗 绑定 <验证码>\n"
        + "   └ 使用验证码绑定MC账号\n"
        + "🤖 /绑定假人 <名称>\n"
        + "   └ 绑定一个假人到你的账号\n"
        + "🗑️ /解绑假人 <名称>\n"
        + "   └ 解绑指定假人\n"
        + "📋 /假人列表\n"
        + "   └ 查看你绑定的所有假人\n"
        + "❓ /帮助\n"
        + "   └ 显示此帮助信息";
  }

  private String handleBindCode(long qq, String code) {
    try {
      // Find player info by verification code on main thread (uses internal maps)
      Future<Map<String, String>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.findPlayerInfoByCode(code);
      });

      Map<String, String> playerInfo = future.get();
      if (playerInfo == null) {
        return plugin.getMessage("onebot.bind.invalid-code");
      }

      String playerUuidStr = playerInfo.get("uuid");
      String playerName = playerInfo.get("name");
      UUID playerUuid = UUID.fromString(playerUuidStr);

      // Check if QQ is already bound to this player
      Future<Long> existingQqFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().getQq(playerUuid);
      });
      long existingQq = existingQqFuture.get();

      if (existingQq == qq) {
        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("player", playerName);
        return plugin.getMessage("onebot.bind.already-bound-same-account", placeholders);
      }

      if (existingQq != 0 && existingQq != qq) {
        return plugin.getMessage("onebot.bind.already-bound-other-qq");
      }

      // Check binding limit
      int maxAccountsPerQq = plugin.getConfig().getInt("binding.max-accounts-per-qq", 1);
      Future<Integer> accountCountFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().getAccountCountByQq(qq);
      });
      int currentAccountCount = accountCountFuture.get();

      if (currentAccountCount >= maxAccountsPerQq) {
        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("max", String.valueOf(maxAccountsPerQq));
        return plugin.getMessage("onebot.bind.max-accounts-reached", placeholders);
      }

      // Perform binding and invalidate code on main thread
      Future<Void> bindFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        DatabaseManager db = plugin.getDatabaseManager();
        db.updateBinding(playerUuid, qq);
        plugin.invalidateCode(playerUuid);
        return null;
      });
      bindFuture.get();

      java.util.Map<String, String> placeholders = new java.util.HashMap<>();
      placeholders.put("player", playerName);
      placeholders.put("uuid_short", playerUuidStr.substring(0, 8));
      placeholders.put("qq", String.valueOf(qq));
      return plugin.getMessage("onebot.bind.success", placeholders);

    } catch (InterruptedException | ExecutionException e) {
      logError("Error handling bind code for QQ " + qq, e);
      return plugin.getMessage("onebot.common.error-command-generic");
    }
  }

  private String handleBindBot(long qq, String botName) {
    try {
      // Get player UUID by QQ
      Future<UUID> uuidFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().findUuidByQq(qq);
      });

      UUID ownerUuid = uuidFuture.get();
      if (ownerUuid == null) {
        return "❌ 你尚未绑定QQ\n\n💡 请先使用「绑定 <验证码>」绑定你的账号";
      }

      // Check bot limit
      int maxBots = plugin.getConfig().getInt("binding.max-bots-per-player", 0);
      Future<Integer> botCountFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().getBotCountForOwner(ownerUuid);
      });
      int currentBotCount = botCountFuture.get();

      if (maxBots == 0) {
        return "🚫 假人功能已禁用";
      } else if (maxBots > 0 && currentBotCount >= maxBots) {
        return "❌ 假人数量已达上限\n\n📊 当前: " + currentBotCount + "/" + maxBots;
      }

      // Check if bot name already exists
      Future<UUID> existingBotOwnerFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().getOwnerByBotName(botName);
      });
      UUID existingBotOwner = existingBotOwnerFuture.get();

      if (existingBotOwner != null) {
        return "❌ 假人名称已被使用\n\n💡 请换一个名称: " + botName;
      }

      // Create bot UUID (deterministic based on name)
      UUID botUuid = UUID.nameUUIDFromBytes(("Bot-" + botName).getBytes(StandardCharsets.UTF_8));

      // Bind bot
      Future<Void> bindFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        plugin.getDatabaseManager().markPlayerAsBot(botUuid, ownerUuid, botName);
        return null;
      });
      bindFuture.get();

      String limitStr = maxBots > 0 ? (currentBotCount + 1) + "/" + maxBots : "无限制";
      return "✅ 假人绑定成功！\n"
          + "━━━━━━━━━━━━━━━\n"
          + "🤖 名称: " + botName + "\n"
          + "🆔 UUID: " + botUuid.toString().substring(0, 8) + "...\n"
          + "📊 数量: " + limitStr;

    } catch (InterruptedException | ExecutionException e) {
      logError("Error handling bind bot for QQ " + qq, e);
      return "⚠️ 处理绑定假人请求时发生错误，请稍后重试";
    }
  }

  private String handleUnbindBot(long qq, String botName) {
    try {
      // Get player UUID by QQ
      Future<UUID> uuidFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().findUuidByQq(qq);
      });

      UUID ownerUuid = uuidFuture.get();
      if (ownerUuid == null) {
        return "❌ 你尚未绑定QQ\n\n💡 请先使用「绑定 <验证码>」绑定你的账号";
      }

      // Get bot UUID by name
      Future<UUID> botUuidFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().getBotUuidByName(botName);
      });

      UUID botUuid = botUuidFuture.get();
      if (botUuid == null) {
        return "❌ 假人不存在\n\n💡 找不到名为「" + botName + "」的假人";
      }

      // Verify ownership
      Future<UUID> actualOwnerFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().getBotOwner(botUuid);
      });

      UUID actualOwner = actualOwnerFuture.get();
      if (actualOwner == null || !actualOwner.equals(ownerUuid)) {
        return "❌ 无法解绑\n\n💡 假人「" + botName + "」不属于你";
      }

      // Unbind bot
      Future<Void> unbindFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        plugin.getDatabaseManager().deleteBot(botUuid);
        return null;
      });
      unbindFuture.get();

      return "✅ 假人解绑成功！\n"
          + "━━━━━━━━━━━━━━━\n"
          + "🗑️ 已移除: " + botName;

    } catch (InterruptedException | ExecutionException e) {
      logError("Error handling unbind bot for QQ " + qq, e);
      return "⚠️ 处理解绑假人请求时发生错误，请稍后重试";
    }
  }

  private String handleListBots(long qq) {
    try {
      // Get player UUID by QQ
      Future<UUID> uuidFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().findUuidByQq(qq);
      });

      UUID ownerUuid = uuidFuture.get();
      if (ownerUuid == null) {
        return "❌ 你尚未绑定QQ\n\n💡 请先使用「绑定 <验证码>」绑定你的账号";
      }

      // Get bots list
      Future<List<Map<String, String>>> botsFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().getBotsByOwner(ownerUuid);
      });

      List<Map<String, String>> bots = botsFuture.get();
      int maxBots = plugin.getConfig().getInt("binding.max-bots-per-player", 0);

      if (bots.isEmpty()) {
        return "📋 假人列表\n"
            + "━━━━━━━━━━━━━━━\n"
            + "📭 暂无假人\n\n"
            + "💡 使用「/绑定假人 <名称>」添加假人";
      }

      StringBuilder response = new StringBuilder();
      response.append("📋 假人列表\n");
      response.append("━━━━━━━━━━━━━━━\n");

      for (int i = 0; i < bots.size(); i++) {
        Map<String, String> bot = bots.get(i);
        response.append("🤖 ").append(i + 1).append(". ").append(bot.get("bot_name")).append("\n");
      }

      response.append("━━━━━━━━━━━━━━━\n");
      if (maxBots == 0) {
        response.append("🚫 假人功能已禁用");
      } else if (maxBots < 0) {
        response.append("📊 共 ").append(bots.size()).append(" 个 | 无限制");
      } else {
        response.append("📊 共 ").append(bots.size()).append("/").append(maxBots).append(" 个");
      }

      return response.toString();

    } catch (InterruptedException | ExecutionException e) {
      logError("Error handling list bots for QQ " + qq, e);
      return "⚠️ 获取假人列表时发生错误，请稍后重试";
    }
  }

  // ------------------------ Group Members Sync & Notices ------------------------

  /**
   * Refreshes group member list for all configured binding groups using OneBot
   * get_group_member_list action, and stores results into SQLite via DatabaseManager.
   */
  private void refreshGroupMembers(WebSocket conn) {
    try {
      List<Long> groups = getGroupBindingGroupsFromConfig();
      if (groups == null || groups.isEmpty()) {
        logAll("No groups configured for force-group-binding; skipping group member refresh");
        return;
      }

      logAll("Refreshing group member lists for groups: " + groups);
      DatabaseManager db = plugin.getDatabaseManager();

      for (Long groupId : groups) {
        try {
          JsonObject action = new JsonObject();
          action.addProperty("action", "get_group_member_list");
          JsonObject params = new JsonObject();
          params.addProperty("group_id", groupId);
          action.add("params", params);

          JsonObject response = callOneBotAction(conn, action, 10000L);
          if (response == null) {
            logAllWarning("No response for get_group_member_list, group=" + groupId);
            continue;
          }

          String status = getString(response, "status");
          if (!"ok".equalsIgnoreCase(status)) {
            logAllWarning("get_group_member_list failed for group " + groupId
                + ", status=" + status + ", retcode=" + getString(response, "retcode"));
            continue;
          }

          if (!response.has("data") || !response.get("data").isJsonArray()) {
            logAllWarning("get_group_member_list returned no data array for group " + groupId);
            continue;
          }

          JsonArray data = response.getAsJsonArray("data");
          List<Long> members = new java.util.ArrayList<>();
          for (JsonElement el : data) {
            if (el.isJsonObject()) {
              JsonObject obj = el.getAsJsonObject();
              if (obj.has("user_id")) {
                try {
                  long memberQq = obj.get("user_id").getAsLong();
                  members.add(memberQq);
                } catch (Exception ignored) {
                  // Ignore malformed user_id
                }
              }
            }
          }

          logAll("Group " + groupId + " member count from OneBot: " + members.size());
          db.replaceGroupMembers(groupId, members);
        } catch (Exception e) {
          logError("Error refreshing group members for group " + groupId, e);
        }
      }
    } catch (Exception e) {
      logError("Error refreshing group members", e);
    }
  }

  /**
   * Handles OneBot notice events to incrementally update group member cache.
   */
  private void handleNotice(JsonObject event) {
    String noticeType = getString(event, "notice_type");
    if (noticeType == null) {
      return;
    }

    if (!event.has("group_id") || !event.has("user_id")) {
      return;
    }

    long groupId = event.get("group_id").getAsLong();
    long qq = event.get("user_id").getAsLong();

    // Only care about configured binding groups
    List<Long> groups = getGroupBindingGroupsFromConfig();
    if (groups == null || groups.isEmpty() || !groups.contains(groupId)) {
      return;
    }

    DatabaseManager db = plugin.getDatabaseManager();

    switch (noticeType) {
      case "group_increase":
        logAll("Notice: group_increase group=" + groupId + " qq=" + qq);
        db.upsertGroupMember(groupId, qq);
        break;
      case "group_decrease":
        logAll("Notice: group_decrease group=" + groupId + " qq=" + qq);
        db.removeGroupMember(groupId, qq);
        break;
      default:
        // ignore other notices
        break;
    }
  }

  /**
   * Sends a OneBot action and waits for its response (by echo) with timeout.
   */
  private JsonObject callOneBotAction(WebSocket conn, JsonObject action, long timeoutMillis) {
    String echo = "authwithqq-" + System.nanoTime();
    action.addProperty("echo", echo);

    CompletableFuture<JsonObject> future = new CompletableFuture<>();
    pendingActions.put(echo, future);

    try {
      String json = gson.toJson(action);
      logAll("Sending action: " + json);
      conn.send(json);

      return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      logError("Error calling OneBot action", e);
      pendingActions.remove(echo);
      return null;
    }
  }

  /**
   * Reads binding.group-binding-groups or falls back to onebot.allowed-groups.
   */
  private List<Long> getGroupBindingGroupsFromConfig() {
    List<Long> groups = plugin.getConfig().getLongList("binding.group-binding-groups");
    if (groups == null || groups.isEmpty()) {
      groups = plugin.getConfig().getLongList("onebot.allowed-groups");
    }
    return groups;
  }

  /**
   * 群管理员登记命令：登记 <QQ号或@> <MC名>
   * 仅在 allowed-groups 的群聊中由管理员/群主使用。
   */
  private String handleRegisterByAdmin(long targetQq, String mcName) {
    try {
      // 查找玩家 UUID
      Future<UUID> playerFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().getPlayerUuid(mcName);
      });
      UUID playerUuid = playerFuture.get();

      if (playerUuid == null) {
        return "❌ 找不到该 MC 玩家\n\n💡 名称: " + mcName;
      }

      // 检查玩家当前绑定的 QQ
      Future<Long> existingQqFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().getQq(playerUuid);
      });
      long existingQq = existingQqFuture.get();

      if (existingQq == targetQq) {
        return "ℹ️ 此玩家已绑定到该 QQ\n\n"
            + "👤 玩家: " + mcName + "\n"
            + "📱 QQ: " + targetQq;
      }

      if (existingQq != 0 && existingQq != targetQq) {
        return "❌ 该玩家已绑定到其他 QQ\n\n"
            + "👤 玩家: " + mcName + "\n"
            + "📱 当前绑定 QQ: " + existingQq + "\n"
            + "💡 如需更改，请先处理原绑定";
      }

      // 检查 QQ 绑定上限
      int maxAccountsPerQq = plugin.getConfig().getInt("binding.max-accounts-per-qq", 1);
      Future<Integer> accountCountFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        return plugin.getDatabaseManager().getAccountCountByQq(targetQq);
      });
      int currentAccountCount = accountCountFuture.get();

      if (currentAccountCount >= maxAccountsPerQq) {
        return "❌ 该 QQ 绑定数量已达上限\n\n"
            + "📱 QQ: " + targetQq + "\n"
            + "📊 限制: " + maxAccountsPerQq + " 个账号";
      }

      // 执行绑定
      Future<Void> bindFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        plugin.getDatabaseManager().updateBinding(playerUuid, targetQq);
        return null;
      });
      bindFuture.get();

      return "✅ 登记成功！\n"
          + "━━━━━━━━━━━━━━━\n"
          + "👤 玩家: " + mcName + "\n"
          + "🆔 UUID: " + playerUuid.toString().substring(0, 8) + "...\n"
          + "📱 QQ: " + targetQq + "\n"
          + "💡 该玩家今后进服将视为已绑定";

    } catch (InterruptedException | ExecutionException e) {
      logError("Error handling register-by-admin for QQ " + targetQq + " mc=" + mcName, e);
      return "⚠️ 处理登记命令时发生错误，请稍后重试";
    }
  }

  // ------------------------ Helpers ------------------------

  private boolean isGroupAllowed(long groupId) {
    List<Long> allowedGroups = plugin.getConfig().getLongList("onebot.allowed-groups");
    if (allowedGroups == null || allowedGroups.isEmpty()) {
      // Empty list means no groups allowed
      return false;
    }
    return allowedGroups.contains(groupId);
  }

  private String getString(JsonObject obj, String key) {
    return obj.has(key) && obj.get(key).isJsonPrimitive()
        ? obj.get(key).getAsString()
        : null;
  }

  private String extractTokenFromQuery(String query) {
    if (query == null || query.isEmpty()) {
      return null;
    }
    String[] parts = query.split("&");
    for (String part : parts) {
      String[] kv = part.split("=", 2);
      if (kv.length == 2 && "access_token".equals(kv[0])) {
        return kv[1];
      }
    }
    return null;
  }

  /**
   * Extracts message text from OneBot message field.
   * Supports both string format and array format.
   */
  private String extractMessageText(JsonElement messageElement) {
    if (messageElement == null) {
      return null;
    }

    // String format
    if (messageElement.isJsonPrimitive() && messageElement.getAsJsonPrimitive().isString()) {
      return messageElement.getAsString();
    }

    // Array format (CQCode style)
    if (messageElement.isJsonArray()) {
      JsonArray arr = messageElement.getAsJsonArray();
      StringBuilder sb = new StringBuilder();
      for (JsonElement el : arr) {
        if (el.isJsonObject()) {
          JsonObject obj = el.getAsJsonObject();
          String type = getString(obj, "type");
          if ("text".equals(type) && obj.has("data")) {
            JsonObject data = obj.getAsJsonObject("data");
            if (data.has("text")) {
              sb.append(data.get("text").getAsString());
            }
          }
        } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
          sb.append(el.getAsString());
        }
      }
      return sb.toString();
    }

    return null;
  }

  /**
   * Sends a reply back to QQ via OneBot action frame.
   */
  private void sendReply(WebSocket conn, JsonObject event, String message,
      boolean isGroup, boolean isPrivate, long qq, long groupId) {
    try {
      JsonObject action = new JsonObject();
      JsonObject params = new JsonObject();

      if (isGroup) {
        action.addProperty("action", "send_group_msg");
        params.addProperty("group_id", groupId);
      } else if (isPrivate) {
        action.addProperty("action", "send_private_msg");
        params.addProperty("user_id", qq);
      } else {
        // Fallback: try private
        action.addProperty("action", "send_private_msg");
        params.addProperty("user_id", qq);
      }

      params.addProperty("message", message);
      action.add("params", params);

      // Optional: echo for debugging
      action.addProperty("echo", "authwithqq-" + System.currentTimeMillis());

      String json = gson.toJson(action);
      logCommand("Sending reply to QQ " + qq + (isGroup ? " in group " + groupId : " (private)"));
      logAll("Action payload: " + json);
      conn.send(json);
    } catch (Exception e) {
      logError("Failed to send reply", e);
    }
  }
}

