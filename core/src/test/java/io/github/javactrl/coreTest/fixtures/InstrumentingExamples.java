package io.github.javactrl.coreTest.fixtures;

import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.Ctrl;

@Ctrl
class InstrumentingExamples {
  InstrumentingExamples() throws CThrowable {
  }

  public static InstrumentingExamples constr() throws CThrowable {
    return new InstrumentingExamples();
  }
}