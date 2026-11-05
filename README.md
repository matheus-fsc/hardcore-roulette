# HardcoreRoulette

Plugin Paper para **hardcore em grupo**: quando alguém morre, o mundo reseta para todos — com mundos reserva pré-gerados, votação de seed, modo party por níveis, invasões de facções controladas por bots e várias ferramentas para servidores pequenos entre amigos.

> Feito para **Paper 26.3** (Java 25). Mensagens em português (pt-BR).

## Recursos

### Reset de mundo
- **Modo votação** — alguém morreu: contagem de 15 min (ou reset imediato quando todos votam). `/vote` abre um baú virtual com as seeds candidatas (bioma do spawn, votos).
- **Modo lobotomia** — morreu, todo mundo vai na hora para um mundo aleatório. O próximo mundo é sorteado com antecedência e só ele é pré-renderizado.
- **Modo party** — os mundos viram níveis (World #1, #2, …) com seeds fixas. Quem entra começa no #1; quando alguém da party morre, **só aquela party** avança junta. Várias parties podem dividir o mesmo nível.
- **Mundos reserva pré-gerados** (spawn + [Chunky](https://modrinth.com/plugin/chunky) opcional) para o reset ser instantâneo.
- Nether/End **por mundo** (portais ligados ao mundo da run).
- Reset completo do jogador: inventário, ender chest, XP, efeitos, conquistas; quem estava offline é resetado ao entrar.
- **Carência de 8 s** após o reset (sem dano de queda acumulada no mundo antigo, sem itens “vazando” entre mundos).

### Jogadores
- `/ranking` — pódio: mais mortes, menos mortes + tempo jogado, mais dano em uma vida.
- `/dia` e anúncio automático de **Dia N — Manhã/Tarde/Noite** com o tempo vivo de cada jogador (formato cresce: `45s → 12min 30s → 2h 05min → 3d 4h`).
- `/coord` — coordenadas salvas no servidor por run: privadas ou públicas, com distância e seta de direção, clique para copiar.
- Tag do mundo no Tab: `[W#3] Nick` (party) ou `[#12] Nick`.
- Skills (ex.: AuraSkills) resetam **só para quem morreu** via comandos configuráveis.

### Invasões de facções
Inimigos controlados por bots externos ([minecraft-raiders](https://github.com/matheus-fsc/minecraft-raiders)):
- 🧟 **Zumbis** — à noite, cercam o alvo, cavam e constroem.
- 🏴‍☠️ **Piratas** — de dia, saqueiam baús e fogem.
- ⚔️ **Guilda inimiga** — atravessa o mapa minerando; neutra até ser provocada.

O plugin faz o **reconhecimento do terreno** antes de spawnar (chão firme, céu aberto ou túneis escuros no modo caverna, rota sem penhasco/água larga até o alvo), equipa cada bot conforme o papel e envia um **flare** com a posição real dos jogadores a cada segundo. Nomes de bot só são aceitos vindos de `127.0.0.1` através do proxy.

### Infra
- `/migrar [minutos]` — contagem regressiva e passagem do servidor para outra máquina (fallback), usando um arquivo `migrate.flag` que um launcher externo observa.
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
| `/invasao <zumbis\|piratas\|guilda\|parar\|status> [qtd] [alvo]` | op | Invasões |
| `/migrar [min\|cancelar]` | op | Migração para o fallback |
| `/bwhitelist <add\|remove> <gamertag>` | op | Whitelist Bedrock |

## Configuração (`plugins/HardcoreRoulette/config.yml`)

```yaml
mode: votacao            # votacao | lobotomia | party
pregen-radius: 300       # raio do Chunky para o proximo mundo (0 desliga)
on-death-commands:       # rodados pelo console quando alguem morre ({player})
  - skills skill reset {player}
invasion:
  enabled: true
  controller-url: http://127.0.0.1:8765
  token: <gerado automaticamente>   # o mesmo RAID_TOKEN do minecraft-raiders
  chance: { zumbis: 0.35, piratas: 0.12, guilda: 0.10 }
  min-day: 1
bedrock-whitelist: []
```

Arquivo opcional `host.txt` na raiz do servidor (`pc` ou `vps`) identifica onde o servidor está rodando, para o `/migrar`.

## Compilar

```bash
mvn package
# target/HardcoreRoulette.jar -> plugins/
```

Requer JDK 25 e Maven. Dependência: `paper-api 26.3` (scope provided).

## Plugins recomendados
- [Chunky](https://modrinth.com/plugin/chunky) — pré-renderização dos mundos reserva.
- [ViaVersion](https://modrinth.com/plugin/viaversion) + [ViaBackwards](https://modrinth.com/plugin/viabackwards) — necessários para os bots de invasão (conectam como 1.21.1).
- [AuraSkills](https://modrinth.com/plugin/auraskills), [SkinsRestorer](https://modrinth.com/plugin/skinsrestorer).

## Licença

[MIT](LICENSE)
