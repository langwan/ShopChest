package de.epiceric.shopchest.listeners;

import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyUniverse;
import com.sk89q.worldguard.bukkit.RegionContainer;
import com.sk89q.worldguard.bukkit.RegionQuery;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.wasteofplastic.askyblock.ASkyBlockAPI;
import com.wasteofplastic.askyblock.Island;
import de.epiceric.shopchest.ShopChest;
import de.epiceric.shopchest.config.Config;
import de.epiceric.shopchest.external.PlotSquaredShopFlag;
import de.epiceric.shopchest.external.WorldGuardShopFlag;
import de.epiceric.shopchest.language.LanguageUtils;
import de.epiceric.shopchest.language.Message;
import de.epiceric.shopchest.nms.Hologram;
import de.epiceric.shopchest.shop.Shop;
import de.epiceric.shopchest.utils.Callback;
import de.epiceric.shopchest.utils.Permissions;
import de.epiceric.shopchest.utils.ShopUtils;
import de.epiceric.shopchest.utils.Utils;
import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;

import pl.islandworld.api.IslandWorldApi;
import us.talabrek.ultimateskyblock.api.IslandInfo;

import java.util.ArrayList;

public class ChestProtectListener implements Listener {

    private ShopChest plugin;
    private ShopUtils shopUtils;
    private WorldGuardPlugin worldGuard;

    public ChestProtectListener(ShopChest plugin, WorldGuardPlugin worldGuard) {
        this.plugin = plugin;
        this.shopUtils = plugin.getShopUtils();
        this.worldGuard = worldGuard;
    }

