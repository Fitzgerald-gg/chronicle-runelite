# Generators

The combat achievement and achievement diary tables in `src/main/resources/chronicle/`
are generated from the OSRS Wiki. Each has a `_meta` block saying where it came from
and when.

## `gen_combat_achievements.py`

    python3 tools/gen_combat_achievements.py \
        src/main/resources/chronicle/osrs_combat_achievements.json

Reads the six `Combat Achievements/<tier>` pages and keys every task by
`data-ca-task-id`, the game's own id, so a renamed task keeps its key. The tier
point values and totals in `_meta` come from the wiki's tier table, which the
script does not read, so they can be used to check the scraped tasks.

## `gen_achievement_diaries.py`

    python3 tools/gen_achievement_diaries.py \
        src/main/resources/chronicle/osrs_achievement_diaries.json

Reads the twelve `<region> Diary` pages. It finds the task tables by their
`data-diary-name` and `data-diary-tier` attributes, because on Karamja the first
table after a tier heading is the audio player.

RuneLite has the same data in compiled per-region classes, but `DiaryRequirement`
is package private and reading it would mean reflecting into another plugin, so
the plugin bundles its own copy.
