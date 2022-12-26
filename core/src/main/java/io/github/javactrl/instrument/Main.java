package io.github.javactrl.instrument;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.security.ProtectionDomain;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;
import static java.lang.String.format;

/** Entry point to JavaAgent and AOT instrumentation tool */
public class Main {

  /** dump instrumented classes */
  public static boolean DEBUG = false;

  /** preliminary filter for classes we don't want to instrument */
  public static Predicate<String> predicate = Pattern
      .compile("(jdk/|java/|sun/|org/junit/|org/gradle/|worker/org/gradle/|com/sun/|io/github/javactrl/rt/).*")
      .asMatchPredicate();

  /** predicate for method call */
  public static CallPredicate defaultCallPredicate = (owner, name) -> {
    if (owner.startsWith("io/github/javactrl/rt"))
      return name.startsWith("brk") || name.equals("resume");
    return !predicate.test(owner);
  };

  /**
   * Java agent entry point
   * 
   * @param agentArgs Java agent arguments
   * @param inst instrumentation interface 
   * @throws Exception on instrumentation errors
   */
  public static void premain(final String agentArgs, final Instrumentation inst)
      throws Exception {
    if (agentArgs != null) {
      final var args = agentArgs.split(",");
      for (final var arg : args) {
        if (arg.equals("check"))
          Transform.CHECK = true;
        else if (arg.equals("debug"))
          DEBUG = true;
      }
    }
    Transform.callPredicate = defaultCallPredicate;
    inst.addTransformer(new ClassFileTransformer() {
      @Override
      public byte[] transform(ClassLoader loader, String className,
          Class<?> classBeingRedefined,
          ProtectionDomain protectionDomain, byte[] data)
          throws IllegalClassFormatException {
        if (className.endsWith("PrimTypes"))
            System.out.println("P");
        if (predicate.test(className))
          return data;
        try {
          if (DEBUG) {
            System.out.println(format("instrumenting %s...", className));
            final var dumpFile = new File(format("_dumps_\\%s-in.class", className));
            dumpFile.getParentFile().mkdirs();
            Files.write(dumpFile.toPath(), data);
            debDump(dumpFile);
        }
          final var instrumented = Transform.instrumentClass(data);
          if (DEBUG) {
            if (instrumented != null) {
              final var dumpFile = new File(format("_dumps_\\%s-out.class", className));
              dumpFile.getParentFile().mkdirs();
              System.out.println(String.format("instrumented %s (dumped into %s)",className, dumpFile.getAbsolutePath()));
              Files.write(dumpFile.toPath(), instrumented);
              debDump(dumpFile);
            } else {
              System.out.println(format("not instrumented %s", className));
            }
          } 
          return instrumented;
        } catch (Throwable e) {
          e.printStackTrace();
          return data;
        }
      }
    });
  }

  /**
   * 
   * @param file path to a class file to dump
   * @throws IOException on IO problems
   */
  private static void debDump(File file) throws IOException {
    var cr = new ClassReader(Files.readAllBytes(file.toPath()));
    cr.accept(new TraceClassVisitor(new PrintWriter(file.toString() + ".txt")),
        ClassReader.EXPAND_FRAMES);
    var traceClassVisitor = new TraceClassVisitor(null, new ASMifier(),
        new PrintWriter(file.toString() + ".java"));
    cr.accept(traceClassVisitor, ClassReader.EXPAND_FRAMES);
  }

  /**
   * Instrumenting a class file 
   * 
   * @param inFile input class file
   * @param outFile output class file
   * @throws IOException on IO errors
   */
  public static void instrumentClass(final File inFile, final File outFile) throws IOException {
    Transform.callPredicate = defaultCallPredicate;
    final var inData = Files.readAllBytes(inFile.toPath());
    var outData = Transform.instrumentClass(inData);
    if (outData == null)
      outData = inData;
    Files.write(outFile.toPath(), outData);
    debDump(outFile);
  }

  /**
   * Command line entry point
   * 
   * @param args command line arguments
   */
  public static void main(String args[]) {
    File inputFile = null;
    File outputFile = null;
    for (var i = 0; i < args.length; ++i) {
      final var arg = args[i];
      if (arg.startsWith("-")) {
        switch (arg) {
          case "-debug":
            DEBUG = true;
            continue;
          case "-check":
            Transform.CHECK = true;
            continue;
          case "-?":
          case "-help":
            usage();
            continue;
          default:
            System.err.println(format("Unknown option %s", arg));
            usage();
            return;
        }
      }
      if (inputFile == null) {
        if (!arg.endsWith(".class")) {
          System.err.println("Only .class files are implemented now");
          usage();
          return;
        }
        inputFile = new File(arg);
      } else {
        if (!arg.endsWith(".class")) {
          System.err.println("Output file type must be the same as input");
          usage();
          return;
        }
        outputFile = new File(arg);
      }
    }
    if (inputFile == null || outputFile == null) {
      System.err.println("No input or ouput files");
      usage();
      return;
    }
    inputFile = inputFile.getAbsoluteFile();
    outputFile = outputFile.getAbsoluteFile();
    try {
      instrumentClass(inputFile, outputFile);
    } catch (IOException e) {
      System.err.println("instrumentation error: " + e.toString());
      e.printStackTrace();
    }
  }

  /** Prints usage */
  public static void usage() {
    System.err.println("java -jar <this jar>.jar  [-check] <input file>.class [<output file>.class]");
    System.exit(-1);
  }
}
