/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Searching the two bundled tables.
 *
 * <p>Every other group in the search reads the RECORD, and so can only answer
 * about things already done. These two are the only ones that answer about a
 * thing the player has not done yet, which is the question the search box gets
 * asked: what does this one want from me.
 */
public class SearchAchievementsTest
{
	private static final String JOURNAL =
		"{\"schema\":1,\"rsn\":\"Somebody\",\"drops\":{},"
		+ "\"collection_log\":{\"finished\":0,\"available\":1717},"
		+ "\"trackers\":{},\"skills\":{},\"feed\":[]}";

	private static ChroniclePanel panel() throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-search-achv");
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		try (FileWriter w = new FileWriter(new File(dir, "somebody.json")))
		{
			w.write(JOURNAL);
		}
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.journalStub(dir.getPath(), "Somebody");
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		return hold[0];
	}

	private static List<String> search(ChroniclePanel p, String q) throws Exception
	{
		final List<String> out = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class
					.getDeclaredMethod("buildSearch", String.class);
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(p, q), flat);
				for (Component c : flat)
				{
					if (c instanceof JLabel && ((JLabel) c).getText() != null)
					{
						out.add(((JLabel) c).getText());
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out;
	}

	private static void flatten(Component c, List<Component> out)
	{
		out.add(c);
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				flatten(k, out);
			}
		}
	}

	private static boolean has(List<String> said, String what)
	{
		for (String s : said)
		{
			if (s.contains(what))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The record here is empty, so anything the search answers with came from the
	 * bundled table rather than from something this account has done.
	 */
	@Test
	public void aCombatAchievementIsFoundByNameWithWhatItAsksFor() throws Exception
	{
		List<String> said = search(panel(), "noxious foe");
		assertTrue("the task was not found: " + said, has(said, "Noxious Foe"));
		assertTrue("found, but without saying where: " + said,
			has(said, "Aberrant Spectre"));
		assertTrue("found, but without saying what it asks for: " + said,
			has(said, "Kill an Aberrant Spectre"));
	}

	/** A diary entry has no name, so the task text is what has to match. */
	@Test
	public void aDiaryEntryIsFoundByWhatItAsksFor() throws Exception
	{
		List<String> said = search(panel(), "golden warbler");
		assertTrue("the entry was not found: " + said,
			has(said, "Catch a Golden Warbler"));
		assertTrue("found, but without its region: " + said, has(said, "Desert"));
		assertTrue("found, but without what it needs: " + said, has(said, "5 Hunter"));
	}

	/**
	 * TRAP: a short query. Two letters match a third of both tables, and the
	 * cheapest way to lose the guard is to stop passing the query length through,
	 * at which point typing the first letter of anything buries every other group
	 * under achievements the reader did not ask about.
	 */
	@Test
	public void aTwoLetterQueryDoesNotOpenTheWholeTable() throws Exception
	{
		assertFalse("a two letter query reached the tables",
			has(search(panel(), "ki"), "ACHIEVEMENTS"));
		assertTrue("and three letters still does",
			has(search(panel(), "kil"), "ACHIEVEMENTS"));
	}

	/**
	 * And by what it asks for, not only by its name: a combat achievement's name
	 * is a pun as often as it is a description ("Noxious Foe" for an aberrant
	 * spectre), so the monster the task names has to be a way in too.
	 */
	@Test
	public void aCombatAchievementIsAlsoFoundByTheMonsterItsTaskNames() throws Exception
	{
		List<String> said = search(panel(), "aberrant spectre");
		assertTrue("the pun-named task was unreachable by its monster: " + said,
			has(said, "Noxious Foe"));
	}
}
