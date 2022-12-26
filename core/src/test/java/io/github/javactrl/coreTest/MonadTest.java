package io.github.javactrl.coreTest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.github.javactrl.ext.CSupplier;
import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.Ctrl;
import io.github.javactrl.rt.Unwind;

@Ctrl
public class MonadTest {
  public static <T> Stream<T> run(final CSupplier<T> s) throws CThrowable {
    try {
      return Stream.of(s.get());
    } catch (final Unwind e) {
      return ((Stream<?>) e.payload).flatMap(v -> e.head.resumeTop(v));
    }
  }

  @Test
  public void streamMonadTest() throws Throwable {
    final var seq = run(() -> {
      final int x = Unwind.brkValue(Stream.of(1, 2, 3));
      final int y = Unwind.brkValue(Stream.of(4, 5, 6));
      final int ret = x + y;
      if (ret % 2 == 1)
        Unwind.brkValue(Stream.empty());
      return ret;
    }).toArray(Integer[]::new);
    assertArrayEquals(new Integer[] { 6, 6, 8, 8 }, seq);
  }
}
