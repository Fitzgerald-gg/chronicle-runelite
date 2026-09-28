/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfHealingRosterTest
{
	@SuppressWarnings("unchecked")
	private static List<Object> roster() throws Exception
	{
		java.lang.reflect.Method m = ChroniclePanel.class
			.getDeclaredMethod("bossRoster", com.google.gson.Gson.class);
		m.setAccessible(true);
		java.lang.reflect.Field cache = ChroniclePanel.class.getDeclaredField("bossRoster");
		cache.setAccessible(true);
		cache.set(null, null);
		return (List<Object>) m.invoke(null, new com.google.gson.Gson());
	}

	private static String name(Object boss) throws Exception
	{
		java.lang.reflect.Field f = boss.getClass().getDeclaredField("name");
		f.setAccessible(true);
		return (String) f.get(boss);
	}

	private static int sprite(Object boss) throws Exception
	{
		java.lang.reflect.Field f = boss.getClass().getDeclaredField("sprite");
		f.setAccessible(true);
		return f.getInt(boss);
	}

	@Test
	public void theRosterIsWhateverRuneLiteSaysItIs() throws Exception
	{
		Set<String> mine = new HashSet<>();
		for (Object b : roster())
		{
			mine.add(name(b));
		}
		Set<String> theirs = new HashSet<>();
		for (HiscoreSkill s : HiscoreSkill.values())
		{
			if (s.getType() == HiscoreSkillType.BOSS)
			{
				theirs.add(s.getName());
			}
		}
		assertFalse("RuneLite lists no bosses at all, which cannot be right", theirs.isEmpty());
		assertEquals("the roster and RuneLite's own list have parted", theirs, mine);
	}

	@Test
	public void everyBossBringsItsOwnSprite() throws Exception
	{
		for (Object b : roster())
		{
			assertTrue(name(b) + " has no sprite", sprite(b) > 0);
		}
	}

	@Test
	public void theFallbackIsStillThere()
	{
		com.google.gson.JsonArray arr;
		try (java.io.InputStreamReader r = new java.io.InputStreamReader(
			ChroniclePanel.class.getResourceAsStream("/chronicle/osrs_bosses.json"),
			java.nio.charset.StandardCharsets.UTF_8))
		{
			arr = new com.google.gson.Gson().fromJson(r, com.google.gson.JsonArray.class);
		}
		catch (Exception e)
		{
			throw new AssertionError("the fallback roster is gone", e);
		}
		assertTrue("the fallback holds only " + arr.size() + " bosses", arr.size() > 50);
	}

	@Test
	public void aRecentBossIsAlreadyHereWithoutAPluginUpdate() throws Exception
	{
		Set<String> mine = new HashSet<>();
		for (Object b : roster())
		{
			mine.add(name(b));
		}
		int recent = 0;
		for (String late : new String[]{"Yama", "Doom of Mokhaiotl", "The Hueycoatl",
			"Amoxliatl", "Araxxor"})
		{
			if (mine.contains(late))
			{
				recent++;
			}
		}
		assertTrue("none of the recent bosses are in the roster: " + mine.size()
			+ " entries", recent >= 2);
	}
}
