# HardcoreRoulette

Plugin Paper para **hardcore em grupo**: quando alguém morre, o mundo reseta para todos, com mundos reserva pré-gerados, votação de seed, modo party por níveis, invasões de facções controladas por bots e várias ferramentas para servidores pequenos entre amigos.

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
- `/coord`: coordenadas salvas no servidor por run: privadas ou públicas, com distância e seta de direção, clique para copiar.
- Tag do mundo no Tab: `[W#3] Nick` (party) ou `[#12] Nick`.
- Skills (ex.: AuraSkills) resetam **só para quem morreu** via comandos configuráveis.

### Invasões de facções
Inimigos controlados por bots externos ([minecraft-raiders](https://github.com/matheus-fsc/minecraft-raiders)):
- **Zumbis**: mobs do próprio servidor (sem bots), à noite, com rastreio de até 128 blocos. Papéis com regra de ferramenta do vanilla: **minerador** (picareta de madeira) quebra e coloca pedra e o que a picareta de madeira minera; **cavador** (pá de madeira) quebra terra, grama, areia, cascalho e argila e coloca terra; **lenhador** (machado de madeira) quebra e coloca blocos de madeira; brutamontes e tanque só perseguem e lutam. Começam sem blocos e têm inventário com limite (`horde.block-capacity`): constroem pilares e pontes só com o que mineraram, trabalham em duas fases: **coleta** (calcula o material que a subida precisa e busca recursos expostos num raio de 10 blocos, 16 para árvores) e **ataque**; se acabar o material no meio, voltam a coletar. Soltam o inventário ao morrer. A horda se distribui entre os jogadores próximos (cada zumbi vai no jogador com menos zumbis em cima, ponderado pela distância). `horde.debug: true` registra fase, inventário e ação de cada zumbi a cada 5 s. O lenhador prepara as escadas no chão como um jogador: crafta uma bancada (4 tábuas) se não tiver, coloca do lado, crafta todas as escadas que a subida precisa (receita vanilla, ~4 tábuas = 3 escadas), guarda a bancada e prega as escadas na parede/penhasco; qualquer zumbi da horda que acha uma escada sobe por ela.
- **Piratas**: de dia, saqueiam baús e fogem.
- **Guilda inimiga**: atravessa o mapa minerando; neutra até ser provocada.

O plugin faz o **reconhecimento do terreno** antes de spawnar (chão firme, céu aberto ou túneis escuros no modo caverna, rota sem penhasco/água larga até o alvo), equipa cada bot conforme o papel e envia um **flare** com a posição real dos jogadores a cada segundo. Nomes de bot só são aceitos vindos de `127.0.0.1` através do proxy.

### Infra
- `/migrar [minutos]`: contagem regressiva e passagem do servidor para outra máquina (fallback), usando um arquivo `migrate.flag` que um launcher externo observa.
- Whitelist Bedrock (Geyser/Floodgate): `/bwhitelist add <gamertag>`.

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
| `/invasao <zumbis|piratas|guilda|parar|status> [qtd] [alvo] [construtores]` | op | Invasões (`construtores` = onda só de minerador, cavador e lenhador) |\|piratas\|guilda\|parar\|status> [qtd] [alvo]` | op | Invasões |
| `/migrar [min\|cancelar]` | op | Migração para o fallback |
| `/bwhitelist <add\|remove> <gamertag>` | op | Whitelist Bedrock |

## Configuração (`plugins/HardcoreRoulette/config.yml`)

Na primeira vez que o servidor liga, o plugin escreve o [config.yml](src/main/resources/config.yml) comentado, com todos os padrões. Chave nova que aparecer numa atualização é adicionada sozinha, sem mexer no que você já mudou.

| Seção | O que controla |
|-------|----------------|
| `server-name` | Nome no título do `/ranking` |
| `worlds.prefix` | Prefixo dos mundos de run (padrão `hc_`; mude só antes da primeira run) |
| `mode`, `pregen-radius`, `on-death-commands` | Modo de jogo, pré-carregamento com Chunky, comandos ao morrer |
| `scoreboard`, `auth`, `announcements` | Placar lateral, tela de login, mensagens automáticas |
| `party` | XP da party e divisão do XP de habilidade |
| `invasion` | Horda noturna: chance, tamanho, rampa por horas de jogo, noites de descanso |
| `nether-horde` | Zumbis-porco que seguem quem fica no Nether |
| `horde` | IA da horda: sair da terra, cercar, rampas, cavar, escolta |
| `portal-omen` | Raios, chuva e noite ao acender um portal do Nether |
| `quick-flask` | Frasco Rápido: tempo de beber, duração e níveis perdidos |
| `invasion.controller-url`, `invasion.token` | Ligação com o minecraft-raiders (o token é gerado sozinho) |

Arquivo opcional `host.txt` na raiz do servidor (`pc` ou `vps`) identifica onde o servidor está rodando, para o `/migrar`.

## Compilar

```bash
mvn package
# target/HardcoreRoulette.jar -> plugins/
```

Requer JDK 25 e Maven. Dependência: `paper-api 26.3` (scope provided).

## Plugins recomendados
- [Chunky](https://modrinth.com/plugin/chunky): pré-renderização dos mundos reserva.
- [ViaVersion](https://modrinth.com/plugin/viaversion) + [ViaBackwards](https://modrinth.com/plugin/viabackwards): necessários para os bots de invasão (conectam como 1.21.1).
- [AuraSkills](https://modrinth.com/plugin/auraskills), [SkinsRestorer](https://modrinth.com/plugin/skinsrestorer).

## Licença

[MIT](LICENSE)
