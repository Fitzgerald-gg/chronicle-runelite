/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class CombatTaskBitsTest
{
	private static int[] words() throws Exception
	{
		Field f = AchievementSync.class.getDeclaredField("CA_TASK_COMPLETED");
		f.setAccessible(true);
		return (int[]) f.get(null);
	}

	private static JsonObject tasks()
	{
		return CombatAchievementsTest.tasks();
	}

	@Test
	public void theVarpsAreNamedBecauseTheyAreNotSequential() throws Exception
	{
		int[] w = words();
		for (int i = 1; i <= 12; i++)
		{
			assertEquals("the contiguous head is shorter than the comment says",
				w[i - 1] + 1, w[i]);
		}
		assertNotEquals("if these ever become contiguous the comment is stale, not"
			+ " the code", w[12] + 1, w[13]);
		int breaks = 0;
		for (int i = 1; i < w.length; i++)
		{
			if (w[i] != w[i - 1] + 1)
			{
				breaks++;
			}
		}
		assertEquals("the comment names the breaks one by one, so it goes stale if"
			+ " their number changes", 7, breaks);
	}

	@Test
	public void theBitsReachEveryTaskInTheTable() throws Exception
	{
		int slots = words().length * 32;
		int highest = -1;
		for (String id : tasks().keySet())
		{
			highest = Math.max(highest, Integer.parseInt(id));
		}
		assertTrue("task id " + highest + " has no bit: only " + slots + " slots",
			highest < slots);
		assertTrue("the words do not even cover the task count", slots >= tasks().size());
	}

	@Test
	public void everyWordIsListedOnce() throws Exception
	{
		int[] w = words();
		assertEquals("a word was dropped from the list", 21, w.length);
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (int v : w)
		{
			assertTrue("varp " + v + " is listed twice", seen.add(v));
			assertTrue("varp " + v + " is not a varp", v > 0);
		}
	}

	private static Client blankClient()
	{
		Client client = Mockito.mock(Client.class);
		Mockito.when(client.getIntStack()).thenReturn(new int[]{1});
		return client;
	}

	@Test
	public void theWriterTurnsBitsIntoTheIdsTheTableUses() throws Exception
	{
		Client client = blankClient();
		int[] w = words();
		Mockito.when(client.getVarpValue(w[0])).thenReturn(1);
		Mockito.when(client.getVarpValue(w[1])).thenReturn(1 << 1);
		Mockito.when(client.getVarpValue(w[13])).thenReturn(1 << 29);

		JsonArray done = new AchievementSync(client, new com.google.gson.Gson()).snapshot()
			.getAsJsonObject("combat").getAsJsonArray("tasksDone");

		Set<Integer> ids = new HashSet<>();
		done.forEach(e -> ids.add(e.getAsInt()));
		assertEquals("three bits set, three ids written",
			new HashSet<>(Arrays.asList(0, 33, 445)), ids);
	}

	@Test
	public void anEmptyWordContributesNothing() throws Exception
	{
		Client client = blankClient();
		Mockito.when(client.getVarpValue(Mockito.anyInt())).thenReturn(0);

		assertEquals("a player with no tasks done has no ids", 0,
			new AchievementSync(client, new com.google.gson.Gson()).snapshot()
				.getAsJsonObject("combat").getAsJsonArray("tasksDone").size());
	}

	@Test
	public void theTopBitOfAWordIsNotLostToTheSign() throws Exception
	{
		Client client = blankClient();
		Mockito.when(client.getVarpValue(words()[0])).thenReturn(-1);

		Set<Integer> ids = new HashSet<>();
		new AchievementSync(client, new com.google.gson.Gson()).snapshot().getAsJsonObject("combat")
			.getAsJsonArray("tasksDone").forEach(e -> ids.add(e.getAsInt()));
		assertEquals(32, ids.size());
		assertTrue("bit 31 is the sign bit and is still a task", ids.contains(31));
	}
}
