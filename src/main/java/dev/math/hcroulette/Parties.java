package dev.math.hcroulette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Modo party: os mundos viram niveis numerados (World #1, #2...) com seeds fixas, iguais para todos.
 * Quem entra comeca no #1. Morreu alguem da party -> so aquela party avanca junta para o proximo nivel.
 * Varias parties podem dividir o mesmo nivel (mesmo mundo). Otimizacoes:
 *  - so o nivel "mais avancado + 1" e criado/pre-renderizado de antemao (fora do momento da morte);
 *  - mundo sem ninguem e descarregado da memoria (fica no disco, carrega sob demanda);
 *  - nivel que todas as parties ja passaram e apagado.
 */
final class Parties {

    private final HardcoreRoulette plugin;
    private final File file;
    private final YamlConfiguration data;
    private final NamespacedKey lvlKey;
    private final Map<UUID, String> invites = new HashMap<>();
    private final Map<UUID, Long> inviteAt = new HashMap<>();

    Parties(HardcoreRoulette plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "parties.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        this.lvlKey = new NamespacedKey(plugin, "party-level");
        Bukkit.getScheduler().runTaskTimer(plugin, this::housekeeping, 1200L, 1200L);
    }

    boolean enabled() { return "party".equalsIgnoreCase(plugin.getConfig().getString("mode", "votacao")); }

    PartyPerks perks; // menu, nivel e bonus (setado pelo plugin)

    // ---------------------------------------------------------------- acesso para o PartyPerks
    List<String> partyIds() {
        var sec = data.getConfigurationSection("party");
        return sec == null ? List.of() : new ArrayList<>(sec.getKeys(false));
    }
    String partyOf(UUID u) { return partyId(u); }
    String labelOf(String id) { return label(id); }
    List<UUID> memberList(String id) { return members(id); }
    List<Player> onlineAlive(String id) {
        List<Player> out = new ArrayList<>();
        for (UUID u : members(id)) { Player m = Bukkit.getPlayer(u); if (m != null && PartyPerks.aliveSurvival(m)) out.add(m); }
        return out;
    }
    long streak(String id) { return data.getLong("party." + id + ".streak", 0); }
    void setStreak(String id, long secs) { data.set("party." + id + ".streak", secs); save(); }
    int pLevel(String id) { return Math.max(1, data.getInt("party." + id + ".plevel", 1)); }
    int pXp(String id) { return data.getInt("party." + id + ".pxp", 0); }
    void setPLevel(String id, int lvl, int xp) { data.set("party." + id + ".plevel", lvl); data.set("party." + id + ".pxp", xp); save(); }
    boolean hasInvite(UUID u) {
        Long at = inviteAt.get(u);
        return invites.containsKey(u) && at != null && System.currentTimeMillis() - at <= 120_000;
    }
    String inviteLabel(UUID u) { String t = invites.get(u); return t == null ? "" : label(t) + " (World #" + level(t) + ")"; }

    void save() {
        try { data.save(file); } catch (IOException e) { plugin.getLogger().warning("parties: " + e.getMessage()); }
    }

    // ---------------------------------------------------------------- niveis

    static String worldName(int lvl) { return "hc_L" + lvl; }

    private long seedFor(int lvl) {
        String k = "levels." + lvl + ".seed";
        if (!data.contains(k)) { data.set(k, ThreadLocalRandom.current().nextLong()); save(); }
        return data.getLong(k);
    }

    /** Alias: party.world-alias.<n> aponta o World #n para um mundo existente (ex.: recuperado do lobotomia). */
    private String alias(int lvl) { return plugin.getConfig().getString("party.world-alias." + lvl); }

    private boolean isPartyWorld(String base) {
        if (base.startsWith("hc_L") || base.startsWith("hc_l")) return true;
        var sec = plugin.getConfig().getConfigurationSection("party.world-alias");
        if (sec != null) for (String k : sec.getKeys(false)) if (base.equalsIgnoreCase(sec.getString(k))) return true;
        return false;
    }

    World ensureLevel(int lvl) {
        String al = alias(lvl);
        if (al != null) { // mundo existente: carrega como esta (com a seed dele)
            World w = Bukkit.getWorld(al);
            return w != null ? w : plugin.createWorld(al, null);
        }
        World w = Bukkit.getWorld(worldName(lvl));
        if (w != null) return w;
        w = plugin.createWorld(worldName(lvl), seedFor(lvl)); // carrega do disco se ja existir
        if (w != null) plugin.chunkyStart(w);
        return w;
    }

    Location spawnOf(World w) { return w.getSpawnLocation().toBlockLocation().add(0.5, 0, 0.5); }

    // ---------------------------------------------------------------- parties

    private String partyId(UUID player) {
        String id = data.getString("player." + player);
        if (id == null || !data.contains("party." + id)) {
            id = player.toString(); // party solo
            if (data.contains("party." + id)) id = player + "-" + Long.toString(System.currentTimeMillis(), 36); // id antigo ainda em uso
            data.set("player." + player, id);
            data.set("party." + id + ".members", List.of(player.toString()));
            data.set("party." + id + ".leader", player.toString());
            data.set("party." + id + ".level", 1);
            save();
        }
        return id;
    }

    int level(String id) { return Math.max(1, data.getInt("party." + id + ".level", 1)); }

    private List<UUID> members(String id) {
        List<UUID> out = new ArrayList<>();
        for (String s : data.getStringList("party." + id + ".members")) {
            try { out.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    private String label(String id) {
        String custom = data.getString("party." + id + ".name");
        if (custom != null && !custom.isBlank()) return "Party " + custom;
        String leader = data.getString("party." + id + ".leader");
        String name = leader == null ? "?" : Bukkit.getOfflinePlayer(UUID.fromString(leader)).getName();
        return members(id).size() > 1 ? "Party de " + name : String.valueOf(name);
    }

    World worldFor(Player p) { return ensureLevel(level(partyId(p.getUniqueId()))); }

    /** Mundo com mais jogadores (usado por invasoes/contador de dias). */
    World busiestWorld() {
        World best = null; int n = -1;
        for (World w : Bukkit.getWorlds()) {
            if (!isPartyWorld(w.getName()) || w.getName().contains("_nether") || w.getName().contains("_the_end")) continue;
            int c = w.getPlayers().size();
            if (c > n) { n = c; best = w; }
        }
        return best;
    }

    // ---------------------------------------------------------------- entrar / morrer

    /** Entrou: vai para o nivel da sua party (reseta se a party avancou enquanto estava fora). */
    void sync(Player p) {
        String id = partyId(p.getUniqueId());
        int lvl = level(id);
        World w = ensureLevel(lvl);
        Integer mine = p.getPersistentDataContainer().get(lvlKey, PersistentDataType.INTEGER);
        if (mine == null || mine != lvl) {
            plugin.resetPlayer(p, spawnOf(w));
            p.getPersistentDataContainer().set(lvlKey, PersistentDataType.INTEGER, lvl);
            p.sendMessage(Component.text("☠ Modo party: voce esta no World #" + lvl + ". Morreu alguem da sua party, voces vao juntos para o proximo.", NamedTextColor.GOLD));
        } else if (!HardcoreRoulette.baseOf(p.getWorld().getName()).equals(w.getName())) {
            Location back = lastPos(p.getUniqueId());
            boolean ok = back != null && HardcoreRoulette.baseOf(back.getWorld().getName()).equals(w.getName());
            p.teleportAsync(ok ? back : spawnOf(w)); // volta para onde saiu (so cai no spawn sem posicao salva)
        }
        bestLevel(p.getUniqueId(), lvl);
        Bukkit.getScheduler().runTaskLater(plugin, this::prepareAhead, 200L);
    }

    void onDeath(Player dead, Component reason) {
        String id = partyId(dead.getUniqueId());
        int next = level(id) + 1;
        data.set("party." + id + ".level", next);
        save();
        World w = ensureLevel(next);
        Location spawn = spawnOf(w);
        Bukkit.broadcast(reason);
        if (members(id).size() > 1)
            Bukkit.broadcast(Component.text("☠ " + label(id) + " caiu junta: um morreu, todos morreram.", NamedTextColor.DARK_RED));
        if (perks != null) perks.onPartyDeath(id);
        Bukkit.broadcast(Component.text("➜ " + label(id) + " avanca para o ", NamedTextColor.GOLD)
                .append(Component.text("World #" + next, NamedTextColor.RED, TextDecoration.BOLD)));
        for (UUID u : members(id)) {
            Player m = Bukkit.getPlayer(u);
            bestLevel(u, next);
            if (m == null) continue; // offline: reseta quando entrar
            plugin.resetPlayer(m, spawn);
            m.getPersistentDataContainer().set(lvlKey, PersistentDataType.INTEGER, next);
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::prepareAhead, 400L); // longe do momento da morte
        Bukkit.getScheduler().runTaskLater(plugin, this::housekeeping, 600L);
    }

    private void bestLevel(UUID u, int lvl) {
        if (data.getInt("best." + u, 0) < lvl) { data.set("best." + u, lvl); save(); }
    }

    int bestLevel(UUID u) { return data.getInt("best." + u, 0); }

    // ---------------------------------------------------------------- otimizacao de mundos

    private int maxLevel() {
        int max = 1;
        var sec = data.getConfigurationSection("party");
        if (sec != null) for (String id : sec.getKeys(false)) max = Math.max(max, level(id));
        return max;
    }

    private int minLevel() {
        int min = Integer.MAX_VALUE;
        var sec = data.getConfigurationSection("party");
        if (sec != null) for (String id : sec.getKeys(false)) min = Math.min(min, level(id));
        return min == Integer.MAX_VALUE ? 1 : min;
    }

    /** Deixa pronto (criado + Chunky) so o nivel seguinte ao mais avancado. */
    void prepareAhead() {
        if (!enabled()) return;
        ensureLevel(maxLevel() + 1);
    }

    java.util.Set<Integer> levelsInUse() {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (String id : partyIds()) out.add(level(id));
        return out;
    }

    /** Carrega no boot os mundos de todas as parties (antes de alguem entrar). */
    void preloadAll() {
        if (!enabled()) return;
        for (int lvl : levelsInUse()) ensureLevel(lvl);
    }

    /** Ao sair: guarda a posicao (se estava no mundo da party ou no nether/end dele). */
    void rememberQuit(Player p) {
        if (!enabled()) return;
        var l = p.getLocation();
        data.set("lastpos." + p.getUniqueId(), l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch());
        save();
    }

    private Location lastPos(UUID u) {
        String s = data.getString("lastpos." + u);
        if (s == null) return null;
        String[] a = s.split(";");
        World w = Bukkit.getWorld(a[0]);
        if (w == null) return null;
        return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]), Float.parseFloat(a[4]), Float.parseFloat(a[5]));
    }

    /** Descarrega niveis vazios e apaga os que todas as parties ja passaram. */
    void housekeeping() {
        if (!enabled()) return;
        int ahead = maxLevel() + 1, min = minLevel();
        for (World w : new ArrayList<>(Bukkit.getWorlds())) {
            String base = HardcoreRoulette.baseOf(w.getName());
            if (!base.startsWith("hc_L")) continue;
            int lvl;
            try { lvl = Integer.parseInt(base.substring(4)); } catch (NumberFormatException e) { continue; }
            if (lvl < min) { plugin.trashWorld(base); continue; }
            // nao descarrega mundo onde alguma party esta: ao entrar, o mundo ja precisa estar carregado
            // (senao o servidor joga o jogador no mundo padrao e a posicao se perde)
            if (w.getPlayers().isEmpty() && lvl != ahead && !levelsInUse().contains(lvl)) Bukkit.unloadWorld(w, true);
        }
        plugin.cleanupTrashLater();
    }

    // ---------------------------------------------------------------- comandos

    boolean command(Player p, String[] args) {
        if ((args.length == 0 || args[0].equalsIgnoreCase("menu")) && perks != null) { perks.open(p); return true; }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();
        String my = partyId(p.getUniqueId());
        switch (sub) {
            case "convidar", "invite" -> {
                if (args.length < 2) { p.sendMessage(Component.text("Uso: /party convidar <nick>", NamedTextColor.RED)); return true; }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null || t == p) { p.sendMessage(Component.text("Jogador nao encontrado.", NamedTextColor.RED)); return true; }
                invites.put(t.getUniqueId(), my);
                inviteAt.put(t.getUniqueId(), System.currentTimeMillis());
                t.sendMessage(Component.text(p.getName() + " te convidou para a party (World #" + level(my) + "). ", NamedTextColor.AQUA)
                        .append(Component.text("[ACEITAR]", NamedTextColor.GREEN, TextDecoration.BOLD)
                                .clickEvent(ClickEvent.runCommand("/party aceitar"))
                                .hoverEvent(HoverEvent.showText(Component.text("Voce vai para o World #" + level(my) + " junto (comeca do zero)")))));
                p.sendMessage(Component.text("Convite enviado para " + t.getName() + ".", NamedTextColor.GREEN));
            }
            case "aceitar", "accept" -> {
                String target = invites.remove(p.getUniqueId());
                Long at = inviteAt.remove(p.getUniqueId());
                if (target == null || at == null || System.currentTimeMillis() - at > 120_000 || !data.contains("party." + target)) {
                    p.sendMessage(Component.text("Nenhum convite valido (expira em 2 min).", NamedTextColor.RED)); return true;
                }
                leave(p.getUniqueId(), my);
                List<String> ms = new ArrayList<>(data.getStringList("party." + target + ".members"));
                ms.add(p.getUniqueId().toString());
                data.set("party." + target + ".members", ms);
                data.set("player." + p.getUniqueId(), target);
                save();
                int lvl = level(target);
                for (UUID u : members(target)) {
                    Player m = Bukkit.getPlayer(u);
                    if (m != null) m.sendMessage(Component.text("✚ " + p.getName() + " entrou na " + label(target) + "!", NamedTextColor.GREEN));
                }
                Integer mine = p.getPersistentDataContainer().get(lvlKey, PersistentDataType.INTEGER);
                if (mine == null || mine != lvl) sync(p); // muda de nivel: comeca do zero no nivel da party
            }
            case "sair", "leave" -> {
                if (members(my).size() <= 1) { p.sendMessage(Component.text("Voce ja esta sozinho.", NamedTextColor.YELLOW)); return true; }
                int lvl = level(my);
                leave(p.getUniqueId(), my);
                String solo = partyId(p.getUniqueId());
                data.set("party." + solo + ".level", lvl); // continua no mesmo nivel, sem reset
                save();
                p.sendMessage(Component.text("Voce saiu da party e segue sozinho no World #" + lvl + ".", NamedTextColor.YELLOW));
            }
            case "lista", "list" -> {
                p.sendMessage(Component.text("── Parties ──", NamedTextColor.GOLD));
                var sec = data.getConfigurationSection("party");
                if (sec != null) for (String id : sec.getKeys(false)) {
                    StringBuilder names = new StringBuilder();
                    for (UUID u : members(id)) {
                        Player m = Bukkit.getPlayer(u);
                        if (names.length() > 0) names.append(", ");
                        names.append(m != null ? m.getName() : Bukkit.getOfflinePlayer(u).getName() + " (off)");
                    }
                    p.sendMessage(Component.text(" World #" + level(id) + "  ", NamedTextColor.RED).append(Component.text(names.toString(), NamedTextColor.WHITE)));
                }
            }
            default -> {
                p.sendMessage(Component.text(label(my) + " | World #" + level(my) + " | seu recorde: #" + bestLevel(p.getUniqueId()), NamedTextColor.GOLD));
                p.sendMessage(Component.text("/party convidar <nick> | aceitar | sair | lista", NamedTextColor.GRAY));
            }
        }
        return true;
    }

    private void leave(UUID u, String id) {
        List<String> ms = new ArrayList<>(data.getStringList("party." + id + ".members"));
        ms.remove(u.toString());
        if (ms.isEmpty()) data.set("party." + id, null);
        else {
            data.set("party." + id + ".members", ms);
            if (u.toString().equals(data.getString("party." + id + ".leader"))) data.set("party." + id + ".leader", ms.get(0));
        }
        data.set("player." + u, null);
        save();
    }
}
