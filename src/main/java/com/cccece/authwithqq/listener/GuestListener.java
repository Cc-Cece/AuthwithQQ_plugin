package com.cccece.authwithqq.listener;

import com.cccece.authwithqq.AuthWithQqPlugin;
import com.cccece.authwithqq.util.MessageManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent; // ADDED
import net.kyori.adventure.text.format.NamedTextColor; // ADDED
import net.kyori.adventure.text.format.TextDecoration; // ADDED
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

import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Handles guest restrictions for players who have not bound their QQ.
 */
public class GuestListener implements Listener {
  private final AuthWithQqPlugin plugin;
  private final Set<UUID> guestCache = new HashSet<>();
  private final Map<UUID, GameMode> originalGameModes = new HashMap<>();
  private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

  /**
   * Initializes the GuestListener.
   *
   * @param plugin The plugin instance.
   */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Plugin instance is a shared service, not meant for defensive copying.")
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
    // plugin.invalidateCode(uuid); // Invalidate code from centralized manager on quit
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
        // --- NEW: Whitelist and Fake Player Bypass Logic ---
        List<String> whitelistedPlayers = plugin.getConfig().getStringList("whitelist.players");
        boolean bypassOps = plugin.getConfig().getBoolean("whitelist.bypass-ops", true);
        boolean allowFakePlayers = plugin.getConfig().getBoolean("guest-mode.allow-fake-players", false);

        // Check for whitelisted players
        if (whitelistedPlayers.contains(player.getName())) {
          plugin.getLogger().info(player.getName() + " is whitelisted, skipping verification.");
          unmarkGuest(uuid);
          return;
        }

        // Check for ops if bypass-ops is enabled
        if (bypassOps && player.isOp()) {
          plugin.getLogger().info(player.getName() + " is an operator, skipping verification.");
          unmarkGuest(uuid);
          return;
        }

        // Check for fake players (Citizens NPC)
        if (allowFakePlayers && player.hasMetadata("NPC")) { // Assuming "NPC" metadata for Citizens
          plugin.getLogger().info(player.getName() + " is a fake player, skipping verification.");
          unmarkGuest(uuid);
          return;
        }
        // --- END NEW LOGIC ---

