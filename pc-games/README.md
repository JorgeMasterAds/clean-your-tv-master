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
4. Ligue a TV ao PC pela própria TV: Configurações › Abas e reprodução › **Jogos do PC** › OK. A TV procura o PC na rede
   (UDP 48501) e o PC mostra uma janela "Permitir?"; clique em Sim. Em **Entrada do computador**, escolha com as setas a
   entrada HDMI onde o PC está ligado. Se o Windows perguntar sobre o firewall, permita o Python na rede privada.
   (Alternativa por ADB: `python games_agent.py --tv-config` gera o bloco `games_pc` do `config.json`.)

## Na TV

- Aba **Jogos** e um dock de jogos: na tela inicial, desça até Entradas e Atalhos e desça mais uma vez.
- Ordem: o jogado por último fica à esquerda (abertos pela TV, pela Steam ou pelo Windows). Segure OK num jogo para
  movê-lo com ← →; OK ou Voltar solta. Depois que você move, a sua ordem vale; jogos novos entram na frente.
- O fundo mostra a arte larga do jogo (da Steam, ou sua em `game-art-own/<nome>.jpg`); sem ela, a capa.

## Ajustes (`%USERPROFILE%\.cleanyourtv\games-agent.json`)

| Chave | Para quê |
|---|---|
| `game_dirs` | Outras pastas de jogos, além de `X:\Games` |
| `search` | Nome em inglês para achar a capa (`"Ratchet e Clank - Rift Apart": "Ratchet & Clank: Rift Apart"`) |
| `covers` | Capa própria: link ou arquivo |
| `hidden` | Jogos que não devem aparecer |
| `before_launch` | Atalho rodado antes do jogo (por exemplo, mandar a imagem só para a TV) |

Só responde na rede de casa e com o código de acesso. Só abre jogos da própria lista.
