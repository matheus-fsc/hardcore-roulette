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
        int role;            // 0 minerador (picareta), 1 lenhador (machado), 2 brutamontes, 3 tanque, 4 cavador (pa)
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
        int ladders;         // escadas craftadas (lenhador)
        boolean hasTable;    // bancada guardada no inventario
        Block table;         // bancada colocada no chao
        long craftUntil;     // usando a bancada ate
        Block tree;          // tora que o lenhador esta indo cortar
        long lastTreeScan;
        // fase de coleta
        boolean gathering;
        boolean hand; // sem recurso do papel: cava terra/areia na mao (mais lento, como no vanilla)
        int need;            // material alvo (blocos; para lenhador, unidades de tabua)
        long gatherStart, gatherCooldownUntil, lastScan, resourceSince;
        Block resource;
        final java.util.Set<Block> bad = new java.util.HashSet<>(); // recursos que nao conseguiu alcancar
        String action = "-";
        long lastDebug;
        boolean assigned;    // ja passou pela distribuicao de alvos
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
        plugin.getConfig().addDefault("horde.debug", false);
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

    void spawn(World w, List<Location> spots, int count, Player target) { spawn(w, spots, count, target, false); }

    /** buildersOnly: so minerador/cavador/lenhador (onda de construtores). */
    void spawn(World w, List<Location> spots, int count, Player target, boolean buildersOnly) {
        int[] cycle = buildersOnly ? new int[]{MINER, DIGGER, LUMBER} : new int[]{MINER, DIGGER, LUMBER, BRUTE, TANK};
        for (int i = 0; i < count; i++) {
            Location base = spots.get(i % spots.size()).clone();
            Biome b = w.getBiome(base);
            Class<? extends Zombie> type = b.getKey().getKey().contains("desert") || b.getKey().getKey().contains("badlands") ? Husk.class : Zombie.class;
            final int role = cycle[i % cycle.length];
            Zombie z = w.spawn(base, type, zz -> setup(zz, role, target));
            State s = new State();
            s.role = role;
            s.target = target.getUniqueId();
            // inventario inicial por papel; o resto ele junta quebrando blocos no caminho
            // comecam sem blocos (vanilla): so colocam o que mineraram no caminho / antes de subir
            horde.put(z.getUniqueId(), s);
            w.spawnParticle(Particle.LARGE_SMOKE, base.clone().add(0, 1, 0), 12, 0.3, 0.6, 0.3, 0.01);
        }
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    private void setup(Zombie z, int role, Player target) {
        z.setAdult();
        z.setShouldBurnInDay(false);
        z.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE,
                org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false));
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
                case DIGGER -> eq.setItemInMainHand(new ItemStack(Material.WOODEN_SHOVEL));
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

    /**
     * Distribui a horda entre os jogadores proximos: cada zumbi vai no jogador com MENOS zumbis em cima,
     * ponderado pela distancia (nao foca todo mundo em 1 enquanto o outro limpa). Rebalanceia a cada ~10s.
     */
    private Player pickTarget(Zombie z, State s) {
        Player t = s.target == null ? null : Bukkit.getPlayer(s.target);
        boolean rebalance = s.tick % 20 == 0 || !s.assigned;
        if (valid(t, z) && !rebalance) return t;
        Map<UUID, Integer> load = new HashMap<>();
        for (var en : horde.entrySet()) {
            if (en.getValue() == s || en.getValue().target == null) continue;
            load.merge(en.getValue().target, 1, Integer::sum);
        }
        Player best = null; double bestScore = Double.MAX_VALUE;
        for (Player p : z.getWorld().getPlayers()) {
            if (!valid(p, z)) continue;
            double d = p.getLocation().distance(z.getLocation());
            if (d > 96) continue; // so jogadores proximos
            double score = load.getOrDefault(p.getUniqueId(), 0) * 24 + d; // 1 zumbi a mais ~ 24 blocos a mais
            if (p.getUniqueId().equals(s.target)) score -= 6;               // leve preferencia em manter o alvo
            if (score < bestScore) { bestScore = score; best = p; }
        }
        if (best == null && valid(t, z)) best = t;
        if (best != null && !best.getUniqueId().equals(s.target)) { s.resource = null; s.action = "novo alvo: " + best.getName(); }
        s.target = best == null ? null : best.getUniqueId();
        s.assigned = true;
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
            long now = System.currentTimeMillis();

            // FASE 1 (COLETA): alvo no alto e pouco material -> junta recurso primeiro, ataca depois
            if (tool(s) != null) {
                double dyT = tl.getY() - zl.getY();
                if (!s.gathering && dyT >= 2 && now > s.gatherCooldownUntil && z.isOnGround()
                        && zl.getBlock().getType() != Material.LADDER && material(s) < needFor(s, dyT)) {
                    startGather(s, needFor(s, dyT), now);
                }
                if (s.gathering) {
                    int needNow = Math.min(s.need, needFor(s, dyT)); // o alvo pode ter descido/chegado perto
                    long took = now - s.gatherStart;
                    if (material(s) >= needNow || s.invTotal >= capacity() || (took > 25_000 && material(s) >= needNow * 0.6)) {
                        s.gathering = false; s.resource = null; s.action = "material ok, atacando";
                    }
                    else if (took > 45_000) { s.gathering = false; s.gatherCooldownUntil = now + 20_000; s.action = "coleta demorou, ataca com o que tem"; }
                    else { gatherTick(z, s, now); debug(z, s, dist, tl.getY() - zl.getY()); continue; }
                }
            }
            // FASE 2 (ATAQUE)
            if (s.tick % 2 == 0 || !s.pathOk) s.pathOk = z.getPathfinder().moveTo(t, 1.15);
            debug(z, s, dist, tl.getY() - zl.getY());

            // travado: quase nao andou e o alvo nao esta colado
            if (s.lastPos != null && zl.getWorld() == s.lastPos.getWorld() && zl.distanceSquared(s.lastPos) < 0.05 && dist > 1.8) s.stuckTicks++;
            else { s.stuckTicks = 0; if (s.breaking != null) cancelBreak(z, s); }
            s.lastPos = zl.clone();
            // na escada com o alvo acima: continua subindo (teleporte por degrau zera o "travado")
            if (zl.getBlock().getType() == Material.LADDER && tl.getY() - zl.getY() >= 1) { act(z, s, zl, tl, dist); continue; }
            if (s.stuckTicks < 3 && s.pathOk) continue; // andando normalmente

            act(z, s, zl, tl, dist);
        }
        if (horde.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    // ---------------------------------------------------------------- fase de coleta

    /** Quanto material a subida pede: blocos (dy + margem) ou, para o lenhador, madeira para escadas + bancada. */
    private int needFor(State s, double dy) {
        if (s.role == LUMBER && !s.hand) {
            int ladders = (int) Math.ceil(dy);
            return (int) Math.ceil(ladders / 3.0) * 4 + 4; // ~4 tabuas por 3 escadas + bancada
        }
        return Math.min(capacity(), (int) Math.ceil(dy) + 1); // 1 bloco por degrau + 1 de folga
    }

    /** Material atual na mesma unidade do needFor. */
    private int material(State s) {
        if (s.role == LUMBER && !s.hand) return woodUnits(s) + (int) Math.ceil(s.ladders * 4 / 3.0) + (s.hasTable ? 4 : 0);
        return s.invTotal;
    }

    private void startGather(State s, int need, long now) {
        s.gathering = true;
        s.hand = false;
        s.need = need;
        s.gatherStart = now;
        s.resource = null;
        s.action = "coletando (precisa " + need + ")";
    }

    /** Vai ate o recurso mais proximo (superficie primeiro), quebra e guarda; repete ate ter o necessario. */
    private void gatherTick(Zombie z, State s, long now) {
        if (s.resource != null && (!breakable(s, s.resource) || s.bad.contains(s.resource))) s.resource = null;
        if (s.resource == null) {
            if (now - s.lastScan < 1000) return;
            s.lastScan = now;
            s.resource = scanResource(z, s);
            s.resourceSince = now;
            if (s.resource == null) { // nada no raio: ataca com o que tem e tenta de novo daqui a pouco
                s.gathering = false;
                s.gatherCooldownUntil = now + 15_000;
                s.action = "sem recurso no raio";
                return;
            }
        }
        Block r = s.resource;
        Location c = r.getLocation().add(0.5, 0.5, 0.5);
        if (z.getEyeLocation().distance(c) <= 4.2) {
            z.lookAt(c);
            s.action = (s.hand ? "cavando na mao " : "quebrando ") + r.getType().name().toLowerCase();
            progressBreak(z, s, r);
            if (!breakable(s, r)) s.resource = null; // quebrou: proximo
            return;
        }
        if (now - s.resourceSince > 8000) { s.bad.add(r); s.resource = null; s.action = "nao alcanca recurso, outro"; return; }
        z.getPathfinder().moveTo(r.getLocation().add(0.5, 1, 0.5), 1.15);
        s.action = "indo ate " + r.getType().name().toLowerCase();
    }

    /**
     * Recurso mais proximo e exposto que vira material util. Primeiro o do papel (pedra para o minerador,
     * tora para o lenhador, num raio maior); sem nada, cai para terra/areia cavada na mao.
     */
    private Block scanResource(Zombie z, State s) {
        boolean wasHand = s.hand;
        s.hand = false;
        Block b = s.role == LUMBER ? scan(z, s, 28, -4, 6) : scan(z, s, s.role == MINER ? 16 : 12, -5, 4);
        if (b == null && s.role != DIGGER) {
            s.hand = true;
            b = scan(z, s, 12, -4, 4);
            if (b != null && s.role == LUMBER && !wasHand) s.need = Math.min(capacity(), Math.max(6, s.need));
        }
        if (b == null) s.hand = wasHand;
        return b;
    }

    private Block scan(Zombie z, State s, int r, int ylo, int yhi) {
        Block feet = z.getLocation().getBlock();
        Block best = null; double bestScore = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dyb = ylo; dyb <= yhi; dyb++) {
            Block b = feet.getRelative(dx, dyb, dz);
            Material m = b.getType();
            if (m.isAir() || b.isPassable()) continue;
            if (s.role == LUMBER && !s.hand ? !Tag.LOGS.isTagged(m) : !breakable(s, b)) continue;
            if (s.bad.contains(b) || (dx == 0 && dz == 0 && dyb < 0)) continue; // nunca o chao embaixo dele
            boolean exposed = false;
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                if (b.getRelative(f).isPassable()) { exposed = true; break; }
            }
            if (!exposed) continue;
            boolean useful = false;
            for (ItemStack d : drops(s, b)) if (buildable(d.getType()) || Tag.LOGS.isTagged(d.getType())) { useful = true; break; }
            if (!useful) continue; // ex.: minerio de carvao nao serve
            double score = Math.sqrt(dx * dx + dz * dz + dyb * dyb * 1.5) + m.getHardness() * 1.5;
            if (score < bestScore) { bestScore = score; best = b; }
        }
        return best;
    }

    /** Quebravel pelo papel, ou na mao (terra, grama, areia, cascalho) quando em modo mao. */
    private static boolean breakable(State s, Block b) {
        if (canBreak(s, b)) return true;
        if (!s.hand) return false;
        Material m = b.getType();
        return !b.isPassable() && !PROTECTED.contains(m) && Tag.MINEABLE_SHOVEL.isTagged(m) && m.getHardness() >= 0 && m.getHardness() <= 0.6f;
    }

    private static java.util.Collection<ItemStack> drops(State s, Block b) {
        ItemStack t = tool(s);
        return t == null || !canBreak(s, b) ? b.getDrops() : b.getDrops(t);
    }

    private void debug(Zombie z, State s, double dist, double dy) {
        if (!plugin.getConfig().getBoolean("horde.debug", false)) return;
        long now = System.currentTimeMillis();
        if (now - s.lastDebug < 5000) return;
        s.lastDebug = now;
        String[] roles = {"minerador", "lenhador", "bruto", "tanque", "cavador"};
        plugin.getLogger().info(String.format("[horda] %s#%04d %s inv=%d/%d escadas=%d dist=%.0f dy=%+.0f | %s",
                roles[s.role], z.getEntityId() % 10000, s.gathering ? "COLETA" : "ATAQUE",
                material(s), s.gathering ? s.need : capacity(), s.ladders, dist, dy, s.action));
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

        // 0) escada: qualquer zumbi da horda sobe (a do lenhador ou de jogador); o lenhador estende a escada
        if (dy >= 1 && climbLadder(z, s, zl, feet, dir)) return;
        if (s.role == LUMBER && dy >= 2 && horiz < 6 && buildLadder(z, s, feet, dir, dy)) return;

        // brutamontes/tanque nao mexem em blocos: so tentam destravar e seguem os outros
        if (tool(s) == null) {
            if (z.isOnGround()) z.setVelocity(dir.clone().multiply(0.22).setY(0.42));
            return;
        }

        // 1a) alvo acima mas longe na horizontal: escadaria de blocos na direcao dele (sobe 1 por bloco andado)
        boolean wall = !front.isPassable() || !frontHead.isPassable();
        if (dy >= 2 && horiz >= 3 && !wall) {
            Block feetUp2 = feet.getRelative(BlockFace.UP, 2), frontUp2 = front.getRelative(BlockFace.UP, 2);
            if (feetUp2.isPassable() && frontUp2.isPassable()) {
                if (now - s.lastPlace < 650) return;
                if (s.invTotal == 0) {
                    if (z.isOnGround() && now > s.gatherCooldownUntil) startGather(s, needFor(s, dy), now);
                    else gather(z, s, feet, dir, true);
                    return;
                }
                Block below = front.getRelative(BlockFace.DOWN);
                if (replaceable(below)) { // buraco na frente: tapa antes do degrau
                    below.setType(takeBlock(s)); placeFx(below); s.lastPlace = now; s.action = "ponte"; return;
                }
                front.setType(takeBlock(s));
                placeFx(front);
                Location step = front.getLocation().add(0.5, 1.05, 0.5);
                step.setYaw(zl.getYaw()); step.setPitch(zl.getPitch());
                z.teleport(step);
                s.lastPlace = now;
                s.action = "degrau (" + s.invTotal + " blocos)";
                return;
            }
        }
        // 1b) alvo acima e perto (ou parede na frente): sobe em pilar.
        //    Prioridade sobre cavar para o lado: cavar a parede nao ajuda a subir.
        if (dy >= 2 && (horiz < 3 || wall)) {
            Block ceiling = feet.getRelative(BlockFace.UP, 2);
            if (!ceiling.isPassable()) { // teto/saliencia: quebra primeiro
                if (canBreak(s, ceiling)) progressBreak(z, s, ceiling);
                else if (z.isOnGround()) z.setVelocity(dir.clone().multiply(0.3).setY(0.42)); // nao quebravel: tenta sair de baixo
                return;
            }
            if (now - s.lastPlace > 650 && replaceable(feet) && feet.getRelative(BlockFace.DOWN).isSolid()) {
                // Junta material ANTES de subir: em cima do pilar so tem ar, nao da para reabastecer.
                if (s.invTotal == 0) { // acabou: volta a coletar (no chao) ou espera
                    if (z.isOnGround() && now > s.gatherCooldownUntil) startGather(s, needFor(s, dy), now);
                    else gather(z, s, feet, dir, true);
                    return;
                }
                s.action = "pilar (" + s.invTotal + " blocos)";
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
        if (obstacle != null && canBreak(s, obstacle)) { progressBreak(z, s, obstacle); s.action = "abrindo caminho"; return; }

        // 3) buraco/agua na frente e alvo nao muito abaixo: ponte
        Block frontBelow = front.getRelative(BlockFace.DOWN);
        if (front.isPassable() && replaceable(frontBelow) && dy > -2 && now - s.lastPlace > 500) {
            if (s.invTotal == 0) { // sem blocos para a ponte: coleta
                if (now > s.gatherCooldownUntil) startGather(s, Math.min(capacity(), 8), now); else gather(z, s, feet, dir, true);
                return;
            }
            s.action = "ponte";
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

    static final int MINER = 0, LUMBER = 1, BRUTE = 2, TANK = 3, DIGGER = 4;

    /** Ferramenta do papel (define o que quebra e o drop). null = nao mexe em blocos. */
    private static ItemStack tool(State s) {
        return switch (s.role) {
            case MINER -> new ItemStack(Material.WOODEN_PICKAXE);
            case LUMBER -> new ItemStack(Material.WOODEN_AXE);
            case DIGGER -> new ItemStack(Material.WOODEN_SHOVEL);
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
            case DIGGER -> Tag.MINEABLE_SHOVEL.isTagged(m); // terra, grama, areia, cascalho, argila, neve
            default -> false;
        };
    }

    private void progressBreak(Zombie z, State s, Block b) {
        long now = System.currentTimeMillis();
        if (!b.equals(s.breaking)) { cancelBreak(z, s); s.breaking = b; s.breakStart = now; }
        float h = Math.max(0.2f, b.getType().getHardness());
        long duration = (long) Math.max(800, Math.min(12_000, 700 + h * 1500));
        if (!canBreak(s, b)) duration *= 2; // na mao: mais lento
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
        java.util.Collection<ItemStack> drops = drops(s, b);
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
    /**
     * Junta material: quebra o bloco util mais facil num raio de 2 (nunca a coluna em que esta pisando).
     * Retorna true se achou algo para quebrar. nudge=true: se nao achou nada, da um pulo para destravar.
     */
    private boolean gather(Zombie z, State s, Block feet, Vector dir, boolean nudge) {
        if (s.invTotal >= capacity()) return false;
        if (s.breaking != null && breakable(s, s.breaking)) { progressBreak(z, s, s.breaking); return true; } // termina o que comecou
        Block best = null; double bestScore = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dyb = -1; dyb <= 1; dyb++) {
            if (dx == 0 && dz == 0) continue; // a propria coluna (chao/teto do pilar)
            Block b = feet.getRelative(dx, dyb, dz);
            if (!breakable(s, b)) continue;
            boolean useful = false;
            for (ItemStack d : drops(s, b)) if (buildable(d.getType())) { useful = true; break; }
            if (!useful) continue;
            double score = b.getType().getHardness() + Math.abs(dx) + Math.abs(dz) * 0.5 + (dyb == 1 ? 0.5 : 0);
            if (score < bestScore) { bestScore = score; best = b; }
        }
        if (best != null) { progressBreak(z, s, best); return true; }
        if (nudge && z.isOnGround()) z.setVelocity(dir.clone().multiply(0.25).setY(0.42)); // nada util em volta: anda um pouco
        return false;
    }

    // ---------------------------------------------------------------- escadas (lenhador crafta, todos sobem)

    private static BlockFace toFace(Vector dir) {
        return Math.abs(dir.getX()) > Math.abs(dir.getZ()) ? (dir.getX() > 0 ? BlockFace.EAST : BlockFace.WEST)
                : (dir.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
    }

    private static boolean isWood(Material m) { return Tag.PLANKS.isTagged(m) || Tag.LOGS.isTagged(m); }

    /** Crafta escadas "na mao" com a madeira do inventario: ~4 tabuas (ou 1 tora) = 3 escadas. */
    /** Madeira disponivel em "unidades de tabua" (tora = 4 tabuas). */
    private static int woodUnits(State s) {
        int units = 0;
        for (var e : s.inv.entrySet()) if (isWood(e.getKey())) units += e.getValue() * (Tag.LOGS.isTagged(e.getKey()) ? 4 : 1);
        return units;
    }

    /** Escadas: receita 3x3 (7 gravetos = 3 escadas, ~4 tabuas). So funciona perto de uma bancada. */
    private boolean craftLadders(State s) {
        if (woodUnits(s) < 4) return false;
        consumeWood(s, 4);
        s.ladders += 3;
        return true;
    }

    private static void consumeWood(State s, int units) {
        int toUse = units;
        for (var it = s.inv.entrySet().iterator(); it.hasNext() && toUse > 0; ) {
            var e = it.next();
            if (!isWood(e.getKey())) continue;
            int per = Tag.LOGS.isTagged(e.getKey()) ? 4 : 1;
            while (toUse > 0 && e.getValue() > 0) {
                e.setValue(e.getValue() - 1);
                s.invTotal--;
                toUse -= per;
            }
            if (e.getValue() == 0) it.remove();
        }
    }

    /**
     * Prepara as escadas no chao, como um jogador: crafta a bancada (2x2, 4 tabuas) se nao tiver, coloca do lado,
     * "usa" por 1,5s, crafta todas as escadas que a subida precisa e guarda a bancada de volta.
     * Retorna true enquanto estiver ocupado com isso.
     */
    private boolean prepareLadders(Zombie z, State s, Block feet, BlockFace towardWall, int need) {
        long now = System.currentTimeMillis();
        if (s.craftUntil > 0) {
            if (now < s.craftUntil) { z.swingMainHand(); return true; } // usando a bancada
            while (s.ladders < need && craftLadders(s)) { }                 // termina: crafta o lote
            s.craftUntil = 0;
            if (s.table != null && s.table.getType() == Material.CRAFTING_TABLE) { // guarda a bancada
                s.table.getWorld().spawnParticle(Particle.BLOCK, s.table.getLocation().add(0.5, 0.5, 0.5), 15, 0.3, 0.3, 0.3, s.table.getBlockData());
                s.table.setType(Material.AIR);
                s.hasTable = true;
            }
            s.table = null;
            return false;
        }
        int cost = 4 + (s.hasTable ? 0 : 4); // um lote de escadas (+ a bancada se ainda nao tiver)
        if (woodUnits(s) < cost) {
            if (now > s.gatherCooldownUntil) { startGather(s, Math.max(needFor(s, need - 1), cost), now); return true; }
            return false; // sem arvore por perto: deixa o pilar/outros agirem
        }
        s.action = "bancada: crafta escadas";
        Block spot = null;
        for (BlockFace f : new BlockFace[]{towardWall.getOppositeFace(), BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block b = feet.getRelative(f);
            if (f != towardWall && replaceable(b) && b.getRelative(BlockFace.DOWN).isSolid()) { spot = b; break; }
        }
        if (spot == null) return false; // sem lugar para a bancada: segue sem escada
        if (!s.hasTable) consumeWood(s, 4); // crafta a bancada na mao (receita 2x2)
        s.hasTable = false;
        spot.setType(Material.CRAFTING_TABLE);
        spot.getWorld().playSound(spot.getLocation(), Sound.BLOCK_WOOD_PLACE, 1f, 1f);
        s.table = spot;
        s.craftUntil = now + 1500;
        z.lookAt(spot.getLocation().add(0.5, 0.5, 0.5));
        return true;
    }

    private static Vector toVec(BlockFace f) { return new Vector(f.getModX(), 0, f.getModZ()); }

    /** Lenhador sem madeira por perto: anda ate a tora mais proxima (raio 12) e corta. Busca cacheada. */
    private boolean goToTree(Zombie z, State s, Block feet) {
        if (s.tree != null && !Tag.LOGS.isTagged(s.tree.getType())) s.tree = null; // ja cortada
        if (s.tree == null && System.currentTimeMillis() - s.lastTreeScan > 1500) {
            s.lastTreeScan = System.currentTimeMillis();
            double bd = Double.MAX_VALUE;
            for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) for (int dyb = -3; dyb <= 6; dyb++) {
                Block b = feet.getRelative(dx, dyb, dz);
                if (!Tag.LOGS.isTagged(b.getType())) continue;
                double d = dx * dx + dz * dz + dyb * dyb * 2;
                if (d < bd) { bd = d; s.tree = b; }
            }
        }
        if (s.tree == null) return false;
        Location tl = s.tree.getLocation().add(0.5, 0, 0.5);
        if (z.getLocation().distanceSquared(tl) <= 3.2 * 3.2) { progressBreak(z, s, s.tree); return true; }
        z.getPathfinder().moveTo(tl, 1.1);
        return true;
    }

    private static boolean placeLadder(Block at, BlockFace towardWall) {
        Block wall = at.getRelative(towardWall);
        if (!wall.getType().isOccluding() || !(at.isPassable() || at.isLiquid()) || at.getType() == Material.LADDER) return false;
        at.setType(Material.LADDER, false);
        if (at.getBlockData() instanceof org.bukkit.block.data.type.Ladder l) {
            l.setFacing(towardWall.getOppositeFace()); // escada olha para longe da parede
            at.setBlockData(l, false);
        }
        placeFx(at);
        return true;
    }

    /** Lenhador com parede na direcao do alvo: crafta (se preciso) e prega a primeira escada. */
    private boolean buildLadder(Zombie z, State s, Block feet, Vector dir, double dy) {
        BlockFace face = toFace(dir);
        if (!feet.getRelative(face).getType().isOccluding()) return false; // sem parede: usa pilar
        int need = (int) Math.ceil(dy) + 1;
        if (s.ladders < need && prepareLadders(z, s, feet, face, need)) return true; // na bancada / juntando madeira
        if (s.ladders == 0) return false;                                  // sem madeira nem escada: usa pilar
        if (!placeLadder(feet, face)) return false;
        s.ladders--;
        s.lastPlace = System.currentTimeMillis();
        return true;
    }

    /**
     * Dentro de uma escada: sobe 1 bloco por vez (o lenhador vai pregando a proxima se a parede continua).
     * Escada por perto: anda ate ela. No topo: passa para cima da parede.
     */
    private boolean climbLadder(Zombie z, State s, Location zl, Block feet, Vector dir) {
        if (feet.getType() == Material.LADDER) {
            BlockFace towardWall = feet.getBlockData() instanceof org.bukkit.block.data.type.Ladder l ? l.getFacing().getOppositeFace() : toFace(dir);
            Block above = feet.getRelative(BlockFace.UP);
            if (above.getType() != Material.LADDER && s.role == LUMBER && above.getRelative(towardWall).getType().isOccluding()) {
                if (s.ladders > 0 && placeLadder(above, towardWall)) s.ladders--;
            }
            if (above.getType() == Material.LADDER || (above.isPassable() && above.getRelative(BlockFace.UP).isPassable()
                    && !above.getRelative(towardWall).getType().isOccluding())) {
                if (above.getType() == Material.LADDER && above.getRelative(BlockFace.UP).isPassable()) {
                    z.teleport(above.getLocation().add(0.5, 0.05, 0.5).setDirection(dir));
                    return true;
                }
                // fim da escada: sobe para cima da parede
                Block top = feet.getRelative(towardWall).getRelative(BlockFace.UP);
                if (top.isPassable() && top.getRelative(BlockFace.UP).isPassable()) {
                    z.teleport(top.getLocation().add(0.5, 0.05, 0.5).setDirection(dir));
                    return true;
                }
            }
            return above.getType() == Material.LADDER; // escada continua mas bloqueada em cima: deixa o resto agir
        }
        // procura uma escada por perto (raio 3, altura do pe +-1) para usar
        Block best = null; double bd = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dyb = -1; dyb <= 1; dyb++) {
            Block b = feet.getRelative(dx, dyb, dz);
            if (b.getType() != Material.LADDER || b.getRelative(BlockFace.UP).getType() != Material.LADDER) continue;
            double d = dx * dx + dz * dz + dyb * dyb;
            if (d < bd) { bd = d; best = b; }
        }
        if (best == null) return false;
        if (bd <= 2.5) z.teleport(best.getLocation().add(0.5, 0.05, 0.5).setDirection(dir)); // encosta e entra na escada
        else z.getPathfinder().moveTo(best.getLocation().add(0.5, 0, 0.5), 1.15);
        return true;
    }

    /** Horda nao pega fogo com o sol (setShouldBurnInDay sozinho nao segurou no 26.3). */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onCombust(org.bukkit.event.entity.EntityCombustEvent e) {
        if (e instanceof org.bukkit.event.entity.EntityCombustByEntityEvent || e instanceof org.bukkit.event.entity.EntityCombustByBlockEvent) return;
        if (isHorde(e.getEntity()) || e.getEntity().getPersistentDataContainer().has(key, PersistentDataType.BYTE)) e.setCancelled(true);
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
        if (s.ladders > 0) e.getDrops().add(new ItemStack(Material.LADDER, Math.min(64, s.ladders)));
        if (s.hasTable) e.getDrops().add(new ItemStack(Material.CRAFTING_TABLE));
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
        int[] roles = new int[5];
        int blocks = 0;
        for (State s : horde.values()) { roles[s.role]++; blocks += s.invTotal; }
        return alive() + " zumbis (mineradores " + roles[MINER] + ", cavadores " + roles[DIGGER] + ", lenhadores " + roles[LUMBER] + ", brutos " + roles[BRUTE] + ", tanques " + roles[TANK]
                + ") carregando " + blocks + " blocos (limite " + capacity() + " cada)";
    }

    Component countLine() { return Component.text(alive() + " zumbis restantes"); }
}
