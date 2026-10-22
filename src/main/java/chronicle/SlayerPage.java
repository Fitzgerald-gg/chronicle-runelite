/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.ChroniclePanel.View;
import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SlayerJourney;
import chronicle.LocalStore.SlayerTask;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.BooleanSupplier;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import static chronicle.Copy.copyPicture;
import static chronicle.Kit.ACCENT_LIFETIME;
import static chronicle.Kit.DARK;
import static chronicle.Kit.ROW_CAP;
import static chronicle.Kit.card;
import static chronicle.Kit.column;
import static chronicle.Kit.copyHeader;
import static chronicle.Kit.day;
import static chronicle.Kit.dim;
import static chronicle.Kit.link;
import static chronicle.Kit.listCard;
import static chronicle.Kit.menuItem;
import static chronicle.Kit.note;
import static chronicle.Kit.noted;
import static chronicle.Kit.part;
import static chronicle.Kit.progress;
import static chronicle.Kit.row;
import static chronicle.Kit.small;
import static chronicle.Kit.spaced;
import static chronicle.Kit.styled;
import static chronicle.Kit.tallyCard;
import static chronicle.Kit.value;
import static chronicle.Kit.vgap;
import static chronicle.Text.count;
import static chronicle.Text.fmt;
import static chronicle.Text.gp;
import static chronicle.Text.gps;

final class SlayerPage
{
	private final ChroniclePanel panel;

	SlayerPage(ChroniclePanel panel)
	{
		this.panel = panel;
	}

	JPanel buildSlayer()
	{
		JPanel p = column();
		ChronicleEventCapture.SlayerView task = panel.live.slayerView();
		if (task != null)
		{
			addTaskCard(p, task, "Current task", ACCENT_LIFETIME);
		}

		JPanel lens = new JPanel(new GridLayout(1, 3, 3, 3));
		lens.setBackground(DARK);
		for (String l : new String[]{"Tasks", "Monsters", "Drops"})
		{
			lens.add(panel.pill(l, l.equals(panel.slayerLens), 4, null, () ->
			{
				panel.slayerLens = l;
				panel.rebuildInPlace();
			}));
		}
		spaced(p, lens);

		if (!panel.journeyFetching)
		{
			panel.journeyFetching = true;
			panel.fetchSlayerJourney(j -> SwingUtilities.invokeLater(() ->
			{
				panel.journeyFetching = false;
				if (j == null)
				{
					return;
				}
				boolean moved = journeyMoved(panel.journeyCache, j);
				panel.journeyCache = j;
				if (moved && panel.view == View.SLAYER && "Tasks".equals(panel.slayerLens))
				{
					panel.rebuildInPlace();
				}
			}));
		}
		if ("Monsters".equals(panel.slayerLens))
		{
			return addKillLog(p);
		}
		if ("Drops".equals(panel.slayerLens))
		{
			return addOnTaskLoot(p);
		}

		if (panel.journeyCache != null)
		{
			addJourney(p, panel.journeyCache);
		}
		else
		{
			p.add(note("Reading the task journey from the journal…"));
		}
		return p;
	}

	private JPanel addOnTaskLoot(JPanel p)
	{
		long[] ms = panel.window().ms();
		final LocalStore.Tasks tasks = panel.store.tasks(ms[0], ms[1], panel.lootTask, panel.wholeRecord());
		final List<BagItem> bag = tasks.loot();
		if (bag.isEmpty())
		{
			p.add(taskPicker());
			return noted(p, panel.lootTask != null
				? panel.window().phrase("No loot logged on " + panel.lootTask)
				: panel.wholeRecord()
					? "No task loot in the journal yet. It collects as tasks close."
					: panel.window().phrase("No task loot"));
		}
		final long[] sum = LocalStore.tallyOf(bag);
		final long qty = sum[0];
		final long value = sum[1];

		if (panel.lootKind != null)
		{
			p.add(taskPicker());
			return panel.loot.kindDrill(p, bag, "task:");
		}
		final long[] tally = tasks.tally();
		spaced(p, onTaskHead(qty, value, tally));
		p.add(taskPicker());

		LinkedHashMap<String, BooleanSupplier> ways =
			new LinkedHashMap<>();
		ways.put("These kinds", () -> copyPicture(kindsPicture(bag, qty, value, tally)));
		ways.put("Every item", () -> copyPicture(
			panel.loot.lootPicture(panel.lootTask == null ? "On-task loot" : panel.lootTask, bag,
				new long[]{qty, value}, false), true));
		p.add(copyHeader("Drops", ways));
		panel.loot.addKindRows(p, bag);
		return p;
	}

	private JPanel kindsPicture(List<BagItem> bag, long qty, long value,
		long[] tally)
	{
		JPanel page = column();
		spaced(page, onTaskHead(qty, value, tally));
		if (panel.lootTask != null)
		{
			spaced(page, row("Task", panel.lootTask, ACCENT_LIFETIME), 4);
		}
		panel.loot.addKindRows(page, bag);
		return page;
	}

	private JPanel onTaskHead(long qty, long value, long[] tally)
	{
		JPanel head = tallyCard("On-task loot", "Items", fmt(qty), ACCENT_LIFETIME, value);
		head.add(row("Tasks", fmt(tally[2])));
		if (tally[0] > 0)
		{
			head.add(row("Kills logged", fmt(tally[0])));
		}
		if (tally[1] > 0)
		{
			head.add(row("Superiors", fmt(tally[1])));
		}
		return head;
	}

