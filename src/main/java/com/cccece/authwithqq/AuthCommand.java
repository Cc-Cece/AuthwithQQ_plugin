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
import org.bukkit.entity.Player; // Added for Player class in unbind command
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
          if (args.length < 3) {
              sendHelpMessage(sender);
              return true;
          }
          if ("add".equalsIgnoreCase(args[1]) && args.length == 4) {
              handleBotAddCommand(sender, args[2], args[3]);
          } else if ("unbind".equalsIgnoreCase(args[1]) && args.length == 3) {
              handleBotUnbindCommand(sender, args[2]);
          } else {
              sendHelpMessage(sender);
          }
          return true;

      case "unbind":
        if (args.length < 2) {
          sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.unbind.usage"));
          return true;
        }
        handleUnbindCommand(sender, args[1]);
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
    sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.help.bot-unbind"));
    sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.help.unbind"));
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
      final boolean[] isAddRef = new boolean[1]; // Use array to make it effectively final

      if ("add".equals(action)) {
        if (!whitelistedPlayers.contains(playerName)) {
          whitelistedPlayers.add(playerName);
          changed = true;
          isAddRef[0] = true;
          sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.whitelist.add-success", Collections.singletonMap("%player%", playerName)));
        } else {
          sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.whitelist.already-whitelisted", Collections.singletonMap("%player%", playerName)));
        }
      } else if ("remove".equals(action)) {
        if (whitelistedPlayers.remove(playerName)) {
          changed = true;
          isAddRef[0] = false;
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
        
        // Apply changes immediately to online players
        final boolean finalIsAdd = isAddRef[0];
        Bukkit.getScheduler().runTask(plugin, () -> {
          Player onlinePlayer = Bukkit.getPlayer(playerName);
          if (onlinePlayer != null) {
            if (finalIsAdd) {
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
      plugin.handleBindingChange(playerUuid, qq); // Update player's guest status
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
      if (maxBotsPerPlayer == 0) {
        // 0 means bot adding is disabled
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bot.bot-limit-reached", new HashMap<String, String>() {{ put("%owner%", ownerName); put("%limit%", "0"); }}));
        return;
      } else if (maxBotsPerPlayer > 0 && currentBotCount >= maxBotsPerPlayer) {
        // Positive number means limit check
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bot.bot-limit-reached", new HashMap<String, String>() {{ put("%owner%", ownerName); put("%limit%", String.valueOf(maxBotsPerPlayer)); }}));
        return;
      }
      // Negative number means unlimited, allow adding

      // Validate bot name prefix
      if (!plugin.validateBotName(botName)) {
        String prefix = plugin.getConfig().getString("binding.bot-name-prefix", "");
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bot.invalid-prefix", new HashMap<String, String>() {{ put("%bot_name%", botName); put("%prefix%", prefix); }}));
        return;
      }

      // Generate a UUID for the bot (deterministic based on name for consistency if needed, or random)
      final UUID botUuid = UUID.nameUUIDFromBytes(("Bot-" + botName).getBytes(StandardCharsets.UTF_8));

      plugin.getDatabaseManager().markPlayerAsBot(botUuid, ownerUuid, botName);
      sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bot.add-success", new HashMap<String, String>() {{ put("%bot_name%", botName); put("%bot_uuid%", botUuid.toString()); put("%owner_name%", ownerName); put("%owner_uuid%", ownerUuid.toString()); }}));
    });
  }

    private void handleBotUnbindCommand(CommandSender sender, String botName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID botUuid = plugin.getDatabaseManager().getBotUuidByName(botName);

            if (botUuid == null) {
                sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bot.remove-not-owned", new HashMap<String, String>() {{ put("%bot_name%", botName); }}));
                return;
            }

            plugin.getDatabaseManager().deleteBot(botUuid);
            sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.bot.remove-success", new HashMap<String, String>() {{ put("%bot_name%", botName); }}));
        });
    }

  private void handleUnbindCommand(CommandSender sender, String playerName) {
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      UUID tempPlayerUuid = plugin.getDatabaseManager().getPlayerUuid(playerName);
      if (tempPlayerUuid == null) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
          tempPlayerUuid = offlinePlayer.getUniqueId();
        }
      }
      final UUID playerUuid = tempPlayerUuid;

      if (playerUuid == null) {
        sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.unbind.player-not-found", new HashMap<String, String>() {{ put("%player%", playerName); }}));
        return;
      }

      plugin.getDatabaseManager().updateBinding(playerUuid, 0L); // Set QQ to 0 to unbind

      // If player is online, send message and unmark as guest
      Player onlinePlayer = Bukkit.getPlayer(playerUuid);
      if (onlinePlayer != null) {
        onlinePlayer.sendMessage(plugin.getMessageManager().getMessage("messages.auth.unbind.success", new HashMap<String, String>() {{ put("%player%", playerName); }}));
        plugin.handleBindingChange(playerUuid, 0L); // This will mark them as guest if online
      }

      sender.sendMessage(plugin.getMessageManager().getMessage("messages.auth.unbind.success", new HashMap<String, String>() {{ put("%player%", playerName); }}));
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
      completions.add("unbind"); // Add unbind for tab completion
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
          completions.add("unbind");
          break;
        default:
          break;
      }
      return filter(completions, args[1]);
    }

    if (args.length == 3) {
      switch (args[0].toLowerCase()) {
        case "whitelist": // /auth whitelist <add|remove> <player>
          Bukkit.getOnlinePlayers().forEach(player -> completions.add(player.getName()));
          break;
        case "bind": // /auth bind <player> <qq>
          Bukkit.getOnlinePlayers().forEach(player -> completions.add(player.getName()));
          break;
        case "bot":
             if ("add".equalsIgnoreCase(args[1])) {
                Bukkit.getOnlinePlayers().forEach(player -> completions.add(player.getName()));
             } else if ("unbind".equalsIgnoreCase(args[1])) {
                // This could be slow if there are many bots.
                // For now, no suggestions. A better approach might cache bot names.
             }
            break;
        case "unbind": // /auth unbind <player>
            Bukkit.getOnlinePlayers().forEach(player -> completions.add(player.getName()));
            break;
        default:
          break;
      }
      return filter(completions, args[2]);
    }

    if (args.length == 4 && "bot".equalsIgnoreCase(args[0]) && "add".equalsIgnoreCase(args[1])) {
        completions.add("MyBot");
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

