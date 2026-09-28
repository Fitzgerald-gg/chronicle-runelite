/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PeakCounterTest
{
	private static Map<String, Long> peaks(Map<String, Long> moved,
		Map<String, Long> opening, Map<String, Long> closing) throws Exception
	{
		Class<?> span = Class.forName("chronicle.ChroniclePanel$Span");
		java.lang.reflect.Constructor<?> c = span.getDeclaredConstructors()[0];
		c.setAccessible(true);
		Object[] args = new Object[c.getParameterCount()];
		Object s = c.newInstance(args);
		for (String which : new String[]{"opening", "closing", "earliest"})
		{
			java.lang.reflect.Field f = span.getDeclaredField(which);
			f.setAccessible(true);
			f.set(s, baseline("closing".equals(which) ? closing : opening));
		}
		Method m = ChroniclePanel.class.getDeclaredMethod("peaksNotDeltas",
			Map.class, span);
		m.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Long> out = (Map<String, Long>) m.invoke(null, moved, s);
		return out;
	}

	private static HistoryLog.Baseline baseline(Map<String, Long> counters)
		throws Exception
	{
		java.lang.reflect.Constructor<HistoryLog.Baseline> c =
			(java.lang.reflect.Constructor<HistoryLog.Baseline>)
				HistoryLog.Baseline.class.getDeclaredConstructors()[0];
		c.setAccessible(true);
		Object[] args = new Object[c.getParameterCount()];
		HistoryLog.Baseline b = c.newInstance(args);
		java.lang.reflect.Field f = HistoryLog.Baseline.class.getDeclaredField("counters");
		f.setAccessible(true);
		f.set(b, counters);
		return b;
	}

	private static Map<String, Long> map(Object... kv)
	{
		Map<String, Long> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2)
		{
			m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
		}
		return m;
	}

	@Test
	public void aRecordThatRoseIsReportedAsTheRecordAndNotTheDifference() throws Exception
	{
		Map<String, Long> out = peaks(map("highestHit", 7L, "damageDealt", 40_000L),
			map("highestHit", 68L), map("highestHit", 75L));
		assertEquals("the week reported a hit of seven, which nobody landed",
			Long.valueOf(75), out.get("highestHit"));
		assertEquals("an ordinary counter stopped being a difference",
			Long.valueOf(40_000), out.get("damageDealt"));
	}

	@Test
	public void aRecordThatHeldIsNotAnswered() throws Exception
	{
		Map<String, Long> out = peaks(map("highestHit", 0L, "deaths", 2L),
			map("highestHit", 75L), map("highestHit", 75L));
		assertFalse("a week that beat no record still claimed one: " + out,
			out.containsKey("highestHit"));
		assertTrue("and it took the ordinary counters with it", out.containsKey("deaths"));
	}

	@Test
	public void bothRecordsAreHeldToTheSameRule() throws Exception
	{
		assertEquals("the panel and the store disagree about what a record is",
			LocalStore.MAX_KEYS, chronicle.panel.StatRegistry.peakKeys());
		Map<String, Long> out = peaks(map("highestHitTaken", 3L),
			map("highestHitTaken", 40L), map("highestHitTaken", 43L));
		assertEquals(Long.valueOf(43), out.get("highestHitTaken"));
	}

	@Test
	public void theRuleIsOnThePathEveryBoardTakes() throws Exception
	{
		String src = new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/chronicle/ChroniclePanel.java")),
			java.nio.charset.StandardCharsets.UTF_8);
		int at = src.indexOf("private Map<String, Long> countersForPeriod()");
		assertTrue("the boards no longer read their counters through one place", at > 0);
		String body = src.substring(at, src.indexOf("\n\t}", at));
		assertTrue("countersForPeriod hands a raw subtraction to the boards, so a "
			+ "record is printed as a difference again", body.contains("peaksNotDeltas("));
		assertTrue("and the subtraction it wraps is still the one being fixed",
			body.contains("HistoryLog.gained("));
	}
}
