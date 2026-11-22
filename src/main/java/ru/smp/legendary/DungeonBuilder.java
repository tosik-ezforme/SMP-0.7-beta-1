package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Чертёж средневековой цитадели «Проклятый замок»: стены с зубцами, привратная башня,
 * четыре угловые башни, двор с колодцем и кузней, донжон с тронным залом и склеп с клетками и боссом.
 * Все координаты относительные: x, z — от центра, y — от уровня земли (0 = поверхность).
 */
final class DungeonBuilder {

    record Op(int x, int y, int z, BlockData data) {
    }

    /** Сундук с лутом: относительная позиция и уровень добычи (0 — башни, 1 — зал/камеры, 2 — сокровищница). */
    record ChestSpot(int dx, int dy, int dz, int tier) {
    }

    static final int HALF = 19; // половина стороны площадки

    private static final String[] STONE = {"stone_bricks", "stone_bricks", "stone_bricks", "stone_bricks", "stone_bricks",
            "mossy_stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "cracked_stone_bricks"};
    private static final String[] DARK = {"deepslate_bricks", "deepslate_bricks", "deepslate_bricks", "deepslate_bricks",
            "cracked_deepslate_bricks", "cracked_deepslate_bricks", "deepslate_tiles", "deepslate_tiles"};
    private static final String[] COBBLE = {"cobblestone", "cobblestone", "cobblestone", "mossy_cobblestone", "andesite", "stone"};
    private static final String[] GROUND = {"grass_block", "grass_block", "grass_block", "coarse_dirt", "coarse_dirt", "gravel", "cobblestone"};
    private static final String[] PATH = {"stone_bricks", "cobblestone", "stone_bricks", "gravel", "mossy_cobblestone"};
    private static final String[] HALLFLOOR = {"polished_andesite", "polished_andesite", "polished_andesite", "stone_bricks", "smooth_stone"};

    static final List<ChestSpot> CHESTS = List.of(
            new ChestSpot(16, 7, 16, 0), new ChestSpot(-16, 7, 16, 0), new ChestSpot(16, 7, -16, 0), new ChestSpot(-16, 7, -16, 0),
            new ChestSpot(5, 1, -5, 1), new ChestSpot(5, 1, 5, 1),
            new ChestSpot(-2, -7, 13, 1), new ChestSpot(2, -7, 13, 1),
            new ChestSpot(0, -7, 5, 2));

    /** Позиции стражников во дворе (dx, dz). */
    static final int[][] GUARDS = {{-4, 10}, {4, 10}, {-9, -6}, {9, -9}, {0, 12}, {-10, 8}, {11, 2}, {-11, -11}};
    /** Позиция босса в склепе. */
    static final int[] BOSS = {0, -7, 8};

    final List<Op> ops = new ArrayList<>();
    final List<Runnable> post = new ArrayList<>();
    private final World w;
    private final int cx, gy, cz;
    private final Random rnd = new Random();
    private final Map<String, BlockData> cache = new HashMap<>();

    DungeonBuilder(World w, int cx, int gy, int cz) {
        this.w = w;
        this.cx = cx;
        this.gy = gy;
        this.cz = cz;
    }

    // ------------------------------------------------------------ примитивы

    private BlockData bd(String s) {
        return cache.computeIfAbsent(s, k -> Bukkit.createBlockData(k.contains(":") ? k : "minecraft:" + k));
    }

    private void set(int x, int y, int z, String b) {
        ops.add(new Op(cx + x, gy + y, cz + z, bd(b)));
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, String... pal) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    set(x, y, z, pal.length == 1 ? pal[0] : pal[rnd.nextInt(pal.length)]);
                }
            }
        }
    }

    /** Зубцы по периметру квадрата радиуса r вокруг (ox, oz) на высоте y. */
    private void crenel(int ox, int oz, int r, int y, String block) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == r && ((dx + dz) & 1) == 0) {
                    set(ox + dx, y, oz + dz, block);
                }
            }
        }
    }

    private void lampPost(int x, int z) {
        set(x, 1, z, "cobblestone_wall");
        set(x, 2, z, "cobblestone_wall");
        set(x, 3, z, "lantern[hanging=false]");
    }

    // ------------------------------------------------------------ чертёж

    void plan() {
        terrain();
        walls();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                tower(sx, sz);
            }
        }
        gatehouse();
        courtyard();
        keep();
        crypt();
    }

    private void terrain() {
        fill(-HALF, 1, -HALF, HALF, 18, HALF, "air");
        fill(-HALF, -9, -HALF, HALF, -1, HALF, COBBLE);
        fill(-HALF, 0, -HALF, HALF, 0, HALF, GROUND);
        fill(-1, 0, 7, 1, 0, HALF, PATH); // дорога от ворот к донжону
        fill(-6, 0, 12, 6, 0, 12, PATH);  // поперечная дорожка
    }

    private void walls() {
        fill(-16, 1, -16, 16, 6, 16, STONE);
        fill(-14, 1, -14, 14, 6, 14, "air");
        crenel(0, 0, 16, 7, "stone_bricks");
        // лестницы на стену
        for (int x : new int[]{10, 11, -11, -10}) {
            for (int j = 0; j <= 5; j++) {
                int z = -9 - j;
                if (j >= 1) {
                    fill(x, 1, z, x, j, z, STONE);
                }
                set(x, 1 + j, z, "stone_brick_stairs[facing=north,half=bottom]");
            }
        }
        // знамёна на внутренней стороне северной стены
        for (int x : new int[]{-6, 0, 6}) {
            set(x, 4, -14, (x == 0 ? "black" : "red") + "_wall_banner[facing=south]");
        }
        // бойницы
        for (int k = -10; k <= 10; k += 5) {
            set(k, 3, -16, "iron_bars");
            set(-16, 3, k, "iron_bars");
            set(16, 3, k, "iron_bars");
        }
    }

    private void tower(int sx, int sz) {
        int ox = 16 * sx, oz = 16 * sz;
        fill(ox - 3, 1, oz - 3, ox + 3, 12, oz + 3, STONE);
        fill(ox - 2, 1, oz - 2, ox + 2, 11, oz + 2, "air");
        fill(ox - 2, 6, oz - 2, ox + 2, 6, oz + 2, "dark_oak_planks");
        crenel(ox, oz, 3, 13, "stone_bricks");
        // дверь во двор (через грань, обращённую к центру) и лестница на верх
        set(13 * sx, 1, 14 * sz, "air");
        set(13 * sx, 2, 14 * sz, "air");
        set(ox, 6, oz - 2, "air");
        for (int y = 1; y <= 7; y++) {
            set(ox, y, oz - 2, "ladder[facing=south]");
        }
        // бойницы и светильники
        for (int y : new int[]{3, 9}) {
            set(19 * sx, y, oz, "iron_bars");
            set(ox, y, 19 * sz, "iron_bars");
        }
        set(ox + 1, 7, oz + 1, "lantern[hanging=false]");
        set(ox - 1, 7, oz + 1, "lantern[hanging=false]");
        set(ox, 7, oz, "chest[facing=" + (sz > 0 ? "north" : "south") + "]");
        // знамя на внешней грани
        set(ox, 10, 19 * sz + sz, (sx == sz ? "red" : "black") + "_wall_banner[facing=" + (sz > 0 ? "south" : "north") + "]");
    }

    private void gatehouse() {
        fill(-4, 1, 14, 4, 9, 17, STONE);
        fill(-1, 1, 14, 1, 4, 17, "air");
        fill(-4, 7, 15, 4, 8, 16, "air"); // переход по верху ворот
        for (int x = -1; x <= 1; x++) {
            set(x, 4, 17, "iron_bars"); // поднятая решётка
            set(x, 4, 14, "iron_bars");
        }
        // арка
        set(-2, 4, 17, "stone_brick_stairs[facing=east,half=top]");
        set(2, 4, 17, "stone_brick_stairs[facing=west,half=top]");
        for (int z = 14; z <= 17; z++) {
            if ((z & 1) == 0) {
                set(-4, 10, z, "stone_bricks");
                set(4, 10, z, "stone_bricks");
            }
        }
        for (int x = -4; x <= 4; x++) {
            set(x, 10, 17, ((x & 1) == 0) ? "stone_bricks" : "air");
            set(x, 10, 14, ((x & 1) == 0) ? "stone_bricks" : "air");
        }
        set(-2, 6, 18, "red_wall_banner[facing=south]");
        set(2, 6, 18, "red_wall_banner[facing=south]");
        set(0, 7, 18, "black_wall_banner[facing=south]");
        lampPost(-3, 19);
        lampPost(3, 19);
        lampPost(-3, 11);
        lampPost(3, 11);
    }

    private void courtyard() {
        // колодец
        fill(-9, 1, 5, -7, 1, 7, "stone_bricks");
        set(-8, 1, 6, "air");
        set(-8, 0, 6, "water");
        for (int[] c : new int[][]{{-9, 5}, {-7, 5}, {-9, 7}, {-7, 7}}) {
            set(c[0], 2, c[1], "dark_oak_fence");
            set(c[0], 3, c[1], "dark_oak_fence");
        }
        fill(-9, 4, 5, -7, 4, 7, "dark_oak_slab[type=bottom]");
        // кузня
        set(9, 1, 5, "smithing_table");
        set(9, 1, 6, "blast_furnace[facing=west,lit=false]");
        set(9, 1, 7, "anvil[facing=north]");
        set(10, 1, 6, "cauldron");
        set(10, 1, 5, "grindstone[face=floor,facing=north]");
        // рынок
        fill(-11, 1, -8, -9, 1, -6, "hay_block");
        set(-10, 2, -7, "barrel[facing=up]");
        set(-9, 2, -8, "barrel[facing=up]");
        set(-11, 2, -6, "red_carpet");
        // лагерь стражи
        set(-4, 1, -12, "campfire[lit=true,facing=north]");
        set(-3, 1, -12, "hay_block");
        set(-5, 1, -12, "barrel[facing=up]");
        // знамя у донжона
        lampPost(-2, 9);
        lampPost(2, 9);
        // эшафот
        fill(5, 1, 9, 7, 1, 10, "dark_oak_planks");
        set(6, 2, 10, "dark_oak_fence");
        set(6, 3, 10, "dark_oak_fence");
        set(6, 4, 10, "dark_oak_fence");
        set(7, 4, 10, "dark_oak_fence");
        set(7, 3, 10, "lantern[hanging=true]");
    }

    private void keep() {
        fill(-7, 1, -7, 7, 10, 7, STONE);
        fill(-6, 1, -6, 6, 9, 6, "air");
        fill(-6, 0, -6, 6, 0, 6, HALLFLOOR);
        crenel(0, 0, 7, 11, "stone_bricks");
        for (int[] c : new int[][]{{7, 7}, {-7, 7}, {7, -7}, {-7, -7}}) {
            set(c[0], 11, c[1], "stone_bricks");
            set(c[0], 12, c[1], "cobblestone_wall");
            set(c[0], 13, c[1], "cobblestone_wall");
            set(c[0], 14, c[1], "lantern[hanging=false]");
        }
        // вход и бойницы
        fill(-1, 1, 7, 1, 3, 7, "air");
        set(-2, 3, 7, "stone_brick_stairs[facing=east,half=top]");
        set(2, 3, 7, "stone_brick_stairs[facing=west,half=top]");
        for (int y = 4; y <= 6; y++) {
            for (int k : new int[]{-4, 4}) {
                set(7, y, k, "iron_bars");
                set(-7, y, k, "iron_bars");
                set(k, y, 7, "iron_bars");
            }
        }
        set(-6, 5, 7, "red_wall_banner[facing=north]");
        // колонны
        for (int[] c : new int[][]{{3, 3}, {-3, 3}, {3, -3}, {-3, -3}}) {
            fill(c[0], 1, c[1], c[0], 8, c[1], "stripped_dark_oak_log[axis=y]");
            set(c[0], 9, c[1], "chiseled_stone_bricks");
            set(c[0], 1, c[1], "polished_andesite");
        }
        // дорожка и трон
        fill(-1, 1, -4, 1, 1, 6, "red_carpet");
        fill(-3, 1, -6, 3, 1, -4, "polished_andesite");
        set(0, 2, -5, "dark_oak_stairs[facing=north,half=bottom]");
        set(0, 2, -6, "dark_oak_planks");
        set(0, 3, -6, "dark_oak_planks");
        set(0, 4, -6, "gold_block");
        for (int x : new int[]{-2, 2}) {
            set(x, 4, -6, "red_wall_banner[facing=south]");
            set(x, 2, -5, "candle[candles=3,lit=true]");
        }
        set(-5, 4, -6, "black_wall_banner[facing=south]");
        set(5, 4, -6, "black_wall_banner[facing=south]");
        // стол
        fill(4, 1, -1, 5, 1, 3, "spruce_slab[type=bottom]");
        set(4, 2, 0, "candle[candles=2,lit=true]");
        set(5, 2, 2, "candle[candles=4,lit=true]");
        // люстры
        for (int[] c : new int[][]{{0, 0}, {0, 4}, {0, -3}, {5, 0}, {-5, 3}}) {
            set(c[0], 9, c[1], "dark_oak_fence");
            set(c[0], 8, c[1], "dark_oak_fence");
            set(c[0], 7, c[1], "lantern[hanging=true]");
        }
        // сундуки донжона
        set(5, 1, -5, "chest[facing=west]");
        set(5, 1, 5, "chest[facing=west]");
        // лестница в склеп
        for (int k = 0; k <= 7; k++) {
            int z = -5 + k;
            if (k >= 1) {
                fill(-5, -k + 1, z, -5, -k + 3, z, "air");
            }
            set(-5, -k, z, "stone_brick_stairs[facing=north,half=bottom]");
        }
        set(-5, 1, -6, "wall_torch[facing=south]");
    }

    private void crypt() {
        fill(-10, -9, 3, 10, -2, 15, DARK);
        fill(-9, -7, 4, 9, -3, 14, "air");
        fill(-9, -8, 4, 9, -8, 14, "polished_deepslate", "polished_deepslate", "deepslate_tiles");
        fill(-5, -7, 3, -5, -5, 3, "air"); // выход лестницы
        // колонны и свет
        for (int x : new int[]{-6, -3, 3, 6}) {
            for (int z : new int[]{7, 11}) {
                fill(x, -7, z, x, -3, z, "polished_deepslate");
            }
        }
        for (int x : new int[]{-8, -4, 4, 8}) {
            for (int z : new int[]{6, 10}) {
                set(x, -7, z, "soul_lantern[hanging=false]");
            }
        }
        // саркофаг и сокровищница
        fill(-1, -7, 7, 1, -7, 9, "polished_blackstone_bricks");
        set(0, -6, 8, "chiseled_polished_blackstone");
        set(-1, -6, 7, "soul_campfire[lit=true]");
        set(1, -6, 9, "soul_campfire[lit=true]");
        set(0, -7, 5, "chest[facing=south]");
        // камеры
        for (int x = -8; x <= 8; x++) {
            boolean door = x == -6 || x == -2 || x == 2 || x == 6;
            for (int y = -7; y <= -4; y++) {
                if (!door || y >= -5) {
                    set(x, y, 12, "iron_bars");
                }
            }
        }
        for (int x : new int[]{-4, 0, 4}) {
            fill(x, -7, 13, x, -4, 14, "iron_bars");
        }
        set(-2, -7, 13, "chest[facing=north]");
        set(2, -7, 13, "chest[facing=north]");
        set(-7, -7, 13, "spawner");
        set(7, -7, 13, "spawner");
        final int sx1 = cx - 7, sx2 = cx + 7, sy = gy - 7, sz = cz + 13;
        post.add(() -> {
            setSpawner(w.getBlockAt(sx1, sy, sz), EntityType.SKELETON);
            setSpawner(w.getBlockAt(sx2, sy, sz), EntityType.ZOMBIE);
        });
        // паутина и кости
        for (int i = 0; i < 16; i++) {
            set(-9 + rnd.nextInt(19), -3 - rnd.nextInt(2), 4 + rnd.nextInt(11), "cobweb");
        }
        for (int i = 0; i < 8; i++) {
            set(-8 + rnd.nextInt(17), -7, 4 + rnd.nextInt(7), "skeleton_skull[rotation=" + rnd.nextInt(16) + "]");
        }
        set(-9, -5, 8, "iron_bars");
        set(9, -5, 8, "iron_bars");
    }

    private static void setSpawner(Block b, EntityType type) {
        if (b.getState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(type);
            cs.setRequiredPlayerRange(16);
            cs.setMaxNearbyEntities(4);
            cs.update(true, false);
        }
    }
}
