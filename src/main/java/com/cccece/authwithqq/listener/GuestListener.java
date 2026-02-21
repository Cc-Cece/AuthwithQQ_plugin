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
    Player player = event.getPlayer();
    UUID uuid = player.getUniqueId();
    guestCache.remove(uuid);
    originalGameModes.remove(uuid);
    // plugin.invalidateCode(uuid); // Invalidate code from centralized manager on quit
    
    // Check if NPC statistics should be skipped
    boolean skipNpcStatistics = plugin.getConfig().getBoolean("guest-mode.skip-npc-statistics", true);
    boolean isNpc = player.hasMetadata("NPC");
    
    // Record player quit for statistics (skip if NPC and skip-npc-statistics is enabled)
    if (!(skipNpcStatistics && isNpc)) {
      plugin.recordPlayerQuit(uuid, player.getName());
    }
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
    
    String playerName = player.getName();
    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
      plugin.getDatabaseManager().addGuest(uuid, playerName);
      plugin.getDatabaseManager().ensurePlayerUuidUpdated(playerName, uuid);
      long qq = plugin.getDatabaseManager().getQqByName(playerName);

      plugin.getServer().getScheduler().runTask(plugin, () -> {
        // Re-check player is still online after async task
        Player onlinePlayer = plugin.getServer().getPlayer(uuid);
        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
          // Player disconnected during async task, skip processing
          return;
        }
        
        // --- NEW: Whitelist and Fake Player Bypass Logic ---
        List<String> whitelistedPlayers = plugin.getConfig().getStringList("whitelist.players");
        boolean bypassOps = plugin.getConfig().getBoolean("whitelist.bypass-ops", true);
        boolean allowFakePlayers = plugin.getConfig().getBoolean("guest-mode.allow-fake-players", false);
        boolean skipNpcStatistics = plugin.getConfig().getBoolean("guest-mode.skip-npc-statistics", true);

        // Check for whitelisted players
        if (whitelistedPlayers.contains(onlinePlayer.getName())) {
          plugin.getLogger().info(onlinePlayer.getName() + " is whitelisted, skipping verification.");
          unmarkGuest(uuid);
          // Record player join for statistics (even if whitelisted)
          plugin.recordPlayerJoin(uuid, onlinePlayer.getName());
          return;
        }

        // Check for ops if bypass-ops is enabled
        // Check both isOp() and permission system (for compatibility with permission plugins like LuckPerms)
        if (bypassOps && (onlinePlayer.isOp() || onlinePlayer.hasPermission("*") || onlinePlayer.hasPermission("bukkit.*"))) {
          plugin.getLogger().info(onlinePlayer.getName() + " is an operator, skipping verification.");
          unmarkGuest(uuid);
          // Record player join for statistics (even if op)
          plugin.recordPlayerJoin(uuid, onlinePlayer.getName());
          return;
        }

        // Check for fake players (Citizens NPC)
        boolean isNpc = onlinePlayer.hasMetadata("NPC");
        if (allowFakePlayers && isNpc) { // Assuming "NPC" metadata for Citizens
          plugin.getLogger().info(onlinePlayer.getName() + " is a fake player, skipping verification.");
          unmarkGuest(uuid);
          // Record player join for statistics only if not skipping NPC statistics
          if (!skipNpcStatistics) {
            plugin.recordPlayerJoin(uuid, onlinePlayer.getName());
          }
          return;
        }
        // --- END NEW LOGIC ---

        if (qq == 0) {
          markGuest(onlinePlayer); // Call the new markGuest method
        } else {
          // Player is bound, clear any existing guest status
          unmarkGuest(uuid); // Ensure any lingering effects are removed
          onlinePlayer.sendMessage(plugin.getMessageManager().getMessage("messages.guest.welcome", Map.of("%player%", onlinePlayer.getName())));
        }
        
        // Record player join for statistics
        plugin.recordPlayerJoin(uuid, onlinePlayer.getName());
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
    String verificationCode = plugin.getOrCreateCode(player.getName());

    // Construct the web link for binding, using name as the only identifier (UUID not needed)
    String externalAddress = plugin.getConfig().getString("server.external-address", "127.0.0.1");
    int port = plugin.getConfig().getInt("server.port", 8081);
    int codeExpiration = plugin.getConfig().getInt("binding.code-expiration", 300);
    // URL encode player name to handle special characters
    String encodedName = java.net.URLEncoder.encode(player.getName(), java.nio.charset.StandardCharsets.UTF_8);
    String webLink = String.format("http://%s:%d/web/auth.html?name=%s&verificationCode=%s&expire=%d", externalAddress, port, encodedName, verificationCode, codeExpiration);

    // Determine how to display verification information based on config
    String displayMethod = plugin.getConfig().getString("guest-mode.verification-display-method", "BOTH").toUpperCase();

    // Prepare message components
    Component codeComponent = Component.text(verificationCode);
    Component clickableWebLinkComponent = Component.text(webLink)
                                                .color(NamedTextColor.BLUE) // Make link blue
                                                .decorate(TextDecoration.UNDERLINED) // Underline link
                                                .clickEvent(ClickEvent.openUrl(webLink)); // Make it clickable

    Component finalMessageComponent = Component.empty();
    // Read from lang file (zh_CN.yml etc.), not config.yml - so user edits to lang/*.yml take effect
    String baseMessageTemplate = plugin.getMessagesConfig().getString("messages.guest.join-prompt", "&6请绑定您的QQ，验证码：%code%。请访问 %web_link% 进行绑定。");

    // Manually construct the message Component based on displayMethod
    if ("CODE_ONLY".equals(displayMethod)) {
        // Take part before %web_link% (works for any language), strip trailing "visit" phrase, replace %code%
        String[] beforeAndAfter = baseMessageTemplate.split("%web_link%", 2);
        String codeOnlyTemplate = (beforeAndAfter.length > 0) ? beforeAndAfter[0].trim() : baseMessageTemplate;
        // Remove trailing "。请访问" / ". Visit" etc. that expect a link to follow
        if (codeOnlyTemplate.endsWith("。请访问")) {
          codeOnlyTemplate = codeOnlyTemplate.substring(0, codeOnlyTemplate.length() - "。请访问".length());
        } else if (codeOnlyTemplate.endsWith(". Visit")) {
          codeOnlyTemplate = codeOnlyTemplate.substring(0, codeOnlyTemplate.length() - ". Visit".length());
        }
        String msg = codeOnlyTemplate.replace("%code%", verificationCode);
        finalMessageComponent = serializer.deserialize(msg);
    } else if ("WEB_ONLY".equals(displayMethod)) {
        // Remove code part from template (replace %code% placeholder; works for any language)
        String processedTemplate = baseMessageTemplate.replace("%code%", "");
        // Use split and append to insert the clickable link component
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
      sendActionbar(event.getPlayer(), plugin.getOrCreateCode(event.getPlayer().getName()));
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
      sendActionbar(event.getPlayer(), plugin.getOrCreateCode(event.getPlayer().getName()));
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
      sendActionbar((Player) event.getDamager(), plugin.getOrCreateCode(event.getDamager().getName()));
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
      sendActionbar(event.getPlayer(), plugin.getOrCreateCode(event.getPlayer().getName()));
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
      sendActionbar((Player) event.getEntity(), plugin.getOrCreateCode(event.getEntity().getName()));
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
        sendActionbar(event.getPlayer(), plugin.getOrCreateCode(event.getPlayer().getName()));
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