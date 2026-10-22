/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.io.File;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HistoryTest
{
	private static final LocalDate D = LocalDate.of(2026, 9, 8);

	private final Harness h = new Harness();

	private static Map<String, Long> m(String key, long value)
	{
		return Collections.singletonMap(key, value);
	}

	private static String line(String date, long attack)
	{
		return "{\"date\":\"" + date + "\",\"skills\":{\"attack\":" + attack + "},\"counters\":{}}";
	}

	private void raw(String... lines)
	{
		Harness.write(h.spineFile(), String.join("\n", lines) + "\n");
	}

	private long attack(LocalDate day)
	{
		return h.spineOn(day).getAsJsonObject("skills").get("attack").getAsLong();
	}

	@Test
	public void aDayIsOneLineAndItsLastAppendCounts()
	{
		h.spine(D, m("attack", 100), m("tilesWalked", 10), m("Zulrah", 5));
		h.spine(D, m("attack", 180), m("tilesWalked", 30), m("Zulrah", 6));
		assertEquals(1, h.spineLines().size());
		assertEquals(180, attack(D));
		assertEquals(30, h.spineOn(D).getAsJsonObject("counters").get("tilesWalked").getAsLong());
	}

	@Test
	public void theFirstAppendAfterMidnightClosesYesterdayAtThatState()
	{
		h.spine(D, m("attack", 100), m("tilesWalked", 10), m("Zulrah", 5));
		h.spine(D.plusDays(1), m("attack", 140), m("tilesWalked", 90), m("Zulrah", 8));
		assertEquals(2, h.spineLines().size());
		assertEquals(140, attack(D));
		assertEquals(8, h.spineOn(D).getAsJsonObject("kcs").get("Zulrah").getAsLong());
		h.spine(D.plusDays(1), m("attack", 180), m("tilesWalked", 120), m("Zulrah", 9));
		assertEquals(140, attack(D));
		assertEquals(180, attack(D.plusDays(1)));
	}

	@Test
	public void anEarlierDayWrittenByAnotherSessionIsNeverRewritten()
	{
		raw(line(D.minusDays(1).toString(), 5));
		h.spine(D, m("attack", 200), m("tilesWalked", 1), m("Zulrah", 1));
		assertEquals(5, attack(D.minusDays(1)));
		assertEquals(200, attack(D));
	}

	@Test
	public void aTornOrDamagedLineCostsOnlyItself()
	{
		raw(line("2026-01-01", 50), "{\"date\":\"2026-01-02\",\"skills\":{\"attack\":60", "{\"date\":\"01/02/2026\"}",
			"{\"date\":\"2026-01-03\",\"skills\":{\"attack\":\"lots\",\"defence\":70}}", line("2026-01-04", 80),
			"{\"date\":\"2026-01-05\",\"skills\":{");
		assertEquals(50, attack(LocalDate.parse("2026-01-01")));
		assertNull(h.spineOn(LocalDate.parse("2026-01-02")));
		assertEquals(70, h.spineOn(LocalDate.parse("2026-01-03")).getAsJsonObject("skills").get("defence").getAsLong());
		assertFalse(h.spineOn(LocalDate.parse("2026-01-03")).getAsJsonObject("skills").has("attack"));
		assertEquals(80, attack(LocalDate.parse("2026-01-04")));
		assertNull(h.spineOn(LocalDate.parse("2026-01-05")));
		h.spine(D, m("attack", 90), m("tilesWalked", 1), m("Zulrah", 1));
		assertEquals(90, attack(D));
	}

	@Test
	public void aCorrectionShiftsTheDaysBeforeItSoItIsNotReadAsPlay()
	{
		raw("{\"date\":\"2026-09-12\",\"kcs\":{\"Tempoross\":40,\"Vorkath\":150}}",
			"{\"date\":\"2026-09-13\",\"kcs\":{\"Tempoross\":46,\"Vorkath\":156,\"Wintertodt\":1078}}",
			"{\"date\":\"2026-09-14\",\"kv\":1,\"kcs\":{\"Tempoross\":455,\"Vorkath\":157,\"Wintertodt\":450},"
				+ "\"adj\":{\"kcs\":{\"Tempoross\":409,\"Wintertodt\":-631}}}");
		JsonObject a = h.spineOn(LocalDate.parse("2026-09-12")).getAsJsonObject("kcs");
		JsonObject b = h.spineOn(LocalDate.parse("2026-09-13")).getAsJsonObject("kcs");
		assertEquals(449, a.get("Tempoross").getAsLong());
		assertEquals(455, b.get("Tempoross").getAsLong());
		assertEquals(447, b.get("Wintertodt").getAsLong());
		assertEquals(156, b.get("Vorkath").getAsLong());
		assertFalse(a.has("Wintertodt"));
	}

	@Test
	public void compactionFoldsRepeatedDaysSortsThemAndKeepsCorrections()
	{
		raw(line("2026-01-02", 9), line("2026-01-01", 1), line("2026-01-01", 3),
			"{\"date\":\"2026-01-03\",\"kcs\":{\"Tempoross\":455},\"adj\":{\"kcs\":{\"Tempoross\":409}}}",
			"{\"date\":\"2026-01-01\",\"kcs\":{\"Tempoross\":46},\"skills\":{\"attack\":3}}");
		assertEquals(2, h.compact());
		assertEquals(3, h.spineLines().size());
		assertTrue(h.spineLines().get(0).contains("2026-01-01"));
		assertEquals(3, attack(LocalDate.parse("2026-01-01")));
		assertEquals(455, h.spineOn(LocalDate.parse("2026-01-01")).getAsJsonObject("kcs").get("Tempoross").getAsLong());
		assertEquals(0, h.compact());
	}

	@Test
	public void compactionLeavesAFileItCannotReadWholly()
	{
		raw(line("2026-01-02", 1), line("2026-01-01", 2), line("2026-01-01", 3), "{\"skills\":{\"attack\":5}}");
		String before = String.join("\n", h.spineLines());
		assertEquals(0, h.compact());
		assertEquals(before, String.join("\n", h.spineLines()));
	}

	@Test
	public void oneAccountIsOneFileHoweverItsNameIsSpelt()
	{
		Harness two = new Harness("ALPHA TWO");
		Harness.write(new File(Harness.DIR, "alpha-two.history.jsonl"), line("2026-06-01", 100) + "\n");
		assertEquals(100, two.spineOn(LocalDate.parse("2026-06-01")).getAsJsonObject("skills").get("attack").getAsLong());
		assertNull(h.spineOn(LocalDate.parse("2026-06-01")));
	}

	@Test
	public void anImportBringsItsHistoryAlong()
	{
		File export = new File(Harness.DIR.getParentFile(), "backup.json");
		Harness.write(export, "{\"trackers\":{\"tilesWalked\":5}}");
		Harness.write(new File(Harness.DIR.getParentFile(), "backup.history.jsonl"),
			line("2026-01-01", 10) + "\n" + line("2026-01-02", 20) + "\n");
		h.login().importFile(export);
		assertEquals(20, attack(LocalDate.parse("2026-01-02")));
		assertTrue(h.said().stream().anyMatch(s -> s.contains("2 days of history")));
	}

	@Test
	public void loggingOutWritesTheDaysLine()
	{
		h.login().level(net.runelite.api.Skill.ATTACK, 50, 101333).kill("Zulrah", 2042, 526, 1).chat(
			"Your Zulrah kill count is: 7.").logout();
		JsonObject today = h.spineOn(LocalDate.now());
		assertEquals(101333, today.getAsJsonObject("skills").get("attack").getAsLong());
		assertEquals(7, today.getAsJsonObject("kcs").get("Zulrah").getAsLong());
		assertTrue(today.getAsJsonObject("counters").has("kills"));
	}
}
