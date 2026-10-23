/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import org.mockito.Mockito;

public class PanelPreviewTest
{
	private static void edt(ThrowingRunnable r) throws Exception
	{
		final Exception[] err = {null};
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				r.run();
			}
			catch (Exception e)
			{
				err[0] = e;
			}
		});
		if (err[0] != null)
		{
			throw err[0];
		}
	}

	private interface ThrowingRunnable
	{
		void run() throws Exception;
	}

	static StubPlugin journalStub(String dirPath, String rsn) throws Exception
	{
		File dir = new File(dirPath);
		StubPlugin s = new StubPlugin(mockItems());
		s.store.load(dir, rsn);
		s.spriteManager = mockSprites();
		s.skillIconManager = Mockito.mock(net.runelite.client.game.SkillIconManager.class);
		s.rsn = rsn;
		s.store.lifetime = s.store.trackersSnapshot();
		s.clogFinished = s.store.clogFraction()[0];
		s.clogAvailable = s.store.clogFraction()[1];
		s.history = new HistoryLog(new Gson()).read(dir, rsn);
		return s;
	}

	private static net.runelite.client.game.SpriteManager mockSprites()
	{
		String dir = System.getProperty("chronicle.exampleSprites");
		File art = dir == null ? null : new File(dir);
		net.runelite.client.game.SpriteManager sm =
			Mockito.mock(net.runelite.client.game.SpriteManager.class);
		Mockito.doAnswer(inv ->
		{
			int id = inv.getArgument(0);
			int frame = inv.getArgument(1);
			BufferedImage img = null;
			if (art != null)
			{
				File f = new File(art, "sprite-" + id + "-" + frame + ".png");
				if (f.isFile())
				{
					try
					{
						img = ImageIO.read(f);
					}
					catch (Exception ignored)
					{
						img = null;
					}
				}
			}
			if (img == null)
			{
				img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = img.createGraphics();
				g.setColor(new java.awt.Color(140, 120, 70));
				g.fillOval(3, 3, 26, 26);
				g.dispose();
			}
			((java.util.function.Consumer<BufferedImage>) inv.getArgument(2)).accept(img);
			return null;
		}).when(sm).getSpriteAsync(Mockito.anyInt(), Mockito.anyInt(),
			Mockito.any(java.util.function.Consumer.class));
		return sm;
	}

	private static ItemManager mockItems()
	{
		ClientThread ct = Mockito.mock(ClientThread.class);
		Mockito.doAnswer(inv ->
		{
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.getImage(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
			.thenAnswer(inv ->
			{
				AsyncBufferedImage img =
					new AsyncBufferedImage(ct, 36, 32, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = img.createGraphics();
				g.setColor(new java.awt.Color(96, 88, 68));
				g.fillRoundRect(6, 4, 24, 24, 6, 6);
				g.dispose();
				img.loaded();
				return img;
			});
		return im;
	}

	static class StubStore extends LocalStore
	{
		ItemManager itemManager;
		Map<String, Long> lifetime = new LinkedHashMap<>();
		int sessionLoots;
		long sessionLootValue;

		StubStore(ItemManager im)
		{
			super(im, new Gson());
			itemManager = im;
		}

		@Override
		ItemManager items()
		{
			return itemManager;
		}

		@Override
		Map<String, Long> lifetimeOf(Map<String, Integer> session)
		{
			return lifetime;
		}

		@Override
		int combatLevel()
		{
			return 125;
		}

		@Override
		int sessionLoots()
		{
			return sessionLoots;
		}

		@Override
		long sessionLootValue()
		{
			return sessionLootValue;
		}
	}

	static class StubPlugin implements Live
	{
		final StubStore store;
		String rsn;
		ChronicleEventCapture.SlayerView slayer;
		Map<String, Integer> session = new LinkedHashMap<>();
		final List<chronicle.counters.ExperienceStatTracker.SkillGain> skillXp = new ArrayList<>();
		String captureWarning;
		String captureWarningWhy;
		int fixesAsked;
		int clogFinished;
		int clogAvailable;
		TreeMap<LocalDate, HistoryLog.Baseline> history = new TreeMap<>();
		JsonObject liveSitting;
		long sessionStartMs;
		long sessionElapsed;
		net.runelite.client.game.SpriteManager spriteManager;
		net.runelite.client.game.SkillIconManager skillIconManager;

		StubPlugin(ItemManager im)
		{
			store = new StubStore(im);
		}

		ChroniclePanel panel()
		{
			java.util.concurrent.ScheduledExecutorService inline =
				Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
			Mockito.doAnswer(inv ->
			{
				((Runnable) inv.getArgument(0)).run();
				return null;
			}).when(inline).submit(Mockito.any(Runnable.class));
			return new ChroniclePanel(store, this, inline, new Gson(), spriteManager, skillIconManager);
		}

		@Override
		public boolean ready()
		{
			return true;
		}

		@Override
		public String displayRsn()
		{
			return rsn;
		}

		@Override
		public long sessionStart()
		{
			return sessionStartMs;
		}

		@Override
		public long sessionElapsedMinutes()
		{
			return sessionElapsed;
		}

		@Override
		public long sessionElapsedSeconds()
		{
			return sessionElapsed * 60;
		}

		@Override
		public Map<String, Integer> sessionView()
		{
			return session;
		}

		@Override
		public List<chronicle.counters.ExperienceStatTracker.SkillGain> sessionSkillXp()
		{
			return skillXp;
		}

		@Override
		public JsonObject liveSessionLine()
		{
			return liveSitting;
		}

		@Override
		public ChronicleEventCapture.SlayerView slayerView()
		{
			return slayer;
		}

		@Override
		public boolean slayerSeenThisSession()
		{
			return slayer != null;
		}

		@Override
		public Map<String, long[]> skillSheet()
		{
			return store.skillSheet();
		}

		@Override
		public PaceBook.Pace pace(String skill)
		{
			long xp = 0;
			if (!history.isEmpty())
			{
				Long v = history.lastEntry().getValue().skills.get(skill.toLowerCase(Locale.ROOT));
				xp = v != null ? v : 0;
			}
			return PaceBook.forSkill(history, skill.toLowerCase(Locale.ROOT), xp, LocalDate.now());
		}

		@Override
		public long gamePlaytimeMinutes()
		{
			return 0;
		}

		@Override
		public int clogFinished()
		{
			return clogFinished;
		}

		@Override
		public int clogAvailable()
		{
			return clogAvailable;
		}

		@Override
		public TreeMap<LocalDate, HistoryLog.Baseline> historyBaselines()
		{
			return history;
		}

		@Override
		public String captureWarning()
		{
			return captureWarning;
		}

		@Override
		public String captureWarningWhy()
		{
			return captureWarningWhy;
		}

		@Override
		public void turnOnMissingCapture()
		{
			fixesAsked++;
			captureWarning = null;
			captureWarningWhy = null;
		}

		@Override
		public void newSession()
		{
		}

		@Override
		public void actionImport(File file)
		{
		}
	}

	private static Object get(ChroniclePanel panel, String field) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(panel);
	}

	static void regatherHistory(ChroniclePanel panel) throws Exception
	{
		awaitHistory(panel);
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("gatherHistory");
			m.setAccessible(true);
			m.invoke(panel);
		});
		awaitHistory(panel);
	}

	static void awaitHistory(ChroniclePanel panel) throws Exception
	{
		long deadline = System.currentTimeMillis() + 10_000;
		while (true)
		{
			final boolean[] landed = new boolean[1];
			edt(() -> landed[0] = !(Boolean) get(panel, "historyGathering"));
			if (landed[0])
			{
				return;
			}
			if (System.currentTimeMillis() > deadline)
			{
				throw new AssertionError("the History tab's read never landed");
			}
			Thread.sleep(20);
		}
	}

}
