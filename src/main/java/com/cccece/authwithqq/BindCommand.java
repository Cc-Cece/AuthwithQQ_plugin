package com.cccece.authwithqq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.cccece.authwithqq.util.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Handles the in-game /bind command for players.
 */
public class BindCommand implements CommandExecutor, TabCompleter {
  private final AuthWithQqPlugin plugin;

  /**
   * Initializes the BindCommand.
   *
   * @param plugin The plugin instance.
   */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Plugin instance is a shared service, not meant for defensive copying.")
  public BindCommand(AuthWithQqPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                           @NotNull String label, @NotNull String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.player-only"));
      return true;
    }

    if (args.length > 0) {
        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "getcode":
                handleGetCode(player);
                return true;
            case "profile":
                handleProfile(player);
                return true;
            case "password":
                if (args.length > 1) {
                    String passwordAction = args[1].toLowerCase();
                    if ("set".equals(passwordAction) && args.length > 2) {
                        handleSetPassword(player, args[2]);
                    } else if ("remove".equals(passwordAction)) {
                        handleRemovePassword(player);
                    } else if ("status".equals(passwordAction)) {
                        handlePasswordStatus(player);
                    } else {
                        sendPasswordHelp(player);
                    }
                } else {
                    sendPasswordHelp(player);
                }
                return true;
            case "bot":
                if (args.length > 2) {
                    String botAction = args[1].toLowerCase();
                    String botName = args[2];
                    if ("add".equals(botAction)) {
                        handleAddBot(player, botName);
                    } else if ("remove".equals(botAction)) {
                        handleRemoveBot(player, botName);
                    } else {
                        sendBotHelp(player);
                    }
                } else {
                    sendBotHelp(player);
                }
                return true;
        }
    }

    // Default action for /bind
    player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.web-bind-only"));
    return true;
  }

    private void handleGetCode(Player player) {
        long qq = plugin.getDatabaseManager().getQq(player.getUniqueId());
        if (qq != 0) {
            player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.already-bound-to-this-qq"));
            return;
        }
        plugin.getGuestListener().markGuest(player); // Resend prompt
    }

    private void handleProfile(Player player) {
        long qq = plugin.getDatabaseManager().getQq(player.getUniqueId());
        if (qq == 0) {
            player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.not-bound", Collections.singletonMap("%player%", player.getName())));
            return;
        }

        String sessionToken = plugin.createProfileSessionToken(player.getUniqueId());
        String externalAddress = plugin.getConfig().getString("server.external-address", "127.0.0.1");
        int port = plugin.getConfig().getInt("server.port", 8081);
        String profileLink = String.format("http://%s:%d/web/profile.html?token=%s", externalAddress, port, sessionToken);

        Component clickableLink = Component.text(profileLink)
                                           .color(NamedTextColor.BLUE)
                                           .decorate(TextDecoration.UNDERLINED)
                                           .clickEvent(ClickEvent.openUrl(profileLink));

        player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.profile-link-prefix", Collections.singletonMap("%link%", profileLink)).append(clickableLink));
    }

    private void handleAddBot(Player player, String botName) {
        UUID ownerUuid = player.getUniqueId();
        long ownerQq = plugin.getDatabaseManager().getQq(ownerUuid);
        if (ownerQq == 0) {
            player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.bot.owner-not-bound"));
            return;
        }

        int maxBots = plugin.getConfig().getInt("binding.max-bots-per-player", 0);
        int currentBotCount = plugin.getDatabaseManager().getBotCountForOwner(ownerUuid);
        if (maxBots == 0) {
            // 0 means bot adding is disabled
            player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.bot.limit-reached", Collections.singletonMap("%limit%", "0")));
            return;
        } else if (maxBots > 0 && currentBotCount >= maxBots) {
            // Positive number means limit check
            player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.bot.limit-reached", Collections.singletonMap("%limit%", String.valueOf(maxBots))));
            return;
        }
        // Negative number means unlimited, allow adding

        // Validate bot name prefix
        if (!plugin.validateBotName(botName)) {
            String prefix = plugin.getConfig().getString("binding.bot-name-prefix", "");
            player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.bot.invalid-prefix", Collections.singletonMap("%prefix%", prefix)));
            return;
        }

        plugin.getDatabaseManager().markPlayerAsBot(botName, player.getName());
        
        player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.bot.add-success", Collections.singletonMap("%bot_name%", botName)));
    }

    private void handleRemoveBot(Player player, String botName) {
        UUID ownerUuid = player.getUniqueId();
        UUID botUuid = plugin.getDatabaseManager().getBotUuidByName(botName);

        if (botUuid == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.bot.remove-not-owned", Collections.singletonMap("%bot_name%", botName)));
            return;
        }
        
        UUID actualOwner = plugin.getDatabaseManager().getBotOwner(botUuid);

        if (actualOwner == null || !actualOwner.equals(ownerUuid)) {
            player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.bot.remove-not-owned", Collections.singletonMap("%bot_name%", botName)));
            return;
        }

        plugin.getDatabaseManager().deleteBot(botUuid);
        player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.bot.remove-success", Collections.singletonMap("%bot_name%", botName)));
    }

    private void handleSetPassword(Player player, String password) {
        if (!plugin.getConfig().getBoolean("binding.web-login-enabled", true)) {
            player.sendMessage(Component.text("网页登录功能已禁用", NamedTextColor.RED));
            return;
        }

        int minLength = plugin.getConfig().getInt("binding.web-password-min-length", 6);
        if (password.length() < minLength) {
            player.sendMessage(Component.text("密码长度至少需要 " + minLength + " 个字符", NamedTextColor.RED));
            return;
        }

        String passwordHash = plugin.hashPassword(password);
        plugin.getDatabaseManager().setWebPasswordHash(player.getUniqueId(), passwordHash);
        player.sendMessage(Component.text("密码设置成功！您现在可以在网页端登录", NamedTextColor.GREEN));
    }

    private void handleRemovePassword(Player player) {
        plugin.getDatabaseManager().removeWebPassword(player.getUniqueId());
        player.sendMessage(Component.text("密码已删除", NamedTextColor.GREEN));
    }

    private void handlePasswordStatus(Player player) {
        boolean hasPassword = plugin.getDatabaseManager().hasWebPassword(player.getUniqueId());
        if (hasPassword) {
            player.sendMessage(Component.text("您已设置网页登录密码", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("您尚未设置网页登录密码。使用 /bind password set <密码> 设置", NamedTextColor.YELLOW));
        }
    }

    private void sendPasswordHelp(Player player) {
        player.sendMessage(Component.text("用法: /bind password <set|remove|status> [密码]", NamedTextColor.RED));
    }

    private void sendBotHelp(Player player) {
        // You might want to create a specific help message for /bind bot
        player.sendMessage(Component.text("用法: /bind bot <add|remove> <假人名称>", NamedTextColor.RED));
    }

  @Override
  public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                              @NotNull Command command,
                                              @NotNull String alias, @NotNull String[] args) {
    if (!(sender instanceof Player)) {
      return Collections.emptyList();
    }

    if (args.length == 1) {
      List<String> completions = new ArrayList<>();
      completions.add("getcode");
      completions.add("profile");
      completions.add("password");
      completions.add("bot");
      return filter(completions, args[0]);
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("password")) {
      List<String> completions = new ArrayList<>();
      completions.add("set");
      completions.add("remove");
      completions.add("status");
      return filter(completions, args[1]);
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("bot")) {
      List<String> completions = new ArrayList<>();
      completions.add("add");
      completions.add("remove");
      return filter(completions, args[1]);
    }
    if (args.length == 3 && args[0].equalsIgnoreCase("bot") && args[1].equalsIgnoreCase("remove")) {
        // Suggest bots owned by the player
        // This is a synchronous DB call, which is not ideal for TabCompleter.
        // For a small number of bots, it might be acceptable.
        // A better solution would involve caching.
        List<String> botNames = new ArrayList<>();
        Player player = (Player) sender;
        plugin.getDatabaseManager().getBotsByOwner(player.getUniqueId()).forEach(botMap -> botNames.add(botMap.get("bot_name")));
        return filter(botNames, args[2]);
    }
    return Collections.emptyList();
  }

  private List<String> filter(List<String> list, String input) {
    return list.stream()
        .filter(s -> s.toLowerCase().startsWith(input.toLowerCase()))
        .toList();
  }
}
