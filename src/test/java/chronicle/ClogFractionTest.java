/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClogFractionTest
{
	private static List<String> board(int finished, int available, String builder)
		throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		Field f = stub.getClass().getDeclaredField("clogFinished");
		f.setAccessible(true);
		f.setInt(stub, finished);
		Field a = stub.getClass().getDeclaredField("clogAvailable");
		a.setAccessible(true);
		a.setInt(stub, available);

		final List<String> out = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				ChroniclePanel p = new ChroniclePanel(stub);
				Method m = ChroniclePanel.class.getDeclaredMethod(builder);
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(p), flat);
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

	@Test
	public void withNoTotalYetTheJournalDoesNotReportAFinishedLog() throws Exception
	{
		List<String> said = board(412, 0, "buildJournal");
		assertFalse("an unsynced log read as complete: " + said, has(said, "412 / 412"));
		assertTrue("the count it does know was thrown away: " + said,
			has(said, "412 obtained"));
	}

	@Test
	public void withNoTotalYetTheBoardSaysSoAndKeepsTheCount() throws Exception
	{
		List<String> said = board(412, 0, "buildLog");
		assertTrue("the obtained count was lost: " + said, has(said, "412"));
		assertTrue("nothing told the reader where the total comes from: " + said,
			has(said, "Open your log in game"));
		assertFalse("a denominator was invented: " + said, has(said, "1,712"));
	}

	@Test
	public void aRealTotalBelowTheOldFloorIsUsedAsGiven() throws Exception
	{
		List<String> said = board(200, 800, "buildLog");
		assertTrue("the game's own total was overridden: " + said, has(said, "/ 800"));
		assertFalse(has(said, "1,712"));
	}

	@Test
	public void withATotalTheFractionReadsNormally() throws Exception
	{
		List<String> said = board(412, 1568, "buildLog");
		assertTrue(said.toString(), has(said, "412 / 1,568"));
	}
}
