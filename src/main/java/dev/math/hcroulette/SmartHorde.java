package dev.math.hcroulette;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Horda zumbi com mobs de verdade (sem bots): rastreio de longo alcance, quebra de blocos com animacao,
 * pilares e pontes. Mesma ideia dos mods de apocalipse (Zombie Break & Build etc.), via API do Paper.
 */
final class SmartHorde implements org.bukkit.event.Listener {

    private static final Set<Material> PROTECTED = Set.of(
            Material.BEDROCK, Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.BARRIER, Material.REINFORCED_DEEPSLATE,
            Material.END_PORTAL, Material.END_PORTAL_FRAME, Material.NETHER_PORTAL, Material.END_GATEWAY,
            Material.RESPAWN_ANCHOR, Material.SPAWNER, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.LIGHT);
    private static final class State {
        UUID target;
        int role;            // 0 minerador (picareta), 1 lenhador (machado), 2 brutamontes, 3 tanque
        Location lastPos;
        int stuckTicks;
        boolean pathOk = true;
        Block breaking;
        long breakStart;
        long lastPlace;
        int tick;
        /** Inventario de blocos: so constroi com o que carrega (e o que carrega veio do que quebrou). */
        final java.util.EnumMap<Material, Integer> inv = new java.util.EnumMap<>(Material.class);
        int invTotal;
    }

    private int capacity() { return Math.max(0, plugin.getConfig().getInt("horde.block-capacity", 32)); }

    /** Bloco que da para usar em pilar/ponte: cubo solido comum (nada de flor, vidro fino, bau etc.). */
    private static boolean buildable(Material m) {
        return m.isBlock() && m.isSolid() && m.isOccluding() && !m.hasGravity() && !PROTECTED.contains(m)
                && m != Material.TNT && !m.name().contains("ORE") && !m.name().contains("SHULKER");
    }

    private boolean addBlock(State s, Material m) {
        if (!buildable(m) || s.invTotal >= capacity()) return false;
        s.inv.merge(m, 1, Integer::sum);
        s.invTotal++;
        return true;
    }

    /** Tira um bloco do inventario (o tipo que tem mais). null = sem blocos. */
    private static Material takeBlock(State s) {
        Material best = null; int n = 0;
        for (var e : s.inv.entrySet()) if (e.getValue() > n) { n = e.getValue(); best = e.getKey(); }
        if (best == null) return null;
        if (n == 1) s.inv.remove(best); else s.inv.put(best, n - 1);
        s.invTotal--;
        return best;
    }

    private final HardcoreRoulette plugin;
    private final NamespacedKey key;
    private final Map<UUID, State> horde = new HashMap<>();
    private BukkitTask task;

