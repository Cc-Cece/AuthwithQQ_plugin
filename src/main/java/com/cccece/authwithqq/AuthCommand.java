package com.cccece.authwithqq;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets; // Added for bot UUID generation
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID; // Added for UUID handling
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import com.cccece.authwithqq.util.MessageManager;
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
      sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.no-permission"));
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
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.reload-success"));
        return true;

      case "csv":
        if (args.length < 2) {
          sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.command-usage.csv"));
          return true;
        }
        handleCsvCommand(sender, args[1].toLowerCase());
        return true;

      case "whitelist":
        if (args.length < 3) {
          sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.command-usage.whitelist"));
          return true;
        }
        handleWhitelistCommand(sender, args[1].toLowerCase(), args[2]);
        return true;

      case "bind":
        if (args.length < 3) {
          sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.command-usage.bind"));
          return true;
        }
        handleBindCommand(sender, args[1], args[2]);
        return true;
      
      case "bot":
        if (args.length < 4) {
          sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.command-usage.bot-add"));
          return true;
        }
        if ("add".equalsIgnoreCase(args[1])) {
          handleBotAddCommand(sender, args[2], args[3]);
        } else {
          sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.command-usage.bot-add"));
        }
        return true;

      default:
        sendHelpMessage(sender);
        return true;
    }
  }

  private void sendHelpMessage(CommandSender sender) {
    sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.help.header"));
    sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.help.reload"));
    sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.help.csv"));
    sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.help.whitelist"));
    sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.help.bind"));
    sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.help.bot-add"));
  }

  private void handleCsvCommand(CommandSender sender, String action) {
    if ("export".equals(action)) {
      File file = new File(plugin.getDataFolder(), "export.csv");
      try {
        plugin.getCsvManager().exportCsv(file);
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.csv.export-success", Collections.singletonMap("%file%", file.getName())));
      } catch (IOException e) {
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.csv.export-fail", Collections.singletonMap("%error%", e.getMessage())));
      }
    } else if ("import".equals(action)) {
      File file = new File(plugin.getDataFolder(), "import.csv");
      if (!file.exists()) {
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.csv.import-file-not-found"));
        return;
      }
      try {
        plugin.getCsvManager().importCsv(file);
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.csv.import-success", Collections.singletonMap("%file%", file.getName())));
      } catch (IOException e) {
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.csv.import-fail", Collections.singletonMap("%error%", e.getMessage())));
      }
    } else {
      sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.command-usage.csv"));
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
          sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.whitelist.add-success", Collections.singletonMap("%player%", playerName)));
        } else {
          sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.whitelist.already-whitelisted", Collections.singletonMap("%player%", playerName)));
        }
      } else if ("remove".equals(action)) {
        if (whitelistedPlayers.remove(playerName)) {
          changed = true;
          sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.whitelist.remove-success", Collections.singletonMap("%player%", playerName)));
        } else {
          sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.whitelist.not-whitelisted", Collections.singletonMap("%player%", playerName)));
        }
      } else {
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.command-usage.whitelist"));
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
      UUID tempPlayerUuid = plugin.getDatabaseManager().getPlayerUuid(playerName);
      if (tempPlayerUuid == null) {
        // Try to get UUID from Bukkit if player is online or known
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
          tempPlayerUuid = offlinePlayer.getUniqueId();
        }
      }
      final UUID playerUuid = tempPlayerUuid;

      if (playerUuid == null) {
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bind.player-not-found", new HashMap<String, String>() {{ put("%player%", playerName); }}));
        return;
      }

      final long qq;
      try {
        qq = Long.parseLong(qqString);
      } catch (NumberFormatException e) {
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bind.invalid-qq", new HashMap<String, String>() {{ put("%qq%", qqString); }}));
        return;
      }

      plugin.getDatabaseManager().updateBinding(playerUuid, qq);
      sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bind.force-bind-success", new HashMap<String, String>() {{ put("%player%", playerName); put("%uuid%", playerUuid.toString()); put("%qq%", String.valueOf(qq)); }}));
    });
  }

  private void handleBotAddCommand(CommandSender sender, String ownerName, String botName) {
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      UUID tempOwnerUuid = plugin.getDatabaseManager().getPlayerUuid(ownerName);
      if (tempOwnerUuid == null) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(ownerName);
        if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
          tempOwnerUuid = offlinePlayer.getUniqueId();
        }
      }
      final UUID ownerUuid = tempOwnerUuid;

      if (ownerUuid == null) {
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bot.owner-not-found", new HashMap<String, String>() {{ put("%owner%", ownerName); }}));
        return;
      }

      // Check owner is bound to a QQ
      long ownerQq = plugin.getDatabaseManager().getQq(ownerUuid);
      if (ownerQq == 0) {
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bot.owner-qq-not-bound", new HashMap<String, String>() {{ put("%owner%", ownerName); }}));
        return;
      }

      // Check bot limit
      final int maxBotsPerPlayer = plugin.getConfig().getInt("binding.max-bots-per-player", 0);
      int currentBotCount = plugin.getDatabaseManager().getBotCountForOwner(ownerUuid);
      if (maxBotsPerPlayer > 0 && currentBotCount >= maxBotsPerPlayer) {
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bot.bot-limit-reached", new HashMap<String, String>() {{ put("%owner%", ownerName); put("%limit%", String.valueOf(maxBotsPerPlayer)); }}));
        return;
      }

      // Generate a UUID for the bot (deterministic based on name for consistency if needed, or random)
      final UUID botUuid = UUID.nameUUIDFromBytes(("Bot-" + botName).getBytes(StandardCharsets.UTF_8));

      plugin.getDatabaseManager().markPlayerAsBot(botUuid, ownerUuid, botName);
      sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bot.add-success", new HashMap<String, String>() {{ put("%bot_name%", botName); put("%bot_uuid%", botUuid.toString()); put("%owner_name%", ownerName); put("%owner_uuid%", ownerUuid.toString()); }}));
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
