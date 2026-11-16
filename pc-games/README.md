# Jogos do PC na TV

Mostra na TV os jogos instalados no computador, com capa. OK num jogo abre o jogo no PC e troca a TV para a entrada HDMI do PC.

## O que ele encontra

- Atalhos do menu Iniciar e da Área de Trabalho que apontam para uma pasta `Games` (`D:\Games`, `E:\Games`...) ou para a Epic, a Steam ou a Riot.
- Jogos da Epic, da Steam (todas as bibliotecas) e o Battle.net.
- Capas da loja da Steam, guardadas em `%USERPROFILE%\.cleanyourtv\game-covers`. Sem capa, a TV mostra o nome.

## Instalar

1. Python 3.8 ou mais novo no PC.
2. Rode uma vez `python games_agent.py --list` para ver a lista.
3. Para iniciar com o Windows: atalho para `iniciar-jogos.vbs` em `shell:startup`.
4. Ligue a TV ao PC: `python games_agent.py --tv-config` gera o bloco `games_pc` do `config.json` da TV (envie com `launcher-ui/config.sh`).
   A entrada HDMI do PC precisa ter o nome `PC` na TV (ou troque `"input"`).

## Ajustes (`%USERPROFILE%\.cleanyourtv\games-agent.json`)

| Chave | Para quê |
|---|---|
| `game_dirs` | Outras pastas de jogos, além de `X:\Games` |
| `search` | Nome em inglês para achar a capa (`"Ratchet e Clank - Rift Apart": "Ratchet & Clank: Rift Apart"`) |
| `covers` | Capa própria: link ou arquivo |
| `hidden` | Jogos que não devem aparecer |
| `before_launch` | Atalho rodado antes do jogo (por exemplo, mandar a imagem só para a TV) |

Só responde na rede de casa e com o código de acesso. Só abre jogos da própria lista.
