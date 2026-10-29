# STKShop (STK SMP)

A configurable Paper plugin with `/shop` and `/buy`, a Vault money shop, a separate DonutShard-powered Shard Shop, confirmation menus, and custom gear kits.

## Included

- Main shop GUI with money player-head balance at bottom-left and Shards at bottom-right.
- Categories for wood/plants, building blocks, ores/minerals, tools/armor, enchanted books, farming, food, mob drops, redstone, Nether, End, potions/brewing, transport, decorations, utility, and miscellaneous items.
- Shop item list seeded from the supplied `sell-prices-source.txt` file. Material names not supported by the installed server version are skipped automatically.
- Buy prices use `sell-price × regular-shop.buy-multiplier` (default `2.5`); there is no sell feature or sell command.
- Click to buy one, shift-click to buy 16, right-click to buy 64.
- Confirmation GUI with green YES and red NO glass panes.
- Shard Shop: one key per purchase, VIP, Battlepass, 3×3 pickaxes, and custom enchanted Diamond/Netherite kits.
- `/stkshop reload` reloads config and prices.
- GitHub Actions Maven build workflow outputs `STKShop-1.0.0.jar` as an artifact.

## Requirements

- Paper/Purpur (built against Paper API 1.21.4, Java 21).
- Vault and a Vault-compatible economy provider for the regular shop.
- DonutShard/DonutShards with a public Java API for the Shard Shop.
- Your existing command plugins for CratesPlus, LuckPerms, PassPlus, and the 3×3 pickaxe plugin.

## Build

Run `mvn clean package` with Java 21 and Maven 3.8+. The output jar is `target/STKShop-1.0.0.jar`. Or push this folder to GitHub and run the included **Build STKShop** Actions workflow.

## Install

1. Upload `STKShop-1.0.0.jar` to the server's `plugins` folder.
2. Restart the server.
3. Edit `plugins/STKShop/config.yml` for prices, commands, and multiplier.
4. Edit `plugins/STKShop/sell-prices.yml` to adjust the sell-price references used by the buy-price formula.
5. Run `/stkshop reload` after editing the files.

## DonutShard compatibility note

The Shard Shop looks for a public `DonutShardAPI` class in the installed DonutShard plugin jar and uses `getBalance`, `take`, and (for refunds if a command fails) `give`. It fails safely if it cannot detect the expected API. Because multiple similarly named DonutShard forks exist, test with a non-production balance first and check the server startup log for `Hooked DonutShard API`.

## Commands and permissions

- `/shop` — opens the shop.
- `/buy` — alias for `/shop`.
- `/stkshop reload` — reloads configuration; requires `stkshop.admin` (default OP).

## Default shard prices

- Common key: 400
- Uncommon key: 800
- Rare key: 1,600
- Legendary key: 2,400
- VIP: 2,500
- Battlepass: 4,000
- Diamond 3×3 pickaxe: 5,000
- Netherite 3×3 pickaxe: 10,000
- Custom enchanted Diamond kit: 15,000
- Custom enchanted Netherite kit: 25,000

Change all shard prices and reward commands in `config.yml`.
