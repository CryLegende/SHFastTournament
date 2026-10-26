package org.Cry_Legende.sHFastTournament;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TournamentListener implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;
        Player player = event.getPlayer();

        // La BossBar de la session précédente est orpheline après une reconnexion : on en crée une neuve
        active.attachBossBar(player);

        int delaySeconds = Math.max(0, SHFastTournament.getInstance().getConfig().getInt("settings.join-message-delay", 3));
        Bukkit.getScheduler().runTaskLater(SHFastTournament.getInstance(), () -> {
            if (!player.isOnline()) return;
            ActiveTournament current = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
            // Le tournoi peut s'être terminé pendant le délai
            if (current != active) return;
            current.sendJoinAnnouncement(player);
        }, delaySeconds * 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;
        active.detachBossBar(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return; // Vérifie si l'event est annulé
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;
        Tournament tournament = active.getTournament();
        if (!"BLOCK_BREAK".equalsIgnoreCase(tournament.getType())) return;
        if (tournament.getWorldsBlacklist().contains(event.getPlayer().getWorld().getName())) return;
        String blockType = event.getBlock().getType().name().toUpperCase();
        if (!tournament.acceptsObjective(blockType)) return;
        if (tournament.isBlacklisted(blockType)) return;
        active.addScore(event.getPlayer(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCropsBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;
        Tournament tournament = active.getTournament();
        if (!"CROPS_BREAK".equalsIgnoreCase(tournament.getType())) return;
        if (tournament.getWorldsBlacklist().contains(event.getPlayer().getWorld().getName())) return;

        Block block = event.getBlock();
        BlockData data = block.getBlockData();
        // Vérifie que le bloc possède un âge (par exemple pour les cultures)
        if (!(data instanceof Ageable)) return;
        Ageable ageable = (Ageable) data;
        // S'assure que la culture est pleinement mûre
        if (ageable.getAge() != ageable.getMaximumAge()) return;

        String blockType = block.getType().name().toUpperCase();
        if (!tournament.acceptsObjective(blockType)) return;
        if (tournament.isBlacklisted(blockType)) return;
        active.addScore(event.getPlayer(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) return; // Vérifie si l'event est annulé
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;
        Tournament tournament = active.getTournament();
        if (!"BLOCK_PLACE".equalsIgnoreCase(tournament.getType())) return;
        if (tournament.getWorldsBlacklist().contains(event.getPlayer().getWorld().getName())) return;
        String blockType = event.getBlock().getType().name().toUpperCase();
        if (!tournament.acceptsObjective(blockType)) return;
        if (tournament.isBlacklisted(blockType)) return;
        active.addScore(event.getPlayer(), 1);
    }


    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;
        Tournament tournament = active.getTournament();
        if (!"KILL_ENTITY".equalsIgnoreCase(tournament.getType())) return;
        Player killer = event.getEntity().getKiller();
        if (tournament.getWorldsBlacklist().contains(killer.getWorld().getName())) return;
        String entityType = event.getEntity().getType().name().toUpperCase();
        if (!tournament.acceptsObjective(entityType)) return;
        if (tournament.isBlacklisted(entityType)) return;
        active.addScore(killer, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;
        Tournament tournament = active.getTournament();
        if (!"FISHING".equalsIgnoreCase(tournament.getType())) return;
        Player player = event.getPlayer();
        if (tournament.getWorldsBlacklist().contains(player.getWorld().getName())) return;

        // Debug pour l'événement de pêche
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (event.getCaught() == null) {
            return;
        }
        String caughtType;
        if (event.getCaught() instanceof Item) {
            Item caughtItem = (Item) event.getCaught();
            caughtType = caughtItem.getItemStack().getType().name().toUpperCase();
        } else {
            caughtType = event.getCaught().getType().name().toUpperCase();
        }
        if (!tournament.acceptsObjective(caughtType)) return;
        if (tournament.isBlacklisted(caughtType)) {
            return;
        }
        active.addScore(player, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // On vérifie si l'événement est annulé avant toute chose
        if (event.isCancelled()) return;

        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;

        Tournament tournament = active.getTournament();
        if (!"MAKE_DAMAGE".equalsIgnoreCase(tournament.getType())) return;
        if (!(event.getDamager() instanceof Player)) return;

        String targetType = event.getEntity().getType().name().toUpperCase();
        if (tournament.isBlacklisted(targetType)) return;

        Player player = (Player) event.getDamager();
        if (tournament.getWorldsBlacklist().contains(player.getWorld().getName())) return;

        active.addScore(player, event.getDamage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;
        Tournament tournament = active.getTournament();
        if (!"TAKE_DAMAGE".equalsIgnoreCase(tournament.getType())) return;
        Player player = (Player) event.getEntity();
        if (tournament.getWorldsBlacklist().contains(player.getWorld().getName())) return;
        active.addScore(player, event.getFinalDamage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMoveForJump(PlayerMoveEvent event) {
        // Événement très fréquent : on sort d'abord s'il n'y a pas de tournoi de ce type
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;
        Tournament tournament = active.getTournament();
        if (!"JUMP".equalsIgnoreCase(tournament.getType())) return;
        if (event.getTo().getY() - event.getFrom().getY() <= 0.4) return;
        if (tournament.getWorldsBlacklist().contains(event.getPlayer().getWorld().getName())) return;
        active.addScore(event.getPlayer(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMoveForWalk(PlayerMoveEvent event) {
        // Événement très fréquent : on sort d'abord s'il n'y a pas de tournoi de ce type
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;
        Tournament tournament = active.getTournament();
        if (!"WALK".equalsIgnoreCase(tournament.getType())) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        Player player = event.getPlayer();
        if (player.isFlying()) return;  // Ne compte pas si le joueur vole
        if (tournament.getWorldsBlacklist().contains(player.getWorld().getName())) return;
        active.addScore(player, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;
        Tournament tournament = active.getTournament();
        if (!"ITEM_PICKUP".equalsIgnoreCase(tournament.getType())) return;
        Player player = (Player) event.getEntity();
        if (tournament.getWorldsBlacklist().contains(player.getWorld().getName())) return;
        active.addScore(player, 1);
    }

    @EventHandler
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        Player player = event.getPlayer();
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) return;

        Tournament tournament = active.getTournament();
        if (!"SMELT".equalsIgnoreCase(tournament.getType())) return;
        if (tournament.getWorldsBlacklist().contains(player.getWorld().getName())) return;

        String itemType = event.getItemType().name().toUpperCase();
        if (!tournament.acceptsObjective(itemType)) return;

        if (tournament.isBlacklisted(itemType)) return;

        // Ajoute le score en fonction du nombre d'items extraits
        active.addScore(player, event.getItemAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) {
            return;
        }
        Tournament tournament = active.getTournament();
        if (!"CRAFT".equalsIgnoreCase(tournament.getType())) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        if (tournament.getWorldsBlacklist().contains(player.getWorld().getName())) {
            return;
        }
        if (event.getCurrentItem() == null) {
            return;
        }

        String itemType = event.getCurrentItem().getType().name().toUpperCase();
        if (!tournament.acceptsObjective(itemType)) return;
        if (tournament.isBlacklisted(itemType)) {
            return;
        }

        int amount = event.isShiftClick() ? calculateShiftCraftAmount(event) : event.getCurrentItem().getAmount();
        active.addScore(player, amount);
    }

    private int calculateShiftCraftAmount(CraftItemEvent event) {
        Recipe recipe = event.getRecipe();
        ItemStack result = event.getCurrentItem();
        if (result == null) {
            return 0;
        }

        int perCraft = result.getAmount();
        CraftingInventory inv = event.getInventory();
        ItemStack[] matrix = inv.getMatrix();

        // Handle non-standard recipes
        if (!(recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)) {
            return perCraft;
        }

        int maxCrafts = Integer.MAX_VALUE;

        if (recipe instanceof ShapelessRecipe) {
            ShapelessRecipe shapeless = (ShapelessRecipe) recipe;
            Map<Material, Integer> required = new HashMap<>();
            Map<Material, Integer> available = new HashMap<>();

            // Aggregate required ingredients
            for (ItemStack ingredient : shapeless.getIngredientList()) {
                if (ingredient != null && ingredient.getType() != Material.AIR) {
                    required.merge(ingredient.getType(), ingredient.getAmount(), Integer::sum);
                }
            }

            // Aggregate available ingredients
            for (ItemStack slot : matrix) {
                if (slot != null && slot.getType() != Material.AIR) {
                    available.merge(slot.getType(), slot.getAmount(), Integer::sum);
                }
            }

            // Calculate max crafts
            for (Map.Entry<Material, Integer> entry : required.entrySet()) {
                Material material = entry.getKey();
                int needed = entry.getValue();
                int have = available.getOrDefault(material, 0);
                if (needed == 0) continue; // Avoid division by zero
                int crafts = have / needed;
                maxCrafts = Math.min(maxCrafts, crafts);
            }
        } else if (recipe instanceof ShapedRecipe) {
            ShapedRecipe shaped = (ShapedRecipe) recipe;
            Map<Character, ItemStack> ingredientMap = shaped.getIngredientMap();
            String[] shape = shaped.getShape();

            // Normalize shape to handle 2x2 recipes in 3x3 grid
            int height = shape.length;
            int width = height > 0 ? shape[0].length() : 0;

            // Check for recipe alignment (shifted positions)
            for (int rowOffset = 0; rowOffset <= 3 - height; rowOffset++) {
                for (int colOffset = 0; colOffset <= 3 - width; colOffset++) {
                    int craftsForAlignment = Integer.MAX_VALUE;
                    boolean validAlignment = true;

                    for (int row = 0; row < height; row++) {
                        for (int col = 0; col < width; col++) {
                            char key = shape[row].charAt(col);
                            if (key == ' ') continue;

                            ItemStack required = ingredientMap.get(key);
                            if (required == null || required.getType() == Material.AIR) continue;

                            int slotIndex = (row + rowOffset) * 3 + (col + colOffset);
                            ItemStack inSlot = slotIndex < matrix.length ? matrix[slotIndex] : null;
                            int have = (inSlot != null && inSlot.getType() == required.getType()) ? inSlot.getAmount() : 0;
                            int need = required.getAmount();

                            if (need == 0) continue;
                            int crafts = have / need;
                            craftsForAlignment = Math.min(craftsForAlignment, crafts);
                            if (crafts == 0) validAlignment = false;

                        }
                    }

                    if (validAlignment) {
                        maxCrafts = Math.min(maxCrafts, craftsForAlignment);
                    }
                }
            }
        }

        // Handle case where no crafts are possible
        if (maxCrafts == Integer.MAX_VALUE) {
            return 0;
        }

        int totalItems = maxCrafts * perCraft;
        return totalItems;
    }


}
