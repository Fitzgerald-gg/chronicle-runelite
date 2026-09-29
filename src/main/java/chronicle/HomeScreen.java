/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.counters.ExperienceStatTracker;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.swing.JPanel;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.Pictures.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;
import static chronicle.panel.StatRegistry.prettify;

final class HomeScreen
{
	private final ChroniclePanel ui;
	private final Board board;
	private final ChroniclePlugin plugin;
	private final Period period;

	HomeScreen(ChroniclePanel ui, Board board)
	{
		this.ui = ui;
		this.board = board;
		this.plugin = board.plugin;
		this.period = board.period;
	}

	JPanel buildHome()
	{
		JPanel p = column();
		String stalled = plugin.journalWarning();
		if (stalled != null)
		{
			spaced(p, note(stalled));
		}

		ChronicleEventCapture.SlayerView task = plugin.slayerView();
		if (task != null && plugin.slayerSeenThisSession())
		{
			ui.slayer.addTaskCard(p, task, "Slayer task", GREEN);
		}

		long began = plugin.sessionStart();
		long ran = plugin.sessionElapsedMinutes();
		JPanel strip = began > 0 && ran > 0
			? card("This session", "since " + CLOCK.format(Instant.ofEpochMilli(began))
				+ " · " + hoursMinutes(ran))
			: card("This session");
		Map<String, Integer> sess = plugin.sessionView();
		int mounted = 0;
		Set<String> shownKeys = new HashSet<>();
		for (String key : HOME_PINNED)
		{
			long v = sess.getOrDefault(key, 0);
			if (v > 0)
			{
				boolean isXp = "totalXpGained".equals(key);
				boolean isDamage = "damageDealt".equals(key) && splitOf(sess) > 0;
				JPanel r = row(homeLabel(key),
					StatRegistry.isGp(key) ? gps(v)
						: (isXp ? "+" + gp(v) : fmt(v)),
					GREEN);
				if (isXp)
				{
					ui.foldHead(r, FOLD_HOME_XP, "Each skill's xp and xp per hour this session");
				}
				if (isDamage)
				{
					ui.foldHead(r, FOLD_HOME_DAMAGE, "The damage this session, by style");
				}
				strip.add(r);
				if (isXp && ui.foldOpen(FOLD_HOME_XP))
				{
					addXpBySkill(strip);
				}
				if (isDamage && ui.foldOpen(FOLD_HOME_DAMAGE))
				{
					for (String split : DAMAGE_SPLIT)
					{
						long sv = sess.getOrDefault(split, 0);
						if (sv > 0)
						{
							strip.add(row(StatRegistry.label(split), fmt(sv)));
							mounted++;
						}
					}
				}
				shownKeys.add(key);
				mounted++;
			}
		}
		if (plugin.sessionLoots() > 0)
		{
			strip.add(row("Drops received",
				plugin.sessionLoots() + " · " + gps(plugin.sessionLootValue()),
				GREEN));
			mounted++;
			if (plugin.sessionUntakenKills() > 0)
			{
				strip.add(row("Drops taken",
					fmt(Math.max(0, plugin.sessionLoots() - plugin.sessionUntakenKills())),
					GREEN));
				mounted++;
			}
		}
		long[] untaken = plugin.sessionUntakenTally();
		if (untaken[0] > 0)
		{
			strip.add(row("Left behind", qtyGp(untaken[0], untaken[1])));
			mounted++;
		}
		mounted += addSittingFeats(strip);
		mounted += addSessionMovers(strip, plugin.sessionDisplayCounters(), shownKeys);
		if (mounted == 0)
		{
			strip.add(row("A fresh page", ""));
		}
		spaced(p, strip);

		List<LocalStore.RecentDrop> recent = plugin.recentDrops();
		if (!recent.isEmpty())
		{
			JPanel card = card("Recent drops");
			JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
			grid.setBackground(DARKER);
			grid.setAlignmentX(Component.LEFT_ALIGNMENT);
			int shown = 0;
			for (LocalStore.RecentDrop d : recent)
			{
				if (shown++ >= 10)
				{
					break;
				}
				grid.add(ui.sprite(d.itemId, d.name, d.quantity));
			}
			card.add(grid);
			spaced(p, card);
		}

		return p;
	}

	static final String[] HOME_PINNED = {
		"totalXpGained", "damageDealt", "consumedValue"
	};

	static String homeLabel(String key)
	{
		switch (key)
		{
			case "totalXpGained":
				return "Xp gained";
			case "consumedValue":
				return "Consumed";
			default:
				return StatRegistry.label(key);
		}
	}

	static long splitOf(Map<String, Integer> sess)
	{
		long n = 0;
		for (String k : DAMAGE_SPLIT)
		{
			n += sess.getOrDefault(k, 0);
		}
		return n;
	}

	void addXpBySkill(JPanel strip)
	{
		List<ExperienceStatTracker.SkillGain> gains = plugin.sessionSkillXp();
		if (gains.isEmpty())
		{
			strip.add(ghostRow("no skill breakdown yet", ""));
			return;
		}
		for (ExperienceStatTracker.SkillGain g : gains)
		{
			String right = "+" + gp(g.xp)
				+ (g.perHour >= 0 ? " · " + gp(g.perHour) + "/h" : "");
			JPanel r = row(g.skill.getName(), right);
			r.setBorder(pad(1, 10, 1, 2));
			strip.add(r);
		}
	}

	static String parentOf(String key, Map<String, Integer> sess)
	{
		if (StatRegistry.isFloor(key))
		{
			return null;
		}
		String sec = StatRegistry.subgroup(key);
		if (sec.isEmpty())
		{
			return null;
		}
		List<String> floors = StatRegistry.floorKeys(
			sec.equals("Destinations") ? "Teleports" : sec);
		for (String f : floors)
		{
			if (sess.getOrDefault(f, 0) > 0)
			{
				return f;
			}
		}
		return null;
	}

