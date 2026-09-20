/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A line that repeats the line above it is not a line.
 *
 * <p>"Drops taken" is the loot events less the kills that left a stack on the
 * floor. Where a sitting left nothing - which is most sittings - that is the
 * received count exactly, and the strip printed the same number twice, one
 * under the other, the second saying nothing the first had not.
 *
 * <p>It is drawn only where it differs, which is exactly when "Left behind" is
 * drawn too, so the two arrive together and mean something between them.
 */
public class SittingStripTest
{
	private static PanelPreviewTest.StubPlugin stub;
	private static ChroniclePanel panel;

	@BeforeClass
	public static void build() throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(
					new net.runelite.client.ui.laf.RuneLiteLAF());
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		stub = PanelPreviewTest.fixtureStub();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		panel = hold[0];
	}

	private static List<String> home() throws Exception
	{
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildHome");
				m.setAccessible(true);
				collect((Component) m.invoke(panel), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return said;
	}

	private static void collect(Component c, List<String> out)
	{
		if (c instanceof JLabel && ((JLabel) c).getText() != null
			&& !((JLabel) c).getText().isEmpty())
		{
			out.add(((JLabel) c).getText());
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				collect(k, out);
			}
		}
	}

	/** TRAP: a sitting that dropped nothing on the floor, which is most of them. */
	@Test
	public void aSittingThatLeftNothingDoesNotSayTheSameNumberTwice() throws Exception
	{
		stub.sessionUntakenKills = 0;
		stub.sessionUntaken = new long[]{0, 0};
		List<String> said = home();
		assertTrue("the strip lost the drops it did receive: " + said,
			said.contains("Drops received"));
		assertFalse("the strip repeated the received count under its own name: "
			+ said, said.contains("Drops taken"));
		assertFalse("and claimed a floor it did not leave", said.contains("Left behind"));
	}

	/** And where they differ, both halves of the difference are on screen. */
	@Test
	public void aSittingThatLeftSomethingSaysBothHalves() throws Exception
	{
		stub.sessionUntakenKills = 6;
		stub.sessionUntaken = new long[]{9, 44_120L};
		List<String> said = home();
		assertTrue("the taken count went missing where it actually says something: "
			+ said, said.contains("Drops taken"));
		assertTrue("and what was left with it: " + said, said.contains("Left behind"));
		int taken = said.indexOf("Drops taken");
		assertTrue("the taken count is not the received count less the kills that "
			+ "left a stack: " + said,
			said.get(taken + 1).equals(String.valueOf(stub.sessionLoots - 6)));
	}
}
