/*
* Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
*/
package chronicle;

import chronicle.Board.Obtained;
import chronicle.LocalStore.SourceRow;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.JPanel;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.Pictures.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;

final class PagesScreen extends Screen
{
	PagesScreen(ChroniclePanel ui, Board board)
	{
		super(ui, board);
	}

	JPanel buildSheetPage(String page)
	{
		JPanel p = ui.backPage();
		if ("log".equals(page))
		{
			p.add(buildLog());
		}
		else if ("clues".equals(page))
		{
			buildClues(p);
		}
		else if ("quests".equals(page))
		{
			buildQuests(p);
		}
		else if ("diaries".equals(page))
		{
			buildDiaries(p);
		}
		else
		{
			buildCombatAchievements(p);
		}
		return p;
	}

	private void buildClues(JPanel p)
	{
		long all = 0;
		long allWorth = 0;
		List<SourceRow> mine = new ArrayList<>();
		for (String tier : CLUE_TIERS)
		{
			SourceRow r = board.clue(tier);
			if (r != null)
			{
				mine.add(r);
				all += Math.max(r.kc, r.loots);
				allWorth += r.value;
			}
		}
		JPanel head = card("Clues");
		head.add(row("Caskets opened", fmt(all), ACCENT));
		head.add(row("Worth", gps(allWorth), ACCENT));
		head.add(row("Tiers seen", fmt(mine.size()) + " / " + CLUE_TIERS.length));
		spaced(p, head);
		if (mine.isEmpty())
		{
			p.add(note("No clue casket has been opened while Chronicle was watching."));
			return;
		}
		p.add(group("BY TIER"));
		for (String tier : CLUE_TIERS)
		{
			SourceRow r = board.clue(tier);
			if (r == null)
			{
				p.add(row(tier, "-", DIM, true));
				continue;
			}
			long n = Math.max(r.kc, r.loots);
			JPanel line = row(tier, qtyGp(n, r.value), ACCENT);
			final String open = r.name;
			link(line, () -> ui.openSource(open));
			line.setToolTipText(tip(tier + " clues",
				"Caskets", fmt(n),
				"Worth", gps(r.value),
				"Each", n > 0 ? gps(r.value / n) : "-"));
			p.add(line);
		}
	}

	private void buildQuests(JPanel p)
	{
		JsonObject q = obj(board.achievements(), "quests");
		if (q.size() == 0)
		{
			p.add(note("The quest list arrives when you next log in."));
			return;
		}
		List<String> done = new ArrayList<>();
		List<String> going = new ArrayList<>();
		List<String> not = new ArrayList<>();
		for (String name : q.keySet())
		{
			String state = q.get(name).getAsString();
			("FINISHED".equals(state) ? done : "IN_PROGRESS".equals(state) ? going : not)
				.add(name);
		}
		JPanel head = card("Quests");
		head.add(row("Complete", fmt(done.size()) + " / " + fmt(q.size()), ACCENT));
		head.add(row("In progress", fmt(going.size())));
		head.add(row("Not started", fmt(not.size())));
		spaced(p, head);
		addNames(p, "IN PROGRESS", going, true, true);
		addNames(p, "COMPLETE", done, true, false);
		addNames(p, "NOT STARTED", not, false, false);
	}

	private void addNames(JPanel p, String heading, List<String> names, boolean held,
		boolean openByDefault)
	{
		if (names.isEmpty())
		{
			return;
		}
		Collections.sort(names);
		String foldKey = "quests:" + heading;
		boolean open = ui.foldOpen(foldKey, openByDefault);
		p.add(ui.quietHead(heading, fmt(names.size()), foldKey));
		if (!open)
		{
			p.add(vgap(4));
			return;
		}
		for (String n : names)
		{
			p.add(row(n, "", held ? null : DIM, !held));
		}
		p.add(vgap(4));
	}

