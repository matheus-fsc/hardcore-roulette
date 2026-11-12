package dev.math.hcroulette.api;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * API publica do HardcoreRoulette para addons.
 *
 * <pre>{@code
 * HardcoreRouletteAPI api = HardcoreRouletteAPI.get(); // null se o HardcoreRoulette nao estiver ligado
 * if (api != null && api.isRunWorld(player.getWorld())) { ... }
 * }</pre>
 *
 * Eventos: {@link RunStartEvent}, {@link PlayerLifeEndEvent}, {@link RunEndEvent}.
 */
public interface HardcoreRouletteAPI {

    /** Instancia registrada no ServicesManager, ou null se o HardcoreRoulette nao estiver ligado. */
    static HardcoreRouletteAPI get() {
        return Bukkit.getServicesManager().load(HardcoreRouletteAPI.class);
    }

    /** Versao da API (sobe quando metodos ou eventos novos aparecem). */
    int apiVersion();

    /** Numero da run atual (modo votacao). No modo party, o nivel mais alto entre as parties. */
    int runNumber();

    /** Mundo da run atual. No modo party, o mundo com mais jogadores. */
    World currentWorld();

    /** Se o mundo (ou o _nether/_the_end dele) e um mundo de run. */
    boolean isRunWorld(World world);

    /** Prefixo dos mundos de run (worlds.prefix). */
    String worldPrefix();

    /** Nome base de um mundo, sem _nether ou _the_end. */
    String baseWorldName(World world);

    /** Se o servidor esta no modo party (cada party com o proprio mundo). */
    boolean isPartyMode();

    /** Membros da party do jogador (incluindo ele). Lista vazia se nao estiver em party. */
    List<UUID> partyMembers(UUID player);

    /** Quem recebe XP compartilhado de {@code player}, respeitando o alcance pelo nivel da party. */
    List<Player> xpShareTargets(Player player);

    /** Fracao do XP repassada a cada membro (party.xp-share). */
    double xpShareRatio();

    /** Tempo vivo da run: a maior vida atual entre quem esta online (segundos). API 2. */
    long runAliveSeconds();

    /** Se a votacao do proximo mundo esta aberta. API 2. */
    boolean isVoting();
}
