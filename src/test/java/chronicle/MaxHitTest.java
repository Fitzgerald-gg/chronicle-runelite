package chronicle;

import chronicle.counters.CombatStatTracker;
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

/**
 * A max hit is the biggest thing an account does, and it wears a hitsplat of its
 * own. The dealt side matched {@link HitsplatID#DAMAGE_ME} alone, so every max hit
 * fell to the switch's default and "Highest hit" could not, by construction, ever
 * hold one. Nothing failed; the figure was quietly the highest NON-max hit.
 */
public class MaxHitTest
{
	private static final int[] MAX_SPLATS = {
		HitsplatID.DAMAGE_MAX_ME, HitsplatID.DAMAGE_MAX_ME_CYAN,
		HitsplatID.DAMAGE_MAX_ME_ORANGE, HitsplatID.DAMAGE_MAX_ME_YELLOW,
		HitsplatID.DAMAGE_MAX_ME_WHITE};

	/** A tracker whose local player is one actor and whose target is another. */
	private static CombatStatTracker tracker(StatStore store, Actor target)
	{
		Client client = Mockito.mock(Client.class);
		Mockito.when(client.getLocalPlayer()).thenReturn(null);   // the splat is never on us
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
			CombatStatTracker t = tracker(store, on);
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
		CombatStatTracker t = tracker(store, on);
		hit(t, on, HitsplatID.DAMAGE_ME, 42);
		assertEquals(42, store.getStat(StatKeys.HIGHEST_HIT));
	}

	/**
	 * The sums are deliberately NOT widened: DAMAGE_DEALT and the per-style split
	 * stay DAMAGE_ME-only so their running totals still line up with the history
	 * already written, exactly as DAMAGE_TAKEN does. Pinned so widening them has to
	 * be a decision rather than an accident.
	 */
	@Test
	public void aMaxHitDoesNotJoinTheRunningTotal()
	{
		StatStore store = new StatStore();
		Actor on = target();
		CombatStatTracker t = tracker(store, on);
		hit(t, on, HitsplatID.DAMAGE_ME, 30);
		hit(t, on, HitsplatID.DAMAGE_MAX_ME, 71);
		assertEquals("the sum stays on the plain splat, as DAMAGE_TAKEN does",
			30, store.getStat(StatKeys.DAMAGE_DEALT));
	}
}
