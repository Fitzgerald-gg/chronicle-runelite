/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Test;

/**
 * Walks the panel over a REAL journal and reports what it finds wrong.
 *
 * <p>The unit tests prove the logic somebody thought to test. Two bugs a player
 * found within an hour of use were of kinds none of them looked for: a board
 * that came back empty on a day the record plainly held, and a door that
 * opened a different monster's page. This looks for those kinds everywhere, on
 * the journal the panel will actually be read against.
 *
 * <p>Off by default. {@code -Dchronicle.audit=<dir>} writes the report there;
 * {@code -Dchronicle.auditJournal=<dir>} and {@code -Dchronicle.auditRsn=<name>}
 * choose the journal (this machine's own by default).
 */
public class AuditCrawlTest
{
	private static final int DOOR_BUDGET = 30000;
	private static final int DOORS_PER_STATE = 250;

	// the Mage Training Arena's Infinity robes are items, not a division by zero
	private static final Pattern NULL = Pattern.compile(
		"\\bnull\\b|NaN|Infinity(?! (hat|top|bottoms|boots|gloves))");
	private static final Pattern NEGATIVE = Pattern.compile("(?:^|[\\s(+·])-\\d");
	// an item's own name carries its dose, "Weapon poison(++)"
	private static final Pattern SIGN_JUNK = Pattern.compile("(?<!\\()\\+-|\\+ -|-\\+|(?<![(+])\\+\\+(?!\\))");
	// the registry's own sub-row labels open on a dot, "· by melee"
	private static final Pattern DOT_JUNK = Pattern.compile("· ·|··|^·(?! by )|·$");
	private static final Pattern RAW_NUMBER = Pattern.compile("(?<![\\d,.])\\d{5,}(?![\\d,.])");
	private static final Pattern PLURAL = Pattern.compile(
		"(?<![\\d,.])1 (sittings|drops|days|kills|tasks|doses|items|sources|slots|pets|levels|deaths"
			+ "|meals|quests|diaries|hours|minutes)\\b");
	private static final Pattern MID_CAPITAL = Pattern.compile("\\b(inside|in|over|for|of) This \\w");
	private static final Pattern BAIL = Pattern.compile(
		"Nothing |fewer than two|imported past|Reading your|has no drops|did not load|No boss|"
			+ "not yet|no skill breakdown|No tasks|No kill log");

	private ChroniclePanel panel;
	private PanelPreviewTest.StubPlugin stub;
	private String[] stateFields;
	private final Map<String, Field> fields = new LinkedHashMap<>();

	private final Map<String, JsonObject> states = new LinkedHashMap<>();
	private final List<JsonObject> findings = new ArrayList<>();
	private final Set<String> seenFinding = new LinkedHashSet<>();
	private int presses;
	private boolean beforeBaseline;
	// boss cells with kills whose page holds no loot: most are kills from before
	// the loot was recorded, which only a reader can tell from loot filed under
	// another name, so they are listed for review rather than reported
	private final Set<String> review = new LinkedHashSet<>();

	@Test
	public void crawl() throws Exception
	{
		String out = System.getProperty("chronicle.audit");
		if (out == null)
		{
			return;
		}
		String dir = System.getProperty("chronicle.auditJournal",
			System.getProperty("user.home") + "/.runelite/chronicle");
		String rsn = System.getProperty("chronicle.auditRsn", "Stroke Devil");
		System.setProperty("java.awt.headless", "true");
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(new net.runelite.client.ui.laf.RuneLiteLAF());
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		Field sf = ExampleExportTest.class.getDeclaredField("STATE_FIELDS");
		sf.setAccessible(true);
		stateFields = (String[]) sf.get(null);

		for (int pass = 0; pass < 3; pass++)
		{
			boolean sitting = pass == 1;
			stub = PanelPreviewTest.journalStub(dir, rsn);
			if (sitting)
			{
				// a sitting in progress, the length of the owner's last one
				stub.sessionStartMs = System.currentTimeMillis() - 20 * 60_000L;
				stub.sessionElapsed = 20;
			}
			if (pass == 2)
			{
				// Today with no baseline of its own, which is how the panel reads
				// every hour of play before logout; a journal audited after logout
				// already has the line, and hid the blank-today bug for that reason.
				stub.history.remove(LocalDate.now());
				beforeBaseline = true;
			}
			final ChroniclePanel[] hold = new ChroniclePanel[1];
			SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
			panel = hold[0];
			fields.clear();
			PanelPreviewTest.regatherHistory(panel);
			sweep(sitting);
		}
		crossChecks();

		File o = new File(out);
		o.mkdirs();
		JsonObject doc = new JsonObject();
		doc.addProperty("states", states.size());
		doc.addProperty("presses", presses);
		com.google.gson.JsonArray fs = new com.google.gson.JsonArray();
		findings.forEach(fs::add);
		doc.add("findings", fs);
		com.google.gson.JsonArray rv = new com.google.gson.JsonArray();
		review.forEach(rv::add);
		doc.add("review", rv);
		Files.write(new File(o, "audit.json").toPath(), new GsonBuilder().setPrettyPrinting()
			.create().toJson(doc).getBytes(StandardCharsets.UTF_8));
		Map<String, Integer> byKind = new TreeMap<>();
		for (JsonObject f : findings)
		{
			byKind.merge(f.get("kind").getAsString(), 1, Integer::sum);
		}
		System.out.println("AUDIT states=" + states.size() + " presses=" + presses
			+ " findings=" + findings.size() + " " + byKind + " review=" + review.size());
	}

