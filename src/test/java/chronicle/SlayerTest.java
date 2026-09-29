/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SlayerTest
{
	private static final String DONE = "You've completed 214 tasks and received 25 points, "
		+ "giving you a total of 4,200; return to a Slayer master.";

	private final Harness h = new Harness().login();

	private String task = "Nechryael";

	private void kill(String npc, int id, int remaining, int initial)
	{
		h.task(task, remaining, initial).kill(npc, id, 526, 1).tick();
	}

	private JsonArray tasks()
	{
		JsonObject sl = h.journal().getAsJsonObject("slayer");
		return sl == null ? new JsonArray() : sl.getAsJsonArray("tasks");
	}

	private JsonObject seg(int fromNewest)
	{
		JsonArray t = tasks();
		return t.get(t.size() - 1 - fromNewest).getAsJsonObject();
	}

	private void finish(String creature, int killed)
	{
		h.chat("You have completed your task! You killed " + killed + " " + creature + ".");
		h.chat(DONE);
		h.task("", 0, 0);
	}

	@Test
	public void onTaskKillsBuildOneSegmentAndOffTaskKillsStayOut()
	{
		kill("Nechryael", 11, 199, 200);
		kill("Nechryael", 11, 198, 200);
		kill("Man", 3106, 198, 200);
		JsonObject s = seg(0);
		assertEquals(1, tasks().size());
		assertEquals("Nechryael", s.get("task").getAsString());
		assertEquals(2, s.get("kills").getAsInt());
		assertEquals(200, s.get("assignment").getAsInt());
		assertTrue(s.get("open").getAsBoolean());
		assertEquals(2, s.getAsJsonObject("monsters").get("Nechryael").getAsInt());
		assertFalse(s.getAsJsonObject("monsters").has("Man"));
		assertEquals(200, s.get("value").getAsInt());
	}

	@Test
	public void aTaskIsMatchedByNpcIdWhereTheNameIsShared()
	{
		task = "Elves";
		kill("Guard", 9183, 99, 100);
		kill("Guard", 3010, 98, 100);
		assertEquals(1, seg(0).get("kills").getAsInt());
	}

	@Test
	public void completionClosesTheTaskAndCountsTheKillsThatLeftNoLoot()
	{
		kill("Nechryael", 11, 2, 150);
		kill("Nechryael", 11, 1, 150);
		finish("Nechryael", 150);
		h.ticks(6);
		JsonObject s = seg(0);
		assertFalse(s.get("open").getAsBoolean());
		assertEquals(150, s.get("kills").getAsInt());
		assertEquals(148, s.get("noLootKills").getAsInt());
		assertEquals(214, h.journal().getAsJsonObject("slayer").get("completed").getAsInt());
		assertEquals(1, h.feed("SLAYER").size());

		h.kill("Nechryael", 11, 526, 1).tick();
		kill("Nechryael", 11, 129, 130);
		assertEquals(tasks().toString(), 2, tasks().size());
		assertTrue(seg(0).get("open").getAsBoolean());
		assertEquals(1, seg(0).get("kills").getAsInt());
	}

	@Test
	public void theFinishingKillLandsOnTheTaskItCompleted()
	{
		kill("Nechryael", 11, 2, 150);
		kill("Nechryael", 11, 1, 150);
		finish("Nechryael", 150);
		h.kill("Nechryael", 11, 526, 1).tick();
		assertEquals(1, tasks().size());
		JsonObject s = seg(0);
		assertEquals(150, s.get("kills").getAsInt());
		assertEquals(147, s.get("noLootKills").getAsInt());
		assertEquals(3, s.getAsJsonObject("monsters").get("Nechryael").getAsInt());
		assertFalse(h.kill("Man", 3106, 526, 1).tick().journal().getAsJsonObject("drops")
			.getAsJsonObject("Man").toString().contains("slayer"));
	}

	@Test
	public void aFinishingKillLongAfterTheTaskClosedStartsANewOne()
	{
		kill("Nechryael", 11, 1, 150);
		finish("Nechryael", 150);
		h.edit(j -> seg(j).addProperty("ts", System.currentTimeMillis() / 1000L - 86_400));
		h.kill("Nechryael", 11, 526, 1).tick();
		assertEquals(2, tasks().size());
		assertEquals(1, seg(0).get("kills").getAsInt());
		assertEquals(150, seg(1).get("kills").getAsInt());
	}

	private static JsonObject seg(JsonObject journal)
	{
		JsonArray t = journal.getAsJsonObject("slayer").getAsJsonArray("tasks");
		return t.get(t.size() - 1).getAsJsonObject();
	}

	@Test
	public void theCounterSplitsRepeatsCorrectsStaleSizesAndResumesJuggledTasks()
	{
		task = "Dust devils";
		kill("Dust devil", 423, 1, 120);
		kill("Dust devil", 423, 0, 120);
		kill("Dust devil", 423, 284, 222);
		kill("Dust devil", 423, 283, 222);
		assertEquals(2, tasks().size());
		assertEquals(285, seg(0).get("assignment").getAsInt());

		task = "Hellhounds";
		kill("Hellhound", 104, 50, 100);
		task = "Greater demons";
		kill("Greater demon", 2025, 30, 80);
		task = "Hellhounds";
		kill("Hellhound", 104, 49, 100);
		assertEquals("Hellhounds", seg(0).get("task").getAsString());
		assertEquals(2, seg(0).get("kills").getAsInt());
		assertEquals(4, tasks().size());
	}
}
