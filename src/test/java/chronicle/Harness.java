/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Guice;
import com.google.inject.Injector;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.ScriptEvent;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.http.api.loottracker.LootRecordType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class Harness
{
	static final File DIR = journalDir();

	private final Map<Integer, String> names = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> prices = new ConcurrentHashMap<>();
	private final Map<Integer, ItemComposition> comps = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> varbits = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> varps = new ConcurrentHashMap<>();
	private final Map<Skill, int[]> skills = new EnumMap<>(Skill.class);
	private final Map<Integer, Widget> widgets = new ConcurrentHashMap<>();
	private final Map<Integer, ItemContainer> containers = new ConcurrentHashMap<>();
	private final Map<String, String> profile = new ConcurrentHashMap<>();
	private final Map<String, String> lootTracker = new ConcurrentHashMap<>();
	private final Map<Integer, String> quests = new ConcurrentHashMap<>();
	private final Map<TileItem, WorldPoint> spots = new ConcurrentHashMap<>();
	private final Client client = mock(Client.class);
	private final Player me = mock(Player.class);
	private final SlayerPluginService slayer = mock(SlayerPluginService.class);
	private final EventBus bus = new EventBus();
	private final ItemManager items = mock(ItemManager.class);
	private final List<String> said = new ArrayList<>();
	private ChroniclePlugin plugin;
	private String rsn;
	private GameState state = GameState.LOGIN_SCREEN;
	private int tick = 100;
	private int lastQuest;
	private WorldPoint at = new WorldPoint(3200, 3200, 0);
	private Actor target;
	private int anim = -1;
	private int gfx = -1;
	private volatile String task;
	private volatile int remaining;
	private volatile int initial;
	private String granularity = "Lifetime";
	private LocalDate cursor = LocalDate.now();

	Harness()
	{
		this("Tester");
	}

	Harness(String rsn)
	{
		this.rsn = rsn;
		wipe(DIR);
		DIR.mkdirs();
		item(995, "Coins", 1);
		item(526, "Bones", 100);
		item(536, "Dragon bones", 2500);
		item(1618, "Uncut diamond", 1800);
		item(4151, "Abyssal whip", 1500000);
		item(11286, "Draconic visage", 3000000);
		item(385, "Shark", 900);
		item(379, "Lobster", 150);
		item(2434, "Prayer potion(4)", 8000, "Drink");
		item(139, "Prayer potion(3)", 6000, "Drink");
		item(229, "Vial", 5);
		item(892, "Rune arrow", 150);
		item(1517, "Maple logs", 20);
		item(52, "Arrow shaft", 5);
		item(1623, "Uncut sapphire", 300);
		item(1607, "Sapphire", 600);
		item(12016, "Tanzanite fang", 900000);
		item(13262, "Abyssal orphan", 0);
		for (Skill s : Skill.values())
		{
			skills.put(s, new int[]{s == Skill.HITPOINTS ? 10 : 1, s == Skill.HITPOINTS ? 1154 : 0});
		}
		fakeClient();
		fakeItems();
		boot();
	}

	private static File journalDir()
	{
		try
		{
			File home = Files.createTempDirectory("chronicle-home").toFile();
			System.setProperty("user.home", home.getPath());
			if (!RuneLite.RUNELITE_DIR.getPath().startsWith(home.getPath()))
			{
				throw new IllegalStateException("RuneLite dir is not under the test home");
			}
			System.setProperty("java.awt.headless", "true");
			return new File(RuneLite.RUNELITE_DIR, "chronicle");
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static void wipe(File f)
	{
		File[] kids = f.listFiles();
		if (kids != null)
		{
			for (File k : kids)
			{
				wipe(k);
			}
		}
		f.delete();
	}

	Harness item(int id, String name, int price, String... actions)
	{
		names.put(id, name);
		prices.put(id, price);
		ItemComposition c = mock(ItemComposition.class);
		when(c.getName()).thenReturn(name);
		when(c.getId()).thenReturn(id);
		when(c.getPrice()).thenReturn(price);
		when(c.getInventoryActions()).thenReturn(actions.length == 0 ? new String[]{"Eat"} : actions);
		comps.put(id, c);
		return this;
	}

	private ItemComposition comp(int id)
	{
		return comps.computeIfAbsent(id, k ->
		{
			ItemComposition c = mock(ItemComposition.class);
			when(c.getName()).thenReturn("Item " + k);
			when(c.getId()).thenReturn(k);
			return c;
		});
	}

	private void fakeClient()
	{
		when(me.getName()).thenAnswer(i -> rsn);
		when(me.getWorldLocation()).thenAnswer(i -> at);
		when(me.getAnimation()).thenAnswer(i -> anim);
		when(me.getGraphic()).thenAnswer(i -> gfx);
		when(me.getInteracting()).thenAnswer(i -> target);
		when(me.getCombatLevel()).thenReturn(90);
		when(me.hasSpotAnim(anyInt())).thenAnswer(i -> i.<Integer>getArgument(0) == gfx);
		when(slayer.getTask()).thenAnswer(i -> task);
		when(slayer.getRemainingAmount()).thenAnswer(i -> remaining);
		when(slayer.getInitialAmount()).thenAnswer(i -> initial);
		when(client.getLocalPlayer()).thenReturn(me);
		when(client.getGameState()).thenAnswer(i -> state);
		when(client.getTickCount()).thenAnswer(i -> tick);
		when(client.getVarbitValue(anyInt())).thenAnswer(i -> varbits.getOrDefault(i.<Integer>getArgument(0), 0));
		when(client.getVarpValue(anyInt())).thenAnswer(i -> varps.getOrDefault(i.<Integer>getArgument(0), 0));
		when(client.getRealSkillLevel(any())).thenAnswer(i -> skills.get(i.<Skill>getArgument(0))[0]);
		when(client.getBoostedSkillLevel(any())).thenAnswer(i -> skills.get(i.<Skill>getArgument(0))[0]);
		when(client.getSkillExperience(any())).thenAnswer(i -> skills.get(i.<Skill>getArgument(0))[1]);
		when(client.getTotalLevel()).thenAnswer(i -> skills.entrySet().stream()
			.filter(e -> e.getKey() != Skill.OVERALL).mapToInt(e -> e.getValue()[0]).sum());
		when(client.getOverallExperience()).thenAnswer(i -> skills.entrySet().stream()
			.filter(e -> e.getKey() != Skill.OVERALL).mapToLong(e -> e.getValue()[1]).sum());
		when(client.getItemContainer(anyInt())).thenAnswer(i -> containers.get(i.<Integer>getArgument(0)));
		when(client.getItemContainer(any(net.runelite.api.InventoryID.class)))
			.thenAnswer(i -> containers.get(i.<net.runelite.api.InventoryID>getArgument(0).getId()));
		when(client.getItemDefinition(anyInt())).thenAnswer(i -> comp(i.getArgument(0)));
		when(client.getWidget(anyInt())).thenAnswer(i -> widgets.get(i.<Integer>getArgument(0)));
		when(client.getEnergy()).thenReturn(10000);
		doAnswer(i ->
		{
			Object[] args = i.getArguments();
			lastQuest = args.length > 1 && args[1] instanceof Integer ? (Integer) args[1] : 0;
			return null;
		}).when(client).runScript(any());
		when(client.getIntStack()).thenAnswer(i -> new int[]{
			"FINISHED".equals(quests.get(lastQuest)) ? 2 : "IN_PROGRESS".equals(quests.get(lastQuest)) ? 0 : 1});
		doAnswer(i ->
		{
			said.add(i.getArgument(2));
			return null;
		}).when(client).addChatMessage(any(), any(), any(), any());
	}

	private void fakeItems()
	{
		when(items.canonicalize(anyInt())).thenAnswer(i -> i.getArgument(0));
		when(items.getItemPrice(anyInt())).thenAnswer(i -> (long) prices.getOrDefault(i.<Integer>getArgument(0), 0));
		when(items.getItemComposition(anyInt())).thenAnswer(i -> comp(i.getArgument(0)));
		when(items.search(anyString())).thenAnswer(i ->
		{
			String q = i.<String>getArgument(0).toLowerCase();
			List<ItemPrice> out = new ArrayList<>();
			names.forEach((id, n) ->
			{
				if (n.toLowerCase().contains(q))
				{
					ItemPrice p = new ItemPrice();
					p.setId(id);
					p.setName(n);
					p.setPrice(prices.get(id));
					p.setWikiPrice(prices.get(id));
					out.add(p);
				}
			});
			return out;
		});
		ClientThread inline = mock(ClientThread.class);
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(inline).invokeLater(any(Runnable.class));
		when(items.getImage(anyInt(), anyInt(), anyBoolean())).thenAnswer(i ->
		{
			AsyncBufferedImage img = new AsyncBufferedImage(inline, 36, 32, BufferedImage.TYPE_INT_ARGB);
			img.loaded();
			return img;
		});
	}

	private void boot()
	{
		ClientThread thread = mock(ClientThread.class);
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(thread).invoke(any(Runnable.class));
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(thread).invokeLater(any(Runnable.class));
		ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(exec).submit(any(Runnable.class));
		ConfigManager config = mock(ConfigManager.class);
		when(config.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> profile.get(i.getArgument(1)));
		doAnswer(i ->
		{
			profile.put(i.getArgument(1), String.valueOf(i.<Object>getArgument(2)));
			return null;
		}).when(config).setRSProfileConfiguration(anyString(), anyString(), any());
		when(config.getRSProfileKey()).thenReturn("profile");
		when(config.getRSProfileConfigurationKeys(anyString(), anyString(), anyString()))
			.thenAnswer(i -> new ArrayList<>(lootTracker.keySet()));
		when(config.getConfiguration(anyString(), anyString(), anyString()))
			.thenAnswer(i -> lootTracker.get(i.<String>getArgument(2)));
		SpriteManager sprites = mock(SpriteManager.class);
		doAnswer(i ->
		{
			BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.fillOval(3, 3, 26, 26);
			g.dispose();
			i.<Consumer<BufferedImage>>getArgument(2).accept(img);
			return null;
		}).when(sprites).getSpriteAsync(anyInt(), anyInt(), any(Consumer.class));
		PluginManager plugins = mock(PluginManager.class);
		when(plugins.getPlugins()).thenReturn(Collections.emptyList());
		Injector injector = Guice.createInjector(b ->
		{
			b.bind(Client.class).toInstance(client);
			b.bind(ClientThread.class).toInstance(thread);
			b.bind(ConfigManager.class).toInstance(config);
			b.bind(ChronicleConfig.class).toInstance(mock(ChronicleConfig.class));
			b.bind(ChronicleApiClient.class).toInstance(mock(ChronicleApiClient.class));
			b.bind(ScheduledExecutorService.class).toInstance(exec);
			b.bind(ClientToolbar.class).toInstance(mock(ClientToolbar.class));
			b.bind(PluginManager.class).toInstance(plugins);
			b.bind(EventBus.class).toInstance(bus);
			b.bind(ItemManager.class).toInstance(items);
			b.bind(SkillIconManager.class).toInstance(mock(SkillIconManager.class));
			b.bind(SpriteManager.class).toInstance(sprites);
			b.bind(SlayerPluginService.class).toInstance(slayer);
			b.bind(Gson.class).toInstance(new Gson());
		});
		edt(() ->
		{
			if (!(javax.swing.UIManager.getLookAndFeel() instanceof net.runelite.client.ui.laf.RuneLiteLAF))
			{
				javax.swing.UIManager.setLookAndFeel(new net.runelite.client.ui.laf.RuneLiteLAF());
			}
		});
		plugin = injector.getInstance(ChroniclePlugin.class);
		edt(plugin::startUp);
		bus.register(plugin);
	}

	Harness login()
	{
		state(GameState.LOGGING_IN);
		state(GameState.LOGGED_IN);
		return tick();
	}

	Harness logout()
	{
		state(GameState.LOGIN_SCREEN);
		return this;
	}

	Harness as(String name)
	{
		rsn = name;
		return this;
	}

	Harness state(GameState s)
	{
		state = s;
		GameStateChanged e = new GameStateChanged();
		e.setGameState(s);
		return post(e);
	}

	Harness post(Object event)
	{
		bus.post(event);
		return this;
	}

	Harness tick()
	{
		tick++;
		return post(new GameTick());
	}

	Harness ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick();
		}
		return this;
	}

	Harness chat(String msg)
	{
		return chat(ChatMessageType.GAMEMESSAGE, msg);
	}

	Harness chat(ChatMessageType type, String msg)
	{
		return post(new ChatMessage(null, type, "", msg, "", 0));
	}

	Harness kill(String npc, int npcId, int... idQty)
	{
		NPCComposition c = mock(NPCComposition.class);
		when(c.getName()).thenReturn(npc);
		when(c.getId()).thenReturn(npcId);
		return post(new ServerNpcLoot(c, stacks(idQty)));
	}

	Harness loot(String source, LootRecordType type, int... idQty)
	{
		return post(new LootReceived(source, 0, type, stacks(idQty), 1, null));
	}

	private static List<ItemStack> stacks(int... idQty)
	{
		List<ItemStack> out = new ArrayList<>();
		for (int i = 0; i + 1 < idQty.length; i += 2)
		{
			out.add(new ItemStack(idQty[i], idQty[i + 1]));
		}
		return out;
	}

	Harness task(String task, int remaining, int initial)
	{
		this.task = task;
		this.remaining = remaining;
		this.initial = initial;
		return this;
	}

	Harness level(Skill skill, int level, int xp)
	{
		skills.put(skill, new int[]{level, xp});
		return post(new StatChanged(skill, xp, level, level));
	}

	Harness xp(Skill skill, int gain)
	{
		int[] s = skills.get(skill);
		return level(skill, s[0], s[1] + gain);
	}

	Harness hp(int boosted)
	{
		skills.get(Skill.HITPOINTS)[0] = boosted;
		return this;
	}

	Harness varbit(int id, int value)
	{
		varbits.put(id, value);
		return this;
	}

	Harness varp(int id, int value)
	{
		varps.put(id, value);
		return this;
	}

	Harness quest(int id, String state)
	{
		quests.put(id, state);
		return this;
	}

	Harness walk(int dx, int dy)
	{
		at = new WorldPoint(at.getX() + dx, at.getY() + dy, at.getPlane());
		return tick();
	}

	Harness teleport(WorldPoint to)
	{
		at = to;
		return tick();
	}

	Harness animate(int animation, int graphic)
	{
		anim = animation;
		gfx = graphic;
		AnimationChanged e = new AnimationChanged();
		e.setActor(me);
		return post(e);
	}

	Harness click(String option, String target, int itemId)
	{
		return click(option, target, itemId, MenuAction.CC_OP, -1, -1);
	}

	Harness click(String option, String target, int itemId, MenuAction action, int param0, int widget)
	{
		MenuEntry m = mock(MenuEntry.class);
		when(m.getOption()).thenReturn(option);
		when(m.getTarget()).thenReturn(target);
		when(m.getItemId()).thenReturn(itemId);
		when(m.isItemOp()).thenReturn(itemId > 0);
		when(m.getType()).thenReturn(action);
		when(m.getParam0()).thenReturn(param0);
		when(m.getParam1()).thenReturn(widget);
		when(m.getIdentifier()).thenReturn(itemId);
		return post(new MenuOptionClicked(m));
	}

	Harness pack(int... idQty)
	{
		return container(InventoryID.INV, idQty);
	}

	Harness worn(int slot, int id, int qty)
	{
		int[] held = new int[(slot + 1) * 2];
		Arrays.fill(held, -1);
		held[slot * 2] = id;
		held[slot * 2 + 1] = qty;
		return container(InventoryID.WORN, held);
	}

	Harness container(int id, int... idQty)
	{
		Item[] held = new Item[idQty.length / 2];
		for (int i = 0; i < held.length; i++)
		{
			held[i] = idQty[i * 2] < 0 ? null : new Item(idQty[i * 2], idQty[i * 2 + 1]);
		}
		ItemContainer c = mock(ItemContainer.class);
		when(c.getItems()).thenReturn(Arrays.stream(held).map(it -> it == null ? new Item(-1, 0) : it)
			.toArray(Item[]::new));
		when(c.getItem(anyInt())).thenAnswer(i ->
		{
			int slot = i.getArgument(0);
			return slot >= 0 && slot < held.length ? held[slot] : null;
		});
		when(c.count(anyInt())).thenAnswer(i -> Arrays.stream(held)
			.filter(it -> it != null && it.getId() == i.<Integer>getArgument(0)).mapToInt(Item::getQuantity).sum());
		when(c.getId()).thenReturn(id);
		containers.put(id, c);
		return post(new ItemContainerChanged(id, c));
	}

	Harness widget(int group)
	{
		WidgetLoaded e = new WidgetLoaded();
		e.setGroupId(group);
		return post(e);
	}

	NPC npc(String name, int id, WorldPoint where, int size)
	{
		NPC n = mock(NPC.class);
		NPCComposition c = mock(NPCComposition.class);
		when(c.getName()).thenReturn(name);
		when(c.getId()).thenReturn(id);
		when(c.getSize()).thenReturn(size);
		when(n.getName()).thenReturn(name);
		when(n.getId()).thenReturn(id);
		when(n.getIndex()).thenReturn(Math.abs(where.hashCode() % 1000));
		when(n.getWorldLocation()).thenReturn(where);
		when(n.getComposition()).thenReturn(c);
		when(n.getCombatLevel()).thenReturn(100);
		return n;
	}

	Harness attackedBy(NPC npc)
	{
		return post(new net.runelite.api.events.InteractingChanged(npc, me));
	}

	Harness questScroll(String title)
	{
		widgets.put(InterfaceID.Questscroll.QUEST_TITLE, text(title));
		return widget(InterfaceID.QUESTSCROLL);
	}

	Harness dies(Actor who)
	{
		return post(new ActorDeath(who == null ? me : who));
	}

	Harness fight(NPC npc)
	{
		target = npc;
		return this;
	}

	Harness hit(Actor on, int type, int amount)
	{
		Hitsplat h = mock(Hitsplat.class);
		when(h.getHitsplatType()).thenReturn(type);
		when(h.getAmount()).thenReturn(amount);
		when(h.isMine()).thenReturn(type != HitsplatID.DAMAGE_OTHER);
		HitsplatApplied e = new HitsplatApplied();
		e.setActor(on == null ? me : on);
		e.setHitsplat(h);
		return post(e);
	}

	TileItem ground(int id, int qty, WorldPoint where, boolean group)
	{
		TileItem t = mock(TileItem.class);
		when(t.getId()).thenReturn(id);
		when(t.getQuantity()).thenReturn(qty);
		when(t.getOwnership()).thenReturn(group ? TileItem.OWNERSHIP_GROUP : TileItem.OWNERSHIP_SELF);
		when(t.getDespawnTime()).thenReturn(tick + 200);
		spots.put(t, where);
		spots.put(t, where);
		post(new ItemSpawned(tile(where), t));
		return t;
	}

	Harness pickUp(TileItem t)
	{
		return post(new ItemDespawned(tile(spots.get(t)), t));
	}

	Harness rot(TileItem t)
	{
		tick = t.getDespawnTime();
		return pickUp(t);
	}

	private static Tile tile(WorldPoint where)
	{
		Tile t = mock(Tile.class);
		when(t.getWorldLocation()).thenReturn(where);
		return t;
	}

	Harness clogPage(String page, List<String> lines, int... idQtyOpacity)
	{
		Widget header = mock(Widget.class);
		List<Widget> head = new ArrayList<>();
		head.add(text(page));
		for (String l : lines)
		{
			head.add(text(l));
		}
		when(header.getDynamicChildren()).thenReturn(head.toArray(new Widget[0]));
		Widget grid = mock(Widget.class);
		Widget[] slots = new Widget[idQtyOpacity.length / 3];
		for (int i = 0; i < slots.length; i++)
		{
			slots[i] = mock(Widget.class);
			when(slots[i].getItemId()).thenReturn(idQtyOpacity[i * 3]);
			when(slots[i].getItemQuantity()).thenReturn(idQtyOpacity[i * 3 + 1]);
			when(slots[i].getOpacity()).thenReturn(idQtyOpacity[i * 3 + 2]);
		}
		when(grid.getDynamicChildren()).thenReturn(slots);
		widgets.put(ComponentID.COLLECTION_LOG_ENTRY_HEADER, header);
		widgets.put(ComponentID.COLLECTION_LOG_ENTRY_ITEMS, grid);
		return post(new ScriptPostFired(net.runelite.api.ScriptID.COLLECTION_DRAW_LIST));
	}

	Harness clogTransmit(int itemId, int qty)
	{
		ScriptEvent ev = mock(ScriptEvent.class);
		when(ev.getArguments()).thenReturn(new Object[]{4100, itemId, qty});
		ScriptPreFired e = new ScriptPreFired(4100);
		e.setScriptEvent(ev);
		return post(e);
	}

	Harness killLog(String... nameKills)
	{
		Widget names = mock(Widget.class);
		Widget kills = mock(Widget.class);
		Widget[] n = new Widget[nameKills.length / 2];
		Widget[] k = new Widget[n.length];
		for (int i = 0; i < n.length; i++)
		{
			n[i] = text(nameKills[i * 2]);
			k[i] = text(nameKills[i * 2 + 1]);
		}
		when(names.getDynamicChildren()).thenReturn(n);
		when(kills.getDynamicChildren()).thenReturn(k);
		widgets.put(InterfaceID.KillLog.NAME, names);
		widgets.put(InterfaceID.KillLog.KILL, kills);
		return widget(InterfaceID.KILL_LOG).ticks(2);
	}

	private static Widget text(String s)
	{
		Widget w = mock(Widget.class);
		when(w.getText()).thenReturn(s);
		return w;
	}

	Harness lootTracker(String key, String json)
	{
		lootTracker.put(key, json);
		return this;
	}


	Harness sitting(int minutes)
	{
		set(plugin, "sessionStartMs", System.currentTimeMillis() - minutes * 60_000L);
		return this;
	}

	List<String> said()
	{
		return said;
	}

	File file()
	{
		return new File(DIR, LocalStore.slug(rsn) + ".json");
	}

	File spineFile()
	{
		return new File(DIR, LocalStore.slug(rsn) + HistoryLog.SPINE_SUFFIX);
	}

	Harness save()
	{
		call(plugin, "refreshLocal");
		return this;
	}

	JsonObject journal()
	{
		save();
		return read(file());
	}

	static JsonObject read(File f)
	{
		try
		{
			return new Gson().fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8),
				JsonObject.class);
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	static void write(File f, String text)
	{
		try
		{
			f.getParentFile().mkdirs();
			Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	Harness edit(Consumer<JsonObject> change)
	{
		JsonObject j = journal();
		change.accept(j);
		write(file(), j.toString());
		store().load(DIR, rsn);
		call(plugin, "reloadHistory", rsn);
		return this;
	}

	JsonArray feed()
	{
		return journal().getAsJsonArray("feed");
	}

	List<JsonObject> feed(String type)
	{
		List<JsonObject> out = new ArrayList<>();
		journal().getAsJsonArray("feed").forEach(e ->
		{
			if (type.equals(e.getAsJsonObject().get("type").getAsString()))
			{
				out.add(e.getAsJsonObject().getAsJsonObject("data"));
			}
		});
		return out;
	}

	long tracker(String key)
	{
		JsonObject t = journal().getAsJsonObject("trackers");
		return t.has(key) ? t.get(key).getAsLong() : 0;
	}

	Map<String, Long> kills()
	{
		return plugin.killCounts();
	}

	Harness importFile(File f)
	{
		plugin.actionImport(f);
		return this;
	}

	Harness spine(LocalDate day, Map<String, Long> skills, Map<String, Long> counters, Map<String, Long> kcs)
	{
		history().append(DIR, rsn, skills, counters, kcs, null, day);
		call(plugin, "reloadHistory", rsn);
		return this;
	}

	JsonObject spineOn(LocalDate day)
	{
		HistoryLog.Baseline b = history().read(DIR, rsn).get(day);
		if (b == null)
		{
			return null;
		}
		Gson g = new Gson();
		JsonObject o = new JsonObject();
		o.add("skills", g.toJsonTree(b.skills));
		o.add("counters", g.toJsonTree(b.counters));
		o.add("kcs", g.toJsonTree(b.kcs));
		return o;
	}

	int compact()
	{
		return history().compact(DIR, rsn);
	}

	List<String> spineLines()
	{
		try
		{
			return spineFile().isFile() ? Files.readAllLines(spineFile().toPath()) : new ArrayList<>();
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private LocalStore store()
	{
		return (LocalStore) get(plugin, "localStore");
	}

	private HistoryLog history()
	{
		return (HistoryLog) get(plugin, "historyLog");
	}

	Harness period(String granularity, LocalDate cursor)
	{
		this.granularity = granularity;
		this.cursor = cursor;
		return this;
	}

	List<String> screen(String... path)
	{
		ChroniclePanel p = (ChroniclePanel) get(plugin, "panel");
		List<String> out = new ArrayList<>();
		edt(() ->
		{
			p.resetAccountCaches();
			go(p, path);
			board(p).gatherHistory();
		});
		for (int i = 0; i < 500 && (Boolean) onEdt(() -> board(p).historyGathering); i++)
		{
			edt(() -> Thread.sleep(10));
		}
		edt(() ->
		{
			go(p, path);
			collect(p, out);
		});
		return out;
	}

	private static Board board(ChroniclePanel p)
	{
		return (Board) get(p, "board");
	}

	BufferedImage picture(String... path)
	{
		screen(path);
		ChroniclePanel p = (ChroniclePanel) get(plugin, "panel");
		BufferedImage[] img = new BufferedImage[1];
		edt(() ->
		{
			p.setSize(242, 8000);
			lay(p);
			javax.swing.JScrollPane pane = find(p, javax.swing.JScrollPane.class);
			int h = pane.getY() + pane.getViewport().getView().getPreferredSize().height + 8;
			p.setSize(242, Math.min(8000, h));
			lay(p);
			img[0] = new BufferedImage(242, p.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img[0].createGraphics();
			p.paint(g);
			g.dispose();
		});
		return img[0];
	}

	private static void lay(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				lay(k);
			}
		}
	}

	private static <T> T find(Component c, Class<T> type)
	{
		if (type.isInstance(c))
		{
			return type.cast(c);
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				T hit = find(k, type);
				if (hit != null)
				{
					return hit;
				}
			}
		}
		return null;
	}

	private void go(ChroniclePanel p, String[] path) throws Exception
	{
		((net.runelite.client.ui.components.IconTextField) get(p, "searchField")).setText("");
		for (String n : new String[]{"detailSource", "detailItem", "detailSkill", "sheetPage", "lootKind",
			"lootTask", "leftBehindSource", "leftBehindItem"})
		{
			set(p, n, null);
		}
		set(p, "detailTask", -1);
		for (String n : new String[]{"allTrackers", "showInfo", "showRecords", "showCalendar",
			"dropsLeftBehind", "dropsByKind", "onTaskOnly"})
		{
			set(p, n, false);
		}
		((Collection<?>) get(p, "detailStack")).clear();
		Period period = (Period) get(p, "period");
		period.from = null;
		period.to = null;
		period.granularity = granularity;
		period.cursor = cursor;
		String lens = null;
		for (String step : path)
		{
			int colon = step.indexOf(':');
			String key = colon < 0 ? step : step.substring(0, colon);
			String val = colon < 0 ? null : step.substring(colon + 1);
			switch (key)
			{
				case "Now":
				case "Journal":
				case "Ledger":
				case "Recap":
					tab(p, "RECORD", key);
					lens = key.equals("Journal") ? "journalLens" : key.equals("Ledger") ? "statsFamily" : null;
					break;
				case "Loot":
				case "Slayer":
					tab(p, "LOOT", key);
					lens = key.equals("Slayer") ? "slayerLens" : null;
					break;
				case "Standing":
					tab(p, "STANDING", null);
					lens = "sheetPage";
					break;
				case "Trackers":
					tab(p, "TRACKERS", null);
					lens = "statsFamily";
					break;
				case "Records":
					p.openRecords();
					break;
				case "Calendar":
					p.openCalendar();
					break;
				case "Info":
					p.openInfo();
					break;
				case "All trackers":
					p.openAllTrackers();
					break;
				case "left":
					set(p, "dropsLeftBehind", true);
					break;
				case "kinds":
					set(p, "dropsByKind", true);
					break;
				case "onTask":
					set(p, "onTaskOnly", true);
					break;
				case "kind":
					set(p, "lootKind", val);
					break;
				case "source":
					p.openSource(val);
					break;
				case "item":
					p.openItem(val);
					break;
				case "skill":
					p.openSkill(val);
					break;
				case "task":
					set(p, "detailTask", Integer.parseInt(val));
					break;
				case "clog":
					set(p, "clogTab", val);
					break;
				case "page":
					set(p, "clogPageSel", val);
					break;
				case "search":
					((net.runelite.client.ui.components.IconTextField) get(p, "searchField")).setText(val);
					break;
				default:
					set(p, lens, step);
			}
		}
		call(p, "rebuildNow");
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void tab(ChroniclePanel p, String tab, String sub) throws Exception
	{
		Class<?> type = Class.forName("chronicle.ChroniclePanel$Tab");
		Object t = Enum.valueOf((Class) type, tab);
		if (sub != null)
		{
			((Map<Object, String>) get(p, "subByTab")).put(t, sub);
		}
		Method m = ChroniclePanel.class.getDeclaredMethod("applyTab", type);
		m.setAccessible(true);
		m.invoke(p, t);
	}

	private static void collect(Component c, List<String> out)
	{
		if (!c.isVisible())
		{
			return;
		}
		if (c instanceof JLabel && ((JLabel) c).getText() != null)
		{
			add(out, ((JLabel) c).getText());
		}
		if (c instanceof JComponent && ((JComponent) c).getToolTipText() != null)
		{
			add(out, ((JComponent) c).getToolTipText());
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				collect(k, out);
			}
		}
	}

	private static void add(List<String> out, String html)
	{
		String s = html.replaceAll("<br\\s*/?>|</div>|</p>", "\n").replaceAll("<[^>]+>", "")
			.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
			.replace("&#39;", "'");
		for (String line : s.split("\n"))
		{
			if (!line.trim().isEmpty())
			{
				out.add(line.trim().replaceAll("\\s+", " "));
			}
		}
	}

	static boolean has(List<String> screen, String text)
	{
		return screen.stream().anyMatch(s -> s.contains(text));
	}

	static String after(List<String> screen, String label)
	{
		int i = screen.lastIndexOf(label);
		return i < 0 || i + 1 >= screen.size() ? null : screen.get(i + 1);
	}


	private interface Job
	{
		void run() throws Exception;
	}

	private interface Ask
	{
		Object get() throws Exception;
	}

	private static void edt(Job job)
	{
		Throwable[] thrown = new Throwable[1];
		Runnable r = () ->
		{
			try
			{
				job.run();
			}
			catch (Throwable t)
			{
				thrown[0] = t;
			}
		};
		try
		{
			if (SwingUtilities.isEventDispatchThread())
			{
				r.run();
			}
			else
			{
				SwingUtilities.invokeAndWait(r);
			}
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
		if (thrown[0] != null)
		{
			throw new IllegalStateException(thrown[0]);
		}
	}

	private static Object onEdt(Ask ask)
	{
		Object[] got = new Object[1];
		edt(() -> got[0] = ask.get());
		return got[0];
	}

	private static Field field(Class<?> c, String name) throws NoSuchFieldException
	{
		for (Class<?> k = c; k != null; k = k.getSuperclass())
		{
			try
			{
				Field f = k.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			}
			catch (NoSuchFieldException ignored)
			{
			}
		}
		throw new NoSuchFieldException(name);
	}

	private static Object get(Object o, String name)
	{
		try
		{
			return field(o.getClass(), name).get(o);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static void set(Object o, String name, Object value)
	{
		try
		{
			field(o.getClass(), name).set(o, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static Object call(Object o, String name, Object... args)
	{
		try
		{
			for (Method m : o.getClass().getDeclaredMethods())
			{
				if (m.getName().equals(name) && m.getParameterCount() == args.length)
				{
					m.setAccessible(true);
					return m.invoke(o, args);
				}
			}
			throw new NoSuchMethodException(name);
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new IllegalStateException(e.getCause());
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
