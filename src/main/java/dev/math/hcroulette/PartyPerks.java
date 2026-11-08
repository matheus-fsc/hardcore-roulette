package dev.math.hcroulette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Party: menu (/party), nivel da party por tempo vivo juntos, bonus por nivel e XP do RPG compartilhado.
 *
 * - A cada 15 min online vivos sem ninguem da party morrer, a party ganha XP (valor por membro online).
 *   A sequencia zera quando a party morre; o NIVEL fica (progresso que sobrevive a morte).
 * - Bonus por nivel = alcance do XP compartilhado: 32, 48, 64, 96, 128, 256 blocos; nivel 7 (max) infinito.
 *   Sem efeitos de pocao (eram fortes demais).
 * - XP de habilidade (AuraSkills) de um membro e repassado em parte para os membros vivos num raio.
 */
final class PartyPerks implements Listener {

    private final HardcoreRoulette plugin;
    private final Parties parties;
    private boolean sharing; // evita repassar o XP que nos mesmos adicionamos

    PartyPerks(HardcoreRoulette plugin, Parties parties) {
        this.plugin = plugin;
        this.parties = parties;
        var c = plugin.getConfig();
        c.addDefault("party.xp-block-minutes", 15);
        c.addDefault("party.xp-per-member", 10);
        c.addDefault("party.xp-share", 0.5);
        c.addDefault("party.share-radius", 32);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::minuteTick, 1200L, 1200L);
        hookAuraSkills();
    }

    // ------------------------------------------------------------------ nivel da party
    /** XP para passar do nivel atual: 100*n ate o 5; os 3 ultimos (alcance ilimitado) sao bem mais caros. */
    static int need(int level) {
        return switch (level) {
            case 6 -> 2500;
            case 7 -> 5000;
            case 8 -> 10000;
            default -> level >= 9 ? Integer.MAX_VALUE : 100 * level;
        };
    }

    private void minuteTick() {
        if (!parties.enabled()) return;
        int block = Math.max(1, plugin.getConfig().getInt("party.xp-block-minutes", 15)) * 60;
        for (String id : parties.partyIds()) {
            List<Player> on = parties.onlineAlive(id);
            if (on.isEmpty()) continue;
            long secs = parties.streak(id) + 60;
            long before = (secs - 60) / block, after = secs / block;
            parties.setStreak(id, secs);
            if (after > before) {
                int gain = (int) ((after - before) * plugin.getConfig().getInt("party.xp-per-member", 10) * on.size());
                addXp(id, gain, on);
            }
        }
    }

    private void addXp(String id, int gain, List<Player> on) {
        int lvl = parties.pLevel(id);
        int xp = parties.pXp(id) + gain;
        boolean up = false;
        while (lvl < MAX_LEVEL && xp >= need(lvl)) { xp -= need(lvl); lvl++; up = true; }
        if (lvl >= MAX_LEVEL) xp = 0; // maximo: nao acumula
        parties.setPLevel(id, lvl, xp);
        for (Player p : on) {
            p.sendMessage(Component.text("✦ Party +" + gain + " XP por ficarem vivos juntos" + (lvl >= MAX_LEVEL ? " (nivel maximo)" : " (" + xp + "/" + need(lvl) + ")"), NamedTextColor.AQUA));
            if (up) {
                p.showTitle(net.kyori.adventure.title.Title.title(Component.text("Party nivel " + lvl, NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("XP compartilhado: " + rangeText(lvl), NamedTextColor.YELLOW)));
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            }
        }
    }

    // ------------------------------------------------------------------ bonus: alcance do XP compartilhado
    static final int MAX_LEVEL = 9;
    private static final int[] RANGE = {0, 32, 48, 64, 96, 128, 256};

    /** Alcance em blocos ate o nivel 6; do 7 em diante e ilimitado (-1), com regra de dimensao em inRange. */
    static int range(int lvl) { return lvl >= 7 ? -1 : RANGE[Math.max(1, Math.min(6, lvl))]; }

    static String rangeText(int lvl) {
        if (lvl >= 9) return "ilimitado em todas as dimensoes";
        if (lvl == 8) return "ilimitado no overworld + nether";
        if (lvl == 7) return "ilimitado na mesma dimensao";
        return range(lvl) + " blocos";
    }

    /** Quem recebe o XP: 1-6 mesma dimensao e no raio; 7 mesma dimensao; 8 overworld e nether juntos; 9 todas. */
    static boolean inRange(Player from, Player to, int lvl) {
        if (lvl >= 9) return true;
        boolean same = from.getWorld() == to.getWorld();
        if (lvl == 8) {
            if (same) return true;
            var a = from.getWorld().getEnvironment(); var b = to.getWorld().getEnvironment();
            return a != org.bukkit.World.Environment.THE_END && b != org.bukkit.World.Environment.THE_END;
        }
        if (lvl == 7) return same;
        int r = range(lvl);
        return same && from.getLocation().distanceSquared(to.getLocation()) <= (double) r * r;
    }

    // ------------------------------------------------------------------ XP compartilhado (AuraSkills, sem dependencia)
    @SuppressWarnings("unchecked")
    private void hookAuraSkills() {
        try {
            Class<? extends Event> cls = (Class<? extends Event>) Class.forName("dev.aurelium.auraskills.api.event.skill.XpGainEvent");
            Bukkit.getPluginManager().registerEvent(cls, this, EventPriority.MONITOR, (l, e) -> onXp(e), plugin, true);
            plugin.getLogger().info("Party: XP do AuraSkills compartilhado entre membros");
        } catch (Throwable t) {
            plugin.getLogger().warning("Party: AuraSkills nao encontrado, XP compartilhado desligado (" + t.getMessage() + ")");
        }
    }

    private void onXp(Event e) {
        if (sharing || !parties.enabled()) return;
        try {
            Player p = (Player) e.getClass().getMethod("getPlayer").invoke(e);
            Object skill = e.getClass().getMethod("getSkill").invoke(e);
            double amount = ((Number) e.getClass().getMethod("getAmount").invoke(e)).doubleValue();
            if (p == null || amount <= 0) return;
            String skillName;
            try { skillName = String.valueOf(skill.getClass().getMethod("name").invoke(skill)).toLowerCase(); }
            catch (NoSuchMethodException nm) {
                Object nid = skill.getClass().getMethod("getId").invoke(skill);
                skillName = String.valueOf(nid.getClass().getMethod("getKey").invoke(nid)).toLowerCase();
            }
            String id = parties.partyOf(p.getUniqueId());
            int plvl = parties.pLevel(id);
            double share = plugin.getConfig().getDouble("party.xp-share", 0.5);
            double give = Math.round(amount * share * 100) / 100.0;
            if (give < 0.01) return;
            sharing = true;
            try {
                for (Player o : parties.onlineAlive(id)) {
                    if (o == p || !inRange(p, o, plvl)) continue;
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "skills xp add " + o.getName() + " " + skillName + " " + give + " silent");
                }
            } finally { sharing = false; }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ menu
    private static final class Menu implements InventoryHolder {
        final String kind; Inventory inv;
        Menu(String kind) { this.kind = kind; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static ItemStack item(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        var meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (Component c : lore) l.add(c.decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    void open(Player p) {
        String id = parties.partyOf(p.getUniqueId());
        int lvl = parties.pLevel(id), xp = parties.pXp(id);
        Menu h = new Menu("main");
        Inventory inv = Bukkit.createInventory(h, 27, Component.text(parties.labelOf(id), NamedTextColor.DARK_PURPLE));
        h.inv = inv;
        int block = Math.max(1, plugin.getConfig().getInt("party.xp-block-minutes", 15)) * 60;
        long streak = parties.streak(id);
        List<Component> info = new ArrayList<>();
        info.add(Component.text("World #" + parties.level(id), NamedTextColor.RED));
        info.add(Component.text("Nivel da party: " + lvl + (lvl >= MAX_LEVEL ? " (maximo)" : "  (" + xp + "/" + need(lvl) + " XP)"), NamedTextColor.GOLD));
        info.add(Component.text(bar(xp, need(lvl)), NamedTextColor.GREEN));
        info.add(Component.text("Vivos juntos ha " + HardcoreRoulette.fmtAlive(streak) + " | proximo XP em "
                + HardcoreRoulette.fmtAlive(block - streak % block), NamedTextColor.AQUA));
        info.add(Component.empty());
        for (UUID u : parties.memberList(id)) {
            Player m = Bukkit.getPlayer(u);
            String name = m != null ? m.getName() : Bukkit.getOfflinePlayer(u).getName();
            info.add(Component.text((m != null ? "● " : "○ ") + name, m != null ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        }
        inv.setItem(11, item(Material.BOOK, Component.text("Informacoes", NamedTextColor.YELLOW, TextDecoration.BOLD), info));
        inv.setItem(13, item(Material.PLAYER_HEAD, Component.text("Convidar jogador", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(Component.text("Clique para escolher quem convidar", NamedTextColor.GRAY))));
        List<Component> bl = new ArrayList<>();
        bl.add(Component.text("XP do RPG dividido (50%) com quem esta no alcance:", NamedTextColor.GRAY));
        for (int i = 1; i <= MAX_LEVEL; i++)
            bl.add(Component.text((lvl >= i ? (lvl == i || (i == MAX_LEVEL && lvl > i) ? "➤ " : "✔ ") : "✖ ") + "Nivel " + i + (i == MAX_LEVEL ? " (max)" : "") + ": " + rangeText(i),
                    lvl >= i ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        bl.add(Component.empty());
        bl.add(Component.text("Party ganha XP a cada " + block / 60 + " min vivos sem ninguem morrer", NamedTextColor.GRAY));
        inv.setItem(15, item(Material.NETHER_STAR, Component.text("Alcance do XP compartilhado", NamedTextColor.AQUA, TextDecoration.BOLD), bl));
        if (parties.hasInvite(p.getUniqueId()))
            inv.setItem(21, item(Material.WRITABLE_BOOK, Component.text("Aceitar convite", NamedTextColor.GREEN, TextDecoration.BOLD),
                    List.of(Component.text(parties.inviteLabel(p.getUniqueId()), NamedTextColor.GRAY))));
        inv.setItem(23, item(Material.BARRIER, Component.text("Sair da party", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(Component.text("Voce segue sozinho no mesmo World", NamedTextColor.GRAY))));
        p.openInventory(inv);
    }

    private void openInvite(Player p) {
        String id = parties.partyOf(p.getUniqueId());
        Menu h = new Menu("invite");
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Convidar para a party", NamedTextColor.DARK_GREEN));
        h.inv = inv;
        int slot = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p || id.equals(parties.partyOf(o.getUniqueId())) || slot >= 26) continue;
            ItemStack head = item(Material.PLAYER_HEAD, Component.text(o.getName(), NamedTextColor.WHITE),
                    List.of(Component.text("Clique para convidar", NamedTextColor.GREEN)));
            if (head.getItemMeta() instanceof SkullMeta sm) { sm.setOwningPlayer(o); head.setItemMeta(sm); }
            inv.setItem(slot++, head);
        }
        if (slot == 0) inv.setItem(13, item(Material.GRAY_DYE, Component.text("Ninguem online para convidar", NamedTextColor.GRAY), List.of()));
        inv.setItem(26, item(Material.ARROW, Component.text("Voltar", NamedTextColor.YELLOW), List.of()));
        p.openInventory(inv);
    }

    private static String bar(int v, int max) {
        int n = (int) Math.round(20.0 * v / Math.max(1, max));
        return "▌".repeat(Math.max(0, n)) + "·".repeat(Math.max(0, 20 - n));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        int s = e.getRawSlot();
        if (m.kind.equals("main")) {
            switch (s) {
                case 13 -> openInvite(p);
                case 21 -> { p.closeInventory(); parties.command(p, new String[]{"aceitar"}); }
                case 23 -> { p.closeInventory(); parties.command(p, new String[]{"sair"}); }
                default -> { }
            }
        } else if (m.kind.equals("invite")) {
            if (s == 26) { open(p); return; }
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType() != Material.PLAYER_HEAD || !(it.getItemMeta() instanceof SkullMeta sm) || sm.getOwningPlayer() == null) return;
            String name = sm.getOwningPlayer().getName();
            p.closeInventory();
            if (name != null) parties.command(p, new String[]{"convidar", name});
        }
    }

    /** Morte: a party cai junto (o Parties.onDeath ja reseta todos); aqui so a sequencia de XP zera. */
    void onPartyDeath(String id) {
        parties.setStreak(id, 0);
    }

    static boolean aliveSurvival(Player p) {
        return !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }
}
