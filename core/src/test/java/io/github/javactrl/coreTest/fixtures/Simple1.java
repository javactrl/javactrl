package io.github.javactrl.coreTest.fixtures;

import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.Ctrl;

@Ctrl
class Simple1 {
  class SomeObj1 {
    SomeObj1(byte b) throws CThrowable {

    }
    SomeObj1(SomeObj1 t) {}
  };

  void instrNewNew() throws CThrowable {
    /*final var t =*/ new SomeObj1(new SomeObj1(byTest1()));
    // new SomeObj1(new SomeObj1(byTest1()));
  }

  byte byTest1() throws CThrowable {
    return 1;
  }

  public static void main(String[] args) {
    try {
      final var s1 = new Simple1();
      s1.instrNewNew();
    } catch (CThrowable e) {
      e.printStackTrace();
    }
  }
}
