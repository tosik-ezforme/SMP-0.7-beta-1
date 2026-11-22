package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Данжи «Проклятый замок»: редкие локации с компонентами для крафта (навершие, голова топора,
 * основа кинжала, пластина). Хранит список в БД, строит замок пакетами блоков, наполняет сундуки,
 * ставит босса и стражу, периодически обновляет добычу.
 */
public final class DungeonManager implements Listener {

    public static final class Dungeon {
        public final int id;
        public final String world;
        public final int x, y, z;

        Dungeon(int id, String world, int x, int y, int z) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final LegendaryPlugin plugin;
    private final Map<Integer, Dungeon> byId = new LinkedHashMap<>();
    private final Random rnd = new Random();
    private final NamespacedKey keyBoss;
    private boolean busy = false;

    public DungeonManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.keyBoss = new NamespacedKey(plugin, "dungeon_boss");
    }

    // ------------------------------------------------------------ данные

    public void load() {
        byId.clear();
        for (Dungeon d : plugin.db().query("SELECT id, world, x, y, z FROM dungeons ORDER BY id",
                r -> new Dungeon(r.getInt(1), r.getString(2), r.getInt(3), r.getInt(4), r.getInt(5)))) {
            byId.put(d.id, d);
        }
    }

    public Collection<Dungeon> all() {
        return byId.values();
    }

    public Dungeon byId(int id) {
        return byId.get(id);
    }

    public boolean busy() {
        return busy;
    }

    public boolean remove(int id) {
        if (byId.remove(id) == null) {
            return false;
        }
        plugin.db().update("DELETE FROM dungeons WHERE id=?", id);
        return true;
    }

