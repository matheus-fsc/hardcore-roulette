package dev.math.hcroulette;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Hardcore em grupo: quando qualquer jogador morre, abre uma votacao de 60s entre
 * mundos pre-gerados; o vencedor vira o mundo atual e todos recomecam do zero nele.
 */
public final class HardcoreRoulette extends JavaPlugin implements Listener {

    private static final int POOL_SIZE = 4;
    private static final int VOTE_SECONDS = 15 * 60;
    private static final int ALL_VOTED_SECONDS = 5; // todos votaram -> reinicia rapido
    private static final int[] GUI_SLOTS = {1, 3, 5, 7};
    private static final int PREGEN_RADIUS = 6; // chunks ao redor do spawn gerados antes
    private static final String PREFIX = "hc_";

    private NamespacedKey runKey;
    private Invasion invasion;
    private Parties parties;
    private Coords coords;
    private BukkitTask migrateTask;
    private String current;
    private final List<String> pool = new ArrayList<>();
    private final List<String> trash = new ArrayList<>();
    private int run;
    private int nextId;

    private volatile boolean voting;
    private boolean refilling;
    private final Map<UUID, Integer> votes = new HashMap<>();
    private List<String> candidates = List.of();
    private BossBar bar;
    private BukkitTask voteTask;

    // ---------------------------------------------------------------- ciclo de vida

    private Arena arena;

