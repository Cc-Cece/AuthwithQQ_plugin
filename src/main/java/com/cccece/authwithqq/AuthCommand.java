package com.cccece.authwithqq;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets; // Added for bot UUID generation
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID; // Added for UUID handling
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit; // Added for player lookup
import org.bukkit.OfflinePlayer; // Added for player lookup
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Admin command handler for AuthWithQq.
 */
public class AuthCommand implements CommandExecutor, TabCompleter {
  private final AuthWithQqPlugin plugin;

  /**
   * Initializes the AuthCommand.
   *
   * @param plugin The plugin instance.
   */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Plugin instance is a shared service, not meant for defensive copying.")
  public AuthCommand(AuthWithQqPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                           @NotNull String label, @NotNull String[] args) {
    if (!sender.isOp()) { // Simple OP check for all admin commands
      sender.sendMessage(Component.text("你没有权限执行此命令。", NamedTextColor.RED));
      return true;
    }

    if (args.length == 0) {
      sendHelpMessage(sender);
      return true;
    }

    String subCommand = args[0].toLowerCase();

    switch (subCommand) {
      case "reload":
        plugin.reloadConfig();
        sender.sendMessage(Component.text("配置已重载。", NamedTextColor.GREEN));
        return true;

      case "csv":
        if (args.length < 2) {
          sender.sendMessage(Component.text("用法: /auth csv <export|import>", NamedTextColor.RED));
          return true;
        }
        handleCsvCommand(sender, args[1].toLowerCase());
        return true;

      case "whitelist":
        if (args.length < 3) {
          sender.sendMessage(Component.text("用法: /auth whitelist <add|remove> <player>", NamedTextColor.RED));
          return true;
        }
        handleWhitelistCommand(sender, args[1].toLowerCase(), args[2]);
        return true;

      case "bind":
        if (args.length < 3) {
          sender.sendMessage(Component.text("用法: /auth bind <player> <qq>", NamedTextColor.RED));
          return true;
        }
        handleBindCommand(sender, args[1], args[2]);
        return true;
      
      case "bot":
        if (args.length < 4) {
          sender.sendMessage(Component.text("用法: /auth bot add <owner_name> <bot_name>", NamedTextColor.RED));
          return true;
        }
        if ("add".equalsIgnoreCase(args[1])) {
          handleBotAddCommand(sender, args[2], args[3]);
        } else {
          sender.sendMessage(Component.text("用法: /auth bot add <owner_name> <bot_name>", NamedTextColor.RED));
        }
        return true;

      default:
        sendHelpMessage(sender);
        return true;
    }
  }

  private void sendHelpMessage(CommandSender sender) {
    sender.sendMessage(Component.text("--- AuthWithQq Admin Commands ---", NamedTextColor.YELLOW));
    sender.sendMessage(Component.text("/auth reload - 重载插件配置。", NamedTextColor.AQUA));
    sender.sendMessage(Component.text("/auth csv <export|import> - 导出/导入玩家数据。", NamedTextColor.AQUA));
    sender.sendMessage(Component.text("/auth whitelist <add|remove> <player> - 管理白名单玩家。", NamedTextColor.AQUA));
    sender.sendMessage(Component.text("/auth bind <player> <qq> - 强制绑定玩家QQ。", NamedTextColor.AQUA));
    sender.sendMessage(Component.text("/auth bot add <owner> <bot_name> - 绑定假人到玩家。", NamedTextColor.AQUA));
  }

  private void handleCsvCommand(CommandSender sender, String action) {
    if ("export".equals(action)) {
      File file = new File(plugin.getDataFolder(), "export.csv");
      try {
        plugin.getCsvManager().exportCsv(file);
        String message = "数据已导出到 " + file.getName();
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
      } catch (IOException e) {
        sender.sendMessage(Component.text("导出失败: " + e.getMessage(), NamedTextColor.RED));
      }
    } else if ("import".equals(action)) {
      File file = new File(plugin.getDataFolder(), "import.csv");
      if (!file.exists()) {
        sender.sendMessage(Component.text("文件 import.csv 未在插件文件夹中找到！", NamedTextColor.RED));
        return;
      }
      try {
        plugin.getCsvManager().importCsv(file);
        String message = "数据已从 " + file.getName() + " 导入。";
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
      } catch (IOException e) {
        sender.sendMessage(Component.text("导入失败: " + e.getMessage(), NamedTextColor.RED));
      }
    } else {
      sender.sendMessage(Component.text("用法: /auth csv <export|import>", NamedTextColor.RED));
    }
  }