    /** Запуск таймеров: автообновление добычи/босса и автосоздание, если данжей ещё нет. */
    public void start() {
        long min = Math.max(1, plugin.getConfig().getInt("dungeon.refill_minutes", 60));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Dungeon d : new ArrayList<>(byId.values())) {
                refill(d);
            }
        }, min * 1200L, min * 1200L);
        int auto = plugin.getConfig().getInt("dungeon.auto_spawn_count", 0);
        if (auto > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (byId.isEmpty() && !Bukkit.getWorlds().isEmpty()) {
                    plugin.getLogger().info("Данжей нет — создаю " + auto + " случайных.");
                    random(null, auto, Bukkit.getWorlds().get(0));
                }
            }, 200L);
        }
    }

    private void say(CommandSender s, String text) {
        if (s != null) {
            s.sendMessage(Util.color(plugin.getConfig().getString("messages.prefix", "")) + Util.color(text));
        }
    }

    // ------------------------------------------------------------ постройка

    /** Строит замок на поверхности в точке (x, z) и регистрирует его. Возвращает null, если место занято. */
    public Dungeon spawnAt(CommandSender by, World w, int x, int z) {
        for (Dungeon d : byId.values()) {
            if (d.world.equals(w.getName()) && Math.abs(d.x - x) < 45 && Math.abs(d.z - z) < 45) {
                say(by, "&cРядом уже стоит данж #" + d.id + ".");
                return null;
            }
        }
        int ch = DungeonBuilder.HALF >> 4;
        for (int cxk = (x >> 4) - ch - 1; cxk <= (x >> 4) + ch + 1; cxk++) {
            for (int czk = (z >> 4) - ch - 1; czk <= (z >> 4) + ch + 1; czk++) {
                w.getChunkAt(cxk, czk).load(true);
            }
        }
        int gy = w.getHighestBlockYAt(x, z);
        if (gy < w.getMinHeight() + 14 || gy > w.getMaxHeight() - 25) {
            say(by, "&cСлишком высоко или низко для замка.");
            return null;
        }
        long id = plugin.db().insert("INSERT INTO dungeons(world, x, y, z) VALUES(?,?,?,?)", w.getName(), x, gy, z);
        if (id <= 0) {
            say(by, "&cНе удалось записать данж в базу.");
            return null;
        }
        Dungeon d = new Dungeon((int) id, w.getName(), x, gy, z);
        byId.put(d.id, d);
        plugin.adminLog(by == null ? "system" : by.getName(), "dungeon_spawn", "#" + d.id + " " + w.getName() + " " + x + " " + gy + " " + z);

        DungeonBuilder b = new DungeonBuilder(w, x, gy, z);
        b.plan();
        final int total = b.ops.size();
        final int perTick = Math.max(500, plugin.getConfig().getInt("dungeon.blocks_per_tick", 4000));
        say(by, "&7Строю замок #" + d.id + " (" + total + " блоков)...");
        new BukkitRunnable() {
            int idx = 0;

            @Override
            public void run() {
                int end = Math.min(total, idx + perTick);
                for (; idx < end; idx++) {
                    DungeonBuilder.Op op = b.ops.get(idx);
                    w.getBlockAt(op.x(), op.y(), op.z()).setBlockData(op.data(), false);
                }
                if (idx >= total) {
                    cancel();
                    for (Runnable r : b.post) {
                        r.run();
                    }
                    fillChests(d);
                    spawnGuards(d);
                    spawnBoss(d);
                    say(by, "&aДанж &f#" + d.id + " &aпостроен: &f" + x + " " + gy + " " + z);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return d;
    }

    // ------------------------------------------------------------ поиск места

    private boolean suitable(World w, int x, int z) {
        int R = DungeonBuilder.HALF;
        int y = w.getHighestBlockYAt(x, z);
        Block g = w.getBlockAt(x, y, z);
        Material m = g.getType();
        if (g.isLiquid() || m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE
                || m.name().endsWith("LEAVES") || m.name().contains("LOG") || m.name().contains("MUSHROOM")) {
            return false;
        }
        String biome = g.getBiome().name();
        if (biome.contains("OCEAN") || biome.contains("RIVER") || biome.contains("BEACH") || biome.contains("SWAMP")) {
            return false;
        }
        if (y < w.getMinHeight() + 14 || y > w.getMaxHeight() - 40) {
            return false;
        }
        int slope = plugin.getConfig().getInt("dungeon.random.max_slope", 7);
        int[][] pts = {{R, 0}, {-R, 0}, {0, R}, {0, -R}, {R, R}, {-R, -R}, {R, -R}, {-R, R}};
        for (int[] c : pts) {
            int cy = w.getHighestBlockYAt(x + c[0], z + c[1]);
            if (w.getBlockAt(x + c[0], cy, z + c[1]).isLiquid() || Math.abs(cy - y) > slope) {
                return false;
            }
        }
        return true;
    }

    private boolean farEnough(World w, int x, int z, int minDist, int minAltar) {
        for (Dungeon d : byId.values()) {
            if (d.world.equals(w.getName())) {
                long dx = d.x - x, dz = d.z - z;
                if (dx * dx + dz * dz < (long) minDist * minDist) {
                    return false;
                }
            }
        }
        for (AltarManager.Altar a : plugin.altars().all()) {
            if (a.world.equals(w.getName())) {
                long dx = a.x - x, dz = a.z - z;
                if (dx * dx + dz * dz < (long) minAltar * minAltar) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Случайная расстановка нескольких данжей (по одной попытке за 2 тика, чтобы не вешать сервер). */
    public void random(CommandSender by, int count, World w) {
        if (busy) {
            say(by, "&cРасстановка уже идёт, подождите.");
            return;
        }
        final int radius = plugin.getConfig().getInt("dungeon.random.radius", 3000);
        final int minFromSpawn = plugin.getConfig().getInt("dungeon.random.min_from_spawn", 400);
        final int minDist = plugin.getConfig().getInt("dungeon.random.min_distance", 900);
        final int minAltar = plugin.getConfig().getInt("dungeon.random.min_altar_distance", 150);
        final int maxTries = plugin.getConfig().getInt("dungeon.random.max_tries", 500);
        final int cx = w.getSpawnLocation().getBlockX();
        final int cz = w.getSpawnLocation().getBlockZ();
        busy = true;
        say(by, "&7Ищу места для данжей (" + count + " шт.)...");
        new BukkitRunnable() {
            int tries = 0;
            int left = count;
            boolean building = false;

            @Override
            public void run() {
                if (building) {
                    if (!pendingBuild()) {
                        building = false;
                    }
                    return;
                }
                if (left <= 0 || tries >= maxTries) {
                    busy = false;
                    cancel();
                    if (left > 0) {
                        say(by, "&eНашлось места не для всех (осталось " + left + "). Увеличьте dungeon.random.radius.");
                    }
                    say(by, "&aГотово. Список: &f/legendary dungeon list");
                    return;
                }
                tries++;
                double ang = rnd.nextDouble() * Math.PI * 2;
                double dist = minFromSpawn + rnd.nextDouble() * Math.max(1, radius - minFromSpawn);
                int x = cx + (int) Math.round(Math.cos(ang) * dist);
                int z = cz + (int) Math.round(Math.sin(ang) * dist);
                if (!farEnough(w, x, z, minDist, minAltar)) {
                    return;
                }
                w.getChunkAt(x >> 4, z >> 4).load(true);
                if (!suitable(w, x, z)) {
                    return;
                }
                Dungeon d = spawnAt(by, w, x, z);
                if (d != null) {
                    left--;
                    tries = 0;
                    building = true;
                    buildStart = System.currentTimeMillis();
                }
            }

            private long buildStart;

            /** Ждём окончания постройки: стража и босс появляются в конце, подождём фиксированное время. */
            private boolean pendingBuild() {
                return System.currentTimeMillis() - buildStart < 8000L;
            }
        }.runTaskTimer(plugin, 1L, 2L);
    }

    // ------------------------------------------------------------ сундуки

    private static final Material[][] EXTRAS = {
            {Material.BREAD, Material.ARROW, Material.IRON_INGOT, Material.COAL, Material.TORCH},
            {Material.GOLD_INGOT, Material.IRON_INGOT, Material.GOLDEN_APPLE, Material.EMERALD, Material.ENDER_PEARL},
            {Material.DIAMOND, Material.GOLDEN_APPLE, Material.EXPERIENCE_BOTTLE, Material.NETHERITE_SCRAP, Material.EMERALD}
    };

    private ItemStack[] rollLoot(int tier) {
        List<ItemStack> out = new ArrayList<>();
        String base = "dungeon.loot.tier" + tier + ".";
        double chance = plugin.getConfig().getDouble(base + "chance", new double[]{0.3, 0.55, 1.0}[tier]);
        int min = Math.max(1, plugin.getConfig().getInt(base + "min", 1));
        int max = Math.max(min, plugin.getConfig().getInt(base + "max", tier == 2 ? 2 : 1));
        for (String comp : List.of("armor_plate", "mace_head", "dagger_base", "axe_head")) {
            if (rnd.nextDouble() < chance) {
                out.add(plugin.items().createComponent(comp, min + rnd.nextInt(max - min + 1)));
            }
        }
        if (tier == 2 && out.isEmpty()) {
            out.add(plugin.items().createComponent("armor_plate", 1));
        }
        int extras = 2 + tier * 2;
        for (int i = 0; i < extras; i++) {
            Material m = EXTRAS[tier][rnd.nextInt(EXTRAS[tier].length)];
            int amt = switch (m) {
                case ARROW -> 8 + rnd.nextInt(17);
                case BREAD, COAL, TORCH -> 4 + rnd.nextInt(9);
                case IRON_INGOT, GOLD_INGOT -> 2 + rnd.nextInt(5);
                default -> 1 + rnd.nextInt(2);
            };
            out.add(new ItemStack(m, amt));
        }
        return out.toArray(new ItemStack[0]);
    }

    private void fillChests(Dungeon d) {
        World w = Bukkit.getWorld(d.world);
        if (w == null) {
            return;
        }
        for (DungeonBuilder.ChestSpot c : DungeonBuilder.CHESTS) {
            Block b = w.getBlockAt(d.x + c.dx(), d.y + c.dy(), d.z + c.dz());
            if (b.getState() instanceof Chest ch) {
                Inventory inv = ch.getBlockInventory();
                inv.clear();
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < inv.getSize(); i++) {
                    slots.add(i);
                }
                java.util.Collections.shuffle(slots, rnd);
                int k = 0;
                for (ItemStack it : rollLoot(c.tier())) {
                    if (k < slots.size()) {
                        inv.setItem(slots.get(k++), it);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ мобы

    private void equip(LivingEntity e, Material helm, Material chest, Material weapon) {
        EntityEquipment eq = e.getEquipment();
        if (eq == null) {
            return;
        }
        if (helm != null) {
            eq.setHelmet(new ItemStack(helm));
            eq.setHelmetDropChance(0f);
        }
        if (chest != null) {
            eq.setChestplate(new ItemStack(chest));
            eq.setChestplateDropChance(0f);
        }
        if (weapon != null) {
            eq.setItemInMainHand(new ItemStack(weapon));
            eq.setItemInMainHandDropChance(0f);
        }
    }

    private void keep(LivingEntity e, String name) {
        e.setCustomName(Util.color(name));
        e.setCustomNameVisible(false);
        e.setRemoveWhenFarAway(false);
        e.setPersistent(true);
    }

    private void spawnGuards(Dungeon d) {
        World w = Bukkit.getWorld(d.world);
        if (w == null || !plugin.getConfig().getBoolean("dungeon.guards", true)) {
            return;
        }
        int n = 0;
        for (int[] g : DungeonBuilder.GUARDS) {
            Location l = new Location(w, d.x + g[0] + 0.5, d.y + 1, d.z + g[1] + 0.5);
            if ((n++ & 1) == 0) {
                w.spawn(l, Zombie.class, z -> {
                    keep(z, "&7Стражник замка");
                    equip(z, Material.IRON_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.IRON_SWORD);
                });
            } else {
                w.spawn(l, Skeleton.class, s -> {
                    keep(s, "&7Лучник замка");
                    equip(s, Material.IRON_HELMET, null, Material.BOW);
                });
            }
        }
    }

    private boolean bossAlive(Dungeon d, World w) {
        Location c = new Location(w, d.x + 0.5, d.y - 5, d.z + 8.5);
        for (Entity e : w.getNearbyEntities(c, 14, 12, 14)) {
            if (e.getPersistentDataContainer().has(keyBoss, PersistentDataType.BYTE) && !e.isDead()) {
                return true;
            }
        }
        return false;
    }

    private void spawnBoss(Dungeon d) {
        World w = Bukkit.getWorld(d.world);
        if (w == null || !plugin.getConfig().getBoolean("dungeon.boss.enabled", true) || bossAlive(d, w)) {
            return;
        }
        Location l = new Location(w, d.x + DungeonBuilder.BOSS[0] + 0.5, d.y + DungeonBuilder.BOSS[1], d.z + DungeonBuilder.BOSS[2] + 2.5);
        w.spawn(l, WitherSkeleton.class, b -> {
            keep(b, plugin.getConfig().getString("dungeon.boss.name", "&4&lРыцарь-страж склепа"));
            b.setCustomNameVisible(true);
            double hp = plugin.getConfig().getDouble("dungeon.boss.health", 120);
            AttributeInstance a = b.getAttribute(Attribute.MAX_HEALTH);
            if (a != null) {
                a.setBaseValue(hp);
            }
            b.setHealth(hp);
            equip(b, Material.NETHERITE_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_SWORD);
            b.getPersistentDataContainer().set(keyBoss, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Обновить добычу в сундуках и вернуть босса, если он погиб. Не трогает незагруженные чанки без нужды. */
    public void refill(Dungeon d) {
        World w = Bukkit.getWorld(d.world);
        if (w == null) {
            return;
        }
        w.getChunkAt(d.x >> 4, d.z >> 4).load(true);
        w.getChunkAt((d.x + 16) >> 4, (d.z + 16) >> 4).load(true);
        w.getChunkAt((d.x - 16) >> 4, (d.z - 16) >> 4).load(true);
        w.getChunkAt((d.x) >> 4, (d.z + 8) >> 4).load(true);
        fillChests(d);
        spawnBoss(d);
    }

    // ------------------------------------------------------------ убийство босса

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!e.getEntity().getPersistentDataContainer().has(keyBoss, PersistentDataType.BYTE)) {
            return;
        }
        String killer = e.getEntity().getKiller() == null ? "?" : e.getEntity().getKiller().getName();
        int min = Math.max(1, plugin.getConfig().getInt("dungeon.boss.drop_min", 2));
        int max = Math.max(min, plugin.getConfig().getInt("dungeon.boss.drop_max", 4));
        List<String> comps = List.of("armor_plate", "mace_head", "dagger_base", "axe_head");
        int n = min + rnd.nextInt(max - min + 1);
        for (int i = 0; i < n; i++) {
            e.getDrops().add(plugin.items().createComponent(comps.get(rnd.nextInt(comps.size())), 1));
        }
        e.setDroppedExp(300);
        Bukkit.broadcastMessage(plugin.text("dungeon_boss_dead", "player", killer));
    }
}
