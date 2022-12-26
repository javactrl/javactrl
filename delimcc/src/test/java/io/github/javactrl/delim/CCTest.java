package io.github.javactrl.delim;

import org.junit.jupiter.api.Test;

import io.github.javactrl.delimcc.CC;
import io.github.javactrl.ext.CFunction;
import io.github.javactrl.ext.CSupplier;
import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.Ctrl;
import io.github.javactrl.test.kit.Snapshot;

import static io.github.javactrl.delimcc.CC.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintStream;

import static java.lang.String.format;

@Ctrl
class CCTest {

  @Snapshot
  PrintStream out;

  final Prompt<Integer> p = new Prompt<>();

  @Test
  void orderOfExecution() throws CThrowable {
    out.println("enter: someLibraryMethodReturnsTrue");
    final int r1 = pushPrompt(p, () -> {
      out.println("pushPrompt1");
      final int t1 = withSubCont(p, sk -> {
        final var stackSize = Thread.currentThread().getStackTrace().length;
        assertEquals(stackSize, Thread.currentThread().getStackTrace().length);
        out.println("withSubCont1.0");
        final int v1 = pushSubCont(sk, () -> 100);
        assertEquals(1000, v1);
        assertEquals(stackSize, Thread.currentThread().getStackTrace().length);
        out.println(format("withSubCont1.1: v1=%d", v1));
        final int v2 = pushSubCont(sk, () -> 200);
        assertEquals(2000, v2);
        assertEquals(stackSize, Thread.currentThread().getStackTrace().length);
        out.println(format("withSubCont1.2: v2=%d", v2));
        return v1 + v2;
      });
      out.println(format("pushPrompt1: t1=%d --- executed twice", t1));
      return t1 * 10;
    });
    assertEquals(3000, r1);
    out.println(format("exit1: r1=%d", r1));
    final int r2 = pushPrompt(p, () -> {
      out.println("pushPrompt2");
      final int t1 = withSubCont(p, sk -> {
        out.println("withSubCont2");
        final int t2 = pushSubCont(sk, () -> {
          out.println("pushSubCont2.1");
          final int t3 = pushSubCont(sk, () -> {
            out.println("pushSubCont2.2");
            return 800;
          });
          out.println(format("pushSubCont2.1: t3=%s", t3));
          return t3 + 1;
        });
        out.println(format("withSubCont2: t2=%s", t2));
        return t2 + 2;
      });
      out.println(format("pushPrompt2: t1=%s --- executed twice", t1));
      return t1 + 3;
    });
    out.println(format("exit2: r2=%d", r2));
    assertEquals(809, r2);
  }

  final Prompt<String> ps = new Prompt<>("ps");

  @Test
  void shiftTest() throws CThrowable {
    assertEquals("a", pushPrompt(ps, () -> {
      final String x = shift(ps, f -> "a" + f.apply(() -> ""));
      return shift(ps, f -> x);
    }));
  }

  @Test
  void shift0Test() throws CThrowable {
    assertEquals("", pushPrompt(ps, () -> "a" + (String) pushPrompt(ps, shift0(ps, f -> ""))));
    assertEquals("a",
        pushPrompt(ps, () -> "a" + pushPrompt(ps, () -> shift0(ps, f -> f.apply(() -> shift0(ps, g -> ""))))));
  }

  @Test
  void controlTest() throws CThrowable {
    assertEquals("", pushPrompt(ps, () -> {
      final String xv = control(ps, f -> "a" + f.apply(() -> ""));
      return control(ps, g -> xv);
    }));
    assertEquals("a", pushPrompt(ps, () -> {
      final String xv = control(ps, f -> "a" + f.apply(() -> ""));
      return control(ps, g -> g.apply(() -> xv));
    }));
  }

  @Test
  void control0Test() throws CThrowable {
    assertEquals(2, pushPrompt(p, () -> {
      return control0(p, f -> f.apply(() -> 2));
    }));
  }

  @Test
  void expressionsTest() throws CThrowable {
    assertEquals(9, 4 + CC.pushPrompt(p, () -> CC.pushPrompt(p, () -> 5)));
    assertEquals(9, 4 + pushPrompt(p, () -> 6 + (int) abort(p, () -> 5)));
    assertEquals(27, 20 + pushPrompt(p, () -> {
      final int v1 = pushPrompt(p, () -> 6 + (int) abort(p, () -> 5));
      final int v2 = abort(p, () -> 7);
      return v1 + v2 + 10;
    }));
    assertEquals(35,
        20 + pushPrompt(p, () -> 10 + (int) withSubCont(p, sk -> pushPrompt(p, () -> pushSubCont(sk, () -> 5)))));
    assertEquals(35,
        20 + pushPrompt(p,
            () -> 10 + (int) withSubCont(p,
                sk -> pushSubCont(sk, () -> pushPrompt(p, () -> pushSubCont(sk, () -> abort(p, () -> 5)))))));
    assertEquals(117, 10 + pushPrompt(p, () -> 2 + (int) shift(p, sk -> 100 + sk.apply(() -> sk.apply(() -> 3)))));
    final var p2L = new Prompt<Integer>("p2L");
    final var p2R = new Prompt<Integer>("p2R");
    assertEquals(115, 10 + pushPrompt(p2L, () -> 2
        + (int) shift(p2L,
            sk -> 100 + sk.apply(() -> pushPrompt(p2R, () -> sk.apply(() -> sk.apply(() -> abort(p2R, () -> 3))))))));
    final var p1 = new Prompt<Integer>("p1");
    final var p2 = new Prompt<Integer>("p2");
    CFunction<SubCont<Integer, Integer>, Integer> pushtwice = sk -> pushSubCont(sk, () -> pushSubCont(sk, () -> 3));
    assertEquals(15, 10 + pushPrompt(p1, () -> 1 + pushPrompt(p2, () -> withSubCont(p1, pushtwice))));
    final var p3 = new Prompt<Integer>("p3");
    CFunction<SubCont<Integer, Integer>, Integer> pushtwice2 = sk -> pushSubCont(sk,
        () -> pushSubCont(sk, () -> withSubCont(p2, sk2 -> pushSubCont(sk2, () -> pushSubCont(sk2, () -> 3)))));
    assertEquals(135,
        100 + pushPrompt(p1, () -> 1 + pushPrompt(p2, () -> 10 + pushPrompt(p3, () -> withSubCont(p1, pushtwice2)))));
    CFunction<CFunction<CSupplier<Integer>, Integer>, Integer> pushtwiceF = f -> f
        .apply(() -> f.apply(() -> shift0(p2, f2 -> f2.apply(() -> f2.apply(() -> 3)))));
    assertEquals(135,
        100 + pushPrompt(p1, () -> 1 + pushPrompt(p2, () -> 10 + pushPrompt(p3, () -> shift0(p1, pushtwiceF)))));
  }
}