	private JPanel addKillLog(JPanel p)
	{
		List<Entry<String, Long>> kcs = new ArrayList<>(LocalStore.killLogCounts(panel.build.clog()).entrySet());
		if (kcs.isEmpty())
		{
			return noted(p, "No kill log yet. It copies itself the next time you open "
				+ "the Slayer Kill Log in game.");
		}
		kcs.sort(Entry.<String, Long>comparingByValue().reversed());
		JPanel card = card("Kill log");
		final int cap = panel.drillShown.getOrDefault("killlog", ROW_CAP);
		for (Entry<String, Long> e : Sources.firstN(kcs, cap))
		{
			JPanel r = row(e.getKey(), fmt(e.getValue()));
			final String mob = e.getKey();
			link(r, () -> panel.openSourceLoose(mob));
			card.add(r);
		}
		panel.drillMore(card, "killlog", kcs.size(), cap);
		p.add(card);
		return p;
	}

	private void addJourney(JPanel p, SlayerJourney j)
	{
		if (j.tasks.isEmpty() && j.completedTasks == 0)
		{
			p.add(note("No tasks in the journal yet. They collect as "
				+ "you play with the Slayer plugin on."));
			return;
		}
		List<SlayerTask> shown = new ArrayList<>();
		List<Integer> where = new ArrayList<>();
		for (int i = 0; i < j.tasks.size(); i++)
		{
			SlayerTask t = j.tasks.get(i);
			if (panel.window().inside((long) (t.ts * 1000)) && (panel.wholeRecord() || !t.inProgress))
			{
				shown.add(t);
				where.add(i);
			}
		}
		if (shown.isEmpty() && !panel.wholeRecord())
		{
			p.add(note(panel.window().phrase("No tasks closed")));
			return;
		}
		long tasksDone = j.completedTasks;
		long killsOnTask = j.totalKills;
		long onTaskLoot = j.totalValueGp;
		if (!panel.wholeRecord())
		{
			tasksDone = 0;
			killsOnTask = 0;
			onTaskLoot = 0;
			for (SlayerTask t : shown)
			{
				if (!t.inProgress)
				{
					tasksDone++;
				}
				killsOnTask += t.kills;
				onTaskLoot += t.totalValue;
			}
		}
		JPanel head = card("The journey");
		head.add(row("Tasks done", fmt(tasksDone), ACCENT_LIFETIME));
		head.add(row("Kills on task", fmt(killsOnTask)));
		head.add(row("On-task loot", gps(onTaskLoot)));
		if (j.totalXpEst > 0)
		{
			head.add(row("Slayer xp (est.)", gp(j.totalXpEst)));
		}
		spaced(p, head);
		for (int k = 0; k < Math.min(shown.size(), panel.slayerShown); k++)
		{
			SlayerTask t = shown.get(k);
			final int at = where.get(k);
			String kills = t.inProgress && t.assignment > t.kills
				? fmt(t.kills) + " / " + fmt(t.assignment)
				: count(t.kills, "kill");
			if (t.noLootKills > 0)
			{
				kills += " · " + fmt(t.noLootKills) + " no-drop";
			}
			listCard(p, row(t.task, t.totalValue > 0 ? gps(t.totalValue) : "", ACCENT_LIFETIME, t.inProgress),
				kills, t.ts > 0 ? day((long) (t.ts * 1000)) : "", () -> panel.showTask(at));
		}
		if (shown.size() > panel.slayerShown)
		{
			panel.more(p, shown.size(), panel.slayerShown, false, n -> panel.slayerShown = n);
			p.add(vgap(4));
		}
	}

	private JPopupMenu taskMenu()
	{
		JPopupMenu menu = new JPopupMenu();
		menuItem(menu, "Every task", panel.lootTask == null, () -> pickTask(null));
		menu.addSeparator();
		for (String task : panel.store.allTasks().names())
		{
			menuItem(menu, task, task.equals(panel.lootTask), () -> pickTask(task));
		}
		return menu;
	}

	private void pickTask(String task)
	{
		panel.lootTask = task;
		panel.lootKind = null;
		panel.rebuildInPlace();
	}

	private JPanel taskPicker()
	{
		JPanel r = row("Task", panel.lootTask == null ? "Every task" : panel.lootTask, ACCENT_LIFETIME);
		styled(part(r, BorderLayout.CENTER), small(), dim());
		JLabel pick = part(r, BorderLayout.EAST);
		pick.setFont(small());
		pick.setToolTipText("Narrow this board to one task");
		link(pick, () -> taskMenu().show(r, 0, r.getHeight()));
		link(r, () -> taskMenu().show(r, 0, r.getHeight()));
		return r;
	}

	void addTaskCard(JPanel p, ChronicleEventCapture.SlayerView task, String title, Color ink)
	{
		JPanel card = card(title);
		card.add(row(task.task, task.remaining + " left", ink));
		if (task.initial > 0)
		{
			card.add(progress(1f - (float) task.remaining / task.initial));
		}
		card.setToolTipText("Open this task's page");
		link(card, panel::openCurrentTask);
		spaced(p, card);
	}

	static boolean journeyMoved(SlayerJourney was,
		SlayerJourney now)
	{
		if (was == null)
		{
			return true;
		}
		if (was.completedTasks != now.completedTasks
			|| was.totalKills != now.totalKills
			|| was.tasks.size() != now.tasks.size())
		{
			return true;
		}
		return !was.tasks.isEmpty()
			&& was.tasks.get(0).kills != now.tasks.get(0).kills;
	}
}
