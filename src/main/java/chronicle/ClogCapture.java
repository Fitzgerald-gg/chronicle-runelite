/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Collection-log capture.
 *
 * <p>Opening the log from code counts as automation; this only ever reacts to the
 * player's own open. The completion fraction and the per-tab counts come from
 * login-synced varps and need no interface at all. On a real open we fire the log's
 * own "Search" op, which makes the game server transmit every entry: one open, the
 * whole log. Page-header scrapes on top of that pick up kill counts.
 *
 * <p>Every collection-log path checks the POH adventure-log varbit first: own account
 * only. The slayer kill log carries no such check because it has no shared view. It
 * opens from the Slayer rewards interface and shows nobody else's kills.
 */
@Singleton
@Slf4j
public class ClogCapture
{
	// Account-wide unique clog slots, synced on login without opening anything.
	private static final int VARP_CLOG_OBTAINED = 2943;
	private static final int VARP_CLOG_TOTAL = 2944;
	// Per-tab obtained/total, also login-synced varps.
	private static final String[] CAT_NAMES = {"bosses", "raids", "clues", "minigames", "other"};
	private static final int[][] VARP_CAT = {
		{4613, 4614}, {4615, 4616}, {4617, 4618}, {4619, 4620}, {4621, 4622},
	};
	// The kill/completion count in a "<label>: 1,234" header line.
	private static final Pattern COUNT_LINE = Pattern.compile(":\\s*([\\d,]+)\\s*$");
	// "Personal Best: 3:46", "Personal Best Corrupted: 13:11", and the hour shape
	// a long raid reaches. Tried BEFORE the count, because a count expression
	// reading the last ": number" on one of these lines comes back with the
	// seconds and calls them kills.
	private static final Pattern TIME_LINE = Pattern.compile(
		"^(?<label>.+?):\\s*(?<h>\\d+:)?(?<m>\\d{1,3}):(?<s>\\d{2})(?:\\.\\d+)?\\s*$");

	// The player's own open fires SETUP; we answer with the "Search" op and each
	// obtained item comes back as a TRANSMIT pre-fire carrying its id + quantity.
	private static final int COLLECTION_LOG_SETUP = 7797;
	private static final int COLLECTION_DELAYED_TRANSMIT = 4100;
	private static final int COLLECTION_INIT_SCRIPT = 2240;

	private final Client client;
	private final ItemManager itemManager;

	// byCat: page -> {item name: quantity}. kcs: page -> the first counted
	// line on its header, which is not always a kill count: Wintertodt's
	// counts rewards claimed, 1,078 where 447 were killed.
	private final Map<String, Map<String, Integer>> byCat = new HashMap<>();
	private final Map<String, Integer> kcs = new HashMap<>();
	// page -> {label: count}, every counter the page carries under the name the
	// log gives it. A number without its label cannot be told apart from a kill
	// count, and several of these are not one.
	private final Map<String, Map<String, Integer>> kcLines = new HashMap<>();
	// page -> label -> seconds. A best time, which is a time and not a count.
	private final Map<String, Map<String, Integer>> pbLines = new HashMap<>();
	// Species -> lifetime kills. The kill log is one scrollable list; one open
	// yields every monster.
	private final Map<String, Integer> slayerKcs = new HashMap<>();
	private int finished;
	private int available;

	// Enabled mid-session: no LOGGED_IN transition is coming. Read the varps now.
	void primeFromVarps(net.runelite.api.Client c)
	{
		int total = c.getVarpValue(VARP_CLOG_TOTAL);
		int obtained = c.getVarpValue(VARP_CLOG_OBTAINED);
		if (total > 0)
		{
			finished = obtained;
			available = total;
			dirty = true;
		}
		if (readCategoryCounts())
		{
			dirty = true;
		}
	}

	int finishedCount()
	{
		return finished;
	}

	int availableCount()
	{
		return available;
	}
	private volatile boolean dirty;
	// Ticks since the kill log opened; -1 = idle. Row widgets can be built a tick
	// or two after WidgetLoaded, so the scrape retries briefly once it's open.
	private int killLogTicks = -1;

