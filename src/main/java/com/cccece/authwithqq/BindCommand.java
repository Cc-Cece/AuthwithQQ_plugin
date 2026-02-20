package com.cccece.authwithqq;

import java.nio.charset.StandardCharsets;
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
        if (maxBots > 0 && plugin.getDatabaseManager().getBotCountForOwner(ownerUuid) >= maxBots) {
            player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.bot.limit-reached", Collections.singletonMap("%limit%", String.valueOf(maxBots))));
            return;
        }

        UUID botUuid = UUID.nameUUIDFromBytes(("Bot-" + botName).getBytes(StandardCharsets.UTF_8));
        
        plugin.getDatabaseManager().markPlayerAsBot(botUuid, ownerUuid, botName);
        
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
      completions.add("bot");
      return filter(completions, args[0]);
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
