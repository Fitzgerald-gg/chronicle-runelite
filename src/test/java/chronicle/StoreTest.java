/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.List;
import org.junit.Test;
import static chronicle.Harness.has;
import static chronicle.Harness.read;
import static chronicle.Harness.write;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StoreTest
{
	private static final String EXPORT = "{"
		+ "\"trackers\":{\"tilesWalked\":5000,\"fishCaught\":120},"
		+ "\"drops\":{\"Nechryael\":{\"kc\":300,\"loots\":280,\"value\":900000,"
		+ "\"pb\":42.5,\"first_seen\":1000,\"last_seen\":9000,"
		+ "\"items\":{\"Rune dagger\":{\"id\":1215,\"qty\":7,\"value\":70000}}}},"
		+ "\"feed\":[{\"ts\":1700000000000,\"type\":\"PET\",\"data\":{\"petName\":\"Phoenix\"}}],"
		+ "\"untaken\":{\"Nechryael\":{\"qty\":40,\"value\":4000}},"
		+ "\"chat_kcs\":{\"Zulrah\":501},"
		+ "\"slayer\":{\"completed\":214,\"tasks\":[{\"task\":\"Nechryael\",\"kills\":180}]}}";

	private final Harness h = new Harness();

	private static String text(File f)
	{
		try
		{
			return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (java.io.IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static File sidecar(String prefix)
	{
		String[] hits = Harness.DIR.list((d, n) -> n.startsWith(prefix));
		assertTrue("no " + prefix, hits != null && hits.length == 1);
		return new File(Harness.DIR, hits[0]);
	}

	@Test
	public void aJournalIsWrittenUnderTheAccountSlugAndReadBackWhole()
	{
		Harness named = new Harness("Some Name").login();
		named.kill("Nechryael", 11, 4151, 2).chat("Oh dear, you are dead!").save();
		assertTrue(new File(Harness.DIR, "some-name.json").isFile());
		named.logout().login();
		JsonObject j = named.journal();
		assertEquals(2, j.getAsJsonObject("drops").getAsJsonObject("Nechryael").getAsJsonObject("items")
			.getAsJsonObject("4151").get("qty").getAsInt());
		assertEquals(1, j.getAsJsonObject("trackers").get("deaths").getAsInt());
	}

	@Test
	public void lifetimeCountersResumeFromTheStoredBaseAndPeaksKeepTheRecord()
	{
		h.login().chat("Oh dear, you are dead!").hit(null, net.runelite.api.HitsplatID.DAMAGE_ME, 60).save().save();
		assertEquals(1, h.tracker("deaths"));
		h.logout().login().chat("Oh dear, you are dead!").hit(null, net.runelite.api.HitsplatID.DAMAGE_ME, 50);
		assertEquals(2, h.tracker("deaths"));
		assertEquals(60, h.tracker("highestHitTaken"));
		h.hit(null, net.runelite.api.HitsplatID.DAMAGE_ME, 71);
		assertEquals(71, h.tracker("highestHitTaken"));
	}

	@Test
	public void anUnreadableJournalIsSetAsideAndANewOneBegins()
	{
		String[] bad = {"{\"schema\":1,\"rsn\":\"Tester\",\"drops\":{\"Nechryael\":{\"kc\":91", "[1,2,3]"};
		for (String torn : bad)
		{
			Harness fresh = new Harness();
			write(fresh.file(), torn);
			fresh.login().kill("Nechryael", 11, 526, 1).save();
			assertEquals(torn, text(sidecar("tester.json.corrupt-")));
			assertEquals(1, fresh.journal().getAsJsonObject("drops").getAsJsonObject("Nechryael")
				.get("loots").getAsInt());
		}
	}

	@Test
	public void aJournalFromANewerBuildIsNeitherMountedNorTouched()
	{
		String newer = "{\"schema\":2,\"rsn\":\"Tester\",\"drops\":{\"Vorkath\":{\"kc\":156}}}";
		write(h.file(), newer);
		h.login().kill("Vorkath", 8061, 536, 1);
		h.save();
		assertEquals(newer, text(h.file()));
		assertTrue(has(h.screen("Now"), "newer version"));
	}

	@Test
	public void aDamagedJournalIsRepairedOnLoad()
	{
		write(h.file(), "{\"schema\":1,\"rsn\":\"Tester\",\"first_seen\":12345,\"drops\":[],\"trackers\":7,\"feed\":{},"
			+ "\"skills\":{}}");
		h.login().kill("Nechryael", 11, 526, 1).chat("Oh dear, you are dead!").chat(
			"New item added to your collection log: Bones");
		JsonObject j = h.journal();
		assertEquals(12345, j.get("first_seen").getAsLong());
		assertEquals(1, j.getAsJsonObject("drops").getAsJsonObject("Nechryael").get("loots").getAsInt());
		assertEquals(1, j.getAsJsonObject("trackers").get("deaths").getAsInt());
		assertEquals(1, j.getAsJsonArray("feed").size());
	}

	@Test
	public void anImportFloorsWhatIsHeldAndTwiceIsOnce()
	{
		h.login().kill("Nechryael", 11, 526, 1).save();
		File export = new File(Harness.DIR.getParentFile(), "export.json");
		write(export, EXPORT);
		h.importFile(export);
		JsonObject once = h.journal();
		h.importFile(export);
		JsonObject twice = h.journal();
		assertEquals(once.get("drops").toString(), twice.get("drops").toString());
		assertEquals(once.getAsJsonArray("feed").size(), twice.getAsJsonArray("feed").size());
		JsonObject n = twice.getAsJsonObject("drops").getAsJsonObject("Nechryael");
		assertEquals(300, n.get("kc").getAsInt());
		assertEquals(42.5, n.get("pb").getAsDouble(), 0.01);
		assertEquals(5000, twice.getAsJsonObject("trackers").get("tilesWalked").getAsInt());
		assertEquals(214, twice.getAsJsonObject("slayer").get("completed").getAsInt());
		assertEquals(Long.valueOf(501), h.kills().get("Zulrah"));
		assertTrue(h.said().stream().anyMatch(s -> s.startsWith("Chronicle: imported")));

		write(export, "{\"trackers\":{\"tilesWalked\":10},\"drops\":{\"Nechryael\":{\"kc\":3,\"loots\":2,"
			+ "\"value\":5,\"pb\":99.0,\"items\":{}}},\"slayer\":{\"completed\":2,\"tasks\":[]}}");
		h.importFile(export);
		JsonObject older = h.journal();
		assertEquals(5000, older.getAsJsonObject("trackers").get("tilesWalked").getAsInt());
		assertEquals(300, older.getAsJsonObject("drops").getAsJsonObject("Nechryael").get("kc").getAsInt());
		assertEquals(42.5, older.getAsJsonObject("drops").getAsJsonObject("Nechryael").get("pb").getAsDouble(), 0.01);
	}

	@Test
	public void anImportNeedsALoggedInAccountAndAJournal()
	{
		File export = new File(Harness.DIR.getParentFile(), "export.json");
		write(export, EXPORT);
		h.importFile(export);
		assertTrue(h.said().get(0).contains("log in first"));
		write(export, "{\"hello\":1}");
		h.login().importFile(export);
		assertTrue(h.said().get(1).contains("isn't a Chronicle journal"));
	}

	@Test
	public void aRenamedAccountTakesItsJournalAndHistoryAlong()
	{
		Harness a = new Harness("Alpha").login();
		a.kill("Zulrah", 2042, 526, 1).spine(java.time.LocalDate.now().minusDays(1), java.util.Map.of("overall", 5L),
			java.util.Map.of(), java.util.Map.of());
		a.logout();
		write(new File(Harness.DIR, "beta.json"), "{\"stranger\":true}");
		a.as("Beta").login();
		assertFalse(new File(Harness.DIR, "alpha.json").exists());
		assertFalse(new File(Harness.DIR, "alpha.history.jsonl").exists());
		assertTrue(a.journal().getAsJsonObject("drops").has("Zulrah"));
		assertFalse(a.spineLines().isEmpty());
		assertEquals("{\"stranger\":true}", text(sidecar("beta.json.conflict-")));
		assertTrue(a.said().stream().anyMatch(s -> s.contains("Alpha is now Beta")));
	}

	@Test
	public void anotherAccountNeverWritesIntoTheMountedJournal()
	{
		h.login().kill("Zulrah", 2042, 526, 1).save();
		h.as("Someone").kill("Vorkath", 8061, 536, 1).chat("Your Vorkath kill count is: 5.");
		h.as("Tester");
		List<String> sources = new java.util.ArrayList<>(h.journal().getAsJsonObject("drops").keySet());
		assertEquals(java.util.Arrays.asList("Zulrah"), sources);
		assertFalse(read(h.file()).toString().contains("Vorkath"));
	}
}
