/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The record has to be readable the instant it moves.
 *
 * <p>Two separate things had to be true for that and only one of them was. The
 * panel is redrawn when the stores say they have been written to, which is what
 * the revision counters are for; and what it then READS has to be the record as
 * it stands, not as it was last written to disk.
 *
 * <p>The second was the one that was wrong. Lifetime counters came from the
 * journal's persisted trackers, which only move when the journal is flushed, so
 * a board showing what an account had gathered sat still through an hour of
 * gathering and jumped on logout. Redrawing it more often would have changed
 * nothing at all: it would have drawn the same stale figure more times.
 */
public class LiveRecordTest
{
	private static chronicle.counters.StatStore store()
	{
		return new chronicle.counters.StatStore();
	}

	@Test
	public void aCounterWriteMovesTheRevision()
	{
		chronicle.counters.StatStore s = store();
		long at = s.revision();
		s.incrementStat("logsChopped");
		assertNotEquals("a write nobody can see is a board that never redraws",
			at, s.revision());
	}

	@Test
	public void aHighWaterWriteMovesItToo()
	{
		chronicle.counters.StatStore s = store();
		s.setStat("highestHit", 40);
		long at = s.revision();
		s.setStat("highestHit", 73);
		assertNotEquals(at, s.revision());
	}

	/** Reading is not writing: a board drawn every tick must not cause the next. */
	@Test
	public void readingDoesNotMoveIt()
	{
		chronicle.counters.StatStore s = store();
		s.incrementStat("logsChopped");
		long at = s.revision();
		s.getStat("logsChopped");
		s.snapshotAll();
		assertEquals("a read moved the revision, so every tick would redraw for ever",
			at, s.revision());
	}

	/**
	 * The lifetime arithmetic, done against counters as they stand rather than as
	 * last flushed. This is the half that made the difference.
	 */
	@Test
	public void lifetimeIsBasePlusSessionWithoutWaitingForAFlush() throws Exception
	{
		LocalStore ls = new LocalStore(null, new com.google.gson.Gson());
		java.lang.reflect.Field base = LocalStore.class.getDeclaredField("trackersBase");
		base.setAccessible(true);
		com.google.gson.JsonObject b = new com.google.gson.JsonObject();
		b.addProperty("logsChopped", 1000L);
		b.addProperty("highestHit", 60L);
		base.set(ls, b);

		Map<String, Integer> session = new HashMap<>();
		session.put("logsChopped", 7);
		session.put("highestHit", 73);

		Map<String, Long> live = ls.lifetimeOf(session);
		assertEquals("an ordinary counter is the base plus this session",
			Long.valueOf(1007L), live.get("logsChopped"));
		assertEquals("a high water mark is the better of the two, never the sum",
			Long.valueOf(73L), live.get("highestHit"));
	}

	/** And with nothing done this session it is simply the base. */
	@Test
	public void withNothingDoneYetItIsJustTheBase() throws Exception
	{
		LocalStore ls = new LocalStore(null, new com.google.gson.Gson());
		java.lang.reflect.Field base = LocalStore.class.getDeclaredField("trackersBase");
		base.setAccessible(true);
		com.google.gson.JsonObject b = new com.google.gson.JsonObject();
		b.addProperty("logsChopped", 1000L);
		base.set(ls, b);

		assertEquals(Long.valueOf(1000L),
			ls.lifetimeOf(new HashMap<>()).get("logsChopped"));
		assertEquals("a null session is a session that did nothing",
			Long.valueOf(1000L), ls.lifetimeOf(null).get("logsChopped"));
	}

	/** A counter at zero is absent rather than printed as a zero row. */
	@Test
	public void aCounterThatIsStillZeroIsNotCarried() throws Exception
	{
		LocalStore ls = new LocalStore(null, new com.google.gson.Gson());
		java.lang.reflect.Field base = LocalStore.class.getDeclaredField("trackersBase");
		base.setAccessible(true);
		base.set(ls, new com.google.gson.JsonObject());
		Map<String, Integer> session = new HashMap<>();
		session.put("logsChopped", 0);
		assertTrue(ls.lifetimeOf(session).isEmpty());
	}
}
