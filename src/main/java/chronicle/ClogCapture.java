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

@Singleton
@Slf4j
public class ClogCapture
{
	private static final int VARP_CLOG_OBTAINED = 2943;
	private static final int VARP_CLOG_TOTAL = 2944;
	private static final String[] CAT_NAMES = {"bosses", "raids", "clues", "minigames", "other"};
	private static final int[][] VARP_CAT = {
		{4613, 4614}, {4615, 4616}, {4617, 4618}, {4619, 4620}, {4621, 4622},
	};
	private static final Pattern COUNT_LINE = Pattern.compile(":\\s*([\\d,]+)\\s*$");
	private static final Pattern TIME_LINE = Pattern.compile(
		"^(?<label>.+?):\\s*(?<h>\\d+:)?(?<m>\\d{1,3}):(?<s>\\d{2})(?:\\.\\d+)?\\s*$");

	private static final int COLLECTION_LOG_SETUP = 7797;
	private static final int COLLECTION_DELAYED_TRANSMIT = 4100;
	private static final int COLLECTION_INIT_SCRIPT = 2240;

	private final Client client;
	private final ItemManager itemManager;

	private final Map<String, Map<String, Integer>> byCat = new HashMap<>();
	private final Map<String, Integer> kcs = new HashMap<>();
	private final Map<String, Map<String, Integer>> kcLines = new HashMap<>();
	private final Map<String, Map<String, Integer>> pbLines = new HashMap<>();
	private final Map<String, Integer> slayerKcs = new HashMap<>();
	private volatile int finished;
	private volatile int available;
	private boolean dirty;

	private void readCompletion()
	{
		int obtained = client.getVarpValue(VARP_CLOG_OBTAINED);
		int total = client.getVarpValue(VARP_CLOG_TOTAL);
		if (total > 0 && (obtained != finished || total != available))
		{
			finished = obtained;
			available = total;
			dirty = true;
			revision++;
		}
	}

	void primeFromVarps()
	{
		readCompletion();
		if (readCategoryCounts())
		{
			dirty = true;
			revision++;
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

	private volatile long revision;

	long revision()
	{
		return revision;
	}

	private int killLogTicks = -1;

	private final Map<String, Integer> catCounts = new HashMap<>();
	private final Map<String, Integer> clogItems = new HashMap<>();
	private boolean clogRetrieving;
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
			readCompletion();
			if (readCategoryCounts())
			{
				dirty = true;
				revision++;
			}
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			dropSceneState();
		}
		else if (state == GameState.HOPPING)
		{
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
			client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP,
				1, -1, "Search", null);
			client.runScript(COLLECTION_INIT_SCRIPT);
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
			killLogTicks = 0;
		}
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
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
			killLogTicks = -1;
		}
		else
		{
			killLogTicks++;
		}
	}

	private void settleTransmit()
	{
		clogFlushTick = -1;
		clogRetrieving = false;
		if (!clogItems.isEmpty())
		{
			dirty = true;
			revision++;
		}
	}

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
					continue;
				}
				try
				{
					slayerKcs.put(mob, Integer.parseInt(digits));
					captured++;
				}
				catch (NumberFormatException ignored)
				{
				}
			}
			if (captured > 0)
			{
				dirty = true;
				revision++;
				log.debug("slayer-log captured {} species", captured);
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("slayer-log scrape failed", ex);
		}
	}

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
			String page = text(head[0]);
			if (page == null || page.isEmpty())
			{
				return;
			}
			Map<String, Integer> lines = new LinkedHashMap<>();
			Map<String, Integer> times = new LinkedHashMap<>();
			Integer first = null;
			for (int i = 1; i < head.length; i++)
			{
				String line = text(head[i]);
				if (line == null || line.toLowerCase(java.util.Locale.ROOT).startsWith("obtained"))
				{
					continue;
				}
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
					String label = line.substring(0, m.start()).trim();
					if (label.isEmpty())
					{
						continue;
					}
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
			Map<String, Integer> pageItems = byCat.computeIfAbsent(page, k -> new HashMap<>());
			Widget[] kids = items.getDynamicChildren();
			if (kids != null)
			{
				pageItems.clear();
				for (Widget it : kids)
				{
					if (it == null || it.getItemId() <= 0 || it.getOpacity() != 0)
					{
						continue;
					}
					String name = itemName(it.getItemId());
					if (name != null)
					{
						pageItems.merge(name, Math.max(1, it.getItemQuantity()),
							Integer::sum);
					}
				}
			}
			dirty = true;
			revision++;
		}
		catch (RuntimeException ex)
		{
			log.debug("clog scrape failed", ex);
		}
	}

	private boolean readCategoryCounts()
	{
		boolean changed = false;
		for (int i = 0; i < CAT_NAMES.length; i++)
		{
			int tot = client.getVarpValue(VARP_CAT[i][1]);
			if (tot <= 0)
			{
				continue;
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

	private static String text(Widget w)
	{
		if (w == null)
		{
			return null;
		}
		String t = w.getText();
		return t == null ? null : Text.removeTags(t).trim();
	}

	boolean isDirty()
	{
		return dirty;
	}

	void clearDirty()
	{
		dirty = false;
	}

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
		dropSceneState();
		dirty = false;
	}

	Map<String, Object> snapshot()
	{
		Map<String, Object> out = new HashMap<>();
		out.put("by_cat", byCat);
		out.put("kcs", kcs);
		out.put("kc_lines", kcLines);
		out.put("pb_lines", pbLines);
		out.put("slayer_kcs", slayerKcs);
		out.put("cat_counts", catCounts);
		out.put("clog_items", clogItems);
		out.put("finished", finished);
		out.put("available", available);
		return out;
	}
}