	// ------------------------------------------------------------------
	// the walk
	// ------------------------------------------------------------------

	/** One board: the tab and sub-tab its pill opens, and the lens fields set on it. */
	private static final class Board
	{
		final String name;
		final String tab;
		final String sub;
		final Object[] lens;

		Board(String name, String tab, String sub, Object... lens)
		{
			this.name = name;
			this.tab = tab;
			this.sub = sub;
			this.lens = lens;
		}
	}

	private List<Board> boards()
	{
		List<Board> b = new ArrayList<>();
		b.add(new Board("Now", "RECORD", "Now"));
		for (String lens : new String[]{"All", "Log", "Slayer", "Feats", "Deaths", "Sessions"})
		{
			b.add(new Board("Journal:" + lens, "RECORD", "Journal", "journalLens", lens));
		}
		for (String fam : new String[]{"Ledger & Roads", "Living"})
		{
			b.add(new Board("Ledger:" + fam, "RECORD", "Ledger", "statsFamily", fam));
		}
		b.add(new Board("Recap", "RECORD", "Recap"));
		b.add(new Board("Sheet", "HISCORES", null));
		for (String page : new String[]{"combat", "quests", "diaries", "clues", "log"})
		{
			b.add(new Board("Sheet:" + page, "HISCORES", null, "sheetPage", page));
		}
		for (boolean left : new boolean[]{false, true})
		{
			for (boolean kind : new boolean[]{false, true})
			{
				b.add(new Board("Loot" + (left ? ":left" : "") + (kind ? ":kind" : ""),
					"LOOT", "Loot", "dropsLeftBehind", left, "dropsByKind", kind));
			}
		}
		for (String lens : new String[]{"Tasks", "Monsters", "Drops"})
		{
			b.add(new Board("Slayer:" + lens, "LOOT", "Slayer", "slayerLens", lens));
		}
		for (String fam : chronicle.panel.StatRegistry.FAMILIES)
		{
			b.add(new Board("Trackers:" + fam, "TRACKERS", null, "statsFamily", fam));
		}
		return b;
	}

	private List<Object[]> periods(boolean sitting)
	{
		LocalDate t = LocalDate.now();
		List<Object[]> p = new ArrayList<>();
		if (beforeBaseline)
		{
			p.add(new Object[]{"Day", t});
			p.add(new Object[]{"Week", t});
			p.add(new Object[]{"Month", t});
			return p;
		}
		if (sitting)
		{
			p.add(new Object[]{"Session", t});
			return p;
		}
		p.add(new Object[]{"Lifetime", t});
		p.add(new Object[]{"Year", t});
		p.add(new Object[]{"Year", t.minusYears(1)});
		p.add(new Object[]{"Month", t});
		p.add(new Object[]{"Month", t.minusMonths(1)});
		p.add(new Object[]{"Month", t.minusMonths(3)});
		p.add(new Object[]{"Week", t});
		p.add(new Object[]{"Week", t.minusWeeks(1)});
		p.add(new Object[]{"Week", t.minusWeeks(2)});
		p.add(new Object[]{"Week", t.minusWeeks(5)});
		for (int d = 0; d <= 7; d++)
		{
			p.add(new Object[]{"Day", t.minusDays(d)});
		}
		p.add(new Object[]{"Day", t.minusDays(30)});
		p.add(new Object[]{"Session", t});
		return p;
	}