  private void handleWhitelistCommand(CommandSender sender, String action, String playerName) {
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      List<String> whitelistedPlayers = plugin.getConfig().getStringList("whitelist.players");
      boolean changed = false;

      if ("add".equals(action)) {
        if (!whitelistedPlayers.contains(playerName)) {
          whitelistedPlayers.add(playerName);
          changed = true;
          sender.sendMessage(Component.text("玩家 " + playerName + " 已添加到白名单。", NamedTextColor.GREEN));
        } else {
          sender.sendMessage(Component.text("玩家 " + playerName + " 已经在白名单中。", NamedTextColor.YELLOW));
        }
      } else if ("remove".equals(action)) {
        if (whitelistedPlayers.remove(playerName)) {
          changed = true;
          sender.sendMessage(Component.text("玩家 " + playerName + " 已从白名单移除。", NamedTextColor.GREEN));
        } else {
          sender.sendMessage(Component.text("玩家 " + playerName + " 不在白名单中。", NamedTextColor.YELLOW));
        }
      } else {
        sender.sendMessage(Component.text("用法: /auth whitelist <add|remove> <player>", NamedTextColor.RED));
        return;
      }

      if (changed) {
        plugin.getConfig().set("whitelist.players", whitelistedPlayers);
        plugin.saveConfig();
        plugin.reloadConfig(); // Reload config to ensure in-memory list is updated
      }
    });
  }

  private void handleBindCommand(CommandSender sender, String playerName, String qqString) {
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      UUID playerUuid = plugin.getDatabaseManager().getPlayerUuid(playerName);
      if (playerUuid == null) {
        // Try to get UUID from Bukkit if player is online or known
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
          playerUuid = offlinePlayer.getUniqueId();
        }
      }

      if (playerUuid == null) {
        sender.sendMessage(Component.text("找不到玩家 " + playerName + "。", NamedTextColor.RED));
        return;
      }

      long qq;
      try {
        qq = Long.parseLong(qqString);
      } catch (NumberFormatException e) {
        sender.sendMessage(Component.text("无效的QQ号码: " + qqString + "。", NamedTextColor.RED));
        return;
      }

      plugin.getDatabaseManager().updateBinding(playerUuid, qq);
      sender.sendMessage(Component.text("已强制绑定玩家 " + playerName + " (" + playerUuid + ") 到 QQ " + qq + "。", NamedTextColor.GREEN));
    });
  }

  private void handleBotAddCommand(CommandSender sender, String ownerName, String botName) {
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      UUID ownerUuid = plugin.getDatabaseManager().getPlayerUuid(ownerName);
      if (ownerUuid == null) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(ownerName);
        if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
          ownerUuid = offlinePlayer.getUniqueId();
        }
      }

      if (ownerUuid == null) {
        sender.sendMessage(Component.text("找不到所有者玩家 " + ownerName + "。", NamedTextColor.RED));
        return;
      }

      // Check owner is bound to a QQ
      long ownerQq = plugin.getDatabaseManager().getQq(ownerUuid);
      if (ownerQq == 0) {
        sender.sendMessage(Component.text("所有者 " + ownerName + " 未绑定QQ，无法绑定假人。", NamedTextColor.RED));
        return;
      }

      // Check bot limit
      int maxBotsPerPlayer = plugin.getConfig().getInt("binding.max-bots-per-player", 0);
      int currentBotCount = plugin.getDatabaseManager().getBotCountForOwner(ownerUuid);
      if (maxBotsPerPlayer > 0 && currentBotCount >= maxBotsPerPlayer) {
        sender.sendMessage(Component.text("所有者 " + ownerName + " 已达到假人绑定上限 (" + maxBotsPerPlayer + ")。", NamedTextColor.RED));
        return;
      }

      // Generate a UUID for the bot (deterministic based on name for consistency if needed, or random)
      UUID botUuid = UUID.nameUUIDFromBytes(("Bot-" + botName).getBytes(StandardCharsets.UTF_8));

      plugin.getDatabaseManager().markPlayerAsBot(botUuid, ownerUuid, botName);
      sender.sendMessage(Component.text("已绑定假人 " + botName + " (" + botUuid + ") 到所有者 " + ownerName + " (" + ownerUuid + ")。", NamedTextColor.GREEN));
    });
  }


  @Override
  public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                              @NotNull Command command,
                                              @NotNull String alias, @NotNull String[] args) {
    if (!sender.isOp()) { // Only ops get tab completion for admin commands
      return Collections.emptyList();
    }

    List<String> completions = new ArrayList<>();
    if (args.length == 1) {
      completions.add("csv");
      completions.add("reload");
      completions.add("whitelist");
      completions.add("bind");
      completions.add("bot");
      return filter(completions, args[0]);
    }

    if (args.length == 2) {
      switch (args[0].toLowerCase()) {
        case "csv":
          completions.add("export");
          completions.add("import");
          break;
        case "whitelist":
          completions.add("add");
          completions.add("remove");
          break;
        case "bot":
          completions.add("add");
          break;
        default:
          break;
      }
      return filter(completions, args[1]);
    }

    if (args.length == 3) {
      switch (args[0].toLowerCase()) {
        case "whitelist": // /auth whitelist <add|remove> <player>
          // Suggest online players
          Bukkit.getOnlinePlayers().forEach(player -> completions.add(player.getName()));
          break;
        case "bind": // /auth bind <player> <qq>
          // Suggest online players
          Bukkit.getOnlinePlayers().forEach(player -> completions.add(player.getName()));
          break;
        case "bot": // /auth bot add <owner_name> <bot_name>
            // Suggest online players as owners
            Bukkit.getOnlinePlayers().forEach(player -> completions.add(player.getName()));
            break;
        default:
          break;
      }
      return filter(completions, args[2]);
    }

    // For /auth bind <player> <qq> and /auth bot add <owner_name> <bot_name>
    if (args.length == 4 && "bot".equalsIgnoreCase(args[0]) && "add".equalsIgnoreCase(args[1])) {
        // No specific suggestions for bot_name, but can suggest something generic if needed
        completions.add("MyBot"); // Example suggestion
        return filter(completions, args[3]);
    }

    return Collections.emptyList();
  }

  private List<String> filter(List<String> list, String input) {
    return list.stream()
        .filter(s -> s.toLowerCase().startsWith(input.toLowerCase()))
        .toList();
  }
}
