package com.cccece.authwithqq;

import java.util.Objects;
import java.util.UUID; // Added for UUID handling
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

    if (args.length < 2) {
      sender.sendMessage(Component.text("用法: /bind <验证码> <QQ号码>", NamedTextColor.RED));
      return true;
    }

    String code = args[0];
    long qq;
    try {
      qq = Long.parseLong(args[1]);
    } catch (NumberFormatException e) {
      sender.sendMessage(Component.text("无效的QQ号码: " + args[1] + "。", NamedTextColor.RED));
      return true;
    }

    // Check if player is already bound
    long existingQq = plugin.getDatabaseManager().getQq(player.getUniqueId());
    if (existingQq != 0 && existingQq == qq) {
      sender.sendMessage(Component.text("你已经绑定到此QQ号码了。", NamedTextColor.YELLOW));
      return true;
    } else if (existingQq != 0 && existingQq != qq) {
      sender.sendMessage(Component.text("你已经绑定到其他QQ号码了，如需解绑请联系管理员。", NamedTextColor.RED));
      return true;
    }

    // Validate the code using the centralized manager
    if (!plugin.isValidCode(code, player.getUniqueId())) {
      sender.sendMessage(Component.text("验证码无效或已过期，请尝试重新加入游戏获取。", NamedTextColor.RED));
      return true;
    }

    // --- Multi-Account Binding Check (similar to Web API) ---
    int maxAccountsPerQq = plugin.getConfig().getInt("binding.max-accounts-per-qq", 1);
    int currentAccountCount = plugin.getDatabaseManager().getAccountCountByQq(qq);
    if (currentAccountCount >= maxAccountsPerQq) {
      sender.sendMessage(Component.text("此QQ号码已达到绑定上限。", NamedTextColor.RED));
      return true;
    }
    // --- END Multi-Account Binding Check ---

    // Perform binding and invalidate code
    plugin.getDatabaseManager().updateBinding(player.getUniqueId(), qq);
    plugin.invalidateCode(player.getUniqueId()); // Invalidate code after successful bind

    // Notify plugin about binding (e.g., clear guest status)
    plugin.handleBindingSuccess(player.getUniqueId()); // This will unmark guest, send success messages

    return true;
  }
}

