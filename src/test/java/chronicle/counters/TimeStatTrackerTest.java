/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;

/**
 * Every tick of a sitting is filed under exactly one activity: the monster
 * being fought, else the skill of the last xp drop, else idle. A hundred
 * ticks is a minute, and a minute is a counter.
 */
public class TimeStatTrackerTest
{
	private StatStore store;
	private Client client;
	private Player me;
	private TimeStatTracker t;
	private int tick;

	@Before
	public void setUp()
	{
		store = new StatStore();
		client = Mockito.mock(Client.class);
		me = Mockito.mock(Player.class);
		Mockito.when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Mockito.when(client.getLocalPlayer()).thenReturn(me);
		Mockito.when(client.getTickCount()).thenAnswer(inv -> tick);
		t = new TimeStatTracker(store, client);
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick++;
			t.onGameTick(new GameTick());
		}
	}

	private NPC npc(String name)
	{
		NPC n = Mockito.mock(NPC.class);
		Mockito.when(n.getName()).thenReturn(name);
		return n;
	}

	private void xp(Skill skill, int total)
	{
		t.onStatChanged(new StatChanged(skill, total, 1, 1));
	}

	private void fight(String name)
	{
		NPC n = npc(name);
		Mockito.when(me.getInteracting()).thenReturn(n);
	}

	private void noFight()
	{
		Mockito.when(me.getInteracting()).thenReturn(null);
	}

	@Test
	public void aFightOwnsItsMinutes()
	{
		fight("Vorkath");
		ticks(250);
		assertEquals(2, store.getStat("timeVorkath"));
		assertEquals(0, store.getStat(StatKeys.TIME_IDLE));
		// the walk back to the boss stays with the boss, then it goes idle
		noFight();
		ticks(TimeStatTracker.FIGHT_GRACE);
		assertEquals(3, store.getStat("timeVorkath"));
		ticks(100);
		assertEquals(3, store.getStat("timeVorkath"));
		assertEquals(1, store.getStat(StatKeys.TIME_IDLE));
	}

	@Test
	public void aHitOwnsThemToo()
	{
		Hitsplat splat = Mockito.mock(Hitsplat.class);
		Mockito.when(splat.isMine()).thenReturn(true);
		NPC demon = npc("Abyssal demon");
		HitsplatApplied e = new HitsplatApplied();
		e.setActor(demon);
		e.setHitsplat(splat);
		// a fight is a run of hits, each holding the tick for the grace after it
		for (int i = 0; i < 3; i++)
		{
			t.onHitsplatApplied(e);
			ticks(40);
		}
		assertEquals(1, store.getStat("timeAbyssalDemon"));
		assertEquals(0, store.getStat(StatKeys.TIME_IDLE));
	}

	@Test
	public void aSkillOwnsThemAfterItsFirstDrop()
	{
		xp(Skill.FISHING, 1_000_000);   // the career total: not a drop
		ticks(100);
		assertEquals(0, store.getStat("timeFishing"));
		assertEquals(1, store.getStat(StatKeys.TIME_IDLE));
		xp(Skill.FISHING, 1_000_090);   // a drop
		ticks(TimeStatTracker.SKILL_GRACE);
		assertEquals(3, store.getStat("timeFishing"));
		// past the grace it is idle again
		ticks(100);
		assertEquals(3, store.getStat("timeFishing"));
		assertEquals(2, store.getStat(StatKeys.TIME_IDLE));
	}

	@Test
	public void aFightOutranksACraft()
	{
		xp(Skill.MAGIC, 100);
		xp(Skill.MAGIC, 200);
		fight("Zulrah");
		ticks(100);
		assertEquals(1, store.getStat("timeZulrah"));
		assertEquals(0, store.getStat("timeMagic"));
	}

	@Test
	public void nothingIsFiledOffTheLoginScreen()
	{
		Mockito.when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		ticks(200);
		assertEquals(0, store.getStat(StatKeys.TIME_IDLE));
	}

	@Test
	public void theMinutesInHandDoNotOutliveTheLogin()
	{
		fight("Vorkath");
		ticks(90);
		GameStateChanged out = Mockito.mock(GameStateChanged.class);
		Mockito.when(out.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		t.onGameStateChanged(out);
		noFight();
		ticks(20);
		assertEquals(0, store.getStat("timeVorkath"));
	}

	@Test
	public void theKeyIsTheNameAsTheGameGivesIt()
	{
		assertEquals("timeAbyssalDemon", StatKeys.timeKey("Abyssal demon"));
		assertEquals("timeDagannothRex", StatKeys.timeKey("Dagannoth Rex"));
		assertEquals("timeKrilTsutsaroth", StatKeys.timeKey("K'ril Tsutsaroth"));
		assertEquals("timeRunecraft", StatKeys.timeKey("Runecraft"));
		assertEquals(true, StatKeys.isTime("timeVorkath"));
		assertEquals(false, StatKeys.isTime("timesLooted"));
	}
}
