/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.counters.ExperienceStatTracker.SkillGain;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.GridLayout;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JPanel;
import static chronicle.Feed.typeOf;
import static chronicle.Json.*;
import static chronicle.Ui.*;
import static chronicle.panel.StatRegistry.prettify;

final class HomeScreen extends Screen
{
	private static final String[] PINNED = {"totalXpGained", "damageDealt", "consumedValue"};
	private static final List<String> DAMAGE_SPLIT = List.of("damageDealtMelee", "damageDealtRanged", "damageDealtMagic");
	private static final String FOLD_XP = "home:xp";
	private static final String FOLD_DAMAGE = "home:damage";

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
		spaced(p, sessionCard());
		List<LocalStore.RecentDrop> recent = store.recentDrops();
		if (!recent.isEmpty())
		{
			JPanel card = card("Recent drops");
			JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
			grid.setBackground(DARKER);
			grid.setAlignmentX(Component.LEFT_ALIGNMENT);
			firstN(recent, 10).forEach(d -> grid.add(ui.sprite(d.itemId, d.name, d.quantity)));
			card.add(grid);
			spaced(p, card);
		}
		return p;
	}

	private JPanel sessionCard()
	{
		long began = plugin.sessionStart();
		long ran = plugin.sessionElapsedMinutes();
		JPanel strip = began > 0 && ran > 0
			? card("This session", "since " + CLOCK.format(Instant.ofEpochMilli(began)) + " · " + hoursMinutes(ran))
			: card("This session");
		int head = strip.getComponentCount();
		Map<String, Integer> sess = plugin.sessionView();
		Set<String> shown = new HashSet<>();
		for (String key : PINNED)
		{
			long v = sess.getOrDefault(key, 0);
			if (v > 0)
			{
				shown.add(key);
				addPinned(strip, key, v, sess);
			}
		}
		LootDays.LootWindow loot = store.loot.sessionLootWindow();
		if (loot.loots > 0)
		{
			strip.add(row("Drops received", loot.loots + " · " + gps(loot.value), GREEN));
			if (loot.leftKills > 0)
			{
				strip.add(row("Drops taken", fmt(Math.max(0, loot.loots - loot.leftKills)), GREEN));
			}
		}
		if (loot.left > 0)
		{
			strip.add(row("Left behind", qtyGp(loot.left, loot.leftValue)));
		}
		addFeats(strip);
		addMovers(strip, plugin.sessionDisplayCounters(), shown);
		if (strip.getComponentCount() == head)
		{
			strip.add(row("A fresh page", ""));
		}
		return strip;
	}

	private void addPinned(JPanel strip, String key, long v, Map<String, Integer> sess)
	{
		boolean xp = "totalXpGained".equals(key);
		boolean damage = "damageDealt".equals(key) && DAMAGE_SPLIT.stream().anyMatch(k -> sess.getOrDefault(k, 0) > 0);
		String label = xp ? "Xp gained" : "consumedValue".equals(key) ? "Consumed" : StatRegistry.label(key);
		JPanel r = row(label, StatRegistry.isGp(key) ? gps(v) : xp ? "+" + gp(v) : fmt(v), GREEN);
		if (xp)
		{
			ui.foldHead(r, FOLD_XP, "Each skill's xp and xp per hour this session");
		}
		if (damage)
		{
			ui.foldHead(r, FOLD_DAMAGE, "The damage this session, by style");
		}
		strip.add(r);
		if (xp && ui.foldOpen(FOLD_XP))
		{
			List<SkillGain> gains = plugin.sessionSkillXp();
			if (gains.isEmpty())
			{
				strip.add(ghostRow("no skill breakdown yet", ""));
			}
			for (SkillGain g : gains)
			{
				JPanel line = row(g.skill.getName(), "+" + gp(g.xp) + (g.perHour >= 0 ? " · " + gp(g.perHour) + "/h" : ""));
				line.setBorder(pad(1, 10, 1, 2));
				strip.add(line);
			}
		}
		if (damage && ui.foldOpen(FOLD_DAMAGE))
		{
			for (String split : DAMAGE_SPLIT)
			{
				long sv = sess.getOrDefault(split, 0);
				if (sv > 0)
				{
					strip.add(row(StatRegistry.label(split), fmt(sv)));
				}
			}
		}
	}

	private void addFeats(JPanel strip)
	{
		long since = plugin.sessionStart();
		if (since <= 0)
		{
			return;
		}
		Map<String, Long> levels = new LinkedHashMap<>();
		List<String> slots = new ArrayList<>();
		List<String> pets = new ArrayList<>();
		for (JsonObject e : store.feedNewest(Board.FEED_SCAN_DEEP))
		{
			JsonObject d = obj(e, "data");
			if (asLong(e.get("ts")) < since)
			{
				continue;
			}
			String type = typeOf(e);
			if ("LEVEL".equals(type) && has(d, "skill") && has(d, "level"))
			{
				levels.merge(prettify(low(d.get("skill").getAsString())), asLong(d.get("level")), Math::max);
			}
			else if ("COLLECTION".equals(type) && has(d, "itemName"))
			{
				slots.add(d.get("itemName").getAsString());
			}
			else if ("PET".equals(type) && has(d, "petName"))
			{
				pets.add(d.get("petName").getAsString());
			}
		}
		List<String> said = new ArrayList<>();
		levels.forEach((skill, level) -> said.add(skill + " " + level));
		named(strip, "Levels", said);
		named(strip, plural(slots.size(), "Log slot"), slots);
		named(strip, plural(pets.size(), "Pet"), pets);
	}

	private static void named(JPanel strip, String label, List<String> names)
	{
		if (!names.isEmpty())
		{
			JPanel r = row(label, names.size() <= 2 ? String.join(" · ", names) : "+" + names.size(), GREEN);
			r.setToolTipText(String.join(" · ", names));
			strip.add(r);
		}
	}

	private void addMovers(JPanel strip, Map<String, Integer> sess, Set<String> shown)
	{
		Map<String, List<Map.Entry<String, Long>>> byFamily = new LinkedHashMap<>();
		Map<String, List<Map.Entry<String, Long>>> under = new LinkedHashMap<>();
		sess.forEach((key, v) ->
		{
			if (v > 0 && !shown.contains(key) && !StatRegistry.hidden(key) && !DAMAGE_SPLIT.contains(key))
			{
				String parent = parentOf(key, sess);
				(parent != null ? under.computeIfAbsent(parent, k -> new ArrayList<>())
					: byFamily.computeIfAbsent(StatRegistry.family(key), f -> new ArrayList<>()))
					.add(Map.entry(key, (long) v));
			}
		});
		for (String family : StatRegistry.FAMILIES)
		{
			List<Map.Entry<String, Long>> rows = byFamily.getOrDefault(family, List.of());
			if (rows.isEmpty())
			{
				continue;
			}
			rows.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
			String fold = "session:" + family;
			boolean open = ui.foldOpen(fold, true);
			strip.add(ui.quietHead(family, open ? "" : fmt(rows.size()), fold));
			if (open)
			{
				rows.forEach(e -> addMover(strip, e.getKey(), e.getValue(), under.get(e.getKey())));
			}
		}
	}

	private static String parentOf(String key, Map<String, Integer> sess)
	{
		String sec = StatRegistry.subgroup(key);
		if (StatRegistry.isFloor(key) || sec.isEmpty())
		{
			return null;
		}
		for (String f : StatRegistry.floorKeys(sec.equals("Destinations") ? "Teleports" : sec))
		{
			if (sess.getOrDefault(f, 0) > 0)
			{
				return f;
			}
		}
		return null;
	}

	private void addMover(JPanel strip, String key, long value, List<Map.Entry<String, Long>> kids)
	{
		JPanel r = row(StatRegistry.label(key), StatRegistry.isGp(key) ? gps(value) : fmt(value));
		if (kids == null || kids.isEmpty())
		{
			strip.add(r);
			return;
		}
		String fold = "session:row:" + key;
		strip.add(ui.folds(r, fold));
		if (!ui.foldOpen(fold))
		{
			return;
		}
		kids.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		int cap = ui.cap(fold, 6);
		firstN(kids, cap).forEach(k -> strip.add(nested(row(StatRegistry.rowLabel(k.getKey()), fmt(k.getValue())))));
		ui.addMore(strip, fold, kids.size(), cap, true);
		long named = kids.stream().mapToLong(Map.Entry::getValue).sum();
		if (value - named >= 1)
		{
			String sec = StatRegistry.subgroup(kids.get(0).getKey());
			boolean roads = "Teleports".equals(sec) || "Destinations".equals(sec);
			strip.add(nested(ghostRow(roads ? "Other means" : "Other", fmt(value - named))));
		}
	}
}