	int addSittingFeats(JPanel strip)
	{
		long since = plugin.sessionStart();
		if (since <= 0)
		{
			return 0;
		}
		Map<String, Long> levels = new LinkedHashMap<>();
		List<String> slots = new ArrayList<>();
		List<String> pets = new ArrayList<>();
		for (JsonObject e : plugin.feedNewest(Board.FEED_SCAN_DEEP))
		{
			if (asLong(e.get("ts")) < since)
			{
				continue;
			}
			JsonObject d = obj(e, "data");
			switch (typeOf(e))
			{
				case "LEVEL":
					if (has(d, "skill") && has(d, "level"))
					{
						levels.merge(prettify(
							low(d.get("skill").getAsString())),
							asLong(d.get("level")), Math::max);
					}
					break;
				case "COLLECTION":
					if (has(d, "itemName"))
					{
						slots.add(d.get("itemName").getAsString());
					}
					break;
				case "PET":
					if (has(d, "petName"))
					{
						pets.add(d.get("petName").getAsString());
					}
					break;
				default:
					break;
			}
		}
		int mounted = 0;
		if (!levels.isEmpty())
		{
			List<String> said = new ArrayList<>();
			for (Entry<String, Long> l : levels.entrySet())
			{
				said.add(l.getKey() + " " + l.getValue());
			}
			strip.add(namedRow("Levels", said, GREEN));
			mounted++;
		}
		if (!slots.isEmpty())
		{
			strip.add(namedRow(plural(slots.size(), "Log slot"), slots, GREEN));
			mounted++;
		}
		if (!pets.isEmpty())
		{
			strip.add(namedRow(plural(pets.size(), "Pet"), pets, GREEN));
			mounted++;
		}
		return mounted;
	}

	JPanel namedRow(String label, List<String> names, Color color)
	{
		String right = names.size() <= 2 ? String.join(" · ", names) : "+" + names.size();
		JPanel r = row(label, right, color);
		r.setToolTipText(String.join(" · ", names));
		return r;
	}

	int addSessionMovers(JPanel strip, Map<String, Integer> sess,
		Set<String> shownKeys)
	{
		Map<String, List<Entry<String, Long>>> byFamily = new LinkedHashMap<>();
		Map<String, List<Entry<String, Long>>> under = new LinkedHashMap<>();
		for (Entry<String, Integer> e : sess.entrySet())
		{
			String key = e.getKey();
			if (e.getValue() <= 0 || shownKeys.contains(key) || StatRegistry.hidden(key)
				|| DAMAGE_SPLIT.contains(key))
			{
				continue;
			}
			Entry<String, Long> moved =
				new AbstractMap.SimpleEntry<>(key, (long) e.getValue());
			String parent = parentOf(key, sess);
			if (parent != null)
			{
				under.computeIfAbsent(parent, k -> new ArrayList<>()).add(moved);
				continue;
			}
			byFamily.computeIfAbsent(StatRegistry.family(key), f -> new ArrayList<>())
				.add(moved);
		}

		int mounted = 0;
		for (String family : StatRegistry.FAMILIES)
		{
			List<Entry<String, Long>> rows = byFamily.get(family);
			if (rows == null || rows.isEmpty())
			{
				continue;
			}
			rows.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
			String stateKey = "session:" + family;
			boolean open = ui.foldOpen(stateKey, true);
			strip.add(ui.quietHead(family, open ? "" : fmt(rows.size()), stateKey));
			mounted++;
			if (!open)
			{
				continue;
			}
			for (Entry<String, Long> e : rows)
			{
				mounted += addMoverRow(strip, e.getKey(), e.getValue(),
					under.get(e.getKey()));
			}
		}
		return mounted;
	}

	int addMoverRow(JPanel strip, String key, long value,
		List<Entry<String, Long>> kids)
	{
		if (kids == null || kids.isEmpty())
		{
			strip.add(sessionRow(key, value));
			return 1;
		}
		String listKey = "session:row:" + key;
		boolean open = ui.foldOpen(listKey);
		strip.add(ui.folds(sessionRow(key, value), listKey));
		int mounted = 1;
		if (!open)
		{
			return mounted;
		}
		kids.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		int cap = ui.shownCap(listKey);
		int shown = 0;
		long named = 0;
		for (Entry<String, Long> k : kids)
		{
			named += k.getValue();
			if (shown++ >= cap)
			{
				continue;
			}
			strip.add(nested(row(StatRegistry.rowLabel(k.getKey()), fmt(k.getValue()))));
			mounted++;
		}
		ui.addMore(strip, listKey, kids.size(), cap, true);
		if (value - named >= 1)
		{
			strip.add(nested(ghostRow(
				"Teleports".equals(StatRegistry.subgroup(kids.get(0).getKey()))
					|| "Destinations".equals(StatRegistry.subgroup(kids.get(0).getKey()))
					? "Other means" : "Other",
				fmt(value - named))));
			mounted++;
		}
		return mounted;
	}

	JPanel sessionRow(String key, long v)
	{
		return row(StatRegistry.label(key),
			StatRegistry.isGp(key) ? gps(v) : fmt(v));
	}

	static final String FOLD_HOME_XP = "home:xp";

	static final String FOLD_HOME_DAMAGE = "home:damage";

	static final List<String> DAMAGE_SPLIT = Arrays.asList(
		"damageDealtMelee", "damageDealtRanged", "damageDealtMagic");
}