    private void remove(final Shop shop, final Block b, final Player p) {
        if (shop.getInventoryHolder() instanceof DoubleChest) {
            DoubleChest dc = (DoubleChest) shop.getInventoryHolder();
            final Chest l = (Chest) dc.getLeftSide();
            final Chest r = (Chest) dc.getRightSide();

            Location loc = (b.getLocation().equals(l.getLocation()) ? r.getLocation() : l.getLocation());
            final Shop newShop = new Shop(shop.getID(), plugin, shop.getVendor(), shop.getProduct(), loc, shop.getBuyPrice(), shop.getSellPrice(), shop.getShopType());

            shopUtils.removeShop(shop, true, new Callback<Void>(plugin) {
                @Override
                public void onResult(Void result) {
                    newShop.create(true);
                    shopUtils.addShop(newShop, true);
                    for (Player player : shop.getLocation().getWorld().getPlayers()) {
                        shopUtils.updateShops(player, true);
                    }
                }
            });
        } else {
            shopUtils.removeShop(shop, true);
            plugin.debug(String.format("%s broke %s's shop (#%d)", p.getName(), shop.getVendor().getName(), shop.getID()));
            p.sendMessage(LanguageUtils.getMessage(Message.SHOP_REMOVED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        final Block b = e.getBlock();

        if (shopUtils.isShop(b.getLocation())) {
            final Shop shop = shopUtils.getShop(e.getBlock().getLocation());
            Player p = e.getPlayer();

            if (p.isSneaking() && Utils.hasAxeInHand(p)) {
                plugin.debug(String.format("%s tries to break %s's shop (#%d)", p.getName(), shop.getVendor().getName(), shop.getID()));

                if (shop.getShopType() == Shop.ShopType.ADMIN) {
                    if (p.hasPermission(Permissions.REMOVE_ADMIN)) {
                        remove(shop, b, p);
                        return;
                    }
                } else {
                    if (shop.getVendor().getUniqueId().equals(p.getUniqueId()) || p.hasPermission(Permissions.REMOVE_OTHER)) {
                        remove(shop, b, p);
                        return;
                    }
                }
            }

            if (shop.getItem() != null) {
                shop.getItem().resetForPlayer(p);
            }

            e.setCancelled(true);
            e.getPlayer().sendMessage(LanguageUtils.getMessage(Message.CANNOT_BREAK_SHOP));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (Config.explosionProtection) {
            ArrayList<Block> bl = new ArrayList<>(e.blockList());
            for (Block b : bl) {
                if (b.getType().equals(Material.CHEST) || b.getType().equals(Material.TRAPPED_CHEST)) {
                    if (shopUtils.isShop(b.getLocation())) e.blockList().remove(b);
                }
            }
        }
    }

    private BlockFace getNeighborFacing(Type chestType, BlockFace facing) {
        switch (facing) {
            case NORTH:
                return chestType == Type.LEFT ? BlockFace.EAST : BlockFace.WEST;
            case EAST:
                return chestType == Type.LEFT ? BlockFace.SOUTH : BlockFace.NORTH;
            case SOUTH:
                return chestType == Type.LEFT ? BlockFace.WEST : BlockFace.EAST;
            case WEST:
                return chestType == Type.LEFT ? BlockFace.NORTH : BlockFace.SOUTH;
            default:
                return null;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        final Player p = e.getPlayer();
        final Block b = e.getBlockPlaced();

        if (b.getType().equals(Material.CHEST) || b.getType().equals(Material.TRAPPED_CHEST)) {

            Chest c = (Chest) b.getState();
            org.bukkit.block.data.type.Chest data = (org.bukkit.block.data.type.Chest) c.getBlockData();

            if (data.getType() != Type.SINGLE) {
                BlockFace neighborFacing = getNeighborFacing(data.getType(), data.getFacing());
                Block b2 = b.getRelative(neighborFacing);

                if (shopUtils.isShop(b.getLocation()) || shopUtils.isShop(b2.getLocation())) {
                    final Shop shop = shopUtils.getShop(b2.getLocation());

                    plugin.debug(String.format("%s tries to extend %s's shop (#%d)", p.getName(), shop.getVendor().getName(), shop.getID()));

                    boolean externalPluginsAllowed = true;

                    if (plugin.hasWorldGuard() && Config.enableWorldGuardIntegration) {
                        RegionContainer container = worldGuard.getRegionContainer();
                        RegionQuery query = container.createQuery();
                        externalPluginsAllowed = query.testState(b.getLocation(), p, WorldGuardShopFlag.CREATE_SHOP);
                    }

                    if (externalPluginsAllowed && plugin.hasTowny() && Config.enableTownyIntegration) {
                        TownBlock townBlock = TownyUniverse.getTownBlock(b.getLocation());
                        if (townBlock != null) {
                            try {
                                Town town = townBlock.getTown();
                                for (Resident resident : town.getResidents()) {
                                    if (resident.getName().equals(p.getName())) {
                                        if (resident.isMayor()) {
                                            externalPluginsAllowed = (Config.townyShopPlotsMayor.contains(townBlock.getType().name()));
                                        } else if (resident.isKing()) {
                                            externalPluginsAllowed = (Config.townyShopPlotsKing.contains(townBlock.getType().name()));
                                        } else {
                                            externalPluginsAllowed = (Config.townyShopPlotsResidents.contains(townBlock.getType().name()));
                                        }
                                        break;
                                    }
                                }
                            } catch (Exception ex) {
                                plugin.debug(ex);
                            }
                        }
                    }

                    if (externalPluginsAllowed && plugin.hasPlotSquared() && Config.enablePlotsquaredIntegration) {
                        com.intellectualcrafters.plot.object.Location loc =
                                new com.intellectualcrafters.plot.object.Location(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());

                        externalPluginsAllowed = Utils.isFlagAllowedOnPlot(loc.getOwnedPlot(), PlotSquaredShopFlag.CREATE_SHOP, p);
                    }

                    if (externalPluginsAllowed && plugin.hasUSkyBlock() && Config.enableUSkyblockIntegration) {
                        IslandInfo islandInfo = plugin.getUSkyBlock().getIslandInfo(b.getLocation());
                        if (islandInfo != null) {
                            externalPluginsAllowed = islandInfo.getMembers().contains(p.getName()) || islandInfo.getLeader().equals(p.getName());
                        }
                    }

                    if (externalPluginsAllowed && plugin.hasASkyBlock() && Config.enableASkyblockIntegration) {
                        Island island = ASkyBlockAPI.getInstance().getIslandAt(b.getLocation());
                        if (island != null) {
                            if (island.getOwner() == null) {
                                externalPluginsAllowed = island.getMembers().contains(p.getUniqueId());
                            } else {
                                externalPluginsAllowed = island.getMembers().contains(p.getUniqueId()) || island.getOwner().equals(p.getUniqueId());
                            }
                        }
                    }

                    if (externalPluginsAllowed && plugin.hasIslandWorld() && Config.enableIslandWorldIntegration && IslandWorldApi.isInitialized()) {
                        if (b.getWorld().getName().equals(IslandWorldApi.getIslandWorld().getName())) {
                            externalPluginsAllowed = IslandWorldApi.canBuildOnLocation(p, b.getLocation(), true);
                        }
                    }

                    if (externalPluginsAllowed && plugin.hasGriefPrevention() && Config.enableGriefPreventionIntegration) {
                        Claim claim = plugin.getGriefPrevention().dataStore.getClaimAt(b.getLocation(), false, null);
                        if (claim != null) {
                            externalPluginsAllowed = claim.allowContainers(p) == null;
                        }
                    }

                    if (externalPluginsAllowed || p.hasPermission(Permissions.EXTEND_PROTECTED)) {
                        if (shop.getVendor().getUniqueId().equals(p.getUniqueId()) || p.hasPermission(Permissions.EXTEND_OTHER)) {
                            if (b.getRelative(BlockFace.UP).getType() == Material.AIR) {
                                final Shop newShop = new Shop(shop.getID(), plugin, shop.getVendor(), shop.getProduct(), shop.getLocation(), shop.getBuyPrice(), shop.getSellPrice(), shop.getShopType());

                                shopUtils.removeShop(shop, true, new Callback<Void>(plugin) {
                                    @Override
                                    public void onResult(Void result) {
                                        newShop.create(true);
                                        shopUtils.addShop(newShop, true);
                                        plugin.debug(String.format("%s extended %s's shop (#%d)", p.getName(), shop.getVendor().getName(), shop.getID()));
                                        for (Player player : shop.getLocation().getWorld().getPlayers()) {
                                            shopUtils.updateShops(player, true);
                                        }
                                    }
                                });
                            } else {
                                e.setCancelled(true);
                                p.sendMessage(LanguageUtils.getMessage(Message.CHEST_BLOCKED));
                            }
                        } else {
                            e.setCancelled(true);
                            p.sendMessage(LanguageUtils.getMessage(Message.NO_PERMISSION_EXTEND_OTHERS));
                        }
                    } else {
                        e.setCancelled(true);
                        p.sendMessage(LanguageUtils.getMessage(Message.NO_PERMISSION_EXTEND_PROTECTED));
                    }

                }

            }

        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHologramDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof ArmorStand) {
            if (Hologram.isPartOfHologram((ArmorStand) e.getEntity())) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemMove(InventoryMoveItemEvent e) {
        if (Config.hopperProtection) {
            if ((e.getSource().getType().equals(InventoryType.CHEST)) && (!e.getInitiator().getType().equals(InventoryType.PLAYER))) {

                if (e.getSource().getHolder() instanceof DoubleChest) {
                    DoubleChest dc = (DoubleChest) e.getSource().getHolder();
                    Chest r = (Chest) dc.getRightSide();
                    Chest l = (Chest) dc.getLeftSide();

                    if (shopUtils.isShop(r.getLocation()) || shopUtils.isShop(l.getLocation())) e.setCancelled(true);

                } else if (e.getSource().getHolder() instanceof Chest) {
                    Chest c = (Chest) e.getSource().getHolder();

                    if (shopUtils.isShop(c.getLocation())) e.setCancelled(true);
                }

            }
        }
    }

}
