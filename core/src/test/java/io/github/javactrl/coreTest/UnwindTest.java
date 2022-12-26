package io.github.javactrl.coreTest;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import static org.junit.jupiter.api.Assertions.*;

import io.github.javactrl.ext.CFunction;
import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.CallFrame;
import io.github.javactrl.rt.Ctrl;
import io.github.javactrl.rt.Unwind;
import io.github.javactrl.rt.Wind;
import io.github.javactrl.test.kit.Snapshot;
import static java.lang.String.format;

@Ctrl
class UnwindTest {

  @Snapshot
  PrintStream out;

  String runOneUnwindOneCall() throws CThrowable {
    var unwound = false;
    try {
      out.println(format("init: unwound=%b", unwound));
      final String ret = Unwind.brkValue("payload1");
      out.println(format("after-raise: ret=%s, unwound=%b", ret, unwound));
      return format("ret1(%s)", ret);
    } catch (final Unwind e) {
      assertEquals(e.head.methodName, "runOneUnwindOneCall", "the test isn't instrumented");
      out.println(format("catch-unwind: payload=%s, unwound=%b", e.payload, unwound));
      unwound = true;
      final String wret = (String) e.head.wind(Wind.createReturn("unwind1"));
      out.println(format("after-wind: wret=%s, unwound=%b", wret, unwound));
      return format("ret2(%s)", wret);
    }
  }

  @Test
  void oneUnwindOneCall() throws CThrowable {
    assertEquals(runOneUnwindOneCall(), "ret2(ret1(unwind1))");
  }

  static String f2B(final String b) throws CThrowable {
    return Unwind.brkValue(b);
  }

  String f2A(final String b) throws CThrowable {
    final CFunction<String, String> fret = UnwindTest::f2B;
    out.println(format("enter-f2: b=%s", b));
    final String ret1 = Unwind.brkValue("payload1");
    out.println(format("middle-f2: b=%s, ret1=%s", b, ret1));
    final String ret2 = fret.apply("payload1");
    out.println(format("exit-f2: ret1=%s, ret2=%s", ret1, ret2));

    return format("retF2(%s,%s)", ret1, ret2);
  }

  String runUnwindSeveralNestedCalls(PrintStream out) throws CThrowable {
    var unwound = false;
    var count = 0;
    try {
      out.println(format("init: unwound=%b, count=%d", unwound, count));
      CFunction<String, String> f1 = a -> {
        out.println(format("enter-f1: a=%s", a));
        CFunction<String, String> f2 = this::f2A;
        final var ret = f2.apply(a);
        out.println(format("exit-f1: a=%s", a));
        return format("retF1(%s)", ret);
      };
      final var ret = f1.apply(format("A1(%d)", count++));
      out.println(format("exit1: a=%s, count=%d", ret, count++));
      return format("retBody(%s,%d)", ret, count);
    } catch (Unwind e) {
      out.println(format("catch-unwind: payload=%s, unwound=%b, count=%d", e.payload, unwound, count++));
      unwound = true;
      final String wret = (String) e.head.wind(Wind.createReturn(format("unwind%d", count++)));
      out.println(format("after-wind: wret=%s, unwound=%b, count=%d", wret, unwound, count++));
      return format("retHandler(%s,%d)", wret, count);
    }
  }

  @Test
  void unwindSeveralNestedCalls() throws CThrowable {
    assertEquals("retHandler(retHandler(retBody(retF1(retF2(unwind2,unwind4)),6),6),4)",
        runUnwindSeveralNestedCalls(out));
  }

  @Test
  void unwindSkipsFinallyAndGenericCatch() throws CThrowable {
    try {
      out.println("enter");
      try {
        Unwind.brkValue(null);
        out.println("after-suspend");
      } catch (final Throwable t) {
        out.println("in-generic-catch: never executed");
        throw t;
      } finally {
        out.println("in-finally - executed once");
      }
    } catch (final Unwind e) {
      out.println("catch-unwind");
      e.head.wind(Wind.createReturn(null));
      out.println("after-wind");
    }
  }

  @Test
  void unusedVarsCleanup() throws CThrowable {
    final var obj = new Object();
    try {
      final var o1 = obj;
      Unwind.brkValue("payload1");
      throw new AssertionFailedError(o1.toString());
    } catch (final Unwind u1) {
      final var frame = u1.head;
      final var vl = Arrays.asList(frame.v);
      final var objs = Arrays.stream(frame.v).filter((Object i) -> i == obj).toArray();
      assertEquals(objs.length, 2);
      out.println(format("Stage1: %d, %d", vl.indexOf(obj), vl.lastIndexOf(obj)));
    }
    try {
      final var o2 = obj;
      Unwind.brkValue("payload2");
      throw new AssertionFailedError(o2.toString());
    } catch (final Unwind u2) {
      final var frame = u2.head;
      final var vl = Arrays.asList(frame.v);
      final var objs = Arrays.stream(frame.v).filter((Object i) -> i == obj).toArray();
      assertEquals(objs.length, 2);
      out.println(format("Stage1: %d, %d", vl.indexOf(obj), vl.lastIndexOf(obj)));
    }
  }

