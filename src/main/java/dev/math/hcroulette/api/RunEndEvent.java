package dev.math.hcroulette.api;

import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A run acabou (no modo votacao: alguem morreu e a votacao do proximo mundo comecou;
 * no modo party: a party do jogador caiu). O mundo ainda existe quando o evento dispara.
 */
public class RunEndEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final World world;
    private final int run;

    public RunEndEvent(World world, int run) {
        this.world = world;
        this.run = run;
    }

    public World getWorld() { return world; }
    public int getRun() { return run; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
