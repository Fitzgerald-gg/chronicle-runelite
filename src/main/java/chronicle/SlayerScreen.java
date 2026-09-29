/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SlayerJourney;
import chronicle.LocalStore.SlayerTask;
import chronicle.LocalStore.UntakenRow;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.function.BooleanSupplier;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import static chronicle.Feed.*;
import static chronicle.Pictures.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;

final class SlayerScreen
{
	private final ChroniclePanel ui;
	private final Board board;
	private final ChroniclePlugin plugin;
	private final Period period;

	SlayerScreen(ChroniclePanel ui, Board board)
	{
		this.ui = ui;
		this.board = board;
		this.plugin = board.plugin;
		this.period = board.period;
	}

	JPanel buildSlayer()
	{
		JPanel p = column();
		ChronicleEventCapture.SlayerView task = plugin.slayerView();
		if (task != null)
		{
			addTaskCard(p, task, "Current task", ACCENT);
		}

		JPanel lens = new JPanel(new GridLayout(1, 3, 3, 3));
		lens.setBackground(DARK);
		for (String l : new String[]{"Tasks", "Monsters", "Drops"})
		{
			lens.add(pill(l, l.equals(slayerLens), 4, null, () ->
			{
				slayerLens = l;
				ui.rebuildInPlace();
			}));
		}
		spaced(p, lens);

		if (!journeyFetching)
		{
			journeyFetching = true;
			plugin.fetchSlayerJourney(j -> SwingUtilities.invokeLater(() ->
			{
				journeyFetching = false;
				if (j == null)
				{
					return;
				}
				boolean moved = Board.journeyMoved(journeyCache, j);
				journeyCache = j;
				if (moved && ui.view == ChroniclePanel.View.SLAYER && "Tasks".equals(slayerLens))
				{
					ui.rebuildInPlace();
				}
			}));
		}
		if ("Monsters".equals(slayerLens))
		{
			return addKillLog(p);
		}
		if ("Drops".equals(slayerLens))
		{
			return addOnTaskLoot(p);
		}

		if (journeyCache != null)
		{
			addJourney(p, journeyCache);
		}
		else
		{
			p.add(note("Reading the task journey from the journal…"));
		}
		return p;
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
		link(card, this::openCurrentTask);
		spaced(p, card);
	}

	void openCurrentTask()
	{
		ChronicleEventCapture.SlayerView live = plugin.slayerView();
		if (live == null)
		{
			return;
		}
		SlayerJourney journey = journeyCache;
		if (journey == null)
		{
			plugin.fetchSlayerJourney(read -> SwingUtilities.invokeLater(() ->
			{
				if (read != null)
				{
					journeyCache = read;
					openCurrentTask();
				}
			}));
			return;
		}
		int at = -1;
		for (int i = 0; i < journey.tasks.size(); i++)
		{
			SlayerTask t = journey.tasks.get(i);
			if (t.inProgress && t.task.equalsIgnoreCase(live.task))
			{
				at = i;
				break;
			}
		}
		ui.applyTab(ChroniclePanel.View.SLAYER);
		if (at >= 0)
		{
			ui.showTask(at);
		}
	}

	String slayerLens = "Tasks";

	int slayerShown = ROW_CAP;

	JPanel addOnTaskLoot(JPanel p)
	{
		long[] ms = board.windowMs();
		final List<BagItem> bag = plugin.onTaskLoot(ms[0], ms[1], ui.loot.lootTask,
			period.whole());
		if (bag.isEmpty())
		{
			p.add(taskPicker());
			return noted(p, ui.loot.lootTask != null
				? board.inside("No loot logged on " + ui.loot.lootTask)
				: period.whole()
					? "No task loot in the journal yet. It collects as tasks close."
					: board.inside("No task loot"));
		}
		final long[] sum = board.tallyOf(bag);
		final long qty = sum[0];
		final long value = sum[1];

		if (ui.loot.lootKind != null)
		{
			p.add(taskPicker());
			return ui.loot.kindDrill(p, bag, "task:");
		}
		final long[] tally = plugin.onTaskTally(ms[0], ms[1], ui.loot.lootTask, period.whole());
		spaced(p, onTaskHead(qty, value, tally));
		p.add(taskPicker());

		LinkedHashMap<String, BooleanSupplier> ways =
			new LinkedHashMap<>();
		ways.put("These kinds", () -> copyPicture(kindsPicture(bag, qty, value, tally)));
		ways.put("Every item", () -> copyPicture(
			ui.loot.lootPicture(ui.loot.lootTask == null ? "On-task loot" : ui.loot.lootTask, bag,
				new long[]{qty, value}, false), true));
		p.add(ui.copyHeader("Drops", ways));
		ui.loot.addKindRows(p, bag);
		return p;
	}

