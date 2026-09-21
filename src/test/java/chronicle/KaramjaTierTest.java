/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Karamja is the one region the game will not answer for directly.
 *
 * <p>Every other diary tier has a completion varbit. Karamja's easy, medium and
 * hard have only a count of tasks done, so a tier is finished when that count
 * reaches the number of tasks the tier holds - a number the plugin has to
 * supply. It was supplied twice: once as a literal in the sync and once as the
 * bundled table's own contents. Regenerating the table moved one and not the
 * other, so adding a Karamja easy task would have left the tier reading
 * finished at ten of eleven.
 */
public class KaramjaTierTest
{
	private static final AchievementSync SYNC =
		new AchievementSync(null, new Gson());

	private static int tierSize(String tier, int fallback) throws Exception
	{
		Method m = AchievementSync.class.getDeclaredMethod("tierSize", String.class, int.class);
		m.setAccessible(true);
		return (Integer) m.invoke(SYNC, tier, fallback);
	}

	private static JsonObject karamja()
	{
		try (InputStreamReader r = new InputStreamReader(
			ChroniclePanel.class.getResourceAsStream(
				"/chronicle/osrs_achievement_diaries.json"), StandardCharsets.UTF_8))
		{
			return new Gson().fromJson(r, JsonObject.class)
				.getAsJsonObject("diaries").getAsJsonObject("Karamja");
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	@Test
	public void theThresholdIsTheTableSOwnTierSize() throws Exception
	{
		for (String tier : new String[]{"easy", "medium", "hard"})
		{
			assertEquals(tier + " threshold does not match the bundled table",
				karamja().getAsJsonArray(tier).size(), tierSize(tier, -1));
		}
	}

	/** The figures it replaced, so a regeneration that moves them is visible. */
	@Test
	public void theTableStillSaysWhatTheHardcodedFiguresSaid() throws Exception
	{
		assertEquals(10, tierSize("easy", -1));
		assertEquals(19, tierSize("medium", -1));
		assertEquals(10, tierSize("hard", -1));
	}

	/**
	 * TRAP: a missing or unreadable bundle. Falling through to zero would report
	 * every Karamja tier finished on an account that has done none of them, which
	 * is worse than the stale literal this replaced.
	 */
	@Test
	public void anUnreadableBundleKeepsTheOldBehaviourRatherThanClaimingCompletion()
		throws Exception
	{
		java.lang.reflect.Field f = AchievementSync.class.getDeclaredField("bundledDiaries");
		f.setAccessible(true);
		Object saved = f.get(SYNC);
		try
		{
			f.set(SYNC, new JsonObject());   // present but empty: no "diaries" key
			assertEquals("the fallback is the figure that was hardcoded, and a tier "
				+ "size of zero would call every tier finished", 10, tierSize("easy", 10));
		}
		finally
		{
			f.set(SYNC, saved);
		}
	}
}
