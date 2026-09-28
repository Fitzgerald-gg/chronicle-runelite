/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AchievementSyncTest
{
	private AchievementSync sync;

	private final Map<Integer, Integer> varbits = new HashMap<>();
	private int tick;
	private int[] questStack;

	@Before
	public void setUp()
	{
		Client client = Mockito.mock(Client.class);
		questStack = new int[]{1};
		tick = 100;
		Mockito.when(client.getTickCount()).thenAnswer(inv -> tick);
		Mockito.when(client.getIntStack()).thenAnswer(inv -> questStack);
		Mockito.when(client.getVarbitValue(Mockito.anyInt()))
			.thenAnswer(inv -> varbits.getOrDefault((Integer) inv.getArgument(0), 0));
		sync = new AchievementSync(client, new com.google.gson.Gson());
	}

	private JsonObject nextTick()
	{
		tick++;
		return sync.snapshot();
	}

	@Test
	public void oneTickBuildsOneSnapshotHoweverManyCallersAskForIt()
	{
		JsonObject first = sync.snapshot();
		assertSame(first, sync.snapshot());
		assertSame(first, sync.snapshot());

		assertNotSame(first, nextTick());
	}

	@Test
	public void aChangeWithinTheTickIsSeenOnTheNextOne()
	{
		JsonObject before = sync.snapshot();
		assertFalse(before.getAsJsonObject("diaries")
			.getAsJsonObject("varrock").get("easy").getAsBoolean());

		varbits.put(VarbitID.VARROCK_DIARY_EASY_COMPLETE, 1);
		assertSame(before, sync.snapshot());

		assertTrue(nextTick().getAsJsonObject("diaries")
			.getAsJsonObject("varrock").get("easy").getAsBoolean());
	}

	@Test
	public void anUnacknowledgedSnapshotAlwaysGoesUp()
	{
		assertTrue(sync.changedSince(sync.snapshot()));
	}

	@Test
	public void identicalStateIsNotResentOnceAcknowledged()
	{
		JsonObject snap = sync.snapshot();
		sync.markSynced(snap);
		assertFalse(sync.changedSince(snap));
		JsonObject later = nextTick();
		assertNotSame(snap, later);
		assertFalse(sync.changedSince(later));
	}

	@Test
	public void aDiaryCompletionReopensTheGate()
	{
		sync.markSynced(sync.snapshot());
		varbits.put(VarbitID.KANDARIN_DIARY_HARD_COMPLETE, 1);
		assertTrue(sync.changedSince(nextTick()));
	}

	@Test
	public void aCombatAchievementChangeReopensTheGate()
	{
		sync.markSynced(sync.snapshot());
		varbits.put(VarbitID.CA_POINTS, 40);
		assertTrue(sync.changedSince(nextTick()));

		sync.markSynced(nextTick());
		varbits.put(VarbitID.CA_TIER_STATUS_HARD, 2);
		assertTrue(sync.changedSince(nextTick()));
	}

	@Test
	public void aQuestCompletionReopensTheGate()
	{
		sync.markSynced(sync.snapshot());
		questStack = new int[]{2};
		assertTrue(sync.changedSince(nextTick()));
	}

	@Test
	public void karamjasDerivedTiersTurnOverAtTheirTaskTotals()
	{
		varbits.put(VarbitID.KARAMJA_EASY_COUNT, 9);
		varbits.put(VarbitID.KARAMJA_MED_COUNT, 19);
		varbits.put(VarbitID.KARAMJA_HARD_COUNT, 10);
		JsonObject karamja = sync.snapshot().getAsJsonObject("diaries").getAsJsonObject("karamja");
		assertFalse(karamja.get("easy").getAsBoolean());
		assertTrue(karamja.get("medium").getAsBoolean());
		assertTrue(karamja.get("hard").getAsBoolean());
		assertFalse(karamja.get("elite").getAsBoolean());

		varbits.put(VarbitID.KARAMJA_EASY_COUNT, 10);
		varbits.put(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, 1);
		JsonObject done = nextTick().getAsJsonObject("diaries").getAsJsonObject("karamja");
		assertTrue(done.get("easy").getAsBoolean());
		assertTrue(done.get("elite").getAsBoolean());
	}

	@Test
	public void aDerivedTierStaysCompletePastItsTotal()
	{
		varbits.put(VarbitID.KARAMJA_MED_COUNT, 25);
		assertTrue(sync.snapshot().getAsJsonObject("diaries")
			.getAsJsonObject("karamja").get("medium").getAsBoolean());
	}

	@Test
	public void resetForcesTheNextAccountToSyncEvenIfItLooksTheSame()
	{
		JsonObject alpha = sync.snapshot();
		sync.markSynced(alpha);
		assertFalse(sync.changedSince(alpha));

		sync.reset();

		JsonObject beta = nextTick();
		assertEquals(alpha.toString(), beta.toString());
		assertTrue(sync.changedSince(beta));
	}

	@Test
	public void resetDropsTheCachedSnapshotWithinTheSameTick()
	{
		JsonObject before = sync.snapshot();
		sync.reset();
		assertNotSame(before, sync.snapshot());
	}

	@Test
	public void theSnapshotCarriesTheSectionsTheServerDerivesFrom()
	{
		JsonObject snap = sync.snapshot();
		assertTrue(snap.has("quests"));
		assertTrue(snap.has("diaries"));
		assertTrue(snap.has("combat"));

		JsonObject diaries = snap.getAsJsonObject("diaries");
		assertEquals(12, diaries.size());
		for (Map.Entry<String, com.google.gson.JsonElement> e : diaries.entrySet())
		{
			JsonObject region = e.getValue().getAsJsonObject();
			assertEquals(e.getKey(), 4, region.size());
			assertTrue(e.getKey(), region.has("easy"));
			assertTrue(e.getKey(), region.has("medium"));
			assertTrue(e.getKey(), region.has("hard"));
			assertTrue(e.getKey(), region.has("elite"));
		}

		JsonObject combat = snap.getAsJsonObject("combat");
		assertTrue(combat.has("points"));
		assertEquals(6, combat.getAsJsonObject("tiers").size());
		assertTrue(combat.getAsJsonObject("tiers").has("grandmaster"));

		assertTrue(snap.getAsJsonObject("quests").size() > 100);
		assertEquals("NOT_STARTED", snap.getAsJsonObject("quests")
			.entrySet().iterator().next().getValue().getAsString());
	}
}