	/** Periods whose every door is pressed, two clicks deep. */
	private static boolean pressHere(String g, LocalDate c)
	{
		LocalDate t = LocalDate.now();
		return ("Lifetime".equals(g) || "Session".equals(g)
			|| (("Month".equals(g) || "Week".equals(g) || "Day".equals(g)) && c.equals(t)));
	}

	private void sweep(boolean sitting) throws Exception
	{
		for (Object[] per : periods(sitting))
		{
			String g = (String) per[0];
			LocalDate c = (LocalDate) per[1];
			for (Board b : boards())
			{
				open(b, g, c);
				String key = visit("board " + b.name, g, c);
				if (key != null && pressHere(g, c))
				{
					pressDoors(key, 1);
				}
			}
			for (String page : new String[]{"records", "calendar", "info", "trackers"})
			{
				openPage(page, g, c);
				String key = visit("page " + page, g, c);
				if (key != null && pressHere(g, c))
				{
					pressDoors(key, 1);
				}
			}
			if ("Lifetime".equals(g))
			{
				for (String q : new String[]{"vorkath", "whip", "dagannoth rex", "nechrael",
					"herbiboar", "fishing", "bloodveld", "qqqq"})
				{
					openSearch(q);
					String key = visit("search " + q, g, c);
					if (key != null)
					{
						pressDoors(key, 1);
					}
					openSearch("");
				}
			}
		}
	}

	private void open(Board b, String g, LocalDate c) throws Exception
	{
		reset();
		Class<?> tabType = Class.forName("chronicle.ChroniclePanel$Tab");
		Object tab = Enum.valueOf((Class) tabType, b.tab);
		if (b.sub != null)
		{
			@SuppressWarnings("unchecked")
			Map<Object, String> subs = (Map<Object, String>) f("subByTab").get(panel);
			subs.put(tab, b.sub);
		}
		Method apply = ChroniclePanel.class.getDeclaredMethod("applyTab", tabType);
		apply.setAccessible(true);
		edt(() -> apply.invoke(panel, tab));
		for (int i = 0; i + 1 < b.lens.length; i += 2)
		{
			f((String) b.lens[i]).set(panel, b.lens[i + 1]);
		}
		period(g, c);
	}

	private void openPage(String page, String g, LocalDate c) throws Exception
	{
		reset();
		period(g, c);
		String m = "records".equals(page) ? "openRecords" : "calendar".equals(page) ? "openCalendar"
			: "info".equals(page) ? "openInfo" : "openAllTrackers";
		Method open = ChroniclePanel.class.getDeclaredMethod(m);
		open.setAccessible(true);
		edt(() -> open.invoke(panel));
	}

	private void openSearch(String q) throws Exception
	{
		Object box = f("searchField").get(panel);
		edt(() -> box.getClass().getMethod("setText", String.class).invoke(box, q));
	}

	private void reset() throws Exception
	{
		openSearch("");
		for (String n : new String[]{"detailSource", "detailItem", "detailSkill", "sheetPage",
			"lootKind", "lootTask", "leftBehindSource", "leftBehindItem", "histFrom", "histTo"})
		{
			f(n).set(panel, null);
		}
		f("detailTask").setInt(panel, -1);
		for (String n : new String[]{"allTrackers", "showInfo", "showRecords", "showCalendar"})
		{
			f(n).setBoolean(panel, false);
		}
		((java.util.Collection<?>) f("detailStack").get(panel)).clear();
	}

	private void period(String g, LocalDate c) throws Exception
	{
		f("histGranularity").set(panel, g);
		f("histCursor").set(panel, c);
		f("histFrom").set(panel, null);
		f("histTo").set(panel, null);
	}

	/**
	 * Build the state standing now, audit it, and return its key; null when
	 * it has been audited already.
	 */
	private String visit(String how, String g, LocalDate c) throws Exception
	{
		String key = stateKey();
		if (states.containsKey(key))
		{
			return null;
		}
		JsonObject st = new JsonObject();
		st.addProperty("how", how);
		st.addProperty("period", g + "@" + c);
		st.addProperty("where", describe());
		states.put(key, st);
		Throwable thrown = rebuild();
		if (thrown != null)
		{
			finding("exception", st, thrown.toString() + " at " + frame(thrown));
			return key;
		}
		List<String[]> texts = new ArrayList<>();
		collect(panel, texts);
		com.google.gson.JsonArray said = new com.google.gson.JsonArray();
		for (String[] t : texts)
		{
			said.add(t[0] + "|" + t[1]);
			check(st, t[0], t[1]);
		}
		st.add("said", said);
		return key;
	}

