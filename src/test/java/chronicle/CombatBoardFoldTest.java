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
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CombatBoardFoldTest
{
	private static final String JOURNAL =
		"{\"schema\":1,\"rsn\":\"Somebody\",\"drops\":{},"
		+ "\"collection_log\":{\"finished\":0,\"available\":1717},"
		+ "\"achievements\":{\"combat\":{\"points\":10,\"tasksDone\":[0,1,2]}},"
		+ "\"trackers\":{},\"skills\":{},\"feed\":[]}";

	private static ChroniclePanel panel() throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-ca-fold");
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

	@SuppressWarnings("unchecked")
	private static void openFold(ChroniclePanel p, String key) throws Exception
	{
		java.lang.reflect.Field f = ChroniclePanel.class.getDeclaredField("openFolds");
		f.setAccessible(true);
		((java.util.Collection<String>) f.get(p)).add(key);
	}

	private static List<Component> board(ChroniclePanel p) throws Exception
	{
		final List<Component> flat = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				JPanel into = new JPanel();
				Method m = ChroniclePanel.class
					.getDeclaredMethod("buildCombatAchievements", JPanel.class);
				m.setAccessible(true);
				m.invoke(p, into);
				flatten(into, flat);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return flat;
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

	private static boolean says(List<Component> flat, String text)
	{
		for (Component c : flat)
		{
			if (c instanceof JLabel && text.equals(((JLabel) c).getText()))
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void aClosedBoardDoesNotMountTheTasksUnderIt() throws Exception
	{
		ChroniclePanel p = panel();
		List<Component> flat = board(p);
		assertTrue("the closed board mounted " + flat.size() + " components, which is"
			+ " the flat board it was meant to replace", flat.size() < 900);
		assertTrue("a task under a closed fold was mounted anyway",
			!says(flat, "Noxious Foe"));
		assertTrue("the sources themselves went missing", says(flat, "ABERRANT SPECTRE")
			&& says(flat, "BARROWS"));
	}

	@Test
	public void openingOneSourceMountsOnlyThatSource() throws Exception
	{
		ChroniclePanel p = panel();
		int shut = board(p).size();
		openFold(p, "ca:Aberrant Spectre");
		List<Component> open = board(p);
		assertTrue("opening a source did not show its tasks",
			says(open, "Noxious Foe"));
		assertTrue("opening one source mounted " + (open.size() - shut)
			+ " components, which is more than one source has tasks",
			open.size() - shut < 120);
		assertTrue("another source's tasks came with it",
			!says(open, "Barrows Novice"));
	}

	@Test
	public void noSingleSourceIsAWall() throws Exception
	{
		com.google.gson.JsonObject all = CombatAchievementsTest.tasks();
		java.util.Map<String, Integer> per = new java.util.HashMap<>();
		for (String id : all.keySet())
		{
			String where = all.getAsJsonObject(id).get("monster").getAsString();
			per.merge(where, 1, Integer::sum);
		}
		String worst = null;
		for (java.util.Map.Entry<String, Integer> e : per.entrySet())
		{
			if (worst == null || e.getValue() > per.get(worst))
			{
				worst = e.getKey();
			}
		}
		assertTrue("the table names only " + per.size() + " sources, which is a filing"
			+ " that has collapsed", per.size() > 50);
		assertTrue(worst + " alone holds " + per.get(worst) + " tasks, which is a wall"
			+ " and not a fold", per.get(worst) <= 40);
		assertEquals("a task is filed under nothing", 0,
			per.getOrDefault("", 0).intValue());
	}
}