	// Per-tab obtained/total, keyed "<tab>_obtained"/"<tab>_total".
	private final Map<String, Integer> catCounts = new HashMap<>();
	// Every obtained item from a full-log transmit: item name -> quantity.
	private final Map<String, Integer> clogItems = new HashMap<>();
	// The init script we run to reset the view re-fires SETUP; this ignores our own
	// re-trigger until the transmit burst settles.
	private boolean clogRetrieving;
	// Tick to flush on, a short buffer after the last transmit item (large logs
	// stream over a few ticks); -1 = idle.
	private int clogFlushTick = -1;

	@Inject
	ClogCapture(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState state = e.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			// completion fraction: free every login, no interface needed.
			int obtained = client.getVarpValue(VARP_CLOG_OBTAINED);
			int total = client.getVarpValue(VARP_CLOG_TOTAL);
			if (total > 0 && (obtained != finished || total != available))
			{
				finished = obtained;
				available = total;
				dirty = true;
			}
			if (readCategoryCounts())
			{
				dirty = true;
			}
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			// NOT reset here. The plugin folds this capture into the journal on its
			// own logout handler, and both of us are @Subscribe on GameStateChanged
			// at the same priority: whichever the EventBus happened to register
			// first decided whether a session's log survived. It calls reset() once
			// it has taken what it needs, so the order is stated rather than lucky.
			dropSceneState();
		}
		else if (state == GameState.HOPPING)
		{
			// Same account on the other side, so the accreted model and the dirty flag
			// survive. Scene state doesn't: drop the kill-log retry, and close out a
			// live transmit before the hop strands it on a deadline.
			killLogTicks = -1;
			if (clogFlushTick > 0)
			{
				settleTransmit();
			}
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired e)
	{
		if (e.getScriptId() == ScriptID.COLLECTION_DRAW_LIST)
		{
			scrapeOpenPage();
			return;
		}
		if (e.getScriptId() == COLLECTION_LOG_SETUP)
		{
			// The player opened their log. An adventure-log view is someone else's log:
			// bail, and bin anything half-captured. Then don't recurse on the reset
			// script we run below.
			if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1)
			{
				clogItems.clear();
				return;
			}
			if (clogRetrieving)
			{
				return;
			}
			clogRetrieving = true;
			clogItems.clear();
			// The log's own "Search" op makes the game server transmit every entry;
			// the init script then resets the view and closes the search behind us.
			client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP,
				1, -1, "Search", null);
			client.runScript(COLLECTION_INIT_SCRIPT);
			// Fallback deadline so the guard always clears, even for an empty log that
			// never fires a transmit. Each captured item pushes this out by 3.
			clogFlushTick = client.getTickCount() + 5;
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired e)
	{
		if (e.getScriptId() != COLLECTION_DELAYED_TRANSMIT)
		{
			return;
		}
		if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1)
		{
			return;
		}
		Object[] args = e.getScriptEvent().getArguments();
		if (args == null || args.length < 3)
		{
			return;
		}
		try
		{
			int itemId = (int) args[1];
			int quantity = (int) args[2];
			String name = itemName(itemId);
			if (name != null)
			{
				clogItems.put(name, Math.max(1, quantity));
				// Flush a few ticks after the last item (big logs stream over ticks).
				clogFlushTick = client.getTickCount() + 3;
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("clog transmit read failed", ex);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.KILL_LOG)
		{
			killLogTicks = 0;   // arm the retry; the scrape runs on the next ticks
		}
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		// No new item for a few ticks means the transmit is done.
		if (clogFlushTick > 0 && client.getTickCount() >= clogFlushTick)
		{
			settleTransmit();
		}

		if (killLogTicks < 0)
		{
			return;
		}
		int before = slayerKcs.size();
		scrapeSlayerLog();
		if (slayerKcs.size() > before || killLogTicks >= 5)
		{
			killLogTicks = -1;   // captured, or gave up after ~5 ticks
		}
		else
		{
			killLogTicks++;
		}
	}

	// End a transmit: the guard reopens for the next log open and whatever arrived
	// becomes publishable. Reached by the quiet-tick deadline or by a world hop.
	private void settleTransmit()
	{
		clogFlushTick = -1;
		clogRetrieving = false;
		if (!clogItems.isEmpty())
		{
			dirty = true;
		}
	}

	// The kill log is two parallel columns of children: names, and their kill counts.
	private void scrapeSlayerLog()
	{
		try
		{
			Widget names = client.getWidget(InterfaceID.KillLog.NAME);
			Widget kills = client.getWidget(InterfaceID.KillLog.KILL);
			Widget[] nameKids = childrenOf(names);
			Widget[] killKids = childrenOf(kills);
			if (nameKids == null || killKids == null)
			{
				return;
			}
			int n = Math.min(nameKids.length, killKids.length);
			int captured = 0;
			for (int i = 0; i < n; i++)
			{
				String mob = text(nameKids[i]);
				String kcText = text(killKids[i]);
				if (mob == null || mob.isEmpty() || kcText == null)
				{
					continue;
				}
				String digits = kcText.replaceAll("[^0-9]", "");
				if (digits.isEmpty())
				{
					continue;   // "Lots!" (>65,535), or a header row
				}
				try
				{
					slayerKcs.put(mob, Integer.parseInt(digits));
					captured++;
					dirty = true;
				}
				catch (NumberFormatException ignored)
				{
					// skip a malformed row
				}
			}
			if (captured > 0)
			{
				log.debug("slayer-log captured {} species", captured);
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("slayer-log scrape failed", ex);
		}
	}

	// Rows can be dynamic, loader, or static children; try each.
	private static Widget[] childrenOf(Widget w)
	{
		if (w == null)
		{
			return null;
		}
		Widget[] d = w.getDynamicChildren();
		if (d != null && d.length > 0)
		{
			return d;
		}
		Widget[] c = w.getChildren();
		if (c != null && c.length > 0)
		{
			return c;
		}
		return w.getStaticChildren();
	}

	private void scrapeOpenPage()
	{
		if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1)
		{
			return;
		}
		try
		{
			Widget header = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
			Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
			if (header == null || items == null)
			{
				return;
			}
			Widget[] head = header.getDynamicChildren();
			if (head == null || head.length == 0)
			{
				return;
			}
			String page = text(head[0]);   // the page title, e.g. "Vorkath"
			if (page == null || page.isEmpty())
			{
				return;
			}
			// EVERY header line "<label>: N" that isn't "Obtained", under the name
			// the log gives it. Keeping only the first number and throwing the
			// label away is what let a counter be read as a kill count when it
			// was counting something else: Wintertodt's line counts rewards
			// claimed, so it said 1,078 where 447 were killed, and the Gauntlet
			// page carries two completion counts of which only one was ever kept.
			// `kcs` still holds that first number, since the stored log and the
			// site both still read it.
			Map<String, Integer> lines = new LinkedHashMap<>();
			Map<String, Integer> times = new LinkedHashMap<>();
			Integer first = null;
			for (int i = 1; i < head.length; i++)
			{
				String line = text(head[i]);
				if (line == null || line.toLowerCase().startsWith("obtained"))
				{
					continue;
				}
				// a time first, or its seconds are read as a count
				Matcher t = TIME_LINE.matcher(line);
				if (t.matches())
				{
					String label = t.group("label").trim();
					if (!label.isEmpty())
					{
						long secs = Integer.parseInt(t.group("m")) * 60L
							+ Integer.parseInt(t.group("s"));
						if (t.group("h") != null)
						{
							secs += Integer.parseInt(
								t.group("h").substring(0, t.group("h").length() - 1)) * 3600L;
						}
						if (secs > 0 && secs < Integer.MAX_VALUE)
						{
							times.put(label, (int) secs);
						}
					}
					continue;
				}
				Matcher m = COUNT_LINE.matcher(line);
				if (!m.find())
				{
					continue;
				}
				try
				{
					int n = Integer.parseInt(m.group(1).replace(",", ""));
					// the match starts at the colon, so what precedes it is the
					// label exactly as the page wrote it
					String label = line.substring(0, m.start()).trim();
					if (label.isEmpty())
					{
						continue;
					}
					// A PERSONAL BEST IS A TIME, NOT A COUNT. The expression takes
					// the last ": number" on the line, so "Personal Best: 3:46"
					// hands back 46 with "Personal Best: 3" as its label, and the
					// seconds of a best time were being stored as a kill count:
					// Tempoross read 46 where 455 were killed, Vorkath 19 where
					// 156 were, the Gauntlet 55 where 31 were. A label left ending
					// in a digit is the tell that a time was cut in half.
					if (Character.isDigit(label.charAt(label.length() - 1)))
					{
						continue;
					}
					lines.put(label, n);
					if (first == null)
					{
						first = n;
					}
				}
				catch (NumberFormatException ignored)
				{
					// keep scanning
				}
			}
			if (first != null)
			{
				kcs.put(page, first);
			}
			if (!lines.isEmpty())
			{
				kcLines.put(page, lines);
			}
			if (!times.isEmpty())
			{
				pbLines.put(page, times);
			}
			// opacity 0 = obtained, anything greyed isn't.
			Map<String, Integer> pageItems = byCat.computeIfAbsent(page, k -> new HashMap<>());
			Widget[] kids = items.getDynamicChildren();
			if (kids != null)
			{
				for (Widget it : kids)
				{
					if (it == null || it.getItemId() <= 0 || it.getOpacity() != 0)
					{
						continue;
					}
					String name = itemName(it.getItemId());
					if (name != null)
					{
						pageItems.put(name, Math.max(1, it.getItemQuantity()));
					}
				}
			}
			dirty = true;
		}
		catch (RuntimeException ex)
		{
			log.debug("clog scrape failed", ex);
		}
	}

	// Reads the five per-tab varps; true if any of them moved.
	private boolean readCategoryCounts()
	{
		boolean changed = false;
		for (int i = 0; i < CAT_NAMES.length; i++)
		{
			int tot = client.getVarpValue(VARP_CAT[i][1]);
			if (tot <= 0)
			{
				continue;   // not synced yet; keep whatever we already had
			}
			int obt = client.getVarpValue(VARP_CAT[i][0]);
			String ok = CAT_NAMES[i] + "_obtained", tk = CAT_NAMES[i] + "_total";
			if (!Integer.valueOf(obt).equals(catCounts.get(ok)) || !Integer.valueOf(tot).equals(catCounts.get(tk)))
			{
				catCounts.put(ok, obt);
				catCounts.put(tk, tot);
				changed = true;
			}
		}
		return changed;
	}

	private String itemName(int id)
	{
		try
		{
			String n = itemManager.getItemComposition(id).getName();
			return (n == null || n.isEmpty() || "null".equalsIgnoreCase(n)) ? null : n;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	/** Widget text with colour tags stripped, or null. */
	private static String text(Widget w)
	{
		if (w == null)
		{
			return null;
		}
		String t = w.getText();
		return t == null ? null : Text.removeTags(t).trim();
	}

	// ── read by the journal and the clog push ──────────────────────────────

	boolean isDirty()
	{
		return dirty;
	}

	void clearDirty()
	{
		dirty = false;
	}

	/** What cannot outlive a scene, without touching what was captured. */
	private void dropSceneState()
	{
		killLogTicks = -1;
		clogFlushTick = -1;
		clogRetrieving = false;
	}

	void reset()
	{
		byCat.clear();
		kcs.clear();
		kcLines.clear();
		pbLines.clear();
		slayerKcs.clear();
		clogItems.clear();
		catCounts.clear();
		finished = 0;
		available = 0;
		killLogTicks = -1;
		clogFlushTick = -1;
		clogRetrieving = false;
		dirty = false;
	}

	/** JSON-ready clog state; the journal floor-merges it into what it already holds. */
	Map<String, Object> snapshot()
	{
		Map<String, Object> out = new HashMap<>();
		out.put("by_cat", byCat);
		out.put("kcs", kcs);
		out.put("kc_lines", kcLines);
		out.put("pb_lines", pbLines);
		out.put("slayer_kcs", slayerKcs);
		out.put("cat_counts", catCounts);
		// empty until the player opens their log once this session.
		out.put("clog_items", clogItems);
		out.put("finished", finished);
		out.put("available", available);
		return out;
	}
}
