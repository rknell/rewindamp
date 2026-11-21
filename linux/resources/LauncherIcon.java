import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

class LauncherIcon {
    public static void main(String[] args) throws Exception {
        BufferedImage foreground = ImageIO.read(new File(args[0]));
        // Android's adaptive icon shows the centered 72 dp of its 108 dp layers.
        int crop = foreground.getWidth() * 2 / 3;
        int offset = (foreground.getWidth() - crop) / 2;
        int size = 256;
        BufferedImage icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var graphics = icon.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setColor(Color.decode(args[1]));
        graphics.fill(new RoundRectangle2D.Float(0, 0, size, size, 54, 54));
        graphics.drawImage(foreground, 0, 0, size, size, offset, offset, offset + crop, offset + crop, null);
        graphics.dispose();
        if (!ImageIO.write(icon, "png", new File(args[2]))) throw new IllegalStateException("Could not write the launcher icon");
    }
}