	private void buildDiaries(JPanel p)
	{
		JsonObject tasks = DIARY_TASKS;
		JsonObject mine = obj(board.achievements(), "diaries");
		boolean known = mine.size() > 0;
		JPanel head = card("Achievement diaries");
		if (known)
		{
			long[] d = board.diaryStanding();
			head.add(row("Tiers done", d[0] + " / " + d[1], ACCENT));
			head.add(row("Regions finished", fmt(d[2]) + " / " + fmt(d[3])));
		}
		spaced(p, head);
		if (!known)
		{
			spaced(p, note("Which tiers you have finished arrives when you next log in. "
				+ "Until then this is what each one asks for."), 4);
		}
		for (String region : tasks.keySet())
		{
			JsonObject tiers = tasks.getAsJsonObject(region);
			String key = low(region);
			JsonObject held = null;
			for (String k : mine.keySet())
			{
				if (k.equalsIgnoreCase(key) || key.startsWith(low(k)))
				{
					held = mine.getAsJsonObject(k);
					break;
				}
			}
			p.add(group(region.toUpperCase(Locale.ROOT)));
			for (String tier : new String[]{"easy", "medium", "hard", "elite"})
			{
				if (!tiers.has(tier))
				{
					continue;
				}
				int n = tiers.getAsJsonArray(tier).size();
				boolean got = held != null && held.has(tier) && held.get(tier).getAsBoolean();
				JPanel line = row(prettyTier(tier),
					fmt(n) + " tasks",
					known && !got ? DIM : null,
					known && !got);
				line.setToolTipText(taskTip(region + " " + tier,
					tiers.getAsJsonArray(tier)));
				p.add(line);
			}
			p.add(vgap(4));
		}
	}

	private static String taskTip(String title, JsonArray tasks)
	{
		final int cap = 8;
		StringBuilder sb = new StringBuilder(TIP_OPEN).append(dimLine(title));
		for (int i = 0; i < tasks.size() && i < cap; i++)
		{
			JsonObject t = tasks.get(i).getAsJsonObject();
			sb.append("<div>").append(clip(t.get("task").getAsString(), 78)).append("</div>");
			String needs = t.has("requirements") ? t.get("requirements").getAsString() : "";
			if (!needs.isEmpty())
			{
				sb.append(dimLine("&nbsp;&nbsp;" + clip(needs, 70)));
			}
		}
		if (tasks.size() > cap)
		{
			sb.append(dimLine("and " + (tasks.size() - cap) + " more"));
		}
		return sb.append(TIP_CLOSE).toString();
	}

	private void buildCombatAchievements(JPanel p)
	{
		JsonObject all = CA_TASKS;
		long[] c = board.combatStanding();
		JPanel head = card("Combat achievements");
		head.add(row("Points", c[1] > 0 ? fmt(c[0]) + " / " + fmt(c[1]) : fmt(c[0]),
			ACCENT));
		head.add(row("Tiers unlocked", fmt(c[2]) + " / 6"));
		Set<Integer> done = board.caDone();
		boolean known = !done.isEmpty();
		long named = done.stream().filter(id -> all.has(String.valueOf(id))).count();
		long unnamed = done.size() - named;
		if (known)
		{
			head.add(row("Tasks done", fmt(named) + " / " + fmt(all.size()), ACCENT));
		}
		spaced(p, head);
		if (unnamed > 0)
		{
			spaced(p, note("You have also done " + count(unnamed, "combat achievement")
				+ " added to the game since this copy of"
				+ " Chronicle was built. They are counted by the game, not named"
				+ " here, until the plugin updates."), 4);
		}
		if (!known)
		{
			spaced(p, note("Which tasks you have done arrives when you next log in. "
				+ "Until then this is what each tier asks for."), 4);
		}
		Map<String, List<JsonObject>> bySource = new TreeMap<>(
			String.CASE_INSENSITIVE_ORDER);
		for (String id : all.keySet())
		{
			JsonObject task = all.getAsJsonObject(id).deepCopy();
			task.addProperty("id", Integer.parseInt(id));
			bySource.computeIfAbsent(caSource(task.get("monster").getAsString()),
				k -> new ArrayList<>()).add(task);
		}
		for (Entry<String, List<JsonObject>> e : bySource.entrySet())
		{
			long got = e.getValue().stream().filter(t -> done.contains(t.get("id").getAsInt())).count();
			String foldKey = "ca:" + e.getKey();
			boolean open = ui.foldOpen(foldKey);
			int n = e.getValue().size();
			p.add(ui.quietHead(e.getKey(), known
				? fmt(got) + " / " + fmt(n)
				: count(n, "task"), foldKey));
			if (!open)
			{
				continue;
			}
			for (JsonObject task : e.getValue())
			{
				boolean has = known && done.contains(task.get("id").getAsInt());
				JPanel line = row(withoutSource(task.get("name").getAsString(), e.getKey()),
					prettyTier(task.get("tier").getAsString()),
					known ? (has ? GREEN : RED) : null, known);
				line.setToolTipText(tip(task.get("name").getAsString(),
					"Tier", task.get("tier").getAsString(),
					"Where", caSource(task.get("monster").getAsString()),
					"Task", task.get("task").getAsString()));
				p.add(line);
			}
			p.add(vgap(4));
		}
	}

