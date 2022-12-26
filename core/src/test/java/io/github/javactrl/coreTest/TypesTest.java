package io.github.javactrl.coreTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.PrintStream;

import org.junit.jupiter.api.Test;

import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.Ctrl;
import io.github.javactrl.rt.Unwind;
import io.github.javactrl.rt.Wind;
import io.github.javactrl.test.kit.Snapshot;
import static java.lang.String.format;

@Ctrl
public class TypesTest {

  @Snapshot
  PrintStream out;

  static int someLoop(PrintStream out, int num) throws CThrowable, IOException {
    int acc = 0;
    out.println("enter-loop");
    for (var i = 0; i < num; ++i) {
      out.println(format("before-suspend: i=%d, acc=%d", i, acc));
      int val = Unwind.brkValue(i);
      acc += val;
      out.println(format("after-suspend: i=%d, val=%d, acc=%d", i, val, acc));
    }
    out.println(format("exit-loop: acc=%d", acc));
    return acc;
  }

  static int someLoop(PrintStream out) throws CThrowable, IOException {
    out.println("loop-wrap");
    final var acc = someLoop(out, 10);
    out.println("loop-wrap-exit");
    return acc;
  }

  int loopWithoutRecursion() throws CThrowable, IOException {
    try {
      final var ret = someLoop(out);
      out.println("loop-result: %ret");
      return ret;
    } catch (Unwind u) {
      for (;;) {
        try {
          var depth = 0;
          for (var i = u.head; i != null; i = i.next)
            depth++;
          assertEquals(2, depth);
          out.println(format("before-resume: depth=%d, u.payload=%s", depth, u.payload));
          final var ret = u.head.next.resume(((int) u.payload) * 10);
          out.println(format("after-resume: res=%s -- executed once", ret));
          return (int) ret;
        } catch (final Unwind w) {
          u = w;
          continue;
        }
      }
    }
  }

  static class BooleanUnwind extends Unwind {
    boolean value;

    BooleanUnwind(boolean value) {
      this.value = value;
    }
  }

  static class CharUnwind extends Unwind {
    char value;

    CharUnwind(char value) {
      this.value = value;
    }
  }

  static class ByteUnwind extends Unwind {
    byte value;

    ByteUnwind(byte value) {
      this.value = value;
    }
  }

  static class ShortUnwind extends Unwind {
    short value;

    ShortUnwind(short value) {
      this.value = value;
    }
  }

  static class IntUnwind extends Unwind {
    int value;

    IntUnwind(int value) {
      this.value = value;
    }
  }

  static class LongUnwind extends Unwind {
    long value;

    LongUnwind(long value) {
      this.value = value;
    }
  }

  static class FloatUnwind extends Unwind {
    float value;

    FloatUnwind(float value) {
      this.value = value;
    }
  }

  static class DoubleUnwind extends Unwind {
    double value;

    DoubleUnwind(double value) {
      this.value = value;
    }
  }

  @Ctrl
  class PrimTypes {
    boolean enabled = true;
    boolean mbool = true;
    char mc = 2;
    byte mb = 3;
    short ms = 4;
    int mi = 5;
    long ml = 6;
    float mf = 7;
    double md = 8;

    boolean plusBoolean(boolean p) throws CThrowable {
      out.println(format("plusBoolean: p=%b", p));
      boolean l = enabled ? Unwind.brk(new BooleanUnwind(p)) : p || mbool;
      mbool = !mbool;
      out.println(format("after-plusBoolean: p=%b, l=%b, mc=%b", p, l, mb));
      return p && l;
    }

    char plusChar(char p) throws CThrowable {
      out.println(format("plusChar: p=%d", (int) p));
      char l = enabled ? Unwind.brk(new CharUnwind(p)) : (char) (p + mc);
      mc++;
      out.println(format("after-plusChar: p=%d, l=%d, mc=%d", (int) p, (int) l, (int) mc));
      return (char) ((byte) p + (byte) l);
    }

    byte plusByte(byte p) throws CThrowable {
      out.println(format("plusByte: p=%d", p));
      byte l = enabled ? Unwind.brk(new ByteUnwind(p)) : (byte) (p + mb);
      mb++;
      out.println(format("after-plusByte: p=%d, l=%d, mi=%d", p, l, mb));
      return (byte) (p + l);
    }

