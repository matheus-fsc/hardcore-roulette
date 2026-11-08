package dev.math.hcroulette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * Anuncios automaticos no chat: uma lista que roda a cada X minutos (novidades, dicas, IP do Bedrock...).
 * Mensagens aceitam MiniMessage (<gold>, <b>, <click:...>). Config: announcements.interval-minutes / messages.
 * /anuncio add <texto> | lista | remover <n> | agora
 */
final class Announcer {

    private final HardcoreRoulette plugin;
    private int index;

    Announcer(HardcoreRoulette plugin) {
        this.plugin = plugin;
        var c = plugin.getConfig();
        c.addDefault("announcements.enabled", true);
        c.addDefault("announcements.interval-minutes", 10);
        c.addDefault("announcements.messages", List.of(
                "<gold>✦ Novidade:</gold> <yellow>/party abre o menu da party: convidar, info, nivel e alcance do XP compartilhado.</yellow>",
                "<gold>✦ Party:</gold> <yellow>a cada 15 min vivos sem ninguem morrer a party ganha XP. Nivel 7 = XP ilimitado na mesma dimensao, 9 = em todas.</yellow>",
                "<gold>✦ Tab:</gold> <yellow>a tag mostra onde cada um esta: <green>W#</green> mundo, <red>N#</red> nether, <dark_purple>END#</dark_purple> end.</yellow>",
                "<gold>✦ Horda:</gold> <yellow>quanto mais tempo a run fica viva, maior a chance e o tamanho das invasoes a noite.</yellow>",
                "<aqua>✦ Bedrock:</aqua> <white>seu.ip.aqui</white> <gray>porta</gray> <white>19132</white>"));
        long period = Math.max(1, c.getInt("announcements.interval-minutes", 10)) * 1200L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::next, period, period);
    }

    private List<String> messages() { return new ArrayList<>(plugin.getConfig().getStringList("announcements.messages")); }

    void next() {
        if (!plugin.getConfig().getBoolean("announcements.enabled", true) || Bukkit.getOnlinePlayers().isEmpty()) return;
        List<String> m = messages();
        if (m.isEmpty()) return;
        index = index % m.size();
        Bukkit.broadcast(parse(m.get(index++)));
    }

    private static Component parse(String s) {
        try { return MiniMessage.miniMessage().deserialize(s); }
        catch (Exception e) { return Component.text(s, NamedTextColor.YELLOW); }
    }

    void command(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "lista" : a[0].toLowerCase();
        List<String> m = messages();
        switch (sub) {
            case "add" -> {
                if (a.length < 2) { s.sendMessage(Component.text("/anuncio add <texto>", NamedTextColor.RED)); return; }
                m.add(String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
                save(m);
                s.sendMessage(Component.text("Anuncio #" + m.size() + " adicionado.", NamedTextColor.GREEN));
            }
            case "remover", "remove" -> {
                try {
                    int i = Integer.parseInt(a[1]) - 1;
                    m.remove(i);
                    save(m);
                    s.sendMessage(Component.text("Removido.", NamedTextColor.GREEN));
                } catch (Exception e) { s.sendMessage(Component.text("/anuncio remover <numero>", NamedTextColor.RED)); }
            }
            case "agora", "now" -> next();
            default -> {
                s.sendMessage(Component.text("Anuncios (a cada " + plugin.getConfig().getInt("announcements.interval-minutes", 10) + " min):", NamedTextColor.GOLD));
                for (int i = 0; i < m.size(); i++) s.sendMessage(Component.text((i + 1) + ". ", NamedTextColor.GRAY).append(parse(m.get(i))));
            }
        }
    }

    private void save(List<String> m) {
        plugin.getConfig().set("announcements.messages", m);
        plugin.saveConfig();
    }
}
