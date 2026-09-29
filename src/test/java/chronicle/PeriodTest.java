/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;
import org.junit.Test;
import static chronicle.Harness.after;
import static chronicle.Harness.has;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PeriodTest
{
	private static final LocalDate JUNE = LocalDate.of(2026, 6, 1);

	private final Harness h = new Harness();

	private static Map<String, Long> skills(long each, long fishing)
	{
		Map<String, Long> m = new HashMap<>();
		long overall = 0;
		for (Skill s : Skill.values())
		{
			if (s != Skill.OVERALL)
			{
				long xp = s == Skill.FISHING ? fishing : each;
				m.put(s.name().toLowerCase(), xp);
				overall += xp;
			}
		}
		m.put("overall", overall);
		return m;
	}

	private static Map<String, Long> kcs(long nechryael, long vorkath)
	{
		Map<String, Long> m = new HashMap<>();
		m.put("Nechryael", nechryael);
		if (vorkath > 0)
		{
			m.put("Vorkath", vorkath);
		}
		return m;
	}

	private static String line(LocalDate day, Map<String, Long> skills, long slots, Map<String, Long> kcs)
	{
		JsonObject o = new JsonObject();
		o.addProperty("date", day.toString());
		o.add("skills", new Gson().toJsonTree(skills));
		JsonObject counters = new JsonObject();
		counters.addProperty("clogSlotsObtained", slots);
		o.add("counters", counters);
		o.add("kcs", new Gson().toJsonTree(kcs));
		return o.toString();
	}

	private void june()
	{
		Harness.write(h.spineFile(), String.join("\n",
			line(JUNE, skills(1_200_000, 9_000_000), 480, kcs(900, 0)),
			line(JUNE.plusDays(7), skills(1_200_000, 10_500_000), 512, kcs(950, 100)),
			line(JUNE.plusDays(12), skills(13_100_000, 13_100_000), 520, kcs(1000, 400))) + "\n");
		h.login();
	}

	@Test
	public void aWindowMeasuresFromTheLineBeforeIt()
	{
		june();
		List<String> week = h.period("Week", JUNE.plusDays(9)).screen("Recap");
		assertTrue(week.toString(), after(week, "Xp gained").startsWith("+1.5M xp, most in Fishing"));
		assertEquals("Nechryael · 50", after(week, "Killed most"));
		List<String> month = h.period("Month", JUNE.plusDays(14)).screen("Recap");
		assertEquals("Vorkath · 300", after(month, "Killed most"));
	}

	@Test
	public void anEmptyPeriodSaysSo()
	{
		h.login();
		List<String> week = h.period("Week", LocalDate.of(2026, 3, 4)).screen("Recap");
		assertTrue(week.toString(), week.stream().anyMatch(s -> s.startsWith("Nothing inside ")));
	}

	@Test
	public void milestonesAreDatedToTheDayTheRecordCrossedThem()
	{
		june();
		List<String> feats = h.screen("Journal", "Feats");
		String text = String.join(" | ", feats);
		assertTrue(text, feats.contains("Milestone: 10M xp in Fishing"));
		assertTrue(text, feats.contains("Milestone: 500 collection log slots"));
		assertTrue(text, feats.contains("Milestone: Total level 2,000"));
		assertFalse(text, feats.contains("Milestone: Total level 1,000"));
		assertTrue(feats.indexOf("Milestone: Total level 2,000") < feats.indexOf("Milestone: 10M xp in Fishing"));
	}

	@Test
	public void theCalendarMarksTheDaysWritten()
	{
		june();
		List<String> cal = h.period("Month", JUNE).screen("Calendar");
		assertTrue(cal.toString(), has(cal, "JUNE 2026"));
		assertTrue(cal.toString(), has(cal, "Written"));
	}
}
