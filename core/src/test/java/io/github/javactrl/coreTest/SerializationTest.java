package io.github.javactrl.coreTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.CallFrame;
import io.github.javactrl.rt.Ctrl;
import io.github.javactrl.rt.Unwind;
import io.github.javactrl.test.kit.Snapshot;
import static java.lang.String.format;

@Ctrl
public class SerializationTest {

  @Snapshot
  PrintStream out;

  static class OutPlaceholder implements Serializable {
    PrintStream getOut(SerializationTest t) {
      return t.out;
    }
  };

  static final OutPlaceholder outPlaceholder = new OutPlaceholder();

  void prepareWrite(CallFrame frame) {
    for (var i = frame; i != null; i = i.next) {
      for (var j = 0; j < i.v.length; ++j) {
        if (i.v[j] == out)
          i.v[j] = outPlaceholder;
      }
    }
  }

  void prepareRead(CallFrame frame) {
    for (var i = frame; i != null; i = i.next) {
      for (var j = 0; j < i.v.length; ++j) {
        if (i.v[j] instanceof OutPlaceholder)
          i.v[j] = out;
      }
    }
  }

  int writeContinuation(File dumpFile) throws CThrowable, IOException {
    try {
      final var ret = TypesTest.someLoop(out);
      out.println("loop-result: %ret");
      return ret;
    } catch (Unwind u) {
      for (;;) {
        try {
          out.println(format("before-resume: u.payload=%s", u.payload));
          final var cur = (int) u.payload;
          if (cur == 4) {
            prepareWrite(u.head.next);
            try (final var stream = new FileOutputStream(dumpFile);
                final var objStream = new ObjectOutputStream(stream)) {
              objStream.writeObject(u.head.next);
            }
            prepareRead(u.head.next);
          }
          final var ret = u.head.next.resume(cur * 10);
          out.println(format("after-resume: res=%s -- executed once", ret));
          return (int) ret;
        } catch (final Unwind w) {
          u = w;
          continue;
        }
      }
    }
  }

  int readContinuation(File dumpFile) throws CThrowable, IOException, ClassNotFoundException {
    CallFrame frame;
    try (final var stream = new FileInputStream(dumpFile);
        final var objStream = new ObjectInputStream(stream);) {
      frame = (CallFrame) objStream.readObject();
    }
    prepareRead(frame);
    var cur = 4;
    for (;;) {
      try {
        out.println(format("before-resume-saved: u.payload=%d", cur));
        final int ret = frame.resume(cur * 10);
        out.println(format("after-resume-saved: res=%s -- executed once", ret));
        return ret;
      } catch (final Unwind w) {
        frame = w.head.next;
        cur = (int) w.payload;
        continue;
      }
    }
  }

  @Test
  void continuationSerialization(final @TempDir Path dir) throws Throwable {
    final var dumpFile = dir.resolve("file.dump").toFile();
    assertEquals(450, writeContinuation(dumpFile));
    assertEquals(450, readContinuation(dumpFile));
  }
}
