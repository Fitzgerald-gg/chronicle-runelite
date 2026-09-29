/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

@Singleton
public class ChronicleEventCapture
{
	private static final int MAX_LEVEL = 99;

	private static final Pattern KILL_COUNT = Pattern.compile(
		"^Your (?:completed )?(?<subject>.+?)"
			+ "(?: (?<kind>kill|chest|lap|harvest|success|completion))? count is: (?<tally>[\\d,]+)\\.$");

	private static final Set<String> NOT_A_KILL =
		new HashSet<>(Arrays.asList("lap", "harvest"));

	private static final Pattern COLLECTION_ITEM = Pattern.compile(
		"^New item added to your collection log: (?<entry>.+)$");

	private static final Pattern COMBAT_TASK = Pattern.compile(
		"^Congratulations, you've completed an? (?<grade>\\w+) combat task: (?<challenge>.+?)\\.?$");
	private static final Pattern COMBAT_TASK_POINTS = Pattern.compile("\\s*\\(\\d+ points?\\)$");

	private static final Pattern CLUE_COMPLETION = Pattern.compile(
		"^You have completed (?<tally>[\\d,]+) (?<rank>beginner|easy|medium|hard|elite|master)"
			+ " Treasure Trails?\\.$");

	private static final Pattern DIARY_COMPLETION = Pattern.compile(
		"Congratulations! You have completed all of the (?<grade>\\w+) tasks in the (?<region>.+?) area");

	private static final Pattern SLAYER_FINISHED = Pattern.compile(
		"^You have completed your task! You killed (?<slain>[\\d,]+) (?<creature>[^.]+)\\.");
	private static final Pattern SLAYER_TOTAL = Pattern.compile(
		"^You've completed (?:at least )?(?<total>[\\d,]+) (?<qual>[A-Za-z]+ )?tasks?"
			+ "(?:;| and received)");

	private static final Pattern PET_RECEIVED = Pattern.compile(
		"^(?:You have a funny feeling like you're being followed"
			+ "|You feel something weird sneaking into your backpack"
			+ "|You have a funny feeling like you would have been followed\\.\\.\\.)\\.?$");

	private static final Pattern UNTRADEABLE_DROP = Pattern.compile("^Untradeable drop: (?<dropped>.+)$");

	private static final Pattern KILL_DURATION = Pattern.compile(
		"(?i:Fight duration|Challenge duration|Corrupted challenge duration"
			+ "|Completion time|Subdued in|Duration):? (?<time>\\d+(?::\\d{2})+(?:\\.\\d{1,2})?)");
	private static final Pattern PERSONAL_BEST = Pattern.compile(
		"[Pp]ersonal best[:!]? (?<pb>\\d+(?::\\d{2})+(?:\\.\\d{1,2})?)");
	private static final String NEW_PB_MARK = "(new personal best)";

	private static final Pattern PICKPOCKET = Pattern.compile("You pick (the )?(?<target>.+)'s? pocket.*");

	private final Client client;
	private final ClientThread clientThread;
	private final CloudSync cloud;
	private final LocalStore localStore;
	private final LeftBehind left;

	@com.google.inject.Inject(optional = true)
	private SlayerPluginService slayerService;

	private final Map<Skill, Integer> knownLevels = new EnumMap<>(Skill.class);
	private final Set<Skill> pendingLevels = new HashSet<>();
	private final Map<String, Integer> recentKc = new HashMap<>();
	private static final int KILL_TIME_PAIR_TICKS = 4;
	private double lastKillTimeSec = -1;
	private double lastPbTimeSec = -1;
	private boolean lastKillPb;
	private int lastKillTimeTick = -1;
	private static final int ATTACKER_MEMORY_TICKS = 50;
	private String lastAttackerName;
	private int lastAttackerTick = -1;
	private int pickpocketTick = -1;

	private boolean groupStorageOpen;
	private Map<Integer, Integer> groupStorageBaseline;
	private Map<Integer, Integer> groupStorageCurrent;

