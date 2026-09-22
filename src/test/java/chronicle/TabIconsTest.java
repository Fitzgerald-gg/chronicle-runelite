/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The tab icons are one set, drawn to one contract: 16 by 16, a single grey,
 * each pixel either lit or clear with no anti-aliasing, and between 84 and 124
 * pixels lit so none reads as a scratch or a blot beside the others. The
 * contract lived in a commit message; this is where it is kept now.
 */
public class TabIconsTest
{
	private static final String[] TABS = {"tab_record", "tab_standing", "tab_loot", "tab_trackers"};
	private static final int GREY = 0x9F9F9F;

	@Test
	public void everyIconKeepsTheSetsContract() throws Exception
	{
		Set<String> seen = new HashSet<>();
		for (String name : TABS)
		{
			BufferedImage im = ImageIO.read(ChroniclePanel.class.getResourceAsStream(name + ".png"));
			assertNotNull(name + " is missing", im);
			assertEquals(name + " width", 16, im.getWidth());
			assertEquals(name + " height", 16, im.getHeight());
			int lit = 0;
			StringBuilder shape = new StringBuilder();
			for (int y = 0; y < 16; y++)
			{
				for (int x = 0; x < 16; x++)
				{
					int argb = im.getRGB(x, y);
					int alpha = argb >>> 24;
					assertTrue(name + " is anti-aliased at " + x + "," + y + ": alpha " + alpha,
						alpha == 0 || alpha == 255);
					if (alpha == 255)
					{
						assertEquals(name + " strays from the set's grey at " + x + "," + y,
							GREY, argb & 0xFFFFFF);
						lit++;
					}
					shape.append(alpha == 255 ? '#' : '.');
				}
			}
			assertTrue(name + " lights " + lit + " pixels, outside the set's 84 to 124",
				lit >= 84 && lit <= 124);
			assertTrue(name + " is the same drawing as another tab's", seen.add(shape.toString()));
		}
	}
}