	JPanel kindsPicture(List<BagItem> bag, long qty, long value,
		long[] tally)
	{
		JPanel page = column();
		spaced(page, onTaskHead(qty, value, tally));
		if (ui.loot.lootTask != null)
		{
			spaced(page, row("Task", ui.loot.lootTask, ACCENT), 4);
		}
		ui.loot.addKindRows(page, bag);
		return page;
	}

	JPanel addKillLog(JPanel p)
	{
		List<Entry<String, Long>> kcs = new ArrayList<>(LocalStore.killLogCounts(board.clogNow()).entrySet());
		if (kcs.isEmpty())
		{
			return noted(p, "No kill log yet. It copies itself the next time you open "
				+ "the Slayer Kill Log in game.");
		}
		kcs.sort(Entry.<String, Long>comparingByValue().reversed());
		JPanel card = card("Kill log");
		final int cap = ui.drillShown.getOrDefault("killlog", ROW_CAP);
		for (Entry<String, Long> e : firstN(kcs, cap))
		{
			JPanel r = row(e.getKey(), fmt(e.getValue()));
			final String mob = e.getKey();
			link(r, () -> ui.openSourceLoose(mob));
			card.add(r);
		}
		ui.drillMore(card, "killlog", kcs.size(), cap);
		p.add(card);
		return p;
	}

	void addTaskAgainstRecord(JPanel head, SlayerJourney j, int index,
		SlayerTask t)
	{
		int earlier = 0;
		long sumValue = 0;
		long sumKills = 0;
		int bestAt = -1;
		for (int i = 0; i < j.tasks.size(); i++)
		{
			SlayerTask o = j.tasks.get(i);
			if (i == index || o.inProgress || o.ts >= t.ts || !o.task.equalsIgnoreCase(t.task))
			{
				continue;
			}
			earlier++;
			sumValue += o.totalValue;
			sumKills += o.kills;
			if (bestAt < 0 || o.totalValue > j.tasks.get(bestAt).totalValue)
			{
				bestAt = i;
			}
		}
		if (earlier < 2)
		{
			return;
		}
		head.add(row("Usual", gp(sumValue / earlier) + " gp · " + fmt(sumKills / earlier)
			+ " kills · over " + earlier + " tasks"));
		SlayerTask best = j.tasks.get(bestAt);
		JPanel bestRow = row("Best", gp(best.totalValue) + " gp · "
			+ day((long) (best.ts * 1000)));
		final int at = bestAt;
		link(bestRow, () -> ui.showTask(at));
		head.add(bestRow);
	}

