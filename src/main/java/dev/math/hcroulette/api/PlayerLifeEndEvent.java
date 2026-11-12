package dev.math.hcroulette.api;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/** Um jogador perdeu a vida hardcore (bots de invasao nao contam). Disparado dentro da morte. */
public class PlayerLifeEndEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final World world;

    public PlayerLifeEndEvent(Player player, World world) {
        super(player);
        this.world = world;
    }

    /** Mundo onde morreu. */
    public World getWorld() { return world; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
