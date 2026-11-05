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
final class SmartHorde {

    private static final Set<Material> PROTECTED = Set.of(
            Material.BEDROCK, Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.BARRIER, Material.REINFORCED_DEEPSLATE,
            Material.END_PORTAL, Material.END_PORTAL_FRAME, Material.NETHER_PORTAL, Material.END_GATEWAY,
            Material.RESPAWN_ANCHOR, Material.SPAWNER, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.LIGHT);
    private static final Material BUILD = Material.COBBLESTONE;

    private static final class State {
        UUID target;
        int role;            // 0 escavador, 1 construtor, 2 brutamontes, 3 tanque
        Location lastPos;
        int stuckTicks;
        boolean pathOk = true;
        Block breaking;
        long breakStart;
        long lastPlace;
        int tick;
    }

    private final HardcoreRoulette plugin;
    private final NamespacedKey key;
    private final Map<UUID, State> horde = new HashMap<>();
    private BukkitTask task;

    SmartHorde(HardcoreRoulette plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "horde");
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
                case 0 -> eq.setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
                case 1 -> eq.setItemInMainHand(new ItemStack(BUILD, 16));
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

        // 1) alvo acima (penhasco, morro, torre) e perto na horizontal: sobe em pilar.
        //    Prioridade sobre cavar para o lado: cavar a parede nao ajuda a subir.
        if (dy >= 2 && horiz < 6) {
            Block ceiling = feet.getRelative(BlockFace.UP, 2);
            if (!ceiling.isPassable()) { // teto/saliencia: quebra primeiro
                if (breakable(ceiling)) progressBreak(z, s, ceiling);
                else if (z.isOnGround()) z.setVelocity(dir.clone().multiply(0.3).setY(0.42)); // nao quebravel: tenta sair de baixo
                return;
            }
            if (now - s.lastPlace > 650 && replaceable(feet) && feet.getRelative(BlockFace.DOWN).isSolid()) {
                z.teleport(zl.clone().add(0, 1.05, 0));
                feet.setType(BUILD);
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
        if (obstacle != null && breakable(obstacle)) { progressBreak(z, s, obstacle); return; }

        // 3) buraco/agua na frente e alvo nao muito abaixo: ponte
        Block frontBelow = front.getRelative(BlockFace.DOWN);
        if (front.isPassable() && replaceable(frontBelow) && dy > -2 && now - s.lastPlace > 500) {
            frontBelow.setType(BUILD);
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

    private static boolean breakable(Block b) {
        Material m = b.getType();
        if (b.isPassable() || PROTECTED.contains(m) || Tag.BEDS.isTagged(m)) return false;
        float h = m.getHardness();
        return h >= 0 && h <= 50;
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
            b.breakNaturally(true);
            cancelBreak(z, s);
            s.stuckTicks = 0;
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
        for (State s : horde.values()) roles[s.role]++;
        return alive() + " zumbis (escavadores " + roles[0] + ", construtores " + roles[1] + ", brutos " + roles[2] + ", tanques " + roles[3] + ")";
    }

    Component countLine() { return Component.text(alive() + " zumbis restantes"); }
}
