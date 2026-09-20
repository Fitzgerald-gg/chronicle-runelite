/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Turns game events into journal entries: LOOT, LEVEL, DEATH, COLLECTION, PET,
 * QUEST, COMBAT_ACHIEVEMENT, DIARY, SLAYER, CLUE, GROUP_STORAGE, LOOT_UNTAKEN.
 *
 * <p>Registered on the EventBus by {@link ChroniclePlugin} in startUp/shutDown
 * rather than being a plugin itself. Every tap ends at {@link #emit}: the on-disk
 * journal always gets the event, and a copy goes to the configured server only
 * when cloud sync is switched on.
 */
@Slf4j
@Singleton
public class ChronicleEventCapture
{
	// ordinary skills stop here; a reported level above it isn't a real level-up.
	private static final int MAX_LEVEL = 99;

	// Chat taps. Everything below matches AFTER Text.removeTags; no <col> wrappers.
	// The lines only print with the usual chat settings: kill-count spam filter off,
	// collection-log notification on, CA repeat completion off.

	// "Your Zulrah kill count is: 501." and the raid shape "Your completed Theatre
	// of Blood: Hard Mode count is: 40." One expression covers both: optional
	// "completed " prefix, optional tally word (raids omit it), lazy name so a mode
	// suffix's own colon stays in the name.
	static final Pattern KILL_COUNT = Pattern.compile(
		"^Your (?:completed )?(?<subject>.+?)"
			+ "(?: (?<kind>kill|chest|lap|harvest|success|completion))? count is: (?<tally>[\\d,]+)\\.$");

	// Of the words that expression admits, these two are not kills: a lap of an
	// agility course and a herbiboar harvest are activities whose sources the loot
	// ledger already follows. Everything else is the game counting an encounter,
	// including the lines that carry no word at all ("Your subdued Wintertodt
	// count is:", "Your completed Chambers of Xeric count is:").
	private static final java.util.Set<String> NOT_A_KILL =
		new java.util.HashSet<>(java.util.Arrays.asList("lap", "harvest"));

	static final Pattern COLLECTION_ITEM = Pattern.compile(
		"^New item added to your collection log: (?<entry>.+)$");

	// the tier is one word; the challenge is the rest of the line, trailing stop optional.
	static final Pattern COMBAT_TASK = Pattern.compile(
		"^Congratulations, you've completed an? (?<grade>\\w+) combat task: (?<challenge>.+?)\\.?$");
	private static final Pattern COMBAT_TASK_POINTS = Pattern.compile("\\s*\\(\\d+ points?\\)$");

	// "You have completed 87 hard Treasure Trails." Explicit tier set, required closing
	// stop. That keeps out the singular reward-open line, "You have completed a hard
	// Treasure Trail."
	static final Pattern CLUE_COMPLETION = Pattern.compile(
		"^You have completed (?<tally>[\\d,]+) (?<rank>beginner|easy|medium|hard|elite|master)"
			+ " Treasure Trails?\\.$");

	// find(), not matches(): more text follows the area name. The region span is lazy
	// up to " area", keeping "Lumbridge & Draynor" whole.
	static final Pattern DIARY_COMPLETION = Pattern.compile(
		"Congratulations! You have completed all of the (?<grade>\\w+) tasks in the (?<region>.+?) area");

	// The finished line is NOT $-anchored: modern OSRS appends " You gained N xp."
	// after the creature, and [^.]+ already stops at the first period. The total line
	// requires a number, which keeps "…enough tasks to unlock…" out; the qualifier
	// before "task" is the game's own ("N Wilderness tasks", "…1 Mortimer task;…").
	static final Pattern SLAYER_FINISHED = Pattern.compile(
		"^You have completed your task! You killed (?<slain>[\\d,]+) (?<creature>[^.]+)\\.");
	static final Pattern SLAYER_TOTAL = Pattern.compile(
		"^You've completed (?:at least )?(?<total>[\\d,]+) (?<qual>[A-Za-z]+ )?tasks?"
			+ "(?:;| and received)");

	// spelled out in full so the near-misses the game also prints ("being watched",
	// "sneaking into your bank") can't slip through.
	static final Pattern PET_RECEIVED = Pattern.compile(
		"^(?:You have a funny feeling like you're being followed"
			+ "|You feel something weird sneaking into your backpack"
			+ "|You have a funny feeling like you would have been followed\\.\\.\\.)\\.?$");

	// No coin value, no trailing stop. That leaves out the sibling "Valuable drop:
	// …(N coins)" and the "<player> received a drop: …." clan broadcast.
	static final Pattern UNTRADEABLE_DROP = Pattern.compile("^Untradeable drop: (?<dropped>.+)$");

	// Boss timers, phrased several ways: "Fight duration: 1:26.40 (new personal
	// best)", "Duration: 36:04. Personal best: 31:12", "Subdued in 6:23". Raid lines
	// lead with their own prose ("Congratulations - your raid is complete! Duration:
	// …"), so this is a find(). Longer labels precede "Duration" in the alternation
	// so it can't shadow them, and the label is case-insensitive because the raids
	// bury theirs mid-sentence in lower case (ToA "…challenge completion time:
	// 25:33.60", ToB "…total completion time: …"). The timer line never names the
	// boss; pairing with the kill is tick adjacency against the next loot event.
	static final Pattern KILL_DURATION = Pattern.compile(
		"(?i:Fight duration|Challenge duration|Corrupted challenge duration"
			+ "|Completion time|Subdued in|Duration):? (?<time>\\d+(?::\\d{2})+(?:\\.\\d{1,2})?)");
	static final Pattern PERSONAL_BEST = Pattern.compile(
		"[Pp]ersonal best[:!]? (?<pb>\\d+(?::\\d{2})+(?:\\.\\d{1,2})?)");
	private static final String NEW_PB_MARK = "(new personal best)";

	// A successful pickpocket, the core Loot Tracker's expression verbatim. The game
	// posts NPC loot for it as well, which onServerNpcLoot drops by tick.
	static final Pattern PICKPOCKET = Pattern.compile("You pick (the )?(?<target>.+)'s? pocket.*");

	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final ChronicleConfig config;
	private final ChronicleApiClient api;
	private final LocalStore localStore;

	// from RuneLite's core Slayer plugin. Stays null in a dev-mode client, or when the
	// user turns Slayer off; we skip the task stamp then.
	@com.google.inject.Inject(optional = true)
	private SlayerPluginService slayerService;

	private final Map<Skill, Integer> knownLevels = new EnumMap<>(Skill.class);
	private final Set<Skill> pendingLevels = new HashSet<>();
	private final Map<String, Integer> recentKc = new HashMap<>();
	// the most recent boss-timer chat line, held a few ticks to annotate the kill's
	// loot event. One-shot: a later unrelated kill can't inherit it.
	private static final int KILL_TIME_PAIR_TICKS = 4;
	private double lastKillTimeSec = -1;
	private double lastPbTimeSec = -1;
	private boolean lastKillPb;
	private int lastKillTimeTick = -1;
	// the last NPC seen locking onto us, for death attribution when nothing is still
	// engaged at the death tick (poison after the attacker moved on).
	private static final int ATTACKER_MEMORY_TICKS = 50;
	private String lastAttackerName;
	private int lastAttackerTick = -1;
	// the tick of the last successful pickpocket line. The NPC loot the game posts
	// for it lands on the same tick and is not a kill.
	private int pickpocketTick = -1;

	// ── GIM group storage ─────────────────────────────────────────────────
	// Deposits/withdrawals come from diffing the shared bank's TEMP container
	// between the first server sync after the interface opens and its state when the
	// interface closes. The game only commits the session's edits on close, so
	// per-click tracking would count changes the player backed out of.
	private boolean groupStorageOpen;
	private Map<Integer, Integer> groupStorageBaseline;
	private Map<Integer, Integer> groupStorageCurrent;

	// ── NPC loot ──────────────────────────────────────────────────────────
	// NPC loot comes only from the game's own loot script (ServerNpcLoot), the way the
	// core Loot Tracker records it. The despawn-time tile sweep it replaced had no
	// ownership filter, so it booked a player's own drop or a neighbouring kill's
	// stack as the NPC's; the only rows it ever added beyond the script's were those.

	// ── Untaken loot ───────────────────────────────────────────────────────
	// The player's own ground items from a kill, keyed by TileItem identity (the same
	// instance is redelivered on despawn). Tracking is armed for only a few ticks
	// after a kill so manual drops aren't counted. On despawn we ask the item's own
	// scheduled despawn tick whether it timed out (left behind) or was taken early;
	// the timed-out ones are batched into a LOOT_UNTAKEN event.
	private static final int SELF_OWNED = TileItem.OWNERSHIP_SELF;
	private static final int KILL_ARM_TICKS = 3;
	private final Map<TileItem, GroundLoot> groundLoot = new IdentityHashMap<>();
	// Self-owned items seen on recent ticks, awaiting a kill to confirm them as loot.
	// LootManager posts the kill's ServerNpcLoot after the tick's ItemSpawned events
	// (usually from its own GameTick), so the spawn can't decide; reconcileKillLoot()
	// matches them up at GameTick.
	private final Map<TileItem, GroundLoot> pendingSelf = new IdentityHashMap<>();
	private final List<UntakenItem> untakenBatch = new ArrayList<>();
	// Stacks that were tracked when the scene unloaded. Only these can come back on
	// a new object; anything else spawning is a fresh drop, even where it is the
	// same thing on the same tile as one we already hold.
	private final java.util.Set<TileItem> unloaded =
		java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	// The kills of the last few ticks, each carrying the source name stamped onto the
	// loot it produced so the Uncollected ledger can say where things were left.
	private final List<RecentKill> recentKills = new ArrayList<>();
	// The NPC deaths of the last few ticks, ours and other players' alike, each with
	// the tiles the NPC stood on. A ground item carries a tile and a tick but no
	// kill, so a stack is matched to its kill by tile first (deathFor) and by tick
	// alone only when no death of an armed source stood near it. The memory is
	// longer than KILL_ARM_TICKS because the death precedes the loot script by the
	// length of the death animation.
	private static final int DEATH_MEMORY_TICKS = 10;
	private final List<RecentDeath> recentDeaths = new ArrayList<>();
	// The player's own "Drop" clicks of the last couple of ticks. The game confirms a
	// drop with a self-owned spawn at the player's feet, which by tick alone looks
	// like kill loot; each click is spent by the first spawn it explains. A drop that
	// waits on the valuable-item warning is confirmed from that dialog, not by a Drop
	// click, so it is not remembered here.
	private static final int DROP_WINDOW_TICKS = 2;
	private final List<RecentDrop> recentDrops = new ArrayList<>();

	private static final class RecentDrop
	{
		private final int id;
		private final int tick;
		// where the player stood at the click: the drop lands there, and a running
		// player has moved on by the time the spawn is read
		private final WorldPoint at;

		private RecentDrop(int id, int tick, WorldPoint at)
		{
			this.id = id;
			this.tick = tick;
			this.at = at;
		}
	}

	// a kill of ours, remembered long enough for its ground items to find it.
	private static final class RecentKill
	{
		private final int tick;
		private final String source;

		private RecentKill(int tick, String source)
		{
			this.tick = tick;
			this.source = source;
		}
	}

	// an NPC death seen in scene, ours or anyone's, remembered long enough for a
	// stack to find the kill it fell from by tile. Index and tick together identify
	// the NPC that died; an index alone is reused once the NPC despawns.
	private static final class RecentDeath
	{
		private final int tick;
		private final int index;
		private final String name;
		private final WorldArea footprint;   // the tiles the NPC occupied at death

		private RecentDeath(int tick, int index, String name, WorldArea footprint)
		{
			this.tick = tick;
			this.index = index;
			this.name = name;
			this.footprint = footprint;
		}
	}

	private static final class UntakenItem
	{
		private final int id;
		private final int qty;
		private final String source;
		// the account that earned it. The batch is gathered before a logout and sent
		// after the next login, which may belong to somebody else.
		private final String owner;
		// the kill it fell from, so the flush can count how many kills left
		// something: the tick of the kill, plus the index of the NPC that died when
		// the stack was matched to its death by tile; -1 each when unknown
		private final int killTick;
		private final int killIndex;

		private UntakenItem(int id, int qty, String source, String owner, int killTick,
			int killIndex)
		{
			this.id = id;
			this.qty = qty;
			this.source = source;
			this.owner = owner;
			this.killTick = killTick;
			this.killIndex = killIndex;
		}
	}

	private static final class GroundLoot
	{
		private final int id;
		private final int qty;
		private final int despawnTick;
		private final int spawnTick;
		private final boolean group;   // group-ironman ownership rather than our own
		private final String owner;    // the account it spawned for
		private final WorldPoint at;   // the tile it spawned on; null when unreadable
		private final String source;   // the kill it belongs to; null until promoted
		private final int killTick;    // that kill's tick; -1 until promoted
		private final int killIndex;   // the dead NPC's index when matched by tile; -1 otherwise

		private GroundLoot(int id, int qty, int despawnTick, int spawnTick,
			boolean group, String owner, WorldPoint at)
		{
			this(id, qty, despawnTick, spawnTick, group, owner, at, null, -1, -1);
		}

		private GroundLoot(int id, int qty, int despawnTick, int spawnTick,
			boolean group, String owner, WorldPoint at, String source, int killTick,
			int killIndex)
		{
			this.id = id;
			this.qty = qty;
			this.despawnTick = despawnTick;
			this.spawnTick = spawnTick;
			this.group = group;
			this.owner = owner;
			this.at = at;
			this.source = source;
			this.killTick = killTick;
			this.killIndex = killIndex;
		}

		// the same stack, stamped with the kill it fell from: its source name and
		// its identity, the kill's tick and the dead NPC's index (-1 when only the
		// tick placed it)
		private GroundLoot withKill(String source, int killTick, int killIndex)
		{
			return new GroundLoot(id, qty, despawnTick, spawnTick, group, owner, at,
				source, killTick, killIndex);
		}
	}

	// A pet-drop message primes us; the pet NAME arrives on a following
	// collection-log / untradeable line a tick or two later. Windowed so a later
	// unrelated clog entry isn't mistaken for the pet.
	private int petPendingTicks = -1;
	// Slayer completion spans two lines ("You killed 150 X" + "You've completed N
	// tasks"); stash the task string until we emit.
	private String pendingSlayerTask;
	private String pendingSlayerMonster;
	private Integer pendingSlayerKills;
	// Ticks since a finished-task line armed a pending completion; -1 = idle. If the
	// streak line never finalises it within the window (reworded, missed, wrong chat
	// type), the finished line is itself a real completion and gets flushed. Disarmed
	// the moment the streak line processes; no double-emit.
	private int slayerPendingTicks = -1;
	// The task name seen at KILL time, via the loot stamp. RuneLite clears getTask()
	// on the completing tick, so by the streak line the live service is empty.
	private String lastSlayerTask;
	// The last completion emitted and when, wall clock. The finishing kill's loot
	// lands after that clear, so it finds no live task; within the grace it is
	// stamped with the task just completed instead (SLAYER_FINAL_KILL_GRACE_MS).
	private String lastSlayerCompletionTask;
	private long lastSlayerCompletionAtMs = -1;

	// How long after a completion a stamp-less kill of its monster is that task's
	// finishing kill. Mirrors LocalStore.SLAYER_FINAL_KILL_GRACE.
	static final long SLAYER_FINAL_KILL_GRACE_MS = LocalStore.SLAYER_FINAL_KILL_GRACE * 1000L;

	@Inject
	ChronicleEventCapture(Client client, ClientThread clientThread, ConfigManager configManager,
		ChronicleConfig config, ChronicleApiClient api, LocalStore localStore)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.config = config;
		this.api = api;
		this.localStore = localStore;
	}

	// false means the core Slayer plugin's service never bound, so on-task drop
	// tagging is inactive.
	boolean hasSlayerService()
	{
		return slayerService != null;
	}

	// clear per-login state so nothing from the last session leaks into this one.
	void reset()
	{
		knownLevels.clear();
		pendingLevels.clear();
		recentKc.clear();
		lastKillTimeTick = -1;
		lastAttackerName = null;
		lastAttackerTick = -1;
		pickpocketTick = -1;
		groupStorageOpen = false;
		groupStorageBaseline = null;
		groupStorageCurrent = null;
		petPendingTicks = -1;
		pendingSlayerTask = null;
		pendingSlayerMonster = null;
		pendingSlayerKills = null;
		slayerPendingTicks = -1;
		lastSlayerTask = null;
		lastSlayerCompletionTask = null;
		lastSlayerCompletionAtMs = -1;
		// Ground-item refs belong to the scene we're leaving, so drop them. The
		// untaken batch is kept: each item carries the account it was earned on, and
		// flushUntakenLoot only sends the ones belonging to whoever logs in next.
		groundLoot.clear();
		pendingSelf.clear();
		recentKills.clear();
		recentDeaths.clear();
		recentDrops.clear();
	}

	// Emit left-behind loot as LOOT_UNTAKEN, one event per SOURCE so the Uncollected
	// ledger can say where things were left, each carrying "kills", how many kills
	// of that source left at least one of its stacks. Items swept up at a logout
	// only go out on the next login's ticks, and that login can belong to a
	// different player, so anything stamped with another account is discarded. It
	// also waits for the journal to be mounted: otherwise LocalStore.record() drops
	// the batch while the cloud push still takes it, and the two ledgers disagree
	// about the same kill.
	private void flushUntakenLoot()
	{
		if (untakenBatch.isEmpty())
		{
			return;
		}
		String owner = localName();
		if (owner == null || !localStore.isReadyFor(owner))
		{
			return;   // nobody to attribute it to yet; the batch keeps
		}
		Map<String, JsonArray> bySource = new HashMap<>();
		// the distinct kills each source's stacks fell from, by kill identity
		Map<String, Set<String>> killsBySource = new HashMap<>();
		for (UntakenItem it : untakenBatch)
		{
			if (!owner.equals(it.owner))
			{
				continue;   // left behind by the account before this one
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", it.id);
			o.addProperty("quantity", it.qty);
			String src = it.source == null ? "" : it.source;
			bySource.computeIfAbsent(src, k -> new JsonArray()).add(o);
			if (it.killTick >= 0)
			{
				killsBySource.computeIfAbsent(src, k -> new HashSet<>())
					.add(it.killIndex + "@" + it.killTick);
			}
		}
		untakenBatch.clear();
		for (Map.Entry<String, JsonArray> e : bySource.entrySet())
		{
			JsonObject data = new JsonObject();
			if (!e.getKey().isEmpty())
			{
				data.addProperty("source", e.getKey());
			}
			data.add("items", e.getValue());
			// The kills that left something: the distinct kill identities among the
			// source's stacks, however many stacks each left. A stack matched to its
			// kill by tile carries the dead NPC's index and tick, so two kills of one
			// source on the same tick (an AoE burst) count two; a stack only its tick
			// could place carries the kill tick alone, so such stacks from one tick
			// collapse to one kill; a stack whose kill is unknown counts none.
			Set<String> kills = killsBySource.get(e.getKey());
			data.addProperty("kills", kills == null ? 0 : kills.size());
			emit("LOOT_UNTAKEN", data);
		}
	}

	/**
	 * A stack already tracked at this place, whatever object now carries it. Matched
	 * on tile, id and quantity together: a stack that changed size is a different
	 * stack, and two of the same thing in the same place at the same size are
	 * indistinguishable anyway.
	 */
	private TileItem trackedStack(WorldPoint where, int id, int qty)
	{
		if (where == null)
		{
			return null;
		}
		for (TileItem key : unloaded)
		{
			GroundLoot g = groundLoot.get(key);
			if (g != null && g.id == id && g.qty == qty && where.equals(g.at))
			{
				return key;
			}
		}
		return null;
	}

	/**
	 * Bank anything that passed its own despawn tick without us ever seeing it
	 * despawn. Walking far enough from a stack unloads it with the scene and no
	 * despawn is posted, so without this the item would neither be counted nor
	 * ever released -- the record would quietly stop seeing loot walked away from,
	 * and the map would grow for as long as the client ran.
	 *
	 * <p>The grace is there because the despawn usually does arrive, and arriving
	 * a tick late should be read as the despawn it is rather than raced by this.
	 */
	private void sweepTimedOutLoot()
	{
		if (groundLoot.isEmpty())
		{
			return;
		}
		int now = client.getTickCount();
		List<TileItem> gone = new ArrayList<>();
		for (Map.Entry<TileItem, GroundLoot> e : groundLoot.entrySet())
		{
			GroundLoot g = e.getValue();
			if (g.despawnTick > 0 && now > g.despawnTick + TIMEOUT_GRACE)
			{
				untakenBatch.add(new UntakenItem(g.id, g.qty, g.source, g.owner, g.killTick,
					g.killIndex));
				gone.add(e.getKey());
			}
		}
		for (TileItem it : gone)
		{
			groundLoot.remove(it);
			unloaded.remove(it);
		}
	}

	// ticks past a stack's own despawn before we conclude nobody is going to tell us
	private static final int TIMEOUT_GRACE = 5;

	// Promote buffered self-owned spawns to tracked kill loot when they landed within
	// KILL_ARM_TICKS of a kill. Runs at GameTick and keeps a spawn pending for that
	// window, so the later-firing ServerNpcLoot (posted by LootManager, usually from
	// its GameTick) has landed by the time it is judged. A spawn that never sits near a
	// kill is a manual drop and is discarded.
	private void reconcileKillLoot()
	{
		if (pendingSelf.isEmpty())
		{
			return;
		}
		int now = client.getTickCount();
		List<TileItem> done = new ArrayList<>();
		for (Map.Entry<TileItem, GroundLoot> e : pendingSelf.entrySet())
		{
			GroundLoot g = e.getValue();
			RecentKill kill = killFor(g);
			if (kill != null)
			{
				// confirmed kill loot: the death that stood on its tile names the
				// kill exactly, else the tick rule's kill
				RecentDeath death = deathFor(g);
				groundLoot.put(e.getKey(), death != null
					? g.withKill(death.name, death.tick, death.index)
					: g.withKill(kill.source, kill.tick, -1));
				done.add(e.getKey());
			}
			else if (now - g.spawnTick > KILL_ARM_TICKS)
			{
				done.add(e.getKey());   // no kill nearby: a manual drop
			}
		}
		for (TileItem t : done)
		{
			pendingSelf.remove(t);
		}
	}

	// The kill a buffered spawn belongs to: the latest one it could have followed, so a
	// burst of kills files each pile under the monster that dropped it. A GROUP-owned
	// spawn has to match a kill on its OWN tick, because a team-mate's drops arrive
	// group-owned too and the wider window would book theirs into our ledger.
	private RecentKill killFor(GroundLoot g)
	{
		int window = g.group ? 0 : KILL_ARM_TICKS;
		RecentKill best = null;
		for (RecentKill k : recentKills)
		{
			int since = g.spawnTick - k.tick;
			if (since < 0 || since > window)
			{
				continue;
			}
			if (best == null || k.tick > best.tick)
			{
				best = k;
			}
		}
		return best;
	}

	// The death a buffered spawn fell from, by tile: a recent death of a source that
	// armKill() saw within the spawn's window, whose footprint holds the spawn tile
	// or, for the NPCs LootManager knows to drop a tile off where they stood, lies
	// one tile from it. A death standing on the tile beats one beside it, and the
	// latest wins among equals. Null when the tile is unreadable or no such death is
	// near it, and the tick rule decides. Other players' kills die in scene too;
	// the source gate keeps them out.
	private RecentDeath deathFor(GroundLoot g)
	{
		if (g.at == null || recentDeaths.isEmpty())
		{
			return null;
		}
		int window = g.group ? 0 : KILL_ARM_TICKS;
		RecentDeath best = null;
		int bestDistance = 0;
		for (RecentDeath d : recentDeaths)
		{
			int since = g.spawnTick - d.tick;
			if (since < 0 || since > DEATH_MEMORY_TICKS || !armedInWindow(d.name, g.spawnTick, window))
			{
				continue;
			}
			int distance = d.footprint.distanceTo(g.at);
			if (distance > 1)
			{
				continue;
			}
			if (best == null || distance < bestDistance
				|| (distance == bestDistance && d.tick > best.tick))
			{
				best = d;
				bestDistance = distance;
			}
		}
		return best;
	}

	// whether a kill of this source was armed within window ticks before the spawn
	private boolean armedInWindow(String source, int spawnTick, int window)
	{
		for (RecentKill k : recentKills)
		{
			int since = spawnTick - k.tick;
			if (since >= 0 && since <= window && source.equals(k.source))
			{
				return true;
			}
		}
		return false;
	}

	// arm untaken tracking: ground items spawning around now are this kill's.
	private void armKill(String source)
	{
		int now = client.getTickCount();
		recentKills.removeIf(k -> now - k.tick > KILL_ARM_TICKS);
		recentKills.add(new RecentKill(now, source));
	}

	// ── LOOT ──────────────────────────────────────────────────────────────

	// The exact per-kill drop from the game's loottracker_add_loot script, and the only
	// NPC loot event this plugin listens to. Emits straight away. The NPC comes from
	// the composition because the actor has usually despawned by now.
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (client.getTickCount() == pickpocketTick)
		{
			return;   // the game posts NPC loot for a pickpocket too; not a kill
		}
		NPCComposition comp = event.getComposition();
		if (comp == null)
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("source", comp.getName());
		data.addProperty("npcId", comp.getId());
		data.addProperty("category", "NPC");
		data.addProperty("lootSource", "server");   // the site protects rows carrying it
		Integer kc = recentKc.get(cleanKey(comp.getName()));
		if (kc != null)
		{
			data.addProperty("killCount", kc);
		}
		attachKillTime(data);
		data.add("items", itemsToJson(event.getItems()));
		stampSlayer(data, comp.getName(), comp.getId());
		emit("LOOT", data);
		armKill(comp.getName());
	}

	// Only "Drop" on an inventory item puts an item on the ground (Destroy and Release
	// do not), so that option alone is remembered.
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!event.isItemOp() || !"Drop".equals(event.getMenuOption()) || event.getItemId() <= 0)
		{
			return;
		}
		int now = client.getTickCount();
		Player me = client.getLocalPlayer();
		recentDrops.removeIf(d -> now - d.tick > DROP_WINDOW_TICKS);
		recentDrops.add(new RecentDrop(event.getItemId(), now,
			me == null ? null : me.getWorldLocation()));
	}

	// Whether a self-owned spawn is the ground copy of a recent "Drop" click: the same
	// item id within DROP_WINDOW_TICKS of the click, on the tile the player stands on
	// now or stood on at the click, when the positions can be read. When they can't,
	// id and tick decide, since missing a real drop is the cheaper error. Spends the
	// click it matches.
	private boolean isOwnDrop(TileItem it, Tile tile, int now)
	{
		if (recentDrops.isEmpty())
		{
			return false;
		}
		WorldPoint at = tile == null ? null : tile.getWorldLocation();
		Player me = client.getLocalPlayer();
		WorldPoint mine = me == null ? null : me.getWorldLocation();
		for (int i = 0; i < recentDrops.size(); i++)
		{
			RecentDrop d = recentDrops.get(i);
			if (d.id != it.getId() || now < d.tick || now - d.tick > DROP_WINDOW_TICKS)
			{
				continue;
			}
			if (at != null && mine != null && !at.equals(mine) && !at.equals(d.at))
			{
				continue;   // not at our feet, so a kill's
			}
			recentDrops.remove(i);
			return true;
		}
		return false;
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		// Only the local player's own kill loot, dropped within a few ticks of a kill,
		// so manual drops and other players' loot are ignored. GROUP ownership counts
		// alongside SELF because a group ironman's own drops arrive GROUP-stamped
		// (upstream GroundItemsPlugin accepts both as well); killFor() then holds a
		// group-owned spawn to the tick of a kill of ours so a team-mate's drops stay
		// out of the ledger.
		TileItem it = event.getItem();
		if (it == null)
		{
			return;
		}
		boolean group = it.getOwnership() == TileItem.OWNERSHIP_GROUP;
		if (!group && it.getOwnership() != SELF_OWNED)
		{
			return;
		}
		int now = client.getTickCount();
		// Already ours and already tracked: this is the scene reload re-announcing
		// what survived it, not a second drop. Re-buffering it would enter it as
		// fresh kill loot and count it twice.
		if (groundLoot.containsKey(it))
		{
			return;
		}
		// Tracked, but on an object that no longer exists. A reload in place keeps
		// the same TileItems; leaving the area entirely destroys them, and coming
		// back rebuilds the stack as a NEW object, so identity cannot carry across a
		// round trip. It is the same stack if it is the same thing in the same
		// place, so move the tracking onto the new object rather than entering it
		// as a fresh drop: otherwise returning for loot would read as abandoning it,
		// and the sweep would bank it the moment its time was up.
		Tile back = event.getTile();
		TileItem prior = trackedStack(back == null ? null : back.getWorldLocation(),
			it.getId(), it.getQuantity());
		if (prior != null)
		{
			groundLoot.put(it, groundLoot.remove(prior));
			unloaded.remove(prior);
			return;
		}
		if (isOwnDrop(it, event.getTile(), now))
		{
			return;   // the game confirming a Drop click, not kill loot
		}
		// Buffer only: the kill's ServerNpcLoot fires AFTER this (LootManager posts it
		// later in the tick), so kill loot and a manual drop are still indistinguishable.
		// reconcileKillLoot() decides at GameTick. The account is read here while we're
		// certainly logged in as it, since left-behind items go out after the next
		// login, which may be another's.
		Tile tile = event.getTile();
		pendingSelf.put(it, new GroundLoot(it.getId(), it.getQuantity(),
			it.getDespawnTime(), now, group, localName(),
			tile == null ? null : tile.getWorldLocation()));
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		pendingSelf.remove(event.getItem());   // may despawn before reconcile runs
		GroundLoot g = groundLoot.remove(event.getItem());
		unloaded.remove(event.getItem());
		if (g == null)
		{
			return;
		}
		int now = client.getTickCount();
		// Reaching the scheduled despawn tick means it timed out on the ground, so it
		// was left behind. An earlier despawn is a pickup: yours while it was still
		// private, or someone else's once it went public.
		boolean left = g.despawnTick > 0 && now >= g.despawnTick - 1;
		if (left)
		{
			untakenBatch.add(new UntakenItem(g.id, g.qty, g.source, g.owner, g.killTick,
				g.killIndex));
		}
	}

	// the live slayer task for the panel's Home card; null when there's none.
	SlayerView slayerView()
	{
		if (slayerService == null)
		{
			return null;
		}
		try
		{
			String task = slayerService.getTask();
			if (task == null || task.isEmpty())
			{
				return null;
			}
			return new SlayerView(task, slayerService.getRemainingAmount(),
				slayerService.getInitialAmount());
		}
		catch (RuntimeException ignored)
		{
			return null;   // no service, no task
		}
	}

	// immutable slayer-task snapshot for the panel.
	static final class SlayerView
	{
		final String task;
		final int remaining;
		final int initial;

		SlayerView(String task, int remaining, int initial)
		{
			this.task = task;
			this.remaining = remaining;
			this.initial = initial;
		}
	}

	// Tag an NPC-loot event with the slayer task live at the moment of the kill, but
	// only when the killed NPC counts toward it (SlayerTaskBook: id first, name
	// second), so on-task loot is settled at capture and a Man killed mid-task is
	// not a task kill. Any kill during a task still marks the session as a slayer's
	// and remembers the task's identity, whether or not this one was on-task.
	void stampSlayer(JsonObject data, String npcName, int npcId)
	{
		if (slayerService == null)
		{
			return;
		}
		try
		{
			String task = slayerService.getTask();
			if (task == null || task.isEmpty())
			{
				// No live task: the completing kill's loot, if a completion of its
				// monster was just seen (or its finished line is still pending the
				// streak line). It carries the task alone, no counter: the counter was
				// cleared with the task.
				String done = finishingKillTask();
				if (done != null && SlayerTaskBook.onTask(npcName, npcId, done))
				{
					data.addProperty("slayerTask", done);
				}
				return;
			}
			lastSlayerTask = task;   // the identity the completion streak line falls back to
			slayerSeenThisSession = true;
			if (!SlayerTaskBook.onTask(npcName, npcId, task))
			{
				return;
			}
			data.addProperty("slayerTask", task);
			data.addProperty("slayerTaskRemaining", slayerService.getRemainingAmount());
			data.addProperty("slayerTaskInitial", slayerService.getInitialAmount());
			String loc = slayerService.getTaskLocation();
			if (loc != null && !loc.isEmpty())
			{
				data.addProperty("slayerTaskLocation", loc);
			}
		}
		catch (RuntimeException ignored)
		{
			// no service, no stamp
		}
	}

	// The task a stamp-less kill landing now would be the finishing kill of: the
	// finished line's creature while its completion is still pending, else the last
	// completion emitted within the grace. Null when there is none.
	private String finishingKillTask()
	{
		if (slayerPendingTicks >= 0 && pendingSlayerMonster != null && !pendingSlayerMonster.isEmpty())
		{
			return pendingSlayerMonster;
		}
		if (lastSlayerCompletionTask != null && lastSlayerCompletionAtMs >= 0
			&& System.currentTimeMillis() - lastSlayerCompletionAtMs <= SLAYER_FINAL_KILL_GRACE_MS)
		{
			return lastSlayerCompletionTask;
		}
		return null;
	}

	// A completion goes out through here so the finishing kill's loot, which lands
	// after the live task was cleared, can still be stamped with it.
	private void emitSlayerCompletion(JsonObject data)
	{
		lastSlayerCompletionTask = data.get("task").getAsString();
		lastSlayerCompletionAtMs = System.currentTimeMillis();
		emit("SLAYER", data);
	}

	// True once a kill landed while a slayer task was live this session, on-task or
	// not. Home's slayer card gates on it so non-slayers never see it. Cleared at the
	// account boundary.
	private volatile boolean slayerSeenThisSession;

	boolean slayerSeenThisSession()
	{
		return slayerSeenThisSession;
	}

	void resetSessionFlags()
	{
		slayerSeenThisSession = false;
	}

	// null when the core Slayer plugin is off or there's no task.
	private String slayerTaskFromService()
	{
		if (slayerService == null)
		{
			return null;
		}
		try
		{
			String t = slayerService.getTask();
			return (t == null || t.isEmpty()) ? null : t;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// NPC loot arrives via onServerNpcLoot and would double-count here. PLAYER
		// loot is dropped outright: on a PK the record carries the victim's display
		// name and their inventory, and this plugin only ever records its own account.
		if (event.getType() == LootRecordType.NPC || event.getType() == LootRecordType.PLAYER)
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("source", event.getName());
		data.addProperty("category", event.getType() != null ? event.getType().name() : "EVENT");
		Integer kc = recentKc.get(cleanKey(event.getName()));
		if (kc != null)
		{
			data.addProperty("killCount", kc);
		}
		attachKillTime(data);
		data.add("items", itemsToJson(event.getItems()));
		emit("LOOT", data);
	}

	// Annotate a kill's loot with the boss-timer chat seen moments before. Pairing is
	// tick adjacency, since the timer line never names the boss, and it's one-shot: a
	// raid chest opened long after its timer printed goes unannotated.
	private void attachKillTime(JsonObject data)
	{
		if (lastKillTimeTick < 0 || client.getTickCount() - lastKillTimeTick > KILL_TIME_PAIR_TICKS)
		{
			return;
		}
		data.addProperty("killTime", lastKillTimeSec);
		data.addProperty("personalBest", lastKillPb);
		if (lastPbTimeSec >= 0)
		{
			data.addProperty("personalBestTime", lastPbTimeSec);
		}
		lastKillTimeTick = -1;
	}

	// "1:26.40" / "36:04" / "1:01:53.40" -> seconds. The regex guarantees the digits.
	static double parseDuration(String text)
	{
		double sec = 0;
		for (String part : text.split(":"))
		{
			sec = sec * 60 + Double.parseDouble(part);
		}
		return sec;
	}

	// ── QUEST / GROUP STORAGE ─────────────────────────────────────────────

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.SHARED_BANK)
		{
			// The baseline comes from the first server sync of the temp container
			// (onItemContainerChanged); it isn't populated yet at widget load.
			groupStorageOpen = true;
			groupStorageBaseline = null;
			groupStorageCurrent = null;
			return;
		}
		if (event.getGroupId() != InterfaceID.QUESTSCROLL)
		{
			return;
		}
		emitQuestFromScroll();
	}

	// the game commits the session's edits on close, so that's when the diff is real.
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.SHARED_BANK && groupStorageOpen)
		{
			flushGroupStorage();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!groupStorageOpen || event.getContainerId() != InventoryID.INV_GROUP_TEMP)
		{
			return;
		}
		Map<Integer, Integer> counts = containerCounts(event.getItemContainer());
		if (groupStorageBaseline == null)
		{
			groupStorageBaseline = counts;   // the opening server sync
		}
		groupStorageCurrent = counts;
	}

	// diff baseline -> final, one event carrying deposits and withdrawals.
	private void flushGroupStorage()
	{
		Map<Integer, Integer> base = groupStorageBaseline;
		Map<Integer, Integer> last = groupStorageCurrent;
		groupStorageOpen = false;
		groupStorageBaseline = null;
		groupStorageCurrent = null;
		if (base == null || last == null)
		{
			return;   // the container never synced; nothing observed
		}
		JsonArray deposits = new JsonArray();
		JsonArray withdrawals = new JsonArray();
		Set<Integer> ids = new HashSet<>(base.keySet());
		ids.addAll(last.keySet());
		for (int id : ids)
		{
			int delta = last.getOrDefault(id, 0) - base.getOrDefault(id, 0);
			if (delta == 0)
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("quantity", Math.abs(delta));
			(delta > 0 ? deposits : withdrawals).add(o);
		}
		if (deposits.size() == 0 && withdrawals.size() == 0)
		{
			return;   // opened, looked, closed
		}
		JsonObject data = new JsonObject();
		data.add("deposits", deposits);
		data.add("withdrawals", withdrawals);
		emit("GROUP_STORAGE", data);
	}

	// id -> total quantity, stacks merged, empty slots skipped.
	private static Map<Integer, Integer> containerCounts(ItemContainer container)
	{
		Map<Integer, Integer> counts = new HashMap<>();
		if (container == null)
		{
			return counts;
		}
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
			{
				counts.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return counts;
	}

	private void emitQuestFromScroll()
	{
		// the title text populates a tick later; read it on the client thread.
		clientThread.invokeLater(() ->
		{
			Widget title = client.getWidget(InterfaceID.Questscroll.QUEST_TITLE);
			if (title == null)
			{
				return;
			}
			String text = title.getText();
			text = text == null ? "" : Text.removeTags(text).trim();
			if (text.isEmpty())
			{
				return;
			}
			JsonObject data = new JsonObject();
			data.addProperty("questName", text);
			emit("QUEST", data);
		});
	}

	// ── LEVEL ─────────────────────────────────────────────────────────────

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (skill == null)
		{
			return;
		}
		int level = event.getLevel();
		Integer prev = knownLevels.put(skill, level);
		// Logging in replays every skill as a StatChanged, which against an empty map
		// reads as 23 simultaneous level-ups. So a level-up needs a previous reading
		// that could only have come from live play: one we've actually seen, and one
		// above zero, since the login pass reports zero for a skill the client hasn't
		// filled in yet.
		if (prev != null && prev > 0 && level > prev && level <= MAX_LEVEL)
		{
			pendingLevels.add(skill);
		}
	}

	// ── DEATH ─────────────────────────────────────────────────────────────

	// an NPC "interacts" with its combat target, so remember the last one on us.
	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (!(event.getSource() instanceof NPC) || event.getTarget() == null
			|| event.getTarget() != client.getLocalPlayer())
		{
			return;
		}
		String name = ((NPC) event.getSource()).getName();
		if (name != null && !name.isEmpty())
		{
			lastAttackerName = name;
			lastAttackerTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (actor instanceof NPC)
		{
			rememberDeath((NPC) actor);
			return;
		}
		Player lp = client.getLocalPlayer();
		if (lp == null || actor != lp)
		{
			return;   // only our own death
		}
		JsonObject data = new JsonObject();
		try
		{
			data.addProperty("regionId", lp.getWorldLocation().getRegionID());
		}
		catch (RuntimeException ignored)
		{
			// no world location, leave it off
		}
		String killer = findKillerNpc(lp);
		if (killer != null)
		{
			data.addProperty("killerName", killer);
		}
		emit("DEATH", data);
	}

	// Every NPC death in scene, whoever's kill, with the tiles the NPC stood on, so
	// a stack can be matched to the kill it fell from by tile (deathFor). A death
	// whose name or tiles cannot be read is not kept; the tick rule still applies.
	private void rememberDeath(NPC npc)
	{
		int now = client.getTickCount();
		recentDeaths.removeIf(d -> now - d.tick > DEATH_MEMORY_TICKS);
		String name;
		WorldPoint at;
		int size;
		try
		{
			name = npc.getName();
			at = npc.getWorldLocation();
			NPCComposition comp = npc.getTransformedComposition();
			if (comp == null)
			{
				comp = npc.getComposition();
			}
			size = comp == null ? 1 : Math.max(1, comp.getSize());
		}
		catch (RuntimeException ignored)
		{
			return;   // nothing readable about it
		}
		if (name == null || name.isEmpty() || at == null)
		{
			return;
		}
		recentDeaths.add(new RecentDeath(now, npc.getIndex(), name,
			new WorldArea(at, size, size)));
	}

	// Best effort: an NPC still locked onto us at the death tick, else the last one
	// seen turning on us within ~30s, which covers poison and a lingering hit after
	// the attacker moved on. Null for an environmental death long after combat.
	private String findKillerNpc(Player lp)
	{
		try
		{
			for (NPC npc : client.getTopLevelWorldView().npcs())
			{
				if (npc != null && npc.getInteracting() == lp && npc.getName() != null
					&& !npc.getName().isEmpty())
				{
					return npc.getName();
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// no world view; fall through to the remembered attacker
		}
		if (lastAttackerName != null && lastAttackerTick >= 0
			&& client.getTickCount() - lastAttackerTick <= ATTACKER_MEMORY_TICKS)
		{
			return lastAttackerName;
		}
		return null;
	}

	// ── CHAT: kc / collection / clue / combat-achievement / slayer / pet /
	//         diary ────────────────────────────────────────────────────────

	@Subscribe
	public void onChatMessage(ChatMessage message)
	{
		ChatMessageType t = message.getType();
		// Every line the game prints reaches here, public and clan chat included, and a
		// crowded world delivers hundreds a minute. Settle the type before paying for
		// the tag strip.
		if (t != ChatMessageType.MESBOX && t != ChatMessageType.GAMEMESSAGE
			&& t != ChatMessageType.SPAM)
		{
			return;
		}
		String msg = Text.removeTags(message.getMessage());

		// A pickpocket line only marks its tick, for onServerNpcLoot. The PICKPOCKET
		// entry itself arrives from the Loot Tracker through onLootReceived.
		if (PICKPOCKET.matcher(msg).matches())
		{
			pickpocketTick = client.getTickCount();
			return;
		}

		// Diary completion is usually a MESBOX line, but the same congratulatory line
		// can arrive as a plain GAMEMESSAGE, so check both.
		Matcher d = DIARY_COMPLETION.matcher(msg);
		if (d.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("area", d.group("region").trim());
			data.addProperty("difficulty", d.group("grade").trim().toUpperCase(java.util.Locale.ROOT));
			emit("DIARY", data);
			return;
		}
		if (t == ChatMessageType.MESBOX)
		{
			return;   // MESBOX only ever carries the diary line handled above
		}

		// Kill count: annotates the next loot event for this source.
		Matcher kc = KILL_COUNT.matcher(msg);
		if (kc.find())
		{
			try
			{
				String subject = Text.removeTags(kc.group("subject")).trim();
				int tally = Integer.parseInt(kc.group("tally").replace(",", ""));
				recentKc.put(cleanKey(subject), tally);
				// And into the journal on the spot. This line is the game stating its
				// own count, on the kill itself, with no interface opened and nothing
				// fetched -- but it used to live only in recentKc, which is read in
				// one place: to stamp the loot event that follows. A source whose
				// reward is not an NPC drop never gets that event, so Wintertodt,
				// Tempoross and the Gauntlet had their true count announced and
				// discarded on every single kill, leaving them on a Kill Log reading
				// that only moves when the player opens an interface.
				String kind = kc.group("kind");
				if (kind == null || !NOT_A_KILL.contains(kind))
				{
					String owner = localName();
					if (owner != null && localStore.isReadyFor(owner))
					{
						localStore.noteKillCount(subject, tally, owner);
					}
				}
			}
			catch (NumberFormatException ignored)
			{
				// non-numeric, skip it
			}
			return;
		}

		// Boss timer: annotates the next loot event, as the kill count does.
		Matcher dur = KILL_DURATION.matcher(msg);
		if (dur.find())
		{
			lastKillTimeSec = parseDuration(dur.group("time"));
			lastKillPb = msg.contains(NEW_PB_MARK);
			// "(new personal best)" means this kill is the record; otherwise the game
			// may restate the standing record after the time.
			Matcher pb = PERSONAL_BEST.matcher(msg);
			lastPbTimeSec = lastKillPb ? lastKillTimeSec
				: (pb.find() ? parseDuration(pb.group("pb")) : -1);
			lastKillTimeTick = client.getTickCount();
			return;
		}

		// Collection log new item (also resolves a pending pet's name).
		Matcher col = COLLECTION_ITEM.matcher(msg);
		if (col.find())
		{
			String item = col.group("entry").trim();
			JsonObject data = new JsonObject();
			data.addProperty("itemName", item);
			emit("COLLECTION", data);
			if (petPendingTicks >= 0)
			{
				emitPet(item);
			}
			return;
		}

		// an untradeable drop can also carry the pet name after a pet prime.
		Matcher unt = UNTRADEABLE_DROP.matcher(msg);
		if (unt.find() && petPendingTicks >= 0)
		{
			emitPet(unt.group("dropped").trim());
			return;
		}

		Matcher ca = COMBAT_TASK.matcher(msg);
		if (ca.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("tier", ca.group("grade").trim().toUpperCase(java.util.Locale.ROOT));
			data.addProperty("task", COMBAT_TASK_POINTS.matcher(ca.group("challenge").trim()).replaceAll(""));
			emit("COMBAT_ACHIEVEMENT", data);
			return;
		}

		// clue casket completion: tier + the running lifetime count.
		Matcher clue = CLUE_COMPLETION.matcher(msg);
		if (clue.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("clueType", clue.group("rank").trim().toUpperCase(java.util.Locale.ROOT));
			try
			{
				data.addProperty("clueCount", Integer.parseInt(clue.group("tally").replace(",", "")));
			}
			catch (NumberFormatException ignored)
			{
				// leave count off
			}
			emit("CLUE", data);
			return;
		}

		// Slayer: the finished line stashes the task and arms the flush; the streak
		// line that follows finalises it and disarms. Only that order is handled: a
		// finished line arriving after its streak line flushes a second completion.
		Matcher sk = SLAYER_FINISHED.matcher(msg);
		if (sk.find())
		{
			pendingSlayerMonster = sk.group("creature").trim();
			pendingSlayerTask = sk.group("slain").trim() + " " + pendingSlayerMonster;
			try
			{
				pendingSlayerKills = Integer.parseInt(sk.group("slain").replace(",", ""));
			}
			catch (NumberFormatException ignored)
			{
				pendingSlayerKills = null;
			}
			slayerPendingTicks = 0;   // arm the flush; the streak line normally disarms it
			return;
		}
		Matcher sd = SLAYER_TOTAL.matcher(msg);
		if (sd.find())
		{
			// The streak line always prints on completion, so it's the trigger. Task
			// identity comes from the finished line's creature if we caught it, else
			// from the fallbacks below, so a missing or reworded finished line doesn't
			// drop the completion.
			String task = pendingSlayerMonster;
			if (task == null || task.isEmpty())
			{
				task = slayerTaskFromService();   // usually empty: cleared this tick
			}
			if (task == null || task.isEmpty())
			{
				task = lastSlayerTask;   // captured at kill time, before the clear
			}
			if (task == null || task.isEmpty())
			{
				task = pendingSlayerTask;   // last resort: the "N creature" blob
			}
			if (task != null && !task.isEmpty())
			{
				JsonObject data = new JsonObject();
				data.addProperty("task", task);
				data.addProperty("monster", task);
				// "You killed N <creature>" is the true size of the task. The loot
				// spine's own count undershoots when an opening kill went uncaptured
				// at the task hand-off.
				if (pendingSlayerKills != null)
				{
					data.addProperty("killCount", pendingSlayerKills);
				}
				// Only the plain "You've completed N tasks…" line carries the lifetime
				// number. A qualified one ("N Mortimer task", "N Wilderness tasks")
				// counts something narrower, so leave the badge off.
				if (sd.group("qual") == null)
				{
					try
					{
						data.addProperty("count", Integer.parseInt(sd.group("total").replace(",", "")));
					}
					catch (NumberFormatException ignored)
					{
						// leave count off
					}
				}
				emitSlayerCompletion(data);
			}
			else
			{
				log.debug("slayer streak line but no task identity, dropped: '{}'", msg);
			}
			pendingSlayerTask = null;
			pendingSlayerMonster = null;
			pendingSlayerKills = null;
			slayerPendingTicks = -1;   // the streak line handled it; disarm the flush
			lastSlayerTask = null;
			return;
		}

		// Pet drop prime; the name resolves on a following clog/untradeable line.
		if (PET_RECEIVED.matcher(msg).matches())
		{
			petPendingTicks = 0;
		}
	}

	private void emitPet(String petName)
	{
		petPendingTicks = -1;
		if (petName == null || petName.isEmpty())
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("petName", petName);
		emit("PET", data);
	}

	// The finished-task line was captured but the streak line never finalised it. That
	// line is a real completion, so emit it from the same identity fallbacks the
	// streak path uses, then clear every pending field (lastSlayerTask included) so a
	// late streak line finds nothing to re-emit.
	private void flushPendingSlayer()
	{
		String task = pendingSlayerMonster;
		if (task == null || task.isEmpty())
		{
			task = lastSlayerTask;
		}
		if (task == null || task.isEmpty())
		{
			task = pendingSlayerTask;
		}
		if (task != null && !task.isEmpty())
		{
			JsonObject data = new JsonObject();
			data.addProperty("task", task);
			data.addProperty("monster", task);
			if (pendingSlayerKills != null)
			{
				data.addProperty("killCount", pendingSlayerKills);
			}
			emitSlayerCompletion(data);   // no lifetime "count": the finished line has none
		}
		pendingSlayerTask = null;
		pendingSlayerMonster = null;
		pendingSlayerKills = null;
		lastSlayerTask = null;
	}

	// ── tick / state ──────────────────────────────────────────────────────

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		reconcileKillLoot();
		sweepTimedOutLoot();
		flushUntakenLoot();

		// expire a pet prime that never got a name.
		if (petPendingTicks >= 0 && ++petPendingTicks > 3)
		{
			petPendingTicks = -1;
		}

		// A finished-task line whose streak line never finalised it gets flushed after
		// a short window. Disarmed above when the streak line handled it.
		if (slayerPendingTicks >= 0 && ++slayerPendingTicks > 4)
		{
			flushPendingSlayer();
			slayerPendingTicks = -1;
		}

		if (pendingLevels.isEmpty())
		{
			return;
		}
		for (Skill skill : pendingLevels)
		{
			JsonObject data = new JsonObject();
			data.addProperty("skill", skill.getName());
			data.addProperty("level", knownLevels.getOrDefault(skill, 1));
			emit("LEVEL", data);
		}
		pendingLevels.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		// NOT on LOADING. A region change reloads the scene in place and the client
		// then re-announces every ground item that survived it -- same TileItem
		// objects, new scene base, no despawn in between. Banking here called that
		// "left behind" and dropped the tracking, so the pickup a second later had
		// nothing left to correct it: 23,591 coins were recorded as abandoned while
		// they sat in the inventory. Cameron's own client log, 2026-09-14:
		//
		//   01:59:04  Item spawn   995 (23591)   y=1984
		//   01:59:06  LOADING -> LOGGED_IN
		//   01:59:06  Item spawn   995 (23591)   y=7104   (the same item, replayed)
		//   01:59:07  Item despawn 995 (23591)            (the pickup)
		//
		// Keeping the entry costs nothing: the item is still tracked, and whether it
		// was taken or timed out is decided by the despawn, which is the one signal
		// that actually knows. A drop genuinely abandoned across a region change
		// still despawns on its own timer and is still counted then.
		//
		// A hop or a logout is different: the world goes away and no despawn is ever
		// coming, so those are still banked here.
		if (state == GameState.LOADING)
		{
			// It may come back on a different object, and only these may.
			unloaded.addAll(groundLoot.keySet());
		}
		if (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN)
		{
			for (GroundLoot g : groundLoot.values())
			{
				untakenBatch.add(new UntakenItem(g.id, g.qty, g.source, g.owner, g.killTick,
					g.killIndex));
			}
			groundLoot.clear();
			unloaded.clear();
		}
		if (state == GameState.LOGGING_IN || state == GameState.HOPPING || state == GameState.LOGIN_SCREEN)
		{
			reset();
		}
	}

	// ── helpers ───────────────────────────────────────────────────────────

	private JsonArray itemsToJson(Collection<ItemStack> items)
	{
		JsonArray arr = new JsonArray();
		if (items != null)
		{
			for (ItemStack is : items)
			{
				if (is == null)
				{
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("id", is.getId());
				o.addProperty("quantity", is.getQuantity());
				arr.add(o);
			}
		}
		return arr;
	}

	// lowercased, trailing parenthetical stripped, to line a kill-count line's subject
	// up with the NPC name.
	private static String cleanKey(String name)
	{
		if (name == null)
		{
			return "";
		}
		return Text.removeTags(name).replaceAll("\\s*\\(.+\\)$", "").trim().toLowerCase(java.util.Locale.ROOT);
	}

	// null while the name can't be read yet.
	private String localName()
	{
		Player lp = client.getLocalPlayer();
		String name = lp != null ? lp.getName() : null;
		return name == null || name.isEmpty() ? null : name;
	}

	private void emit(String type, JsonObject data)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String name = localName();
		if (name == null)
		{
			return;
		}
		// The journal always gets the event; the cloud push below is extra. Gating the
		// network on the opt-in as well as the token means a token left over from an
		// earlier install can't leak anything.
		localStore.record(type, data, name);
		if (!config.cloudSync() || config.serverBaseUrl().trim().isEmpty())
		{
			return;
		}
		String tokenRaw = configManager.getRSProfileConfiguration(
			ChroniclePlugin.GROUP, ChroniclePlugin.KEY_TOKEN);
		if (tokenRaw == null || tokenRaw.trim().isEmpty())
		{
			return;   // no token, nothing to push under
		}
		final String base = config.serverBaseUrl();
		final String token = tokenRaw.trim();
		final JsonObject body = new JsonObject();
		body.addProperty("playerName", name);
		// Stable account id, so an in-game rename still lands on the same account.
		// Sent as a string to dodge 64-bit JSON number precision.
		long accountHash = client.getAccountHash();
		// -1 means "no account"; any other value, negative ones included, is real.
		if (accountHash != -1L)
		{
			body.addProperty("accountHash", String.valueOf(accountHash));
		}
		body.addProperty("type", type);
		body.addProperty("eventId", UUID.randomUUID().toString());
		body.add("data", data);

		api.postEvent(base, token, body);
	}

}
