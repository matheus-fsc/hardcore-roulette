package dev.math.hcroulette.api;

import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Uma run nova comecou num mundo novo. Disparado depois que os jogadores foram levados para ele.
 * Modo votacao: todos os jogadores. Modo party: so os membros da party que avancou.
 */
public class RunStartEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final World world;
    private final int run;
    private final List<UUID> players;

    public RunStartEvent(World world, int run, List<UUID> players) {
        this.world = world;
        this.run = run;
        this.players = List.copyOf(players);
    }

    public World getWorld() { return world; }

    /** Numero da run (modo party: nivel do World #n). */
    public int getRun() { return run; }

    /** Jogadores que comecam a vida neste mundo. */
    public List<UUID> getPlayers() { return players; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
