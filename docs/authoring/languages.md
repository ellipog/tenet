# Languages

A questline written for one language is a questline half the people who install it cannot read. Tenet
lets a pack ship its own translations beside the book, one file per language, and sends each player the
one they read, without the quest files being written twice and without the tree being sent once per
language.

```
config/tenet/quests/
├── index.json
├── first_light/
│   └── ...
├── reward_tables/
│   └── dice.json
└── lang/
    ├── en_us.json
    └── es_es.json
```

The folder is `lang/`, and it sits at the root of the quest tree next to `reward_tables/`. It is
reserved by name: the root is otherwise strictly what `index.json` lists, so a folder outside
the book has to be named as such, which is why a `lang/` folder produces no
complaint about being unlisted.

The worked questline in the repository ships one as a starting point:
`tools/quests/lang/es_es.json`, with a chapter, a group, a reward table and one quest translated.

## What a file is

A flat object of key to text, the same shape a resource pack's language file has:

```json
{
  "quest.punch_a_tree.title": "Golpea un árbol",
  "quest.punch_a_tree.description": "Todo lo que vale la pena empieza con un árbol.",
  "chapter.first_light.title": "Primera luz"
}
```

The file's own name **is** the locale (`es_es.json` is `es_es`), because there is nowhere else to
declare one and a name no client can ask for is simply never looked up. Names are read lower case with
`-` treated as `_`, so `en-US.json` is `en_us`. A name that is not a locale id at all is refused and
reported, since nothing could ever ask for it.

A file that does not parse, or a value that is not a string, is refused **whole** and reported with its
file, line and column. Half a locale is worse than none: a few sentences translated and the rest
silently in the canonical language is the failure that reads as working. The rest of the folder still
loads, and the questline is never affected. A typo in a translation should cost you a working book and
a line naming the file, not a server that will not start.

## Which file a player gets

The server knows a player's language from the moment they connect, and a player who changes it
mid-session is told to the server as soon as they do. Either way the language is resolved against the
files the pack actually ships, in this order:

| # | Rung | Why it is there |
|---|---|---|
| 1 | the exact locale | You translated for `es_mx`; that is what an `es_mx` player reads |
| 2 | the language root | A file named `es.json` is Spanish wherever it is read, so every `es_*` client wants it |
| 3 | `fallbackLocale`, when it shares the root | An `es_es` file is a far better answer for an `es_mx` player than English, and you have already said which locale is canonical |
| 4 | any other sibling, in name order | Minecraft ships `es_ar`, `es_cl`, `es_ec`, `es_mx`, `es_uy`, `es_ve`, `pt_br`, `pt_pt`, `zh_cn`, `zh_tw` and `zh_hk`; without this rung a pack with one Spanish file would serve none of them. Name order, so the answer is the same on every server and in every run |
| 5 | `fallbackLocale` | Nothing matched the language, so your own language is the honest answer |

The locale that wins is then laid **over** `fallbackLocale`, not instead of it. So a pack can keep the
strings every language shares (a name, a number, a line it did not translate) in its canonical file
and only the differences in the others. `lang/en_us.json`:

```json
{
  "quest.punch_a_tree.title": "Punch a Tree",
  "menu.play": "Play"
}
```

and `lang/es_es.json`, which names only what differs:

```json
{
  "quest.punch_a_tree.title": "Golpea un árbol"
}
```

An `es_es` player then reads `Golpea un árbol` and `Play`. A player whose language has no file at all
still reads the canonical one.

## `fallbackLocale`

```json
{
  "entries": [ { "group": "first_light" } ],
  "settings": {
    "fallbackLocale": "en_us"
  }
}
```

The locale the tree's own strings are written in. A quest file's `title` is what a player reads when
nothing has translated it, so the canonical locale is whatever language those strings are in, and it
is what every other locale is merged over.

## The keys

Every key is the object's own id with a field on the end, so nothing has to be written into the quest
file for a translation to work:

| Object | Keys |
|---|---|
| A quest | `quest.<id>.title`, `quest.<id>.subtitle`, `quest.<id>.description` |
| A chapter | `chapter.<id>.title`, `chapter.<id>.subtitle` |
| A chapter group | `group.<id>.title` |
| A reward table | `rewardTable.<name>.title` |
| A canvas element's label or picture | `element.<id>.text`, `element.<id>.title` |
| The book | `book.title` |

The book's key translates the `bookTitle` in `index.json` (the name the header draws), so a pack
that ships it renames the book for every language it translates without touching the file.

A task's or a reward's sentence is already a translation key of its own (a checkmark task's `title`,
or a built-in sentence like `tenet.reward.xp.points`), so it needs no conventional key: put **its** key
in a locale file and it is translated like any other.

### Descriptions, and paragraphs

A description is a list of paragraphs, and a translation is a sentence, so the two can disagree about
how many there are. Three roads, tried in this order:

1. **`quest.<id>.description`:** one string for the whole description. This is the road to use: write
   it as one paragraph, or put `\n` between them and write it as four, and the canonical count never
   has to agree with yours.
2. **`quest.<id>.description.<i>`:** one paragraph by index, for when a single line of many is wrong.
   The index is against the canonical description, so a key past its end simply never matches.
3. **Nothing:** the canonical paragraphs, as the tree sent them.

### Two roads to the same title

A quest file can also name a translation key itself, which is what a mod shipping its own questline
wants:

```json
{ "id": "punch_a_tree", "title": { "translate": "my_pack.punch", "fallback": "Punch a Tree" } }
```

Then `my_pack.punch` is tried first and `quest.punch_a_tree.title` second, because the more specific thing you
said about this exact quest wins over the pack-wide convention. The `fallback` is what a player reads
when neither is translated, which is why the validator warns when it is missing: without it a missing
translation shows the raw key.

Either key also suppresses the default-title adoption: a quest still titled `New quest` borrows its
first task's name (see [[tenet:authoring/quests]]), unless a translation has words of its own.

### Stale keys

A key for an object that is gone (a deleted quest, a renamed table) warns on reload, with the file
named and, where the spelling is close, the id that was meant. It never fails the load: a pack with
leftover keys still plays. Write keys against the object's own lowercase id: another case, an alias
or a tag is a key no reader looks up, and the warning names the id it should have been. Keys in no
book namespace are left alone, because the overlay serves any key, and a script may own the ones no reader
looks up.

## What a player sees, and what the editor sees

Translations are **player-facing**. The sidebar, the canvas, the reader's card, the completion toast and
the recipe-viewer pages all draw the text in the player's own language.

The **editor** draws the authored text. A chapter panel, a rename card and every editable field are
seeded from what the file says, because a translated string written back to a quest file would replace
your own words with somebody else's translation of them.

## Reloading

```
/tenet reload
```

re-reads `lang/` with everything else and re-sends each player their own locale, so editing a
translation and reloading shows the change without anyone reconnecting. A language changed in the
client's options follows within a tick, with no reconnect at all.

## What is not translatable yet

- A chapter's `description`: nothing draws it for a player, so there is no surface for
  a translation to reach. It is the editor's, and the editor shows the file. The chapter's
  `subtitle` does have one (the second line of its sidebar row's hover), so `chapter.<id>.subtitle`
  translates it.
- A legacy `image` object's title: Tenet has no image object, so the key has no home here.