	private void check(JsonObject st, String kind, String text)
	{
		String s = text.trim();
		if (s.isEmpty())
		{
			return;
		}
		flag(st, NULL, s, "null-or-nan");
		flag(st, NEGATIVE, s, "negative-figure");
		flag(st, DOT_JUNK, s, "dot-junk");
		flag(st, PLURAL, s, "plural");
		flag(st, MID_CAPITAL, s, "mid-capital");
		// the bundled task and diary text is the wiki's own, symbols and all
		if (!"tip".equals(kind))
		{
			flag(st, SIGN_JUNK, s, "sign-junk");
			flag(st, RAW_NUMBER, s.replaceAll("\\b(19|20)\\d\\d\\b", ""), "raw-number");
			if (s.contains("  "))
			{
				finding("double-space", st, s);
			}
		}
		if (BAIL.matcher(s).find())
		{
			st.addProperty("bail", (st.has("bail") ? st.get("bail").getAsString() + " / " : "") + s);
		}
	}

	private void flag(JsonObject st, Pattern p, String s, String kind)
	{
		if (p.matcher(s).find())
		{
			finding(kind, st, s);
		}
	}

	/** Press every door the state offers, one at a time, from the state itself. */
	private void pressDoors(String key, int depth) throws Exception
	{
		Map<String, Object> home = snapshot();
		JsonObject st = states.get(key);
		List<Component> doors = doors();
		int limit = Math.min(doors.size(), DOORS_PER_STATE);
		for (int i = 0; i < limit && presses < DOOR_BUDGET; i++)
		{
			restore(home);
			if (rebuild() != null)
			{
				return;
			}
			List<Component> now = doors();
			if (i >= now.size())
			{
				break;
			}
			Component door = now.get(i);
			String label = doorLabel(door);
			if (label.startsWith("copy") || label.equals("copied") || label.equals("cannot copy")
				|| "Choose the period".equals(tipOf(door)))
			{
				continue;   // the clipboard and the popup menu, which a headless run cannot hold
			}
			String before = stateKey();
			// the figure the door carried, for the pages it opens to agree with
			String carried = figureOf(door);
			String boss = bossOf(door);
			presses++;
			Throwable thrown = press(door);
			if (thrown != null)
			{
				// a popup menu asks where its invoker sits on the screen, and a
				// headless run has no screen: the menu's own business, not a bug
				if (!(thrown instanceof java.awt.HeadlessException)
					&& !(thrown instanceof java.awt.IllegalComponentStateException))
				{
					finding("door-throws", st, "\"" + label + "\" -> " + thrown + " at " + frame(thrown));
				}
				continue;
			}
			String after = stateKey();
			if (after.equals(before) && selected(label))
			{
				continue;   // the pill already chosen, pressed again
			}
			if (after.equals(before))
			{
				// a fold toggles openFolds, a "Show more" drillShown: both are state,
				// so an unchanged state is a door that did nothing
				finding("dead-door", st, "\"" + label + "\"" + (tipOf(door) != null
					? " (hover: " + plain(tipOf(door)).replaceAll("\\s+", " ").trim() + ")" : ""));
				continue;
			}
			String g = (String) f("histGranularity").get(panel);
			LocalDate c = (LocalDate) f("histCursor").get(panel);
			String next = visit("door \"" + label + "\" from " + st.get("where").getAsString(), g, c);
			JsonObject landed = states.get(after);
			if (landed != null && carried != null)
			{
				agree(st, label, carried, landed);
			}
			if (boss != null)
			{
				landsOn(st, boss, carried, landed);
			}
			if (next != null && depth < 2)
			{
				pressDoors(next, depth + 1);
			}
		}
		restore(home);
	}

	/** The roster boss a sheet cell stands for, read off its hover's title; null for any other door. */
	private String bossOf(Component door) throws Exception
	{
		String tip = tipOf(door);
		if (tip == null || !String.valueOf(f("view").get(panel)).equals("SHEET"))
		{
			return null;
		}
		String title = plain(tip).split("\n")[0].trim();
		return roster().contains(LocalStore.kindOf(title)) ? title : null;
	}

	private Set<String> rosterKinds;

