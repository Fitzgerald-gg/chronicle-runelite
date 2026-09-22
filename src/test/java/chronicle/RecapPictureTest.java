/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The Recap's picture: the period whole on one sheet, up to 1920 by 1080.
 *
 * <p>It is a copy of the Recap, so every figure on it has to be the figure
 * the board it belongs to prints; it leaves the reader's machine, so nothing on
 * it may say when the record began; and it has a ceiling, so a list the ceiling
 * cuts has to say so.
 */
public class RecapPictureTest
{
	@BeforeClass
	public static void headless() throws Exception
	{
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
	}

	private static ChroniclePanel panel(PanelPreviewTest.StubPlugin s) throws Exception
	{
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		PanelPreviewTest.regatherHistory(hold[0]);
		return hold[0];
	}

	private static void set(ChroniclePanel p, String field, Object v) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(p, v);
	}

	private static Object call(ChroniclePanel p, String name) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod(name);
		m.setAccessible(true);
		return m.invoke(p);
	}

	/** The period, then the facts over it, read the way a rebuild leaves them. */
	private static RecapPicture.Facts facts(ChroniclePanel p, String g, LocalDate c) throws Exception
	{
		final RecapPicture.Facts[] out = new RecapPicture.Facts[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				set(p, "histGranularity", g);
				set(p, "histCursor", c);
				call(p, "rebuildNow");
				out[0] = (RecapPicture.Facts) call(p, "recapFacts");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out[0];
	}

	private static HistoryLog.Baseline line(Map<String, Long> skills, Map<String, Long> kcs)
	{
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		b.skills.putAll(skills);
		long all = 0;
		for (long v : skills.values())
		{
			all += v;
		}
		b.skills.put("overall", all);
		b.complete = true;
		b.kcs.putAll(kcs);
		return b;
	}

	private static Map<String, Long> m(Object... kv)
	{
		Map<String, Long> out = new java.util.LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2)
		{
			out.put((String) kv[i], ((Number) kv[i + 1]).longValue());
		}
		return out;
	}

	/** A year with a skill climbing, a boss killed and a monster killed, closed in the past. */
	private static PanelPreviewTest.StubPlugin year()
	{
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		s.history.put(LocalDate.of(2024, 12, 31), line(
			m("attack", 1_000_000L, "fishing", 2_000_000L, "hitpoints", 1_200_000L),
			m("Vorkath", 100L, "Abyssal demons", 400L, "Dusk", 20L)));
		s.history.put(LocalDate.of(2025, 6, 30), line(
			m("attack", 1_500_000L, "fishing", 2_500_000L, "hitpoints", 1_300_000L),
			m("Vorkath", 110L, "Abyssal demons", 450L, "Dusk", 30L)));
		s.history.put(LocalDate.of(2025, 12, 31), line(
			m("attack", 2_000_000L, "fishing", 6_000_000L, "hitpoints", 1_400_000L),
			m("Vorkath", 130L, "Abyssal demons", 500L, "Dusk", 40L)));
		return s;
	}

	@Test
	public void theSheetIsNeverPastNineteenTwentyByTenEighty() throws Exception
	{
		ChroniclePanel p = panel(PanelPreviewTest.fixtureStub());
		for (String g : new String[]{"Lifetime", "Week", "Day", "Session"})
		{
			RecapPicture.Facts f = facts(p, g, LocalDate.now());
			BufferedImage img = RecapPicture.paint(f, null, null);
			assertEquals(g, RecapPicture.WIDTH, img.getWidth());
			assertTrue(g + " is " + img.getHeight() + " tall", img.getHeight() <= RecapPicture.MAX_HEIGHT);
		}
	}

	/**
	 * TRAP: a record bigger than the sheet. The lists give up their tails at the
	 * ceiling and each says how many; without the ceiling the picture ran on,
	 * and without the saying so it silently stopped short.
	 */
	@Test
	public void aRecordBiggerThanTheSheetGivesUpRowsAndSaysSo() throws Exception
	{
		RecapPicture.Facts f = new RecapPicture.Facts();
		f.title = "Everything";
		for (int i = 0; i < 300; i++)
		{
			f.bosses.add(new RecapPicture.BossLine("Boss " + i, 0, null, (long) i, i));
		}
		for (String fam : new String[]{"Combat", "Skilling", "Living", "Ledger & Roads"})
		{
			List<RecapPicture.Named> rows = new ArrayList<>();
			for (int i = 0; i < 60; i++)
			{
				rows.add(new RecapPicture.Named(fam + " " + i, "1", null));
			}
			f.trackers.put(fam, rows);
		}
		BufferedImage img = RecapPicture.paint(f, null, null);
		assertTrue("the sheet ran to " + img.getHeight(), img.getHeight() <= RecapPicture.MAX_HEIGHT);
		assertTrue("rows were lost and nothing was dropped by count", f.dropped > 0);
	}

	/**
	 * Every figure is the one its own board prints: each skill's gain is the
	 * sheet's, they sum to the Recap's xp, and a boss's two ends subtract to
	 * its cell's own figure.
	 */
	@Test
	public void itAgreesWithTheBoardsItCopies() throws Exception
	{
		ChroniclePanel p = panel(year());
		RecapPicture.Facts f = facts(p, "Year", LocalDate.of(2025, 6, 1));
		final Object[] read = new Object[2];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				read[0] = call(p, "periodSkillGains");
				read[1] = call(p, "periodXp");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		@SuppressWarnings("unchecked")
		Map<String, Long> sheet = (Map<String, Long>) read[0];
		long total = 0;
		for (RecapPicture.SkillLine l : f.skills)
		{
			String key = l.skill.name().toLowerCase(Locale.ROOT);
			assertEquals(l.name, sheet.getOrDefault(key, 0L).longValue(), l.gained());
			total += l.gained();
		}
		assertEquals(((long[]) read[1])[0], total);
		assertEquals(5_200_000L, total);

		RecapPicture.BossLine vorkath = null;
		for (RecapPicture.BossLine b : f.bosses)
		{
			if ("Vorkath".equals(b.name))
			{
				vorkath = b;
			}
		}
		assertNotNull("Vorkath's thirty kills are not on the sheet: " + names(f.bosses), vorkath);
		assertEquals(30, vorkath.gained);
		assertEquals(Long.valueOf(100), vorkath.start);
		assertEquals(Long.valueOf(130), vorkath.end);
	}

	/**
	 * The monsters leave out the bosses and every name a boss's fight is fought
	 * as: Dusk is the Grotesque Guardians, not a monster killed forty times.
	 */
	@Test
	public void theMonstersAreNotTheBossesOrTheirFights() throws Exception
	{
		ChroniclePanel p = panel(year());
		RecapPicture.Facts f = facts(p, "Year", LocalDate.of(2025, 6, 1));
		List<String> named = new ArrayList<>();
		for (RecapPicture.Named n : f.monsters)
		{
			named.add(n.name + " " + n.figure);
		}
		assertTrue(named.toString(), named.contains("Abyssal demons 100"));
		for (String n : named)
		{
			assertFalse("a boss among the monsters: " + named, n.startsWith("Vorkath"));
			assertFalse("a boss's fight among the monsters: " + named, n.startsWith("Dusk"));
		}
	}

	/**
	 * The levels the period reached are the skills table's own, not the
	 * journal's level lines: those began with the plugin, and a year that took
	 * fishing up twelve levels named none of them.
	 */
	@Test
	public void theLevelsAreTheTablesNotTheFeeds() throws Exception
	{
		ChroniclePanel p = panel(year());
		RecapPicture.Facts f = facts(p, "Year", LocalDate.of(2025, 6, 1));
		List<String> levels = f.feats.get("Levels");
		assertNotNull("no levels, with no level line in the feed: " + f.feats.keySet(), levels);
		assertTrue(levels.toString(), levels.stream().anyMatch(l -> l.startsWith("Fishing ")));
	}

	/**
	 * TRAP: the picture leaves the reader's machine. Nothing on it says when the
	 * record began: not a first date, not a "since", not the day counts started.
	 */
	@Test
	public void nothingOnItSaysWhenTheRecordBegan() throws Exception
	{
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.fixtureStub();
		ChroniclePanel p = panel(s);
		List<String> said = new ArrayList<>();
		said.addAll(strings(facts(p, "Lifetime", LocalDate.now())));
		// a window from before the record kept its counts
		said.addAll(strings(facts(p, "Year", LocalDate.now().minusYears(40))));
		// Neither of these windows names a day of its own, so no day may appear
		// on either: whatever day one showed would be the record's own start.
		java.util.regex.Pattern day = java.util.regex.Pattern.compile(
			"\\b\\d{1,2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sept|Sep|Oct|Nov|Dec)");
		for (String line : said)
		{
			String low = line.toLowerCase(Locale.ROOT);
			assertFalse("a since on the picture: " + line, low.contains("since"));
			assertFalse("a before on the picture: " + line, low.contains("before"));
			assertFalse("a day on the picture: " + line, day.matcher(line).find());
		}
	}

	/**
	 * The copy always answers. The boss icons are asked for and may never
	 * land, a dev client with no sprite cache has none to give, and the copy
	 * still reports after a moment and a half rather than reading "copying"
	 * forever.
	 */
	@Test
	public void theCopyAlwaysAnswers() throws Exception
	{
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.fixtureStub();
		s.spriteManager = null;
		ChroniclePanel p = panel(s);
		final JLabel take = new JLabel("copying");
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				set(p, "histGranularity", "Lifetime");
				call(p, "rebuildNow");
				Method m = ChroniclePanel.class.getDeclaredMethod("copyRecapPicture", JLabel.class);
				m.setAccessible(true);
				m.invoke(p, take);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		long deadline = System.currentTimeMillis() + 5_000;
		final String[] now = {"copying"};
		while (System.currentTimeMillis() < deadline && "copying".equals(now[0]))
		{
			Thread.sleep(100);
			SwingUtilities.invokeAndWait(() -> now[0] = take.getText());
		}
		assertTrue("the copy never answered: " + now[0],
			Arrays.asList("copied", "cannot copy").contains(now[0]));
	}

	private static List<String> names(List<RecapPicture.BossLine> bosses)
	{
		List<String> out = new ArrayList<>();
		for (RecapPicture.BossLine b : bosses)
		{
			out.add(b.name);
		}
		return out;
	}

	private static List<String> strings(RecapPicture.Facts f)
	{
		List<String> out = new ArrayList<>();
		out.add(f.title);
		for (RecapPicture.Tile t : f.tiles)
		{
			out.addAll(Arrays.asList(t.label, t.figure, t.under == null ? "" : t.under));
		}
		for (String n : Arrays.asList(f.skillsNote, f.bossesNote, f.monstersNote, f.lootNote, f.trackersNote))
		{
			if (n != null)
			{
				out.add(n);
			}
		}
		out.addAll(f.notes);
		for (List<String> l : f.feats.values())
		{
			out.addAll(l);
		}
		for (List<RecapPicture.Named> l : Arrays.asList(f.monsters, f.loot, f.sources, f.items, f.slayer,
			f.clues))
		{
			for (RecapPicture.Named n : l)
			{
				out.add(n.name + " " + n.figure + " " + n.gp);
			}
		}
		for (List<RecapPicture.Named> l : f.trackers.values())
		{
			for (RecapPicture.Named n : l)
			{
				out.add(n.name + " " + n.figure + " " + n.gp);
			}
		}
		return out;
	}
}
