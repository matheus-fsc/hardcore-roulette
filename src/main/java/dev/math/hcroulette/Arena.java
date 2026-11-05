package dev.math.hcroulette;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Arena de teste (servidor separado com arena-mode: true): o proprio jogo como simulador.
 * Cola o terreno de uma onda gravada, recria a horda no lugar/papel de onde comecou, poe um substituto do
 * jogador (aldeao sem IA) seguindo o trajeto gravado ou uma estrategia, roda acelerado com /tick sprint e
 * imprime "ARENA_RESULT {json}" no fim. Generico: outras faccoes so precisam de um spawn proprio.
 *
 * /arena load <onda>                       cola o terreno
 * /arena run <onda> <estrategia> [seg] [tag]  estrategias: replay afk pillar fighter smart
 * /arena stop
 */
final class Arena implements Listener {

    static final Map<UUID, Double> HP = new HashMap<>();

    static double hp(UUID id) { return HP.getOrDefault(id, 20.0); }

    private static final double ARMOR = 0.4;

    private final HardcoreRoulette plugin;
    private final SmartHorde horde;
    private final World world;
    private String pasted;
    private int[] box; // x0 y0 z0 sx sy sz da ultima colagem

    // rodada
    private BukkitTask task;
    private Villager dummy;
    private String strategy, tag, wave;
    private final List<double[]> path = new ArrayList<>(); // t(ms), x, y, z
    private int startTick, maxTicks, lastHurt, hits, built, broke, swings;
    private double dmg;
    private int pathIdx;
    private boolean watch; // assistir em velocidade normal (sem /tick sprint)

