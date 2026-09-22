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

	/** A hit I dealt, which is the one thing that starts a fight. */
	private void hit(String name)
	{
		Hitsplat splat = Mockito.mock(Hitsplat.class);
		Mockito.when(splat.isMine()).thenReturn(true);
		HitsplatApplied e = new HitsplatApplied();
		e.setActor(npc(name));
		e.setHitsplat(splat);
		t.onHitsplatApplied(e);
	}

	private void fight(String name)
	{
		hit(name);
		// built before the stubbing, or npc()'s own when() nests inside this one
		NPC on = npc(name);
		Mockito.when(me.getInteracting()).thenReturn(on);
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
		// a fight is a run of hits, each holding the tick for the grace after it
		for (int i = 0; i < 3; i++)
		{
			hit("Abyssal demon");
			ticks(40);
		}
		assertEquals(1, store.getStat("timeAbyssalDemon"));
		assertEquals(0, store.getStat(StatKeys.TIME_IDLE));
	}

	/**
	 * And standing beside one is not. A fishing spot, a banker, a pickpocket
	 * target and an impling are all NPCs, and letting an interaction claim the
	 * tick filed an hour of fishing under the shoal rather than under Fishing.
	 */
	@Test
	public void interactingAloneStartsNothing()
	{
		NPC spot = npc("Fishing spot");
		Mockito.when(me.getInteracting()).thenReturn(spot);
		xp(Skill.FISHING, 1_000_000);
		xp(Skill.FISHING, 1_000_090);
		ticks(200);
		assertEquals(0, store.getStat("timeFishingSpot"));
		assertEquals(2, store.getStat("timeFishing"));
	}

	/** A fight's own xp does not make it a craft. */
	@Test
	public void combatXpDoesNotSeedTheSkillBranch()
	{
		xp(Skill.ATTACK, 1_000_000);
		fight("Vorkath");
		ticks(10);
		xp(Skill.ATTACK, 1_000_400);   // lands mid-fight
		noFight();
		ticks(300);
		assertEquals(0, store.getStat("timeAttack"));
		// the fight keeps its grace, and what follows is idle
		assertEquals(0, store.getStat("timeVorkath"));
		assertEquals(2, store.getStat(StatKeys.TIME_IDLE));
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
		// and the next sitting starts from nothing: 90 ticks carried over would
		// have credited a minute after only ten of the new one
		fight("Vorkath");
		ticks(10);
		assertEquals(0, store.getStat("timeVorkath"));
		ticks(90);
		assertEquals(1, store.getStat("timeVorkath"));
		// the xp baseline too, or the first drop back reads as a career total
		xp(Skill.FISHING, 2_000_000);
		noFight();
		ticks(TimeStatTracker.FIGHT_GRACE + 100);
		assertEquals(0, store.getStat("timeFishing"));
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
