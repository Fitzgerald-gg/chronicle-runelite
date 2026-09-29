/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.Board.Kind;
import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SourceRow;
import chronicle.LocalStore.UntakenRow;
import chronicle.Period.Window;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.swing.JPanel;
import static chronicle.Pictures.*;
import static chronicle.Ui.*;

final class LootScreen extends Screen
{
	private static final String NOTHING_YET = "Drops appear here as you play: every kill, priced as it lands.";

	boolean dropsLeftBehind;
	boolean dropsByKind;
	boolean onTaskOnly;
	String lootKind;
	private int dropsShown = ROW_CAP;

	LootScreen(ChroniclePanel ui, Board board)
	{
		super(ui, board);
	}

	void reset()
	{
		dropsShown = ROW_CAP;
		lootKind = null;
	}

	JPanel buildDrops()
	{
		JPanel p = column();
		List<JPanel> axes = new ArrayList<>();
		axes.add(toggle(dropsLeftBehind ? "Left behind" : "Received", relens(() -> dropsLeftBehind = !dropsLeftBehind)));
		if (!dropsLeftBehind || period.whole())
		{
			axes.add(toggle(!dropsByKind ? "By source" : dropsLeftBehind ? "By item" : "By kind",
				relens(() -> dropsByKind = !dropsByKind)));
		}
		boolean askOnTask = !dropsLeftBehind && dropsByKind && board.everOnTask();
		if (askOnTask)
		{
			axes.add(toggle(onTaskOnly ? "On task" : "All", relens(() -> onTaskOnly = !onTaskOnly)));
		}
		JPanel lens = new JPanel(new GridLayout(1, axes.size(), 3, 3));
		lens.setBackground(DARK);
		axes.forEach(lens::add);
		spaced(p, lens);
		if (askOnTask && onTaskOnly)
		{
			long[] w = board.windowMs();
			List<BagItem> bag = store.slayer.onTaskLoot(w[0], w[1], null, period.whole());
			return bag.isEmpty() ? noted(p, board.inside("No task closed"))
				: kindLens(p, period.whole() ? "On-task loot" : "Tasks closed in " + board.window().label, bag, "ontask:");
		}
		if (!period.whole())
		{
			return dropsInWindow(p);
		}
		if (dropsLeftBehind)
		{
			return leftBehind(p);
		}
		if (dropsByKind)
		{
			List<BagItem> bag = store.allLoot();
			return bag.isEmpty() ? noted(p, NOTHING_YET) : kindLens(p, "Everything dropped", bag, "all:");
		}
		List<SourceRow> sources = new ArrayList<>(board.sources());
		if (sources.isEmpty())
		{
			return noted(p, NOTHING_YET);
		}
		sources.sort(Comparator.comparingLong((SourceRow r) -> r.value).reversed());
		JPanel head = tallyCard("Drops received", "Drops", fmt(sources.stream().mapToLong(r -> r.loots).sum()), ACCENT,
			sources.stream().mapToLong(r -> r.value).sum());
		head.add(row("Sources", fmt(sources.size())));
		spaced(p, head);
		for (SourceRow r : firstN(sources, dropsShown))
		{
			boolean killed = board.isKillSource(r.name);
			String under = (killed ? fmt(board.standingKills(r)) + " kc" : count(r.loots, "drop"))
				+ (r.pb != null ? " · PB " + pb(r.pb) : "");
			listCard(p, row(r.name, gps(r.value), ACCENT), under,
				r.loots > 0 ? perOne(r.value, r.loots, killed) : "", () -> ui.openSource(r.name));
		}
		ui.more(p, sources.size(), dropsShown, false, n -> dropsShown = n);
		return p;
	}

	private JPanel dropsInWindow(JPanel p)
	{
		Window win = board.window();
		LootDays.LootWindow sitting = period.session() ? store.loot.sessionLootWindow() : null;
		long rollFrom = store.loot.lootRollFrom();
		if (sitting == null && rollFrom <= 0)
		{
			return noted(p, Board.UNDATED);
		}
		if (sitting == null && rollFrom > startMs(win.start))
		{
			return noted(p, "The dated loot roll begins " + dayOf(rollFrom).format(FULL_DAY) + ", which is inside "
				+ board.periodInSentence() + ". Naming the part it can see as the whole period would be worse than saying nothing.");
		}
		LootDays.LootWindow w = sitting != null ? sitting : store.loot.lootBetween(win.start, win.end);
		if (!dropsLeftBehind && dropsByKind)
		{
			return w.items.isEmpty() ? noted(p, board.inside("Nothing taken")) : kindLens(p, win.label, bagOf(w.items), "win:");
		}
		List<String[]> ranked = dropsLeftBehind ? w.leftItems : w.sources;
		if (ranked.isEmpty())
		{
			return noted(p, board.inside("Nothing " + (dropsLeftBehind ? "left behind" : "taken")));
		}
		JPanel head = dropsLeftBehind ? tallyCard("Left behind", "Items", fmt(w.left), RED, w.leftValue)
			: tallyCard("Drops received", "Drops", fmt(w.loots), ACCENT, w.value);
		if (dropsLeftBehind)
		{
			head.add(row("Kills that left one", fmt(w.leftKills)));
		}
		spaced(p, head);
		String key = dropsLeftBehind ? "win:left" : "win:source";
		int cap = ui.cap(key, ROW_CAP);
		for (String[] r : firstN(ranked, cap))
		{
			Runnable open = dropsLeftBehind ? () -> ui.openItem(r[0]) : () -> ui.openSourceLoose(r[0]);
			p.add(link(row(r[0], qtyGp(safeParse(r[1]), safeParse(r[2]))), open));
		}
		ui.drillMore(p, key, ranked.size(), cap);
		return p;
	}