    short plusShort(short p) throws CThrowable {
      out.println(format("plusShort: p=%d", p));
      short l = enabled ? Unwind.brk(new ShortUnwind(p)) : (short) (p + ms);
      ms++;
      out.println(format("after-plusShort: p=%d, l=%d, ms=%d", p, l, ms));
      return (byte) (p + l);
    }

    int plusInt(int p) throws CThrowable {
      out.println(format("plusInt: p=%d", p));
      int l = enabled ? Unwind.brk(new IntUnwind(p)) : p + mi;
      mi++;
      out.println(format("after-plusInt: p=%d, l=%d, mi=%d", p, l, mi));
      return p + l;
    }

    long plusLong(long p) throws CThrowable {
      out.println(format("plusLong: p=%d", p));
      long l = enabled ? Unwind.brk(new LongUnwind(p)) : p + ml;
      ml++;
      out.println(format("after-plusLong: p=%d, l=%d, ml=%d", p, l, ml));
      return p + l;
    }

    float plusFloat(float p) throws CThrowable {
      out.println(format("plusFloat: p=%f", p));
      float l = enabled ? Unwind.brk(new FloatUnwind(p)) : p + mf;
      mf++;
      out.println(format("after-plusFloat: p=%f, l=%f, mf=%f", p, l, mf));
      return p + l;
    }

    double plusDouble(double p) throws CThrowable {
      out.println(format("plusDouble: p=%f", p));
      double l = enabled ? Unwind.brk(new DoubleUnwind(p)) : p + md;
      md++;
      out.println(format("after-plusDouble: p=%f, l=%f, md=%f", p, l, md));
      return p + l;
    }

    int plus(byte b1, char c1, short s1, int i1, long l1, float f1, double d1,
        byte b2, boolean bool, double d2, float f2, long l2, int i2, short s2, char c2) throws CThrowable {
      return (int) (plusByte(b1) + plusInt(i1) + (int) plusChar(c1) +
          (plusBoolean(bool) ? plusLong(l1) : plusChar(c2))
          + plusFloat(f1) + plusShort(s1) + plusInt(i2) + plusLong(l2) + plusDouble(d1)
          + plusFloat(f2) + (plusBoolean(bool) ? plusChar(c2) : plusLong(l1)) +
          plusInt(i2) + (byte) plusChar(c2)
          + plusShort(s2) +
          plusDouble(d2));
    }

    int main() throws CThrowable {
      try {
        var bool = mbool;
        var b1 = mb;
        var c1 = mc;
        var s1 = ms;
        var i1 = mi;
        var l1 = ml;
        var f1 = mf;
        var d1 = md;
        var d2 = md;
        var l2 = ml;
        var i2 = mi;
        var s2 = ms;
        var c2 = mc;
        var b2 = mb;
        var f2 = mf;
        try {
          out.println(format("before: %b %d %d %d %d %d %f %f", mbool, (int) mc, mb, ms, mi, ml, mf, md));
          return plus(b1, c1, s1, i1, l1, f1, d1, b2, bool, d2, f2, l2, i2, s2, c2);
        } finally {
          assertEquals(true, mbool);
          assertEquals(5, mc);
          assertEquals(4, mb);
          assertEquals(6, ms);
          assertEquals(8, mi);
          assertEquals(8, ml);
          assertEquals(9, mf);
          assertEquals(10, md);
          out.println(format("after: %b %d %d %d %d %d %f %f", mbool, (int) mc, mb, ms, mi, ml, mf, md));
        }
      } catch (final BooleanUnwind ub) {
        out.println(format("booleanUnwind: %b %b", ub.value, mbool));
        return ub.head.resume(ub.value || mbool);
      } catch (final CharUnwind uc) {
        out.println(format("charUnwind: %d", (int) uc.value));
        return uc.head.resume((char) (uc.value + mc));
      } catch (final ByteUnwind ub) {
        out.println(format("byteUnwind: %d", ub.value));
        return ub.head.resume((byte) (ub.value + mb));
      } catch (final ShortUnwind us) {
        out.println(format("shortUnwind: %d", us.value));
        return us.head.resume((short) (us.value + ms));
      } catch (final IntUnwind ui) {
        out.println(format("intUnwind: %d", ui.value));
        return ui.head.resume(ui.value + mi);
      } catch (final LongUnwind ul) {
        out.println(format("longUnwind: %d", ul.value));
        return ul.head.resume(ul.value + ml);
      } catch (final FloatUnwind uf) {
        out.println(format("floatUnwind: %f", uf.value));
        return uf.head.resume(uf.value + mf);
      } catch (final DoubleUnwind ud) {
        out.println(format("doubleUnwind: %f", ud.value));
        return ud.head.resume(ud.value + md);
      }
    }
  }

