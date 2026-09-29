/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Skill;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static chronicle.Harness.has;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SittingTest
{
	private final Harness h = new Harness().login();

	private static String heading(LocalDate d)
	{
		return DateTimeFormatter.ofPattern("d MMM", Locale.UK).format(d).toUpperCase(Locale.ROOT);
	}

	private static JsonObject sitting(LocalDateTime closed, long minutes, long xp, long drops, String skill)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", closed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
		e.addProperty("type", "SESSION");
		JsonObject d = new JsonObject();
		d.addProperty("minutes", minutes);
		d.addProperty("xp", xp);
		d.addProperty("drops", drops);
		d.addProperty("dropsGp", drops * 4000);
		JsonObject sk = new JsonObject();
		sk.addProperty(skill, xp);
		d.add("skills", sk);
		e.add("data", d);
		return e;
	}

	@Test
	public void loggingOutWritesTheSittingWithWhatItTookAndLeft()
	{
		h.level(Skill.HUNTER, 50, 101333).xp(Skill.HUNTER, 2000).xp(Skill.FISHING, 500);
		h.kill("Bear", 1, 1618, 1);
		TileItem left = h.ground(526, 3, new WorldPoint(3200, 3200, 0), false);
		h.tick().rot(left);
		h.tick().sitting(42).logout();
		JsonObject s = h.login().feed("SESSION").get(0);
		assertEquals(42, s.get("minutes").getAsInt());
		assertEquals(2000, s.get("xp").getAsInt());
		assertFalse(s.getAsJsonObject("skills").has("fishing"));
		assertEquals(1, s.get("drops").getAsInt());
		assertEquals(1800, s.get("dropsGp").getAsInt());
		assertEquals(3, s.get("left").getAsInt());
		assertEquals(1, s.get("leftKills").getAsInt());
		assertEquals(2000, s.getAsJsonObject("skills").get("hunter").getAsInt());
		assertTrue(s.get("start").getAsLong() > 0);
	}

	@Test
	public void aShortIdleSittingLeavesNoLine()
	{
		h.sitting(3).logout().login();
		assertTrue(h.feed("SESSION").isEmpty());
	}

	@Test
	public void theSittingInProgressReadsAsASessionInTheJournal()
	{
		h.kill("Bear", 1, 1618, 1).sitting(97);
		List<String> journal = h.screen("Journal");
		assertTrue(journal.toString(), has(journal, "Session · 1h 37m"));
		assertTrue(has(h.screen("Now"), "1 · 1,800 gp"));
	}

	@Test
	public void aSittingAcrossMidnightBelongsToTheDayItBegan()
	{
		LocalDate day = LocalDate.now().minusDays(3);
		h.edit(j ->
		{
			JsonArray feed = j.getAsJsonArray("feed");
			feed.add(sitting(day.plusDays(1).atTime(0, 2), 152, 354_610, 129, "hunter"));
			feed.add(sitting(day.plusDays(1).atTime(17, 0), 8, 2_540, 1, "fletching"));
		});
		List<String> said = h.screen("Journal");
		int began = said.indexOf(heading(day));
		int next = said.indexOf(heading(day.plusDays(1)));
		assertTrue(said.toString(), began >= 0 && next >= 0 && next < began);
		String beganLine = String.join(" · ", said.subList(began + 1, said.size()));
		assertTrue(beganLine, beganLine.startsWith("1 sitting · 2h 32m · +354k xp, most in Hunter · 129 drops"));
		assertFalse(said.subList(next, began).contains("Session · 2h 32m"));
		assertTrue(String.join(" · ", said.subList(next + 1, began)).startsWith("1 sitting · 8m · +2,540 xp"));
	}
}
