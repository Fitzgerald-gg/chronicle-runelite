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

public class SearchAchievementsTest
{
	private static final String JOURNAL =
		"{\"schema\":1,\"rsn\":\"Somebody\",\"drops\":{},"
		+ "\"collection_log\":{\"finished\":0,\"available\":1717},"
		+ "\"trackers\":{},\"skills\":{},\"feed\":[]}";

	private static ChroniclePanel panel() throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-search-achv");
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
					if (c instanceof javax.swing.JComponent
						&& ((javax.swing.JComponent) c).getToolTipText() != null)
					{
						out.add(((javax.swing.JComponent) c).getToolTipText());
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

	@Test
	public void aDiaryEntryIsFoundByWhatItAsksFor() throws Exception
	{
		List<String> said = search(panel(), "golden warbler");
		assertTrue("the entry was not found: " + said,
			has(said, "Catch a Golden Warbler"));
		assertTrue("found, but without its region: " + said, has(said, "Desert"));
		assertTrue("found, but without what it needs: " + said, has(said, "5 Hunter"));
	}

	@Test
	public void aTwoLetterQueryDoesNotOpenTheWholeTable() throws Exception
	{
		assertFalse("a two letter query reached the tables",
			has(search(panel(), "ki"), "ACHIEVEMENTS"));
		assertTrue("and three letters still does",
			has(search(panel(), "kil"), "ACHIEVEMENTS"));
	}

	@Test
	public void aCombatAchievementIsAlsoFoundByTheMonsterItsTaskNames() throws Exception
	{
		List<String> said = search(panel(), "aberrant spectre");
		assertTrue("the pun-named task was unreachable by its monster: " + said,
			has(said, "Noxious Foe"));
	}
}
