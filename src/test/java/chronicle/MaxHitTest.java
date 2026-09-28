package chronicle;

import chronicle.counters.CombatStatTracker;
import chronicle.counters.CounterTestKeys;
import chronicle.counters.StatKeys;
import chronicle.counters.StatStore;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.events.HitsplatApplied;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;

public class MaxHitTest
{
	private static final int[] MAX_SPLATS = {
		HitsplatID.DAMAGE_MAX_ME, HitsplatID.DAMAGE_MAX_ME_CYAN,
		HitsplatID.DAMAGE_MAX_ME_ORANGE, HitsplatID.DAMAGE_MAX_ME_YELLOW,
		HitsplatID.DAMAGE_MAX_ME_WHITE};

	private static CombatStatTracker tracker(StatStore store)
	{
		Client client = Mockito.mock(Client.class);
		Mockito.when(client.getLocalPlayer()).thenReturn(null);
		Mockito.when(client.getTickCount()).thenReturn(1);
		return new CombatStatTracker(store, client);
	}

	private static Actor target()
	{
		NPC npc = Mockito.mock(NPC.class);
		Mockito.when(npc.getCombatLevel()).thenReturn(100);
		return npc;
	}

	private static void hit(CombatStatTracker t, Actor on, int type, int amount)
	{
		Hitsplat splat = Mockito.mock(Hitsplat.class);
		Mockito.when(splat.getHitsplatType()).thenReturn(type);
		Mockito.when(splat.getAmount()).thenReturn(amount);
		HitsplatApplied e = new HitsplatApplied();
		e.setActor(on);
		e.setHitsplat(splat);
		t.onHitsplatApplied(e);
	}

	@Test
	public void aMaxHitBecomesTheHighestHit()
	{
		for (int splat : MAX_SPLATS)
		{
			StatStore store = new StatStore();
			Actor on = target();
			CombatStatTracker t = tracker(store);
			hit(t, on, HitsplatID.DAMAGE_ME, 30);
			hit(t, on, splat, 71);
			assertEquals("splat " + splat + ": a max hit is still a hit",
				71, store.getStat(StatKeys.HIGHEST_HIT));
		}
	}

	@Test
	public void aPlainHitStillCounts()
	{
		StatStore store = new StatStore();
		Actor on = target();
		CombatStatTracker t = tracker(store);
		hit(t, on, HitsplatID.DAMAGE_ME, 42);
		assertEquals(42, store.getStat(StatKeys.HIGHEST_HIT));
	}

	@Test
	public void aMaxHitJoinsTheRunningTotal()
	{
		StatStore store = new StatStore();
		Actor on = target();
		CombatStatTracker t = tracker(store);
		hit(t, on, HitsplatID.DAMAGE_ME, 30);
		hit(t, on, HitsplatID.DAMAGE_MAX_ME, 71);
		assertEquals("every hit we dealt is damage we dealt",
			101, store.getStat(StatKeys.DAMAGE_DEALT));
	}

	@Test
	public void damageTakenIsUnchanged()
	{
		StatStore store = new StatStore();
		Client client = Mockito.mock(Client.class);
		Mockito.when(client.getLocalPlayer()).thenReturn(null);
		CombatStatTracker t = new CombatStatTracker(store, client);
		Hitsplat splat = Mockito.mock(Hitsplat.class);
		Mockito.when(splat.getHitsplatType()).thenReturn(HitsplatID.DAMAGE_MAX_ME);
		Mockito.when(splat.getAmount()).thenReturn(40);
		HitsplatApplied e = new HitsplatApplied();
		e.setActor(null);
		e.setHitsplat(splat);
		t.onHitsplatApplied(e);
		assertEquals("the taken total is still DAMAGE_ME only",
			0, store.getStat(CounterTestKeys.DAMAGE_TAKEN));
	}
}