	private static List<BagItem> bagOf(List<String[]> rows)
	{
		List<BagItem> bag = new ArrayList<>();
		rows.forEach(r -> bag.add(new BagItem(0, r[0], safeParse(r[1]), safeParse(r[2]))));
		return bag;
	}

	private JPanel leftBehind(JPanel p)
	{
		List<UntakenRow> rows = store.untakenSources();
		if (rows.isEmpty())
		{
			return noted(p, "What you walk past gets counted here, priced at the moment you declined it.");
		}
		List<UntakenRow> items = store.untakenItems();
		rows.sort(Comparator.comparingLong((UntakenRow r) -> r.value).reversed());
		items.sort(Comparator.comparingLong((UntakenRow r) -> r.value).reversed());
		boolean byItem = dropsByKind;
		JPanel head = tallyCard("Left behind", "Items", fmt(rows.stream().mapToLong(r -> r.qty).sum()), RED,
			rows.stream().mapToLong(r -> r.value).sum());
		head.add(row(byItem ? "Distinct items" : "Sources", fmt(byItem ? items.size() : rows.size())));
		spaced(p, head);
		if (byItem && items.isEmpty())
		{
			return noted(p, "Nothing walked past has been priced yet.");
		}
		List<UntakenRow> list = byItem ? items : rows;
		String key = byItem ? "left:item" : "left:source";
		int cap = ui.cap(key, ROW_CAP);
		for (UntakenRow r : firstN(list, cap))
		{
			listCard(p, row(r.name, gps(r.value), RED), byItem ? "×" + fmt(r.qty) : fmt(r.qty) + " left",
				r.qty > 0 ? perOne(r.value, r.qty, false) : "",
				() -> ui.showLeftBehind(byItem ? null : r.name, byItem ? r.name : null));
		}
		ui.drillMore(p, key, list.size(), cap);
		return p;
	}

	private JPanel kindLens(JPanel p, String title, List<BagItem> bag, String key)
	{
		if (lootKind != null)
		{
			return kindDrill(p, bag, key);
		}
		long[] sum = Board.tallyOf(bag);
		JPanel head = bagCard(title, bag, sum);
		BagItem top = most(bag, b -> b.value);
		if (top != null)
		{
			head.add(link(row("Dearest", top.name + " · " + gps(top.value)), () -> ui.openItem(top.name)));
		}
		spaced(p, head);
		Map<String, BooleanSupplier> ways = new LinkedHashMap<>();
		ways.put("These kinds", () -> copyPicture(lootPicture(title, bag, sum, true)));
		ways.put("Every item", () -> copyPicture(lootPicture(title, bag, sum, false), true));
		p.add(copyHeader("Drops", ways));
		addKindRows(p, bag);
		return p;
	}

	JPanel kindDrill(JPanel p, List<BagItem> bag, String key)
	{
		List<BagItem> kept = new ArrayList<>();
		for (BagItem b : bag)
		{
			String k = ItemKinds.kindOf(b.name);
			if (Board.UNFILED.equals(lootKind) ? k == null : lootKind.equals(k))
			{
				kept.add(b);
			}
		}
		long[] sum = Board.tallyOf(kept);
		spaced(p, bagCard(lootKind, kept, sum));
		p.add(backRow("< All kinds", count(kept.size(), "item"), relens(() -> { })));
		if (kept.isEmpty())
		{
			return noted(p, "Nothing of this kind here.");
		}
		String kind = lootKind;
		p.add(copyHeader(kind, () -> copyPicture(lootPicture(kind, kept, sum, false), true)));
		int cap = ui.cap(key + kind, ROW_CAP);
		addBagRows(p, firstN(kept, cap));
		ui.drillMore(p, key + kind, kept.size(), cap);
		return p;
	}

	void addKindRows(JPanel p, List<BagItem> bag)
	{
		for (Kind k : Board.kindsOf(bag))
		{
			JPanel r = row(k.name, qtyGp(k.qty, k.value), ACCENT);
			r.setToolTipText(count(k.distinct, "distinct item"));
			p.add(link(r, () ->
			{
				lootKind = k.name;
				ui.rebuildInPlace();
			}));
		}
	}

	void addBagRows(JPanel p, List<BagItem> bag)
	{
		bag.forEach(b -> p.add(link(row(named(b.name, b.qty), b.value > 0 ? gps(b.value) : ""), () -> ui.openItem(b.name))));
	}

	JPanel lootPicture(String title, List<BagItem> bag, long[] sum, boolean kinds)
	{
		JPanel page = column();
		spaced(page, bagCard(title, bag, sum));
		if (kinds)
		{
			addKindRows(page, bag);
		}
		else
		{
			addBagRows(page, bag);
		}
		return page;
	}

	private static JPanel bagCard(String title, List<BagItem> bag, long[] sum)
	{
		JPanel head = tallyCard(title, "Items", fmt(sum[0]), ACCENT, sum[1]);
		head.add(row("Distinct items", fmt(bag.size())));
		return head;
	}

	Runnable relens(Runnable change)
	{
		return () ->
		{
			change.run();
			lootKind = null;
			ui.rebuildInPlace();
		};
	}
}