	private static String withoutSource(String name, String source)
	{
		if (name == null || source == null || name.length() <= source.length()
			|| !name.regionMatches(true, 0, source, 0, source.length()))
		{
			return name;
		}
		String rest = name.substring(source.length()).trim();
		return rest.isEmpty() ? name : rest;
	}

	private static String caSource(String monster)
	{
		return monster == null || monster.trim().isEmpty()
			|| "N/A".equalsIgnoreCase(monster.trim()) ? "Anywhere" : monster;
	}

	private JPanel buildLog()
	{
		JPanel p = column();
		if (!period.whole())
		{
			return logInWindow(p);
		}
		spaced(p, logHead());
		Map<String, Map<String, List<String>>> tax = taxonomy(plugin.gson());
		JPanel pills = new JPanel(new GridLayout(0, 3, 3, 3));
		pills.setBackground(DARK);
		for (String tab : tax.keySet())
		{
			pills.add(pill(tab, tab.equals(clogTab), 4, board.tabStanding(board.clogNow(), tab), () ->
			{
				clogTab = tab;
				clogPageSel = null;
				ui.rebuild();
			}));
		}
		spaced(p, pills);
		JsonObject cl = board.clogNow();
		Obtained ob = Board.obtained(cl);
		Map<String, Long> kcs = Board.pageCounts(cl);
		for (Entry<String, List<String>> pg : tax.getOrDefault(clogTab, new LinkedHashMap<>()).entrySet())
		{
			logPage(p, cl, ob, kcs, pg.getKey(), pg.getValue());
		}
		if ("Other".equals(clogTab))
		{
			strangers(p, tax, ob, kcs);
		}
		return p;
	}

	private JPanel logHead()
	{
		int[] standing = board.clogStanding();
		int fin = plugin.clogFinished();
		JPanel head = card("Collection log");
		if (standing != null)
		{
			head.add(row(fmt(standing[0]) + " / " + fmt(standing[1]),
				Math.round(100f * standing[0] / standing[1]) + "%", ACCENT));
			head.add(progress((float) standing[0] / standing[1]));
		}
		else if (fin > 0)
		{
			head.add(row("Slots obtained", fmt(fin), ACCENT));
			head.add(row("Open your log in game once for the total", ""));
		}
		else
		{
			head.add(row("Open your log in game once to fill this in", ""));
		}
		return head;
	}

	private void logPage(JPanel p, JsonObject cl, Obtained ob, Map<String, Long> kcs, String page, List<String> slots)
	{
		boolean[] lit = Board.lightSlots(slots, ob.byPage.get(low(page)), ob.all, sharedSlotNames(plugin.gson()));
		int got = 0;
		for (boolean b : lit)
		{
			got += b ? 1 : 0;
		}
		Long kc = kcs.get(low(page));
		boolean open = page.equals(clogPageSel);
		boolean complete = got == slots.size() && !slots.isEmpty();
		JPanel head = row(page, got + "/" + slots.size() + (kc != null && kc > 0 ? " · " + fmt(kc) + " kc" : ""),
			complete ? GREEN : null, complete);
		String lines = Board.pageHeaderTip(cl, page);
		if (lines != null)
		{
			head.setToolTipText(lines);
		}
		link(head, () ->
		{
			clogPageSel = open ? null : page;
			ui.rebuild();
		});
		p.add(head);
		if (open)
		{
			spaced(p, slotCard(page, slots, lit), 3);
		}
	}

