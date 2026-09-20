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

/**
 * One rule across four boards: brightness says whether you have the thing.
 *
 * <p>A held collection log slot reads bright and an absent one reads dim, and
 * the combat, diary and clue boards were brought onto that rule rather than
 * each answering in a word of its own. The rule is invisible in a structural
 * test, since a dim row and a bright row have the same shape, so a board can
 * slide off it without anything failing. That is what these hold.
 */
public class AbsenceReadsDimTest
{
	private static final Color DIM = ColorScheme.LIGHT_GRAY_COLOR.darker();

	// A record with nothing in it: no diary tier done, no clue casket opened, and
	// no combat achievement bits, so every board is asked about things absent.
	private static final String EMPTY =
		"{\"schema\":1,\"rsn\":\"Somebody\",\"drops\":{},"
		+ "\"collection_log\":{\"finished\":0,\"available\":1717},"
		+ "\"trackers\":{},\"skills\":{},\"feed\":[]}";

	// The same, plus the game's own answers: one clue tier opened, one diary tier
	// done, and the combat bits saying task 0 is done and nothing else. Each board
	// then has both a present thing and an absent one to tell apart.
	private static final String SOME =
		"{\"schema\":1,\"rsn\":\"Somebody\",\"drops\":{"
		+ "\"Clue Scroll (Hard)\":{\"kc\":3,\"loots\":3,\"value\":900,"
		+ "\"items\":{\"1\":{\"id\":1,\"name\":\"Coins\",\"qty\":900,\"value\":900}}}},"
		+ "\"collection_log\":{\"finished\":0,\"available\":1717},"
		+ "\"achievements\":{\"diaries\":{\"ardougne\":{\"easy\":true,\"medium\":false,"
		+ "\"hard\":false,\"elite\":false}},"
		+ "\"combat\":{\"points\":10,\"tasksDone\":[0]}},"
		+ "\"trackers\":{},\"skills\":{},\"feed\":[]}";

	private static ChroniclePanel panel(String journal, String dirName) throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), dirName);
		//noinspection ResultOfMethodCallIgnored
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

	/** The colour the named row draws its NAME in, or null if the row is absent. */
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
	public void anUndoneCombatTaskReadsDimAndADoneOneDoesNot() throws Exception
	{
		ChroniclePanel p = panel(SOME, "chronicle-dim-combat");
		// task 0, the only bit set
		assertNotEquals("the done task went dim with the rest",
			DIM, nameColour(p, "buildCombatAchievements", "Noxious Foe"));
		assertEquals("an undone task should be dim",
			DIM, nameColour(p, "buildCombatAchievements", "Barrows Novice"));
	}

	/**
	 * TRAP: a journal written before the combat bits were captured. No bits is not
	 * the same as no tasks done, and dimming the whole board would report an
	 * account that has done a hundred of them as having done none.
	 */
	@Test
	public void withNoBitsAtAllNothingIsDimmed() throws Exception
	{
		ChroniclePanel p = panel(EMPTY, "chronicle-dim-unknown");
		assertNotEquals("an unknown combat board dimmed itself into a wrong answer",
			DIM, nameColour(p, "buildCombatAchievements", "Noxious Foe"));
		// The same trap on the diary board, which had the rule applied to it
		// without the guard: no diaries block is not forty eight unfinished tiers.
		assertNotEquals("an unknown diary board dimmed itself into a wrong answer",
			DIM, nameColour(p, "buildDiaries", "Easy"));
	}
}
