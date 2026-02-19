package com.cccece.authwithqq;

import java.util.Objects;
import java.util.UUID; // Added for UUID handling
import java.util.Map; // Added for MessageManager
import com.cccece.authwithqq.util.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent; // ADDED
import net.kyori.adventure.text.format.NamedTextColor; // ADDED
import net.kyori.adventure.text.format.TextDecoration; // ADDED
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Handles the in-game /bind command for players.
 */
public class BindCommand implements CommandExecutor {
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

    // Handle /bind getcode command
    if (args.length == 1 && args[0].equalsIgnoreCase("getcode")) {
      long qq = plugin.getDatabaseManager().getQq(player.getUniqueId());
      if (qq != 0) { // Player is already bound
        player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.already-bound-to-this-qq")); // Reusing this message for simplicity
        return true;
      }
      plugin.getGuestListener().markGuest(player); // Resend the join prompt message with code/link
      return true;
    }

    // Handle /bind profile command
    if (args.length == 1 && args[0].equalsIgnoreCase("profile")) {
      long qq = plugin.getDatabaseManager().getQq(player.getUniqueId());
      if (qq == 0) { // Player is not bound
        player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.not-bound", Map.of("%player%", player.getName()))); // Needs new message config
        return true;
      }

      String sessionToken = plugin.createProfileSessionToken(player.getUniqueId());
      // Construct the web link for profile editing
      String externalAddress = plugin.getConfig().getString("server.external-address", "127.0.0.1");
      int port = plugin.getConfig().getInt("server.port", 8081);
      String profileLink = String.format("http://%s:%d/web/profile.html?token=%s", externalAddress, port, sessionToken);

      // Create clickable link component
      Component clickableLink = Component.text(profileLink)
                                         .color(NamedTextColor.BLUE)
                                         .decorate(TextDecoration.UNDERLINED)
                                         .clickEvent(ClickEvent.openUrl(profileLink));
      
      // Send message to player (needs new message config)
      player.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.profile-link-prefix", Map.of("%link%", profileLink)).append(clickableLink)); // Needs new message config with prefix for link
      return true;
    }

    // All in-game binding logic is removed, players must bind via web
    sender.sendMessage(plugin.getMessageManager().getMessage("messages.bind-command.web-bind-only")); // New message config
    return true;
  }
}

