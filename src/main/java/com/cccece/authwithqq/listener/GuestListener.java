package com.cccece.authwithqq.listener;

import com.cccece.authwithqq.AuthWithQqPlugin;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Handles guest restrictions for players who have not bound their QQ.
 */
public class GuestListener implements Listener {
  private final AuthWithQqPlugin plugin;
  private final Set<UUID> guestCache = new HashSet<>();
  private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

  /**
   * Initializes the GuestListener.
   *
   * @param plugin The plugin instance.
   */
  public GuestListener(AuthWithQqPlugin plugin) {
    this.plugin = plugin;
  }

  /**
   * Clears the guest cache for a player when they quit.
   *
   * @param event The PlayerQuitEvent.
   */
  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    guestCache.remove(event.getPlayer().getUniqueId());
  }

  /**
   * Checks if a joining player is a guest.
   *
   * @param event The PlayerJoinEvent.
   */
  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    UUID uuid = player.getUniqueId();
    
    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
      // Database operations (can be blocking)
      plugin.getDatabaseManager().addGuest(uuid, player.getName());
      long qq = plugin.getDatabaseManager().getQq(uuid);

      // Schedule subsequent Bukkit API calls back on the main thread
      plugin.getServer().getScheduler().runTask(plugin, () -> {
        if (qq == 0) {
          guestCache.add(uuid);
          String message = plugin.getConfig().getString("messages.guest-join", "Please bind your QQ")
              .replace("%code%", uuid.toString());
          player.sendMessage(serializer.deserialize(message));
          
          // Apply blindness and extreme slowness
          player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false));
          player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 255, false, false));
        } else {
          String welcome = plugin.getConfig().getString("messages.welcome", "&a欢迎回来, %player%!")
              .replace("%player%", player.getName());
          player.sendMessage(serializer.deserialize(welcome));
        }
      });
    });
  }

  /**
   * Prevents guests from chatting if restricted.
   *
   * @param event The AsyncPlayerChatEvent.
   */
  @EventHandler
  public void onChat(AsyncPlayerChatEvent event) {
    if (isRestricted(event.getPlayer(), "CHAT")) {
      event.setCancelled(true);
      sendActionbar(event.getPlayer());
    }
  }

  /**
   * Prevents guests from interacting if restricted.
   *
   * @param event The PlayerInteractEvent.
   */
  @EventHandler
  public void onInteract(PlayerInteractEvent event) {
    if (isRestricted(event.getPlayer(), "INTERACT")) {
      event.setCancelled(true);
      sendActionbar(event.getPlayer());
    }
  }

  /**
   * Prevents guests from using restricted commands.
   *
   * @param event The PlayerCommandPreprocessEvent.
   */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onCommand(PlayerCommandPreprocessEvent event) {
    if (isRestricted(event.getPlayer(), "COMMAND")) {
      String message = event.getMessage().toLowerCase();
      List<String> allowed = plugin.getConfig().getStringList("guest-mode.allowed-commands");
      
      boolean isAllowed = false;
      for (String cmd : allowed) {
        if (message.startsWith(cmd.toLowerCase())) {
          isAllowed = true;
          break;
        }
      }
      
      if (!isAllowed) {
        event.setCancelled(true);
        sendActionbar(event.getPlayer());
      }
    }
  }

  private boolean isRestricted(Player player, String type) {
    if (!guestCache.contains(player.getUniqueId())) {
      return false;
    }
    List<String> restrictions = plugin.getConfig().getStringList("guest-mode.restrictions");
    return restrictions.contains(type);
  }

  private void sendActionbar(Player player) {
    String prompt = plugin.getConfig().getString("messages.bind-prompt", "&6Please bind your QQ");
    player.sendActionBar(serializer.deserialize(prompt));
  }

  /**
   * Removes a player from the guest cache.
   *
   * @param uuid The player's UUID.
   */
  public void unmarkGuest(UUID uuid) {
    guestCache.remove(uuid);
    Player player = plugin.getServer().getPlayer(uuid);
    if (player != null) {
      player.removePotionEffect(PotionEffectType.BLINDNESS);
      player.removePotionEffect(PotionEffectType.SLOW);
      String success = plugin.getConfig().getString("messages.success", "&aBinding successful!");
      player.sendMessage(serializer.deserialize(success));
      player.showTitle(Title.title(serializer.deserialize(success),
          net.kyori.adventure.text.Component.empty()));
    }
  }
}