	private Set<String> roster() throws Exception
	{
		if (rosterKinds == null)
		{
			rosterKinds = new LinkedHashSet<>();
			Method r = ChroniclePanel.class.getDeclaredMethod("bossRoster", com.google.gson.Gson.class);
			r.setAccessible(true);
			for (Object b : (List<?>) r.invoke(null, new com.google.gson.Gson()))
			{
				rosterKinds.add(LocalStore.kindOf((String) field(b, "name")));
			}
		}
		return rosterKinds;
	}

	/**
	 * A boss cell opens its own fight's page: the page named for it, a
	 * container named after it in brackets, or a name the table says it pays
	 * out through. Anything else is another monster's loot under this one's
	 * cell, which is how all three Dagannoth kings opened the ordinary
	 * dagannoth.
	 */
	@SuppressWarnings("unchecked")
	private void landsOn(JsonObject from, String boss, String carried, JsonObject landed) throws Exception
	{
		String dest = (String) f("detailSource").get(panel);
		if (dest == null)
		{
			return;   // the cell opened something other than a source page
		}
		Field po = ChroniclePanel.class.getDeclaredField("PAYS_OUT");
		po.setAccessible(true);
		List<String> paid = ((Map<String, List<String>>) po.get(null)).getOrDefault(boss, new ArrayList<>());
		boolean own = LocalStore.kindOf(dest).equals(LocalStore.kindOf(boss))
			|| dest.toLowerCase().contains("(" + boss.toLowerCase() + ")")
			|| paid.stream().anyMatch(dest::equalsIgnoreCase);
		if (!own)
		{
			finding("boss-opens-elsewhere", from, boss + " opens " + dest);
			return;
		}
		String said = landed == null ? "" : String.join(" ", landed.getAsJsonArray("said").toString());
		if (carried != null && carried.matches("[0-9,]+") && said.contains("has no drops"))
		{
			review.add(boss + " (" + carried + " kills) opens a page with no loot");
		}
	}

	/** Whether a label names the sub-tab or lens the panel is already on. */
	private boolean selected(String label) throws Exception
	{
		for (String n : new String[]{"journalLens", "slayerLens", "statsFamily", "clogTab"})
		{
			if (label.equals(f(n).get(panel)))
			{
				return true;
			}
		}
		@SuppressWarnings("unchecked")
		Map<Object, String> subs = (Map<Object, String>) f("subByTab").get(panel);
		return label.equals(subs.get(f("tab").get(panel)));
	}

	/**
	 * A boss cell carries a kill count and opens a page that states one; the two
	 * must agree at Lifetime, where both read the whole record.
	 */
	private void agree(JsonObject from, String label, String carried, JsonObject landed)
	{
		if (!from.get("period").getAsString().startsWith("Lifetime")
			|| !from.get("where").getAsString().contains("view=SHEET")
			|| !carried.matches("[0-9,]+"))
		{
			return;
		}
		String kills = beside(landed, "Kills");
		if (kills != null && !kills.equals(carried))
		{
			finding("disagree:boss-cell-vs-page", from, label + " cell " + carried
				+ " but its page says Kills " + kills + " (" + landed.get("where").getAsString() + ")");
		}
	}

	// ------------------------------------------------------------------
	// checks across the whole walk
	// ------------------------------------------------------------------

	private void crossChecks() throws Exception
	{
		for (JsonObject st : states.values())
		{
			if (!st.has("bail") || !st.get("how").getAsString().startsWith("board Sheet"))
			{
				continue;
			}
			String bail = st.get("bail").getAsString();
			boolean skills = bail.contains("Nothing recorded") || bail.contains("imported past")
				|| bail.contains("fewer than two") && !bail.contains("boss");
			boolean kills = bail.contains("Nothing on the boss sheet was killed");
			String why = skills ? moved(st, true) : kills ? moved(st, false) : null;
			if (why != null)
			{
				finding("bail-while-data", st, bail + "  <-- but " + why);
			}
		}
		// the Recap and the sheet's own period card read the same period two ways
		Map<String, JsonObject> recap = new LinkedHashMap<>();
		Map<String, JsonObject> sheet = new LinkedHashMap<>();
		for (JsonObject st : states.values())
		{
			String how = st.get("how").getAsString();
			if ("board Recap".equals(how))
			{
				recap.put(st.get("period").getAsString(), st);
			}
			if ("board Sheet".equals(how))
			{
				sheet.put(st.get("period").getAsString(), st);
			}
		}
		for (Map.Entry<String, JsonObject> e : recap.entrySet())
		{
			JsonObject sh = sheet.get(e.getKey());
			if (sh == null)
			{
				continue;
			}
			String xpR = beside(e.getValue(), "Xp gained");
			String xpS = tipFigure(sh, "Experience");
			if (xpR != null && xpS != null)
			{
				String a = xpR.replaceAll(" xp.*", "");
				if (!a.equals(xpS))
				{
					finding("disagree:recap-vs-sheet-xp", e.getValue(), "Recap " + xpR + " vs sheet " + xpS);
				}
			}
			String playR = beside(e.getValue(), "Played");
			String playS = tipFigure(sh, "Time played");
			if (playR != null && playS != null && !playR.startsWith(playS))
			{
				finding("disagree:recap-vs-sheet-played", e.getValue(), "Recap " + playR + " vs sheet " + playS);
			}
		}
	}

