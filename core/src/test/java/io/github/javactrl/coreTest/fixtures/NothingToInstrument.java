package io.github.javactrl.coreTest.fixtures;

import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.Ctrl;

@Ctrl
class NothingToInstrument {
  NothingToInstrument() throws CThrowable {

  }

  void fn1() {
  }

  void fn2() throws CThrowable {
    return;
  }

  

}