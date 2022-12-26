/** 
 * JUnit 5 extensions for testing control flows
 * 
 * It injects a `PrintStream` stream field into a test class. Everything output 
 * there is simply stored (overwritten) into "src/test/resources/snapshots" folder.
 * This way, git can be used to manage changes.
 */
package io.github.javactrl.test.kit;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.opentest4j.AssertionFailedError;

public class OutputSnapshotExtension implements BeforeEachCallback, AfterEachCallback  {

  private static final String snapshotsFolder = "src/test/resources/snapshots";
  private PrintStream out;
  private String snapshotFile;

  @Override
  public void beforeEach(final ExtensionContext context) throws Exception {
    final var testClass = context.getRequiredTestClass();
    final var classFolder = String.format("%s/%s", snapshotsFolder, testClass.getName().replace('.', '/'));
    Files.createDirectories(Path.of(classFolder));
    snapshotFile = String.format("%s/%s.txt", classFolder, context.getRequiredTestMethod().getName());
    final var field = AnnotationSupport.findAnnotatedFields(testClass,Snapshot.class).get(0);
    field.setAccessible(true);
    field.set(context.getRequiredTestInstance(), out = new PrintStream(snapshotFile));
  }

  @Override
  public void afterEach(final ExtensionContext context) throws Exception {
    out.close();
    if (Runtime.getRuntime().exec("git diff --ignore-space-at-eol --exit-code --quiet "+snapshotFile).waitFor() != 0)
      throw new AssertionFailedError("changed snapshot file, check `git diff <snapshots folder>` for details");
  }
}