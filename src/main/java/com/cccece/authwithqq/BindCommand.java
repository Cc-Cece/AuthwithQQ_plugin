package com.cccece.authwithqq;

import java.util.Objects; // Placed before net.kyori.adventure... for CustomImportOrder
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
      sender.sendMessage(Component.text("This command is for players only.", NamedTextColor.RED));
      return true;
    }

    long qq = plugin.getDatabaseManager().getQq(player.getUniqueId());
    if (qq != 0) {
      sender.sendMessage(Component.text("You are already bound to QQ: " + qq,
          NamedTextColor.YELLOW));
    } else {
      String message = Objects.requireNonNullElse(
          plugin.getConfig().getString("messages.bind-prompt", "Please bind your QQ"),
          "Please bind your QQ").replace("%code%", player.getUniqueId().toString());
      LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();
      sender.sendMessage(serializer.deserialize(message));
    }
    return true;
  }
}
