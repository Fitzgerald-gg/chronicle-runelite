/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Chronicle",
	description = "A comprehensive journal of your OSRS account - loot, levels, kill "
		+ "counts, collection log, slayer, clues, quests, diaries and lifetime counters.",
	tags = {"chronicle", "journal", "stats", "tracker", "loot", "slayer", "collection", "osrs"}
)
// The Slayer plugin's service supplies the active task for on-task drop tagging.
// The dependency guarantees it's loaded, and its service bound, before us.
@PluginDependency(SlayerPlugin.class)
// Chest, casket and every other non-NPC pickup reaches us only as the core Loot
// Tracker's LootReceived, and its archive is what a late install inherits from.
@PluginDependency(LootTrackerPlugin.class)
public class ChroniclePlugin extends Plugin
{
	static final String GROUP = ChronicleConfig.GROUP; // "chronicle"
	static final String KEY_TOKEN = "token";
	// The name this account's journal is filed under. RSProfile-scoped: keyed on the
	// account hash, which survives an in-game rename.
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
	private chronicle.counters.ChronicleCounters counters;

	@Inject
	private chronicle.counters.StatStore statStore;

	@Inject
	private ClogCapture clogCapture;

	@Inject
	private AchievementSync achievementSync;

	@Inject
	private LocalStore localStore;

	@Inject
	private net.runelite.client.game.SkillIconManager skillIcons;

	@Inject
	private net.runelite.client.game.SpriteManager sprites;

	// Injected: Hub review rejects a plugin that builds its own Gson.
	@Inject
	private com.google.gson.Gson gson;

	private HistoryLog historyLog;

	// The calendar spine, parsed off the EDT and published whole. The History tab
	// re-reads it on every pill, stepper and date click, and the file is unbounded.
	private volatile String historyCacheRsn;
	private volatile java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> historyCache;
	private volatile boolean historyLoading;

	private ChroniclePanel panel;
	private NavigationButton navButton;

	private ScheduledFuture<?> pushTask;
	// Armed at login, spent on the first tick the player's name has populated.
	// Volatile because a settings toggle arms it on the EDT and onGameTick reads it
	// on the client thread, with nothing between them to publish the write.
	private volatile boolean pendingLoginSetup;
	// True from the moment an account is in-game until its session is torn down. It
	// can't key off the prior state: a dropped connection arrives via CONNECTION_LOST.
	private volatile boolean wasLoggedIn;

	// Kept from the last harvest so a logout push still works once the RSProfile is gone.
	private volatile String cachedToken;
	private volatile String cachedName;
	private volatile Map<String, Integer> cachedSnapshot;
	private volatile String cachedAccountType;

	// Set while the Loot Tracker adoption is between its off-thread read and the
	// client-thread apply; a refresh in that window must not start it over.
	private volatile boolean lootImportRunning;

	// Keys the upward push withholds: the server re-derives all three at read time and
	// sending them as counters double-presents them. Only resourcesGatheredValue is
	// still written by this build; the untakenLoot pair reaches us only in a journal
	// imported from an older record.
	private static final java.util.Set<String> PUSH_EXCLUDE = new java.util.HashSet<>(
		java.util.Arrays.asList("untakenLootValue", "untakenLootCount", "resourcesGatheredValue"));

	// The wiki drop-rate book behind the dryness ledger.
	private GrindBook grindBook;

	// Panel-facing status.
	private volatile String syncedRsn;
	// The logged-in name: the journal's identity, and the push name when cloud is on.
	private volatile String localName;
	// Null when both plugins we lean on are enabled, else a short label for the
	// heartbeat and the sentence that explains it. This is the only status the
	// panel has ever shown: the eight-writer statusLine it replaces was assigned
	// everywhere and rendered nowhere, so its cloud messages went to nobody.
	private volatile String captureWarning;
	private volatile String captureWarningWhy;

