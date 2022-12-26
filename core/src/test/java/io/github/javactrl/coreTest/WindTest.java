package io.github.javactrl.coreTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.PrintStream;

import org.junit.jupiter.api.Test;

import io.github.javactrl.ext.CFunction;
import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.Ctrl;
import io.github.javactrl.rt.Unwind;
import io.github.javactrl.rt.Wind;
import io.github.javactrl.test.kit.Snapshot;
import static java.lang.String.format;

@Ctrl
public class WindTest {

  @Snapshot
  PrintStream out;

  String runWindHandlerInOneFrame(final boolean withRethrow) throws CThrowable {
    out.println(format("enter: withRethrow=%s", withRethrow));
    try {
      var ret = "default";
      try {
        try {
          ret = Unwind.brkValue("payload1");
          out.println(format("after-raise: ret=%s", ret));
        } catch (final CThrowable t) {
          out.println(format("CThrowable-catch-section: should be called only for unwind %s", t));
          assertInstanceOf(Unwind.class, t);
          throw t;
        } finally {
          out.println("finally-section: should be called only once");
        }
      } catch (final Wind w) {
        out.println(format("in-wind: ret=%s", w.value));
        if (withRethrow)
          throw w;
      }
      return format("ret1(%s)", ret);
    } catch (final Unwind e) {
      out.println(format("catch-unwind: payload=%s", e.payload));
      final var wret = (String) e.head.resume("unwind1");
      out.println(format("after-wind: wret=%s", wret));
      return format("ret2(%s)", wret);
    }
  }

  @Test
  void windHandlerInOneFrame() throws Throwable {
    assertEquals("ret2(ret1(default))", runWindHandlerInOneFrame(false));
    assertEquals("ret2(ret1(unwind1))", runWindHandlerInOneFrame(true));
  }

  String runNestedWindHandlersInOneFrame(final boolean withRethrow1, final boolean withRethrow2) throws CThrowable {
    out.println(format("enter: withRethrow1=%b, withRethrow2=%b", withRethrow1, withRethrow2));
    var count = 0;
    try {
      var ret = "default";
      try {
        try {
          ret = Unwind.brkValue("payload1");
          out.println(format("after-raise: ret=%s, count=%d", ret, count));
          count++;
        } catch (final Wind w1) {
          w1.value += "-w1";
          out.println(format("in-wind-1: ret=%s, count=%d", w1.value, count));
          count++;
          if (withRethrow1)
            throw w1;
        }
      } catch (final Wind w2) {
        w2.value += "-w2";
        out.println(format("in-wind-2: ret=%s, count=%d", w2.value, count));
        count++;
        if (withRethrow2)
          throw w2;
      }
      return format("ret1(%s,%d)", ret, count);
    } catch (final Unwind e) {
      out.println(format("catch-unwind: payload=%s, count=%d", e.payload, count));
      count++;
      final var wret = (String) e.head.resume("unwind1");
      out.println(format("after-wind: wret=%s, count=%d", wret, count));
      return format("ret2(%s)", wret);
    }
  }

  @Test
  void nestedWindHandlerInOneFrame() throws Throwable {
    assertEquals("ret2(ret1(default,2))", runNestedWindHandlersInOneFrame(false, false));
    assertEquals("ret2(ret1(default,3))", runNestedWindHandlersInOneFrame(false, true));
    assertEquals("ret2(ret1(default,2))", runNestedWindHandlersInOneFrame(true, false));
    assertEquals("ret2(ret1(unwind1-w2-w1,4))", runNestedWindHandlersInOneFrame(true, true));
  }

  String runNestedWindHandlerInSeveralFrames(final boolean wind1, final boolean wind2a,
      final boolean wind2b, final boolean wind3) throws CThrowable {
    var count = 0;
    try {
      out.println(format("enter: count=%b (%b, %b, %b, %b)", count, wind1,
          wind2a, wind2b, wind3));
      CFunction<String, String> f1 = (a) -> {
        out.println(format("enter-f1: a=%s", a));
        var count1 = 0;
        CFunction<String, String> f2 = b -> {
          var count2 = 0;
          out.println(format("enter-f2: b=%s, count2=%d", b, count2++));
          var ret = "?1";
          try {
            ret = Unwind.brkValue("payload1");
            out.println(format("exit-f2: ret=%s, count2=%d", ret, count2++));
          } catch (final Wind w) {
            out.println(format("wind1: w.value=%s, count2=%d", w.value, count2++));
            if (wind1)
              throw w;
          }
          out.println(format("after-wind1: ret=%s, count2=%d", ret, count2++));
          return ret;
        };
        var ret = "?2";
        try {
          try {
            ret = f2.apply(a);
            out.println(format("exit-f1 a=%s, count1=%d", a, count1++));
          } catch (final Wind w1) {
            out.println(format("wind2a: w.value=%s, count1=%d", w1.value, count1++));
            if (wind2b)
              throw w1;
          }
          out.println(format("after-wind2a: ret=%s, count1=%d", ret, count1++));
        } catch (final Wind w2) {
          out.println(format("wind2b: w.value=%s, count1=%d", w2.value, count1++));
          if (wind2a)
            throw w2;
        }
        out.println(format("after-wind2b: ret=%s, count=1%d", ret, count1++));
        return ret;
      };
      var ret = "?3";
      try {
        ret = f1.apply("F1");
        out.println(format("exit3: a=%s, count=%d", ret, count++));
      } catch (final Wind w) {
        out.println(format("wind3: w.value=%s, count=%d", w.value, count++));
        if (wind3)
          throw w;
      }
      out.println(format("after-wind3: ret=%s, count=%d", ret, count++));
      return format("ret1(%s,%d)", ret, count);
    } catch (final Unwind e) {
      out.println(format("catch-unwind: payload=%s, count=%d", e.payload, count++));
      final var wret = (String) e.head.resume("unwind1");
      out.println(format("after-wind: wret=%s, count=%d", wret, count++));
      return format("ret2(%s,%d)", wret, count);
    }
  }

  @Test
  void nestedWindHandlerInSeveralFrames() throws Throwable {
    assertEquals("ret2(ret1(unwind1,4),2)", runNestedWindHandlerInSeveralFrames(true, true, true, true));
    assertEquals("ret2(ret1(?1,4),2)", runNestedWindHandlerInSeveralFrames(false, true, true, true));
    assertEquals("ret2(ret1(?2,4),2)", runNestedWindHandlerInSeveralFrames(false, false, true, true));
    assertEquals("ret2(ret1(?2,4),2)", runNestedWindHandlerInSeveralFrames(false, false, false, true));
    assertEquals("ret2(ret1(?3,3),2)", runNestedWindHandlerInSeveralFrames(false, false, false, false));
  }

}
