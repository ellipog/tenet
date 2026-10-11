# Commands

Everything Tenet can be asked from the command line is under `/tenet`. Player actions — submitting,
claiming, opening the book, and everything under `party` — are for whoever is playing. The commands that change the
quest files or operator state (`reload`, `removed`, `restore`, `complete`, `complete-all`, `reset`, `reset-all`, `rewards
block|unblock`, `stage add|remove`, and naming another player in `stage list`) ask for permission level
2, a command block's level — and so does everything under `table`, the reads included, along with
`config`: they name the server's own file paths and table ids. The three diagnostics in their own table
below take the same level, because they read the server's own costs rather than a player's.

## For players

| Command | What it does |
|---|---|
| `/tenet quests` | The tree at a glance: how many quests, chapters and chapter groups, then the groups by title. |
| `/tenet quest <id>` | One quest as the engine sees it: location, dependencies, and the flags set on it. |
| `/tenet progress` | The tree from your point of view: each quest's state, and every task's count against what it needs. |
| `/tenet submit <quest> [<index>]` | Hand a task in by hand — the same call the Submit button makes. The optional second argument is the task's index, a bare integer counted from 0; omitted, it is the first task. |
| `/tenet claim <quest>` | Collect the quest's outstanding rewards. The same call the Claim button makes. |
| `/tenet emergency` | Collect the pack's emergency shelf: the file's `emergencyItems`, once per `emergencyItemsCooldown` seconds — see [[tenet:authoring/quest-files]]. What fits goes into your inventory; the rest drops at your feet. |
| `/tenet open_book [quest]` | Open the quest book on your client — on one quest when one is named. the legacy mod's `/ftbquests open_book`, which command rewards and click actions name. |
| `/tenet stage list [player]` | The stages a player carries. Without an argument, your own; naming another player asks for permission level 2. |
| `/tenet stage team-list [player]` | The stages a player's team holds. The same permission rule as `list`. |
| `/tenet party` | Who is in your party, how it counts, and **which mod the parties come from** — Tenet's answer to "are these the parties I think they are". |
| `/tenet version` | The Tenet and Armature versions this server is running. |
| `/tenet types` | Every task, reward and condition type this build has, with the fields each one takes. |

## For operators

