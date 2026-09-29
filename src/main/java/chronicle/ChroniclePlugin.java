/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.counters.ChronicleCounters;
import chronicle.counters.ExperienceStatTracker.SkillGain;
import chronicle.counters.StatStore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import net.runelite.api.gameval.VarbitID;
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
import net.runelite.http.api.loottracker.LootRecordType;

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
	static final String KEY_TOKEN = "token";
	static final String KEY_JOURNAL_NAME = "journalName";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChronicleConfig config;

	@Inject
	private ChronicleApiClient api;

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

	private volatile String cachedToken;
	private volatile String cachedName;
	private volatile Map<String, Integer> cachedSnapshot;
	private volatile String cachedAccountType;

	private volatile boolean lootImportRunning;

	private static final Set<String> PUSH_EXCLUDE = Set.of("untakenLootValue", "untakenLootCount", "resourcesGatheredValue");
	private static final String KEY_PLAYTIME = "gamePlaytime";
	private static final String KEY_PLAYTIME_AT = "gamePlaytimeAt";

	private GrindBook grindBook;

	private volatile String syncedRsn;
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
		historyLog = new HistoryLog(gson);
		grindBook = new GrindBook(gson);
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
			if (cloudActive())
			{
				adoptToken(name);
			}
			return;
		}
		localName = name;
		sessionStartMs = System.currentTimeMillis();
		loadPlaytime();
		refreshPanel();
		final String who = name;
		final String priorName = trimToNull(
			configManager.getRSProfileConfiguration(GROUP, KEY_JOURNAL_NAME));
		executor.submit(() ->
		{
			if (priorName != null && !LocalStore.slug(priorName).equals(LocalStore.slug(who))
				&& LocalStore.migrateJournalFiles(localDir(), priorName, who))
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
		if (cloudActive())
		{
			adoptToken(name);
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
			actionPushNow();
			return;
		}
		if ("pushIntervalMinutes".equals(key) || "cloudSync".equals(key)
			|| "serverBaseUrl".equals(key) || "manualToken".equals(key))
		{
			reschedulePushLoop();
			if ("serverBaseUrl".equals(key))
			{
				clientThread.invoke(() ->
				{
					if (trimToNull(config.manualToken()) == null)
					{
						configManager.unsetRSProfileConfiguration(GROUP, KEY_TOKEN);
					}
					cachedToken = null;
					cachedName = null;
					syncedRsn = null;
				});
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
			if (cloudActive() && client.getGameState() == GameState.LOGGED_IN)
			{
				pendingLoginSetup = true;
			}
			refreshPanel();
		}
	}

	private void adoptToken(String name)
	{
		String override = trimToNull(config.manualToken());
		String token = trimToNull(configManager.getRSProfileConfiguration(GROUP, KEY_TOKEN));
		if (override != null && !override.equals(token))
		{
			configManager.setRSProfileConfiguration(GROUP, KEY_TOKEN, override);
			token = override;
		}
		if (token == null)
		{
			refreshPanel();
			return;
		}
		syncedRsn = name;
		cachedToken = token;
		cachedName = name;
		refreshPanel();
		pushCurrent();
	}

	private void scheduledPush()
	{
		clientThread.invoke(this::refreshLocal);
		if (cloudActive())
		{
			clientThread.invoke(this::pushCurrent);
		}
	}

	private void pushCurrent()
	{
		String name = ChronicleEventCapture.playerName(client);
		if (!cloudActive() || client.getGameState() != GameState.LOGGED_IN || name == null)
		{
			return;
		}
		api.setAccountHash(client.getAccountHash());
		String token = trimToNull(configManager.getRSProfileConfiguration(GROUP, KEY_TOKEN));
		if (token == null)
		{
			return;
		}
		if (clogCapture.isDirty())
		{
			api.pushClog(config.serverBaseUrl(), token, name, clogCapture.snapshot());
			clogCapture.clearDirty();
		}
		final JsonObject achievements = achievementSync.snapshot();
		if (achievementSync.changedSince(achievements))
		{
			api.pushAchievements(config.serverBaseUrl(), token, name, achievements,
				ok ->
				{
					if (ok)
					{
						achievementSync.markSynced(achievements);
					}
				});
		}
		if (!name.equals(localName) || !localStore.isReadyFor(name))
		{
			return;
		}
		localStore.setTrackers(sessionView(), localName);
		Map<String, Integer> snapshot = journalAbsolutes(name);
		if (snapshot.isEmpty())
		{
			return;
		}
		cachedToken = token;
		cachedName = name;
		cachedSnapshot = snapshot;
		cachedAccountType = accountTypeTag(client.getVarbitValue(VarbitID.IRONMAN));
		syncedRsn = name;

		log.debug("pushing {} counters for {}", snapshot.size(), name);
		api.pushStats(config.serverBaseUrl(), token, name, snapshot, cachedAccountType,
			harvestSkills(), this::refreshPanel);
	}

	private static String accountTypeTag(int varbit)
	{
		switch (varbit)
		{
			case 1: return "ironman";
			case 2: return "uim";
			case 3: return "hcim";
			case 4: return "gim";
			case 5: return "hcgim";
			case 6: return "ugim";
			default: return "";
		}
	}

	private Map<String, Integer> journalAbsolutes(String rsn)
	{
		Map<String, Integer> out = new HashMap<>();
		if (!localStore.isReadyFor(rsn))
		{
			return out;
		}
		for (Map.Entry<String, Long> e : localStore.trackersSnapshot().entrySet())
		{
			long v = e.getValue() != null ? e.getValue() : 0;
			if (v > 0 && !PUSH_EXCLUDE.contains(e.getKey()))
			{
				out.put(e.getKey(), (int) Math.min(Integer.MAX_VALUE, v));
			}
		}
		return out;
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
		if (ready())
		{
			bankSitting();
			appendHistoryBaseline();
			recordSessionLine();
			Map<String, Integer> fresh = journalAbsolutes(localName);
			if (!fresh.isEmpty())
			{
				cachedSnapshot = fresh;
			}
		}
		executor.submit(() -> localStore.flush(localDir()));
		localStore.endSession();
		clogCapture.reset();
		eventCapture.resetSessionFlags();
		achievementSync.reset();
		statStore.clear();
		final String token = cachedToken;
		final String who = cachedName;
		final String type = cachedAccountType;
		final Map<String, Integer> snapshot = cachedSnapshot;
		cachedToken = null;
		cachedName = null;
		cachedSnapshot = null;
		cachedAccountType = null;
		syncedRsn = null;
		if (!cloudActive() || token == null || who == null
			|| snapshot == null || snapshot.isEmpty())
		{
			return;
		}
		api.pushStats(config.serverBaseUrl(), token, who, snapshot, type,
			null, this::refreshPanel);
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

	private JsonObject harvestSkills()
	{
		JsonObject skills = new JsonObject();
		for (Map.Entry<String, long[]> e : readSkills().entrySet())
		{
			JsonObject o = new JsonObject();
			o.addProperty("level", e.getValue()[0]);
			o.addProperty("xp", e.getValue()[1]);
			skills.add(e.getKey(), o);
		}
		return skills;
	}

	void actionPushNow()
	{
		if (!cloudActive())
		{
			chat("Chronicle: enable cloud sync (and set a server) under Advanced in the "
				+ "plugin settings first.");
			return;
		}
		clientThread.invoke(this::pushCurrent);
	}

	boolean cloudActive()
	{
		return config.cloudSync() && !config.serverBaseUrl().trim().isEmpty();
	}

	Map<String, Long> lifetimeCounters()
	{
		return localStore.isReadyFor(localName)
			? localStore.lifetimeOf(sessionView())
			: localStore.trackersSnapshot();
	}

	long gamePlaytimeMinutes()
	{
		return carriedForward(playtimeMinutes, playtimeAt, System.currentTimeMillis(),
			sessionStartMs, sessionElapsedMinutes());
	}

	static long carriedForward(long had, long at, long now, long sessionStart,
		long sessionElapsed)
	{
		if (had <= 0)
		{
			return 0;
		}
		long since = at > 0 && at >= sessionStart
			? Math.max(0, (now - at) / 60_000L)
			: Math.max(0, sessionElapsed);
		return had + since;
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

	private void loadPlaytime()
	{
		playtimeMinutes = readLong(KEY_PLAYTIME);
		playtimeAt = readLong(KEY_PLAYTIME_AT);
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
		if (sessionStartMs <= 0 || client == null
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return 0;
		}
		return Math.max(0, (System.currentTimeMillis() - sessionStartMs) / 60_000L);
	}

	List<SkillGain> sessionSkillXp()
	{
		ChronicleCounters c = counters;
		return c == null ? Collections.emptyList() : c.sessionSkillXp();
	}

	List<LocalStore.SourceRow> dropSources()
	{
		return localStore.dropSources();
	}

	long lootRollFrom()
	{
		return localStore.lootRollFrom();
	}

	LocalStore.LootWindow lootBetween(LocalDate from, LocalDate to)
	{
		return localStore.lootBetween(from, to);
	}

	long[] itemDays(String name)
	{
		return localStore.itemDays(name);
	}

	Map<String, long[]> dayTotals()
	{
		return localStore.dayTotals();
	}

	List<LocalStore.BagItem> sourceItems(String source)
	{
		return localStore.sourceItems(source);
	}

	void fetchSlayerJourney(Consumer<LocalStore.SlayerJourney> onDone)
	{
		if (!ready())
		{
			onDone.accept(null);
			return;
		}
		executor.submit(() -> onDone.accept(localStore.slayerJourney()));
	}

	LocalStore.SlayerJourney slayerJourney()
	{
		return ready() ? localStore.slayerJourney() : null;
	}

	List<JsonObject> feedNewest(int n)
	{
		return localStore.feedNewest(n);
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

	LocalStore.LootWindow sessionLootWindow()
	{
		return localStore.sessionLootWindow();
	}

	Map<String, List<LocalStore.BagItem>> itemsBySource(LocalDate from, LocalDate to)
	{
		return localStore.itemsBySource(from, to);
	}

	Set<String> unfiledSources(LocalDate from, LocalDate to)
	{
		return localStore.unfiledSources(from, to);
	}

	long lootDetailFrom()
	{
		return localStore.lootDetailFrom();
	}

	int sessionLoots()
	{
		return localStore.sessionLoots();
	}

	long sessionLootValue()
	{
		return localStore.sessionLootValue();
	}

	List<LocalStore.RecentDrop> recentDrops()
	{
		return localStore.recentDrops();
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
		return localStore == null ? new JsonObject() : localStore.achievements();
	}

	int combatLevel()
	{
		return localStore.combatLevel();
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
		return LocalStore.reconciledKills(localStore.clogSnapshot(),
			localStore.dropSources(), localStore.chatKillCounts(),
			localStore.anchoredKills());
	}

	Map<String, long[]> skillSheet()
	{
		Map<String, long[]> now = liveSkills;
		return now.isEmpty() ? localStore.skillSheet() : now;
	}

	JsonObject clogSnapshot()
	{
		return localStore.clogSnapshot();
	}

	int clogFinished()
	{
		return Math.max(clogCapture.finishedCount(), localStore.clogFraction()[0]);
	}

	int clogAvailable()
	{
		return Math.max(clogCapture.availableCount(), localStore.clogFraction()[1]);
	}

	List<LocalStore.BagItem> onTaskLoot(long fromMs, long toMs, String task, boolean includeOpen)
	{
		return localStore.onTaskLoot(fromMs, toMs, task, includeOpen);
	}

	List<LocalStore.BagItem> allLoot()
	{
		return localStore.allLoot();
	}

	List<String> taskNames()
	{
		return localStore.taskNames();
	}

	long[] onTaskTally(long fromMs, long toMs, String onlyTask, boolean includeOpen)
	{
		return localStore.onTaskTally(fromMs, toMs, onlyTask, includeOpen);
	}

	Map<String, Long> journalFacts()
	{
		return localStore.journalFacts();
	}

	Map<String, Long> chatKills()
	{
		return localStore.chatKillCounts();
	}

	Map<String, Long> anchoredKills()
	{
		return localStore.anchoredKills();
	}

	Map<String, long[]> onTaskItems(long fromMs, long toMs)
	{
		return localStore.onTaskItems(fromMs, toMs);
	}

	Map<String, Long> onTaskKills(long fromMs, long toMs)
	{
		return localStore.onTaskKills(fromMs, toMs);
	}

	List<Object[]> onTaskItemByTask(String itemName, long fromMs, long toMs)
	{
		return localStore.onTaskItemByTask(itemName, fromMs, toMs);
	}

	List<LocalStore.Assignment> onTaskAssignments(String npc, long fromMs, long toMs)
	{
		return localStore.onTaskAssignments(npc, fromMs, toMs);
	}

	List<LocalStore.BagItem> untakenItemsOf(String source)
	{
		return localStore.untakenItemsOf(source);
	}

	List<LocalStore.UntakenRow> untakenSourcesOf(String item)
	{
		return localStore.untakenSourcesOf(item);
	}

	List<LocalStore.BagItem> slayerTaskItems(int index)
	{
		return localStore.slayerTaskItems(index);
	}

	List<LocalStore.UntakenRow> slayerTaskMonsters(int index)
	{
		return localStore.slayerTaskMonsters(index);
	}

	List<LocalStore.PetRow> pets()
	{
		return localStore.pets();
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

	List<LocalStore.UntakenRow> untakenSources()
	{
		return localStore.untakenSources();
	}

	List<LocalStore.UntakenRow> untakenItems()
	{
		return localStore.untakenItems();
	}

	Map<String, Long> consumableValues()
	{
		return localStore.consumableValues();
	}

	void fetchGrinds(Consumer<List<GrindBook.GrindRow>> onDone)
	{
		if (!ready())
		{
			onDone.accept(null);
			return;
		}
		final JsonObject clog = localStore.clogSnapshot();
		final List<LocalStore.SourceRow> sources = localStore.dropSources();
		executor.submit(() -> onDone.accept(grindBook.grinds(clog, sources)));
	}

	Map<String, GrindBook.PetChase> petChases(Collection<String> pets)
	{
		if (!ready())
		{
			return Collections.emptyMap();
		}
		return grindBook.petChases(localStore.clogSnapshot(), localStore.dropSources(),
			localStore.trackersSnapshot(), localStore.skillSheet(),
			localStore.achievements(), pets);
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
		int drops = localStore.sessionLoots();
		long dropsGp = localStore.sessionLootValue();
		if (mins < 5 && xp == 0 && drops == 0)
		{
			return;
		}
		localStore.record("SESSION", sessionData(mins, xp, drops, dropsGp), localName);
	}

	private JsonObject sessionData(long mins, long xp, int drops, long dropsGp)
	{
		long[] left = localStore.sessionUntakenTally();
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
		data.addProperty("leftKills", localStore.sessionUntakenKills());
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

	JsonObject liveSessionLine()
	{
		if (client == null || localStore == null)
		{
			return null;
		}
		long mins = sessionElapsedMinutes();
		if (sessionStartMs <= 0 || client.getGameState() != GameState.LOGGED_IN || !ready())
		{
			return null;
		}
		Map<String, Integer> sess = sessionView();
		long xp = sess.getOrDefault("totalXpGained", 0);
		int drops = localStore.sessionLoots();
		if (mins == 0 && xp == 0 && drops == 0)
		{
			return null;
		}
		JsonObject line = new JsonObject();
		line.addProperty("type", "SESSION");
		line.addProperty("ts", System.currentTimeMillis());
		line.addProperty("live", true);
		line.add("data", sessionData(mins, xp, drops, localStore.sessionLootValue()));
		return line;
	}

	long[] sessionUntakenTally()
	{
		return localStore.sessionUntakenTally();
	}

	int sessionUntakenKills()
	{
		return localStore.sessionUntakenKills();
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
		return cloudActive() && syncedRsn != null && !syncedRsn.isEmpty() ? syncedRsn : localName;
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
			localStore.setCharacter(name, harvestSkills(), lp.getCombatLevel(),
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
			if (!"true".equals(configManager.getRSProfileConfiguration(GROUP, "lootTrackerImported")))
			{
				importLootTracker();
			}
		}
		executor.submit(() -> localStore.flush(localDir()));
	}

	@lombok.RequiredArgsConstructor
	private static final class RawSource
	{
		final String source;
		final int kills;
		final long firstMs;
		final long lastMs;
		final List<long[]> items = new ArrayList<>();
	}

	private void importLootTracker()
	{
		if (lootImportRunning)
		{
			return;
		}
		lootImportRunning = true;
		final String who = localName;
		final String profileKey = configManager.getRSProfileKey();
		executor.submit(() ->
		{
			final List<RawSource> parsed;
			try
			{
				parsed = readLootTrackerArchive(profileKey);
			}
			catch (RuntimeException e)
			{
				lootImportRunning = false;
				log.debug("loot tracker archive read failed", e);
				return;
			}
			clientThread.invoke(() -> adoptLootTrackerArchive(who, parsed));
		});
	}

	private static boolean wantedLootType(String key)
	{
		return key != null && Arrays.stream(LootRecordType.values())
			.anyMatch(t -> t != LootRecordType.PLAYER && key.startsWith("drops_" + t.name() + "_"));
	}

	private List<RawSource> readLootTrackerArchive(String profileKey)
	{
		List<RawSource> out = new ArrayList<>();
		List<String> keys;
		try
		{
			keys = configManager.getRSProfileConfigurationKeys("loottracker", profileKey, "drops_");
		}
		catch (RuntimeException e)
		{
			return out;
		}
		for (String key : keys == null ? Collections.<String>emptyList() : keys)
		{
			String raw = wantedLootType(key) ? configManager.getConfiguration("loottracker", profileKey, key) : null;
			if (raw == null || raw.isEmpty())
			{
				continue;
			}
			try
			{
				JsonObject o = gson.fromJson(raw, JsonObject.class);
				String source = o.has("name") ? o.get("name").getAsString() : null;
				if (source == null || source.isEmpty())
				{
					continue;
				}
				RawSource src = new RawSource(source,
					o.has("kills") ? o.get("kills").getAsInt() : 0,
					o.has("first") ? o.get("first").getAsLong() : 0,
					o.has("last") ? o.get("last").getAsLong() : 0);
				if (o.has("drops") && o.get("drops").isJsonArray())
				{
					JsonArray arr = o.getAsJsonArray("drops");
					for (int i = 0; i + 1 < arr.size(); i += 2)
					{
						long id = arr.get(i).getAsLong();
						long qty = arr.get(i + 1).getAsLong();
						if (id > 0 && qty > 0)
						{
							src.items.add(new long[]{id, qty});
						}
					}
				}
				out.add(src);
			}
			catch (RuntimeException e)
			{
				log.debug("loot tracker record parse failed: {}", key, e);
			}
		}
		return out;
	}

	private void adoptLootTrackerArchive(String rsn, List<RawSource> parsed)
	{
		try
		{
			if (rsn == null || !rsn.equals(localName) || !localStore.isReadyFor(rsn))
			{
				return;
			}
			List<LocalStore.LootSeed> seeds = new ArrayList<>(parsed.size());
			long events = 0;
			for (RawSource src : parsed)
			{
				List<LocalStore.BagItem> items = new ArrayList<>(src.items.size());
				for (long[] drop : src.items)
				{
					int canon = localStore.items().canonicalize((int) drop[0]);
					String name;
					try
					{
						name = localStore.items().getItemComposition(canon).getName();
					}
					catch (Exception e)
					{
						name = "Item " + canon;
					}
					long each = localStore.items().getItemPrice(canon);
					items.add(new LocalStore.BagItem(canon, name, drop[1], Math.max(0, each) * drop[1]));
				}
				seeds.add(new LocalStore.LootSeed(src.source, src.kills, src.firstMs, src.lastMs, items));
				events += src.kills;
			}
			if (!seeds.isEmpty())
			{
				localStore.floorLootTracker(seeds, rsn);
				chat("Chronicle: adopted " + seeds.size() + " sources · "
					+ String.format(Locale.UK, "%,d", events)
					+ " loot events from your Loot Tracker.");
				refreshPanel();
			}
			configManager.setRSProfileConfiguration(GROUP, "lootTrackerImported", true);
		}
		finally
		{
			lootImportRunning = false;
		}
	}

	private void appendHistoryBaseline()
	{
		final String rsn = localName;
		if (rsn == null)
		{
			return;
		}
		final Map<String, Long> skills = new HashMap<>();
		for (Map.Entry<String, long[]> e : readSkills().entrySet())
		{
			skills.put(e.getKey(), e.getValue()[1]);
		}
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

	private void refreshPanel()
	{
		ChroniclePanel p = panel;
		if (p != null)
		{
			p.update();
		}
	}

	private void chat(String message)
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

	private static String trimToNull(String s)
	{
		return s == null || s.trim().isEmpty() ? null : s.trim();
	}
}
