package com.cccece.authwithqq;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
    if (args.length == 0) {
      sender.sendMessage(Component.text("Usage: /auth <csv|reload>", NamedTextColor.RED));
      return true;
    }

    if ("reload".equalsIgnoreCase(args[0])) {
      plugin.reloadConfig();
      sender.sendMessage(Component.text("Configuration reloaded.", NamedTextColor.GREEN));
      return true;
    }

    if ("csv".equalsIgnoreCase(args[0])) {
      if (args.length < 2) {
        sender.sendMessage(Component.text("Usage: /auth csv <export|import>", NamedTextColor.RED));
        return true;
      }

      if ("export".equalsIgnoreCase(args[1])) {
        File file = new File(plugin.getDataFolder(), "export.csv");
        try {
          plugin.getCsvManager().exportCsv(file);
          String message = "Data exported to " + file.getName();
          sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
        } catch (IOException e) {
          sender.sendMessage(Component.text("Export failed: " + e.getMessage(),
              NamedTextColor.RED));
        }
        return true;
      }

      if ("import".equalsIgnoreCase(args[1])) {
        File file = new File(plugin.getDataFolder(), "import.csv");
        if (!file.exists()) {
          sender.sendMessage(Component.text("File import.csv not found in plugin folder!",
              NamedTextColor.RED));
          return true;
        }
        try {
          plugin.getCsvManager().importCsv(file);
          String message = "Data imported from " + file.getName();
          sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
        } catch (IOException e) {
          sender.sendMessage(Component.text("Import failed: " + e.getMessage(),
              NamedTextColor.RED));
        }
        return true;
      }
    }

    return false;
  }

  @Override
  public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                              @NotNull Command command,
                                              @NotNull String alias, @NotNull String[] args) {
    if (args.length == 1) {
      List<String> options = new ArrayList<>();
      options.add("csv");
      options.add("reload");
      return filter(options, args[0]);
    }
    if (args.length == 2 && "csv".equalsIgnoreCase(args[0])) {
      List<String> options = new ArrayList<>();
      options.add("export");
      options.add("import");
      return filter(options, args[1]);
    }
    return Collections.emptyList();
  }

  private List<String> filter(List<String> list, String input) {
    return list.stream()
        .filter(s -> s.toLowerCase().startsWith(input.toLowerCase()))
        .toList();
  }
}
