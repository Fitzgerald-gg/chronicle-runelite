/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.counters.ChronicleCounters;
import chronicle.counters.ExperienceStatTracker.SkillGain;
import chronicle.counters.StatStore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Chronicle",
	description = "A comprehensive journal of your OSRS account - loot, levels, kill "
		+ "counts, collection log, slayer, clues, quests, diaries and lifetime counters.",
	tags = {"chronicle", "journal", "stats", "tracker", "loot", "slayer", "collection", "osrs"}
)
@PluginDependency(SlayerPlugin.class)
public class ChroniclePlugin extends Plugin
{
	static final String GROUP = ChronicleConfig.GROUP;
	private static final String KEY_JOURNAL_NAME = "journalName";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChronicleConfig config;

	@Inject
	private CloudSync cloud;

	@Inject
	private LootTrackerImport lootTracker;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private ChronicleEventCapture eventCapture;

	@Inject
	private ChronicleCounters counters;

	@Inject
	private StatStore statStore;

	@Inject
	private ClogCapture clogCapture;

	@Inject
	private AchievementSync achievementSync;

	@Inject
	private LocalStore localStore;

	@Inject
	private SkillIconManager skillIcons;

	@Inject
	private SpriteManager sprites;

	@Inject
	private Gson gson;

	private HistoryLog historyLog;

	private volatile String historyCacheRsn;
	private volatile TreeMap<LocalDate, HistoryLog.Baseline> historyCache;
	private volatile boolean historyLoading;

	private ChroniclePanel panel;
	private NavigationButton navButton;

	private ScheduledFuture<?> pushTask;
	private volatile boolean pendingLoginSetup;
	private volatile boolean wasLoggedIn;

	private static final String KEY_PLAYTIME = "gamePlaytime";
	private static final String KEY_PLAYTIME_AT = "gamePlaytimeAt";

	private volatile String localName;
	private volatile String captureWarning;
	private volatile String captureWarningWhy;
	private volatile long playtimeMinutes;
	private volatile long playtimeAt;
	private boolean playtimeLogged;
	private volatile long sessionStartMs;
	private long lastRollAttempt;
	private final long[] lastRevision = new long[4];
	private volatile Map<String, long[]> liveSkills = Collections.emptyMap();
	private volatile long skillRevision;

