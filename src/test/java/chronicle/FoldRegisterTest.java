/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import net.runelite.client.game.ItemManager;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FoldRegisterTest
{
	private static final String STATS_SECTION = "Skilling:Roads";
	private static final String STATS_VERB = "Skilling:craft:smelted";
	private static final String PETS_PAGE = "pets:All Pets:Abyssal orphan";
	private static final String HOME_XP = "home:xp";

	@BeforeClass
	public static void headless()
	{
		System.setProperty("java.awt.headless", "true");
	}

	private static ChroniclePanel panel() throws Exception
	{
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() -> holder[0] = new ChroniclePanel(
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class))));
		return holder[0];
	}

	@SuppressWarnings("unchecked")
	private static Set<String> register(ChroniclePanel p) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("openFolds");
		f.setAccessible(true);
		return (Set<String>) f.get(p);
	}

	private static void toggle(ChroniclePanel p, String key) throws Exception
	{
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("toggleFold", String.class);
			m.setAccessible(true);
			m.invoke(p, key);
		});
	}

	private static boolean open(ChroniclePanel p, String key) throws Exception
	{
		final boolean[] out = {false};
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("foldOpen", String.class);
			m.setAccessible(true);
			out[0] = (Boolean) m.invoke(p, key);
		});
		return out[0];
	}

	private static void rebuild(ChroniclePanel p) throws Exception
	{
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("rebuild");
			m.setAccessible(true);
			m.invoke(p);
		});
	}

	private static void switchAccount(ChroniclePanel p) throws Exception
	{
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("resetAccountCaches");
			m.setAccessible(true);
			m.invoke(p);
		});
	}

	@Test
	public void theRegisterIsReachableUnderItsName() throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("openFolds");
		assertTrue("the register must not be swapped out from under a fold",
			Modifier.isFinal(f.getModifiers()));
		assertTrue("the register holds keys, not a boolean per fold",
			Set.class.isAssignableFrom(f.getType()));
		assertTrue(register(panel()).isEmpty());
	}

	@Test
	public void everyKindOfFoldOpensAndShuts() throws Exception
	{
		ChroniclePanel p = panel();
		for (String key : new String[]{STATS_SECTION, STATS_VERB, PETS_PAGE, HOME_XP})
		{
			assertFalse(key + " did not start folded", open(p, key));
			toggle(p, key);
			assertTrue(key + " did not open", open(p, key));
			assertTrue(key + " is not in the register", register(p).contains(key));
			toggle(p, key);
			assertFalse(key + " did not shut", open(p, key));
			assertFalse(key + " is still in the register", register(p).contains(key));
		}
	}

	@Test
	public void severalFoldsStandOpenAtOnce() throws Exception
	{
		ChroniclePanel p = panel();
		toggle(p, STATS_SECTION);
		toggle(p, PETS_PAGE);
		toggle(p, HOME_XP);
		assertEquals(3, register(p).size());
		assertTrue(open(p, STATS_SECTION));
		assertTrue(open(p, PETS_PAGE));
		assertTrue(open(p, HOME_XP));
	}

	@Test
	public void anOpenFoldSurvivesARebuild() throws Exception
	{
		ChroniclePanel p = panel();
		toggle(p, STATS_VERB);
		rebuild(p);
		rebuild(p);
		assertTrue("the rebuild shut the fold", open(p, STATS_VERB));
	}

	@Test
	public void theRegisterClearsOnAnAccountSwitch() throws Exception
	{
		ChroniclePanel p = panel();
		toggle(p, STATS_SECTION);
		toggle(p, PETS_PAGE);
		toggle(p, HOME_XP);
		assertFalse(register(p).isEmpty());
		switchAccount(p);
		assertTrue("a fold outlived the account it was opened over",
			register(p).isEmpty());
		assertFalse(open(p, STATS_SECTION));
		assertFalse(open(p, PETS_PAGE));
		assertFalse(open(p, HOME_XP));
	}

	@Test
	public void homesXpFoldIsAKeyInTheSameRegister() throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("FOLD_HOME_XP");
		f.setAccessible(true);
		assertEquals(HOME_XP, f.get(null));
		ChroniclePanel p = panel();
		toggle(p, HOME_XP);
		assertTrue(register(p).contains(HOME_XP));
	}

	private interface ThrowingRunnable
	{
		void run() throws Exception;
	}

	private static void edt(ThrowingRunnable r) throws Exception
	{
		final Exception[] err = {null};
		javax.swing.SwingUtilities.invokeAndWait(() ->
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