  String runSeveralUnwindsOnOneFrame() throws CThrowable {
    var counter = 0;
    try {
      out.println(format("enter: unwound=%d", counter));
      final String ret1 = Unwind.brkValue(format("payload%d", counter));
      out.println(format("after-raise1: ret1=%s, unwound=%d", ret1, counter));
      final Object ret2 = Unwind.brkValue("payload1");
      out.println(format("after-raise2: ret1=%s, ret2=%s, unwound=%d", ret1, ret2, counter));
      return "ret1(" + ret1 + "," + ret2 + ")";
    } catch (final Unwind e) {
      out.println(format("catch-unwind: payload=%s, unwound=%d", e.payload, counter));
      counter++;
      final String wret = (String) e.head.resume("unwind" + counter);
      out.println(format("after-wind: wret=%s, unwound=%b", wret, counter));
      return "ret2(" + wret + "," + counter + ")";
    }
  }

  @Test
  void severalUnwindsOnOneFrame() throws Throwable {
    assertEquals(runSeveralUnwindsOnOneFrame(), "ret2(ret2(ret1(unwind1,unwind2),2),1)");
  }

  static <T, U> T raiseIOEx(U payload) throws CThrowable, IOException {
    throw new Unwind(payload);
  }

  static <R> R windIOEx(CallFrame frame, Wind token) throws CThrowable, IOException {
    return frame.wind(token);
  }

  String runUnwindSameCallError() throws CThrowable {
    var unwound = false;
    int ret = 0;
    try {
      out.println(format("enter: unwound=%b", unwound));
      try {
        ret = raiseIOEx("payload1");
        out.println(format("after-raise: ret=%s, unwound=%b", ret,
            unwound));
      } catch (final IOException e) {
        out.println(format("in-catch-io-exception: raise: ret=%d, unwound=%b, e=%s", ret, unwound, e));
        return format("ret1(%d,%s)", ret, e);
      }
    } catch (final Unwind e) {
      out.println(format("unwindSame:catch unwind: payload=%s, unwound=%b", e.payload, unwound));
      final var retE = e.head.resumeThrow(new IOException(format("unwind1(%s)", e.payload)));
      out.println("exit-unwind");
      return format("ret2(%d,%s,%s)", ret, e.payload, retE);
    }
    throw new AssertionFailedError("Shouldn't happen");
  }

  @Test
  void unwindSameCallError() throws CThrowable {
    assertEquals("ret2(0,payload1,ret1(0,java.io.IOException: unwind1(payload1)))", runUnwindSameCallError());
  }

  @Test
  void exceptionInBody() throws CThrowable, IOException {
    try {
      out.println("enter");
      try {
        raiseIOEx(null);
        out.println("after-raise");
        throw new IOException("throw-in-body");
      } catch (final Throwable t) {
        out.println(format("in-generic-catch: %s, %s", t.getClass().getSimpleName(), t.getMessage()));
        throw t;
      } finally {
        out.println("in-finally - executed once");
      }
    } catch (final Unwind e) {
      out.println("catch-unwind");
      try {
        windIOEx(e.head, Wind.createReturn(null));
        out.println("after-wind: never executed");
      } catch (IOException ioe) {
        out.println(format("resume-catch: %s", ioe.getMessage()));
      }
      out.println("after-wind");
    }
  }

  @Test
  void exceptionInExceptionHandler() throws CThrowable, IOException {
    try {
      out.println("enter");
      try {
        // Unwind.Raise.raiseE(null);
        raiseIOEx(null);
        out.println("after-raise - never executed");
      } catch (final Throwable t) {
        out.println(format("in-generic-catch: %s, %s", t.getClass().getSimpleName(), t.getMessage()));
        throw t;
      } finally {
        out.println("in-finally - executed once");
      }
    } catch (final Unwind e) {
      out.println("catch-unwind");
      try {
        windIOEx(e.head, Wind.createThrow(new IOException("resuming")));
        out.println("after-wind: never executed");
      } catch (IOException ioe) {
        out.println(format("resume-catch: %s", ioe.getMessage()));
      }
      out.println("after-wind");
    }
  }
}
