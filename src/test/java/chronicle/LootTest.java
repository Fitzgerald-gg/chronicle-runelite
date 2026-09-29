/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.List;
import net.runelite.api.GameState;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.Test;
import static chronicle.Harness.has;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LootTest
{
	private static final WorldPoint HERE = new WorldPoint(3200, 3200, 0);

	private final Harness h = new Harness().login();

	private JsonObject drops()
	{
		return h.journal().getAsJsonObject("drops");
	}

	@Test
	public void aKillIsFiledUnderItsNpcWithItsItems()
	{
		h.kill("Dust devil", 7249, 526, 1, 1618, 2);
		JsonObject src = drops().getAsJsonObject("Dust devil");
		assertEquals(1, src.get("loots").getAsInt());
		assertEquals(100 + 3600, src.get("value").getAsLong());
		assertEquals(2, src.getAsJsonObject("items").getAsJsonObject("1618").get("qty").getAsInt());
		assertEquals("Uncut diamond", src.getAsJsonObject("items").getAsJsonObject("1618").get("name").getAsString());
		assertTrue(src.get("first_seen").getAsLong() > 0);
	}

	@Test
	public void theLootTrackerHandsOverOnlyWhatTheServerDoesNot()
	{
		Object[][] cases = {
			{"Barrows", LootRecordType.EVENT, true},
			{"Man", LootRecordType.PICKPOCKET, true},
			{"Vorkath", LootRecordType.NPC, false},
			{"Someone", LootRecordType.PLAYER, false},
		};
		for (Object[] c : cases)
		{
			h.loot((String) c[0], (LootRecordType) c[1], 995, 5000);
			assertEquals(c[0] + " " + c[1], c[2], drops().has((String) c[0]));
		}
	}

	@Test
	public void oneKillReportedByBothRoutesIsOneLoot()
	{
		h.kill("Vorkath", 8061, 536, 2);
		h.loot("Vorkath", LootRecordType.NPC, 536, 2);
		h.ticks(5);
		assertEquals(1, drops().getAsJsonObject("Vorkath").get("loots").getAsInt());
	}

	@Test
	public void lootOnThePickpocketTickIsNotAKill()
	{
		h.chat("You pick the man's pocket.");
		h.kill("Man", 3106, 995, 3);
		assertFalse(drops().has("Man"));
		h.tick().kill("Man", 3106, 995, 3);
		assertTrue(drops().has("Man"));
	}

	@Test
	public void aNewBestTimeIsARecordWithWhatItBeat()
	{
		h.chat("Your Vorkath kill count is: 50.");
		h.chat("Fight duration: 1:40.00. Personal best: 1:30.00");
		h.kill("Vorkath", 8061, 536, 2);
		h.tick().chat("Your Vorkath kill count is: 51.");
		h.chat("Fight duration: 1:20.00 (new personal best)");
		h.kill("Vorkath", 8061, 536, 2);
		JsonObject src = drops().getAsJsonObject("Vorkath");
		assertEquals(51, src.get("kc").getAsInt());
		assertEquals(80.0, src.get("pb").getAsDouble(), 0.01);
		assertEquals(2, src.get("timed").getAsInt());
		List<JsonObject> records = h.feed("RECORD");
		assertEquals(1, records.size());
		assertEquals(90.0, records.get(0).get("was").getAsDouble(), 0.01);
	}

	@Test
	public void theDaysTakeIsDatedAndPeriodsReadOnlyTheirOwnDays()
	{
		h.kill("Zulrah", 2042, 4151, 1);
		h.kill("Vorkath", 8061, 536, 1);
		LocalDate today = LocalDate.now();
		List<String> day = h.period("Day", today).screen("Loot");
		assertTrue(day.toString(), has(day, "Zulrah"));
		assertTrue(day.toString(), day.indexOf("Zulrah") < day.indexOf("Vorkath"));
		List<String> before = h.period("Day", today.minusDays(1)).screen("Loot");
		assertFalse(has(before, "Zulrah"));
		assertTrue(has(before, "The dated loot roll begins"));

		LocalDate then = today.minusDays(20);
		h.edit(j ->
		{
			JsonObject days = j.getAsJsonObject("loot_days");
			days.add(then.toString(), days.remove(today.toString()));
		});
		assertTrue(has(h.period("Day", then).screen("Loot"), "Zulrah"));
		assertTrue(has(h.period("Month", then).screen("Loot"), "The dated loot roll begins"));
		assertFalse(has(h.period("Day", today).screen("Loot"), "Zulrah"));
		assertFalse(has(h.period("Week", today).screen("Loot"), "Zulrah"));
		assertTrue(has(h.period("Lifetime", today).screen("Loot"), "Zulrah"));
	}

	private void leave(TileItem... stacks)
	{
		for (TileItem t : stacks)
		{
			h.rot(t);
		}
		h.tick();
	}

	private JsonObject left(String source)
	{
		JsonObject u = h.journal().getAsJsonObject("untaken");
		return u == null ? null : u.getAsJsonObject(source);
	}

	@Test
	public void stacksLeftOnTheFloorCountTheKillsTheyCameFrom()
	{
		h.kill("Bear", 1, 526, 1);
		TileItem a = h.ground(526, 1, HERE, false);
		TileItem b = h.ground(1618, 1, HERE, false);
		h.tick().ticks(10).kill("Bear", 1, 526, 1);
		TileItem c = h.ground(526, 1, HERE, false);
		h.tick().kill("Wolf", 2, 526, 1);
		TileItem d = h.ground(526, 1, HERE, false);
		h.tick();
		leave(a, b, c, d);
		assertEquals(3, left("Bear").get("qty").getAsInt());
		assertEquals(2, left("Bear").get("kills").getAsInt());
		assertEquals(1, left("Wolf").get("kills").getAsInt());
	}

	@Test
	public void aStackPickedUpIsNotLeftBehind()
	{
		h.kill("Bear", 1, 526, 1);
		TileItem a = h.ground(526, 1, HERE, false);
		h.tick().pickUp(a);
		h.ticks(300);
		assertEquals(null, left("Bear"));
	}

	@Test
	public void somethingWeDroppedOurselvesIsNotLoot()
	{
		h.kill("Bear", 1, 526, 1);
		h.click("Drop", "Bones", 526);
		TileItem ours = h.ground(526, 1, HERE, false);
		h.tick();
		leave(ours);
		assertEquals(null, left("Bear"));
	}

	@Test
	public void hoppingAwayLeavesTheFloorAndAReloadDoesNot()
	{
		h.kill("Bear", 1, 526, 1);
		TileItem a = h.ground(526, 1, HERE, false);
		h.tick().state(GameState.LOADING).tick();
		h.post(new net.runelite.api.events.ItemSpawned(null, a)).tick().pickUp(a).tick();
		assertEquals(null, left("Bear"));

		h.state(GameState.LOGGED_IN).kill("Bear", 1, 526, 1);
		h.ground(526, 1, HERE, false);
		h.tick().state(GameState.HOPPING).state(GameState.LOGGED_IN).tick();
		assertEquals(1, left("Bear").get("kills").getAsInt());
	}

	@Test
	public void whatWasLeftIsDatedAndShownOnTheLeftBehindLens()
	{
		h.kill("Bear", 1, 526, 1);
		TileItem t = h.ground(526, 7, HERE, false);
		h.tick();
		leave(t);
		JsonObject today = h.journal().getAsJsonObject("loot_days").getAsJsonObject(LocalDate.now().toString());
		assertEquals(7, today.get("left").getAsInt());
		assertEquals(1, today.get("leftKills").getAsInt());
		assertTrue(has(h.period("Day", LocalDate.now()).screen("Loot", "left"), "Bones"));
	}

	@Test
	public void theLootTrackerIsAdoptedOnceAndNeverAnotherPlayersRecord()
	{
		Harness fresh = new Harness()
			.lootTracker("drops_NPC_Zulrah", "{\"name\":\"Zulrah\",\"kills\":430,\"drops\":[12934,500,526,2]}")
			.lootTracker("drops_EVENT_Barrows", "{\"name\":\"Barrows\",\"kills\":12,\"drops\":[4151,1]}")
			.lootTracker("drops_PLAYER_Some Player", "{\"name\":\"Some Player\",\"kills\":3,\"drops\":[995,9]}")
			.lootTracker("drops_SOMETHINGNEW_Thing", "{\"name\":\"Thing\",\"kills\":3,\"drops\":[995,9]}")
			.login();
		JsonObject drops = fresh.journal().getAsJsonObject("drops");
		assertEquals(430, drops.getAsJsonObject("Zulrah").get("loots").getAsInt());
		assertTrue(drops.has("Barrows"));
		assertFalse(drops.has("Some Player"));
		assertFalse(drops.has("Thing"));
		assertTrue(fresh.said().stream().anyMatch(s -> s.startsWith("Chronicle: adopted 2 sources")));
		fresh.logout().login();
		assertEquals(430, fresh.journal().getAsJsonObject("drops").getAsJsonObject("Zulrah").get("loots").getAsInt());
	}
}
