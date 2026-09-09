import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Tiny offline jar/zip builder: creates a standard jar (or zip) from a directory,
 * using '/' path separators, so the JVM can load classes from it.
 * Usage: java MakeJar <srcDir> <outputFile>
 */
public class MakeJar {
  public static void main(String[] args) throws Exception {
    Path src = Paths.get(args[0]);
    Path out = Paths.get(args[1]);
    if (!Files.isDirectory(src)) {
      System.err.println("source is not a directory: " + src);
      System.exit(2);
    }
    Files.createDirectories(out.getParent());
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {
      try (Stream<Path> paths = Files.walk(src)) {
        paths.filter(Files::isRegularFile).sorted().forEach(p -> {
          String entry = src.relativize(p).toString().replace('\\', '/');
          try {
            jos.putNextEntry(new JarEntry(entry));
            jos.write(Files.readAllBytes(p));
            jos.closeEntry();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
      }
    }
    System.out.println("created " + out + " (" + Files.size(out) + " bytes)");
  }
}