	private int petPendingTicks = -1;
	private String pendingSlayerTask;
	private String pendingSlayerMonster;
	private Integer pendingSlayerKills;
	private int slayerPendingTicks = -1;
	private String lastSlayerTask;
	private String lastSlayerCompletionTask;
	private long lastSlayerCompletionAtMs = -1;
	private volatile boolean slayerSeenThisSession;

	private static final long SLAYER_FINAL_KILL_GRACE_MS = SlayerLog.SLAYER_FINAL_KILL_GRACE * 1000L;

	@Inject
	ChronicleEventCapture(Client client, ClientThread clientThread, CloudSync cloud, LocalStore localStore)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.cloud = cloud;
		this.localStore = localStore;
		this.left = new LeftBehind(client);
	}

	boolean hasSlayerService()
	{
		return slayerService != null;
	}

	void reset()
	{
		knownLevels.clear();
		pendingLevels.clear();
		recentKc.clear();
		lastKillTimeTick = -1;
		lastAttackerName = null;
		lastAttackerTick = -1;
		pickpocketTick = -1;
		groupStorageOpen = false;
		groupStorageBaseline = null;
		groupStorageCurrent = null;
		petPendingTicks = -1;
		pendingSlayerTask = null;
		pendingSlayerMonster = null;
		pendingSlayerKills = null;
		slayerPendingTicks = -1;
		lastSlayerTask = null;
		lastSlayerCompletionTask = null;
		lastSlayerCompletionAtMs = -1;
		left.reset();
	}

	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (client.getTickCount() == pickpocketTick)
		{
			return;
		}
		NPCComposition comp = event.getComposition();
		if (comp == null)
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("source", comp.getName());
		data.addProperty("npcId", comp.getId());
		data.addProperty("category", "NPC");
		data.addProperty("lootSource", "server");
		addLoot(data, comp.getName(), event.getItems());
		stampSlayer(data, comp.getName(), comp.getId());
		emit("LOOT", data);
		left.armKill(comp.getName());
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		left.onMenuOptionClicked(event);
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		left.onItemSpawned(event);
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		left.onItemDespawned(event);
	}

	private String currentTask()
	{
		try
		{
			String task = slayerService == null ? null : slayerService.getTask();
			return task == null || task.isEmpty() ? null : task;
		}
		catch (RuntimeException ignored)
		{
			return null;
		}
	}

	SlayerView slayerView()
	{
		String task = currentTask();
		return task == null ? null
			: new SlayerView(task, slayerService.getRemainingAmount(), slayerService.getInitialAmount());
	}

	@RequiredArgsConstructor
	static final class SlayerView
	{
		final String task;
		final int remaining;
		final int initial;
	}

	private void stampSlayer(JsonObject data, String npcName, int npcId)
	{
		if (slayerService == null)
		{
			return;
		}
		String task = currentTask();
		if (task == null)
		{
			String done = finishingKillTask();
			if (done != null && SlayerTaskBook.onTask(npcName, npcId, done))
			{
				data.addProperty("slayerTask", done);
			}
			return;
		}
		lastSlayerTask = task;
		slayerSeenThisSession = true;
		if (!SlayerTaskBook.onTask(npcName, npcId, task))
		{
			return;
		}
		data.addProperty("slayerTask", task);
		data.addProperty("slayerTaskRemaining", slayerService.getRemainingAmount());
		data.addProperty("slayerTaskInitial", slayerService.getInitialAmount());
		String loc = slayerService.getTaskLocation();
		if (loc != null && !loc.isEmpty())
		{
			data.addProperty("slayerTaskLocation", loc);
		}
	}

	private String finishingKillTask()
	{
		if (slayerPendingTicks >= 0 && pendingSlayerMonster != null && !pendingSlayerMonster.isEmpty())
		{
			return pendingSlayerMonster;
		}
		if (lastSlayerCompletionTask != null && lastSlayerCompletionAtMs >= 0
			&& System.currentTimeMillis() - lastSlayerCompletionAtMs <= SLAYER_FINAL_KILL_GRACE_MS)
		{
			return lastSlayerCompletionTask;
		}
		return null;
	}

	private void emitSlayerCompletion(String task, Integer count)
	{
		if (task != null)
		{
			JsonObject data = new JsonObject();
			data.addProperty("task", task);
			data.addProperty("monster", task);
			if (pendingSlayerKills != null)
			{
				data.addProperty("killCount", pendingSlayerKills);
			}
			if (count != null)
			{
				data.addProperty("count", count);
			}
			lastSlayerCompletionTask = task;
			lastSlayerCompletionAtMs = System.currentTimeMillis();
			emit("SLAYER", data);
		}
		pendingSlayerTask = null;
		pendingSlayerMonster = null;
		pendingSlayerKills = null;
		lastSlayerTask = null;
		slayerPendingTicks = -1;
	}

	boolean slayerSeenThisSession()
	{
		return slayerSeenThisSession;
	}

	void resetSessionFlags()
	{
		slayerSeenThisSession = false;
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (event.getType() == LootRecordType.NPC || event.getType() == LootRecordType.PLAYER)
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("source", event.getName());
		data.addProperty("category", event.getType() != null ? event.getType().name() : "EVENT");
		addLoot(data, event.getName(), event.getItems());
		emit("LOOT", data);
	}

	private void addLoot(JsonObject data, String source, Collection<ItemStack> items)
	{
		Integer kc = recentKc.get(cleanKey(source));
		if (kc != null)
		{
			data.addProperty("killCount", kc);
		}
		if (lastKillTimeTick >= 0 && client.getTickCount() - lastKillTimeTick <= KILL_TIME_PAIR_TICKS)
		{
			data.addProperty("killTime", lastKillTimeSec);
			data.addProperty("personalBest", lastKillPb);
			if (lastPbTimeSec >= 0)
			{
				data.addProperty("personalBestTime", lastPbTimeSec);
			}
			lastKillTimeTick = -1;
		}
		JsonArray arr = new JsonArray();
		if (items != null)
		{
			for (ItemStack is : items)
			{
				if (is != null)
				{
					JsonObject o = new JsonObject();
					o.addProperty("id", is.getId());
					o.addProperty("quantity", is.getQuantity());
					arr.add(o);
				}
			}
		}
		data.add("items", arr);
	}

	private static double parseDuration(String text)
	{
		double sec = 0;
		for (String part : text.split(":"))
		{
			sec = sec * 60 + Double.parseDouble(part);
		}
		return sec;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.SHARED_BANK)
		{
			groupStorageOpen = true;
			groupStorageBaseline = null;
			groupStorageCurrent = null;
			return;
		}
		if (event.getGroupId() == InterfaceID.QUESTSCROLL)
		{
			emitQuestFromScroll();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.SHARED_BANK && groupStorageOpen)
		{
			flushGroupStorage();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!groupStorageOpen || event.getContainerId() != InventoryID.INV_GROUP_TEMP)
		{
			return;
		}
		Map<Integer, Integer> counts = containerCounts(event.getItemContainer());
		if (groupStorageBaseline == null)
		{
			groupStorageBaseline = counts;
		}
		groupStorageCurrent = counts;
	}

	private void flushGroupStorage()
	{
		Map<Integer, Integer> base = groupStorageBaseline;
		Map<Integer, Integer> last = groupStorageCurrent;
		groupStorageOpen = false;
		groupStorageBaseline = null;
		groupStorageCurrent = null;
		if (base == null || last == null)
		{
			return;
		}
		JsonArray deposits = new JsonArray();
		JsonArray withdrawals = new JsonArray();
		Set<Integer> ids = new HashSet<>(base.keySet());
		ids.addAll(last.keySet());
		for (int id : ids)
		{
			int delta = last.getOrDefault(id, 0) - base.getOrDefault(id, 0);
			if (delta == 0)
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("quantity", Math.abs(delta));
			(delta > 0 ? deposits : withdrawals).add(o);
		}
		if (deposits.size() == 0 && withdrawals.size() == 0)
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.add("deposits", deposits);
		data.add("withdrawals", withdrawals);
		emit("GROUP_STORAGE", data);
	}

	private static Map<Integer, Integer> containerCounts(ItemContainer container)
	{
		Map<Integer, Integer> counts = new HashMap<>();
		if (container == null)
		{
			return counts;
		}
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
			{
				counts.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return counts;
	}

	private void emitQuestFromScroll()
	{
		clientThread.invokeLater(() ->
		{
			Widget title = client.getWidget(InterfaceID.Questscroll.QUEST_TITLE);
			if (title == null)
			{
				return;
			}
			String text = title.getText();
			text = text == null ? "" : Text.removeTags(text).trim();
			if (text.isEmpty())
			{
				return;
			}
			JsonObject data = new JsonObject();
			data.addProperty("questName", text);
			emit("QUEST", data);
		});
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (skill == null)
		{
			return;
		}
		int level = event.getLevel();
		Integer prev = knownLevels.put(skill, level);
		if (prev != null && prev > 0 && level > prev && level <= MAX_LEVEL)
		{
			pendingLevels.add(skill);
		}
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (!(event.getSource() instanceof NPC) || event.getTarget() == null
			|| event.getTarget() != client.getLocalPlayer())
		{
			return;
		}
		String name = ((NPC) event.getSource()).getName();
		if (name != null && !name.isEmpty())
		{
			lastAttackerName = name;
			lastAttackerTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (actor instanceof NPC)
		{
			left.rememberDeath((NPC) actor);
			return;
		}
		Player lp = client.getLocalPlayer();
		if (lp == null || actor != lp)
		{
			return;
		}
		JsonObject data = new JsonObject();
		try
		{
			data.addProperty("regionId", lp.getWorldLocation().getRegionID());
		}
		catch (RuntimeException ignored)
		{
		}
		String killer = findKillerNpc(lp);
		if (killer != null)
		{
			data.addProperty("killerName", killer);
		}
		emit("DEATH", data);
	}

	private String findKillerNpc(Player lp)
	{
		try
		{
			for (NPC npc : client.getTopLevelWorldView().npcs())
			{
				if (npc != null && npc.getInteracting() == lp && npc.getName() != null
					&& !npc.getName().isEmpty())
				{
					return npc.getName();
				}
			}
		}
		catch (RuntimeException ignored)
		{
		}
		if (lastAttackerName != null && lastAttackerTick >= 0
			&& client.getTickCount() - lastAttackerTick <= ATTACKER_MEMORY_TICKS)
		{
			return lastAttackerName;
		}
		return null;
	}

	@Subscribe
	public void onChatMessage(ChatMessage message)
	{
		ChatMessageType t = message.getType();
		if (t != ChatMessageType.MESBOX && t != ChatMessageType.GAMEMESSAGE
			&& t != ChatMessageType.SPAM)
		{
			return;
		}
		String msg = Text.removeTags(message.getMessage());

		if (PICKPOCKET.matcher(msg).matches())
		{
			pickpocketTick = client.getTickCount();
			return;
		}

		Matcher d = DIARY_COMPLETION.matcher(msg);
		if (d.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("area", d.group("region").trim());
			data.addProperty("difficulty", d.group("grade").trim().toUpperCase(Locale.ROOT));
			emit("DIARY", data);
			return;
		}
		if (t == ChatMessageType.MESBOX)
		{
			return;
		}

		Matcher kc = KILL_COUNT.matcher(msg);
		if (kc.find())
		{
			String subject = Text.removeTags(kc.group("subject")).trim();
			Integer tally = count(kc.group("tally"));
			if (tally == null)
			{
				return;
			}
			recentKc.put(cleanKey(subject), tally);
			String owner = localName();
			if (!NOT_A_KILL.contains(String.valueOf(kc.group("kind"))) && owner != null && localStore.isReadyFor(owner))
			{
				localStore.noteKillCount(subject, tally, owner);
			}
			return;
		}

		Matcher dur = KILL_DURATION.matcher(msg);
		if (dur.find())
		{
			lastKillTimeSec = parseDuration(dur.group("time"));
			lastKillPb = msg.contains(NEW_PB_MARK);
			Matcher pb = PERSONAL_BEST.matcher(msg);
			lastPbTimeSec = lastKillPb ? lastKillTimeSec
				: (pb.find() ? parseDuration(pb.group("pb")) : -1);
			lastKillTimeTick = client.getTickCount();
			return;
		}

		Matcher col = COLLECTION_ITEM.matcher(msg);
		if (col.find())
		{
			String item = col.group("entry").trim();
			JsonObject data = new JsonObject();
			data.addProperty("itemName", item);
			emit("COLLECTION", data);
			if (petPendingTicks >= 0)
			{
				emitPet(item);
			}
			return;
		}

		Matcher unt = UNTRADEABLE_DROP.matcher(msg);
		if (unt.find() && petPendingTicks >= 0)
		{
			emitPet(unt.group("dropped").trim());
			return;
		}

		Matcher ca = COMBAT_TASK.matcher(msg);
		if (ca.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("tier", ca.group("grade").trim().toUpperCase(Locale.ROOT));
			data.addProperty("task", COMBAT_TASK_POINTS.matcher(ca.group("challenge").trim()).replaceAll(""));
			emit("COMBAT_ACHIEVEMENT", data);
			return;
		}

		Matcher clue = CLUE_COMPLETION.matcher(msg);
		if (clue.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("clueType", clue.group("rank").trim().toUpperCase(Locale.ROOT));
			Integer tally = count(clue.group("tally"));
			if (tally != null)
			{
				data.addProperty("clueCount", tally);
			}
			emit("CLUE", data);
			return;
		}

		Matcher sk = SLAYER_FINISHED.matcher(msg);
		if (sk.find())
		{
			pendingSlayerMonster = sk.group("creature").trim();
			pendingSlayerTask = sk.group("slain").trim() + " " + pendingSlayerMonster;
			pendingSlayerKills = count(sk.group("slain"));
			slayerPendingTicks = 0;
			return;
		}
		Matcher sd = SLAYER_TOTAL.matcher(msg);
		if (sd.find())
		{
			String task = pendingSlayerIdentity(currentTask());
			emitSlayerCompletion(task, sd.group("qual") == null ? count(sd.group("total")) : null);
			return;
		}

		if (PET_RECEIVED.matcher(msg).matches())
		{
			petPendingTicks = 0;
		}
	}

	private void emitPet(String petName)
	{
		petPendingTicks = -1;
		if (petName == null || petName.isEmpty())
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("petName", petName);
		emit("PET", data);
	}

	private String pendingSlayerIdentity(String serviceTask)
	{
		for (String task : new String[]{pendingSlayerMonster, serviceTask, lastSlayerTask, pendingSlayerTask})
		{
			if (task != null && !task.isEmpty())
			{
				return task;
			}
		}
		return null;
	}

	private static Integer count(String digits)
	{
		try
		{
			return Integer.parseInt(digits.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		String owner = localName();
		left.tick(owner != null && localStore.isReadyFor(owner) ? owner : null).forEach(data ->
			emit("LOOT_UNTAKEN", data));

		if (petPendingTicks >= 0 && ++petPendingTicks > 3)
		{
			petPendingTicks = -1;
		}

		if (slayerPendingTicks >= 0 && ++slayerPendingTicks > 4)
		{
			emitSlayerCompletion(pendingSlayerIdentity(null), null);
		}

		if (pendingLevels.isEmpty())
		{
			return;
		}
		for (Skill skill : pendingLevels)
		{
			JsonObject data = new JsonObject();
			data.addProperty("skill", skill.getName());
			data.addProperty("level", knownLevels.getOrDefault(skill, 1));
			emit("LEVEL", data);
		}
		pendingLevels.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		left.stateChanged(state);
		if (state == GameState.LOGGING_IN || state == GameState.HOPPING || state == GameState.LOGIN_SCREEN)
		{
			reset();
		}
	}

	private static String cleanKey(String name)
	{
		if (name == null)
		{
			return "";
		}
		return Text.removeTags(name).replaceAll("\\s*\\(.+\\)$", "").trim().toLowerCase(Locale.ROOT);
	}

	private String localName()
	{
		return playerName(client);
	}

	static String playerName(Client client)
	{
		Player lp = client.getLocalPlayer();
		String name = lp != null ? lp.getName() : null;
		return name == null || name.isEmpty() ? null : name;
	}

	private void emit(String type, JsonObject data)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String name = localName();
		if (name == null)
		{
			return;
		}
		localStore.record(type, data, name);
		cloud.event(name, type, data);
	}
}
