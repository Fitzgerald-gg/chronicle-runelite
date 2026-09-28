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

	@Test
	public void theTableStillSaysWhatTheHardcodedFiguresSaid() throws Exception
	{
		assertEquals(10, tierSize("easy", -1));
		assertEquals(19, tierSize("medium", -1));
		assertEquals(10, tierSize("hard", -1));
	}

	@Test
	public void anUnreadableBundleKeepsTheOldBehaviourRatherThanClaimingCompletion()
		throws Exception
	{
		java.lang.reflect.Field f = AchievementSync.class.getDeclaredField("bundledDiaries");
		f.setAccessible(true);
		Object saved = f.get(SYNC);
		try
		{
			f.set(SYNC, new JsonObject());
			assertEquals("the fallback is the figure that was hardcoded, and a tier "
				+ "size of zero would call every tier finished", 10, tierSize("easy", 10));
		}
		finally
		{
			f.set(SYNC, saved);
		}
	}
}