	/**
	 * What moved inside the window this state read, where the board said
	 * nothing did: the overall xp, or any kill count present at both ends.
	 * Null when nothing moved, which is the board telling the truth.
	 */
	private String moved(JsonObject st, boolean xp) throws Exception
	{
		String[] pc = st.get("period").getAsString().split("@");
		if ("Lifetime".equals(pc[0]) || "Session".equals(pc[0]))
		{
			return null;
		}
		reset();
		period(pc[0], LocalDate.parse(pc[1]));
		Method w = ChroniclePanel.class.getDeclaredMethod("window");
		w.setAccessible(true);
		Object win = w.invoke(panel);
		LocalDate start = (LocalDate) field(win, "start");
		LocalDate end = (LocalDate) field(win, "end");
		TreeMap<LocalDate, HistoryLog.Baseline> spine = stub.history;
		Map.Entry<LocalDate, HistoryLog.Baseline> open = HistoryLog.windowStart(spine, start, end);
		Map.Entry<LocalDate, HistoryLog.Baseline> shut = spine.floorEntry(end);
		// only the eve counts as an opening: a record days cold is the board's
		// own honest refusal, not a board that should have drawn
		if (open == null || shut == null || open.getKey().isBefore(start.minusDays(1)))
		{
			return null;
		}
		HistoryLog.Baseline a = HistoryLog.stateAt(spine, open.getKey());
		HistoryLog.Baseline b = HistoryLog.stateAt(spine, shut.getKey());
		if (xp)
		{
			Long x0 = a.skills.get("overall");
			Long x1 = b.skills.get("overall");
			long[] live = stub.skillSheet().get("overall");
			if (!end.isBefore(LocalDate.now()) && live != null && live.length > 1)
			{
				x1 = Math.max(x1 == null ? 0 : x1, live[1]);
			}
			return x0 != null && x1 != null && x1 > x0
				? "overall xp moved " + x0 + " -> " + x1 + " between " + open.getKey() + " and " + shut.getKey()
				: null;
		}
		// Herbiboar and the implings keep kill counts and are no boss: only the
		// roster's own names count against "nothing on the boss sheet"
		Set<String> roster = new LinkedHashSet<>();
		Method r = ChroniclePanel.class.getDeclaredMethod("bossRoster", com.google.gson.Gson.class);
		r.setAccessible(true);
		for (Object boss : (List<?>) r.invoke(null, new com.google.gson.Gson()))
		{
			roster.add(LocalStore.kindOf((String) field(boss, "name")));
		}
		for (Map.Entry<String, Long> e : b.kcs.entrySet())
		{
			Long was = a.kcs.get(e.getKey());
			if (was != null && e.getValue() > was && roster.contains(LocalStore.kindOf(e.getKey())))
			{
				return e.getKey() + " moved " + was + " -> " + e.getValue() + " between "
					+ open.getKey() + " and " + shut.getKey();
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// the panel, read
	// ------------------------------------------------------------------

	private List<Component> doors()
	{
		List<Component> out = new ArrayList<>();
		doors(panel, out);
		return out;
	}

	private static void doors(Component c, List<Component> out)
	{
		if (c.isVisible() && c.getCursor().getType() == Cursor.HAND_CURSOR
			&& c.getMouseListeners().length > 0 && (c.getParent() == null
			|| c.getParent().getCursor().getType() != Cursor.HAND_CURSOR
			|| c.getMouseListeners().length > c.getParent().getMouseListeners().length))
		{
			out.add(c);
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				doors(k, out);
			}
		}
	}

	private static void collect(Component c, List<String[]> out)
	{
		if (!c.isVisible())
		{
			return;
		}
		if (c instanceof JLabel && ((JLabel) c).getText() != null)
		{
			out.add(new String[]{"label", ((JLabel) c).getText()});
		}
		if (c instanceof JComponent && ((JComponent) c).getToolTipText() != null)
		{
			out.add(new String[]{"tip", plain(((JComponent) c).getToolTipText())});
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				collect(k, out);
			}
		}
	}

	private static String plain(String html)
	{
		return html.replaceAll("</div>", " \n").replaceAll("<[^>]+>", "").replace("&nbsp;", " ")
			.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim();
	}

	private static String doorLabel(Component c)
	{
		List<String[]> t = new ArrayList<>();
		collect(c, t);
		for (String[] s : t)
		{
			if ("label".equals(s[0]) && !s[1].trim().isEmpty())
			{
				return s[1].trim();
			}
		}
		String tip = tipOf(c);
		return tip != null ? plain(tip).split("\n")[0].trim() : c.getClass().getSimpleName();
	}

	private static String tipOf(Component c)
	{
		return c instanceof JComponent ? ((JComponent) c).getToolTipText() : null;
	}

	/** The right-hand figure a row or cell carries, when it has one. */
	private static String figureOf(Component c)
	{
		if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof BorderLayout)
		{
			Component east = ((BorderLayout) ((JPanel) c).getLayout()).getLayoutComponent(BorderLayout.EAST);
			if (east instanceof JLabel)
			{
				return ((JLabel) east).getText();
			}
		}
		return null;
	}

