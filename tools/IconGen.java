import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.*;

/** Render the DSH FishLogo SVG path into a 16x16 PNG plugin icon. */
public class IconGen {
  public static void main(String[] args) throws Exception {
    String tsx = Files.readString(Path.of(args[0]));
    Matcher m = Pattern.compile("d=\"([^\"]+)\"").matcher(tsx);
    if (!m.find()) { System.err.println("no path found"); System.exit(1); }
    String d = m.group(1);

    // ---- parse M / L / C / Z ----
    Path2D.Float p = new Path2D.Float();
    Matcher seg = Pattern.compile("([MLCZ])([^MLCZ]*)").matcher(d);
    double lastX = 0, lastY = 0;
    while (seg.find()) {
      char cmd = seg.group(1).charAt(0);
      String body = seg.group(2).trim();
      if (body.isEmpty() && cmd != 'Z') continue;
      if (cmd == 'Z') { p.closePath(); continue; }
      double[] n = nums(body);
      int i = 0;
      switch (cmd) {
        case 'M':
          p.moveTo(n[i], n[i + 1]); lastX = n[i]; lastY = n[i + 1]; i += 2;
          while (i + 1 < n.length) { p.lineTo(n[i], n[i + 1]); lastX = n[i]; lastY = n[i + 1]; i += 2; }
          break;
        case 'L':
          while (i + 1 < n.length) { p.lineTo(n[i], n[i + 1]); lastX = n[i]; lastY = n[i + 1]; i += 2; }
          break;
        case 'C':
          while (i + 5 < n.length) {
            p.curveTo(n[i], n[i + 1], n[i + 2], n[i + 3], n[i + 4], n[i + 5]);
            lastX = n[i + 4]; lastY = n[i + 5]; i += 6;
          }
          break;
      }
    }

    // ---- render 32x32 (fish 23.16x17.04 scaled to 28 wide, centered, transparent bg) ----
    int size = 32;
    double scale = 28.0 / 23.16;
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g.translate(2.0, (size - 17.04 * scale) / 2.0);
    g.scale(scale, scale);
    g.setColor(Color.BLACK);
    g.fill(p);
    g.dispose();

    File out = new File(args[1]);
    javax.imageio.ImageIO.write(img, "png", out);
    System.out.println("icon written: " + out.getAbsolutePath() + " " + out.length() + " bytes");
  }

  private static double[] nums(String s) {
    Matcher m = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(s);
    java.util.List<Double> list = new java.util.ArrayList<>();
    while (m.find()) list.add(Double.parseDouble(m.group()));
    double[] r = new double[list.size()];
    for (int i = 0; i < r.length; i++) r[i] = list.get(i);
    return r;
  }
}