  @Test
  void primTypesTest() throws CThrowable {
    final var obj = new PrimTypes();
    assertEquals(232, obj.main());
  }

  void unwindSameCall(String args[]) throws CThrowable, Exception {
    try {
      try {
        out.println("start");
        final int ret = Unwind.brkValue("Some Payload");
        out.println(format("after raise: %s", ret));
      } catch (final Unwind e) {
        out.println(format("unwind 1: %s", e.payload));
        e.head.next.resume(42);
        e.head.next.resume(43);
        try {
          e.head.wind(Wind.createThrow(new Exception("throw 1")));
        } catch (final Exception e1) {
          if (e1.getMessage().equals("throw 2"))
            throw new Exception("throw 3");
          e1.printStackTrace();
          throw e1;
        }
      }
    } catch (final Exception e2) {
      out.println(format("Exeption %s", e2.toString()));
      if (e2.getMessage().equals("throw 1"))
        throw new Exception("throw 2");
      e2.printStackTrace();
      throw e2;
    } finally {
      out.println("done!");
    }
  }

  static int[] plusLB(long[] a, int c, long d, int[] b) throws CThrowable {
    final var len = Math.min(a.length, b.length);
    final var ret = new int[len];
    for (int i = 0; i < len; ++i)
      ret[i] = (int) a[i] + (int) Unwind.brkValue(b[i]) + c * (int) d;
    return ret;
  }

  private static int[] plusSI(short[] a, int[] b) throws CThrowable {
    final var len = Math.min(a.length, b.length);
    final var ret = new int[len];
    for (int i = 0; i < len; ++i)
      ret[i] = (int) Unwind.brkValue(b[i]) + (int) a[i];
    return ret;
  }

  @Test
  void primTypeArrays() throws CThrowable {
    try {
      final var ia = new int[] { 1, 2, 3 };
      final var sa = new short[] { 10, 20, 30 };
      final var la = new long[] { 100, 200, 300 };
      var i = 0;
      for (final var v : plusLB(la, 99, (long) 3, plusSI(sa, ia))) {
        out.println(format("arr[%d]=%d, %d, %d, %d", i, ia[i], sa[i], la[i], v));
        i++;
      }
    } catch (Unwind w) {
      w.head.resume(((int) w.payload) * 10);
    }
  }

  class SomeObj1 {
    byte b;
    long l;
    SomeObj1 s1;
    SomeObj1 s2;

    SomeObj1() {
    }

    SomeObj1(byte b) throws CThrowable {
      this.b = b;
    }

    SomeObj1(long l, SomeObj1 s1, SomeObj1 s2) {
      this.l = l;
      this.s1 = s1;
      this.s2 = s2;
    }

    public String toString() {
      return format("O1(%s, %s, %s, %s)", b, l, s1, s2);
    }
  };

  String instrNewNew() throws CThrowable {
    final var t = new SomeObj1(longTest1(), new SomeObj1((byte) 10), new SomeObj1(booTest1() ? byTest1() : 2));
    return t.toString();
  }

  boolean enabled = false;

  byte byTest1() throws Unwind {
    if (enabled)
      throw new Unwind((byte) -1);
    return -1;
  }

  boolean booTest1() throws Unwind {
    if (enabled)
      throw new Unwind(true);
    return true;
  }

  long longTest1() throws Unwind {
    if (enabled)
      throw new Unwind(100L);
    return 100;
  }

  double doubleTest1() throws Unwind {
    if (enabled)
      throw new Unwind(100.1);
    return 100.1;
  }

  String constrArgsTestRun() throws CThrowable {
    try {
      return instrNewNew();
    } catch (Unwind w) {
      out.println("constr-arg-unwind");
      return w.head.resume(w.payload);
    }
  }

  @Test
  void constrArgsTest() throws CThrowable {
    assertEquals("O1(0, 100, O1(10, 0, null, null), O1(-1, 0, null, null))", constrArgsTestRun());
    enabled = true;
    assertEquals("O1(0, 100, O1(10, 0, null, null), O1(-1, 0, null, null))", constrArgsTestRun());
  }

}
