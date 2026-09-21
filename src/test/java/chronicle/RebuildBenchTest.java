package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.Test;

/** Not an assertion: a measurement, run with -Dchronicle.bench=1. */
public class RebuildBenchTest
{
	@Test
	public void howLongDoesABoardTake() throws Exception
	{
		if (System.getProperty("chronicle.bench") == null)
		{
			return;
		}
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
		String real = System.getProperty("chronicle.realJournal");
		PanelPreviewTest.StubPlugin stub;
		if (real != null && new java.io.File(real).isDirectory())
		{
			java.io.File[] js = new java.io.File(real)
				.listFiles((d, n) -> n.endsWith(".json"));
			java.io.File newest = js[0];
			for (java.io.File f : js)
			{
				if (f.length() > newest.length())
				{
					newest = f;
				}
			}
			String rsn = newest.getName().replace(".json", "").replace('-', ' ');
			System.out.println("BENCH journal: " + newest.getName()
				+ "  " + (newest.length() / 1024) + " KB");
			stub = PanelPreviewTest.journalStub(real, rsn);
		}
		else
		{
			stub = PanelPreviewTest.fixtureStub();
		}
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		ChroniclePanel panel = hold[0];
		PanelPreviewTest.regatherHistory(panel);
		PanelPreviewTest.awaitHistory(panel);

		Class<?> viewClass = Class.forName("chronicle.ChroniclePanel$View");
		for (Object v : viewClass.getEnumConstants())
		{
			final Object view = v;
			final long[] best = {Long.MAX_VALUE};
			final int[] rows = {0};
			final int[] tall = {0};
			for (int i = 0; i < 12; i++)
			{
				SwingUtilities.invokeAndWait(() ->
				{
					try
					{
						Field vf = ChroniclePanel.class.getDeclaredField("view");
						vf.setAccessible(true);
						vf.set(panel, view);
						Method tabFor = ChroniclePanel.class
							.getDeclaredMethod("tabFor", viewClass);
						tabFor.setAccessible(true);
						Field tf = ChroniclePanel.class.getDeclaredField("tab");
						tf.setAccessible(true);
						tf.set(panel, tabFor.invoke(panel, view));
						if (System.getProperty("chronicle.benchExpanded") != null)
						{
							for (String big : new String[]{"dropsShown", "slayerShown",
								"journalShown"})
							{
								Field bf = ChroniclePanel.class.getDeclaredField(big);
								bf.setAccessible(true);
								bf.setInt(panel, 100000);
							}
						}
						Method rebuild = ChroniclePanel.class.getDeclaredMethod("rebuild");
						rebuild.setAccessible(true);
						// What the client does: build, lay out at the height the
						// content actually wants, and paint it. Laying out at a
						// fixed 800 and never painting measures the cheap half.
						panel.setSize(242, 800);
						long t0 = System.nanoTime();
						rebuild.invoke(panel);
						layoutAll(panel);
						int want = Math.max(300, panel.getPreferredSize().height);
						panel.setSize(242, want);
						layoutAll(panel);
						java.awt.image.BufferedImage img =
							new java.awt.image.BufferedImage(242, Math.min(want, 20000),
								java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D g = img.createGraphics();
						panel.paint(g);
						g.dispose();
						long dt = System.nanoTime() - t0;
						best[0] = Math.min(best[0], dt);
						tall[0] = want;
						List<Component> flat = new ArrayList<>();
						flatten(panel, flat);
						rows[0] = flat.size();
					}
					catch (Exception e)
					{
						throw new RuntimeException(e);
					}
				});
			}
			System.out.printf("BENCH %-10s %7.2f ms  (%d components, %dpx)%n",
				((Enum<?>) v).name(), best[0] / 1_000_000.0, rows[0], tall[0]);
			if ("DROPS".equals(((Enum<?>) v).name()))
			{
				SwingUtilities.invokeAndWait(() ->
				{
					try
					{
						Method m = ChroniclePanel.class.getDeclaredMethod("buildDrops");
						m.setAccessible(true);
						long t = Long.MAX_VALUE;
						for (int i = 0; i < 10; i++)
						{
							long t0 = System.nanoTime();
							m.invoke(panel);
							t = Math.min(t, System.nanoTime() - t0);
						}
						System.out.printf("BENCH   ..buildDrops alone %6.2f ms%n",
							t / 1_000_000.0);
						Method src = ChroniclePanel.class.getDeclaredMethod("sources");
						src.setAccessible(true);
						long t2 = Long.MAX_VALUE;
						for (int i = 0; i < 10; i++)
						{
							long t0 = System.nanoTime();
							src.invoke(panel);
							t2 = Math.min(t2, System.nanoTime() - t0);
						}
						System.out.printf("BENCH   ..sources() alone %6.2f ms%n",
							t2 / 1_000_000.0);
					}
					catch (Exception e)
					{
						throw new RuntimeException(e);
					}
				});
			}
		}
	}

	private static void layoutAll(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				layoutAll(k);
			}
		}
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
}
