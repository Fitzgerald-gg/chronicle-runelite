/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The per-task combat achievement bits, and the arithmetic that reads them.
 *
 * <p>The game states points and which tiers are unlocked, and nothing about WHICH
 * tasks are done, so the journal could only ever name the ones it happened to
 * watch land. CA_TASK_COMPLETED_0 to _20 carry all of them: task id N is bit N%32
 * of word N/32. Getting that arithmetic wrong does not throw; it silently ticks
 * the wrong achievements.
 */
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
		try (InputStreamReader r = new InputStreamReader(
			ChroniclePanel.class.getResourceAsStream(
				"/chronicle/osrs_combat_achievements.json"), StandardCharsets.UTF_8))
		{
			return new Gson().fromJson(r, JsonObject.class).getAsJsonObject("tasks");
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	/**
	 * TRAP: the varps are NOT contiguous. They run 3116 upward, then jump to 4496,
	 * 4721 and 5673 as tasks were added over the years. A loop over base + i reads
	 * varps belonging to something else entirely, and every bit it finds there is
	 * a combat achievement ticked at random.
	 */
	@Test
	public void theVarpsAreNamedBecauseTheyAreNotSequential()
	{
		int[] head = {VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1,
			VarPlayerID.CA_TASK_COMPLETED_2};
		assertEquals(head[0] + 1, head[1]);
		assertEquals(head[1] + 1, head[2]);
		// and then they stop being sequential, which is the whole point
		assertNotEquals("if these ever become contiguous the comment is stale, not the"
				+ " code",
			VarPlayerID.CA_TASK_COMPLETED_17 + 1, VarPlayerID.CA_TASK_COMPLETED_18);
	}

	/** Every task the table knows has a bit to live in. */
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

	/** And the list is the whole run, with nothing skipped in the middle. */
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

	/**
	 * A client with nothing set. snapshot() walks the quest list before it reaches
	 * the bits, and Quest.getState reads the script's return off the int stack, so
	 * the stack has to exist: 1 is "not started" for every quest.
	 */
	private static Client blankClient()
	{
		Client client = Mockito.mock(Client.class);
		Mockito.when(client.getIntStack()).thenReturn(new int[]{1});
		return client;
	}

	/**
	 * The writer end to end, against a client whose varps are set the way the game
	 * would set them. This is the test that bites: the loop can read the right
	 * varps and still file every bit under the wrong id.
	 */
	@Test
	public void theWriterTurnsBitsIntoTheIdsTheTableUses() throws Exception
	{
		Client client = blankClient();
		int[] w = words();
		// Task 0 is bit 0 of the first word; task 33 is bit 1 of the second; task
		// 445 is bit 29 of the fourteenth. Three words apart so a reversed or
		// off-by-one mapping cannot land on all three by luck.
		Mockito.when(client.getVarpValue(w[0])).thenReturn(1);
		Mockito.when(client.getVarpValue(w[1])).thenReturn(1 << 1);
		Mockito.when(client.getVarpValue(w[13])).thenReturn(1 << 29);

		JsonArray done = new AchievementSync(client).snapshot()
			.getAsJsonObject("combat").getAsJsonArray("tasksDone");

		Set<Integer> ids = new HashSet<>();
		done.forEach(e -> ids.add(e.getAsInt()));
		assertEquals("three bits set, three ids written",
			new HashSet<>(Arrays.asList(0, 33, 445)), ids);
	}

	/**
	 * TRAP: a mask read the wrong way round. Testing for == 0 instead of != 0 is
	 * a one-character slip that still produces a plausible-looking array of ids,
	 * and on a fresh account it produces all 672 of them: a player who has done
	 * nothing reads as a player who has done everything. The empty client is the
	 * case that makes it obvious.
	 */
	@Test
	public void anEmptyWordContributesNothing() throws Exception
	{
		Client client = blankClient();
		Mockito.when(client.getVarpValue(Mockito.anyInt())).thenReturn(0);

		assertEquals("a player with no tasks done has no ids", 0,
			new AchievementSync(client).snapshot()
				.getAsJsonObject("combat").getAsJsonArray("tasksDone").size());
	}

	/**
	 * And a full word writes all 32, including bit 31. Read into a signed int that
	 * bit is the sign bit, so a loop guarding on bits &gt; 0 silently drops it.
	 */
	@Test
	public void theTopBitOfAWordIsNotLostToTheSign() throws Exception
	{
		Client client = blankClient();
		Mockito.when(client.getVarpValue(words()[0])).thenReturn(-1);

		Set<Integer> ids = new HashSet<>();
		new AchievementSync(client).snapshot().getAsJsonObject("combat")
			.getAsJsonArray("tasksDone").forEach(e -> ids.add(e.getAsInt()));
		assertEquals(32, ids.size());
		assertTrue("bit 31 is the sign bit and is still a task", ids.contains(31));
	}
}
