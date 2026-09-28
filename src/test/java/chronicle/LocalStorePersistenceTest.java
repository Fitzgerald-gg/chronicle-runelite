/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalStorePersistenceTest
{
	private static final String RSN = "Tester";
	private static final String FILE = "tester.json";

	private File dir;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("chronicle-persist").toFile();
	}

	private LocalStore newStore()
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(im.getItemPrice(Mockito.anyInt())).thenReturn(100L);
		ItemComposition comp = Mockito.mock(ItemComposition.class);
		Mockito.when(comp.getName()).thenReturn("Rune dagger");
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenReturn(comp);
		return new LocalStore(im, new Gson());
	}

	private LocalStore mounted()
	{
		LocalStore store = newStore();
		store.load(dir, RSN);
		return store;
	}

	@Test
	public void theCollectionLogCanBeFoldedWithoutTheRestOfTheCharacter()
	{
		LocalStore store = mounted();
		JsonObject skills = new JsonObject();
		JsonObject slayer = new JsonObject();
		slayer.addProperty("level", 99);
		slayer.addProperty("xp", 13_034_431);
		skills.add("Slayer", slayer);
		store.setCharacter(RSN, skills, 126, null, null);

		java.util.Map<String, Object> clog = new HashMap<>();
		java.util.Map<String, Integer> killLog = new HashMap<>();
		killLog.put("Wintertodt", 447);
		clog.put("slayer_kcs", killLog);
		store.setCharacter(RSN, null, 0, clog, null);

		JsonObject got = store.clogSnapshot();
		assertEquals("the kill log did not land",
			447, got.getAsJsonObject("slayer_kcs").get("Wintertodt").getAsInt());
		assertEquals(99, store.skillSheet().get("Slayer")[0]);
	}

	@Test
	public void theChatCountFloorsAndSurvivesAReload() throws Exception
	{
		LocalStore store = mounted();
		store.noteKillCount("subdued Wintertodt", 448, RSN);
		store.noteKillCount("subdued Wintertodt", 440, RSN);
		store.noteKillCount("Zulrah", 501, RSN);
		store.noteKillCount("", 9, RSN);
		store.noteKillCount("Nothing", 0, RSN);
		store.noteKillCount("Other", 5, "Someone Else");

		assertEquals(Long.valueOf(448), store.chatKillCounts().get("subdued Wintertodt"));
		assertEquals(Long.valueOf(501), store.chatKillCounts().get("Zulrah"));
		assertEquals(2, store.chatKillCounts().size());

		store.flush(dir);
		LocalStore back = newStore();
		back.load(dir, RSN);
		assertEquals("the counts did not survive the write",
			Long.valueOf(448), back.chatKillCounts().get("subdued Wintertodt"));
	}

	@Test
	public void theChatCountTravelsWithAnImport()
	{
		LocalStore store = mounted();
		store.noteKillCount("Zulrah", 100, RSN);

		JsonObject incoming = new JsonObject();
		JsonObject counts = new JsonObject();
		counts.addProperty("Zulrah", 501);
		counts.addProperty("subdued Wintertodt", 448);
		incoming.add("chat_kcs", counts);
		store.importJournal(incoming, RSN);

		assertEquals(Long.valueOf(501), store.chatKillCounts().get("Zulrah"));
		assertEquals(Long.valueOf(448), store.chatKillCounts().get("subdued Wintertodt"));
	}

	private void kill(LocalStore store, String source, int kc, int itemId, int qty)
	{
		JsonObject data = new JsonObject();
		data.addProperty("source", source);
		data.addProperty("killCount", kc);
		JsonArray items = new JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", itemId);
		it.addProperty("quantity", qty);
		items.add(it);
		data.add("items", items);
		store.record("LOOT", data, RSN);
	}

	private void pet(LocalStore store, String name)
	{
		JsonObject data = new JsonObject();
		data.addProperty("name", name);
		store.record("PET", data, RSN);
	}

	private void leave(LocalStore store, String source, int itemId, int qty, Integer kills)
	{
		JsonObject data = new JsonObject();
		data.addProperty("source", source);
		JsonArray items = new JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", itemId);
		it.addProperty("quantity", qty);
		items.add(it);
		data.add("items", items);
		if (kills != null)
		{
			data.addProperty("kills", kills);
		}
		store.record("LOOT_UNTAKEN", data, RSN);
	}

	private LocalStore.UntakenRow untakenSource(LocalStore store, String name)
	{
		for (LocalStore.UntakenRow row : store.untakenSources())
		{
			if (name.equals(row.name))
			{
				return row;
			}
		}
		throw new AssertionError("no untaken source " + name);
	}

	private Map<String, Integer> session(String key, int value)
	{
		Map<String, Integer> m = new HashMap<>();
		m.put(key, value);
		return m;
	}

	@Test
	public void aNewerJournalIsNeitherMountedNorOverwritten() throws Exception
	{
		String newer = "{\"schema\":2,\"rsn\":\"" + RSN + "\","
			+ "\"drops\":{\"Vorkath\":{\"kc\":156,\"loots\":143,\"value\":900}},"
			+ "\"somethingThisBuildHasNeverHeardOf\":[1,2,3]}";
		write(FILE, newer);

		LocalStore store = newStore();
		store.load(dir, RSN);

		assertFalse("a journal this build cannot read must not be mounted",
			store.isReadyFor(RSN));
		assertTrue("and the reader has to be told why",
			store.journalWarning() != null
				&& store.journalWarning().contains("newer version"));

		store.flush(dir);
		String after = new String(Files.readAllBytes(new File(dir, FILE).toPath()),
			StandardCharsets.UTF_8);
		assertEquals("the file on disk is byte for byte what was there", newer, after);
	}

	@Test
	public void theSameJournalMountsWhenTheSchemaIsReadable() throws Exception
	{
		write(FILE, "{\"schema\":1,\"rsn\":\"" + RSN + "\","
			+ "\"drops\":{\"Vorkath\":{\"kc\":156,\"loots\":143,\"value\":900}}}");
		LocalStore store = newStore();
		store.load(dir, RSN);
		assertTrue(store.isReadyFor(RSN));
		assertEquals(null, store.journalWarning());
	}

	private void write(String name, String content) throws Exception
	{
		Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String name) throws Exception
	{
		return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
	}

	private JsonObject readJson(String name) throws Exception
	{
		return new Gson().fromJson(read(name), JsonObject.class);
	}

	private String onlySidecar(String prefix)
	{
		String[] hits = dir.list((d, name) -> name.startsWith(prefix));
		assertTrue("no sidecar for " + prefix, hits != null && hits.length == 1);
		return hits[0];
	}

	private LocalStore.SourceRow source(LocalStore store, String name)
	{
		for (LocalStore.SourceRow row : store.dropSources())
		{
			if (name.equals(row.name))
			{
				return row;
			}
		}
		throw new AssertionError("no drop source " + name);
	}

	@Test
	public void aFlushedJournalReloadsIntoTheSameModel()
	{
		LocalStore first = mounted();
		kill(first, "Nechryael", 7, 4151, 2);
		pet(first, "Abyssal orphan");
		first.setTrackers(session("deaths", 3), RSN);
		first.flush(dir);

		LocalStore second = mounted();
		LocalStore.SourceRow row = source(second, "Nechryael");
		assertEquals(7, row.kc);
		assertEquals(1, row.loots);
		assertEquals(200L, row.value);

		List<LocalStore.BagItem> bag = second.sourceItems("Nechryael");
		assertEquals(1, bag.size());
		assertEquals(4151, bag.get(0).itemId);
		assertEquals(2L, bag.get(0).qty);
		assertEquals(200L, bag.get(0).value);
		assertEquals("Rune dagger", bag.get(0).name);

		List<JsonObject> feed = second.feedNewest(10);
		assertEquals(1, feed.size());
		assertEquals("PET", feed.get(0).get("type").getAsString());
		assertEquals("Abyssal orphan",
			feed.get(0).getAsJsonObject("data").get("name").getAsString());

		assertEquals(Long.valueOf(3), second.trackersSnapshot().get("deaths"));
	}

	@Test
	public void theKillsThatLeftLootSumPerSourceAndAcrossTheSession()
	{
		LocalStore store = mounted();
		leave(store, "Nechryael", 526, 3, 2);
		leave(store, "Nechryael", 526, 1, 1);
		leave(store, "Zulrah", 526, 2, 1);
		assertEquals(4, store.sessionUntakenKills());
		assertEquals(3, untakenSource(store, "Nechryael").kills);
		assertEquals(1, untakenSource(store, "Zulrah").kills);
		assertEquals(6, store.sessionUntakenTally()[0]);
		assertEquals(600L, store.sessionUntakenTally()[1]);
		assertEquals(4, untakenSource(store, "Nechryael").qty);
		assertEquals(Long.valueOf(4), store.spineExtras().get("lootLeftKills"));
		assertEquals(Long.valueOf(6), store.spineExtras().get("lootLeftCount"));
	}

	@Test
	public void anEventWithoutTheKillsFigureReadsAsNoKills()
	{
		LocalStore store = mounted();
		leave(store, "Nechryael", 526, 5, null);
		assertEquals(0, store.sessionUntakenKills());
		assertEquals(0, untakenSource(store, "Nechryael").kills);
		assertEquals(5, untakenSource(store, "Nechryael").qty);
		assertEquals(Long.valueOf(0), store.spineExtras().get("lootLeftKills"));
		leave(store, "Nechryael", 526, 1, -3);
		assertEquals(0, store.sessionUntakenKills());
		assertEquals(0, untakenSource(store, "Nechryael").kills);
	}

	@Test
	public void theKillsThatLeftLootSurviveAReloadAndTheSessionTallyDoesNot() throws Exception
	{
		LocalStore first = mounted();
		leave(first, "Nechryael", 526, 3, 2);
		first.flush(dir);
		assertEquals(2, readJson(FILE).getAsJsonObject("untaken")
			.getAsJsonObject("Nechryael").get("kills").getAsInt());

		LocalStore second = mounted();
		assertEquals(2, untakenSource(second, "Nechryael").kills);
		assertEquals(Long.valueOf(2), second.spineExtras().get("lootLeftKills"));
		assertEquals(0, second.sessionUntakenKills());
		leave(second, "Nechryael", 526, 1, 1);
		assertEquals(1, second.sessionUntakenKills());
		assertEquals(3, untakenSource(second, "Nechryael").kills);
		second.endSession();
		assertEquals(0, second.sessionUntakenKills());
	}

	@Test
	public void lifetimeCountersResumeFromTheStoredBaseNotFromZero()
	{
		LocalStore first = mounted();
		first.setTrackers(session("deaths", 4), RSN);
		first.setTrackers(session("deaths", 4), RSN);
		assertEquals(Long.valueOf(4), first.trackersSnapshot().get("deaths"));
		first.flush(dir);

		LocalStore second = mounted();
		second.setTrackers(session("deaths", 4), RSN);
		assertEquals(Long.valueOf(8), second.trackersSnapshot().get("deaths"));
	}

	@Test
	public void aPeakCounterKeepsTheRecordAcrossSessionsInsteadOfSumming()
	{
		LocalStore first = mounted();
		first.setTrackers(session("highestHit", 60), RSN);
		first.flush(dir);

		LocalStore second = mounted();
		second.setTrackers(session("highestHit", 50), RSN);
		assertEquals(Long.valueOf(60), second.trackersSnapshot().get("highestHit"));

		second.setTrackers(session("highestHit", 71), RSN);
		assertEquals(Long.valueOf(71), second.trackersSnapshot().get("highestHit"));
	}

	@Test
	public void anUnreadableRecordIsKeptAsideRatherThanOverwritten() throws Exception
	{
		String torn = "{\"schema\":1,\"rsn\":\"Tester\",\"drops\":{\"Nechryael\":{\"kc\":91";
		write(FILE, torn);

		LocalStore store = mounted();
		store.flush(dir);

		assertEquals(torn, read(onlySidecar(FILE + ".corrupt-")));
		kill(store, "Nechryael", 1, 4151, 1);
		store.flush(dir);
		assertEquals(1, source(mounted(), "Nechryael").loots);
	}

	@Test
	public void aRecordThatIsNotAnObjectIsTreatedAsUnreadable() throws Exception
	{
		write(FILE, "[1,2,3]");
		mounted().flush(dir);
		assertEquals("[1,2,3]", read(onlySidecar(FILE + ".corrupt-")));
	}

	@Test
	public void aRecordMissingItsContainersIsRepairedOnLoad() throws Exception
	{
		write(FILE, "{\"schema\":1,\"rsn\":\"Tester\"}");
		LocalStore store = mounted();

		kill(store, "Nechryael", 3, 4151, 1);
		pet(store, "Abyssal orphan");
		store.setTrackers(session("deaths", 1), RSN);

		assertEquals(1, source(store, "Nechryael").loots);
		assertEquals(1, store.feedNewest(10).size());
		assertEquals(Long.valueOf(1), store.trackersSnapshot().get("deaths"));
	}

	@Test
	public void containersHoldingTheWrongShapeAreReplacedNotTrusted() throws Exception
	{
		write(FILE, "{\"schema\":1,\"rsn\":\"Tester\",\"drops\":[],\"trackers\":7,\"feed\":{}}");
		LocalStore store = mounted();

		kill(store, "Nechryael", 2, 4151, 1);
		pet(store, "Abyssal orphan");
		store.setTrackers(session("deaths", 2), RSN);

		assertEquals(1, source(store, "Nechryael").loots);
		assertEquals(1, store.feedNewest(10).size());
		assertEquals(Long.valueOf(2), store.trackersSnapshot().get("deaths"));
	}

	@Test
	public void theJournalKeepsItsOriginalStartDateAcrossReloads() throws Exception
	{
		write(FILE, "{\"schema\":1,\"rsn\":\"Tester\",\"first_seen\":12345}");
		mounted().flush(dir);
		assertEquals(12345L, readJson(FILE).get("first_seen").getAsLong());
	}

	@Test
	public void anUnmountedStoreWritesNothing()
	{
		newStore().flush(dir);
		assertFalse(new File(dir, FILE).isFile());
		assertEquals(0, dir.list().length);
	}

	@Test
	public void theRecordIsFiledUnderTheAccountSlug()
	{
		LocalStore store = newStore();
		store.load(dir, "Some Name");
		store.flush(dir);
		assertTrue(new File(dir, "some-name.json").isFile());
	}

	@Test
	public void theJournalDirectoryIsCreatedOnFirstFlush()
	{
		File fresh = new File(dir, "nested/local");
		LocalStore store = newStore();
		store.load(fresh, RSN);
		store.flush(fresh);
		assertTrue(new File(fresh, FILE).isFile());
	}

	@Test
	public void aGatheredItemIsRememberedAcrossSessions()
	{
		LocalStore store = mounted();
		store.noteGathered(440);
		assertTrue(store.wasGathered(440));
		assertFalse(store.wasGathered(1333));
		store.flush(dir);

		LocalStore next = mounted();
		assertTrue(next.wasGathered(440));
		assertFalse(next.wasGathered(1333));
	}

	@Test
	public void anUnmountedStoreRemembersNoGathers()
	{
		LocalStore store = newStore();
		store.noteGathered(440);
		assertFalse(store.wasGathered(440));
	}

	@Test
	public void loggingOutClosesTheLedgerToTheNextAccount()
	{
		LocalStore store = mounted();
		store.noteGathered(440);
		store.endSession();
		assertFalse(store.wasGathered(440));
	}

	@Test
	public void aLevelUpIsKeptInTheJournalLikeEveryOtherMilestone()
	{
		LocalStore store = mounted();
		JsonObject d = new JsonObject();
		d.addProperty("skill", "Attack");
		d.addProperty("level", 99);
		store.record("LEVEL", d, "Tester");

		List<JsonObject> feed = store.feedNewest(10);
		assertEquals(1, feed.size());
		assertEquals("LEVEL", feed.get(0).get("type").getAsString());
		assertEquals("Attack",
			feed.get(0).getAsJsonObject("data").get("skill").getAsString());
		assertEquals(99,
			feed.get(0).getAsJsonObject("data").get("level").getAsInt());
	}

	@Test
	public void oneLevelUpWrittenTwiceCollapsesOnLoad() throws Exception
	{
		write(FILE, "{\"schema\":1,\"rsn\":\"Tester\",\"feed\":["
			+ "{\"ts\":1700000000000,\"type\":\"LEVEL\","
			+ "\"data\":{\"skill\":\"Attack\",\"level\":99}},"
			+ "{\"ts\":1700000000400,\"type\":\"LEVEL\","
			+ "\"data\":{\"skill\":\"Attack\",\"level\":99,\"source\":\"import\"}}"
			+ "]}");
		LocalStore store = newStore();
		store.load(dir, "Tester");
		assertEquals(1, store.feedNewest(10).size());
	}

	@Test
	public void twoSkillsLevellingInTheSameSecondBothSurvive() throws Exception
	{
		write(FILE, "{\"schema\":1,\"rsn\":\"Tester\",\"feed\":["
			+ "{\"ts\":1700000000000,\"type\":\"LEVEL\","
			+ "\"data\":{\"skill\":\"Attack\",\"level\":70}},"
			+ "{\"ts\":1700000000400,\"type\":\"LEVEL\","
			+ "\"data\":{\"skill\":\"Strength\",\"level\":70}}"
			+ "]}");
		LocalStore store = newStore();
		store.load(dir, "Tester");
		assertEquals(2, store.feedNewest(10).size());
	}

	@Test
	public void theAchievementSheetSurvivesAReload()
	{
		LocalStore store = mounted();
		JsonObject western = new JsonObject();
		western.addProperty("hard", true);
		western.addProperty("elite", false);
		JsonObject diaries = new JsonObject();
		diaries.add("western", western);
		JsonObject achievements = new JsonObject();
		achievements.add("diaries", diaries);
		store.setCharacter(RSN, null, 0, null, achievements);
		store.flush(dir);

		JsonObject back = mounted().achievements();
		assertTrue(back.getAsJsonObject("diaries").getAsJsonObject("western")
			.get("hard").getAsBoolean());
		assertFalse(back.getAsJsonObject("diaries").getAsJsonObject("western")
			.get("elite").getAsBoolean());
	}

	@Test
	public void anUngatheredSheetIsAnEmptyObjectNotNull()
	{
		assertEquals(0, mounted().achievements().size());
	}

	@Test
	public void theKeysAnEarlierBuildMintedWrongFoldOnLoadAndNothingElseMoves() throws Exception
	{
		write(FILE, "{\"schema\":1,\"rsn\":\"Tester\",\"first_seen\":1700000000,"
			+ "\"skills\":{\"Attack\":{\"level\":70,\"xp\":737627}},"
			+ "\"feed\":[{\"ts\":1700000000000,\"type\":\"LEVEL\","
			+ "\"data\":{\"skill\":\"Attack\",\"level\":70}}],"
			+ "\"trackers\":{\"logsLogsChopped\":1,\"normalLogsChopped\":4,"
			+ "\"guard(level21)Pickpockets\":2,\"guardPickpockets\":3,"
			+ "\"knight(level-46)FailedPickpockets\":6,"
			+ "\"__probe\":1,\"deaths\":7,\"willowLogsChopped\":9,\"highestHit\":60}}");
		LocalStore store = newStore();
		store.load(dir, "Tester");

		Map<String, Long> expected = new HashMap<>();
		expected.put("normalLogsChopped", 5L);
		expected.put("guardPickpockets", 5L);
		expected.put("knightFailedPickpockets", 6L);
		expected.put("deaths", 7L);
		expected.put("willowLogsChopped", 9L);
		expected.put("highestHit", 60L);
		assertEquals(expected, store.trackersSnapshot());

		store.setTrackers(session("deaths", 1), "Tester");
		expected.put("deaths", 8L);
		assertEquals(expected, store.trackersSnapshot());

		store.flush(dir);
		JsonObject flushed = readJson(FILE);
		Map<String, Long> onDisk = new HashMap<>();
		for (Map.Entry<String, com.google.gson.JsonElement> e
			: flushed.getAsJsonObject("trackers").entrySet())
		{
			onDisk.put(e.getKey(), e.getValue().getAsLong());
		}
		assertEquals(expected, onDisk);
		assertEquals(70, flushed.getAsJsonObject("skills").getAsJsonObject("Attack")
			.get("level").getAsInt());
		assertEquals(737627, flushed.getAsJsonObject("skills").getAsJsonObject("Attack")
			.get("xp").getAsInt());
		assertEquals(1, flushed.getAsJsonArray("feed").size());
		assertEquals(1700000000L, flushed.get("first_seen").getAsLong());

		LocalStore again = newStore();
		again.load(dir, "Tester");
		assertEquals(expected, again.trackersSnapshot());
	}
}