| Command | What it does |
|---|---|
| `/tenet reload` | Re-read the quest folder without a restart, and re-sync every connected player. Reports how many files loaded, and how many had errors. |
| `/tenet removed` | Every recoverable delete under the quest folder — the copy a delete sets aside, and the name each would come back as. See [removing something](index.md#removing-something-and-getting-it-back). |
| `/tenet restore <path>` | Put one of those back, using the path `/tenet removed` printed. The file or folder returns under its own name and is listed again where it belongs, and every connected player's tree is refreshed — no reload, so the undo history survives it. |
| `/tenet complete <quest> [with-dependencies] [player]` | Mark a quest complete for the team. It checks that the quest is playable and its stage gate is met, but it does not evaluate the tasks — that is what it is for. Rewards are left waiting to be claimed. `with-dependencies` finishes the quest and everything it waits on, dependencies first. With a player it moves their progress rather than yours. |
| `/tenet complete-all [player]` | Mark every completable quest complete, iterating to a fixed point so a chain finishes in one press. Without a player, your own book; with one, theirs. |
| `/tenet reset [quest] [with-dependencies] [player]` | Clear progress — one quest, or the whole tree when no quest is named. `with-dependencies` clears the quest and everything it waits on. With a player it clears theirs, which the console may run; without one, run it as a player, because progress belongs to a team and the console is not in one. |
| `/tenet reset-all [player]` | Clear the whole book — the explicit spelling of a bare `/tenet reset`, and with a player, theirs. |
| `/tenet rewards block` / `unblock` | Hold or release the team's rewards. A held team collects nothing: the automatic payouts stop and every claim is refused, except for rewards marked `ignoreRewardBlocking`. |
| `/tenet stage add <player> <stage>` | Grant a stage. |
| `/tenet stage remove <player> <stage>` | Take one away. |
| `/tenet stage add-team <player> <stage>` | Grant a stage to the player's team — the legacy mod's `/ftbteams teamstage`. Every online member is told, because each of them sees their own stages and their team's together. |
| `/tenet stage remove-team <player> <stage>` | Take a team's stage away. |
| `/tenet config` | The server's settings in force — the party cap and a new party's policy, the tree-wide quest defaults — and the files they live in. Edit and `/tenet reload` — both files are re-read. A player's own text size is in the book's Settings card; the palette, the corner radius and the Motion switch are in the tools panel's Quest Book tab, which needs edit permission. |

Three diagnostics, and they are operator tools rather than preferences:

| Command | What it does |
|---|---|
| `/tenet vitals [on\|off]` | Show or hide the frame-rate readout over the canvas. Bare, it flips. |
| `/tenet editcost [on\|off]` | Start or stop logging what one editing gesture costs on the server. It works from the console, because the question it answers is about a server's cost rather than a client's. |
| `/tenet iconmode <0-3>` | A temporary experiment in how a node's item icon is drawn: four arms, so the one that shows the item names the cause of the one that did not. It sets a **system property**, so on a dedicated server it changes the server's JVM and nothing visible. |

## Reward tables

Tables live in `config/tenet/quests/reward_tables/<id>.json` and are pointed at by the four table
reward types — see [rewards](authoring/rewards.md). The panel covers most of the editing, including
reading **the container you are looking at** into a table (`Import... › From target chest`). These
commands are for what a screen still cannot do: they are aimed actions, and an open book captures the
mouse — filling a container from a table, and reading one while you are walking around.

| Command | What it does |
|---|---|
| `/tenet table list` | Every loaded table: its id, its title, its entry count and how many times it rolls. |
| `/tenet table roll <id> [rolls [reading]]` | Roll a table without granting anything, and print what came up — nesting included, and a nested loot table that paid nothing says so. Clamped to 500 rolls. The reading is `random` (the default), `loot`, `all_table` or `choice` — the same four words the file's own mode writes — and it can only be given after a count: `/tenet table roll <id> 3 loot`. |
| `/tenet table import <id>` | Add an entry per distinct item in **the container you are looking at**. Appends: it never touches the entries already there, counts are summed per item (components included, so a named sword is its own entry), and anything clipped to the 6400 limit is named. |
| `/tenet table export <id>` | Fill **the container you are looking at** with the table's items, one entry's configured count per stack, split into legal stacks. Empty slots only — nothing already in the chest is touched — then your inventory, then the ground; each non-zero count is reported, in that order. |
| `/tenet table export <id> here` | The same, skipping the chest: your inventory, then the ground. The way out of a room where every angle within reach hits a wall. |
| `/tenet table export <id> nested` | The same, resolving nested tables as well — everything the table can produce. The chest is then a **flattened view**: do not import it back into the same table, or its sub-tables become a flat list of entries. |
| `/tenet table export <id> nested here` | The same again, skipping the chest: everything the table can produce, into your inventory and then the ground. `nested container` is the same as bare `nested`. |
| `/tenet table edit <id>` | Open that table's editor in your quest book. Refuses an unknown id in chat rather than opening a modal that can only show a refusal. |

`import` and `export` need a player: a console has no crosshair, no inventory and no feet to drop
things at. Every subcommand here asks for permission level 2, the reads (`list`, `roll`) included,
because they read the server's files and tables rather than a player's.

## Parties

Party membership is stored and shared by Armature's team API ([[armature:api/teams]]), so the parties
Tenet shows may be its own or another mod's, depending on what is installed.

| Command | What it does |
|---|---|
| `/tenet party create <name>` | Form a party; you are its owner. The name must be 1 to 32 characters. |
| `/tenet party invite <player>` | Invite a player. Members may invite while the party's switch says so; officers always may. |
| `/tenet party accept [party]` | Accept an invitation. With an id, that invitation exactly — a player can hold several, and the panel's Accept button names the row it is on. |
| `/tenet party decline <party>` | Turn an invitation down. |
| `/tenet party join <party>` | Join an open party from the solo screen, without an invitation. Refused while you are in another party. |
| `/tenet party rename <name>` | Rename the party. Owner only; the same name rule as `create`. |
| `/tenet party transfer <player>` | Hand ownership to a member. The previous owner becomes an ordinary member. |
| `/tenet party handover <player>` | Give the party away **and leave it**, in one validated operation — what the panel's successor picker sends. |
| `/tenet party uninvite <player>` | Withdraw an invitation before it is answered. Whoever sent it may always withdraw their own. |
| `/tenet party open [on\|off]` | Read, or set, whether anybody may join without an invitation. Owner only. |
| `/tenet party member-invites [on\|off]` | Read, or set, whether ordinary members may invite. Owner only. On by default. |
| `/tenet party leave` | Leave the party you are in. An owner with members present is sent to the successor picker by the panel; by command the ownership fallback applies — the most senior remaining member, ties broken arbitrarily but stably. |
| `/tenet party kick <player>` | Remove a member. |
| `/tenet party disband` | End the party for everyone in it. |
| `/tenet party mode [mode]` | Read, or set, how the party's counts combine. |

**Leaving keeps what you earned.** When a membership ends — leave, kick or disband — the party's
completed quest nodes are merged into the departing player's own record (union, best-of-both), so a
progression-gated pack cannot soft-lock somebody who did a chain with friends and then went solo. A
reward already collected in the party stays collected; one never claimed stays claimable. Joining a
party still imports nothing.

**A party holds eight by default**, and both the invitation and the answer are refused past the cap —
a party can fill between the two. A server may set `teams.maxMembers` between 2 and 64 in
`config/armature/config.json`; see Armature's teams page for the file and what else is in it.

**On a singleplayer world whose LAN is closed**, the commands that change a party refuse: there is
nobody to party with, and the refusal says so rather than failing silently.

**On servers whose parties come from another mod**, the commands refuse with the source's name where
their API cannot do the operation: Open Parties and Claims has no rename, transfer or policy, and a bridged read-only source stays read-only here. `mode` works everywhere, because how a party counts is Tenet's own.

A party's mode decides what "the party has eight logs" means:

| Mode | The party's count is |
|---|---|
| `one_member` | The largest single member's count — one player gathers and both progress. The default. |
| `pooled` | Every member's count added together. A consuming task pays from as many members as it takes. |
| `owner_only` | The owner's count alone, whatever anyone else carries. |

There is one more command, `/tenet echo <times>`, and it is not useful: it is kept because it is the
cheapest proof that a Brigadier argument and a return value survive the trip through Armature's
command event.