	@Provides
	ChronicleConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChronicleConfig.class);
	}

	@Override
	protected void startUp()
	{
		// Built here so field injection has already supplied the client's Gson.
		historyLog = new HistoryLog(gson);
		grindBook = new GrindBook(gson);
		panel = new ChroniclePanel(this);
		navButton = NavigationButton.builder()
			.tooltip("Chronicle")
			.icon(buildIcon())
			.priority(9)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		refreshPanel();

		eventCapture.reset();
		eventBus.register(eventCapture);
		// Toggling the plugin fires no GameStateChanged. Nothing else clears the trackers'
		// inference state (inventory snapshots, in-flight clicks).
		counters.reset();
		// Consumables are priced as they're used and folded straight into the journal.
		counters.setConsumableSink((key, gp) ->
		{
			String who = localName;
			if (who != null)
			{
				localStore.addConsumableValue(key, gp, who);
			}
		});
		// Gathers are noted in the journal and read back later, so an ore mined a month
		// ago still counts as gathered when it's finally binned.
		counters.setGatheredLedger(localStore);
		eventBus.register(counters);
		// Clog capture: the completion fraction comes off the login varps. When the player
		// opens the log themselves, the capture fires the log's own Search op to make the
		// server transmit every page in one go. It never opens the log itself.
		clogCapture.reset();
		eventBus.register(clogCapture);
		// Empty change gate: the first push after a restart sends everything.
		achievementSync.reset();

		reschedulePushLoop();
		checkDependencies();
		log.debug("Chronicle started - slayer service: {}",
			eventCapture.hasSlayerService() ? "AVAILABLE" : "MISSING");

		// If the plugin is toggled on mid-session, catch the already-logged-in case.
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			pendingLoginSetup = true;
			// No LOGGED_IN transition will arrive; arm the teardown flag by hand.
			wasLoggedIn = true;
			// The clog fraction varps arrived with a LOGGED_IN we missed; read them now.
			clientThread.invoke(() -> clogCapture.primeFromVarps(client));
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
		// The panel's repeating timers keep it (and us) alive; left running, every
		// plugin toggle leaks another detached panel rebuilding itself forever.
		final ChroniclePanel dying = panel;
		if (dying != null)
		{
			javax.swing.SwingUtilities.invokeLater(dying::shutdown);
		}
		panel = null;
		pendingLoginSetup = false;
		// Bank the session on the way out, which here means a plugin toggle and not
		// much else: closing the client does NOT reach this method. ClientUI's own
		// shutdown posts ClientShutdown, waits for its consumers and calls
		// System.exit without stopping a single plugin, and nothing registers a JVM
		// shutdown hook. So a player who closes the client loses whatever the last
		// fold missed, bounded by the write interval rather than by nothing.
		if (localName != null && localStore.isReadyFor(localName))
		{
			localStore.setCharacter(localName, null, 0, clogCapture.snapshot(), null);
			localStore.setTrackers(sessionView(), localName);
			localStore.rebase(localName);
			// The toggle path is the EDT one, where the flush (an fsync and a move)
			// has to leave the thread. The other arm is defence against a caller that
			// does not exist today rather than one that does.
			if (javax.swing.SwingUtilities.isEventDispatchThread())
			{
				executor.submit(() -> localStore.flush(localDir()));
			}
			else
			{
				localStore.flush(localDir());
			}
		}
		// No events reach the trackers while we're unregistered, and the player may
		// switch accounts before toggling us back on. Drop the totals.
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

	// Both of the plugins we lean on can be switched off by the player: chest and casket
	// loot only reaches us through the core Loot Tracker, and on-task tagging needs the
	// Slayer plugin's service. The dependency guarantees they are loaded, not enabled.
	// Re-checked on the write beat, so turning one back on clears the warning without
	// a relog.
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
		if (!java.util.Objects.equals(was, now))
		{
			log.debug("capture warning: {}", now);
			refreshPanel();
		}
	}

	/**
	 * Switch back on whichever of the two plugins we capture through is off.
	 *
	 * <p>The same pair of calls RuneLite's own plugin list makes when its toggle
	 * is pressed; startPlugin arranges its own threading. Re-checked at once so
	 * the band that offered the click comes down on the same rebuild.
	 */
	void turnOnMissingCapture()
	{
		for (Class<? extends Plugin> type : java.util.Arrays.asList(
			SlayerPlugin.class, LootTrackerPlugin.class))
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
				catch (net.runelite.client.plugins.PluginInstantiationException e)
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

	// ------------------------------------------------------------------
	// Event handlers
	// ------------------------------------------------------------------

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
			// Any arrival at the login screen from an in-game session closes it,
			// CONNECTION_LOST included. A world hop never reaches the login screen.
			wasLoggedIn = false;
			onLogout();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		// Every board reads live, so every board is redrawn the moment the record
		// behind it moves. This is the whole of it: the panel already coalesces
		// asks within a tick, declines to draw while it is off screen, owes the
		// reader a draw when they come back, and puts the scroll bar back where
		// it was, so all that was missing was somebody telling it that something
		// had happened.
		//
		// Gated on a revision rather than a timer. Three stores count their own
		// writes, and a tick where nothing was written costs one comparison of
		// three longs; drawing regardless would redraw a still record fifty
		// thousand times an hour for nothing.
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			takeLiveSkills();
			watchPlaytime();
		}
		// WHICH store moved, not merely that one did. A board is redrawn when the
		// thing it shows has changed and left alone otherwise: an hour of
		// training moves the counters and the skills on almost every tick, and
		// redrawing the drops board for it costs twenty five milliseconds of
		// laying out and painting fifteen hundred components that say exactly
		// what they said before.
		int moved = 0;
		long r = localStore.revision();
		if (r != lastLootRevision)
		{
			lastLootRevision = r;
			moved |= ChroniclePanel.MOVED_RECORD;
		}
		r = statStore.revision();
		if (r != lastCounterRevision)
		{
			lastCounterRevision = r;
			moved |= ChroniclePanel.MOVED_COUNTERS;
		}
		r = clogCapture.revision();
		if (r != lastClogRevision)
		{
			lastClogRevision = r;
			moved |= ChroniclePanel.MOVED_CLOG;
		}
		if (skillRevision != lastSkillRevision)
		{
			lastSkillRevision = skillRevision;
			moved |= ChroniclePanel.MOVED_SKILLS;
		}
		if (moved != 0)
		{
			ChroniclePanel p = panel;
			if (p != null)
			{
				p.update(moved);
			}
		}
		if (!pendingLoginSetup)
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String name = localPlayerName();
		if (name == null)
		{
			return; // wait for the name to populate
		}
		pendingLoginSetup = false;
		// LOGGED_IN fires again on every world hop and region load for the same session;
		// reloading would re-freeze the lifetime base over totals it already holds.
		if (name.equals(localName) && localStore.isReadyFor(name))
		{
			refreshPanel();
			if (cloudActive())
			{
				adoptToken(name);
			}
			return;
		}
		// A new account for this session: mount its journal. Nothing here touches the network.
		localName = name;
		sessionStartMs = System.currentTimeMillis();
		loadPlaytime();
		refreshPanel();
		final String who = name;
		// A different name on the RSProfile pointer means an in-game rename: move the
		// journal and its spine to the new slug before loading so the record continues.
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
			// Same off-thread mount as the journal, so the History tab opens from memory.
			reloadHistory(who);
			// A different journal is mounted now; drop the panel views built on the last one.
			ChroniclePanel p = panel;
			if (p != null)
			{
				javax.swing.SwingUtilities.invokeLater(p::resetAccountCaches);
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
		// An action, not a setting: RuneLite's config has no button, so the tick
		// runs the thing and clears itself. Cleared FIRST, so a failure leaves the
		// box unticked rather than armed to fire again on the next change.
		if ("importJournal".equals(key) && "true".equals(e.getNewValue()))
		{
			configManager.setConfiguration(GROUP, "importJournal", false);
			final ChroniclePanel asking = panel;
			if (asking != null)
			{
				javax.swing.SwingUtilities.invokeLater(asking::promptImport);
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
				// A token is issued by one server and means nothing to another, so
				// repointing the URL drops it. A pasted one re-adopts on the next login.
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
				// A settings write arrives on whatever thread made it: the EDT, for the
				// settings panel. The stores below belong to the client thread.
				clientThread.invoke(() ->
				{
					// A cloud or server-URL change restarts the session: fold and re-freeze first.
					final String who = localName;
					if (who != null)
					{
						localStore.setTrackers(sessionView(), who);
						localStore.rebase(who);
						executor.submit(() -> localStore.flush(localDir()));
					}
					// The session restarts from zero; the rebase above already banked its increments.
					statStore.clear();
					counters.reset();
				});
			}
			// Turning cloud on re-runs the per-login branch so the token adopts now.
			if (cloudActive() && client.getGameState() == GameState.LOGGED_IN)
			{
				pendingLoginSetup = true;
			}
			refreshPanel();
		}
	}

	// ------------------------------------------------------------------
	// Cloud identity (upward only)
	// ------------------------------------------------------------------

	// Takes this account's push token: a pasted override, or whatever an earlier
	// install left on the RSProfile. No token means no cloud. Client thread.
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
		// Push straight away so a freshly-launched client isn't stale.
		pushCurrent();
	}

	// ------------------------------------------------------------------
	// Harvest + push
	// ------------------------------------------------------------------

	// Scheduled on the executor; hops to the client thread to read config.
	private void scheduledPush()
	{
		// The journal refreshes every cycle; the cloud push rides the same cadence.
		clientThread.invoke(this::refreshLocal);
		if (cloudActive())
		{
			clientThread.invoke(this::pushCurrent);
		}
	}

	// Client thread. Harvests the current counters and pushes them.
	private void pushCurrent()
	{
		// Nothing below may touch the network without a configured server.
		if (!cloudActive())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String name = localPlayerName();
		if (name == null)
		{
			return;
		}
		// The account hash lets the server follow a rename instead of reading it as an alt.
		api.setAccountHash(client.getAccountHash());
		String token = trimToNull(configManager.getRSProfileConfiguration(GROUP, KEY_TOKEN));
		if (token == null)
		{
			return;   // no token, no cloud
		}
		// Flush any clog pages viewed since the last push; the server merges partials.
		if (clogCapture.isDirty())
		{
			api.pushClog(config.serverBaseUrl(), token, name, clogCapture.snapshot());
			clogCapture.clearDirty();
		}
		// Quests, diaries and combat tasks: whole snapshot, sent only when it changed.
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
		// The journal is the record; the push mirrors its absolutes upward. Guarded on
		// the store's identity: a switch mid-load would push another player's lifetime.
		if (!name.equals(localName) || !localStore.isReadyFor(name))
		{
			return;
		}
		localStore.setTrackers(sessionView(), localName);
		Map<String, Integer> snapshot = journalAbsolutes(name);
		if (snapshot.isEmpty())
		{
			return;   // nothing journaled yet on this account
		}
		cachedToken = token;
		cachedName = name;
		cachedSnapshot = snapshot;
		cachedAccountType = accountTypeTag(client.getVarbitValue(VarbitID.IRONMAN));
		syncedRsn = name;

		log.debug("pushing {} counters for {}", snapshot.size(), name);
		api.pushStats(config.serverBaseUrl(), token, name, snapshot, cachedAccountType,
			harvestSkills(), this::onPushResult);
	}

	// The IRONMAN varbit's values as the server's account tags; empty for a normal account.
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

	// The journal's lifetime counters, clamped to the wire's int shape.
	private Map<String, Integer> journalAbsolutes(String rsn)
	{
		Map<String, Integer> out = new java.util.HashMap<>();
		// The previous account's model stays mounted until the next load lands. Unguarded,
		// this read hands its totals to another account's push.
		if (rsn == null || !localStore.isReadyFor(rsn))
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

	// Client thread (GameStateChanged). Best-effort final push.
	private void onLogout()
	{
		// Fold, write and end the session so a different account logging in next can't
		// record onto this model. Guarded in case the load never finished.
		if (localName != null && localStore.isReadyFor(localName))
		{
			// The collection log first: it is only folded in by the write interval
			// otherwise, so a player who opened their log and logged out inside that
			// window lost the whole capture -- the Kill Log included, which is the
			// one instruction a new install is given. setCharacter takes the log
			// alone; the player is already gone, so there are no skills to read.
			localStore.setCharacter(localName, null, 0, clogCapture.snapshot(), null);
			localStore.setTrackers(sessionView(), localName);
			appendHistoryBaseline();
			recordSessionLine();
			// Freeze the totals for the final push before the stores reset.
			Map<String, Integer> fresh = journalAbsolutes(localName);
			if (!fresh.isEmpty())
			{
				cachedSnapshot = fresh;
			}
		}
		executor.submit(() -> localStore.flush(localDir()));
		localStore.endSession();
		// Now that it is banked, and not before: one account's log must not accrete
		// onto the next one's.
		clogCapture.reset();
		eventCapture.resetSessionFlags();
		// Reset the gate so the next login syncs its own snapshot even if identical.
		achievementSync.reset();
		// These totals belong to the account that just left; the next must not inherit them.
		statStore.clear();
		// Take the push identity by value, then clear it: left standing it becomes the
		// identity of whoever logs in next. localName stays so the panel can browse.
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
			null, this::onPushResult);   // logout flush: client unreadable, skip skills
	}

	private void onPushResult(ChronicleApiClient.PushResult result)
	{
		if (result.ok)
		{
			log.debug("push ok: {} accepted, {} changed", result.accepted, result.changed);
		}
		else if (result.code == 409)
		{
			// The server holds higher totals than this journal, usually another computer.
			// The client stays authoritative for its own record; just surface it.
			log.debug("push 409: server ahead; journal stays authoritative");
		}
		else
		{
			log.debug("push failed code={} err={}", result.code, result.error);
		}
		refreshPanel();
	}

	// This session's increments, counted from zero by the trackers.
	Map<String, Integer> harvest()
	{
		return statStore.snapshotAll();
	}

	// Per-skill level and xp for the push, keyed by lowercase skill name, plus an
	// "overall" total. Client thread only, the skill accessors require it.
	private JsonObject harvestSkills()
	{
		JsonObject skills = new JsonObject();
		for (Skill s : Skill.values())
		{
			if (s == Skill.OVERALL)
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("level", client.getRealSkillLevel(s));
			o.addProperty("xp", client.getSkillExperience(s));
			// ROOT locale: a Turkish JVM lowercases MINING to "mınıng".
			skills.add(s.name().toLowerCase(java.util.Locale.ROOT), o);
		}
		JsonObject overall = new JsonObject();
		overall.addProperty("level", client.getTotalLevel());
		overall.addProperty("xp", client.getOverallExperience());
		skills.add("overall", overall);
		return skills;
	}

	// ------------------------------------------------------------------
	// Panel-invoked actions (may be called off the client thread)
	// ------------------------------------------------------------------

	void actionPushNow()
	{
		if (!cloudActive())
		{
			chat("Chronicle: enable cloud sync (and set a server) under Advanced in the plugin settings first.");
			return;
		}
		clientThread.invoke(this::pushCurrent);
	}

	// Cloud sync active: opted in and pointed at a server. Only the network is
	// gated; the journal runs whenever the plugin is on.
	boolean cloudActive()
	{
		return config.cloudSync() && !config.serverBaseUrl().trim().isEmpty();
	}

	// ── Panel-facing reads ─────────────────────────────────────────────

	// Lifetime counters as the journal knows them (base + session, floored).
	/**
	 * Live, not as last flushed. The journal's persisted trackers only move when
	 * the journal is written, so a board reading them sat still through an hour
	 * of play and then jumped on logout: the same arithmetic is done here against
	 * the counters as they stand this instant.
	 */
	Map<String, Long> lifetimeCounters()
	{
		return localStore.isReadyFor(localName)
			? localStore.lifetimeOf(sessionView())
			: localStore.trackersSnapshot();
	}

	// This session's own increments (max-type keys as absolutes).
	Map<String, Integer> sessionCounters()
	{
		return sessionView();
	}

	// ------------------------------------------------------------------
	// The game's own playtime
	// ------------------------------------------------------------------

	/**
	 * What the game says this account has played, in minutes.
	 *
	 * <p>Not a varp. The account summary panel is built by clientscript 3310,
	 * which reads VarClientInt 526 for its "Time Played:" row and hands it to
	 * clientscript 494 to phrase; 494 divides by 60 for hours and by 24 again
	 * for days, so the unit is the minute. The only writer anywhere in the cache
	 * is clientscript 3970, which the server invokes.
	 *
	 * <p>This replaced varp 4523, which RuneLite names TRACKING_PLAYTIME_LEAGUES
	 * and which NOTHING in the game reads: the tracker panel beside it reads
	 * 4510 through 4527 for bosses killed, coins gained, fish caught and the
	 * rest, and for its playtime row it emits a literal dash and defers to the
	 * hide/reveal toggle. 4523 is a dead id, so it read zero forever and the
	 * panel quietly fell back to the time Chronicle had watched, which is a
	 * different and much smaller number.
	 */
	private static final String KEY_PLAYTIME = "gamePlaytime";
	private static final String KEY_PLAYTIME_AT = "gamePlaytimeAt";

	private volatile long playtimeMinutes;
	private volatile long playtimeAt;
	private boolean playtimeLogged;

	/**
	 * The game's own figure, carried forward to now.
	 *
	 * <p>What the client holds is a SNAPSHOT: the server pushes it when it builds
	 * the account summary, and it does not tick. Left as it landed it would be
	 * right when the panel was opened and progressively behind Hans afterwards,
	 * so the time played since the snapshot is added to it. Only time actually
	 * spent logged in counts, which is why it is measured against the sitting
	 * and not against the wall clock.
	 */
	long gamePlaytimeMinutes()
	{
		return carriedForward(playtimeMinutes, playtimeAt, System.currentTimeMillis(),
			sessionStartMs, sessionElapsedMinutes());
	}

	/**
	 * The rule on its own, so it can be held to without a client to ask.
	 *
	 * <p>A snapshot taken during THIS sitting is carried forward by the wall
	 * clock since it was taken, all of which was spent logged in. A snapshot
	 * from an earlier sitting is carried forward by the whole of this one: the
	 * hours between two sittings are not playtime and adding them would put the
	 * figure ahead of Hans by however long the client was shut.
	 */
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

	// Client thread, once a tick.
	private void watchPlaytime()
	{
		long raw = client.getVarcIntValue(
			net.runelite.api.gameval.VarClientID.ACCOUNT_SUMMARY_PLAYTIME);
		// Zero is not an answer. The varc is empty until the server has pushed
		// it, which it does when the account summary is built, so on most logins
		// it stays empty until the player opens that panel. Taking the zero would
		// throw away the figure already on disk from the last time they did.
		if (raw <= 0 || raw == playtimeMinutes)
		{
			return;
		}
		playtimeMinutes = raw;
		playtimeAt = System.currentTimeMillis();
		configManager.setRSProfileConfiguration(GROUP, KEY_PLAYTIME, String.valueOf(raw));
		configManager.setRSProfileConfiguration(GROUP, KEY_PLAYTIME_AT,
			String.valueOf(playtimeAt));
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
		String was = configManager.getRSProfileConfiguration(GROUP, key);
		if (was == null || was.isEmpty())
		{
			return 0;
		}
		try
		{
			return Long.parseLong(was);
		}
		catch (NumberFormatException ignored)
		{
			return 0;   // a figure that cannot be read is one to be told again
		}
	}

	/** When this sitting began, or 0 before a login. */
	long sessionStart()
	{
		return sessionStartMs;
	}

	/**
	 * Minutes this sitting has run, or 0 when there is no sitting in progress.
	 *
	 * <p>A sitting reaches the journal as one dated line when it CLOSES, so
	 * until then the time played figure is every sitting but the one the reader
	 * is having. On a long evening that is the difference between the number on
	 * screen and the number they would recognise.
	 *
	 * <p>Zero once logged out, which is exactly when the closing line exists to
	 * be counted instead, so the two can never both be in the sum.
	 */
	long sessionElapsedMinutes()
	{
		if (sessionStartMs <= 0 || client == null
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return 0;
		}
		return Math.max(0, (System.currentTimeMillis() - sessionStartMs) / 60_000L);
	}

	// This session's xp split by skill, biggest first, each with its own rate. Held in
	// memory by the experience tracker alone: it never enters the journal or the push.
	java.util.List<chronicle.counters.ExperienceStatTracker.SkillGain> sessionSkillXp()
	{
		// Guarded for the panel's test doubles, which stand in for the plugin without
		// Guice ever filling this field.
		chronicle.counters.ChronicleCounters c = counters;
		return c == null ? java.util.Collections.emptyList() : c.sessionSkillXp();
	}

	java.util.List<LocalStore.SourceRow> dropSources()
	{
		return localStore.dropSources();
	}

	/** The first day the dated loot roll holds, epoch ms, or 0 for none. */
	long lootRollFrom()
	{
		return localStore.lootRollFrom();
	}

	/** The dated loot roll over a window, both days included. */
	LocalStore.LootWindow lootBetween(java.time.LocalDate from, java.time.LocalDate to)
	{
		return localStore.lootBetween(from, to);
	}

	java.util.List<LocalStore.BagItem> sourceItems(String source)
	{
		return localStore.sourceItems(source);
	}

	// The journal's task-by-task slayer journey; the panel fetches on first open.
	void fetchSlayerJourney(
		java.util.function.Consumer<ChronicleApiClient.SlayerJourney> onDone)
	{
		final String rsn = localName;
		if (rsn == null || !localStore.isReadyFor(rsn))
		{
			onDone.accept(null);
			return;
		}
		executor.submit(() -> onDone.accept(localStore.slayerJourney()));
	}

	// The same journey read on the calling thread, for the History tab's gather
	// worker; null while no store is mounted. Never call this on the EDT.
	ChronicleApiClient.SlayerJourney slayerJourney()
	{
		final String rsn = localName;
		if (rsn == null || !localStore.isReadyFor(rsn))
		{
			return null;
		}
		return localStore.slayerJourney();
	}

	/** The feed as the journal holds it, newest first. */
	java.util.List<JsonObject> feedNewest(int n)
	{
		return localStore.feedNewest(n);
	}

	/**
	 * The feed with the sitting in progress at the head of it.
	 *
	 * <p>Everything else already reaches the feed as it happens - a level, a
	 * pet, a log slot, a drop. The sitting was the one thing that did not, and
	 * it is the line saying what the reader has been doing for the last hour.
	 *
	 * <p>Kept apart from feedNewest because the live line's stamp is the current
	 * moment, and one caller uses the newest stamp to decide whether the feed has
	 * grown: handed a line that is newer every time it looks, it would re-read
	 * the whole history on every draw for ever.
	 */
	java.util.List<JsonObject> feedWithSitting(int n)
	{
		java.util.List<JsonObject> kept = localStore.feedNewest(n);
		JsonObject live = liveSessionLine();
		if (live == null)
		{
			return kept;
		}
		java.util.List<JsonObject> out = new java.util.ArrayList<>(kept.size() + 1);
		out.add(live);
		out.addAll(kept);
		return out;
	}

	/** What this sitting has taken and left, ranked, in the roll's own shape. */
	LocalStore.LootWindow sessionLootWindow()
	{
		return localStore.sessionLootWindow();
	}

	/** What one source paid this sitting. */
	java.util.List<LocalStore.BagItem> sessionSourceItems(String source)
	{
		return localStore.sessionSourceItems(source);
	}

	int sessionLoots()
	{
		return localStore.sessionLoots();
	}

	long sessionLootValue()
	{
		return localStore.sessionLootValue();
	}

	java.util.List<LocalStore.RecentDrop> recentDrops()
	{
		return localStore.recentDrops();
	}

	ChronicleEventCapture.SlayerView slayerView()
	{
		return eventCapture.slayerView();
	}

	// The spine as last parsed. The panel's rebuild calls this on the EDT: never read
	// disk here. A cold cache asks the executor and the panel rebuilds when it lands.
	java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> historyBaselines()
	{
		String rsn = localName;
		if (rsn == null)
		{
			return new java.util.TreeMap<>();
		}
		java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> cached = historyCache;
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
		return new java.util.TreeMap<>();
	}

	// Executor only: the spine read the EDT must not do, published to the panel.
	private void reloadHistory(String rsn)
	{
		// Older builds appended at login, rollover and logout alike, so a long-running
		// account carries several lines for the same day. Fold them before reading.
		historyLog.compact(localDir(), rsn);
		java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> read =
			historyLog.read(localDir(), rsn);
		// An account switch can overtake the read; don't hand the panel the previous
		// player's calendar under the current player's name.
		if (rsn.equals(localName))
		{
			historyCache = read;
			historyCacheRsn = rsn;
			refreshPanel();
		}
	}

	// The earliest date the record knows about, which the Loot Tracker inheritance
	// often puts years before the file. Epoch millis, 0 when nothing is dated.
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

	/**
	 * Quests, diaries and combat achievements as the journal holds them.
	 *
	 * <p>Kept current on the character-sheet beat and already read by the chase
	 * book; the panel had no way to reach it at all until the sheet's activity
	 * tiles needed it.
	 */
	JsonObject achievements()
	{
		// The preview harness builds a panel with no store behind it, and the sheet
		// asks for this on every build.
		return localStore == null ? new JsonObject() : localStore.achievements();
	}

	int combatLevel()
	{
		return localStore.combatLevel();
	}

	// Skill sprites for the History grid.
	net.runelite.client.game.SkillIconManager skillIcons()
	{
		return skillIcons;
	}

	// The game's own sprites, for the History tab's facet strip. Null in a dev
	// client with no cache, which the panel falls back from.
	net.runelite.client.game.SpriteManager sprites()
	{
		return sprites;
	}

	// Everything counted, keyed as the History spine stores it: the collection
	// log's bosses and activities by kill count, raised by every drop-ledger
	// source at the most any record has seen of it (LocalStore.sourceKills: its
	// kill-count line, its loot events, the log's count for the page of the same
	// name), under the log's spelling where the two name one thing. A paged
	// source's figure already holds the page's count, so it stands in for it
	// outright, and a page nothing else has counted stands at the log's count.
	// But a page counter need not be counting kills at all, so what the game
	// itself has stated -- the Kill Log, or the chat line it prints on the kill
	// -- goes on first and the ledger floors that back up; a name only the chat
	// box has ever counted comes in under its own. The order below is the whole
	// of it.
	Map<String, Long> killCounts()
	{
		return LocalStore.reconciledKills(localStore.clogSnapshot(),
			localStore.dropSources(), localStore.chatKillCounts(),
			localStore.anchoredKills());
	}

	// The ledger's sources the collection log has no page for, at the figures
	// killCounts carries for them. The panel sorts its own boards by kind now and
	// no longer asks; this stays as the one place that answers "which of these
	// did the log never hear of", which is a question about the record itself.
	Map<String, Long> ledgerKills()
	{
		JsonObject cl = localStore.clogSnapshot();
		java.util.Set<String> paged = LocalStore.clogKillCounts(cl).keySet();
		Map<String, Long> out = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, Long> e
			: LocalStore.sourceKills(cl, localStore.dropSources()).entrySet())
		{
			if (!paged.contains(e.getKey()))
			{
				out.put(e.getKey(), e.getValue());
			}
		}
		return out;
	}

	// Level + xp per skill, as the journal last saw them.
	/**
	 * The skill sheet as it stands, not as last written. Falls back to the
	 * journal's copy before the first tick of a session, and while logged out.
	 */
	java.util.Map<String, long[]> skillSheet()
	{
		java.util.Map<String, long[]> now = liveSkills;
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

	/** Every item the slayer journey logged inside a window, ranked by value. */
	java.util.List<LocalStore.BagItem> onTaskLoot(long fromMs, long toMs, boolean includeOpen)
	{
		return localStore.onTaskLoot(fromMs, toMs, includeOpen);
	}

	java.util.List<LocalStore.BagItem> onTaskLoot(long fromMs, long toMs, String task,
		boolean includeOpen)
	{
		return localStore.onTaskLoot(fromMs, toMs, task, includeOpen);
	}

	java.util.List<LocalStore.BagItem> allLoot()
	{
		return localStore.allLoot();
	}

	/** Every task name the journey holds, newest first, without repeats. */
	java.util.List<String> taskNames()
	{
		return localStore.taskNames();
	}

	/** The kills behind that loot, and how many of them were a superior. */
	long[] onTaskTally(long fromMs, long toMs, boolean includeOpen)
	{
		return localStore.onTaskTally(fromMs, toMs, includeOpen);
	}

	long[] onTaskTally(long fromMs, long toMs, String onlyTask, boolean includeOpen)
	{
		return localStore.onTaskTally(fromMs, toMs, onlyTask, includeOpen);
	}

	java.util.Map<String, Long> journalFacts()
	{
		return localStore.journalFacts();
	}

	java.util.Map<String, Long> chatKills()
	{
		return localStore.chatKillCounts();
	}

	java.util.Map<String, Long> anchoredKills()
	{
		return localStore.anchoredKills();
	}

	java.util.Map<String, long[]> onTaskItems(long fromMs, long toMs)
	{
		return localStore.onTaskItems(fromMs, toMs);
	}

	java.util.Map<String, Long> onTaskKills(long fromMs, long toMs)
	{
		return localStore.onTaskKills(fromMs, toMs);
	}

	java.util.List<Object[]> onTaskItemByTask(String itemName, long fromMs, long toMs)
	{
		return localStore.onTaskItemByTask(itemName, fromMs, toMs);
	}

	java.util.List<LocalStore.Assignment> onTaskAssignments(String npc, long fromMs, long toMs)
	{
		return localStore.onTaskAssignments(npc, fromMs, toMs);
	}

	java.util.List<LocalStore.BagItem> untakenItemsOf(String source)
	{
		return localStore.untakenItemsOf(source);
	}

	java.util.List<LocalStore.UntakenRow> untakenSourcesOf(String item)
	{
		return localStore.untakenSourcesOf(item);
	}

	java.util.List<LocalStore.BagItem> slayerTaskItems(int index)
	{
		return localStore.slayerTaskItems(index);
	}

	java.util.List<LocalStore.UntakenRow> slayerTaskMonsters(int index)
	{
		return localStore.slayerTaskMonsters(index);
	}

	java.util.List<LocalStore.PetRow> pets()
	{
		return localStore.pets();
	}

	// The pace of a skill, measured over the days it actually moved.
	PaceBook.Pace pace(String skill)
	{
		java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> spine = historyBaselines();
		long xp = 0;
		try
		{
			xp = client.getSkillExperience(Skill.valueOf(skill.toUpperCase(java.util.Locale.ROOT)));
		}
		catch (RuntimeException ignored)
		{
			// not a real skill name, or the client is unreadable. No pace.
		}
		// The spine files skills lowercase (harvestSkills writes them that way); the panel
		// asks with its own capitalisation. Normalise, or nothing ever matches.
		return PaceBook.forSkill(spine, skill.toLowerCase(java.util.Locale.ROOT), xp);
	}

	java.util.List<LocalStore.UntakenRow> untakenSources()
	{
		return localStore.untakenSources();
	}

	java.util.List<LocalStore.UntakenRow> untakenItems()
	{
		return localStore.untakenItems();
	}

	java.util.Map<String, Long> consumableValues()
	{
		return localStore.consumableValues();
	}

	// Dryness, computed from the journal's own collection log and kill counts against
	// the bundled wiki rate book. The panel fetches once per session.
	void fetchGrinds(java.util.function.Consumer<java.util.List<ChronicleApiClient.GrindRow>> onDone)
	{
		final String rsn = localName;
		if (rsn == null || !localStore.isReadyFor(rsn))
		{
			onDone.accept(null);
			return;
		}
		final JsonObject clog = localStore.clogSnapshot();
		final java.util.List<LocalStore.SourceRow> sources = localStore.dropSources();
		executor.submit(() -> onDone.accept(grindBook.grinds(clog, sources)));
	}

	// The chase behind each unearned pet a log page lists, keyed by lower-cased name.
	// Cheap enough for the panel to ask on the spot: both rate books are parsed once
	// and held, and the reads are ones the log page already makes. Skilling pets need
	// two more: the lifetime counters their attempts are in, and the skill sheet the
	// grid reads its levels off. And the achievements the sheet has always carried,
	// which say whether a pet behind an unlock is a chase at all yet.
	java.util.Map<String, GrindBook.PetChase> petChases(java.util.Collection<String> pets)
	{
		final String rsn = localName;
		if (rsn == null || !localStore.isReadyFor(rsn))
		{
			return java.util.Collections.emptyMap();
		}
		return grindBook.petChases(localStore.clogSnapshot(), localStore.dropSources(),
			localStore.trackersSnapshot(), localStore.skillSheet(),
			localStore.achievements(), pets);
	}

	// True once a kill landed while a slayer task was live this session,
	// on-task or not.
	boolean slayerSeenThisSession()
	{
		return eventCapture.slayerSeenThisSession();
	}

	private volatile long sessionStartMs;

	// The logout diary line: one dated feed entry closing the session. Local only,
	// skipped when the session was too slight to be worth a line.
	private void recordSessionLine()
	{
		// Wall-clock: an NTP correction or a resumed VM can put the start ahead of now.
		// Floored at zero, in place of a line of negative minutes.
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

	/**
	 * What a sitting amounted to, in the shape the feed keeps it.
	 *
	 * <p>What the sitting left behind rides beside what it took, so the three
	 * figures of a take - received, kept and left - come from one dated record.
	 * The journal keeps only lifetime totals for the floor, and a period cannot
	 * be told from those.
	 */
	private JsonObject sessionData(long mins, long xp, int drops, long dropsGp)
	{
		long[] left = localStore.sessionUntakenTally();
		JsonObject data = new JsonObject();
		data.addProperty("minutes", mins);
		data.addProperty("xp", xp);
		data.addProperty("drops", drops);
		data.addProperty("dropsGp", dropsGp);
		data.addProperty("left", left[0]);
		data.addProperty("leftGp", left[1]);
		data.addProperty("leftKills", localStore.sessionUntakenKills());
		return data;
	}

	/**
	 * The sitting in progress, as the feed line it will become.
	 *
	 * <p>A sitting reaches the journal as one dated line when it CLOSES. Until
	 * then the journal showed every sitting the reader had ever had except the
	 * one they were in the middle of, which is the one they can see happening.
	 * This is that line, built from the same figures and not written down: it
	 * changes as the sitting does, and the moment the sitting closes the real
	 * line takes its place. Null while logged out, which is exactly when the
	 * real line exists, so the two are never both in the list.
	 */
	JsonObject liveSessionLine()
	{
		if (client == null || localStore == null)
		{
			return null;
		}
		long mins = sessionElapsedMinutes();
		if (sessionStartMs <= 0 || client.getGameState() != GameState.LOGGED_IN
			|| localName == null || !localStore.isReadyFor(localName))
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

	// Session counters shaped for display: a peak key survives only when this session
	// beat the journal's lifetime record. Otherwise an old peak reads as a session feat.
	Map<String, Integer> sessionDisplayCounters()
	{
		Map<String, Integer> out = new java.util.HashMap<>(sessionView());
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

	// The client's Gson, shared with the panel.
	com.google.gson.Gson gson()
	{
		return gson;
	}

	net.runelite.client.game.ItemManager items()
	{
		return localStore.items();
	}

	// Name to show in the panel: the synced name when cloud is on, else the local one.
	String displayRsn()
	{
		return cloudActive() && syncedRsn != null && !syncedRsn.isEmpty() ? syncedRsn : localName;
	}

	// This session's increments, straight from the trackers. Max-type keys pass
	// through as absolutes (the journal takes their max); the rest drops noise.
	Map<String, Integer> sessionView()
	{
		Map<String, Integer> abs = harvest();
		Map<String, Integer> out = new java.util.HashMap<>(abs.size());
		for (Map.Entry<String, Integer> en : abs.entrySet())
		{
			if (LocalStore.MAX_KEYS.contains(en.getKey()) || en.getValue() > 0)
			{
				out.put(en.getKey(), en.getValue());
			}
		}
		return out;
	}

	// Cached once per client run; every flush, load and history append asks for it.
	private static volatile File journalDir;

	private static File localDir()
	{
		File cached = journalDir;
		if (cached != null)
		{
			return cached;
		}
		File dir = new File(net.runelite.client.RuneLite.RUNELITE_DIR, "chronicle");
		journalDir = dir;
		return dir;
	}

	// Client thread. Copies the current character sheet into the journal.
	private void gatherCharacter()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String name = localPlayerName();
		if (name == null)
		{
			return;
		}
		Player lp = client.getLocalPlayer();
		localStore.setCharacter(name, harvestSkills(), lp != null ? lp.getCombatLevel() : 0,
			clogCapture.snapshot(), achievementSync.snapshot());
		// The journal's additive base does the lifetime arithmetic from the session view.
		localStore.setTrackers(sessionView(), name);
	}

	private void refreshLocal()
	{
		gatherCharacter();
		// Only with the journal mounted: a baseline's counters come from it. A day closed
		// mid-load appends a line of zeroes, and every later subtraction reads that as a collapse.
		if (localName != null && localStore.isReadyFor(localName))
		{
			localStore.setTrackers(sessionView(), localName);
			// One closing baseline per day; the History tab and year cards subtract over it.
			checkDependencies();
			if (historyLog.dayRolledOver(localName))
			{
				appendHistoryBaseline();
			}
		}
		// First run per account: adopt the core Loot Tracker's archive so a late install
		// starts years deep. Own-account by the RSProfile keys, and the floors are idempotent.
		if (localName != null && localStore.isReadyFor(localName)
			&& !"true".equals(configManager.getRSProfileConfiguration(GROUP, "lootTrackerImported")))
		{
			importLootTracker();
		}
		executor.submit(() -> localStore.flush(localDir()));
	}

	/** One Loot Tracker source as parsed off the client thread: raw ids and quantities. */
	private static final class RawSource
	{
		final String source;
		final int kills;
		final long firstMs;
		final long lastMs;
		// {item id, quantity} pairs, in the order the tracker stored them.
		final java.util.List<long[]> items = new java.util.ArrayList<>();

		RawSource(String source, int kills, long firstMs, long lastMs)
		{
			this.source = source;
			this.kills = kills;
			this.firstMs = firstMs;
			this.lastMs = lastMs;
		}
	}

	// Client thread. The config scan and JSON parse read no game state and are the
	// bulk of the work, so they go to the executor; inline they stalled the login.
	private void importLootTracker()
	{
		// The RSProfile flag is only written once the adoption lands. Without this, the next
		// refresh starts a second read over the same archive.
		if (lootImportRunning)
		{
			return;
		}
		lootImportRunning = true;
		final String who = localName;
		// Read on the account being imported for, so a switch mid-read can't cross accounts.
		final String profileKey = configManager.getRSProfileKey();
		executor.submit(() ->
		{
			final java.util.List<RawSource> parsed;
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

	/**
	 * Whether a Loot Tracker archive key holds a kind of loot this plugin keeps.
	 *
	 * <p>An allow list rather than a block list, so a record type added to the
	 * core plugin later is ignored until somebody decides it belongs here, rather
	 * than inherited because nobody thought to name it.
	 */
	private static boolean wantedLootType(String key)
	{
		if (key == null)
		{
			return false;
		}
		for (net.runelite.http.api.loottracker.LootRecordType t
			: net.runelite.http.api.loottracker.LootRecordType.values())
		{
			if (t == net.runelite.http.api.loottracker.LootRecordType.PLAYER)
			{
				continue;
			}
			if (key.startsWith("drops_" + t.name() + "_"))
			{
				return true;
			}
		}
		return false;
	}

	// Executor: the config-archive scan and its JSON parse, no game state.
	private java.util.List<RawSource> readLootTrackerArchive(String profileKey)
	{
		java.util.List<RawSource> out = new java.util.ArrayList<>();
		java.util.List<String> keys;
		try
		{
			keys = configManager.getRSProfileConfigurationKeys(
				"loottracker", profileKey, "drops_");
		}
		catch (RuntimeException e)
		{
			log.debug("loot tracker key scan failed", e);
			return out;
		}
		if (keys == null)
		{
			return out;
		}
		for (String key : keys)
		{
			// The archive holds the core plugin's PvP records too, keyed
			// drops_PLAYER_<the victim's display name> and carrying their
			// inventory. Adopting one would put another player's name and their
			// items into this journal and onto the Loot board, under the account
			// that killed them. The live path already refuses these outright,
			// saying that this plugin only ever records its own account; the
			// inherited path has to refuse them on the same terms.
			if (!wantedLootType(key))
			{
				continue;
			}
			String raw = configManager.getConfiguration("loottracker", profileKey, key);
			if (raw == null || raw.isEmpty())
			{
				continue;
			}
			try
			{
				JsonObject o = gson.fromJson(raw, JsonObject.class);
				// Belt and braces: the record names its own type, so a key spelled
				// some other way than expected is still caught.
				if (o.has("type") && "PLAYER".equalsIgnoreCase(
					String.valueOf(o.get("type").getAsString())))
				{
					continue;
				}
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
					com.google.gson.JsonArray arr = o.getAsJsonArray("drops");
					for (int i = 0; i + 1 < arr.size(); i += 2)
					{
						long id = arr.get(i).getAsLong();
						long qty = arr.get(i + 1).getAsLong();
						if (id <= 0 || qty <= 0)
						{
							continue;
						}
						src.items.add(new long[]{id, qty});
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

	// Client thread, for the ItemManager naming and pricing. Flag set on success.
	private void adoptLootTrackerArchive(String rsn, java.util.List<RawSource> parsed)
	{
		try
		{
			// A switch can land mid-read; don't floor one account's archive into another's.
			if (rsn == null || !rsn.equals(localName) || !localStore.isReadyFor(rsn))
			{
				return;
			}
			java.util.List<LocalStore.LootSeed> seeds = new java.util.ArrayList<>(parsed.size());
			long events = 0;
			for (RawSource src : parsed)
			{
				java.util.List<LocalStore.BagItem> items =
					new java.util.ArrayList<>(src.items.size());
				for (long[] drop : src.items)
				{
					int canon = localStore.items().canonicalize((int) drop[0]);
					String name;
					try
					{
						name = localStore.items().getItemComposition(canon).getName();
					}
					catch (Exception e)   // an id this client can't compose
					{
						// Name it by number. Thrown, it escapes the loop with the imported flag
						// unwritten, and every later refresh starts the whole archive again.
						name = "Item " + canon;
					}
					long each = localStore.items().getItemPrice(canon);
					items.add(new LocalStore.BagItem(canon, name, drop[1],
						Math.max(0, each) * drop[1]));
				}
				seeds.add(new LocalStore.LootSeed(src.source, src.kills, src.firstMs,
					src.lastMs, items));
				events += src.kills;
			}
			if (!seeds.isEmpty())
			{
				localStore.floorLootTracker(seeds, rsn);
				chat("Chronicle: adopted " + seeds.size() + " sources · "
					+ String.format(java.util.Locale.UK, "%,d", events)
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

	// Client thread. Appends today's closing skills+counters baseline.
	private void appendHistoryBaseline()
	{
		final String rsn = localName;
		if (rsn == null)
		{
			return;
		}
		// Long: the "overall" entry is total xp, past Integer.MAX_VALUE well before a
		// maxed account, and it would wrap negative into a stream nothing rewrites.
		final Map<String, Long> skills = new java.util.HashMap<>();
		JsonObject sk = harvestSkills();
		if (sk != null)
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e : sk.entrySet())
			{
				if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("xp"))
				{
					skills.put(e.getKey(), e.getValue().getAsJsonObject().get("xp").getAsLong());
				}
			}
		}
		// A copy of the trackers with the journal's own totals (loot events, loot
		// left on the floor, kills, slayer tasks, clog slots) laid beside them: the
		// History summary reads those as period deltas. The trackers themselves
		// stay as they are.
		final Map<String, Long> counters = localStore.spineCounters();
		final Map<String, Long> kcs = killCounts();
		// Said in the log at the one moment a sitting can straddle a day. A
		// sitting crossing midnight was reported to lose its gains on the sheet
		// until the next logout, and nothing on this path or the panel's could
		// be made to do it; if it happens again, this line and the next tick's
		// sheet are the two things to read.
		long sittingXp = 0;
		for (chronicle.counters.ExperienceStatTracker.SkillGain g : sessionSkillXp())
		{
			sittingXp += g.xp;
		}
		log.debug("day rolled over: sitting began {} min ago, {} skills / {} xp counted so far",
			sessionElapsedMinutes(), sessionSkillXp().size(), sittingXp);
		executor.submit(() ->
		{
			historyLog.append(localDir(), rsn, skills, counters, kcs);
			// The panel reads the spine from memory, so the line just written has to reach
			// the cache or the closed day stays invisible until the next mount.
			reloadHistory(rsn);
		});
	}

	/** A short label for the heartbeat while a plugin we lean on is switched off. */
	String captureWarning()
	{
		return captureWarning;
	}

	/** The sentence behind that label, shown as the heartbeat's tooltip. */
	String captureWarningWhy()
	{
		return captureWarningWhy;
	}

	// Why the journal isn't keeping the record (a failed write, or a file a newer
	// build wrote), or null while it is.
	String journalWarning()
	{
		return localStore.journalWarning();
	}

	/**
	 * Merge a Chronicle journal file into this account's record. {@link
	 * LocalStore#importJournal} covers why the merge floors. A sibling
	 * {@code .history.jsonl} brings its calendar spine across too.
	 */
	void actionImport(File file)
	{
		final String rsn = localName;
		if (rsn == null || !localStore.isReadyFor(rsn))
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
				String txt = new String(java.nio.file.Files.readAllBytes(file.toPath()),
					java.nio.charset.StandardCharsets.UTF_8);
				com.google.gson.JsonElement el = gson.fromJson(txt, com.google.gson.JsonElement.class);
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
			// The spine travels beside the journal in its own file.
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

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	// Each store's revision as of the last draw asked for, kept apart so a draw
	// can be asked for only of the boards that show what moved.
	private long lastLootRevision;
	private long lastCounterRevision;
	private long lastClogRevision;
	private long lastSkillRevision;

	/**
	 * Every skill's level and experience as the CLIENT has them this tick.
	 *
	 * <p>The journal's own copy only moves when the journal is written, so a
	 * board reading it showed the same experience through an hour of training and
	 * jumped on logout. This is read off the client on the client thread, which
	 * is the only thread allowed to, and handed to the panel as a finished map.
	 */
	private volatile java.util.Map<String, long[]> liveSkills =
		java.util.Collections.emptyMap();

	private volatile long skillRevision;

	long skillRevision()
	{
		return skillRevision;
	}

	// Client thread only.
	private void takeLiveSkills()
	{
		java.util.Map<String, long[]> out = new java.util.LinkedHashMap<>();
		long overall = 0;
		for (Skill s : Skill.values())
		{
			if (s == Skill.OVERALL)
			{
				continue;
			}
			long xp = client.getSkillExperience(s);
			out.put(s.name().toLowerCase(java.util.Locale.ROOT),
				new long[]{client.getRealSkillLevel(s), xp});
			overall += xp;
		}
		out.put("overall", new long[]{client.getTotalLevel(), client.getOverallExperience()});
		java.util.Map<String, long[]> was = liveSkills;
		long[] wasOverall = was.get("overall");
		liveSkills = out;
		if (wasOverall == null || wasOverall[1] != client.getOverallExperience()
			|| was.size() != out.size())
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
		// addChatMessage must run on the client thread. invoke() runs inline when
		// already on it, and queues when called from an OkHttp callback thread.
		clientThread.invoke(() ->
		{
			try
			{
				client.addChatMessage(ChatMessageType.CONSOLE, "", message, null);
			}
			catch (Exception ignored)
			{
				// Chat unavailable (e.g. not logged in). The panel still shows status.
			}
		});
	}

	// The logged-in player's name, or null while it's still populating.
	private String localPlayerName()
	{
		Player lp = client.getLocalPlayer();
		String name = lp != null ? lp.getName() : null;
		return name == null || name.isEmpty() ? null : name;
	}

	private static String trimToNull(String s)
	{
		if (s == null)
		{
			return null;
		}
		s = s.trim();
		return s.isEmpty() ? null : s;
	}

	private static BufferedImage buildIcon()
	{
		// A small open book, distinct from the text-badge icons on the rail.
		BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x1E, 0x1B, 0x16));
		g.fillRoundRect(1, 1, 22, 22, 6, 6);
		Color gold = new Color(0xC8, 0xA2, 0x5A);
		g.setColor(gold);
		// two page leaves meeting at a spine
		g.fillPolygon(new int[]{4, 11, 11, 4}, new int[]{7, 5, 17, 19}, 4);
		g.fillPolygon(new int[]{20, 13, 13, 20}, new int[]{7, 5, 17, 19}, 4);
		g.setColor(new Color(0x1E, 0x1B, 0x16));
		// page lines
		g.drawLine(6, 9, 10, 8);
		g.drawLine(6, 12, 10, 11);
		g.drawLine(14, 8, 18, 9);
		g.drawLine(14, 11, 18, 12);
		g.setColor(gold);
		g.drawLine(12, 5, 12, 18);   // spine
		g.dispose();
		return img;
	}
}
