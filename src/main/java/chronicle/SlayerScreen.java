/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.UntakenRow;
import chronicle.SlayerLog.SlayerJourney;
import chronicle.SlayerLog.SlayerTask;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import static chronicle.Pictures.*;
import static chronicle.Ui.*;

final class SlayerScreen extends Screen
{
	private static final String[] LENSES = {"Tasks", "Monsters", "Drops"};

	String slayerLens = LENSES[0];
	private int slayerShown = ROW_CAP;
	private String taskFilter;
	SlayerJourney journeyCache;
	private boolean journeyFetching;

	void reset()
	{
		slayerShown = ROW_CAP;
		taskFilter = null;
	}

	void forget()
	{
		journeyCache = null;
		journeyFetching = false;
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
		for (String l : LENSES)
		{
			lens.add(pill(l, l.equals(slayerLens), 4, null, () ->
			{
				slayerLens = l;
				ui.rebuildInPlace();
			}));
		}
		spaced(p, lens);
		fetchJourney(false);
		switch (slayerLens)
		{
			case "Monsters":
				return killLog(p);
			case "Drops":
				return onTaskLoot(p);
			default:
				return journeyCache == null ? noted(p, "Reading the task journey from the journal…") : journey(p, journeyCache);
		}
	}

	void fetchJourney(boolean forSearch)
	{
		if (journeyFetching)
		{
			return;
		}
		journeyFetching = true;
		plugin.fetchSlayerJourney(j -> SwingUtilities.invokeLater(() ->
		{
			journeyFetching = false;
			if (j == null)
			{
				return;
			}
			SlayerJourney was = forSearch && journeyCache == null ? board.historyJourney : journeyCache;
			journeyCache = j;
			boolean shown = forSearch ? !ui.searchQuery().isEmpty()
				: ui.view == ChroniclePanel.View.SLAYER && "Tasks".equals(slayerLens);
			if (shown && Board.journeyMoved(was, j))
			{
				ui.rebuildInPlace();
			}
		}));
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
		spaced(p, link(card, this::openCurrentTask));
	}

	private void openCurrentTask()
	{
		ChronicleEventCapture.SlayerView live = plugin.slayerView();
		if (live == null)
		{
			return;
		}
		if (journeyCache == null)
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
		ui.show(ChroniclePanel.View.SLAYER);
		for (int i = 0; i < journeyCache.tasks.size(); i++)
		{
			SlayerTask t = journeyCache.tasks.get(i);
			if (t.inProgress && t.task.equalsIgnoreCase(live.task))
			{
				ui.showTask(i);
				return;
			}
		}
	}

	private JPanel onTaskLoot(JPanel p)
	{
		Range ms = board.range();
		List<BagItem> bag = store.slayer.onTaskLoot(ms.from, ms.to, taskFilter, period.whole());
		if (bag.isEmpty() || ui.loot.lootKind != null)
		{
			p.add(taskPicker());
			return !bag.isEmpty() ? ui.loot.kindDrill(p, bag, "task:")
				: noted(p, taskFilter != null ? board.inside("No loot logged on " + taskFilter)
				: period.whole() ? "No task loot in the journal yet. It collects as tasks close."
				: board.inside("No task loot"));
		}
		Tally sum = Tally.of(bag);
		SlayerLog.TaskTally tally = store.slayer.onTaskTally(ms.from, ms.to, taskFilter, period.whole());
		spaced(p, onTaskHead(sum, tally));
		p.add(taskPicker());
		Map<String, BooleanSupplier> ways = new LinkedHashMap<>();
		ways.put("These kinds", () -> copyPicture(kindsPicture(bag, sum, tally)));
		ways.put("Every item", () -> copyPicture(ui.loot.lootPicture(taskFilter == null ? "On-task loot" : taskFilter,
			bag, sum, false), true));
		p.add(copyHeader("Drops", ways));
		ui.loot.addKindRows(p, bag);
		return p;
	}

	private JPanel kindsPicture(List<BagItem> bag, Tally sum, SlayerLog.TaskTally tally)
	{
		JPanel page = column();
		spaced(page, onTaskHead(sum, tally));
		if (taskFilter != null)
		{
			spaced(page, row("Task", taskFilter, ACCENT), 4);
		}
		ui.loot.addKindRows(page, bag);
		return page;
	}

	private static JPanel onTaskHead(Tally sum, SlayerLog.TaskTally tally)
	{
		JPanel head = tallyCard("On-task loot", "Items", fmt(sum.qty), ACCENT, sum.value);
		head.add(row("Tasks", fmt(tally.tasks)));
		if (tally.kills > 0)
		{
			head.add(row("Kills logged", fmt(tally.kills)));
		}
		if (tally.superiors > 0)
		{
			head.add(row("Superiors", fmt(tally.superiors)));
		}
		return head;
	}

	private JPanel taskPicker()
	{
		JPanel r = row("Task", taskFilter == null ? "Every task" : taskFilter, ACCENT);
		styled(part(r, BorderLayout.CENTER), small(), DIM);
		JLabel pick = part(r, BorderLayout.EAST);
		pick.setFont(small());
		pick.setToolTipText("Narrow this board to one task");
		Runnable menu = () ->
		{
			JPopupMenu m = new JPopupMenu();
			menuItem(m, "Every task", taskFilter == null, () -> pickTask(null));
			m.addSeparator();
			store.slayer.taskNames().forEach(t -> menuItem(m, t, t.equals(taskFilter), () -> pickTask(t)));
			m.show(r, 0, r.getHeight());
		};
		link(pick, menu);
		return link(r, menu);
	}

	private void pickTask(String task)
	{
		taskFilter = task;
		ui.loot.lootKind = null;
		ui.rebuildInPlace();
	}