	/** The figure beside a label in a recorded state, read off its said list. */
	private static String beside(JsonObject st, String left)
	{
		com.google.gson.JsonArray said = st.getAsJsonArray("said");
		if (said == null)
		{
			return null;
		}
		for (int i = 0; i + 1 < said.size(); i++)
		{
			if (("label|" + left).equals(said.get(i).getAsString())
				&& said.get(i + 1).getAsString().startsWith("label|"))
			{
				return said.get(i + 1).getAsString().substring(6);
			}
		}
		return null;
	}

	/** "Label: figure" inside any hover card of a recorded state. */
	private static String tipFigure(JsonObject st, String label)
	{
		com.google.gson.JsonArray said = st.getAsJsonArray("said");
		if (said == null)
		{
			return null;
		}
		Pattern p = Pattern.compile("(?m)^" + Pattern.quote(label) + ": (.+?)\\s*$");
		for (int i = 0; i < said.size(); i++)
		{
			String s = said.get(i).getAsString();
			if (s.startsWith("tip|"))
			{
				Matcher m = p.matcher(s.substring(4));
				if (m.find())
				{
					return m.group(1).trim();
				}
			}
		}
		return null;
	}

	private String describe() throws Exception
	{
		StringBuilder b = new StringBuilder();
		for (String n : new String[]{"tab", "subByTab", "view", "sheetPage", "slayerLens", "journalLens",
			"statsFamily", "dropsLeftBehind", "dropsByKind", "lootKind", "detailSource", "detailItem",
			"detailSkill", "detailTask", "leftBehindSource", "leftBehindItem", "showInfo", "allTrackers",
			"showRecords", "showCalendar", "calendarMonth", "clogPageSel"})
		{
			Object v = f(n).get(panel);
			if (v == null || Boolean.FALSE.equals(v) || Integer.valueOf(-1).equals(v))
			{
				continue;
			}
			b.append(n).append('=').append(v).append(' ');
		}
		Object q = f("searchField").get(panel);
		String text = (String) q.getClass().getMethod("getText").invoke(q);
		if (text != null && !text.isEmpty())
		{
			b.append("search=").append(text);
		}
		return b.toString().trim();
	}

	private void finding(String kind, JsonObject st, String detail)
	{
		String key = kind + "|" + st.get("how").getAsString().replaceAll("from .*", "") + "|" + detail;
		if (!seenFinding.add(key))
		{
			return;
		}
		JsonObject f = new JsonObject();
		f.addProperty("kind", kind);
		f.addProperty("period", st.get("period").getAsString());
		f.addProperty("where", st.get("where").getAsString());
		f.addProperty("how", st.get("how").getAsString());
		f.addProperty("detail", detail);
		findings.add(f);
	}

	// ------------------------------------------------------------------
	// plumbing
	// ------------------------------------------------------------------