	private JPanel slotCard(String page, List<String> slots, boolean[] lit)
	{
		JPanel drill = cardPlain();
		boolean pets = low(page).contains("pet");
		Map<String, LocalStore.PetRow> known = pets ? board.petsByName() : Collections.emptyMap();
		List<List<JPanel>> detail = new ArrayList<>();
		for (int i = 0; i < slots.size(); i++)
		{
			String key = low(slots.get(i));
			detail.add(petDetail(known.get(key)));
		}
		Map<String, Long> landed = board.landedSlots();
		for (int i = 0; i < slots.size(); i++)
		{
			String slot = slots.get(i);
			JPanel r = row(slot, "", lit[i] || known.get(low(slot)) != null ? GREEN : RED, true);
			Long when = landed.get(low(slot));
			if (when != null)
			{
				r.setToolTipText(tip(slot, "Landed", dated(when)));
			}
			drill.add(r);
			if (detail.get(i).isEmpty())
			{
				continue;
			}
			String fold = "pets:" + page + ":" + low(slot);
			ui.folds(r, fold);
			if (ui.foldOpen(fold))
			{
				detail.get(i).forEach(drill::add);
			}
		}
		return drill;
	}

	private void strangers(JPanel p, Map<String, Map<String, List<String>>> tax, Obtained ob, Map<String, Long> kcs)
	{
		Set<String> known = new HashSet<>();
		tax.values().forEach(pages -> pages.keySet().forEach(n -> known.add(low(n))));
		List<String> strangers = new ArrayList<>();
		ob.byPage.keySet().stream().filter(name -> !known.contains(name)).forEach(strangers::add);
		if (strangers.isEmpty())
		{
			return;
		}
		Collections.sort(strangers);
		p.add(vgap(6));
		p.add(group("NEW SINCE THIS RELEASE"));
		for (String name : strangers)
		{
			Map<String, Long> held = ob.byPage.get(name);
			Long kc = kcs.get(name);
			p.add(row(prettyPage(name), fmt(held == null ? 0 : held.size()) + " held"
				+ (kc != null && kc > 0 ? " · " + fmt(kc) + " kc" : "")));
		}
		p.add(ghostRow("Chronicle has no slot list for " + (strangers.size() == 1 ? "this page" : "these pages")
			+ " yet, so only what you hold is known.", ""));
	}

	private static List<JPanel> petDetail(LocalStore.PetRow pet)
	{
		List<JPanel> out = new ArrayList<>();
		if (pet != null)
		{
			StringBuilder line = new StringBuilder();
			if (pet.source != null && !pet.source.isEmpty())
			{
				line.append(pet.source);
				if (pet.kc > 0)
				{
					line.append(skill(pet.source.toUpperCase(Locale.ROOT)) != null
						? ", " + fmt(pet.kc) + " xp"
						: ", kc " + fmt(pet.kc));
				}
			}
			if (line.length() > 0)
			{
				out.add(ghostRow(line.toString(), pet.ts > 0
					? day(pet.ts) : ""));
			}
		}
		return out;
	}

	private JPanel logInWindow(JPanel p)
	{
		List<JsonObject> got = new ArrayList<>();
		store.feedNewest(Board.FEED_SCAN_DEEP).stream().filter(e -> "COLLECTION".equals(typeOf(e)) && board.insideWindow(asLong(e.get("ts"))))
			.forEach(got::add);
		if (got.isEmpty())
		{
			return noted(p, board.inside("Nothing new was logged"));
		}
		JPanel head = card("Collection log");
		head.add(row("Slots logged", fmt(got.size()), ACCENT));
		spaced(p, head);
		for (JsonObject e : got)
		{
			JsonObject d = obj(e, "data");
			final String name = str(d, "itemName", "new item");
			JPanel line = row(name, stamp(e));
			link(line, () -> ui.openItem(name));
			p.add(line);
		}
		return p;
	}

	String clogTab = "Bosses";

	String clogPageSel;
}