	JPanel buildTaskDetail(int index)
	{
		JPanel p = column();
		p.add(ui.backRow("< Back", "", () ->
		{
			ui.detailTask = -1;
			ui.rebuild();
		}));
		p.add(vgap(4));
		SlayerJourney j = journeyCache;
		SlayerTask t = j != null && index >= 0 && index < j.tasks.size()
			? j.tasks.get(index) : null;
		if (t == null)
		{
			return noted(p, "That task is no longer in the journal.");
		}
		JPanel head = card(t.task.toUpperCase(Locale.ROOT));
		String kills = t.inProgress && t.assignment > t.kills
			? fmt(t.kills) + " / " + fmt(t.assignment) : fmt(t.kills);
		head.add(row("Kills logged", kills, ACCENT));
		if (t.noLootKills > 0)
		{
			head.add(row("Killed without loot", fmt(t.noLootKills)));
		}
		head.add(worthRow(t.totalValue));
		if (t.ts > 0)
		{
			head.add(row(t.inProgress ? "Started" : "Finished",
				day((long) (t.ts * 1000))));
		}
		addTaskAgainstRecord(head, j, index, t);
		spaced(p, head);

		List<UntakenRow> monsters = plugin.slayerTaskMonsters(index);
		if (!monsters.isEmpty())
		{
			p.add(group("Killed"));
			for (UntakenRow m : monsters)
			{
				JPanel r = row(m.name, "×" + fmt(m.qty));
				link(r, () -> ui.openSourceLoose(m.name));
				p.add(r);
			}
			p.add(vgap(6));
		}

		List<BagItem> bag = plugin.slayerTaskItems(index);
		if (bag.isEmpty())
		{
			p.add(note("No loot recorded against this task."));
		}
		else
		{
			p.add(group("Loot from this task"));
			for (BagItem it : bag)
			{
				JPanel r = row(named(it.name, it.qty),
					gps(it.value));
				link(r, () -> ui.openItem(it.name));
				p.add(r);
			}
		}
		p.add(vgap(8));
		JPanel all = row("All kills of " + t.task, "", ACCENT, true);
		link(all, () ->
		{
			ui.detailTask = -1;
			ui.openSourceLoose(t.task);
		});
		p.add(all);
		return p;
	}

	void addJourney(JPanel p, SlayerJourney j)
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
			if (board.insideWindow((long) (t.ts * 1000)) && (period.whole() || !t.inProgress))
			{
				shown.add(t);
				where.add(i);
			}
		}
		if (shown.isEmpty() && !period.whole())
		{
			p.add(note(board.inside("No tasks closed")));
			return;
		}
		long tasksDone = j.completedTasks;
		long killsOnTask = j.totalKills;
		long onTaskLoot = j.totalValueGp;
		if (!period.whole())
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
		head.add(row("Tasks done", fmt(tasksDone), ACCENT));
		head.add(row("Kills on task", fmt(killsOnTask)));
		head.add(row("On-task loot", gps(onTaskLoot)));
		if (j.totalXpEst > 0)
		{
			head.add(row("Slayer xp (est.)", gp(j.totalXpEst)));
		}
		spaced(p, head);
		for (int k = 0; k < Math.min(shown.size(), slayerShown); k++)
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
			listCard(p, row(t.task, t.totalValue > 0 ? gps(t.totalValue) : "", ACCENT, t.inProgress),
				kills, t.ts > 0 ? day((long) (t.ts * 1000)) : "", () -> ui.showTask(at));
		}
		if (shown.size() > slayerShown)
		{
			ui.loot.more(p, shown.size(), slayerShown, false, n -> slayerShown = n);
			p.add(vgap(4));
		}
	}

	SlayerJourney journeyCache;

	boolean journeyFetching;

	JPopupMenu taskMenu()
	{
		JPopupMenu menu = new JPopupMenu();
		ui.menuItem(menu, "Every task", ui.loot.lootTask == null, () -> pickTask(null));
		menu.addSeparator();
		for (String task : plugin.taskNames())
		{
			ui.menuItem(menu, task, task.equals(ui.loot.lootTask), () -> pickTask(task));
		}
		return menu;
	}

	void pickTask(String task)
	{
		ui.loot.lootTask = task;
		ui.loot.lootKind = null;
		ui.rebuildInPlace();
	}

	JPanel taskPicker()
	{
		JPanel r = row("Task", ui.loot.lootTask == null ? "Every task" : ui.loot.lootTask, ACCENT);
		styled(part(r, BorderLayout.CENTER), small(), DIM);
		JLabel pick = part(r, BorderLayout.EAST);
		pick.setFont(small());
		pick.setToolTipText("Narrow this board to one task");
		link(pick, () -> taskMenu().show(r, 0, r.getHeight()));
		link(r, () -> taskMenu().show(r, 0, r.getHeight()));
		return r;
	}

	JPanel onTaskHead(long qty, long value, long[] tally)
	{
		JPanel head = tallyCard("On-task loot", "Items", fmt(qty), ACCENT, value);
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
}