	private Throwable rebuild() throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("rebuildNow");
		m.setAccessible(true);
		final Throwable[] out = {null};
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				m.invoke(panel);
			}
			catch (InvocationTargetException e)
			{
				out[0] = e.getCause();
			}
			catch (Throwable t)
			{
				out[0] = t;
			}
		});
		// icons dress on a later pass
		SwingUtilities.invokeAndWait(() -> { });
		return out[0];
	}

	private static Throwable press(Component c) throws Exception
	{
		final Throwable[] out = {null};
		SwingUtilities.invokeAndWait(() ->
		{
			MouseEvent e = new MouseEvent(c, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false);
			for (MouseListener l : c.getMouseListeners())
			{
				try
				{
					l.mousePressed(e);
				}
				catch (Throwable t)
				{
					out[0] = t;
				}
			}
		});
		SwingUtilities.invokeAndWait(() -> { });
		return out[0];
	}

	private static String frame(Throwable t)
	{
		for (StackTraceElement e : t.getStackTrace())
		{
			if (e.getClassName().startsWith("chronicle."))
			{
				return e.getClassName().replace("chronicle.", "") + "." + e.getMethodName() + ":"
					+ e.getLineNumber();
			}
		}
		return t.getStackTrace().length > 0 ? t.getStackTrace()[0].toString() : "?";
	}

	private String stateKey() throws Exception
	{
		StringBuilder b = new StringBuilder(beforeBaseline ? "nobaseline;" : "");
		for (String n : stateFields)
		{
			Field fl = f(n);
			b.append(n).append('=').append(fl == null ? null : fl.get(panel)).append(';');
		}
		Object q = f("searchField").get(panel);
		b.append("q=").append(q.getClass().getMethod("getText").invoke(q));
		return b.toString();
	}

	private Map<String, Object> snapshot() throws Exception
	{
		Map<String, Object> out = new LinkedHashMap<>();
		for (String n : stateFields)
		{
			Field fl = f(n);
			if (fl == null)
			{
				continue;
			}
			Object v = fl.get(panel);
			if (v instanceof Set)
			{
				v = new LinkedHashSet<>((Set<?>) v);
			}
			else if (v instanceof Map)
			{
				v = new LinkedHashMap<>((Map<?, ?>) v);
			}
			out.put(n, v);
		}
		Object q = f("searchField").get(panel);
		out.put("__q", q.getClass().getMethod("getText").invoke(q));
		out.put("__stack", new ArrayDeque<>((Deque<?>) f("detailStack").get(panel)));
		return out;
	}

	@SuppressWarnings("unchecked")
	private void restore(Map<String, Object> snap) throws Exception
	{
		openSearch((String) snap.get("__q"));
		Deque<Object> stack = (Deque<Object>) f("detailStack").get(panel);
		stack.clear();
		stack.addAll((Deque<Object>) snap.get("__stack"));
		for (Map.Entry<String, Object> e : snap.entrySet())
		{
			if (e.getKey().startsWith("__"))
			{
				continue;
			}
			Field fl = f(e.getKey());
			Object v = e.getValue();
			if (v instanceof Set)
			{
				Set<Object> live = (Set<Object>) fl.get(panel);
				live.clear();
				live.addAll((Set<Object>) v);
			}
			else if (v instanceof Map)
			{
				Map<Object, Object> live = (Map<Object, Object>) fl.get(panel);
				live.clear();
				live.putAll((Map<Object, Object>) v);
			}
			else
			{
				fl.set(panel, v);
			}
		}
	}

	private Field f(String name) throws Exception
	{
		Field fl = fields.get(name);
		if (fl == null && !fields.containsKey(name))
		{
			try
			{
				fl = ChroniclePanel.class.getDeclaredField(name);
				fl.setAccessible(true);
			}
			catch (NoSuchFieldException e)
			{
				fl = null;
			}
			fields.put(name, fl);
		}
		return fl;
	}

	private static Object field(Object o, String name) throws Exception
	{
		Field fl = o.getClass().getDeclaredField(name);
		fl.setAccessible(true);
		return fl.get(o);
	}

	private interface Run
	{
		void run() throws Exception;
	}

	private static void edt(Run r) throws Exception
	{
		final Exception[] err = {null};
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				r.run();
			}
			catch (Exception e)
			{
				err[0] = e;
			}
		});
		if (err[0] != null)
		{
			throw err[0];
		}
	}
}