	@Provides
	ChronicleConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChronicleConfig.class);
	}

	@Override
	protected void startUp()
	{
		cloud.attach(this);
		historyLog = new HistoryLog(gson);
		panel = new ChroniclePanel(this);
		navButton = NavigationButton.builder()
			.tooltip("Chronicle")
			.icon(ImageUtil.loadImageResource(ChroniclePlugin.class, "plugin_nav_icon.png"))
			.priority(9)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		refreshPanel();

		eventCapture.reset();
		eventBus.register(eventCapture);
		counters.reset();
		counters.setConsumableSink((key, gp) ->
		{
			String who = localName;
			if (who != null)
			{
				localStore.addConsumableValue(key, gp, who);
			}
		});
		counters.setGatheredLedger(localStore);
		eventBus.register(counters);
		clogCapture.reset();
		eventBus.register(clogCapture);
		achievementSync.reset();

		reschedulePushLoop();
		checkDependencies();
		log.debug("Chronicle started - slayer service: {}",
			eventCapture.hasSlayerService() ? "AVAILABLE" : "MISSING");

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			pendingLoginSetup = true;
			wasLoggedIn = true;
			clientThread.invoke(() -> clogCapture.primeFromVarps());
		}
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(eventCapture);
		eventBus.unregister(counters);
		eventBus.unregister(clogCapture);
		if (pushTask != null)
		{
			pushTask.cancel(false);
			pushTask = null;
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		final ChroniclePanel dying = panel;
		if (dying != null)
		{
			SwingUtilities.invokeLater(dying::shutdown);
		}
		panel = null;
		pendingLoginSetup = false;
		if (ready())
		{
			bankSitting();
			localStore.rebase(localName);
			if (SwingUtilities.isEventDispatchThread())
			{
				executor.submit(() -> localStore.flush(localDir()));
			}
			else
			{
				localStore.flush(localDir());
			}
		}
		statStore.clear();
		wasLoggedIn = false;
	}

	private void reschedulePushLoop()
	{
		if (pushTask != null)
		{
			pushTask.cancel(false);
			pushTask = null;
		}
		long minutes = Math.max(1, config.pushIntervalMinutes());
		pushTask = executor.scheduleWithFixedDelay(
			this::scheduledPush, minutes, minutes, TimeUnit.MINUTES);
	}

	private void checkDependencies()
	{
		String was = captureWarning;
		String now = null;
		String why = null;
		if (isOff(SlayerPlugin.class))
		{
			now = "Slayer off";
			why = "Turn on the Slayer plugin for on-task drop tagging.";
		}
		else if (isOff(LootTrackerPlugin.class))
		{
			now = "Loot Tracker off";
			why = "Turn on the Loot Tracker plugin. Chest and casket loot reaches "
				+ "Chronicle through it.";
		}
		captureWarning = now;
		captureWarningWhy = why;
		if (!Objects.equals(was, now))
		{
			log.debug("capture warning: {}", now);
			refreshPanel();
		}
	}

	void turnOnMissingCapture()
	{
		for (Class<? extends Plugin> type : List.of(SlayerPlugin.class, LootTrackerPlugin.class))
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				if (!type.isInstance(p) || pluginManager.isPluginEnabled(p))
				{
					continue;
				}
				try
				{
					pluginManager.setPluginEnabled(p, true);
					pluginManager.startPlugin(p);
				}
				catch (PluginInstantiationException e)
				{
					log.warn("could not start {}", type.getSimpleName(), e);
				}
			}
		}
		checkDependencies();
	}

	private boolean isOff(Class<? extends Plugin> type)
	{
		try
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				if (type.isInstance(p))
				{
					return !pluginManager.isPluginEnabled(p);
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("plugin-enabled check failed for {}", type.getSimpleName(), e);
		}
		return false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState state = e.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			pendingLoginSetup = true;
			wasLoggedIn = true;
		}
		else if (state == GameState.LOGIN_SCREEN && wasLoggedIn)
		{
			wasLoggedIn = false;
			onLogout();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			takeLiveSkills();
			watchPlaytime();
			boolean turned = localName != null && historyLog.dayTurned(localName)
				&& System.currentTimeMillis() - lastRollAttempt >= 60_000L;
			if (ready() && (localStore.hasFreshAdjust() || turned))
			{
				if (turned)
				{
					lastRollAttempt = System.currentTimeMillis();
				}
				appendHistoryBaseline();
				executor.submit(() -> localStore.flush(localDir()));
			}
		}
		int moved = 0;
		long[] now = {localStore.revision(), statStore.revision(), skillRevision, clogCapture.revision()};
		for (int i = 0; i < now.length; i++)
		{
			if (now[i] != lastRevision[i])
			{
				lastRevision[i] = now[i];
				moved |= 1 << i;
			}
		}
		ChroniclePanel p = panel;
		if (moved != 0 && p != null)
		{
			p.update(moved);
		}
		if (!pendingLoginSetup || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String name = ChronicleEventCapture.playerName(client);
		if (name == null)
		{
			return;
		}
		pendingLoginSetup = false;
		if (name.equals(localName) && localStore.isReadyFor(name))
		{
			refreshPanel();
			if (cloud.active())
			{
				cloud.adopt(name);
			}
			return;
		}
		localName = name;
		sessionStartMs = System.currentTimeMillis();
		playtimeMinutes = readLong(KEY_PLAYTIME);
		playtimeAt = readLong(KEY_PLAYTIME_AT);
		refreshPanel();
		final String who = name;
		final String priorName = CloudSync.trim(
			configManager.getRSProfileConfiguration(GROUP, KEY_JOURNAL_NAME));
		executor.submit(() ->
		{
			if (priorName != null && !JournalFile.slug(priorName).equals(JournalFile.slug(who))
				&& JournalFile.migrateJournalFiles(localDir(), priorName, who))
			{
				chat("Chronicle: your journal followed the rename. "
					+ priorName + " is now " + who + ".");
			}
			configManager.setRSProfileConfiguration(GROUP, KEY_JOURNAL_NAME, who);
			localStore.load(localDir(), who);
			reloadHistory(who);
			ChroniclePanel open = panel;
			if (open != null)
			{
				SwingUtilities.invokeLater(open::resetAccountCaches);
			}
			clientThread.invoke(this::refreshLocal);
		});
		if (cloud.active())
		{
			cloud.adopt(name);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!GROUP.equals(e.getGroup()))
		{
			return;
		}
		String key = e.getKey();
		if ("importJournal".equals(key) && "true".equals(e.getNewValue()))
		{
			configManager.setConfiguration(GROUP, "importJournal", false);
			final ChroniclePanel asking = panel;
			if (asking != null)
			{
				SwingUtilities.invokeLater(asking::promptImport);
			}
			return;
		}
		if ("pushNow".equals(key) && "true".equals(e.getNewValue()))
		{
			configManager.setConfiguration(GROUP, "pushNow", false);
			cloud.pushNow();
			return;
		}
		if ("pushIntervalMinutes".equals(key) || "cloudSync".equals(key)
			|| "serverBaseUrl".equals(key) || "manualToken".equals(key))
		{
			reschedulePushLoop();
			if ("serverBaseUrl".equals(key))
			{
				clientThread.invoke(cloud::forget);
			}
			if ("cloudSync".equals(key) || "serverBaseUrl".equals(key))
			{
				clientThread.invoke(() ->
				{
					final String who = localName;
					if (who != null)
					{
						localStore.setTrackers(sessionView(), who);
						localStore.rebase(who);
						executor.submit(() -> localStore.flush(localDir()));
					}
					statStore.clear();
					counters.reset();
				});
			}
			if (cloud.active() && client.getGameState() == GameState.LOGGED_IN)
			{
				pendingLoginSetup = true;
			}
			refreshPanel();
		}
	}

	private void scheduledPush()
	{
		clientThread.invoke(this::refreshLocal);
		if (cloud.active())
		{
			clientThread.invoke(cloud::push);
		}
	}

	private boolean ready()
	{
		String rsn = localName;
		return rsn != null && localStore.isReadyFor(rsn);
	}

	private void bankSitting()
	{
		localStore.setCharacter(localName, null, 0, clogCapture.snapshot(), null);
		localStore.setTrackers(sessionView(), localName);
	}

	private void onLogout()
	{
		String who = ready() ? localName : null;
		if (who != null)
		{
			bankSitting();
			appendHistoryBaseline();
			recordSessionLine();
		}
		cloud.logout(who);
		executor.submit(() -> localStore.flush(localDir()));
		localStore.endSession();
		clogCapture.reset();
		eventCapture.resetSessionFlags();
		achievementSync.reset();
		statStore.clear();
	}

	private Map<String, long[]> readSkills()
	{
		Map<String, long[]> out = new LinkedHashMap<>();
		for (Skill s : Skill.values())
		{
			if (s != Skill.OVERALL)
			{
				out.put(s.name().toLowerCase(Locale.ROOT),
					new long[]{client.getRealSkillLevel(s), client.getSkillExperience(s)});
			}
		}
		out.put("overall", new long[]{client.getTotalLevel(), client.getOverallExperience()});
		return out;
	}

	JsonObject skillsJson()
	{
		JsonObject skills = new JsonObject();
		for (Map.Entry<String, long[]> e : readSkills().entrySet())
		{
			JsonObject o = Json.of("level", e.getValue()[0], "xp", e.getValue()[1]);
			skills.add(e.getKey(), o);
		}
		return skills;
	}

	String localName()
	{
		return localName;
	}

	LocalStore store()
	{
		return localStore;
	}

	Map<String, Long> lifetimeCounters()
	{
		return localStore.isReadyFor(localName)
			? localStore.lifetimeOf(sessionView())
			: localStore.trackersSnapshot();
	}

	long gamePlaytimeMinutes()
	{
		long had = playtimeMinutes;
		long at = playtimeAt;
		if (had <= 0)
		{
			return 0;
		}
		return had + (at > 0 && at >= sessionStartMs
			? Math.max(0, (System.currentTimeMillis() - at) / 60_000L)
			: sessionElapsedMinutes());
	}

	private void watchPlaytime()
	{
		long raw = client.getVarcIntValue(VarClientID.ACCOUNT_SUMMARY_PLAYTIME);
		if (raw <= 0 || raw == playtimeMinutes)
		{
			return;
		}
		playtimeMinutes = raw;
		playtimeAt = System.currentTimeMillis();
		configManager.setRSProfileConfiguration(GROUP, KEY_PLAYTIME, String.valueOf(raw));
		configManager.setRSProfileConfiguration(GROUP, KEY_PLAYTIME_AT, String.valueOf(playtimeAt));
		if (!playtimeLogged)
		{
			playtimeLogged = true;
			log.debug("the game says this account has played {} minutes", raw);
		}
	}


	private long readLong(String key)
	{
		try
		{
			return Long.parseLong(configManager.getRSProfileConfiguration(GROUP, key));
		}
		catch (NumberFormatException ignored)
		{
			return 0;
		}
	}

	long sessionStart()
	{
		return sessionStartMs;
	}

	long sessionElapsedMinutes()
	{
		if (sessionStartMs <= 0 || client.getGameState() != GameState.LOGGED_IN)
		{
			return 0;
		}
		return Math.max(0, (System.currentTimeMillis() - sessionStartMs) / 60_000L);
	}

	List<SkillGain> sessionSkillXp()
	{
		return counters.sessionSkillXp();
	}

	void fetchSlayerJourney(Consumer<SlayerLog.SlayerJourney> onDone)
	{
		if (!ready())
		{
			onDone.accept(null);
			return;
		}
		executor.submit(() -> onDone.accept(localStore.slayer.slayerJourney()));
	}

	SlayerLog.SlayerJourney slayerJourney()
	{
		return ready() ? localStore.slayer.slayerJourney() : null;
	}

	List<JsonObject> feedWithSitting(int n)
	{
		List<JsonObject> kept = localStore.feedNewest(n);
		JsonObject live = liveSessionLine();
		if (live == null)
		{
			return kept;
		}
		List<JsonObject> out = new ArrayList<>(kept.size() + 1);
		out.add(live);
		out.addAll(kept);
		return out;
	}

	ChronicleEventCapture.SlayerView slayerView()
	{
		return eventCapture.slayerView();
	}

	TreeMap<LocalDate, HistoryLog.Baseline> historyBaselines()
	{
		String rsn = localName;
		if (rsn == null)
		{
			return new TreeMap<>();
		}
		TreeMap<LocalDate, HistoryLog.Baseline> cached = historyCache;
		if (cached != null && rsn.equals(historyCacheRsn))
		{
			return cached;
		}
		if (!historyLoading)
		{
			historyLoading = true;
			executor.submit(() ->
			{
				try
				{
					reloadHistory(rsn);
				}
				finally
				{
					historyLoading = false;
				}
			});
		}
		return new TreeMap<>();
	}

	private void reloadHistory(String rsn)
	{
		historyLog.compact(localDir(), rsn);
		TreeMap<LocalDate, HistoryLog.Baseline> read = historyLog.read(localDir(), rsn);
		if (rsn.equals(localName))
		{
			historyCache = read;
			historyCacheRsn = rsn;
			refreshPanel();
		}
	}

	long keptSince()
	{
		long earliest = Long.MAX_VALUE;
		for (LocalStore.SourceRow r : localStore.dropSources())
		{
			if (r.firstMs > 0)
			{
				earliest = Math.min(earliest, r.firstMs);
			}
		}
		for (JsonObject e : localStore.feedNewest(4000))
		{
			if (e.has("ts"))
			{
				long ts = e.get("ts").getAsLong();
				if (ts > 0)
				{
					earliest = Math.min(earliest, ts);
				}
			}
		}
		return earliest == Long.MAX_VALUE ? 0 : earliest;
	}

	JsonObject achievements()
	{
		return localStore.achievements();
	}

	SkillIconManager skillIcons()
	{
		return skillIcons;
	}

	SpriteManager sprites()
	{
		return sprites;
	}

	Map<String, Long> killCounts()
	{
		return KillCounts.reconciledKills(localStore.clogSnapshot(),
			localStore.dropSources(), localStore.chatKillCounts(),
			localStore.anchoredKills());
	}

	Map<String, long[]> skillSheet()
	{
		Map<String, long[]> now = liveSkills;
		return now.isEmpty() ? localStore.skillSheet() : now;
	}

	int clogFinished()
	{
		return Math.max(clogCapture.finishedCount(), localStore.clogFraction()[0]);
	}

	int clogAvailable()
	{
		return Math.max(clogCapture.availableCount(), localStore.clogFraction()[1]);
	}

	PaceBook.Pace pace(String skill)
	{
		TreeMap<LocalDate, HistoryLog.Baseline> spine = historyBaselines();
		long xp = 0;
		try
		{
			xp = client.getSkillExperience(Skill.valueOf(skill.toUpperCase(Locale.ROOT)));
		}
		catch (RuntimeException ignored)
		{
		}
		return PaceBook.forSkill(spine, skill.toLowerCase(Locale.ROOT), xp, LocalDate.now());
	}

	boolean slayerSeenThisSession()
	{
		return eventCapture.slayerSeenThisSession();
	}

	private void recordSessionLine()
	{
		long mins = sessionStartMs > 0
			? Math.max(0, (System.currentTimeMillis() - sessionStartMs) / 60_000) : 0;
		Map<String, Integer> sess = sessionView();
		long xp = sess.getOrDefault("totalXpGained", 0);
		int drops = localStore.loot.sessionLoots();
		long dropsGp = localStore.loot.sessionLootValue();
		if (mins < 5 && xp == 0 && drops == 0)
		{
			return;
		}
		localStore.record("SESSION", sessionData(mins, xp, drops, dropsGp), localName);
	}

	private JsonObject sessionData(long mins, long xp, int drops, long dropsGp)
	{
		long[] left = localStore.loot.sessionUntakenTally();
		JsonObject data = new JsonObject();
		data.addProperty("minutes", mins);
		if (sessionStartMs > 0)
		{
			data.addProperty("start", sessionStartMs);
		}
		data.addProperty("xp", xp);
		data.addProperty("drops", drops);
		data.addProperty("dropsGp", dropsGp);
		data.addProperty("left", left[0]);
		data.addProperty("leftGp", left[1]);
		data.addProperty("leftKills", localStore.loot.sessionUntakenKills());
		JsonObject skills = new JsonObject();
		for (SkillGain g : sessionSkillXp())
		{
			if (g.xp > 0)
			{
				skills.addProperty(g.skill.name().toLowerCase(Locale.ROOT), g.xp);
			}
		}
		if (skills.size() > 0)
		{
			data.add("skills", skills);
		}
		return data;
	}

	private JsonObject liveSessionLine()
	{
		long mins = sessionElapsedMinutes();
		if (sessionStartMs <= 0 || client.getGameState() != GameState.LOGGED_IN || !ready())
		{
			return null;
		}
		Map<String, Integer> sess = sessionView();
		long xp = sess.getOrDefault("totalXpGained", 0);
		int drops = localStore.loot.sessionLoots();
		if (mins == 0 && xp == 0 && drops == 0)
		{
			return null;
		}
		JsonObject line = Json.of("type", "SESSION", "ts", System.currentTimeMillis(), "live", true);
		line.add("data", sessionData(mins, xp, drops, localStore.loot.sessionLootValue()));
		return line;
	}

	Map<String, Integer> sessionDisplayCounters()
	{
		Map<String, Integer> out = new HashMap<>(sessionView());
		for (String key : LocalStore.MAX_KEYS)
		{
			Integer val = out.get(key);
			if (val != null && val <= localStore.trackerBase(key))
			{
				out.remove(key);
			}
		}
		return out;
	}

	Gson gson()
	{
		return gson;
	}

	ItemManager items()
	{
		return localStore.items();
	}

	String displayRsn()
	{
		String r = cloud.rsn();
		return r != null ? r : localName;
	}

	Map<String, Integer> sessionView()
	{
		Map<String, Integer> abs = statStore.snapshotAll();
		Map<String, Integer> out = new HashMap<>(abs.size());
		for (Map.Entry<String, Integer> en : abs.entrySet())
		{
			if (LocalStore.MAX_KEYS.contains(en.getKey()) || en.getValue() > 0)
			{
				out.put(en.getKey(), en.getValue());
			}
		}
		return out;
	}

	private static File localDir()
	{
		return new File(RuneLite.RUNELITE_DIR, "chronicle");
	}

	private void refreshLocal()
	{
		String name = ChronicleEventCapture.playerName(client);
		if (client.getGameState() == GameState.LOGGED_IN && name != null)
		{
			Player lp = client.getLocalPlayer();
			localStore.setCharacter(name, skillsJson(), lp.getCombatLevel(),
				clogCapture.snapshot(), achievementSync.snapshot());
		}
		if (ready())
		{
			localStore.setTrackers(sessionView(), localName);
			checkDependencies();
			if (historyLog.dayRolledOver(localName) || localStore.hasPendingAdjust())
			{
				appendHistoryBaseline();
			}
			lootTracker.run(this);
		}
		executor.submit(() -> localStore.flush(localDir()));
	}

	private void appendHistoryBaseline()
	{
		final String rsn = localName;
		if (rsn == null)
		{
			return;
		}
		final Map<String, Long> skills = new HashMap<>();
		readSkills().entrySet().forEach(e -> skills.put(e.getKey(), e.getValue()[1]));
		final Map<String, Long> counters = localStore.spineCounters();
		final Map<String, Long> kcs = killCounts();
		final HistoryLog.Adjust adj = localStore.takePendingAdjust();
		executor.submit(() ->
		{
			HistoryLog.Adjust unwritten = historyLog.append(localDir(), rsn, skills, counters, kcs, adj,
				LocalDate.now());
			if (unwritten != null)
			{
				localStore.restorePendingAdjust(rsn, unwritten);
			}
			reloadHistory(rsn);
		});
	}

	String captureWarning()
	{
		return captureWarning;
	}

	String captureWarningWhy()
	{
		return captureWarningWhy;
	}

	String journalWarning()
	{
		return localStore.journalWarning();
	}

	void actionImport(File file)
	{
		final String rsn = localName;
		if (!ready())
		{
			chat("Chronicle: log in first. An import lands in the logged-in account's journal.");
			return;
		}
		if (file == null || !file.isFile())
		{
			return;
		}
		executor.submit(() ->
		{
			JsonObject in;
			try
			{
				String txt = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
				JsonElement el = gson.fromJson(txt, JsonElement.class);
				in = el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
			}
			catch (Exception e)
			{
				log.debug("import read failed", e);
				chat("Chronicle: couldn't read that file. It doesn't look like a journal.");
				return;
			}
			if (in == null || !(in.has("trackers") || in.has("drops") || in.has("feed")))
			{
				chat("Chronicle: that file isn't a Chronicle journal.");
				return;
			}
			String summary = localStore.importJournal(in, rsn);
			if (summary == null)
			{
				return;
			}
			File spine = new File(file.getParentFile(),
				file.getName().replaceAll("\\.json$", "") + HistoryLog.SPINE_SUFFIX);
			int days = spine.isFile() ? historyLog.importSpine(localDir(), rsn, spine) : 0;
			localStore.flush(localDir());
			reloadHistory(rsn);
			chat("Chronicle: imported " + summary
				+ (days > 0 ? " · " + days + " days of history" : "") + ".");
			clientThread.invoke(this::refreshLocal);
		});
	}

	private void takeLiveSkills()
	{
		Map<String, long[]> out = readSkills();
		Map<String, long[]> was = liveSkills;
		long[] wasOverall = was.get("overall");
		liveSkills = out;
		if (wasOverall == null || wasOverall[1] != out.get("overall")[1] || was.size() != out.size())
		{
			skillRevision++;
		}
	}

	void refreshPanel()
	{
		ChroniclePanel p = panel;
		if (p != null)
		{
			p.update();
		}
	}

	void chat(String message)
	{
		clientThread.invoke(() ->
		{
			try
			{
				client.addChatMessage(ChatMessageType.CONSOLE, "", message, null);
			}
			catch (Exception ignored)
			{
			}
		});
	}
}
