# HardcoreRoulette

[English](README.md) | **Português (pt-BR)**

> **Beta.** O HardcoreRoulette está em desenvolvimento ativo: pode mudar e ter arestas. É open source (MIT) e ideias da comunidade são bem-vindas. Sugira recursos ou reporte problemas nos [issues](https://github.com/matheus-fsc/hardcore-roulette/issues/new/choose).


Plugin Paper para **hardcore em grupo**: quando alguém morre, o mundo reseta para todos, com mundos reserva pré-gerados, votação de seed, modo party por níveis e várias ferramentas para servidores pequenos entre amigos. As hordas de zumbis ficam no plugin companheiro [SmartHordes](https://github.com/matheus-fsc/smarthordes).

> Feito para **Paper 26.3** (Java 25). Mensagens em português (pt-BR).

## Recursos

### Reset de mundo
- **Modo votação**: alguém morreu: contagem de 15 min (ou reset imediato quando todos votam). `/vote` abre um baú virtual com as seeds candidatas (bioma do spawn, votos).
- **Modo lobotomia**: morreu, todo mundo vai na hora para um mundo aleatório. O próximo mundo é sorteado com antecedência e só ele é pré-renderizado.
- **Modo party**: os mundos viram níveis (World #1, #2, …) com seeds fixas. Quem entra começa no #1; quando alguém da party morre, **só aquela party** avança junta. Várias parties podem dividir o mesmo nível.
- **Mundos reserva pré-gerados** (spawn + [Chunky](https://modrinth.com/plugin/chunky) opcional) para o reset ser instantâneo.
- Nether/End **por mundo** (portais ligados ao mundo da run).
- Reset completo do jogador: inventário, ender chest, XP, efeitos, conquistas; quem estava offline é resetado ao entrar.
- **Carência de 8 s** após o reset (sem dano de queda acumulada no mundo antigo, sem itens “vazando” entre mundos).

### Jogadores
- `/ranking`: pódio: mais mortes, menos mortes + tempo jogado, mais dano em uma vida.
- `/dia` e anúncio automático de **Dia N: Manhã/Tarde/Noite** com o tempo vivo de cada jogador (formato cresce: `45s → 12min 30s → 2h 05min → 3d 4h`).
- `/coord`: coordenadas salvas no servidor por run: privadas ou públicas, com distância e seta de direção, clique para copiar (`coords.enabled: false` passa isso para um plugin de HUD, como o [DirectionHUD](https://modrinth.com/plugin/directionhud)).
- Tag do mundo no Tab: `[W#3] Nick` (party) ou `[#12] Nick`.
- Skills (ex.: AuraSkills) resetam **só para quem morreu** via comandos configuráveis.

### Plugins companheiros
- **[SmartHordes](https://github.com/matheus-fsc/smarthordes)**: hordas de zumbis que cavam, constroem e cercam, avisadas à tarde. Segue o mundo da run pela API abaixo.
- **[Classforge](https://github.com/matheus-fsc/classforge)**: habilidades por nível presas a cada vida, com XP dividido na party.

### Infra
- `/migrar [minutos]`: contagem regressiva e passagem do servidor para outra máquina (fallback), usando um arquivo `migrate.flag` que um launcher externo observa.
- Whitelist Bedrock (Geyser/Floodgate): `/bwhitelist add <gamertag>`.
- Tela de login para servidores offline (`auth`). Com o [AuthMe](https://modrinth.com/plugin/authmereloaded) instalado ela desliga sozinha, e jogadores Bedrock (Floodgate) entram logados pela API do AuthMe.

## Comandos

| Comando | Quem | Descrição |
|---|---|---|
| `/vote [n]` | todos | Menu de votação da próxima seed |
| `/ranking` (`/podio`, `/metricas`) | todos | Pódio de estatísticas |
| `/dia` | todos | Dia/período e tempo vivo |
| `/coord [salvar\|publico\|lista\|mostrar\|apagar] <nome>` | todos | Coordenadas |
| `/party [convidar <nick>\|aceitar\|sair\|lista]` | todos | Modo party |
| `/hcstatus` | todos | Estado da run |
| `/hcmodo <votacao\|lobotomia\|party>` | op | Troca o modo |
| `/hcreset` | op | Força um reset/votação |
| `/migrar [min\|cancelar]` | op | Migração para o fallback |
| `/bwhitelist <add\|remove> <gamertag>` | op | Whitelist Bedrock |
| `/hcreport` | op | Gera um relatorio para anexar num issue |

## Configuração (`plugins/HardcoreRoulette/config.yml`)

Na primeira vez que o servidor liga, o plugin escreve o [config.yml](src/main/resources/config.yml) comentado, com todos os padrões. Chave nova que aparecer numa atualização é adicionada sozinha, sem mexer no que você já mudou.

| Seção | O que controla |
|-------|----------------|
| `server-name` | Nome no título do `/ranking` |
| `worlds.prefix` | Prefixo dos mundos de run (padrão `hc_`; mude só antes da primeira run) |
| `mode`, `pregen-radius`, `on-death-commands` | Modo de jogo, pré-carregamento com Chunky, comandos ao morrer |
| `scoreboard`, `auth`, `announcements` | Placar lateral, tela de login (e login automático do Bedrock no AuthMe), mensagens automáticas |
| `coords` | Desliga o `/coord` quando há um plugin de HUD |
| `party` | XP da party e divisão do XP de habilidade |
| `portal-omen` | Raios, chuva e noite ao acender um portal do Nether |
| `quick-flask` | Frasco Rápido: tempo de beber, duração e níveis perdidos |

Arquivo opcional `host.txt` na raiz do servidor (`pc` ou `vps`) identifica onde o servidor está rodando, para o `/migrar`.

## Reportar um problema

1. Rode `/hcreport` (op; no console tambem funciona).
2. O plugin cria `plugins/HardcoreRoulette/reports/report-<data>.txt` com versoes, plugins instalados, estado da run, o `config.yml` sem segredos (token, URLs e senhas viram `<removido>`, IPs viram `<ip>`) e as ultimas linhas do log.
3. Abra um [issue](https://github.com/matheus-fsc/hardcore-roulette/issues/new/choose) e anexe o arquivo.

O log completo fica em `plugins/HardcoreRoulette/logs/` (um arquivo por dia, 7 dias). `debug: true` no config.yml deixa o log mais detalhado.

## API para addons

O HardcoreRoulette registra a interface `dev.math.hcroulette.api.HardcoreRouletteAPI` no ServicesManager do Paper:

```java
HardcoreRouletteAPI api = HardcoreRouletteAPI.get(); // null se o HardcoreRoulette nao estiver ligado
if (api != null && api.isRunWorld(player.getWorld())) {
    List<UUID> party = api.partyMembers(player.getUniqueId());
}
```

| Metodo | O que devolve |
|--------|---------------|
| `runNumber()` | Numero da run atual (modo party: nivel mais alto) |
| `currentWorld()` | Mundo da run atual |
| `isRunWorld(World)` | Se o mundo (ou o Nether/End dele) e de run |
| `worldPrefix()`, `baseWorldName(World)` | Prefixo dos mundos e nome sem `_nether`/`_the_end` |
| `isPartyMode()`, `partyMembers(UUID)` | Modo party e membros da party do jogador |
| `xpShareTargets(Player)`, `xpShareRatio()` | Quem recebe XP compartilhado e a fracao |
| `runAliveSeconds()`, `isVoting()` | Tempo vivo da run e se a votacao esta aberta (API 2) |

Eventos: `RunStartEvent` (mundo novo, numero da run, jogadores), `RunEndEvent` (a run acabou) e `PlayerLifeEndEvent` (um jogador perdeu a vida).

Para compilar contra a API: `mvn install` neste repositorio e, no seu `pom.xml`, a dependencia `dev.math:HardcoreRoulette:1.4.0` com `<scope>provided</scope>`. Declare `softdepend: [HardcoreRoulette]` no plugin.yml. O [Classforge](https://github.com/matheus-fsc/classforge) e o [SmartHordes](https://github.com/matheus-fsc/smarthordes) usam a API assim.

## Compilar

```bash
mvn package
# target/HardcoreRoulette.jar -> plugins/
```

Requer JDK 25 e Maven. Dependência: `paper-api 26.3` (scope provided).

## Plugins recomendados
- [Chunky](https://modrinth.com/plugin/chunky): pré-renderização dos mundos reserva.
- [SmartHordes](https://github.com/matheus-fsc/smarthordes): hordas de zumbis.
- [AuthMe](https://modrinth.com/plugin/authmereloaded): login para servidores offline.
- [DirectionHUD](https://modrinth.com/plugin/directionhud): waypoints e bússola na tela.
- [AuraSkills](https://modrinth.com/plugin/auraskills), [SkinsRestorer](https://modrinth.com/plugin/skinsrestorer).

## Licença

[MIT](LICENSE)
