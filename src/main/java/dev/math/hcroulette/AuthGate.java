package dev.math.hcroulette;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Login proprio (servidor offline-mode sem whitelist): /register na primeira vez, /login depois.
 * Ate logar: parado, sem chat/comandos/interacao/dano. Mesmo IP em ate 12 h entra direto.
 * Bedrock (Floodgate, nick com ".") pula: ja vem autenticado pela conta Xbox.
 * Senhas: PBKDF2-SHA256 com sal aleatorio em auth.yml (nunca em texto).
 */
final class AuthGate implements Listener {

    private static final long SESSION_MS = 12 * 3600_000L;
    private static final int KICK_SECONDS = 90;

    private final HardcoreRoulette plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, Long> pending = new HashMap<>();

    AuthGate(HardcoreRoulette plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auth.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        plugin.getConfig().addDefault("auth.enabled", true);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::remind, 40L, 100L);
    }

    /** Desliga sozinho quando o AuthMe esta instalado (ele assume o login). */
    private boolean enabled() {
        return plugin.getConfig().getBoolean("auth.enabled", true) && !Bukkit.getPluginManager().isPluginEnabled("AuthMe");
    }

    static boolean bedrock(Player p) { return p.getName().startsWith("."); }

    boolean locked(Player p) { return pending.containsKey(p.getUniqueId()); }

    private boolean registered(UUID u) { return data.contains(u + ".hash"); }

    private static String ip(Player p) { return p.getAddress() == null ? "?" : p.getAddress().getAddress().getHostAddress(); }

    // ------------------------------------------------------------------ hash
    private static String hash(String pw, byte[] salt) throws Exception {
        var f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return Base64.getEncoder().encodeToString(f.generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 120_000, 256)).getEncoded());
    }

    private boolean check(UUID u, String pw) {
        try {
            byte[] salt = Base64.getDecoder().decode(data.getString(u + ".salt", ""));
            return MessageDigest.isEqual(hash(pw, salt).getBytes(), data.getString(u + ".hash", "").getBytes());
        } catch (Exception e) { return false; }
    }

    private void store(Player p, String pw) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String u = p.getUniqueId().toString();
        data.set(u + ".name", p.getName());
        data.set(u + ".salt", Base64.getEncoder().encodeToString(salt));
        data.set(u + ".hash", hash(pw, salt));
        session(p);
    }

    private void session(Player p) {
        String u = p.getUniqueId().toString();
        data.set(u + ".ip", ip(p));
        data.set(u + ".last", System.currentTimeMillis());
        try { data.save(file); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ entrar / sair
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!enabled() || bedrock(p)) return;
        UUID u = p.getUniqueId();
        boolean sameIp = ip(p).equals(data.getString(u + ".ip")) && System.currentTimeMillis() - data.getLong(u + ".last", 0) < SESSION_MS;
        if (registered(u) && sameIp) {
            session(p);
            Bukkit.getScheduler().runTaskLater(plugin, () -> p.sendMessage(Component.text("✔ Sessao lembrada: login automatico.", NamedTextColor.GREEN)), 20L);
            return;
        }
        pending.put(u, System.currentTimeMillis());
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * KICK_SECONDS, 0, false, false, false));
        Bukkit.getScheduler().runTaskLater(plugin, () -> prompt(p), 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { pending.remove(e.getPlayer().getUniqueId()); }

    private void prompt(Player p) {
        if (!locked(p)) return;
        boolean reg = registered(p.getUniqueId());
        p.showTitle(Title.title(Component.text(reg ? "/login <senha>" : "/register <senha> <senha>", NamedTextColor.GOLD),
                Component.text(reg ? "digite sua senha para entrar" : "crie uma senha para proteger seu nick", NamedTextColor.GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(6), Duration.ofMillis(500))));
        p.sendMessage(Component.text(reg ? "🔒 Digite /login <senha>" : "🔒 Primeira vez: /register <senha> <senha> (guarde a senha!)", NamedTextColor.GOLD));
    }

    private void remind() {
        long now = System.currentTimeMillis();
        for (var en : new HashMap<>(pending).entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) { pending.remove(en.getKey()); continue; }
            if (now - en.getValue() > KICK_SECONDS * 1000L) { p.kick(Component.text("Tempo de login esgotado.")); continue; }
            prompt(p);
        }
    }

    private void unlock(Player p, String msg) {
        pending.remove(p.getUniqueId());
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.resetTitle();
        p.sendMessage(Component.text(msg, NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ comandos
    boolean command(Player p, String cmd, String[] a) {
        UUID u = p.getUniqueId();
        try {
            switch (cmd) {
                case "register" -> {
                    if (registered(u)) { p.sendMessage(Component.text("Ja registrado: use /login <senha>.", NamedTextColor.RED)); return true; }
                    if (a.length < 2 || !a[0].equals(a[1])) { p.sendMessage(Component.text("Use /register <senha> <senha> (iguais).", NamedTextColor.RED)); return true; }
                    if (a[0].length() < 4) { p.sendMessage(Component.text("Senha com pelo menos 4 caracteres.", NamedTextColor.RED)); return true; }
                    store(p, a[0]);
                    unlock(p, "✔ Registrado! Da proxima vez use /login <senha> (mesmo IP em 12 h entra direto).");
                }
                case "login" -> {
                    if (!locked(p)) { p.sendMessage(Component.text("Voce ja esta logado.", NamedTextColor.GRAY)); return true; }
                    if (!registered(u)) { p.sendMessage(Component.text("Primeira vez: /register <senha> <senha>.", NamedTextColor.RED)); return true; }
                    if (a.length < 1 || !check(u, a[0])) {
                        plugin.getLogger().warning("Login errado para " + p.getName() + " de " + ip(p));
                        p.kick(Component.text("Senha errada."));
                        return true;
                    }
                    session(p);
                    unlock(p, "✔ Logado. Bom jogo!");
                }
                case "senha" -> { // trocar: /senha <atual> <nova>
                    if (locked(p) || a.length < 2 || !check(u, a[0])) { p.sendMessage(Component.text("Use /senha <atual> <nova>.", NamedTextColor.RED)); return true; }
                    store(p, a[1]);
                    p.sendMessage(Component.text("✔ Senha trocada.", NamedTextColor.GREEN));
                }
                default -> { return false; }
            }
        } catch (Exception ex) {
            p.sendMessage(Component.text("Erro no login: " + ex.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    /** Admin: /authreset <nick> apaga a senha (o jogador registra de novo). */
    void reset(String name) {
        for (String k : data.getKeys(false)) if (name.equalsIgnoreCase(data.getString(k + ".name"))) data.set(k, null);
        try { data.save(file); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ travas ate logar
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!locked(e.getPlayer())) return;
        var f = e.getFrom(); var t = e.getTo();
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) e.setTo(f.clone().setDirection(t.getDirection()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        if (!locked(e.getPlayer())) return;
        String c = e.getMessage().toLowerCase().split(" ")[0];
        if (!c.equals("/login") && !c.equals("/l") && !c.equals("/register") && !c.equals("/reg")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST) public void onBreak(BlockBreakEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onPlace(BlockPlaceEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInteract(PlayerInteractEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onDrop(PlayerDropItemEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInv(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p && locked(p)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent d && d.getDamager() instanceof Player p && locked(p)) e.setCancelled(true);
    }
}
