package chronicle;

import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CopyPictureHeightTest
{
	private static JPanel rows(int n)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(Color.BLACK);
		for (int i = 0; i < n; i++)
		{
			JLabel l = new JLabel("row " + (i + 1));
			l.setForeground(Color.WHITE);
			l.setOpaque(true);
			l.setBackground(Color.BLACK);
			l.setPreferredSize(new java.awt.Dimension(300, 20));
			l.setMaximumSize(new java.awt.Dimension(340, 20));
			p.add(l);
		}
		return p;
	}

	private static BufferedImage tall(int n) throws Exception
	{
		final BufferedImage[] hold = new BufferedImage[1];
		SwingUtilities.invokeAndWait(
			() -> hold[0] = (BufferedImage) ChroniclePanel.copyImage(rows(n), true));
		return hold[0];
	}

	private static boolean inked(BufferedImage img, int y)
	{
		for (int x = 0; x < img.getWidth(); x++)
		{
			if ((img.getRGB(x, y) & 0xffffff) != 0)
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void aTallPictureIsAsTallAsItsList() throws Exception
	{
		BufferedImage img = tall(600);
		assertEquals("a tall copy is one column", 340, img.getWidth());
		assertTrue("600 rows of 20px came out " + img.getHeight() + " tall",
			img.getHeight() >= 600 * 20);
	}

	@Test
	public void theTopOfTheListIsDrawn() throws Exception
	{
		BufferedImage img = tall(600);
		boolean any = false;
		for (int y = 0; y < 20 && !any; y++)
		{
			any = inked(img, y);
		}
		assertTrue("the first row of the list was drawn at no height at all", any);
	}

	@Test
	public void aPictureTooTallSaysSo() throws Exception
	{
		BufferedImage img = tall(1200);
		assertEquals(20000, img.getHeight());
		boolean top = false;
		for (int y = 0; y < 20 && !top; y++)
		{
			top = inked(img, y);
		}
		assertTrue("a cropped picture still starts at the top of the list", top);
		assertTrue("nothing was written where the picture had to stop",
			inked(img, img.getHeight() - 8));
	}
}