    SmartHorde(HardcoreRoulette plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "horde");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getConfig().addDefault("horde.block-capacity", 32);
        // restos de uma horda anterior (crash/restart) somem ao carregar
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Zombie z : w.getEntitiesByClass(Zombie.class))
                if (z.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) z.remove();
        }, 40L);
    }

    boolean isHorde(Entity e) { return e != null && horde.containsKey(e.getUniqueId()); }

    int alive() {
        horde.keySet().removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || e.isDead() || !e.isValid(); });
        return horde.size();
    }

    // ---------------------------------------------------------------- spawn / remover

    void spawn(World w, List<Location> spots, int count, Player target) {
        for (int i = 0; i < count; i++) {
            Location base = spots.get(i % spots.size()).clone();
            Biome b = w.getBiome(base);
            Class<? extends Zombie> type = b.getKey().getKey().contains("desert") || b.getKey().getKey().contains("badlands") ? Husk.class : Zombie.class;
            final int role = i % 4;
            Zombie z = w.spawn(base, type, zz -> setup(zz, role, target));
            State s = new State();
            s.role = role;
            s.target = target.getUniqueId();
            // inventario inicial por papel; o resto ele junta quebrando blocos no caminho
            // so minerador e lenhador mexem em blocos; comecam com um pouco do proprio material
            if (role == MINER) for (int k = 0; k < 8; k++) addBlock(s, Material.COBBLESTONE);
            if (role == LUMBER) for (int k = 0; k < 8; k++) addBlock(s, Material.OAK_PLANKS);
            horde.put(z.getUniqueId(), s);
            w.spawnParticle(Particle.LARGE_SMOKE, base.clone().add(0, 1, 0), 12, 0.3, 0.6, 0.3, 0.01);
        }
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    private void setup(Zombie z, int role, Player target) {
        z.setAdult();
        z.setShouldBurnInDay(false);
        z.setPersistent(true);
        z.setRemoveWhenFarAway(false);
        z.setCanPickupItems(false);
        z.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        attr(z, Attribute.FOLLOW_RANGE, 128);
        attr(z, Attribute.MOVEMENT_SPEED, role == 3 ? 0.24 : 0.28);
        attr(z, Attribute.KNOCKBACK_RESISTANCE, role == 3 ? 0.6 : 0.15);
        if (role == 3) attr(z, Attribute.MAX_HEALTH, 34);
        EntityEquipment eq = z.getEquipment();
        if (eq != null) {
            switch (role) {
                case MINER -> eq.setItemInMainHand(new ItemStack(Material.WOODEN_PICKAXE));
                case LUMBER -> eq.setItemInMainHand(new ItemStack(Material.WOODEN_AXE));
                case 2 -> eq.setItemInMainHand(new ItemStack(Material.STONE_SWORD));
                default -> eq.setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
            }
            eq.setItemInMainHandDropChance(0.08f);
            eq.setHelmetDropChance(0.08f);
        }
        if (role == 3) z.setHealth(34);
        z.setTarget(target);
    }

    private static void attr(Zombie z, Attribute a, double v) {
        AttributeInstance i = z.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    void despawn(boolean effect) {
        for (UUID id : new ArrayList<>(horde.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e == null) continue;
            if (effect) e.getWorld().spawnParticle(Particle.LARGE_SMOKE, e.getLocation().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.02);
            e.remove();
        }
        horde.clear();
        if (task != null) { task.cancel(); task = null; }
    }

    // ---------------------------------------------------------------- cerebro (2x por segundo)

    private Player pickTarget(Zombie z, State s) {
        Player t = s.target == null ? null : Bukkit.getPlayer(s.target);
        if (valid(t, z)) return t;
        Player best = null; double bd = Double.MAX_VALUE;
        for (Player p : z.getWorld().getPlayers()) {
            if (!valid(p, z)) continue;
            double d = p.getLocation().distanceSquared(z.getLocation());
            if (d < bd) { bd = d; best = p; }
        }
        s.target = best == null ? null : best.getUniqueId();
        return best;
    }

    private static boolean valid(Player p, Zombie z) {
        return p != null && p.isOnline() && !p.isDead() && p.getWorld() == z.getWorld()
                && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                && !p.getName().matches("^(Zumbi|Pirata|Guilda)_\\d{1,2}$");
    }

    private void tick() {
        Iterator<Map.Entry<UUID, State>> it = horde.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> en = it.next();
            Entity e = Bukkit.getEntity(en.getKey());
            if (!(e instanceof Zombie z) || z.isDead() || !z.isValid()) { it.remove(); continue; }
            State s = en.getValue();
            s.tick++;
            Player t = pickTarget(z, s);
            if (t == null) continue;
            if (z.getTarget() != t) z.setTarget(t);

            Location zl = z.getLocation(), tl = t.getLocation();
            double dist = zl.distance(tl);
            if (s.tick % 2 == 0 || !s.pathOk) s.pathOk = z.getPathfinder().moveTo(t, 1.15);

            // travado: quase nao andou e o alvo nao esta colado
            if (s.lastPos != null && zl.getWorld() == s.lastPos.getWorld() && zl.distanceSquared(s.lastPos) < 0.05 && dist > 1.8) s.stuckTicks++;
            else { s.stuckTicks = 0; if (s.breaking != null) cancelBreak(z, s); }
            s.lastPos = zl.clone();
            if (s.stuckTicks < 3 && s.pathOk) continue; // andando normalmente

            act(z, s, zl, tl, dist);
        }
        if (horde.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    /** Sem caminho ou travado: pilar, quebrar o obstaculo, ponte ou pulo. */
    private void act(Zombie z, State s, Location zl, Location tl, double dist) {
        Vector dir = tl.toVector().subtract(zl.toVector());
        double dy = dir.getY();
        dir.setY(0);
        double horiz = dir.length();
        if (horiz < 0.01) dir = new Vector(1, 0, 0); else dir.normalize();
        Block feet = zl.getBlock();
        Block front = zl.clone().add(dir.clone().multiply(0.9)).getBlock();
        Block frontHead = front.getRelative(BlockFace.UP);
        long now = System.currentTimeMillis();

        // brutamontes/tanque nao mexem em blocos: so tentam destravar e seguem os outros
        if (tool(s) == null) {
            if (z.isOnGround()) z.setVelocity(dir.clone().multiply(0.22).setY(0.42));
            return;
        }

        // 1) alvo acima (penhasco, morro, torre) e perto na horizontal: sobe em pilar.
        //    Prioridade sobre cavar para o lado: cavar a parede nao ajuda a subir.
        if (dy >= 2 && horiz < 6) {
            Block ceiling = feet.getRelative(BlockFace.UP, 2);
            if (!ceiling.isPassable()) { // teto/saliencia: quebra primeiro
                if (canBreak(s, ceiling)) progressBreak(z, s, ceiling);
                else if (z.isOnGround()) z.setVelocity(dir.clone().multiply(0.3).setY(0.42)); // nao quebravel: tenta sair de baixo
                return;
            }
            if (now - s.lastPlace > 650 && replaceable(feet) && feet.getRelative(BlockFace.DOWN).isSolid()) {
                if (s.invTotal == 0) { gather(z, s, feet, dir); return; } // sem blocos: junta primeiro
                Material m = takeBlock(s);
                z.teleport(zl.clone().add(0, 1.05, 0));
                feet.setType(m);
                placeFx(feet);
                s.lastPlace = now;
            }
            return;
        }
        // 2) obstaculo na frente (cabeca primeiro) ou cavar para baixo se o alvo esta embaixo
        Block obstacle = null;
        if (dy <= -2 && horiz < 2) obstacle = feet.getRelative(BlockFace.DOWN);
        else if (!frontHead.isPassable()) obstacle = frontHead;
        else if (!front.isPassable()) obstacle = front;
        else if (dy >= 1 && !feet.getRelative(BlockFace.UP, 2).isPassable()) obstacle = feet.getRelative(BlockFace.UP, 2); // teto
        if (obstacle != null && canBreak(s, obstacle)) { progressBreak(z, s, obstacle); return; }

        // 3) buraco/agua na frente e alvo nao muito abaixo: ponte
        Block frontBelow = front.getRelative(BlockFace.DOWN);
        if (front.isPassable() && replaceable(frontBelow) && dy > -2 && now - s.lastPlace > 500) {
            if (s.invTotal == 0) { gather(z, s, feet, dir); return; } // sem blocos para a ponte
            frontBelow.setType(takeBlock(s));
            placeFx(frontBelow);
            s.lastPlace = now;
            return;
        }
        // 4) empurrao + pulo para destravar
        if (z.isOnGround()) z.setVelocity(dir.multiply(0.22).setY(0.42));
    }

    private static boolean replaceable(Block b) {
        Material m = b.getType();
        return b.isPassable() || b.isLiquid() || m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.SNOW;
    }

    static final int MINER = 0, LUMBER = 1, BRUTE = 2, TANK = 3;

    /** Ferramenta do papel (define o que quebra e o drop). null = nao mexe em blocos. */
    private static ItemStack tool(State s) {
        return switch (s.role) {
            case MINER -> new ItemStack(Material.WOODEN_PICKAXE);
            case LUMBER -> new ItemStack(Material.WOODEN_AXE);
            default -> null;
        };
    }

    /**
     * Regras de ferramenta como no vanilla: minerador so quebra o que picareta de madeira minera
     * (pedra sim; ferro/diamante/obsidiana nao); lenhador so blocos de madeira (tag de machado).
     */
    private static boolean canBreak(State s, Block b) {
        Material m = b.getType();
        if (b.isPassable() || PROTECTED.contains(m) || Tag.BEDS.isTagged(m) || m.getHardness() < 0) return false;
        return switch (s.role) {
            case MINER -> Tag.MINEABLE_PICKAXE.isTagged(m) && !Tag.NEEDS_STONE_TOOL.isTagged(m)
                    && !Tag.NEEDS_IRON_TOOL.isTagged(m) && !Tag.NEEDS_DIAMOND_TOOL.isTagged(m);
            case LUMBER -> Tag.MINEABLE_AXE.isTagged(m);
            default -> false;
        };
    }

    private void progressBreak(Zombie z, State s, Block b) {
        long now = System.currentTimeMillis();
        if (!b.equals(s.breaking)) { cancelBreak(z, s); s.breaking = b; s.breakStart = now; }
        float h = Math.max(0.2f, b.getType().getHardness());
        long duration = (long) Math.max(800, Math.min(12_000, 700 + h * 1500));
        float progress = Math.min(1f, (now - s.breakStart) / (float) duration);
        z.swingMainHand();
        for (Player p : b.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(b.getLocation()) < 48 * 48) p.sendBlockDamage(b.getLocation(), progress, z.getEntityId());
        }
        if (s.tick % 2 == 0) b.getWorld().playSound(b.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.7f, 0.8f + ThreadLocalRandom.current().nextFloat() * 0.3f);
        if (progress >= 1f) {
            b.getWorld().playSound(b.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.8f, 1f);
            cancelBreak(z, s);
            collect(s, b);
            s.stuckTicks = 0;
        }
    }

    /** Quebra o bloco e guarda o drop no inventario (como se fosse com picareta); o que nao cabe cai no chao. */
    private void collect(State s, Block b) {
        ItemStack t = tool(s);
        java.util.Collection<ItemStack> drops = t == null ? b.getDrops() : b.getDrops(t);
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        b.getWorld().spawnParticle(Particle.BLOCK, c, 25, 0.3, 0.3, 0.3, b.getBlockData());
        b.setType(Material.AIR);
        for (ItemStack d : drops) {
            int left = 0;
            for (int i = 0; i < d.getAmount(); i++) if (!addBlock(s, d.getType())) left++;
            if (left > 0) b.getWorld().dropItemNaturally(c, new ItemStack(d.getType(), left));
        }
    }

    /**
     * Sem blocos para construir: quebra um bloco do lado (o mais facil que de material de construcao).
     * Nunca o bloco em que esta pisando.
     */
    private void gather(Zombie z, State s, Block feet, Vector dir) {
        if (s.invTotal >= capacity()) return;
        Block best = null; float bh = Float.MAX_VALUE;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            for (Block b : new Block[]{feet.getRelative(f), feet.getRelative(f).getRelative(BlockFace.DOWN)}) {
                if (!canBreak(s, b)) continue;
                boolean useful = false;
                for (ItemStack d : b.getDrops(tool(s))) if (buildable(d.getType())) { useful = true; break; }
                if (!useful) continue;
                float h = b.getType().getHardness();
                if (h < bh) { bh = h; best = b; }
            }
        }
        if (best != null) progressBreak(z, s, best);
        else if (z.isOnGround()) z.setVelocity(dir.clone().multiply(0.25).setY(0.42)); // nada util em volta: anda um pouco
    }

    /** Zumbi da horda morreu: solta os blocos que carregava. */
    @org.bukkit.event.EventHandler
    public void onDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        State s = horde.remove(e.getEntity().getUniqueId());
        if (s == null) return;
        for (var en : s.inv.entrySet()) {
            int n = en.getValue();
            while (n > 0) { int k = Math.min(64, n); e.getDrops().add(new ItemStack(en.getKey(), k)); n -= k; }
        }
    }

    private void cancelBreak(Zombie z, State s) {
        if (s.breaking == null) return;
        Block b = s.breaking;
        for (Player p : b.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(b.getLocation()) < 48 * 48) p.sendBlockDamage(b.getLocation(), 0f, z.getEntityId());
        }
        s.breaking = null;
    }

    private static void placeFx(Block b) {
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_PLACE, 1f, 0.9f);
    }

    String describe() {
        int[] roles = new int[4];
        int blocks = 0;
        for (State s : horde.values()) { roles[s.role]++; blocks += s.invTotal; }
        return alive() + " zumbis (mineradores " + roles[MINER] + ", lenhadores " + roles[LUMBER] + ", brutos " + roles[BRUTE] + ", tanques " + roles[TANK]
                + ") carregando " + blocks + " blocos (limite " + capacity() + " cada)";
    }

    Component countLine() { return Component.text(alive() + " zumbis restantes"); }
}
