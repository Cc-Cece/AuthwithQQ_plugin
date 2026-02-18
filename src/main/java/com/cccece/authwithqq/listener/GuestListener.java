package com.cccece.authwithqq.listener;

import com.cccece.authwithqq.AuthWithQqPlugin;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Handles guest restrictions for players who have not bound their QQ.
 */
public class GuestListener implements Listener {
  private final AuthWithQqPlugin plugin;
  private final Set<UUID> guestCache = new HashSet<>();
  private final Map<UUID, GameMode> originalGameModes = new HashMap<>(); // Store original game modes
  private final Map<UUID, String> playerVerificationCodes = new HashMap<>(); // Store generated codes
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
   * Clears the guest cache and stored game modes for a player when they quit.
   *
   * @param event The PlayerQuitEvent.
   */
  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID uuid = event.getPlayer().getUniqueId();
    guestCache.remove(uuid);
    originalGameModes.remove(uuid);
    playerVerificationCodes.remove(uuid);
  }

  /**
   * Checks if a joining player is a guest, applies restrictions, and generates a verification code.
   *
   * @param event The PlayerJoinEvent.
   */
  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    UUID uuid = player.getUniqueId();
    
    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
      plugin.getDatabaseManager().addGuest(uuid, player.getName());
      long qq = plugin.getDatabaseManager().getQq(uuid);

      plugin.getServer().getScheduler().runTask(plugin, () -> {
        if (qq == 0) {
          guestCache.add(uuid);
          
          // Store original game mode
          originalGameModes.put(uuid, player.getGameMode());

          // Apply configured game mode
          String configuredGameMode = plugin.getConfig().getString("guest-mode.gamemode", "SURVIVAL").toUpperCase();
          try {
            GameMode gm = GameMode.valueOf(configuredGameMode);
            player.setGameMode(gm);
          } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid game mode configured: " + configuredGameMode + ". Defaulting to SURVIVAL for " + player.getName());
            player.setGameMode(GameMode.SURVIVAL);
          }

          // Apply allow-move potion effects
          boolean allowMove = plugin.getConfig().getBoolean("guest-mode.allow-move", true);
          if (!allowMove) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 255, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 128, false, false)); // Level 128 for no jump
          }

          // Apply custom potion effects
          List<Map<?, ?>> customPotionEffects = plugin.getConfig().getMapList("guest-mode.potion-effects");
          for (Map<?, ?> effectMap : customPotionEffects) {
            try {
              String typeName = (String) effectMap.get("type");
              int level = (Integer) effectMap.get("level");
              PotionEffectType effectType = PotionEffectType.getByName(typeName);
              if (effectType != null) {
                player.addPotionEffect(new PotionEffect(effectType, Integer.MAX_VALUE, level, false, false));
              } else {
                plugin.getLogger().warning("Invalid potion effect type configured: " + typeName);
              }
            } catch (Exception e) {
              plugin.getLogger().warning("Error parsing custom potion effect: " + effectMap.toString() + " - " + e.getMessage());
            }
          }

          // Generate and store verification code
          String verificationCode = plugin.generateCode();
          playerVerificationCodes.put(uuid, verificationCode);
          String message = plugin.getConfig().getString("messages.guest-join", "Please bind your QQ")
              .replace("%code%", verificationCode);
          player.sendMessage(serializer.deserialize(message));
        } else {
          // Player is bound, clear any existing guest status
          unmarkGuest(uuid); // Ensure any lingering effects are removed
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
    if (guestCache.contains(event.getPlayer().getUniqueId())) {
      // Assuming allowed-commands config is handled by the command listener
      // If CHAT restriction is needed based on config, add it here.
      // For now, if they are a guest, all chat is disallowed unless explicitly allowed.
      // This is implicit from original setup, will rely on command listener for allowed commands.
    }
  }

  /**
   * Handles interaction restrictions for guests.
   * PlayerInteractEvent (physical interaction), EntityDamageByEntityEvent (attack),
   * PlayerDropItemEvent (drop), EntityPickupItemEvent (pickup).
   */
  @EventHandler(priority = EventPriority.LOW)
  public void onInteract(PlayerInteractEvent event) {
    if (guestCache.contains(event.getPlayer().getUniqueId()) && !plugin.getConfig().getBoolean("guest-mode.allow-interact", true)) {
      event.setCancelled(true);
      sendActionbar(event.getPlayer());
    }
  }

  @EventHandler(priority = EventPriority.LOW)
  public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
    if (event.getDamager() instanceof Player && guestCache.contains(event.getDamager().getUniqueId()) && !plugin.getConfig().getBoolean("guest-mode.allow-interact", true)) {
      event.setCancelled(true);
      sendActionbar((Player) event.getDamager());
    }
    if (event.getEntity() instanceof Player && guestCache.contains(event.getEntity().getUniqueId()) && !plugin.getConfig().getBoolean("guest-mode.allow-interact", true)) {
      event.setCancelled(true);
      // No actionbar for being attacked, only for attacking
    }
  }

  @EventHandler(priority = EventPriority.LOW)
  public void onPlayerDropItem(PlayerDropItemEvent event) {
    if (guestCache.contains(event.getPlayer().getUniqueId()) && !plugin.getConfig().getBoolean("guest-mode.allow-interact", true)) {
      event.setCancelled(true);
      sendActionbar(event.getPlayer());
    }
  }

  @EventHandler(priority = EventPriority.LOW)
  public void onEntityPickupItem(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player && guestCache.contains(event.getEntity().getUniqueId()) && !plugin.getConfig().getBoolean("guest-mode.allow-interact", true)) {
      event.setCancelled(true);
      sendActionbar((Player) event.getEntity());
    }
  }

  /**
   * Handles world change restrictions for guests.
   */
  @EventHandler(priority = EventPriority.LOW)
  public void onPlayerTeleport(PlayerTeleportEvent event) {
    if (guestCache.contains(event.getPlayer().getUniqueId()) && !plugin.getConfig().getBoolean("guest-mode.allow-world-change", true)) {
      if (event.getFrom().getWorld() != null && event.getTo().getWorld() != null && !event.getFrom().getWorld().equals(event.getTo().getWorld())) {
        event.setCancelled(true);
        String deniedMessage = plugin.getConfig().getString("messages.world-change-denied", "&c未验证无法离开当前世界！");
        event.getPlayer().sendMessage(serializer.deserialize(deniedMessage));
      }
    }
  }

  @EventHandler(priority = EventPriority.LOW)
  public void onPlayerPortal(PlayerPortalEvent event) {
    if (guestCache.contains(event.getPlayer().getUniqueId()) && !plugin.getConfig().getBoolean("guest-mode.allow-world-change", true)) {
      if (event.getFrom().getWorld() != null && event.getTo().getWorld() != null && !event.getFrom().getWorld().equals(event.getTo().getWorld())) {
        event.setCancelled(true);
        String deniedMessage = plugin.getConfig().getString("messages.world-change-denied", "&c未验证无法离开当前世界！");
        event.getPlayer().sendMessage(serializer.deserialize(deniedMessage));
      }
    }
  }

  /**
   * Prevents guests from using restricted commands.
   *
   * @param event The PlayerCommandPreprocessEvent.
   */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onCommand(PlayerCommandPreprocessEvent event) {
    if (guestCache.contains(event.getPlayer().getUniqueId())) {
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

  private void sendActionbar(Player player) {
    String prompt = plugin.getConfig().getString("messages.bind-prompt", "&6请输入 /绑定 <验证码> 以完成绑定。");
    player.sendActionBar(serializer.deserialize(prompt));
  }

  /**
   * Removes a player from the guest cache, clears restrictions, and restores original game mode.
   *
   * @param uuid The player's UUID.
   */
  public void unmarkGuest(UUID uuid) {
    guestCache.remove(uuid);
    Player player = plugin.getServer().getPlayer(uuid);
    if (player != null) {
      // Clear all potion effects
      for (PotionEffect effect : player.getActivePotionEffects()) {
        player.removePotionEffect(effect.getType());
      }

      // Restore original game mode
      GameMode originalGm = originalGameModes.remove(uuid);
      if (originalGm != null) {
        player.setGameMode(originalGm);
      } else {
        // Fallback to SURVIVAL if original was not recorded (e.g., player joined before plugin enabled)
        player.setGameMode(GameMode.SURVIVAL);
      }
      
      String success = plugin.getConfig().getString("messages.success", "&aBinding successful!");
      player.sendMessage(serializer.deserialize(success));
      player.showTitle(Title.title(serializer.deserialize(success),
          net.kyori.adventure.text.Component.empty()));
    }
  }

  public String getVerificationCode(UUID uuid) {
    return playerVerificationCodes.get(uuid);
  }
}
