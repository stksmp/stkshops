package net.stksmp.shop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public final class STKShop extends JavaPlugin implements Listener, TabExecutor {
    private Economy economy;
    private File pricesFile;
    private YamlConfiguration prices;
    private final Map<UUID, PendingPurchase> pending = new HashMap<>();
    private ShardBridge shardBridge;

    private static final int[] CONTENT_SLOTS = {
        10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,
        37,38,39,40,41,42,43
    };

    @Override public void onEnable() {
        saveDefaultConfig();
        loadPrices();
        setupEconomy();
        shardBridge = new ShardBridge();
        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand shop = getCommand("shop");
        if (shop != null) { shop.setExecutor(this); shop.setTabCompleter(this); }
        PluginCommand admin = getCommand("stkshop");
        if (admin != null) admin.setExecutor(this);
        getLogger().info("STKShop enabled. Vault=" + (economy != null) + ", DonutShard API=" + shardBridge.isAvailable());
    }

    private void loadPrices() {
        pricesFile = new File(getDataFolder(), "sell-prices.yml");
        if (!pricesFile.exists()) {
            try { getDataFolder().mkdirs(); saveResource("sell-prices.yml", false); }
            catch (Exception e) { getLogger().warning("Could not copy sell-prices.yml: " + e.getMessage()); }
        }
        prices = YamlConfiguration.loadConfiguration(pricesFile);
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) { economy = null; return; }
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        economy = provider == null ? null : provider.getProvider();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("stkshop")) {
            if (!sender.hasPermission("stkshop.admin")) { msg(sender, "no-permission"); return true; }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig(); loadPrices(); setupEconomy(); shardBridge = new ShardBridge(); msg(sender, "reload"); return true;
            }
            sender.sendMessage(color("&bSTKShop &7- &f/stkshop reload"));
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        openHome(player);
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("stkshop") && args.length == 1 && sender.hasPermission("stkshop.admin")) return Collections.singletonList("reload");
        return Collections.emptyList();
    }

    private void openHome(Player p) {
        MenuHolder holder = new MenuHolder(MenuType.HOME, null, 0);
        Inventory inv = Bukkit.createInventory(holder, 54, color("&3&lSTK SMP &8» &bShop")); holder.inventory = inv;
        fillBorder(inv);
        List<ShopCategory> categories = categories();
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        for (int i=0; i<categories.size() && i<slots.length; i++) {
            ShopCategory c = categories.get(i);
            inv.setItem(slots[i], item(c.icon, "&b&l" + c.name, List.of("&7Browse " + c.name.toLowerCase(Locale.ROOT) + ".", "", "&eClick to open")));
        }
        inv.setItem(49, item(Material.NETHER_STAR, "&b&lShard Shop", List.of("&7Exclusive rewards paid with Shards.", "", "&dClick to browse")));
        inv.setItem(45, moneyHead(p));
        inv.setItem(53, shardBalanceItem(p));
        inv.setItem(48, item(Material.BOOK, "&fShop Information", List.of("&7Buy items using your money.", "&7Exclusive items use DonutShard.", "&7Click an item to confirm your purchase.")));
        p.openInventory(inv);
    }

    private void openCategory(Player p, ShopCategory category, int page) {
        List<Material> items = categoryItems(category);
        int perPage = Math.max(1, Math.min(CONTENT_SLOTS.length, getConfig().getInt("regular-shop.items-per-page", CONTENT_SLOTS.length)));
        int maxPage = Math.max(0, (items.size()-1) / perPage);
        page = Math.max(0, Math.min(page, maxPage));
        MenuHolder holder = new MenuHolder(MenuType.CATEGORY, category, page);
        Inventory inv = Bukkit.createInventory(holder, 54, color("&3&lShop &8» &b" + category.name + " &7("+(page+1)+"/"+(maxPage+1)+")")); holder.inventory=inv;
        fillBorder(inv);
        int start=page*perPage;
        for (int i=0; i<perPage && start+i<items.size(); i++) {
            Material m=items.get(start+i);
            double sell=prices.getDouble(m.name(), 1.0);
            double buy=Math.max(0.01, Math.round(sell*getConfig().getDouble("regular-shop.buy-multiplier",2.5)*100.0)/100.0);
            ItemStack display=item(m, "&b"+pretty(m.name()), List.of("&7Buy price: &a$"+money(buy), "&7Sell reference: &8$"+money(sell), "", "&eLeft-click: buy 1", "&eShift-click: buy 16", "&eRight-click: buy 64"));
            inv.setItem(CONTENT_SLOTS[i], display);
        }
        inv.setItem(45, moneyHead(p));
        inv.setItem(49, item(Material.BARRIER,"&cBack to Shop",List.of("&7Return to shop categories.")));
        inv.setItem(53, shardBalanceItem(p));
        if (page>0) inv.setItem(47,item(Material.ARROW,"&ePrevious Page",List.of("&7Go to page "+page+".")));
        if (page<maxPage) inv.setItem(51,item(Material.ARROW,"&eNext Page",List.of("&7Go to page "+(page+2)+".")));
        p.openInventory(inv);
    }

    private void openShardShop(Player p) {
        MenuHolder holder = new MenuHolder(MenuType.SHARD, null, 0);
        Inventory inv=Bukkit.createInventory(holder,54,color("&5&lSTK SMP &8» &dShard Shop")); holder.inventory=inv; fillBorder(inv);
        int[] slots={10,11,12,13,14,15,16,20,21,22};
        List<ShardProduct> products=shardProducts();
        for(int i=0;i<products.size();i++) {
            ShardProduct product=products.get(i);
            inv.setItem(slots[i], item(product.icon,"&d&l"+product.name,List.of("&7"+product.description,"","&dPrice: &f"+product.price+" Shards","","&eClick to purchase")));
        }
        inv.setItem(45,moneyHead(p)); inv.setItem(49,item(Material.BARRIER,"&cBack to Shop",List.of("&7Return to shop categories."))); inv.setItem(53,shardBalanceItem(p));
        p.openInventory(inv);
    }

    private void openConfirm(Player p, PendingPurchase purchase) {
        pending.put(p.getUniqueId(), purchase);
        MenuHolder holder=new MenuHolder(MenuType.CONFIRM,null,0);
        Inventory inv=Bukkit.createInventory(holder,27,color("&8Confirm Purchase")); holder.inventory=inv;
        for(int i=0;i<27;i++) inv.setItem(i,item(Material.GRAY_STAINED_GLASS_PANE," ",List.of()));
        inv.setItem(11,item(Material.LIME_STAINED_GLASS_PANE,"&a&lYES, PURCHASE",List.of("&7Item: &f"+purchase.name,"&7Cost: &f"+purchase.costLabel,"","&aClick to confirm")));
        inv.setItem(13,item(purchase.icon,"&b&l"+purchase.name,List.of("&7Review your purchase.","&7This action will charge your balance.")));
        inv.setItem(15,item(Material.RED_STAINED_GLASS_PANE,"&c&lNO, CANCEL",List.of("&7Keep your currency.","","&cClick to cancel")));
        p.openInventory(inv);
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory()==null || e.getClickedInventory()!=e.getView().getTopInventory()) return;
        int slot=e.getRawSlot(); ItemStack clicked=e.getCurrentItem(); if(clicked==null || clicked.getType()==Material.AIR) return;
        switch(holder.type) {
            case HOME -> {
                if(slot==49) { openShardShop(p); return; }
                if(slot==45 || slot==53 || slot==48) return;
                List<ShopCategory> cs=categories(); int[] slots={10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
                for(int i=0;i<cs.size() && i<slots.length;i++) if(slots[i]==slot) { openCategory(p,cs.get(i),0); return; }
            }
            case CATEGORY -> {
                if(slot==49) { openHome(p); return; }
                if(slot==47 && holder.page>0) { openCategory(p,holder.category,holder.page-1); return; }
                if(slot==51) { openCategory(p,holder.category,holder.page+1); return; }
                if(slot==45 || slot==53) return;
                int index=Arrays.stream(CONTENT_SLOTS).boxed().toList().indexOf(slot); if(index<0) return;
                List<Material> items=categoryItems(holder.category); int perPage=Math.max(1,Math.min(CONTENT_SLOTS.length,getConfig().getInt("regular-shop.items-per-page",CONTENT_SLOTS.length))); int itemIndex=holder.page*perPage+index;
                if(itemIndex>=items.size()) return;
                Material m=items.get(itemIndex); int quantity=e.getClick()==ClickType.SHIFT_LEFT || e.getClick()==ClickType.SHIFT_RIGHT?16:(e.getClick().isRightClick()?64:1); quantity=Math.min(quantity,m.getMaxStackSize()*64);
                double sell=prices.getDouble(m.name(),1.0); double unit=Math.max(0.01,Math.round(sell*getConfig().getDouble("regular-shop.buy-multiplier",2.5)*100.0)/100.0);
                openConfirm(p,new PendingPurchase(PurchaseType.MONEY_ITEM,m.name(),m,quantity,unit*quantity,"$"+money(unit*quantity),m,"item"));
            }
            case SHARD -> {
                if(slot==49) { openHome(p); return; } if(slot==45 || slot==53) return;
                int[] slots={10,11,12,13,14,15,16,20,21,22}; List<ShardProduct> products=shardProducts();
                for(int i=0;i<products.size();i++) if(slots[i]==slot) { ShardProduct sp=products.get(i); openConfirm(p,new PendingPurchase(PurchaseType.SHARD_PRODUCT,sp.id,null,1,sp.price,sp.price+" Shards",sp.icon,sp.id)); return; }
            }
            case CONFIRM -> {
                PendingPurchase purchase=pending.get(p.getUniqueId());
                if(slot==15) { pending.remove(p.getUniqueId()); p.closeInventory(); msg(p,"cancelled"); return; }
                if(slot==11 && purchase!=null) { p.closeInventory(); pending.remove(p.getUniqueId()); completePurchase(p,purchase); }
            }
        }
    }

    private void completePurchase(Player p, PendingPurchase purchase) {
        if(purchase.type==PurchaseType.MONEY_ITEM) {
            if(economy==null) { msg(p,"no-economy"); return; }
            if(economy.getBalance(p)<purchase.cost) { msg(p,"no-money"); return; }
            if(countFreeSlots(p.getInventory()) < slotsNeeded(purchase.material,purchase.quantity)) { msg(p,"inventory-full"); return; }
            if(!economy.withdrawPlayer(p,purchase.cost).transactionSuccess()) { msg(p,"no-money"); return; }
            giveStacked(p,purchase.material,purchase.quantity);
            p.sendMessage(prefix()+color("&aPurchased &f"+purchase.quantity+"x "+pretty(purchase.material)+" &afor &f$"+money(purchase.cost)+"&a.")); return;
        }
        if(!shardBridge.isAvailable()) { msg(p,"no-shard-api"); return; }
        long cost=(long)purchase.cost;
        long balance=shardBridge.balance(p);
        if(balance<cost) { msg(p,"no-shards"); return; }
        if(purchase.extra.equals("diamond-kit") || purchase.extra.equals("netherite-kit")) {
            List<ItemStack> kit=makeKit(purchase.extra.equals("diamond-kit"));
            if(countFreeSlots(p.getInventory())<kit.size()) { msg(p,"inventory-full"); return; }
            if(!shardBridge.take(p,cost)) { msg(p,"no-shards"); return; }
            for(ItemStack item:kit) p.getInventory().addItem(item);
            p.sendMessage(prefix()+color("&aPurchased &f"+purchase.name+" &afor &d"+cost+" Shards&a.")); return;
        }
        if(!shardBridge.take(p,cost)) { msg(p,"no-shards"); return; }
        String command=getConfig().getString("commands."+purchase.extra,"").replace("{player}",p.getName());
        boolean success=!command.isBlank() && Bukkit.dispatchCommand(Bukkit.getConsoleSender(),command);
        if(!success) {
            shardBridge.give(p,cost,"STKShop refund: command failed");
            p.sendMessage(prefix()+color("&cPurchase command failed. Your Shards were refunded; contact staff if this repeats.")); return;
        }
        p.sendMessage(prefix()+color("&aPurchased &f"+purchase.name+" &afor &d"+cost+" Shards&a."));
    }

    private List<ItemStack> makeKit(boolean diamond) {
        Material armor=diamond?Material.DIAMOND_HELMET:Material.NETHERITE_HELMET;
        Material chest=diamond?Material.DIAMOND_CHESTPLATE:Material.NETHERITE_CHESTPLATE;
        Material legs=diamond?Material.DIAMOND_LEGGINGS:Material.NETHERITE_LEGGINGS;
        Material boots=diamond?Material.DIAMOND_BOOTS:Material.NETHERITE_BOOTS;
        Material sword=diamond?Material.DIAMOND_SWORD:Material.NETHERITE_SWORD;
        Material pick=diamond?Material.DIAMOND_PICKAXE:Material.NETHERITE_PICKAXE;
        Material axe=diamond?Material.DIAMOND_AXE:Material.NETHERITE_AXE;
        Material shovel=diamond?Material.DIAMOND_SHOVEL:Material.NETHERITE_SHOVEL;
        List<ItemStack> out=new ArrayList<>();
        out.add(enchant(armor,Map.of(Enchantment.PROTECTION,4,Enchantment.UNBREAKING,3,Enchantment.MENDING,1,Enchantment.RESPIRATION,3,Enchantment.AQUA_AFFINITY,1)));
        out.add(enchant(chest,Map.of(Enchantment.PROTECTION,4,Enchantment.UNBREAKING,3,Enchantment.MENDING,1,Enchantment.THORNS,3)));
        out.add(enchant(legs,Map.of(Enchantment.PROTECTION,4,Enchantment.UNBREAKING,3,Enchantment.MENDING,1,Enchantment.SWIFT_SNEAK,3)));
        out.add(enchant(boots,Map.of(Enchantment.PROTECTION,4,Enchantment.UNBREAKING,3,Enchantment.MENDING,1,Enchantment.FEATHER_FALLING,4,Enchantment.DEPTH_STRIDER,3,Enchantment.SOUL_SPEED,3)));
        out.add(enchant(sword,Map.of(Enchantment.SHARPNESS,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1,Enchantment.LOOTING,3,Enchantment.FIRE_ASPECT,2,Enchantment.SWEEPING_EDGE,3)));
        out.add(enchant(pick,Map.of(Enchantment.EFFICIENCY,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1,Enchantment.FORTUNE,3)));
        out.add(enchant(axe,Map.of(Enchantment.EFFICIENCY,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1,Enchantment.FORTUNE,3)));
        out.add(enchant(shovel,Map.of(Enchantment.EFFICIENCY,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1,Enchantment.FORTUNE,3)));
        out.add(enchant(Material.BOW,Map.of(Enchantment.POWER,5,Enchantment.UNBREAKING,3,Enchantment.FLAME,1,Enchantment.PUNCH,2,Enchantment.INFINITY,1)));
        out.add(enchant(Material.SHIELD,Map.of(Enchantment.UNBREAKING,3,Enchantment.MENDING,1)));
        out.add(item(Material.GOLDEN_APPLE,"&6Kit Bonus",List.of("&7STK SMP custom kit")));
        return out;
    }

    private ItemStack enchant(Material m, Map<Enchantment,Integer> enchants) {
        ItemStack stack=new ItemStack(m); ItemMeta meta=stack.getItemMeta();
        if(meta!=null) { for(var e:enchants.entrySet()) { try { meta.addEnchant(e.getKey(),e.getValue(),true); } catch(Exception ignored){} } stack.setItemMeta(meta); }
        return stack;
    }

    private List<ShardProduct> shardProducts() {
        return List.of(
            new ShardProduct("common-key","Common Crate Key",Material.TRIPWIRE_HOOK,getConfig().getLong("shard-shop.common-key",400),"One Common key"),
            new ShardProduct("uncommon-key","Uncommon Crate Key",Material.TRIPWIRE_HOOK,getConfig().getLong("shard-shop.uncommon-key",800),"One Uncommon key"),
            new ShardProduct("rare-key","Rare Crate Key",Material.TRIPWIRE_HOOK,getConfig().getLong("shard-shop.rare-key",1600),"One Rare key"),
            new ShardProduct("legendary-key","Legendary Crate Key",Material.TRIPWIRE_HOOK,getConfig().getLong("shard-shop.legendary-key",2400),"One Legendary key"),
            new ShardProduct("vip","VIP Rank",Material.DIAMOND_CHESTPLATE,getConfig().getLong("shard-shop.vip",2500),"Unlock the VIP rank"),
            new ShardProduct("battlepass","Battlepass",Material.EXPERIENCE_BOTTLE,getConfig().getLong("shard-shop.battlepass",4000),"Unlock the Battlepass"),
            new ShardProduct("diamond-3x3-pickaxe","3x3 Diamond Pickaxe",Material.DIAMOND_PICKAXE,getConfig().getLong("shard-shop.diamond-3x3-pickaxe",5000),"Custom 3x3 mining pickaxe"),
            new ShardProduct("netherite-3x3-pickaxe","3x3 Netherite Pickaxe",Material.NETHERITE_PICKAXE,getConfig().getLong("shard-shop.netherite-3x3-pickaxe",10000),"Custom 3x3 mining pickaxe"),
            new ShardProduct("diamond-kit","Fully Enchanted Diamond Kit",Material.DIAMOND_CHESTPLATE,getConfig().getLong("shard-shop.diamond-kit",15000),"Full custom diamond gear and tools"),
            new ShardProduct("netherite-kit","Fully Enchanted Netherite Kit",Material.NETHERITE_CHESTPLATE,getConfig().getLong("shard-shop.netherite-kit",25000),"Full custom netherite gear and tools")
        );
    }

    private List<ShopCategory> categories() {
        List<ShopCategory> all=List.of(
            new ShopCategory("Wood & Plants",Material.OAK_LOG,"wood"), new ShopCategory("Building Blocks",Material.BRICKS,"building"),
            new ShopCategory("Ores & Minerals",Material.DIAMOND,"ores"), new ShopCategory("Tools & Armor",Material.DIAMOND_PICKAXE,"gear"),
            new ShopCategory("Enchanted Books",Material.ENCHANTED_BOOK,"books"), new ShopCategory("Farming",Material.WHEAT,"farming"),
            new ShopCategory("Food",Material.COOKED_BEEF,"food"), new ShopCategory("Mob Drops",Material.BONE,"drops"),
            new ShopCategory("Redstone",Material.REDSTONE,"redstone"), new ShopCategory("Nether",Material.NETHERRACK,"nether"),
            new ShopCategory("End",Material.END_STONE,"end"), new ShopCategory("Potions & Brewing",Material.BREWING_STAND,"potions"),
            new ShopCategory("Transportation",Material.MINECART,"transport"), new ShopCategory("Decorations",Material.PAINTING,"decor"),
            new ShopCategory("Utility",Material.CRAFTING_TABLE,"utility"), new ShopCategory("Miscellaneous",Material.CHEST,"misc")
        );
        return all.stream().filter(c->!categoryItems(c).isEmpty()).collect(Collectors.toList());
    }

    private List<Material> categoryItems(ShopCategory category) {
        List<Material> out=new ArrayList<>();
        for(String key:prices.getKeys(false)) {
            Material m=Material.matchMaterial(key); if(m==null || !m.isItem() || m==Material.AIR) continue;
            if(!getConfig().getBoolean("regular-shop.allow-technical-items",false) && isTechnical(m)) continue;
            if(classify(m).equals(category.key)) out.add(m);
        }
        out.sort(Comparator.comparing(Material::name)); return out;
    }

    private String classify(Material m) {
        String n=m.name();
        if(n.contains("ENCHANTED_BOOK")) return "books";
        if(n.contains("_SPAWN_EGG") || n.contains("BONE") || n.contains("ROTTEN_FLESH") || n.contains("STRING") || n.contains("GUNPOWDER") || n.contains("SLIME_BALL") || n.contains("ENDER_PEARL") || n.contains("PHANTOM_MEMBRANE") || n.contains("SPIDER_EYE") || n.contains("BLAZE_ROD") || n.contains("GHAST_TEAR") || n.contains("SHULKER_SHELL") || n.contains("PRISMARINE_SHARD")) return "drops";
        if(n.contains("LOG") || n.endsWith("_WOOD") || n.endsWith("_PLANKS") || n.endsWith("_SAPLING") || n.contains("LEAVES") || n.contains("HANGING_SIGN") || n.endsWith("_FENCE") || n.endsWith("_FENCE_GATE") || n.endsWith("_BOAT") || n.endsWith("_CHEST_BOAT") || n.endsWith("_RAFT") || n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR") || n.endsWith("_SIGN") || n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE")) return "wood";
        if(n.contains("DIAMOND") || n.contains("EMERALD") || n.contains("IRON_INGOT") || n.contains("IRON_BLOCK") || n.contains("IRON_ORE") || n.contains("GOLD") || n.contains("COPPER") || n.contains("COAL") || n.contains("LAPIS") || n.contains("REDSTONE") || n.contains("QUARTZ") || n.contains("ANCIENT_DEBRIS") || n.contains("NETHERITE") || n.contains("AMETHYST") || n.contains("RAW_IRON") || n.contains("RAW_GOLD") || n.contains("RAW_COPPER")) return "ores";
        if(n.contains("PICKAXE") || n.contains("SWORD") || n.contains("AXE") || n.contains("SHOVEL") || n.contains("HOE") || n.contains("HELMET") || n.contains("CHESTPLATE") || n.contains("LEGGINGS") || n.contains("BOOTS") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("SHIELD") || n.equals("ELYTRA") || n.equals("MACE") || n.equals("SHEARS") || n.equals("FISHING_ROD") || n.equals("FLINT_AND_STEEL")) return "gear";
        if(n.contains("WHEAT") || n.contains("SEEDS") || n.contains("CARROT") || n.contains("POTATO") || n.contains("SUGAR_CANE") || n.contains("KELP") || n.contains("CACTUS") || n.contains("MELON") || n.contains("PUMPKIN") || n.contains("BAMBOO") || n.contains("NETHER_WART") || n.contains("FARMLAND") || n.contains("COMPOSTER") || n.contains("BEEHIVE") || n.contains("HONEY") || n.contains("FLOWER") || n.contains("TULIP") || n.contains("SAPLING") || n.contains("MUSHROOM") || n.contains("FERN") || n.contains("AZALEA") || n.contains("VINE") || n.contains("LILY_PAD")) return "farming";
        if(n.contains("BEEF") || n.contains("PORKCHOP") || n.contains("CHICKEN") || n.contains("MUTTON") || n.contains("RABBIT") || n.contains("COD") || n.contains("SALMON") || n.contains("BREAD") || n.contains("APPLE") || n.contains("POTATO") || n.contains("COOKIE") || n.contains("CAKE") || n.contains("SOUP") || n.contains("STEW") || n.contains("PIE") || n.contains("CARROT") || n.contains("MELON_SLICE") || n.contains("GOLDEN_CARROT") || n.contains("GOLDEN_APPLE")) return "food";
        if(n.contains("PISTON") || n.contains("OBSERVER") || n.contains("REPEATER") || n.contains("COMPARATOR") || n.contains("DISPENSER") || n.contains("DROPPER") || n.contains("HOPPER") || n.contains("REDSTONE") || n.contains("DAYLIGHT_DETECTOR") || n.contains("TARGET") || n.contains("LEVER") || n.contains("NOTE_BLOCK") || n.contains("CRAFTER") || n.contains("TRIPWIRE") || n.contains("RAIL")) return "redstone";
        if(n.startsWith("NETHER_") || n.contains("NETHERRACK") || n.contains("CRIMSON") || n.contains("WARPED") || n.contains("BASALT") || n.contains("BLACKSTONE") || n.contains("SOUL_SAND") || n.contains("SOUL_SOIL") || n.contains("MAGMA") || n.contains("GLOWSTONE") || n.contains("BLAZE") || n.contains("GHAST") || n.contains("WITHER")) return "nether";
        if(n.startsWith("END_") || n.startsWith("PURPUR") || n.contains("CHORUS") || n.contains("SHULKER") || n.contains("DRAGON") || n.contains("ELYTRA") || n.contains("ENDER_")) return "end";
        if(n.contains("POTION") || n.contains("BREWING") || n.contains("CAULDRON") || n.contains("BLAZE_POWDER") || n.contains("FERMENTED_SPIDER_EYE") || n.contains("GLISTERING_MELON") || n.contains("PHANTOM_MEMBRANE") || n.contains("GOLDEN_CARROT")) return "potions";
        if(n.contains("MINECART") || n.equals("RAIL") || n.contains("BOAT") || n.contains("RAFT") || n.contains("SADDLE") || n.contains("LEAD") || n.contains("HORSE_ARMOR") || n.equals("COMPASS") || n.equals("CLOCK") || n.equals("MAP") || n.equals("FILLED_MAP") || n.equals("RECOVERY_COMPASS")) return "transport";
        if(n.contains("WOOL") || n.contains("CARPET") || n.contains("BANNER") || n.contains("BED") || n.contains("CANDLE") || n.contains("GLASS") || n.contains("TERRACOTTA") || n.contains("CONCRETE") || n.contains("DYE") || n.contains("PAINTING") || n.contains("ITEM_FRAME") || n.contains("POTTERY") || n.contains("SHERD") || n.contains("HEAD") || n.contains("SKULL") || n.contains("FLOWER_POT") || n.contains("DECORATED_POT") || n.contains("ARMOR_TRIM")) return "decor";
        if(n.contains("STONE") || n.contains("DIRT") || n.contains("SAND") || n.contains("GRAVEL") || n.contains("BRICK") || n.contains("SLAB") || n.contains("STAIRS") || n.contains("WALL") || n.contains("ORE") || n.contains("BLOCK") || n.contains("PRISMARINE") || n.contains("OBSIDIAN") || n.contains("ICE") || n.contains("SNOW") || n.contains("CLAY") || n.contains("MUD") || n.contains("SANDSTONE") || n.contains("GRANITE") || n.contains("DIORITE") || n.contains("ANDESITE") || n.contains("DEEPSLATE") || n.contains("TUFF") || n.contains("CALCITE") || n.contains("DRIPSTONE") || n.contains("BASALT")) return "building";
        if(n.contains("CRAFTING") || n.contains("FURNACE") || n.contains("ANVIL") || n.contains("CHEST") || n.contains("BARREL") || n.contains("SHULKER_BOX") || n.contains("ENDER_CHEST") || n.contains("BOOKSHELF") || n.contains("ENCHANTING_TABLE") || n.contains("GRINDSTONE") || n.contains("SMITHING_TABLE") || n.contains("CARTOGRAPHY_TABLE") || n.contains("LOOM") || n.contains("LANTERN") || n.contains("TORCH") || n.contains("LADDER") || n.contains("BUCKET") || n.contains("BUNDLE") || n.contains("SPONGE") || n.contains("SPAWNER") || n.contains("EXPERIENCE_BOTTLE")) return "utility";
        return "misc";
    }

    private boolean isTechnical(Material m) {
        String n=m.name();
        return n.equals("AIR") || n.contains("SPAWN_EGG") || n.contains("COMMAND_BLOCK") || n.equals("BARRIER") || n.equals("BEDROCK") || n.equals("STRUCTURE_BLOCK") || n.equals("STRUCTURE_VOID") || n.equals("JIGSAW") || n.equals("DEBUG_STICK") || n.equals("LIGHT") || n.endsWith("_HEAD") && !n.equals("PLAYER_HEAD") || n.endsWith("_WALL_HEAD") || n.endsWith("_WALL_SKULL") || n.contains("PORTAL") || n.contains("PISTON_HEAD") || n.contains("MOVING_PISTON") || n.contains("CAVE_AIR") || n.contains("VOID_AIR");
    }

    private ItemStack moneyHead(Player p) {
        ItemStack head=new ItemStack(Material.PLAYER_HEAD); SkullMeta meta=(SkullMeta)head.getItemMeta();
        if(meta!=null) { meta.setOwningPlayer(p); meta.setDisplayName(color("&a&lYour Money")); double bal=economy==null?0:economy.getBalance(p); meta.setLore(List.of(color("&7Balance: &a$"+money(bal)),color("&8Uses the server money economy"))); head.setItemMeta(meta); }
        return head;
    }
    private ItemStack shardBalanceItem(Player p) {
        long bal=shardBridge!=null && shardBridge.isAvailable()?shardBridge.balance(p):-1;
        return item(Material.AMETHYST_SHARD,"&d&lYour Shards",List.of("&7Balance: &d"+(bal<0?"Unavailable":bal)+" Shards", "&8Powered by DonutShard"));
    }
    private ItemStack item(Material material,String name,List<String> lore) {
        ItemStack stack=new ItemStack(material); ItemMeta meta=stack.getItemMeta(); if(meta!=null) { meta.setDisplayName(color(name)); meta.setLore(lore.stream().map(STKShop::color).toList()); stack.setItemMeta(meta); } return stack;
    }
    private void fillBorder(Inventory inv) {
        ItemStack pane=item(Material.BLUE_STAINED_GLASS_PANE," ",List.of());
        for(int i=0;i<9;i++){inv.setItem(i,pane);inv.setItem(45+i,pane);} for(int r=1;r<5;r++){inv.setItem(r*9,pane);inv.setItem(r*9+8,pane);}
    }
    private int countFreeSlots(PlayerInventory inv) { int count=0; for(ItemStack item:inv.getStorageContents()) if(item==null || item.getType()==Material.AIR) count++; return count; }
    private int slotsNeeded(Material m,int amount) { return (amount+m.getMaxStackSize()-1)/m.getMaxStackSize(); }
    private void giveStacked(Player p,Material m,int amount) { while(amount>0) { int n=Math.min(amount,m.getMaxStackSize()); p.getInventory().addItem(new ItemStack(m,n)); amount-=n; } }
    private static String pretty(String n) { return Arrays.stream(n.toLowerCase(Locale.ROOT).split("_")).map(s->s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1)).collect(Collectors.joining(" ")); }
    private static String money(double amount) { return String.format(Locale.US,"%.2f",amount); }
    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&',s==null?"":s); }
    private String prefix() { return color(getConfig().getString("messages.prefix","&8[&bSTKShop&8] &r")); }
    private void msg(CommandSender s,String key) { s.sendMessage(prefix()+color(getConfig().getString("messages."+key,"&c"+key))); }

    private enum MenuType { HOME, CATEGORY, SHARD, CONFIRM }
    private enum PurchaseType { MONEY_ITEM, SHARD_PRODUCT }
    private static final class MenuHolder implements InventoryHolder {
        final MenuType type; final ShopCategory category; final int page; Inventory inventory;
        MenuHolder(MenuType type,ShopCategory category,int page){this.type=type;this.category=category;this.page=page;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private record ShopCategory(String name,Material icon,String key) {}
    private record ShardProduct(String id,String name,Material icon,long price,String description) {}
    private record PendingPurchase(PurchaseType type,String name,Material material,int quantity,double cost,String costLabel,Material icon,String extra) {}

    /** Runtime reflection bridge so this project does not bundle or replace DonutShard's currency. */
    private final class ShardBridge {
        private Object apiTarget; private Class<?> apiClass; private Method balanceMethod,takeMethod,giveMethod;
        ShardBridge() { discover(); }
        private void discover() {
            for(org.bukkit.plugin.Plugin plugin:Bukkit.getPluginManager().getPlugins()) {
                if(plugin==STKShop.this || !(plugin.getName().toLowerCase(Locale.ROOT).contains("donut") && plugin.getName().toLowerCase(Locale.ROOT).contains("shard"))) continue;
                try(JarFile jar=new JarFile(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI().getPath())) {
                    Enumeration<JarEntry> entries=jar.entries();
                    while(entries.hasMoreElements()) {
                        String entry=entries.nextElement().getName();
                        if(!entry.endsWith("DonutShardAPI.class")) continue;
                        String className=entry.substring(0,entry.length()-6).replace('/','.');
                        Class<?> candidate=Class.forName(className,true,plugin.getClass().getClassLoader());
                        Method b=find(candidate,"getBalance",1); Method t=find(candidate,"take",2); Method g=find(candidate,"give",3);
                        if(b!=null && t!=null) { apiClass=candidate; apiTarget=Modifier.isStatic(b.getModifiers())?null:tryInstance(candidate); balanceMethod=b; takeMethod=t; giveMethod=g; getLogger().info("Hooked DonutShard API: "+className); return; }
                    }
                } catch(Exception ex) { getLogger().fine("Could not inspect shard API in "+plugin.getName()+": "+ex.getMessage()); }
            }
        }
        private Object tryInstance(Class<?> c) { try { return c.getDeclaredConstructor().newInstance(); } catch(Exception e){return null;} }
        private Method find(Class<?> c,String name,int count) { for(Method m:c.getMethods()) if(m.getName().equalsIgnoreCase(name)&&m.getParameterCount()==count) return m; return null; }
        boolean isAvailable(){return apiClass!=null && balanceMethod!=null && takeMethod!=null;}
        long balance(Player p) { try { Object result=invoke(balanceMethod,p); return result instanceof Number n?Math.max(0,n.longValue()):0; } catch(Exception e){getLogger().warning("DonutShard balance API call failed: "+e.getMessage());return 0;} }
        boolean take(Player p,long amount) { try { Object result=invoke(takeMethod,p,amount); return !(result instanceof Boolean b) || b; } catch(Exception e){getLogger().warning("DonutShard take API call failed: "+e.getMessage());return false;} }
        void give(Player p,long amount,String reason) { try { if(giveMethod!=null) invoke(giveMethod,p,amount,reason); } catch(Exception e){getLogger().warning("DonutShard refund API call failed: "+e.getMessage());} }
        private Object invoke(Method method,Object... values) throws Exception {
            Class<?>[] types=method.getParameterTypes(); Object[] args=new Object[types.length];
            for(int i=0;i<types.length;i++) {
                if(i==0) args[i]=adapt(types[i],values[0]);
                else if(i==1) args[i]=adapt(types[i],values[1]);
                else if(i==2) args[i]=adapt(types[i],values[2]);
            }
            return method.invoke(Modifier.isStatic(method.getModifiers())?null:apiTarget,args);
        }
        private Object adapt(Class<?> type,Object value) {
            if(type.isInstance(value)) return value;
            if(value instanceof Player p) { if(type==UUID.class) return p.getUniqueId(); if(type==String.class) return p.getName(); }
            if(value instanceof Number n) { if(type==int.class||type==Integer.class) return n.intValue(); if(type==long.class||type==Long.class) return n.longValue(); if(type==double.class||type==Double.class) return n.doubleValue(); }
            if(type==String.class && value!=null) return String.valueOf(value);
            return value;
        }
    }
}