        if (qq == 0) {
          markGuest(player); // Call the new markGuest method
        } else {
          // Player is bound, clear any existing guest status
          unmarkGuest(uuid); // Ensure any lingering effects are removed
          player.sendMessage(plugin.getMessageManager().getMessage("messages.guest.welcome", Map.of("%player%", player.getName())));
        }
      });
    });
  }

  /**
   * Marks an online player as a guest, applying all necessary restrictions.
   * This method should only be called on the main server thread.
   *
   * @param player The player to mark as guest.
   */
  public void markGuest(Player player) {
    UUID uuid = player.getUniqueId();
    
    // --- NEW: Bot Bypass Logic ---
    // If the player is a bot, unmark them as guest and skip all restrictions.
    // Also consider if guest-mode.allow-fake-players is true as a general bypass.
    boolean allowFakePlayers = plugin.getConfig().getBoolean("guest-mode.allow-fake-players", false);
    if (plugin.getDatabaseManager().isBot(uuid) || (allowFakePlayers && player.hasMetadata("NPC"))) {
        plugin.getLogger().info(player.getName() + " is a bot or fake player, skipping guest restrictions.");
        unmarkGuest(uuid); // Unmark immediately, essentially treating them as bound
        return;
    }
    // --- END NEW LOGIC ---

    guestCache.add(uuid);
    
    // Store original game mode
    originalGameModes.put(uuid, player.getGameMode());

    // Apply configured game mode
    String configuredGameMode = plugin.getConfig().getString("guest-mode.gamemode", "SURVIVAL").toUpperCase();
    try {
      GameMode gm = GameMode.valueOf(configuredGameMode);
      player.setGameMode(gm);
    } catch (IllegalArgumentException e) {
      plugin.getLogger().warning("Invalid game mode configured: " + configuredGameMode
          + ". Defaulting to SURVIVAL for " + player.getName());
      player.setGameMode(GameMode.SURVIVAL);
    }

    // Apply allow-move potion effects
    boolean allowMove = plugin.getConfig().getBoolean("guest-mode.allow-move", true);
    if (!allowMove) {
      player.addPotionEffect(new PotionEffect(
          PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 255, false, false));
      player.addPotionEffect(
          new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE,
              128, false, false));
    }

    // Apply custom potion effects
    List<Map<?, ?>> customPotionEffects = plugin.getConfig().getMapList("guest-mode.potion-effects");
    for (Map<?, ?> effectMap : customPotionEffects) {
      try {
        String typeName = (String) effectMap.get("type");
        int level = (Integer) effectMap.get("level");
        PotionEffectType effectType = PotionEffectType.getByName(typeName);
        if (effectType != null) {
          player.addPotionEffect(new PotionEffect(effectType, Integer.MAX_VALUE, level,
              false, false));
        } else {
          plugin.getLogger().warning("Invalid potion effect type configured: " + typeName);
        }
      } catch (Exception e) {
        plugin.getLogger().warning("Error parsing custom potion effect: "
            + effectMap.toString() + " - " + e.getMessage());
      }
    }

    // Generate and store verification code with expiration logic
    String verificationCode = plugin.getOrCreateCode(uuid); // Use centralized manager

    // Construct the web link for binding, including UUID and name for authentication context
    String externalAddress = plugin.getConfig().getString("server.external-address", "127.0.0.1");
    int port = plugin.getConfig().getInt("server.port", 8081);
    String webLink = String.format("http://%s:%d/web/auth.html?uuid=%s&name=%s&verificationCode=%s", externalAddress, port, uuid.toString(), player.getName(), verificationCode);

    // Determine how to display verification information based on config
    String displayMethod = plugin.getConfig().getString("guest-mode.verification-display-method", "BOTH").toUpperCase();

    // Prepare message components
    Component codeComponent = Component.text(verificationCode);
    Component clickableWebLinkComponent = Component.text(webLink)
                                                .color(NamedTextColor.BLUE) // Make link blue
                                                .decorate(TextDecoration.UNDERLINED) // Underline link
                                                .clickEvent(ClickEvent.openUrl(webLink)); // Make it clickable

    Component finalMessageComponent = Component.empty();
    String baseMessageTemplate = plugin.getConfig().getString("messages.guest.join-prompt", "&6请绑定您的QQ，验证码：%code%。请访问 %web_link% 进行绑定。");

    // Manually construct the message Component based on displayMethod
    if ("CODE_ONLY".equals(displayMethod)) {
        // Remove web_link part from message template
        String processedTemplate = baseMessageTemplate.replace("。请访问 %web_link% 进行绑定。", "");
        finalMessageComponent = plugin.getMessageManager().getMessage(processedTemplate, Map.of("%code%", verificationCode));
    } else if ("WEB_ONLY".equals(displayMethod)) {
        // Remove code part from message template
        String processedTemplate = baseMessageTemplate.replace("，验证码：%code%", "");
        // Use a simple split and append to insert the clickable component
        String[] parts = processedTemplate.split("%web_link%", 2); // Split only once
        if (parts.length > 0) {
            finalMessageComponent = serializer.deserialize(parts[0]);
        }
        finalMessageComponent = finalMessageComponent.append(clickableWebLinkComponent);
        if (parts.length > 1) {
            finalMessageComponent = finalMessageComponent.append(serializer.deserialize(parts[1]));
        }
    } else { // "BOTH" or unknown
        // Construct full message with both code and clickable link
        String[] parts = baseMessageTemplate.split("%code%", 2);
        finalMessageComponent = serializer.deserialize(parts[0]).append(codeComponent);
        
        if (parts.length > 1) {
            String[] parts2 = parts[1].split("%web_link%", 2);
            finalMessageComponent = finalMessageComponent
                .append(serializer.deserialize(parts2[0]))
                .append(clickableWebLinkComponent);
            if (parts2.length > 1) {
                finalMessageComponent = finalMessageComponent.append(serializer.deserialize(parts2[1]));
            }
        }
    }
    
    player.sendMessage(finalMessageComponent);
  }

  /**
   * Prevents guests from chatting if they are in the guest cache.
   *
   * @param event The AsyncPlayerChatEvent.
   */
  @EventHandler
  public void onChat(AsyncPlayerChatEvent event) {
    if (guestCache.contains(event.getPlayer().getUniqueId())) {
      event.setCancelled(true);
      sendActionbar(event.getPlayer(), plugin.getOrCreateCode(event.getPlayer().getUniqueId()));
    }
  }

  /**
   * Handles interaction restrictions for guests.
   * PlayerInteractEvent (physical interaction).
   *
   * @param event The PlayerInteractEvent.
   */
  @EventHandler(priority = EventPriority.LOW)
  public void onInteract(PlayerInteractEvent event) {
    if (guestCache.contains(event.getPlayer().getUniqueId())
        && !plugin.getConfig().getBoolean("guest-mode.allow-interact", true)) {
      event.setCancelled(true);
      sendActionbar(event.getPlayer(), plugin.getOrCreateCode(event.getPlayer().getUniqueId()));
    }
  }

  /**
   * Handles attack restrictions for guests.
   * EntityDamageByEntityEvent (attack).
   *
   * @param event The EntityDamageByEntityEvent.
   */
  @EventHandler(priority = EventPriority.LOW)
  public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
    if (event.getDamager() instanceof Player
        && guestCache.contains(event.getDamager().getUniqueId())
        && !plugin.getConfig().getBoolean("guest-mode.allow-interact", true)) {
      event.setCancelled(true);
      sendActionbar((Player) event.getDamager(), plugin.getOrCreateCode(event.getDamager().getUniqueId()));
    }
    if (event.getEntity() instanceof Player
        && guestCache.contains(event.getEntity().getUniqueId())
        && !plugin.getConfig().getBoolean("guest-mode.allow-interact", true)) {
      event.setCancelled(true);
      // No actionbar for being attacked, only for attacking
    }
  }

  /**
   * Handles item drop restrictions for guests.
   * PlayerDropItemEvent.
   *
   * @param event The PlayerDropItemEvent.
   */
  @EventHandler(priority = EventPriority.LOW)
  public void onPlayerDropItem(PlayerDropItemEvent event) {
    if (guestCache.contains(event.getPlayer().getUniqueId())
        && !plugin.getConfig().getBoolean("guest-mode.allow-interact", true)) {
      event.setCancelled(true);
      sendActionbar(event.getPlayer(), plugin.getOrCreateCode(event.getPlayer().getUniqueId()));
    }
  }

  /**
   * Handles item pickup restrictions for guests.
   * EntityPickupItemEvent.
   *
   * @param event The EntityPickupItemEvent.
   */
  @EventHandler(priority = EventPriority.LOW)
  public void onEntityPickupItem(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player
        && guestCache.contains(event.getEntity().getUniqueId())
        && !plugin.getConfig().getBoolean("guest-mode.allow-interact", true)) {
      event.setCancelled(true);
      sendActionbar((Player) event.getEntity(), plugin.getOrCreateCode(event.getEntity().getUniqueId()));
    }
  }

  /**
   * Handles world change restrictions for guests via PlayerTeleportEvent.
   *
   * @param event The PlayerTeleportEvent.
   */
  @EventHandler(priority = EventPriority.LOW)
  public void onPlayerTeleport(PlayerTeleportEvent event) {
    if (guestCache.contains(event.getPlayer().getUniqueId())
        && !plugin.getConfig().getBoolean("guest-mode.allow-world-change", true)) {
      if (event.getFrom().getWorld() != null
          && event.getTo().getWorld() != null
          && !event.getFrom().getWorld().equals(event.getTo().getWorld())) {
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.getMessageManager().getMessage("messages.guest.world-change-denied"));
      }
    }
  }

  /**
   * Handles world change restrictions for guests via PlayerPortalEvent.
   *
   * @param event The PlayerPortalEvent.
   */
  @EventHandler(priority = EventPriority.LOW)
  public void onPlayerPortal(PlayerPortalEvent event) {
    if (guestCache.contains(event.getPlayer().getUniqueId())
        && !plugin.getConfig().getBoolean("guest-mode.allow-world-change", true)) {
      if (event.getFrom().getWorld() != null
          && event.getTo().getWorld() != null
          && !event.getFrom().getWorld().equals(event.getTo().getWorld())) {
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.getMessageManager().getMessage("messages.guest.world-change-denied"));
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
        sendActionbar(event.getPlayer(), plugin.getOrCreateCode(event.getPlayer().getUniqueId()));
      }
    }
  }

  private void sendActionbar(Player player, String verificationCode) {
    player.sendActionBar(plugin.getMessageManager().getMessage("messages.guest.actionbar-prompt", Map.of("%code%", verificationCode)));
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
      
            Component successComponent = plugin.getMessageManager().getMessage("messages.guest.binding-successful");
      player.sendMessage(successComponent);
      player.showTitle(
          Title.title(successComponent, net.kyori.adventure.text.Component.empty()));
    }
  }

  /**
   * Retrieves the verification code for a given player UUID.
   *
   * @param uuid The UUID of the player.
   * @return The verification code, or null if not found.
   */
  public String getVerificationCode(UUID uuid) {
    return plugin.getOrCreateCode(uuid); // Use centralized manager
  }
}