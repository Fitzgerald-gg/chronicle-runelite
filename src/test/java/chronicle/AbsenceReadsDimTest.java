/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Color;
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
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class AbsenceReadsDimTest
{
	private static final Color DIM = ColorScheme.LIGHT_GRAY_COLOR.darker();

	private static Color field(String name) throws Exception
	{
		java.lang.reflect.Field f = ChroniclePanel.class.getDeclaredField(name);
		f.setAccessible(true);
		return (Color) f.get(null);
	}

	private static Color green() throws Exception
	{
		return field("ACCENT_SESSION");
	}

	private static Color red() throws Exception
	{
		return field("ACCENT_RED");
	}

	@SuppressWarnings("unchecked")
	private static void openFold(ChroniclePanel p, String key) throws Exception
	{
		java.lang.reflect.Field f = ChroniclePanel.class.getDeclaredField("openFolds");
		f.setAccessible(true);
		((java.util.Collection<String>) f.get(p)).add(key);
	}

	private static final String EMPTY =
		"{\"schema\":1,\"rsn\":\"Somebody\",\"drops\":{},"
		+ "\"collection_log\":{\"finished\":0,\"available\":1717},"
		+ "\"trackers\":{},\"skills\":{},\"feed\":[]}";

	private static final String SOME =
		"{\"schema\":1,\"rsn\":\"Somebody\",\"drops\":{"
		+ "\"Clue Scroll (Hard)\":{\"kc\":3,\"loots\":3,\"value\":900,"
		+ "\"items\":{\"1\":{\"id\":1,\"name\":\"Coins\",\"qty\":900,\"value\":900}}}},"
		+ "\"collection_log\":{\"finished\":0,\"available\":1717},"
		+ "\"achievements\":{"
		+ "\"quests\":{\"Cook's Assistant\":\"FINISHED\","
		+ "\"Dragon Slayer II\":\"NOT_STARTED\"},"
		+ "\"diaries\":{\"ardougne\":{\"easy\":true,\"medium\":false,"
		+ "\"hard\":false,\"elite\":false}},"
		+ "\"combat\":{\"points\":10,\"tasksDone\":[0]}},"
		+ "\"trackers\":{},\"skills\":{},\"feed\":[]}";

	private static final String AHEAD =
		"{\"schema\":1,\"rsn\":\"Somebody\",\"drops\":{},"
		+ "\"collection_log\":{\"finished\":0,\"available\":1717},"
		+ "\"achievements\":{\"combat\":{\"points\":10,"
		+ "\"tasksDone\":[0,1,2,660,661]}},"
		+ "\"trackers\":{},\"skills\":{},\"feed\":[]}";

	private static ChroniclePanel panel(String journal, String dirName) throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), dirName);
		dir.mkdirs();
		try (FileWriter w = new FileWriter(new File(dir, "somebody.json")))
		{
			w.write(journal);
		}
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.journalStub(dir.getPath(), "Somebody");
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		return hold[0];
	}

	private static Color nameColour(ChroniclePanel p, String builder, String rowName)
		throws Exception
	{
		final Color[] found = {null};
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				JPanel into = new JPanel();
				Method m = ChroniclePanel.class
					.getDeclaredMethod(builder, JPanel.class);
				m.setAccessible(true);
				m.invoke(p, into);
				List<Component> flat = new ArrayList<>();
				flatten(into, flat);
				for (Component c : flat)
				{
					if (c instanceof JLabel && rowName.equals(((JLabel) c).getText()))
					{
						found[0] = c.getForeground();
						return;
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return found[0];
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

	@Test
	public void aClueTierNeverOpenedReadsDimAndOneOpenedDoesNot() throws Exception
	{
		ChroniclePanel p = panel(SOME, "chronicle-dim-clue");
		assertNotEquals("the opened tier went dim with the rest",
			DIM, nameColour(p, "buildClues", "Hard"));
		assertEquals("a tier with no casket behind it should be dim",
			DIM, nameColour(p, "buildClues", "Beginner"));
	}

	@Test
	public void anUnfinishedDiaryTierReadsDimAndAFinishedOneDoesNot() throws Exception
	{
		ChroniclePanel p = panel(SOME, "chronicle-dim-diary");
		Color easy = nameColour(p, "buildDiaries", "Easy");
		Color medium = nameColour(p, "buildDiaries", "Medium");
		assertNotEquals("the finished tier went dim with the rest", DIM, easy);
		assertEquals("an unfinished tier should be dim", DIM, medium);
	}

	@Test
	public void aDoneCombatTaskIsGreenAndAnUndoneOneIsRed() throws Exception
	{
		ChroniclePanel p = panel(SOME, "chronicle-colour-combat");
		openFold(p, "ca:Aberrant Spectre");
		openFold(p, "ca:Barrows");
		assertEquals("a done task should be green",
			green(), nameColour(p, "buildCombatAchievements", "Noxious Foe"));
		assertEquals("an undone task should be red",
			red(), nameColour(p, "buildCombatAchievements", "Novice"));
	}

	@Test
	public void withNoBitsAtAllNothingIsDimmed() throws Exception
	{
		ChroniclePanel p = panel(EMPTY, "chronicle-dim-unknown");
		openFold(p, "ca:Aberrant Spectre");
		java.awt.Color unknown = nameColour(p, "buildCombatAchievements", "Noxious Foe");
		assertNotEquals("an unknown combat board dimmed itself into a wrong answer",
			DIM, unknown);
		assertNotEquals("an unknown board called a task done", green(), unknown);
		assertNotEquals("an unknown board called a task undone", red(), unknown);
		assertNotEquals("an unknown diary board dimmed itself into a wrong answer",
			DIM, nameColour(p, "buildDiaries", "Easy"));
	}

	@Test
	public void anUnstartedQuestReadsDimAndAFinishedOneDoesNot() throws Exception
	{
		ChroniclePanel p = panel(SOME, "chronicle-dim-quests");
		openFold(p, "quests:COMPLETE");
		openFold(p, "quests:NOT STARTED");
		assertNotEquals("the finished quest went dim with the rest",
			DIM, nameColour(p, "buildQuests", "Cook's Assistant"));
		assertEquals("an unstarted quest should be dim",
			DIM, nameColour(p, "buildQuests", "Dragon Slayer II"));
	}

	@Test
	public void tasksTheTableCannotNameAreSaidOutLoudNotCountedIn() throws Exception
	{
		java.util.List<String> said = new java.util.ArrayList<>();
		ChroniclePanel p = panel(AHEAD, "chronicle-ahead");
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				JPanel into = new JPanel();
				java.lang.reflect.Method m = ChroniclePanel.class
					.getDeclaredMethod("buildCombatAchievements", JPanel.class);
				m.setAccessible(true);
				m.invoke(p, into);
				List<Component> flat = new ArrayList<>();
				flatten(into, flat);
				for (Component c : flat)
				{
					if (c instanceof JLabel && ((JLabel) c).getText() != null)
					{
						said.add(((JLabel) c).getText());
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		String all = String.join(" | ", said);
		assertTrue("the fraction counted ids the board cannot account for: " + all,
			all.contains("3 / 655"));
		assertTrue("the two unnameable tasks were silently dropped: " + all,
			all.contains("also done 2 combat"));
		assertTrue("and the table's own total stood in for the game's, which this"
			+ " journal has never heard: " + all, all.contains("2,697"));
	}
}