    Arena(HardcoreRoulette plugin) {
        this.plugin = plugin;
        this.horde = new SmartHorde(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        World w = Bukkit.getWorld("arena");
        if (w == null) w = new WorldCreator("arena").generator(new ChunkGenerator() { }).createWorld(); // vazio
        world = w;
        rule(false, "advance_time", "doDaylightCycle");
        rule(false, "spawn_mobs", "doMobSpawning");
        rule(false, "advance_weather", "doWeatherCycle");
        rule(0, "random_tick_speed", "randomTickSpeed");
        world.setDifficulty(org.bukkit.Difficulty.HARD); // mundo novo nasce pacifico: zumbi sumia na hora
        world.setSpawnFlags(false, false);
        world.setTime(6000);
        world.setStorm(false);
        plugin.getLogger().info("ARENA pronta (mundo 'arena')");
    }

    /** Gamerule pelo nome (os nomes mudaram no 26.x: tenta os dois). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rule(Object value, String... names) {
        for (GameRule<?> g : GameRule.values()) {
            for (String n : names) {
                if (g.getName().equalsIgnoreCase(n) || g.getName().equalsIgnoreCase("minecraft:" + n)) {
                    try { world.setGameRule((GameRule) g, value); } catch (Exception ignored) { }
                    return;
                }
            }
        }
    }

    private File waveDir(String name) {
        File f = new File(name);
        if (f.isDirectory()) return f;
        return new File(plugin.getDataFolder(), "waves/" + name);
    }

    void command(CommandSender sender, String[] args) {
        try {
            if (args.length == 0) { sender.sendMessage("/arena load <onda> | run <onda> <estrategia> [seg] [tag] | stop"); return; }
            switch (args[0]) {
                case "load" -> paste(args[1], () -> plugin.getLogger().info("ARENA_LOADED " + args[1]));
                case "run" -> {
                    // parametros da rodada: tokens k=v (horde.p.k) ou opt:k=v (horde.k), so em memoria
                    List<String> pos = new ArrayList<>();
                    var cfg = plugin.getConfig();
                    for (String key : new ArrayList<>(cfg.getKeys(true))) if (key.startsWith("horde.p.")) cfg.set(key, null);
                    for (int i = 1; i < args.length; i++) {
                        String tok = args[i];
                        int eq = tok.indexOf('=');
                        if (eq < 0) { pos.add(tok); continue; }
                        String k = tok.substring(0, eq), v = tok.substring(eq + 1);
                        String path = k.startsWith("opt:") ? "horde." + k.substring(4) : "horde.p." + k;
                        if (v.equals("true") || v.equals("false")) cfg.set(path, Boolean.parseBoolean(v));
                        else try { cfg.set(path, Double.parseDouble(v)); } catch (NumberFormatException nf) { cfg.set(path, v); }
                    }
                    args = new String[pos.size() + 1];
                    args[0] = "run";
                    for (int i = 0; i < pos.size(); i++) args[i + 1] = pos.get(i);
                    final String[] fa = args;
                    String strat = fa.length > 2 ? fa[2] : "replay";
                    int secs = fa.length > 3 ? Integer.parseInt(fa[3]) : 300;
                    String tg = fa.length > 4 ? fa[4] : "-";
                    paste(fa[1], () -> {
                        try { start(fa[1], strat, secs, tg); }
                        catch (Exception ex) { plugin.getLogger().warning("ARENA_ERROR " + ex); }
                    });
                }
                case "stop" -> finish("parado");
                case "watch" -> {
                    watch = args.length < 2 || args[1].equalsIgnoreCase("on");
                    sender.sendMessage("modo assistir: " + (watch ? "velocidade normal" : "acelerado (tick sprint)"));
                }
                default -> sender.sendMessage("subcomando desconhecido");
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("ARENA_ERROR " + ex);
        }
    }

    // ------------------------------------------------------------------ terreno
    /** Cola (ou restaura) o terreno da onda em lotes por tick; chama done no fim. */
    private void paste(String name, Runnable done) throws Exception {
        File dir = waveDir(name);
        JsonObject meta = JsonParser.parseString(Files.readString(new File(dir, "meta.json").toPath())).getAsJsonObject();
        JsonArray o = meta.getAsJsonArray("origin"), sz = meta.getAsJsonArray("size"), pal = meta.getAsJsonArray("palette");
        int x0 = o.get(0).getAsInt(), y0 = o.get(1).getAsInt(), z0 = o.get(2).getAsInt();
        int sx = sz.get(0).getAsInt(), sy = sz.get(1).getAsInt(), szz = sz.get(2).getAsInt();
        Material[] mats = new Material[pal.size()];
        for (int i = 0; i < mats.length; i++) {
            Material m = Material.matchMaterial(pal.get(i).getAsString());
            mats[i] = m == null || !m.isBlock() ? Material.STONE : m;
        }
        byte[] data;
        try (GZIPInputStream in = new GZIPInputStream(new FileInputStream(new File(dir, "terrain.bin.gz")))) { data = in.readAllBytes(); }
        int[] oldBox = box;
        box = new int[]{x0, y0, z0, sx, sy, szz};
        for (int cx = (x0 >> 4) - 1; cx <= ((x0 + sx) >> 4) + 1; cx++)
            for (int cz = (z0 >> 4) - 1; cz <= ((z0 + szz) >> 4) + 1; cz++) world.setChunkForceLoaded(cx, cz, true);
        final byte[] d = data;
        final int total = sx * szz * sy;
        new org.bukkit.scheduler.BukkitRunnable() {
            int i = 0, clearI = 0;
            final int clearTotal = oldBox == null || java.util.Arrays.equals(oldBox, box) ? 0 : oldBox[3] * oldBox[4] * oldBox[5];
            @Override public void run() {
                int budget = 80_000;
                while (clearI < clearTotal && budget-- > 0) { // limpa a colagem anterior (outra onda)
                    int k = clearI++;
                    int y = k % oldBox[4], z = (k / oldBox[4]) % oldBox[5], x = k / (oldBox[4] * oldBox[5]);
                    world.getBlockAt(oldBox[0] + x, oldBox[1] + y, oldBox[2] + z).setType(Material.AIR, false);
                }
                while (i < total && budget-- > 0) {
                    int k = i++;
                    int y = k % sy, z = (k / sy) % szz, x = k / (sy * szz);
                    Material m = mats[d[k] & 0xff];
                    Block b = world.getBlockAt(x0 + x, y0 + y, z0 + z);
                    if (b.getType() != m) b.setType(m, false);
                }
                if (i >= total && clearI >= clearTotal) {
                    cancel();
                    pasted = name;
                    SmartHorde.ZPLACED.clear();
                    done.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ------------------------------------------------------------------ rodada
    private void start(String name, String strat, int secs, String tg) throws Exception {
        if (task != null) finish("substituido");
        File dir = waveDir(name);
        List<Location> spots = new ArrayList<>();
        List<Integer> roles = new ArrayList<>();
        path.clear();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(new File(dir, "frames.jsonl.gz"))), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while (true) {
                try { line = r.readLine(); } catch (java.io.EOFException eof) { break; }
                if (line == null) break;
                if (line.isBlank()) continue;
                JsonObject f;
                try { f = JsonParser.parseString(line).getAsJsonObject(); } catch (Exception bad) { break; }
                if (first) {
                    for (JsonElement ze : f.getAsJsonArray("z")) {
                        JsonArray z = ze.getAsJsonArray();
                        spots.add(new Location(world, z.get(2).getAsDouble(), z.get(3).getAsDouble(), z.get(4).getAsDouble()));
                        roles.add(z.get(1).getAsInt());
                    }
                    first = false;
                }
                JsonArray ps = f.getAsJsonArray("p");
                if (ps.size() > 0) {
                    JsonArray p = ps.get(0).getAsJsonArray();
                    path.add(new double[]{f.get("t").getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble(), p.get(3).getAsDouble()});
                }
            }
        }
        if (spots.isEmpty() || path.isEmpty()) { plugin.getLogger().warning("ARENA_ERROR onda sem zumbis ou sem jogador"); return; }
        double[] p0 = path.get(0);
        dummy = world.spawn(new Location(world, p0[1], p0[2], p0[3]), Villager.class, v -> {
            v.setAI(false); v.setSilent(true); v.setPersistent(true); v.setRemoveWhenFarAway(false);
            v.setGravity(false); v.customName(net.kyori.adventure.text.Component.text("alvo"));
        });
        SmartHorde.EXTRA_TARGETS.clear();
        SmartHorde.EXTRA_TARGETS.add(dummy.getUniqueId());
        HP.put(dummy.getUniqueId(), 20.0);
        strategy = strat; tag = tg; wave = name;
        startTick = Bukkit.getCurrentTick(); maxTicks = secs * 20; lastHurt = -9999;
        hits = built = broke = swings = 0; dmg = 0; pathIdx = 0;
        horde.spawnExact(world, spots, roles, dummy);
        if (!watch) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick sprint " + maxTicks);
        for (org.bukkit.entity.Player pl : Bukkit.getOnlinePlayers()) watchPlayer(pl);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        plugin.getLogger().info("ARENA_START " + name + " " + strat + " " + secs + "s (" + spots.size() + " zumbis)");
    }

    private void tick() {
        if (dummy == null || !dummy.isValid()) { finish("alvo sumiu"); return; }
        int t = Bukkit.getCurrentTick() - startTick;
        UUID id = dummy.getUniqueId();
        double hp = hp(id);
        // regeneracao natural (1 coracao a cada 4 s, 4 s depois do ultimo dano)
        if (hp < 20 && t - lastHurt > 80 && t % 80 == 0) HP.put(id, Math.min(20, hp + 1));
        switch (strategy) {
            case "replay" -> replay(t);
            case "pillar" -> { pillar(t); breakStruct(t); }
            case "fighter" -> fight(t);
            case "smart" -> { pillar(t); breakStruct(t); fight(t); }
            default -> { }
        }
        if (hp(id) <= 0) { finish("morreu"); return; }
        if (t >= maxTicks) { finish("tempo"); return; }
        if (horde.alive() == 0) { finish("horda morta"); return; }
        if (t % 10 == 0 && !Bukkit.getOnlinePlayers().isEmpty()) labels();
    }

    /** Nome + acao em cima de cada zumbi (so quando tem alguem assistindo). */
    private void labels() {
        horde.forEachState((z, role, action, inv) -> {
            z.customName(net.kyori.adventure.text.Component.text(role + " [" + inv + "] " + action));
            z.setCustomNameVisible(true);
        });
        dummy.customName(net.kyori.adventure.text.Component.text("ALVO " + Math.round(hp(dummy.getUniqueId()) * 10) / 10.0 + " vida"));
        dummy.setCustomNameVisible(true);
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> watchPlayer(e.getPlayer()), 5L);
    }

    /** Espectador: nao atrapalha (a horda ignora, o gravador nao grava) e vai para a rodada atual. */
    private void watchPlayer(org.bukkit.entity.Player p) {
        // Auto_Math entra criativo (voa e pode mexer); outros como espectador. A horda ignora os dois.
        p.setGameMode(p.getName().equals("Auto_Math") ? org.bukkit.GameMode.CREATIVE : org.bukkit.GameMode.SPECTATOR);
        p.setAllowFlight(true);
        p.setFlying(true);
        Location to = dummy != null ? dummy.getLocation().add(8, 6, 8)
                : box != null ? new Location(world, box[0] + box[3] / 2.0, box[1] + box[4] - 10, box[2] + box[5] / 2.0) : world.getSpawnLocation();
        to.setDirection(dummy != null ? dummy.getLocation().toVector().subtract(to.toVector()) : new Vector(0, -1, 0));
        p.teleport(to);
        p.sendMessage("Arena: " + p.getGameMode().name().toLowerCase() + ". /arena watch on = velocidade normal | /arena run <onda> <estrategia> [seg]");
    }

    private void replay(int t) {
        double ms = t * 50.0;
        while (pathIdx + 1 < path.size() && path.get(pathIdx + 1)[0] <= ms) pathIdx++;
        double[] p = path.get(pathIdx);
        Location cur = dummy.getLocation();
        if (Math.abs(cur.getX() - p[1]) + Math.abs(cur.getY() - p[2]) + Math.abs(cur.getZ() - p[3]) > 0.05)
            dummy.teleport(new Location(world, p[1], p[2], p[3], cur.getYaw(), cur.getPitch()));
    }

    private Zombie nearest(double max) {
        Zombie best = null; double bd = max * max;
        for (Entity e : dummy.getNearbyEntities(max, max, max)) {
            if (!(e instanceof Zombie z) || !horde.isHorde(z)) continue;
            double d = z.getLocation().distanceSquared(dummy.getLocation());
            if (d < bd) { bd = d; best = z; }
        }
        return best;
    }

    /** Sobe em pilar (ate 10) quando a horda chega a 30 blocos: 1 bloco a cada 0,5 s. */
    private void pillar(int t) {
        if (t % 10 != 0 || built >= 10 || nearest(30) == null) return;
        Block feet = dummy.getLocation().getBlock();
        if (!feet.getRelative(0, 2, 0).isPassable()) return;
        dummy.teleport(dummy.getLocation().add(0, 1, 0));
        feet.setType(Material.COBBLESTONE, false);
        built++;
    }

    /** Derruba o bloco colocado pela horda embaixo do zumbi mais alto ao alcance (4,5 blocos). */
    private void breakStruct(int t) {
        if (t % 8 != 0) return;
        Location eye = dummy.getEyeLocation();
        Block best = null; double by = -1e9;
        for (Entity e : dummy.getNearbyEntities(5, 6, 5)) {
            if (!(e instanceof Zombie z) || !horde.isHorde(z)) continue;
            Block under = z.getLocation().getBlock().getRelative(0, -1, 0);
            if (!SmartHorde.ZPLACED.contains(under)) continue;
            if (under.getLocation().add(0.5, 0.5, 0.5).distance(eye) > 4.5) continue;
            if (z.getLocation().getY() > by) { by = z.getLocation().getY(); best = under; }
        }
        if (best != null) { best.setType(Material.AIR); SmartHorde.ZPLACED.remove(best); broke++; }
    }

    /** Espada: 6 de dano a cada 0,6 s no zumbi mais perto (alcance 3), com empurrao. */
    private void fight(int t) {
        if (t % 12 != 0) return;
        Zombie z = nearest(3);
        if (z == null) return;
        z.damage(6, dummy);
        Vector kb = z.getLocation().toVector().subtract(dummy.getLocation().toVector()).setY(0);
        if (kb.lengthSquared() > 0.01) z.setVelocity(kb.normalize().multiply(0.4).setY(0.3));
        swings++;
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (dummy == null || !e.getEntity().getUniqueId().equals(dummy.getUniqueId())) return;
        e.setCancelled(true); // a vida do substituto e nossa (com armadura), nao a do aldeao
        if (!(e instanceof EntityDamageByEntityEvent by) || !(by.getDamager() instanceof Zombie)) return;
        double d = e.getDamage() * (1 - ARMOR);
        HP.merge(dummy.getUniqueId(), -d, Double::sum);
        dmg += d;
        hits++;
        lastHurt = Bukkit.getCurrentTick() - startTick;
    }

    @EventHandler
    public void onTransform(EntityTransformEvent e) {
        if (dummy != null && e.getEntity().getUniqueId().equals(dummy.getUniqueId())) e.setCancelled(true);
    }

    private void finish(String why) {
        if (task == null) return;
        task.cancel();
        task = null;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick sprint stop");
        int t = Bukkit.getCurrentTick() - startTick;
        int[] st = horde.stats();
        String rec = horde.recordingName();
        horde.despawn(false);
        boolean died = dummy != null && hp(dummy.getUniqueId()) <= 0;
        if (dummy != null) { SmartHorde.EXTRA_TARGETS.remove(dummy.getUniqueId()); HP.remove(dummy.getUniqueId()); dummy.remove(); }
        dummy = null;
        String json = String.format(java.util.Locale.ROOT,
                "{\"wave\":\"%s\",\"strategy\":\"%s\",\"tag\":\"%s\",\"end\":\"%s\",\"survival\":%.1f,\"died\":%b,\"dmg\":%.1f,"
                        + "\"hits\":%d,\"zombies_alive\":%d,\"zombie_blocks_left\":%d,\"player_built\":%d,\"player_broke\":%d,\"swings\":%d,\"recording\":\"%s\"}",
                wave, strategy, tag, why, t / 20.0, died, dmg, hits, st[0], st[1], built, broke, swings, rec);
        plugin.getLogger().info("ARENA_RESULT " + json);
    }
}
