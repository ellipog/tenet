# KubeJS scripting

A questline can be driven from a KubeJS script: stages, quest state, completions, custom [[task]] and
[[reward]] handlers, and the six lifecycle events. The integration is **NeoForge only**, and that is
KubeJS's doing rather than a choice. KubeJS 7, the 1.21 line, publishes no Fabric artifact. The
operations themselves live in `TenetScripts`, which names no KubeJS type, so the Fabric build carries
the same Java API for addons; only the script binding is NeoForge's.

KubeJS is optional. Nothing requires it to build, to launch, or to play, and the mod does nothing
script-shaped when it is absent.

## The `Tenet` binding

A server script sees one object, `Tenet`. Every method is one call over the same service the commands
use, so a script cannot do what an operator could not.

| Method | Answers |
|---|---|
| `Tenet.hasStage(player, 'my_pack:inducted')` | whether this player has the stage |
| `Tenet.addStage(player, 'my_pack:inducted')` | whether anything changed: false when they already had it |
| `Tenet.removeStage(player, 'my_pack:inducted')` | whether it was there to take |
| `Tenet.stages(player)` | every stage they have, as strings, sorted |
| `Tenet.hasTeamStage(player, 'my_pack:inducted')` | whether this player's team holds the stage |
| `Tenet.addTeamStage(player, 'my_pack:inducted')` | whether anything changed: granted to the team, told to every online member |
| `Tenet.removeTeamStage(player, 'my_pack:inducted')` | whether it was there to take |
| `Tenet.teamStages(player)` | every stage the team holds, as strings, sorted |
| `Tenet.state(player, 'quest_id')` | `LOCKED`, `UNLOCKED`, `STARTED`, `COMPLETED`, or `UNKNOWN` for an id that resolves to no quest |
| `Tenet.complete(player, 'quest_id')` | finishes it exactly as `/tenet complete` does (the same service call, so a script cannot complete what the command would refuse) |
| `Tenet.resolve(player, 'quest_id')` | the quest a string names, or null, for a script deciding whether to bother |
| `Tenet.customTaskIds()` | the ids this build has a custom-task handler for |
| `Tenet.customRewardIds()` | the same for custom rewards |
| `Tenet.registerTask(id, handler)` | registers what a `tenet:custom` task measures |
| `Tenet.registerReward(id, handler)` | registers what a `tenet:custom` reward does |

There are no permission checks on these, by design: the person who wrote the script owns the
questline, and a permission check would be asking them for their own permission. A stage id that does
not parse, or a player who is null, gets a plain `false` or `UNKNOWN` rather than an error thrown into
the script.

`Tenet.state` answers from stored progress, the team's, so a quest gated on a stage this player
lacks still reads `UNLOCKED` there: the gate is a per-player overlay applied when that player's view
is sent. Ask `Tenet.hasStage` for the fact the gate reads.

## Events

`TenetEvents` is a server event group with one entry per lifecycle moment. Every listener is a
function, and every event object is a description of what happened: public fields, not getters.

| Event | Fields |
|---|---|
| `TenetEvents.questStarted` | `player`, `quest`, `title` |
| `TenetEvents.questCompleted` | `player`, `quest`, `title` |
| `TenetEvents.taskCompleted` | `player`, `quest`, `taskIndex` |
| `TenetEvents.rewardClaimed` | `player`, `quest`, `type` |
| `TenetEvents.stageAdded` | `player`, `stage` |
| `TenetEvents.stageRemoved` | `player`, `stage` |
| `TenetEvents.clickEvent` | `player`, `id`, `chapter`, `element` |

## A worked script

```js
// kubejs/server_scripts/tenet.js

// Finishing a quest grants a stage. This is how a pack writes "finishing this opens that chapter"
// when the thing linking the two is not a dependency edge -- a stage gate is per player, and a
// dependency is not.
TenetEvents.questCompleted(event => {
  if (event.quest === 'the_calling') {
    Tenet.addStage(event.player, 'my_pack:inducted')
  }
})

// Stages have their own events, so a script can watch the flag rather than the quest that set it.
TenetEvents.stageAdded(event => {
  console.info('[tenet] stage granted: ' + event.stage)
})

// A canvas press whose click is a script event: the picture names an id, and the script sorts
// presses by it. The chapter and the element travel because a press is a place as well as an
// event -- two pictures firing one id are still two pictures:
//   { "type": "image", "id": "sounder", "click": { "type": "custom_event", "data": "my_pack:sounded" } }
TenetEvents.clickEvent(event => {
  if (event.id === 'my_pack:sounded') {
    console.info('[tenet] ' + event.player.name.string + ' sounded ' + event.element)
  }
})

// A custom task: the handler answers how far along the player is, counted the way the task's own
// `value` counts. The quest names the id:
//   { "type": "tenet:custom", "id": "my_pack:inducted_check", "value": 1 }
//
// The context is a Java record, so its fields are method calls -- `context.player()` and not
// `context.player`. Without the parentheses the handler receives the method itself, and the
// mistake only shows when the task is evaluated. A handler that throws reads as zero and warns
// once, rather than crashing the tick that polled it.
Tenet.registerTask('my_pack:inducted_check', (task, context) => {
  return Tenet.hasStage(context.player(), 'my_pack:inducted') ? 1 : 0
})

// A custom reward: the handler does whatever the pack needs when the reward is collected.
Tenet.registerReward('my_pack:announce', (player, context) => {
  console.info('[tenet] reward collected on ' + context.questId)
})
```

Register handlers at the top level of a server script, not inside a listener: the registry is
consulted when a quest file is read, and handlers are forgotten on a server reload, so a reload
replaces them rather than stacking them, and a deleted script's handler stops existing. A client
script reload does not clear them, only a server one does.

> [!NOTE]
> A `tenet:custom` task whose handler is not registered is not an error: the quest still loads and the
> [[tenet:authoring/validation|validator]] warns that nothing in this build provides the id. That is what a pack
> that ships its handler mod as an optional dependency wants to see.

## What has been verified, and what has not

The binding was run, not assumed. A `:neoforge:runServer` with KubeJS on the run classpath and a script
at `neoforge/run/kubejs/server_scripts/tenet_verify.js` produces, in the server log: KubeJS's own
`Found plugin source tenet`, then the script's lines (`Tenet` is an object, its methods are
callable, `Tenet.state(null, "nothing")` answers `UNKNOWN`, a `TenetEvents.stageAdded` listener
attaches, and a JS arrow function passed to `registerTask`/`registerReward` converts into a Java
handler and appears in `customTaskIds()`/`customRewardIds()`). The load reports 0 errors and 0
warnings.

What that run cannot prove, said plainly: no player exists at server start, so a handler's body
running against a real quest is the pack's own test, and the client-side look of a stage-gated quest
is on the manual playtest list in `TESTING.md`.
