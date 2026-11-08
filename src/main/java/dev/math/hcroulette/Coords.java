package dev.math.hcroulette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Coordenadas salvas no servidor, por run (mundo novo = lista nova).
 * /coord                       mostra onde voce esta (clique para compartilhar)
 * /coord salvar <nome>         salva a posicao atual (so voce ve)
 * /coord publico <nome>        salva e deixa visivel para todos
 * /coord lista                 suas coordenadas + as publicas da run
 * /coord mostrar <nome>        manda no chat para todo mundo
 * /coord apagar <nome>
 */
final class Coords {

    private final HardcoreRoulette plugin;
    private final File file;
    private YamlConfiguration data;

    Coords(HardcoreRoulette plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "coords.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private void save() {
        try { data.save(file); } catch (IOException e) { plugin.getLogger().warning("coords: " + e.getMessage()); }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }

    private String base(int run) { return "run-" + run; }

    private static String dim(String world) {
        return world.endsWith("_nether") ? "Nether" : world.endsWith("_the_end") ? "End" : "Overworld";
    }

    private Component line(Player viewer, String name, String owner, String world, int x, int y, int z, boolean pub) {
        String coords = x + " " + y + " " + z;
        Component c = Component.text(" ✦ ", pub ? NamedTextColor.AQUA : NamedTextColor.GOLD)
                .append(Component.text(name, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text("  " + coords, NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false))
                .append(Component.text("  " + dim(world), NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false));
        if (viewer.getWorld().getName().equals(world)) {
            Location here = viewer.getLocation();
            double dx = x + 0.5 - here.getX(), dz = z + 0.5 - here.getZ();
            int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            c = c.append(Component.text("  " + dist + "m " + arrow(here.getYaw(), dx, dz), NamedTextColor.GREEN).decoration(TextDecoration.BOLD, false));
        }
        if (pub) c = c.append(Component.text("  (" + owner + ")", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false));
        return c.hoverEvent(HoverEvent.showText(Component.text("Clique para copiar as coordenadas")))
                .clickEvent(ClickEvent.copyToClipboard(coords));
    }

    /** Seta relativa a para onde o jogador esta olhando. */
    private static String arrow(float yaw, double dx, double dz) {
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((target - yaw) % 360 + 540) % 360 - 180;
        String[] a = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        return a[(int) Math.round(((rel + 360) % 360) / 45.0) % 8];
    }

    boolean command(Player p, String[] args, int run) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        // aceita tambem em ingles: /coord save|public|list|show|delete
        sub = switch (sub) { case "save", "set", "add" -> "salvar"; case "public", "share" -> "publico";
            case "list" -> "lista"; case "show" -> "mostrar"; case "delete", "del", "remove" -> "apagar"; default -> sub; };
        // nome entre aspas ("base principal"): tira as aspas
        for (int i = 1; i < args.length; i++) args[i] = args[i].replace("\"", "");
        Location l = p.getLocation();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        String world = l.getWorld().getName();
        String mine = base(run) + ".players." + p.getUniqueId();
        String pubs = base(run) + ".public";

        switch (sub) {
            case "" -> {
                p.sendMessage(Component.text("Voce esta em ", NamedTextColor.GRAY)
                        .append(Component.text(x + " " + y + " " + z, NamedTextColor.YELLOW, TextDecoration.BOLD))
                        .append(Component.text(" (" + dim(world) + ")  ", NamedTextColor.GRAY))
                        .append(Component.text("[compartilhar]", NamedTextColor.AQUA)
                                .clickEvent(ClickEvent.runCommand("/coord mostrar-aqui"))
                                .hoverEvent(HoverEvent.showText(Component.text("Manda sua posicao no chat")))));
            }
            case "mostrar-aqui" -> Bukkit.broadcast(Component.text("📍 " + p.getName() + " esta em ", NamedTextColor.GRAY)
                    .append(Component.text(x + " " + y + " " + z, NamedTextColor.YELLOW, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.copyToClipboard(x + " " + y + " " + z)))
                    .append(Component.text(" (" + dim(world) + ")", NamedTextColor.GRAY)));
            case "salvar", "publico" -> {
                if (args.length < 2) { p.sendMessage(Component.text("Uso: /coord " + sub + " <nome>", NamedTextColor.RED)); return true; }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                boolean pub = sub.equals("publico");
                String path = (pub ? pubs : mine) + "." + key(name);
                data.set(path + ".name", name);
                data.set(path + ".owner", p.getName());
                data.set(path + ".world", world);
                data.set(path + ".x", x); data.set(path + ".y", y); data.set(path + ".z", z);
                save();
                if (pub) Bukkit.broadcast(Component.text("📍 " + p.getName() + " marcou ", NamedTextColor.AQUA)
                        .append(line(p, name, p.getName(), world, x, y, z, true)));
                else p.sendMessage(Component.text("Salvo: ", NamedTextColor.GREEN).append(line(p, name, p.getName(), world, x, y, z, false)));
            }
            case "lista" -> {
                p.sendMessage(Component.text("── Coordenadas (run #" + run + ") ──", NamedTextColor.GOLD));
                int n = list(p, data.getConfigurationSection(mine), false) + list(p, data.getConfigurationSection(pubs), true);
                if (n == 0) p.sendMessage(Component.text(" nada salvo ainda. /coord salvar <nome>", NamedTextColor.GRAY));
            }
            case "mostrar" -> {
                if (args.length < 2) { p.sendMessage(Component.text("Uso: /coord mostrar <nome>", NamedTextColor.RED)); return true; }
                String k = key(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                ConfigurationSection s = data.getConfigurationSection(mine + "." + k);
                if (s == null) s = data.getConfigurationSection(pubs + "." + k);
                if (s == null) { p.sendMessage(Component.text("Nao achei essa coordenada.", NamedTextColor.RED)); return true; }
                for (Player v : Bukkit.getOnlinePlayers()) {
                    v.sendMessage(Component.text("📍 " + p.getName() + ": ", NamedTextColor.AQUA)
                            .append(line(v, s.getString("name"), s.getString("owner"), s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z"), false)));
                }
            }
            case "apagar" -> {
                if (args.length < 2) { p.sendMessage(Component.text("Uso: /coord apagar <nome>", NamedTextColor.RED)); return true; }
                String k = key(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                boolean removed = false;
                if (data.contains(mine + "." + k)) { data.set(mine + "." + k, null); removed = true; }
                else if (data.contains(pubs + "." + k) && (p.getName().equals(data.getString(pubs + "." + k + ".owner")) || p.isOp())) {
                    data.set(pubs + "." + k, null); removed = true;
                }
                save();
                p.sendMessage(Component.text(removed ? "Apagado." : "Nao achei (ou nao e sua).", removed ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            default -> p.sendMessage(Component.text("/coord [salvar|publico|lista|mostrar|apagar] <nome>", NamedTextColor.YELLOW));
        }
        return true;
    }

    private int list(Player p, ConfigurationSection s, boolean pub) {
        if (s == null) return 0;
        int n = 0;
        for (String k : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(k);
            if (c == null) continue;
            p.sendMessage(line(p, c.getString("name"), c.getString("owner"), c.getString("world"), c.getInt("x"), c.getInt("y"), c.getInt("z"), pub));
            n++;
        }
        return n;
    }
}
