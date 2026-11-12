package dev.math.hcroulette;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Ponte com o AuthMe: jogadores Bedrock (Floodgate, nick com ".") ja vem autenticados pela conta
 * Xbox, entao entram logados no AuthMe (cadastro automatico com senha aleatoria na primeira vez).
 * Usa a API v3 do AuthMe por reflexao: sem o AuthMe instalado, nada acontece.
 */
final class AuthMeBridge implements Listener {

    private final HardcoreRoulette plugin;

    AuthMeBridge(HardcoreRoulette plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!AuthGate.bedrock(p) || !plugin.getConfig().getBoolean("auth.bedrock-auto-login", true)) return;
        Plugin authme = Bukkit.getPluginManager().getPlugin("AuthMe");
        if (authme == null || !authme.isEnabled()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            try {
                Class<?> c = authme.getClass().getClassLoader().loadClass("fr.xephi.authme.api.v3.AuthMeApi");
                Object api = c.getMethod("getInstance").invoke(null);
                if ((Boolean) c.getMethod("isAuthenticated", Player.class).invoke(api, p)) return;
                if ((Boolean) c.getMethod("isRegistered", String.class).invoke(api, p.getName())) {
                    c.getMethod("forceLogin", Player.class).invoke(api, p);
                } else {
                    byte[] pw = new byte[18];
                    new SecureRandom().nextBytes(pw);
                    c.getMethod("forceRegister", Player.class, String.class, boolean.class)
                            .invoke(api, p, Base64.getUrlEncoder().encodeToString(pw), true);
                }
                plugin.getLogger().info("Bedrock " + p.getName() + " logado no AuthMe automaticamente");
            } catch (Throwable t) {
                plugin.getLogger().warning("AuthMe: falha no login automatico do Bedrock " + p.getName() + ": " + t);
            }
        }, 20L);
    }
}