    @Override
    public void onEnable() {
        runKey = new NamespacedKey(this, "run");
        getConfig().addDefault("on-death-commands", List.of("skills skill reset {player}"));
        getConfig().addDefault("mode", "votacao");
        getConfig().addDefault("pregen-radius", 300);
        getConfig().options().copyDefaults(true);
        if (getConfig().getBoolean("arena-mode", false)) { // servidor de teste: so horda + comandos da arena
            arena = new Arena(this);
            getLogger().info("MODO ARENA: mecanicas do hardcore desligadas");
            return;
        }
        loadState();
        loadStats();
        for (Player p : Bukkit.getOnlinePlayers()) sessionStart.put(p.getUniqueId(), System.currentTimeMillis());
        Bukkit.getScheduler().runTaskTimer(this, this::saveStats, 6000L, 6000L);
        deleteTrashNow();

        if (current == null || !worldExists(current)) {
            current = newName();
            run = Math.max(run, 0) + 1;
            createWorld(current, randomSeed());
        } else {
            createWorld(current, null);
        }
        pool.removeIf(n -> !worldExists(n) || createWorld(n, null) == null);
        saveState();

        getServer().getPluginManager().registerEvents(this, this);
        coords = new Coords(this);
        parties = new Parties(this);
        // tag do mundo no Tab (atualiza mudancas de mundo/portal)
        Bukkit.getScheduler().runTaskTimer(this, () -> { for (Player p : Bukkit.getOnlinePlayers()) updateTab(p); }, 100L, 100L);
        if (parties.enabled()) Bukkit.getScheduler().runTaskLater(this, parties::prepareAhead, 600L);
        invasion = new Invasion(this);
        getServer().getPluginManager().registerEvents(invasion, this);
        Bukkit.getScheduler().runTaskTimer(this, this::dayTick, 20L, 20L);
        Bukkit.getScheduler().runTaskLater(this, this::refillPool, 100L);
        // reservas que ja existiam e ainda nao foram pre-renderizadas (uma por vez, espacadas)
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (lobotomia()) chooseNext();
            else for (String n : new ArrayList<>(pool)) { World pw = Bukkit.getWorld(n); if (pw != null) chunkyStart(pw); }
        }, 600L);
        for (Player p : Bukkit.getOnlinePlayers()) syncPlayer(p);
        getLogger().info("Mundo atual: " + current + " | reserva: " + pool + " | run " + run);
    }

    @Override
    public void onDisable() {
        if (arena != null) return; // arena nao tem estado do hardcore para salvar
        if (bar != null) Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
        saveState();
        saveStats();
    }

    private void loadState() {
        var c = getConfig();
        current = c.getString("current");
        run = c.getInt("run", 0);
        nextId = c.getInt("next-id", 1);
        pool.clear();
        pool.addAll(c.getStringList("pool"));
        trash.clear();
        trash.addAll(c.getStringList("trash"));
    }

    private void saveState() {
        var c = getConfig();
        c.set("current", current);
        c.set("run", run);
        c.set("next-id", nextId);
        c.set("pool", new ArrayList<>(pool));
        c.set("trash", new ArrayList<>(trash));
        saveConfig();
    }

    // ---------------------------------------------------------------- mundos

    private String newName() {
        String n;
        do { n = PREFIX + (nextId++); } while (worldExists(n));
        return n;
    }

    private static long randomSeed() {
        return ThreadLocalRandom.current().nextLong();
    }

    /** 26.x guarda mundos extras em <level>/dimensions/minecraft/<nome>; versoes antigas na raiz. */
    private static List<File> worldFolders(String name) {
        File c = Bukkit.getWorldContainer();
        String main = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
        return List.of(new File(c, main + "/dimensions/minecraft/" + name), new File(c, name));
    }

    private boolean worldExists(String name) {
        if (Bukkit.getWorld(name) != null) return true;
        for (File f : worldFolders(name)) if (f.exists()) return true;
        return false;
    }

    World createWorld(String name, Long seed) {
        World w = Bukkit.getWorld(name);
        if (w == null) {
            WorldCreator c = new WorldCreator(name);
            if (name.endsWith("_nether")) c.environment(World.Environment.NETHER);
            else if (name.endsWith("_the_end")) c.environment(World.Environment.THE_END);
            if (seed != null) c.seed(seed);
            w = c.createWorld();
        }
        if (w != null) {
            w.setHardcore(true);
            w.setDifficulty(Difficulty.HARD);
        }
        return w;
    }

    static String baseOf(String worldName) {
        if (worldName.endsWith("_nether")) return worldName.substring(0, worldName.length() - 7);
        if (worldName.endsWith("_the_end")) return worldName.substring(0, worldName.length() - 8);
        return worldName;
    }

    private boolean inCurrentRun(World w) {
        return w != null && baseOf(w.getName()).equals(current);
    }

    private Location currentSpawn() {
        World w = createWorld(current, null);
        return w.getSpawnLocation().toBlockLocation().add(0.5, 0, 0.5);
    }

    /** Gera um mundo reserva por vez (cada criacao trava o servidor por alguns segundos). */
    /** Modo party usa mundos-nivel (hc_L#); usado pelas outras classes. */
    void trashWorld(String base) {
        for (String n : List.of(base, base + "_nether", base + "_the_end")) if (!trash.contains(n)) trash.add(n);
        saveState();
    }

    void cleanupTrashLater() { Bukkit.getScheduler().runTaskLater(this, this::cleanupTrash, 40L); }

    private boolean partyMode() { return parties != null && parties.enabled(); }

    private void refillPool() {
        if (refilling || partyMode()) return; // party: niveis sao criados pela classe Parties
        if (voting) { // nao travar o jogo durante a votacao
            Bukkit.getScheduler().runTaskLater(this, this::refillPool, 200L);
            return;
        }
        if (pool.size() >= POOL_SIZE) return;
        refilling = true;
        try {
            String name = newName();
            World w = createWorld(name, randomSeed());
            if (w != null) {
                pool.add(name);
                saveState();
                pregen(w);
                getLogger().info("Mundo reserva pronto: " + name + " (seed " + w.getSeed() + ")");
            }
        } finally {
            refilling = false;
        }
        if (pool.size() < POOL_SIZE) Bukkit.getScheduler().runTaskLater(this, this::refillPool, 100L);
    }

    /** Gera os chunks ao redor do spawn em segundo plano para o teleporte ser instantaneo. */
    private void pregen(World w) {
        Location s = w.getSpawnLocation();
        int cx = s.getBlockX() >> 4, cz = s.getBlockZ() >> 4;
        for (int dx = -PREGEN_RADIUS; dx <= PREGEN_RADIUS; dx++)
            for (int dz = -PREGEN_RADIUS; dz <= PREGEN_RADIUS; dz++)
                w.getChunkAtAsync(cx + dx, cz + dz, true);
        // votacao: qualquer reserva pode ganhar -> pre-renderiza todas; lobotomia: so a proxima sorteada
        if (lobotomia()) chooseNext(); else chunkyStart(w);
    }

    /** Lobotomia: proximo mundo sorteado com antecedencia; so ele recebe o Chunky. */
    private String nextWorld;

    private void chooseNext() {
        if (!lobotomia() || pool.isEmpty()) return;
        if (nextWorld == null || !pool.contains(nextWorld)) {
            // depois de um restart: reaproveita uma reserva que ja foi pre-renderizada
            List<String> ready = new ArrayList<>(pool);
            ready.retainAll(getConfig().getStringList("pregen-started"));
            List<String> from = ready.isEmpty() ? pool : ready;
            nextWorld = from.get(ThreadLocalRandom.current().nextInt(from.size()));
            getLogger().info("Lobotomia: proximo mundo sorteado = " + nextWorld);
        }
        World w = Bukkit.getWorld(nextWorld);
        if (w != null) chunkyStart(w);
    }

    /** Pre-renderiza uma area maior com o Chunky (em segundo plano), para o mundo novo nao ter lag de geracao. */
    void chunkyStart(World w) {
        if (Bukkit.getPluginManager().getPlugin("Chunky") == null) return;
        int radius = getConfig().getInt("pregen-radius", 300);
        if (radius <= 0) return;
        List<String> done = getConfig().getStringList("pregen-started");
        if (done.contains(w.getName())) return; // o proprio Chunky retoma tarefas apos restart
        Location s = w.getSpawnLocation();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "chunky start " + w.getName() + " square " + s.getBlockX() + " " + s.getBlockZ() + " " + radius);
        done.add(w.getName());
        getConfig().set("pregen-started", done);
        saveConfig();
        getLogger().info("Chunky: pre-renderizando " + w.getName() + " (raio " + radius + ")");
    }

    private void chunkyCancel(String world) {
        if (Bukkit.getPluginManager().getPlugin("Chunky") == null) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky cancel " + world);
        List<String> done = getConfig().getStringList("pregen-started");
        if (done.remove(world)) { getConfig().set("pregen-started", done); saveConfig(); }
    }

    private void cleanupTrash() {
        boolean pending = false;
        for (String name : new ArrayList<>(trash)) {
            if (!name.startsWith(PREFIX) || baseOf(name).equals(current) || pool.contains(name)) {
                trash.remove(name);
                continue;
            }
            World w = Bukkit.getWorld(name);
            if (w != null) chunkyCancel(name);
            List<File> folders = new ArrayList<>(worldFolders(name));
            if (w != null) {
                folders.add(w.getWorldFolder());
                for (Player p : w.getPlayers()) p.teleportAsync(currentSpawn());
                if (!Bukkit.unloadWorld(w, false)) { pending = true; continue; }
            }
            trash.remove(name);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> folders.forEach(this::deleteFolder));
        }
        saveState();
        if (pending) Bukkit.getScheduler().runTaskLater(this, this::cleanupTrash, 200L);
    }

    private void deleteTrashNow() {
        for (String name : new ArrayList<>(trash)) {
            if (name.startsWith(PREFIX)) worldFolders(name).forEach(this::deleteFolder);
        }
        trash.clear();
    }

    private void deleteFolder(File folder) {
        Path root = folder.toPath().toAbsolutePath().normalize();
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        if (!root.startsWith(container) || !root.getFileName().toString().startsWith(PREFIX) || !Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException e) {
            getLogger().warning("Falha ao apagar " + root + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- votacao

    private void startVote(Component reason) {
        if (voting) return;
        if (pool.isEmpty()) { // sem reserva pronta: gera uma na hora
            String name = newName();
            if (createWorld(name, randomSeed()) != null) pool.add(name);
        }
        voting = true;
        votes.clear();
        candidates = new ArrayList<>(pool.subList(0, Math.min(POOL_SIZE, pool.size())));

        if (lobotomia()) { // sem perguntas: mundo aleatorio quase na hora
            Bukkit.broadcast(Component.empty());
            Bukkit.broadcast(reason);
            Bukkit.broadcast(Component.text("🧠 MODO LOBOTOMIA: todo mundo pra um mundo aleatorio!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
            secondsLeft = 0;
            Bukkit.getScheduler().runTaskLater(this, this::finishVote, 20L);
            return;
        }

        Bukkit.broadcast(Component.empty());
        Bukkit.broadcast(reason);
        Bukkit.broadcast(Component.text("O mundo reinicia em 15 minutos, ou assim que todos votarem. Use /vote para escolher a proxima seed:", NamedTextColor.YELLOW));
        showCandidates();

        secondsLeft = VOTE_SECONDS;
        bar = BossBar.bossBar(barText(), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        Bukkit.getOnlinePlayers().forEach(p -> p.showBossBar(bar));

        voteTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            secondsLeft--;
            if (secondsLeft <= 0) { finishVote(); return; }
            bar.name(barText());
            bar.progress(Math.max(0f, Math.min(1f, secondsLeft / (float) VOTE_SECONDS)));
            if (secondsLeft == 600 || secondsLeft == 300 || secondsLeft == 60) {
                Bukkit.broadcast(Component.text("Faltam " + (secondsLeft / 60) + " min para o reset. Quem nao votou: /vote", NamedTextColor.YELLOW));
            }
            if (secondsLeft <= 5) Bukkit.broadcast(Component.text("Reiniciando em " + secondsLeft + "...", NamedTextColor.RED));
        }, 20L, 20L);
    }

    private int secondsLeft;

    private boolean lobotomia() {
        return "lobotomia".equalsIgnoreCase(getConfig().getString("mode", "votacao"));
    }

    /** Jogadores de verdade online (sem os bots de invasao). */
    private List<Player> humans() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (invasion == null || !invasion.isBot(p)) out.add(p);
        return out;
    }

    public boolean isVoting() { return voting; }

    public World currentWorld() {
        if (partyMode()) return parties.busiestWorld();
        return current == null ? null : Bukkit.getWorld(current);
    }

    private Component barText() {
        int online = humans().size();
        return Component.text(String.format("Novo mundo em %d:%02d  |  %d/%d votaram  |  /vote",
                secondsLeft / 60, secondsLeft % 60, countedVotes(), online));
    }

    /** Votos so contam de quem esta online. */
    private int countedVotes() {
        int n = 0;
        for (Player p : humans()) if (votes.containsKey(p.getUniqueId())) n++;
        return n;
    }

    private void checkAllVoted() {
        if (!voting) return;
        int online = humans().size();
        if (online > 0 && countedVotes() >= online && secondsLeft > ALL_VOTED_SECONDS) {
            secondsLeft = ALL_VOTED_SECONDS;
            Bukkit.broadcast(Component.text("Todo mundo votou! Reiniciando em " + ALL_VOTED_SECONDS + "s.", NamedTextColor.GREEN));
        }
        if (bar != null) bar.name(barText());
    }

    // ---------------------------------------------------------------- GUI (bau virtual)

    private static class VoteMenu implements InventoryHolder {
        protected Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    /** Menu so de leitura; herda de VoteMenu para reaproveitar o bloqueio de cliques. */
    private static final class StatsMenu extends VoteMenu { }

    // ---------------------------------------------------------------- estatisticas

    private static final class Stats {
        String name = "?";
        int deaths;
        long playSeconds;
        long lifeSeconds; // tempo jogado (online, em survival) na vida atual
        double lifeDamage;
        double bestLifeDamage;
        double best() { return Math.max(bestLifeDamage, lifeDamage); }
    }

    private final Map<UUID, Stats> stats = new HashMap<>();
    private final Map<UUID, Long> sessionStart = new HashMap<>();
    private File statsFile;

    private Stats stats(UUID id) { return stats.computeIfAbsent(id, k -> new Stats()); }

    private long playSeconds(UUID id) {
        long s = stats(id).playSeconds;
        Long start = sessionStart.get(id);
        if (start != null) s += (System.currentTimeMillis() - start) / 1000;
        return s;
    }

    private void loadStats() {
        statsFile = new File(getDataFolder(), "stats.yml");
        var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(statsFile);
        for (String key : y.getKeys(false)) {
            try {
                Stats s = stats(UUID.fromString(key));
                s.name = y.getString(key + ".name", "?");
                s.deaths = y.getInt(key + ".deaths");
                s.playSeconds = y.getLong(key + ".play-seconds");
                s.lifeDamage = y.getDouble(key + ".life-damage");
                s.bestLifeDamage = y.getDouble(key + ".best-life-damage");
                s.lifeSeconds = y.getLong(key + ".life-seconds");
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void saveStats() {
        // fecha as sessoes abertas para nao perder tempo jogado num crash/restart
        long now = System.currentTimeMillis();
        for (var e : sessionStart.entrySet()) {
            stats(e.getKey()).playSeconds += (now - e.getValue()) / 1000;
            e.setValue(now);
        }
        var y = new org.bukkit.configuration.file.YamlConfiguration();
        for (var e : stats.entrySet()) {
            String k = e.getKey().toString();
            Stats s = e.getValue();
            y.set(k + ".name", s.name);
            y.set(k + ".deaths", s.deaths);
            y.set(k + ".play-seconds", s.playSeconds);
            y.set(k + ".life-damage", s.lifeDamage);
            y.set(k + ".best-life-damage", s.bestLifeDamage);
            y.set(k + ".life-seconds", s.lifeSeconds);
        }
        try { y.save(statsFile); } catch (IOException ex) { getLogger().warning("Falha ao salvar stats: " + ex.getMessage()); }
    }

    /** Encerra a "vida" atual do jogador (morte ou reset do mundo). */
    private void endLife(UUID id) {
        Stats s = stats(id);
        s.bestLifeDamage = s.best();
        s.lifeDamage = 0;
        s.lifeSeconds = 0;
    }

    // ---------------------------------------------------------------- contador de dias

    private long lastPhaseKey = -1;

    /** Tempo com unidade que cresce junto com o progresso: 45s -> 12min 30s -> 2h 05min -> 3d 4h. */
    static String fmtAlive(long s) {
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "min " + (s % 60) + "s";
        if (s < 86400) return (s / 3600) + "h " + String.format("%02d", (s % 3600) / 60) + "min";
        return (s / 86400) + "d " + ((s % 86400) / 3600) + "h";
    }

    private static String phaseName(long t) {
        return t < 6000 ? "Manha" : t < 12000 ? "Tarde" : "Noite";
    }

    private static String clock(long t) {
        long h = (t / 1000 + 6) % 24, m = (t % 1000) * 60 / 1000;
        return String.format("%02d:%02d", h, m);
    }

    /** Roda a cada segundo: soma tempo de vida e anuncia mudanca de periodo do dia. */
    private void dayTick() {
        for (Player p : humans()) {
            GameMode gm = p.getGameMode();
            if (!p.isDead() && (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE)) stats(p.getUniqueId()).lifeSeconds++;
        }
        World w = currentWorld();
        if (w == null || voting) return;
        long t = w.getTime();
        long day = w.getFullTime() / 24000L + 1;
        int phase = t < 6000 ? 0 : t < 12000 ? 1 : 2;
        long key = day * 3 + phase;
        if (key == lastPhaseKey) return;
        boolean first = lastPhaseKey == -1;
        lastPhaseKey = key;
        if (!first) announceDay(w);
    }

    private void announceDayTo(CommandSender to, World w) {
        long t = w.getTime();
        to.sendMessage(Component.text("Dia " + (w.getFullTime() / 24000L + 1) + " — " + phaseName(t) + "  " + clock(t) + "  |  run #" + run, NamedTextColor.GOLD));
        for (Player p : humans()) {
            to.sendMessage(Component.text("  ♥ " + p.getName() + ": vivo ha " + fmtAlive(stats(p.getUniqueId()).lifeSeconds), NamedTextColor.GREEN));
        }
    }

    // ---------------------------------------------------------------- Bedrock (Geyser/Floodgate no proxy)

    /** Floodgate entrega o nome como ".Gamertag" (espacos viram _). */
    static String bedrockKey(String gamertag) {
        String g = gamertag.startsWith(".") ? gamertag.substring(1) : gamertag;
        return g.replace(' ', '_').toLowerCase(java.util.Locale.ROOT);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH)
    public void onBedrockLogin(org.bukkit.event.player.PlayerLoginEvent e) {
        String name = e.getPlayer().getName();
        if (!name.startsWith(".") || e.getResult() != org.bukkit.event.player.PlayerLoginEvent.Result.KICK_WHITELIST) return;
        if (getConfig().getStringList("bedrock-whitelist").contains(bedrockKey(name))) e.allow();
    }

    private void announceDay(World w) {
        long t = w.getTime();
        long day = w.getFullTime() / 24000L + 1;
        String icon = t < 6000 ? "☀" : t < 12000 ? "🌤" : "🌙";
        NamedTextColor color = t < 6000 ? NamedTextColor.YELLOW : t < 12000 ? NamedTextColor.GOLD : NamedTextColor.BLUE;
        Bukkit.broadcast(Component.text(icon + " Dia " + day + " — " + phaseName(t), color, TextDecoration.BOLD)
                .append(Component.text("  " + clock(t) + "  |  run #" + run, NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false)));
        List<Player> hs = humans();
        if (hs.isEmpty()) return;
        hs.sort(Comparator.comparingLong((Player p) -> stats(p.getUniqueId()).lifeSeconds).reversed());
        Component line = Component.text("  ♥ Vivos: ", NamedTextColor.RED);
        for (int i = 0; i < hs.size(); i++) {
            Player p = hs.get(i);
            if (i > 0) line = line.append(Component.text("  ·  ", NamedTextColor.DARK_GRAY));
            line = line.append(Component.text(p.getName() + " ", NamedTextColor.WHITE))
                    .append(Component.text(fmtAlive(stats(p.getUniqueId()).lifeSeconds), NamedTextColor.GREEN));
        }
        Bukkit.broadcast(line);
    }

    private static String fmtTime(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60;
        return h > 0 ? h + "h " + m + "min" : m + "min";
    }

    private void openStats(Player viewer) {
        StatsMenu holder = new StatsMenu();
        holder.inv = Bukkit.createInventory(holder, 27, Component.text("Ranking BABYLON FLAMES"));
        Inventory inv = holder.inv;

        List<UUID> ids = new ArrayList<>(stats.keySet());

        List<UUID> mostDeaths = new ArrayList<>(ids);
        mostDeaths.removeIf(id -> stats(id).deaths == 0);
        mostDeaths.sort(Comparator.comparingInt((UUID id) -> stats(id).deaths).reversed()
                .thenComparing(Comparator.comparingLong(this::playSeconds).reversed()));

        List<UUID> leastDeaths = new ArrayList<>(ids);
        leastDeaths.sort(Comparator.comparingInt((UUID id) -> stats(id).deaths)
                .thenComparing(Comparator.comparingLong(this::playSeconds).reversed()));

        List<UUID> mostDamage = new ArrayList<>(ids);
        mostDamage.removeIf(id -> stats(id).best() <= 0);
        mostDamage.sort(Comparator.comparingDouble((UUID id) -> stats(id).best()).reversed());

        inv.setItem(0, label(Material.WITHER_SKELETON_SKULL, "☠ Mais mortes", NamedTextColor.DARK_RED));
        inv.setItem(9, label(Material.TOTEM_OF_UNDYING, "✚ Menos mortes + tempo jogado", NamedTextColor.GREEN));
        inv.setItem(18, label(Material.NETHERITE_CHESTPLATE, "⚔ Mais dano recebido em 1 vida", NamedTextColor.GOLD));
        podium(inv, 2, mostDeaths);
        podium(inv, 11, leastDeaths);
        podium(inv, 20, mostDamage);
        viewer.openInventory(inv);
    }

    private static ItemStack label(Material m, String text, NamedTextColor color) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(text, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    private void podium(Inventory inv, int firstSlot, List<UUID> ranking) {
        NamedTextColor[] medal = {NamedTextColor.GOLD, NamedTextColor.GRAY, NamedTextColor.RED, NamedTextColor.WHITE, NamedTextColor.WHITE};
        for (int i = 0; i < 5 && i < ranking.size(); i++) {
            UUID id = ranking.get(i);
            Stats s = stats(id);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) {
                sm.setOwningPlayer(Bukkit.getOfflinePlayer(id));
                sm.displayName(Component.text("#" + (i + 1) + " " + s.name, medal[i], TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
                sm.lore(List.of(
                        Component.text("Mortes: " + s.deaths, NamedTextColor.RED).decoration(TextDecoration.ITALIC, false),
                        Component.text("Tempo jogado: " + fmtTime(playSeconds(id)), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false),
                        Component.text(String.format("Mais dano em 1 vida: %.1f ❤", s.best() / 2.0), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)));
                head.setItemMeta(sm);
            }
            inv.setItem(firstSlot + i, head);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (invasion.isBot(p)) return;
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return;
        stats(p.getUniqueId()).lifeDamage += e.getFinalDamage();
    }

    private void openMenu(Player p) {
        if (!voting) { p.sendMessage(Component.text("Nao tem votacao aberta agora.", NamedTextColor.RED)); return; }
        VoteMenu holder = new VoteMenu();
        holder.inv = Bukkit.createInventory(holder, 9, Component.text("Escolha o proximo mundo"));
        fillMenu(holder.inv, p);
        p.openInventory(holder.inv);
    }

    private void fillMenu(Inventory inv, Player viewer) {
        inv.clear();
        Integer mine = votes.get(viewer.getUniqueId());
        for (int i = 0; i < candidates.size() && i < GUI_SLOTS.length; i++) {
            World w = Bukkit.getWorld(candidates.get(i));
            if (w == null) continue;
            int n = i + 1;
            String biome = spawnBiome(w);
            ItemStack item = new ItemStack(iconFor(biome));
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text("Mundo " + n, NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Seed: " + w.getSeed(), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                    Component.text("Spawn: " + biome, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text(votesFor(n) + " voto(s)", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text(Integer.valueOf(n).equals(mine) ? "✔ Seu voto" : "Clique para votar",
                            Integer.valueOf(n).equals(mine) ? NamedTextColor.GREEN : NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(Integer.valueOf(n).equals(mine));
            item.setItemMeta(meta);
            inv.setItem(GUI_SLOTS[i], item);
        }
    }

    private void refreshMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof VoteMenu m && !(m instanceof StatsMenu)) fillMenu(m.getInventory(), p);
        }
    }

    private static String spawnBiome(World w) {
        return w.getBiome(w.getSpawnLocation()).getKey().getKey().replace('_', ' ');
    }

    private static Material iconFor(String biome) {
        if (biome.contains("desert")) return Material.SAND;
        if (biome.contains("badlands")) return Material.RED_SAND;
        if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice")) return Material.SNOW_BLOCK;
        if (biome.contains("jungle")) return Material.JUNGLE_SAPLING;
        if (biome.contains("ocean") || biome.contains("river")) return Material.WATER_BUCKET;
        if (biome.contains("mushroom")) return Material.RED_MUSHROOM_BLOCK;
        if (biome.contains("swamp")) return Material.LILY_PAD;
        if (biome.contains("savanna")) return Material.ACACIA_SAPLING;
        if (biome.contains("taiga")) return Material.SPRUCE_SAPLING;
        if (biome.contains("cherry")) return Material.CHERRY_SAPLING;
        if (biome.contains("birch")) return Material.BIRCH_SAPLING;
        if (biome.contains("dark forest")) return Material.DARK_OAK_SAPLING;
        if (biome.contains("forest")) return Material.OAK_SAPLING;
        if (biome.contains("peak") || biome.contains("slope") || biome.contains("hills") || biome.contains("stony")) return Material.STONE;
        if (biome.contains("beach")) return Material.SANDSTONE;
        return Material.GRASS_BLOCK;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof VoteMenu menu)) return;
        e.setCancelled(true);
        if (menu instanceof StatsMenu) return;
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        for (int i = 0; i < GUI_SLOTS.length; i++) {
            if (GUI_SLOTS[i] == e.getRawSlot() && i < candidates.size()) {
                vote(p, i + 1);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                return;
            }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof VoteMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (invasion.isBot(e.getPlayer())) { invasion.handleQuit(e); return; }
        Long start = sessionStart.remove(e.getPlayer().getUniqueId());
        if (start != null) stats(e.getPlayer().getUniqueId()).playSeconds += (System.currentTimeMillis() - start) / 1000;
        // quem saiu deixa de contar; reavalia no proximo tick (o jogador ainda esta na lista agora)
        Bukkit.getScheduler().runTask(this, this::checkAllVoted);
    }

    private int votesFor(int option) {
        int n = 0;
        for (int v : votes.values()) if (v == option) n++;
        return n;
    }

    private void showCandidates() {
        for (int i = 0; i < candidates.size(); i++) {
            World w = Bukkit.getWorld(candidates.get(i));
            if (w == null) continue;
            String biome = spawnBiome(w);
            int n = i + 1;
            Component line = Component.text(" [" + n + "] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text("Seed " + w.getSeed(), NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
                    .append(Component.text("  spawn: " + biome, NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                    .append(Component.text("  (" + votesFor(n) + " votos)", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, false))
                    .clickEvent(ClickEvent.runCommand("/vote " + n))
                    .hoverEvent(HoverEvent.showText(Component.text("Clique para votar no mundo " + n)));
            Bukkit.broadcast(line);
        }
    }

    private void vote(Player p, int option) {
        if (!voting) { p.sendMessage(Component.text("Nao tem votacao aberta agora.", NamedTextColor.RED)); return; }
        if (option < 1 || option > candidates.size()) {
            p.sendMessage(Component.text("Escolha entre 1 e " + candidates.size() + ".", NamedTextColor.RED));
            return;
        }
        votes.put(p.getUniqueId(), option);
        Bukkit.broadcast(Component.text(p.getName() + " votou no mundo " + option + " (" + votesFor(option) + " votos)", NamedTextColor.AQUA));
        refreshMenus();
        checkAllVoted();
    }

    private void finishVote() {
        voting = false;
        if (voteTask != null) voteTask.cancel();
        if (bar != null) { Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(bar)); bar = null; }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof VoteMenu m && !(m instanceof StatsMenu)) p.closeInventory();
        }

        int best = -1;
        List<Integer> top = new ArrayList<>();
        for (int i = 1; i <= candidates.size(); i++) {
            int v = votesFor(i);
            if (v > best) { best = v; top.clear(); }
            if (v == best) top.add(i);
        }
        int chosen = top.get(ThreadLocalRandom.current().nextInt(top.size()));
        // lobotomia: o mundo ja sorteado (e pre-renderizado) e o escolhido
        if (lobotomia() && nextWorld != null && candidates.contains(nextWorld)) chosen = candidates.indexOf(nextWorld) + 1;
        String winner = candidates.get(chosen - 1);

        String old = current;
        current = winner;
        pool.remove(winner);
        nextWorld = null;
        chooseNext();
        run++;
        trash.add(old);
        trash.add(old + "_nether");
        trash.add(old + "_the_end");
        saveState();

        World nw = createWorld(current, null);
        nw.setFullTime(0); // run nova comeca no Dia 1
        lastPhaseKey = -1;
        nw.setStorm(false);
        nw.setThundering(false);
        Location spawn = currentSpawn();
        invasion.abortSilently();
        for (Player p : humans()) resetPlayer(p, spawn);

        Bukkit.broadcast(Component.text("Mundo " + chosen + " venceu! Seed " + nw.getSeed() + " - run #" + run, NamedTextColor.GREEN, TextDecoration.BOLD));
        Title t = Title.title(Component.text("Run #" + run, NamedTextColor.RED),
                Component.text("Seed " + nw.getSeed(), NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1)));
        Bukkit.getOnlinePlayers().forEach(p -> p.showTitle(t));

        Bukkit.getScheduler().runTaskLater(this, this::cleanupTrash, 100L);
        Bukkit.getScheduler().runTaskLater(this, this::refillPool, 300L);
    }

    // ---------------------------------------------------------------- jogadores

    /** Jogadores entre o reset e o fim do teleporte: nao podem pegar itens do mundo antigo. */
    private final java.util.Set<UUID> inTransit = new java.util.HashSet<>();

    /** Carencia depois do reset: nenhum dano (queda acumulada no mundo antigo, mob no spawn...). */
    private final Map<UUID, Long> graceUntil = new HashMap<>();
    private static final long GRACE_MS = 8000;

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGraceDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Long until = graceUntil.get(p.getUniqueId());
        if (until == null) return;
        if (System.currentTimeMillis() < until || inTransit.contains(p.getUniqueId())) {
            e.setCancelled(true);
            p.setFallDistance(0f);
        } else graceUntil.remove(p.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && inTransit.contains(p.getUniqueId())) e.setCancelled(true);
    }

    private void wipeItems(Player p) {
        p.closeInventory(); // devolve o grid de crafting para o inventario antes de limpar
        p.getInventory().clear();
        p.getEnderChest().clear();
        p.setItemOnCursor(null);
        p.setExp(0f);
        p.setLevel(0);
        p.setTotalExperience(0);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
    }

    void resetPlayer(Player p, Location spawn) {
        if (p.isDead()) p.spigot().respawn();
        inTransit.add(p.getUniqueId());
        graceUntil.put(p.getUniqueId(), System.currentTimeMillis() + GRACE_MS);
        p.setFallDistance(0f);
        p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        wipeItems(p);
        p.setGameMode(GameMode.SURVIVAL);
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max != null ? max.getValue() : 20.0);
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setExhaustion(0f);
        p.setExp(0f);
        p.setLevel(0);
        p.setTotalExperience(0);
        p.setFireTicks(0);
        p.setFreezeTicks(0);
        p.setFallDistance(0f);
        p.setRespawnLocation(null);
        endLife(p.getUniqueId());
        for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
            AdvancementProgress prog = p.getAdvancementProgress(it.next());
            for (String c : prog.getAwardedCriteria()) prog.revokeCriteria(c);
        }
        p.getPersistentDataContainer().set(runKey, PersistentDataType.INTEGER, run);
        UUID id = p.getUniqueId();
        p.teleportAsync(spawn).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(this, () -> {
            Player now = Bukkit.getPlayer(id);
            if (now != null) {
                if (!baseOf(now.getWorld().getName()).equals(baseOf(spawn.getWorld().getName()))) now.teleport(spawn); // teleporte async falhou: forca
                wipeItems(now); // remove o que tenha pego no mundo antigo durante o carregamento
                now.setFallDistance(0f); // queda acumulada no mundo antigo nao vale no novo
                now.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                now.setNoDamageTicks(100);
                graceUntil.put(id, System.currentTimeMillis() + GRACE_MS); // carencia conta a partir da chegada
                AttributeInstance mh = now.getAttribute(Attribute.MAX_HEALTH);
                now.setHealth(mh != null ? mh.getValue() : 20.0);
                now.setFoodLevel(20);
                now.setFireTicks(0);
            }
            inTransit.remove(id);
        }));
    }

    /** Quem entra depois de um reset (ou pela primeira vez) recomeca no mundo atual. */
    /** Tag no Tab: [W#3] Nick (party) ou [#12] Nick (votacao/lobotomia). */
    void updateTab(Player p) {
        if (invasion != null && invasion.isBot(p)) return;
        String tag;
        NamedTextColor color;
        if (partyMode()) {
            String base = baseOf(p.getWorld().getName());
            tag = base.startsWith("hc_L") ? "W#" + base.substring(4) : "W#?";
            color = NamedTextColor.RED;
        } else {
            tag = "#" + run;
            color = NamedTextColor.GOLD;
        }
        p.playerListName(Component.text("[" + tag + "] ", color).append(Component.text(p.getName(), NamedTextColor.WHITE)));
    }

    private void syncPlayer(Player p) {
        if (partyMode()) {
            parties.sync(p);
            Bukkit.getScheduler().runTaskLater(this, () -> updateTab(p), 40L);
            return;
        }
        Integer r = p.getPersistentDataContainer().get(runKey, PersistentDataType.INTEGER);
        if (r == null || r != run) {
            resetPlayer(p, currentSpawn());
            p.sendMessage(Component.text("Hardcore run #" + run + ". Se alguem morrer, todo mundo recomeca!", NamedTextColor.GOLD));
        } else if (!inCurrentRun(p.getWorld())) {
            p.teleportAsync(currentSpawn());
        }
        if (bar != null) p.showBossBar(bar);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (invasion.handleJoin(e)) return;
        sessionStart.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        stats(e.getPlayer().getUniqueId()).name = e.getPlayer().getName();
        syncPlayer(e.getPlayer());
        if (voting) {
            e.getPlayer().sendMessage(Component.text("Votacao de reset aberta! Use /vote para escolher o proximo mundo.", NamedTextColor.YELLOW));
            if (bar != null) bar.name(barText());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (invasion.handleDeath(e)) return; // bot de invasao: nao e morte hardcore
        Player dead = e.getPlayer();
        stats(dead.getUniqueId()).name = dead.getName();
        stats(dead.getUniqueId()).deaths++;
        endLife(dead.getUniqueId());
        // So quem morreu perde as skills (comandos configurados em config.yml)
        for (String cmd : getConfig().getStringList("on-death-commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", dead.getName()));
        }
        Component msg = e.deathMessage();
        Component reason = Component.text("☠ ", NamedTextColor.DARK_RED)
                .append(msg != null ? msg.colorIfAbsent(NamedTextColor.RED)
                        : Component.text(e.getPlayer().getName() + " morreu", NamedTextColor.RED));
        if (partyMode()) { parties.onDeath(dead, reason); return; } // so a party de quem morreu avanca
        startVote(reason);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (invasion.isBot(e.getPlayer())) return;
        if (partyMode()) {
            World pw = parties.worldFor(e.getPlayer());
            if (!baseOf(e.getRespawnLocation().getWorld().getName()).equals(pw.getName())) e.setRespawnLocation(parties.spawnOf(pw));
            return;
        }
        if (!inCurrentRun(e.getRespawnLocation().getWorld())) e.setRespawnLocation(currentSpawn());
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!voting) return;
        String s = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (!s.matches("[1-9]")) return;
        e.setCancelled(true);
        int n = Integer.parseInt(s);
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> vote(p, n));
    }

    // ---------------------------------------------------------------- portais (nether/end por run)

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        Location to = mapPortal(e.getFrom(), e.getTo(), e.getPlayer());
        if (to != null) e.setTo(to);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        Location to = mapPortal(e.getFrom(), e.getTo(), null);
        if (to != null) e.setTo(to);
    }

    private Location mapPortal(Location from, Location vanillaTo, Player player) {
        if (from == null || vanillaTo == null || from.getWorld() == null || vanillaTo.getWorld() == null) return null;
        String base = baseOf(from.getWorld().getName());
        if (!base.startsWith(PREFIX)) return null;
        World.Environment fromEnv = from.getWorld().getEnvironment();
        World.Environment toEnv = vanillaTo.getWorld().getEnvironment();
        World overworld = createWorld(base, null);

        switch (toEnv) {
            case NETHER -> {
                World nether = createWorld(base + "_nether", overworld.getSeed());
                double y = Math.max(nether.getMinHeight() + 2, Math.min(from.getY(), 120));
                return new Location(nether, from.getX() / 8.0, y, from.getZ() / 8.0, from.getYaw(), from.getPitch());
            }
            case THE_END -> {
                World end = createWorld(base + "_the_end", overworld.getSeed());
                buildEndPlatform(end);
                return new Location(end, 100.5, 49, 0.5, 90f, 0f);
            }
            case NORMAL -> {
                if (fromEnv == World.Environment.NETHER) {
                    return new Location(overworld, from.getX() * 8.0, from.getY(), from.getZ() * 8.0, from.getYaw(), from.getPitch());
                }
                if (player != null) {
                    Location r = player.getRespawnLocation();
                    if (r != null && r.getWorld() != null && r.getWorld().equals(overworld)) return r;
                }
                return overworld.getSpawnLocation().toBlockLocation().add(0.5, 0, 0.5);
            }
            default -> { return null; }
        }
    }

    private static void buildEndPlatform(World end) {
        for (int x = 98; x <= 102; x++) {
            for (int z = -2; z <= 2; z++) {
                end.getBlockAt(x, 48, z).setType(Material.OBSIDIAN);
                for (int y = 49; y <= 51; y++) end.getBlockAt(x, y, z).setType(Material.AIR);
            }
        }
    }

    // ---------------------------------------------------------------- migracao PC <-> VPS

    /** host.txt na raiz do servidor diz onde este servidor roda: "pc" ou "vps" (nao e sincronizado). */
    private String host() {
        try {
            return Files.readString(new File(Bukkit.getWorldContainer(), "host.txt").toPath()).trim().toLowerCase();
        } catch (IOException e) {
            return "?";
        }
    }

    private boolean migrating;

    private void migrate(CommandSender sender) {
        if (!"pc".equals(host())) {
            sender.sendMessage(Component.text("Este ja e o servidor da VPS. Para voltar pro PC, o host abre o launcher no PC dele (a VPS desliga sozinha).", NamedTextColor.YELLOW));
            return;
        }
        if (migrating) return;
        migrating = true;
        try {
            Files.writeString(new File(Bukkit.getWorldContainer(), "migrate.flag").toPath(), "vps");
        } catch (IOException e) {
            migrating = false;
            sender.sendMessage(Component.text("Falha ao marcar a migracao: " + e.getMessage(), NamedTextColor.RED));
            return;
        }
        Bukkit.broadcast(Component.text("✈ " + sender.getName() + " vai sair: o servidor migra para a VPS em 10s.", NamedTextColor.GOLD, TextDecoration.BOLD));
        Bukkit.broadcast(Component.text("Reconecte no MESMO endereco em ~2 minutos. O mundo vai junto.", NamedTextColor.YELLOW));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Component msg = Component.text("Servidor migrando para a VPS.\n", NamedTextColor.GOLD)
                    .append(Component.text("Reconecte no mesmo endereco em ~2 minutos.", NamedTextColor.YELLOW));
            for (Player p : Bukkit.getOnlinePlayers()) p.kick(msg);
            Bukkit.shutdown();
        }, 200L);
    }

    // ---------------------------------------------------------------- comandos

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("arena")) {
            if (arena == null) sender.sendMessage("arena-mode desligado neste servidor");
            else arena.command(sender, args);
            return true;
        }
        if (arena != null) return true; // na arena os outros comandos nao existem
        switch (cmd.getName().toLowerCase()) {
            case "vote" -> {
                if (!(sender instanceof Player p)) return true;
                if (args.length == 0) { openMenu(p); return true; }
                try { vote(p, Integer.parseInt(args[0])); } catch (NumberFormatException ex) { openMenu(p); }
            }
            case "hcstatus" -> {
                sender.sendMessage(Component.text("Run #" + run + " | mundo " + current
                        + " (seed " + createWorld(current, null).getSeed() + ") | reservas prontas: " + pool.size()
                        + " | modo " + getConfig().getString("mode", "votacao")
                        + (lobotomia() && nextWorld != null ? " | proximo: " + nextWorld : "")
                        + (voting ? " | VOTACAO ABERTA" : ""), NamedTextColor.GOLD));
            }
            case "hcmodo" -> {
                if (args.length != 1 || !List.of("lobotomia", "votacao", "party").contains(args[0].toLowerCase())) {
                    sender.sendMessage(Component.text("Uso: /hcmodo <votacao|lobotomia|party>  (atual: " + getConfig().getString("mode", "votacao") + ")", NamedTextColor.YELLOW));
                    return true;
                }
                getConfig().set("mode", args[0].toLowerCase());
                saveState();
                if (partyMode()) {
                    Bukkit.broadcast(Component.text("⚑ MODO PARTY ativado: mundos viram niveis (World #1, #2...). Morreu alguem da sua party, so ela avanca. /party", NamedTextColor.RED, TextDecoration.BOLD));
                    for (Player p : humans()) syncPlayer(p); // todo mundo comeca/continua no nivel da sua party
                    parties.prepareAhead();
                    return true;
                }
                chooseNext();
                refillPool();
                for (Player p : humans()) syncPlayer(p);
                Bukkit.broadcast(lobotomia()
                        ? Component.text("🧠 MODO LOBOTOMIA ativado: morreu, todo mundo vai direto pra um mundo aleatorio.", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                        : Component.text("Modo votacao ativado: morreu, 15 min (ou todos votam) e /vote escolhe o mundo.", NamedTextColor.GOLD));
            }
            case "party" -> {
                if (!(sender instanceof Player p)) return true;
                if (!partyMode()) { p.sendMessage(Component.text("O modo party nao esta ativo (/hcmodo party).", NamedTextColor.YELLOW)); return true; }
                parties.command(p, args);
                updateTab(p);
            }
            case "ranking" -> {
                if (sender instanceof Player p) openStats(p);
            }
            case "coord" -> {
                if (sender instanceof Player p) coords.command(p, args, run);
            }
            case "migrar" -> {
                if (args.length == 0) { migrate(sender); return true; }
                if (args[0].equalsIgnoreCase("cancelar")) {
                    if (migrateTask != null) { migrateTask.cancel(); migrateTask = null;
                        Bukkit.broadcast(Component.text("✈ Migracao para a VPS cancelada.", NamedTextColor.GREEN)); }
                    return true;
                }
                int min;
                try { min = Math.max(1, Integer.parseInt(args[0])); } catch (NumberFormatException ex) { return false; }
                if (!"pc".equals(host())) { migrate(sender); return true; }
                if (migrateTask != null) migrateTask.cancel();
                final int[] left = {min * 60};
                final String who = sender.getName();
                Bukkit.broadcast(Component.text("✈ O servidor vai migrar para a VPS em " + min + " min (o host vai sair). Mesmo endereco, ~2 min fora.", NamedTextColor.GOLD, TextDecoration.BOLD));
                migrateTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                    left[0]--;
                    int s = left[0];
                    if (s == 600 || s == 300 || s == 120 || s == 60) {
                        Bukkit.broadcast(Component.text("✈ Migracao para a VPS em " + (s / 60) + " min. Fique num lugar seguro!", NamedTextColor.GOLD));
                    } else if (s == 30) {
                        Bukkit.broadcast(Component.text("✈ Migracao em 30s!", NamedTextColor.RED, TextDecoration.BOLD));
                    } else if (s <= 10) {
                        migrateTask.cancel(); migrateTask = null;
                        migrate(Bukkit.getConsoleSender()); // ja avisa e espera mais 10s
                    }
                }, 20L, 20L);
            }
            case "dia" -> {
                World w = currentWorld();
                if (w != null) announceDayTo(sender, w);
            }
            case "bwhitelist" -> {
                List<String> list = new ArrayList<>(getConfig().getStringList("bedrock-whitelist"));
                if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
                    String tag = bedrockKey(args[1]);
                    if (args[0].equalsIgnoreCase("add")) { if (!list.contains(tag)) list.add(tag); }
                    else list.remove(tag);
                    getConfig().set("bedrock-whitelist", list);
                    saveConfig();
                }
                sender.sendMessage(Component.text("Whitelist Bedrock: " + (list.isEmpty() ? "(vazia)" : String.join(", ", list))
                        + "  |  /bwhitelist <add|remove> <gamertag>", NamedTextColor.AQUA));
            }
            case "invasao" -> {
                if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
                    sender.sendMessage(Component.text("Invasao: " + invasion.status() + "  |  /invasao <zumbis|piratas|guilda> [qtd]  |  /invasao parar", NamedTextColor.GOLD));
                } else if (args[0].equalsIgnoreCase("parar")) {
                    invasion.finish(Component.text("A invasao foi encerrada por " + sender.getName() + ".", NamedTextColor.GRAY));
                } else {
                    Invasion.Faction f = Invasion.Faction.of(args[0]);
                    if (f == null) { sender.sendMessage(Component.text("Faccoes: zumbis, piratas, guilda", NamedTextColor.RED)); return true; }
                    Integer n = null;
                    if (args.length > 1) try { n = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) { }
                    Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : null;
                    if (args.length > 2 && target == null) { sender.sendMessage(Component.text("Jogador " + args[2] + " nao esta online.", NamedTextColor.RED)); return true; }
                    boolean builders = args.length > 3 && args[3].equalsIgnoreCase("construtores");
                    invasion.start(f, n, sender, target, builders);
                }
            }
            case "hcreset" -> startVote(Component.text("Reset forcado por " + sender.getName(), NamedTextColor.RED));
            default -> { return false; }
        }
        return true;
    }
}