	private JPanel killLog(JPanel p)
	{
		List<Map.Entry<String, Long>> kcs = new ArrayList<>(KillCounts.killLogCounts(board.clogNow()).entrySet());
		if (kcs.isEmpty())
		{
			return noted(p, "No kill log yet. It copies itself the next time you open the Slayer Kill Log in game.");
		}
		kcs.sort(Map.Entry.<String, Long>comparingByValue().reversed());
		JPanel card = card("Kill log");
		ui.capped(card, "killlog", ui.cap("killlog", ROW_CAP), kcs,
			e -> card.add(link(row(e.getKey(), fmt(e.getValue())), () -> ui.openSourceLoose(e.getKey()))));
		p.add(card);
		return p;
	}

	private JPanel journey(JPanel p, SlayerJourney j)
	{
		if (j.tasks.isEmpty() && j.completedTasks == 0)
		{
			return noted(p, "No tasks in the journal yet. They collect as you play with the Slayer plugin on.");
		}
		List<Integer> shown = new ArrayList<>();
		long done = 0;
		long kills = 0;
		long loot = 0;
		for (int i = 0; i < j.tasks.size(); i++)
		{
			SlayerTask t = j.tasks.get(i);
			if (board.insideWindow((long) (t.ts * 1000)) && (period.whole() || !t.inProgress))
			{
				shown.add(i);
				done += t.inProgress ? 0 : 1;
				kills += t.kills;
				loot += t.totalValue;
			}
		}
		if (shown.isEmpty() && !period.whole())
		{
			return noted(p, board.inside("No tasks closed"));
		}
		JPanel head = card("The journey");
		head.add(row("Tasks done", fmt(period.whole() ? j.completedTasks : done), ACCENT));
		head.add(row("Kills on task", fmt(period.whole() ? j.totalKills : kills)));
		head.add(row("On-task loot", gps(period.whole() ? j.totalValueGp : loot)));
		if (j.totalXpEst > 0)
		{
			head.add(row("Slayer xp (est.)", gp(j.totalXpEst)));
		}
		spaced(p, head);
		for (int at : firstN(shown, slayerShown))
		{
			SlayerTask t = j.tasks.get(at);
			String said = (t.inProgress && t.assignment > t.kills ? fmt(t.kills) + " / " + fmt(t.assignment)
				: count(t.kills, "kill")) + (t.noLootKills > 0 ? " · " + fmt(t.noLootKills) + " no-drop" : "");
			listCard(p, row(t.task, t.totalValue > 0 ? gps(t.totalValue) : "", ACCENT, t.inProgress),
				said, t.ts > 0 ? day((long) (t.ts * 1000)) : "", () -> ui.showTask(at));
		}
		if (shown.size() > slayerShown)
		{
			ui.more(p, shown.size(), slayerShown, false, n -> slayerShown = n);
			p.add(vgap(4));
		}
		return p;
	}

	JPanel buildTaskDetail(int index)
	{
		JPanel p = column();
		p.add(ui.backRow(null));
		p.add(vgap(4));
		SlayerJourney j = journeyCache;
		SlayerTask t = j != null && index >= 0 && index < j.tasks.size() ? j.tasks.get(index) : null;
		if (t == null)
		{
			return noted(p, "That task is no longer in the journal.");
		}
		JPanel head = card(t.task.toUpperCase(Locale.ROOT));
		head.add(row("Kills logged", t.inProgress && t.assignment > t.kills
			? fmt(t.kills) + " / " + fmt(t.assignment) : fmt(t.kills), ACCENT));
		if (t.noLootKills > 0)
		{
			head.add(row("Killed without loot", fmt(t.noLootKills)));
		}
		head.add(worthRow(t.totalValue));
		if (t.ts > 0)
		{
			head.add(row(t.inProgress ? "Started" : "Finished", day((long) (t.ts * 1000))));
		}
		againstRecord(head, j, index, t);
		spaced(p, head);
		List<UntakenRow> monsters = store.slayer.slayerTaskMonsters(index);
		if (!monsters.isEmpty())
		{
			p.add(group("Killed"));
			monsters.forEach(m -> p.add(link(row(m.name, "×" + fmt(m.qty)), () -> ui.openSourceLoose(m.name))));
			p.add(vgap(6));
		}
		List<BagItem> bag = store.slayer.slayerTaskItems(index);
		if (bag.isEmpty())
		{
			p.add(note("No loot recorded against this task."));
		}
		else
		{
			p.add(group("Loot from this task"));
			bag.forEach(it -> p.add(link(row(named(it.name, it.qty), gps(it.value)), () -> ui.openItem(it.name))));
		}
		p.add(vgap(8));
		p.add(link(row("All kills of " + t.task, "", ACCENT, true), () -> ui.openSourceLoose(t.task)));
		return p;
	}

	private void againstRecord(JPanel head, SlayerJourney j, int index, SlayerTask t)
	{
		int earlier = 0;
		long value = 0;
		long kills = 0;
		int best = -1;
		for (int i = 0; i < j.tasks.size(); i++)
		{
			SlayerTask o = j.tasks.get(i);
			if (i != index && !o.inProgress && o.ts < t.ts && o.task.equalsIgnoreCase(t.task))
			{
				earlier++;
				value += o.totalValue;
				kills += o.kills;
				best = best < 0 || o.totalValue > j.tasks.get(best).totalValue ? i : best;
			}
		}
		if (earlier < 2)
		{
			return;
		}
		head.add(row("Usual", gp(value / earlier) + " gp · " + fmt(kills / earlier) + " kills · over " + earlier + " tasks"));
		SlayerTask top = j.tasks.get(best);
		int at = best;
		head.add(link(row("Best", gp(top.totalValue) + " gp · " + day((long) (top.ts * 1000))), () -> ui.showTask(at)));
	}
}
