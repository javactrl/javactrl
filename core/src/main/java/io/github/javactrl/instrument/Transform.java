package io.github.javactrl.instrument;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnalyzerAdapter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.util.CheckClassAdapter;

import static org.objectweb.asm.Opcodes.*;
import static java.lang.String.format;

/**
 * Byte code instrumentation
 */
public class Transform {
  private Transform() {
    throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
  }
  /** Checks generated IR */
  public static boolean CHECK = true;
  /** Anotation class to enable this instrumentation for a class */
  public static String enablingAnnotation = "Lio/github/javactrl/rt/Ctrl;";
  /**  The predicate to select methods to instrument */
  public static CallPredicate callPredicate = (owner, name) -> false;
  /** Throws declaration for enabling instrumentation for a method */
  public static final String CTRL_TOKEN = "io/github/javactrl/rt/CThrowable";

  private static class FieldDescr {
    /* tracks usage for each state */
    int count = 0;
    /* number of opstack variables with this type */
    int stack = 0;
    /* number of local variables with this type */
    int localsCount = 0;
    /* type */
    final Type type;
    /* name of the variable in `CallFrame` */
    final String varName;
    /* how many registers this type occupy */
    final int shift;
    /* storage's size (same as shift except for void it's 0) */
    final int size;
    /* the array where its values are stored */
    final int blockIndex;
    /* mapping from its variable id to its array's index */
    final Map<Integer, NavigableMap<Integer, Integer>> regToIndex = new HashMap<>();
    /* mapping from its array's index to its variable id */
    final Map<Integer, Integer> indexToReg = new HashMap<>();

    FieldDescr(Type type, int blockIndex) {
      this.type = type;
      var iname = type.getInternalName();
      if (iname.length() > 1)
        iname = "";
      this.varName = iname;
      size = type.getSize();
      shift = size == 0 ? 1 : size;
      this.blockIndex = blockIndex;
    }
  }

  private static class StateDescr {
    /* where it restores local variables */
    final Label init;
    /* where it jumps to resume execution after suspend */
    final Label resume;
    /* where suspended and normal executions meet */
    final Label cont;
    /* Unwind `try-catch` handler block's start */
    final Label start;
    /* unwind hander block */
    final Label unwind;
    /* local variables for the call */
    final List<FieldDescr> localFields = new ArrayList<>();
    /* part of the state's opstack stored in vars blocks */
    final List<FieldDescr> stackFields = new ArrayList<>();
    /* part of the state's opstack stored in locals */
    final List<FieldDescr> argStackFields = new LinkedList<>();
    /* part of the state's opstack stored in call frames */
    final List<FieldDescr> storedStackFields = new LinkedList<>();
    /* type of the function currently called */
    final Type type;
    /* the result type */
    final Type retType;
    /* the index for the dispatching switch statement */
    final int id;
    /* a copy of `locals` from `AnalyzerAdapter` */
    final Object[] localTypes;
    /* a copy of `stack` from `AnalyzerAdapter` */
    final Object[] stackTypes;
    /* a copy of `stack` from `AnalyzerAdapter` after the call */
    final List<Object> stackTypesAfter = new ArrayList<>();
    /* mapping between the original variable index and array's index in the frame */
    protected int storedStackSize;
    /* a label's counter to resolve overlapping variables using debugging info */
    final int when;

    StateDescr(final String descriptor, final int id, final List<Object> stack, final List<Object> local,
        final int labelPosition) {
      this.id = id;
      this.when = labelPosition;
      this.stackTypes = stack.toArray();
      this.localTypes = local.toArray();
      type = Type.getType(descriptor);
      retType = type.getReturnType();
      init = new Label();
      resume = new Label();
      start = new Label();
      unwind = new Label();
      cont = new Label();
    }
  }

  /** 
   * Instrumets class bytecode
   * 
   * @param data input bytecode
   * @return instrumented bytecode or {@literal null} if nothing is changed there
   */
  public static byte[] instrumentClass(final byte[] data) {
    final var cr = new ClassReader(data);
    final var ci = new ClassNode(ASM9);
    final var cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

    final var visitor = new ClassVisitor(ASM9, ci) {
      String className;
      final Map<String, Integer> methodIds = new HashMap<>();
      boolean anythingInstrumented = false;
      boolean innerClassAlreadySet = false;
      boolean classNeedsInstrumentation = false;

      public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
        if (enablingAnnotation.equals(descriptor))
          classNeedsInstrumentation = true;
        return super.visitAnnotation(descriptor, visible);
      }

      @Override
      public void visit(
          final int version,
          final int access,
          final String name,
          final String signature,
          final String superName,
          final String[] interfaces) {
        this.className = name;
        ci.visit(version, access, name, signature, superName, interfaces);
      }

      @Override
      public void visitInnerClass(final String name, final String outerName, final String innerName, final int access) {
        if (name.equals("java/lang/invoke/MethodHandles$Lookup"))
          innerClassAlreadySet = true;
        super.visitInnerClass(name, outerName, innerName, access);
      }

      @Override
      public void visitEnd() {
        if (anythingInstrumented && !innerClassAlreadySet)
          super.visitInnerClass("java/lang/invoke/MethodHandles$Lookup", "java/lang/invoke/MethodHandles", "Lookup",
              ACC_PUBLIC | ACC_FINAL | ACC_STATIC);
        super.visitEnd();
      }

      @Override
      public MethodVisitor visitMethod(final int access,
          final String name,
          final String descriptor,
          final String signature,
          final String[] exceptions) {
        final var method = ci.visitMethod(access, name, descriptor, signature, exceptions);
        if (!classNeedsInstrumentation || name.equals("<init>") || name.equals("<clinit>")
            || exceptions == null || !Arrays.asList(exceptions).contains(CTRL_TOKEN))
          return method;
        final var methodType = Type.getType(descriptor);
        final var retType = methodType.getReturnType();
        final var ccInterm = new MethodNode(ASM9);
        var stateVarCount = 0;
        final var FRAME_VAR_INDEX = stateVarCount++;
        final var intFD = new FieldDescr(Type.INT_TYPE, stateVarCount++);
        final var longFD = new FieldDescr(Type.LONG_TYPE, stateVarCount++);
        final var floatFD = new FieldDescr(Type.FLOAT_TYPE, stateVarCount++);
        final var doubleFD = new FieldDescr(Type.DOUBLE_TYPE, stateVarCount++);
        final var refFD = new FieldDescr(Type.getObjectType("java/lang/Object"), stateVarCount++);
        final var voidFD = new FieldDescr(Type.VOID_TYPE, -1);
        final var fieldDescrs = new FieldDescr[] { intFD, longFD, floatFD, doubleFD, refFD };
        final int stackStart = stateVarCount++;
        final var paramsFields = new ArrayList<FieldDescr>();
        final var visitedLabels = new HashMap<LabelNode, Integer>();
        final var visitedVarIns = new ArrayList<Integer>();
        final var frameNodes = new HashSet<FrameNode>();
        final var fixNullTypeLocals = new ArrayList<StateDescr>();

        return new AnalyzerAdapter(ASM9, className, access, name, descriptor, method) {

          final List<StateDescr> states = new ArrayList<>();
          Object[] paramsTypes;
          int maxStackSize = 0;
          int maxLocalsSize = 0;
          int invokeCounter = 0;
          Set<Integer> skipInvoke = new HashSet<>();

          class UninitializedDescr {
            final String objType;
            final List<FieldDescr> fields = new LinkedList<>();
            final List<Object> types;
            final int count;

            UninitializedDescr(Label label, String objType, int argsSize) {
              this.objType = objType;
              final var len = stack.size();
              final var argsPos = len - argsSize;
              final var types2E = new ArrayList<Object>();
              for (var i = argsPos; i < len; ++i)
                types2E.add(stack.get(i));
              types = to1ElemOpTypes(types2E);
              int count = 0;
              assert stack.get(argsPos - 1) == label;
              for (var i = len - argsSize - 2; i >= 0; --i) {
                if (stack.get(i) != label)
                  break;
                count++;
              }
              this.count = count;
              for (final var i : types)
                fields.add(getFieldDescr(i));
            }
          }

          final Map<LabelNode, String> uninitialized = new HashMap<>();
          final Map<Integer, UninitializedDescr> toShuffle = new HashMap<>();

          FieldDescr opcodeFieldDescr(final int opcode) {
            return opcode == RET ? intFD : fieldDescrs[opcode < ISTORE ? opcode - ILOAD : opcode - ISTORE];
          }

          FieldDescr getFieldDescr(final Object type) {
            return type == INTEGER ? intFD
                : type == FLOAT ? floatFD
                    : type == LONG ? longFD
                        : type == DOUBLE ? doubleFD : type == TOP ? voidFD : refFD;
          }

          FieldDescr getTypeFieldDescr(final Type type) {
            switch (type.getSort()) {
              case Type.VOID:
                return voidFD;
              case Type.BOOLEAN:
              case Type.CHAR:
              case Type.BYTE:
              case Type.SHORT:
              case Type.INT:
                return intFD;
              case Type.LONG:
                return longFD;
              case Type.FLOAT:
                return floatFD;
              case Type.DOUBLE:
                return doubleFD;
              default:
                return refFD;
            }
          }

          void intConst(MethodVisitor dest, int i) {
            if (i < Short.MAX_VALUE)
              dest.visitIntInsn(i < Byte.MAX_VALUE ? BIPUSH : SIPUSH, i);
            else
              dest.visitLdcInsn(i);
          }

          List<Object> to1ElemOpTypes(List<Object> ocTypes) {
            final var iter = ocTypes.iterator();
            final var ret = new ArrayList<>();
            while (iter.hasNext()) {
              final var code = iter.next();
              if (code == LONG || code == DOUBLE)
                iter.next();
              ret.add(code);
            }
            return ret;
          }

          Object[] to1ElemOpTypes(Object[] ocTypes) {
            return to1ElemOpTypes(List.of(ocTypes)).toArray();
          }

          void getFields(List<FieldDescr> ret, List<Object> vars) {
            if (vars == null)
              return;
            int i = 0, len = vars.size();
            for (; i < len;) {
              final var v = vars.get(i);
              final var fieldDescr = getFieldDescr(v);
              ret.add(fieldDescr);
              i += fieldDescr.shift;
            }
          }

          @Override
          public void visitFrame(final int type, final int numLocal, final Object[] local, final int numStack,
              final Object[] stack) {
            super.visitFrame(type, numLocal, local, numStack, stack);
            frameNodes.add((FrameNode) ccInterm.instructions.getLast());
          }

          @Override
          public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor,
              final boolean isInterface) {
            final var id = invokeCounter++;
            if (opcode == INVOKESPECIAL && name.equals("<init>")) {
              var argsSize = (Type.getType(descriptor).getArgumentsAndReturnSizes() >> 2) - 1;
              final var label = (Label) stack.get(stack.size() - argsSize - 1);
              final var objType = uninitialized.getOrDefault(label.info, null);
              if (objType != null) {
                final var descr = new UninitializedDescr(label, objType, argsSize);
                toShuffle.put(id, descr);
                final var labelNode = (LabelNode) label.info;
                AbstractInsnNode curNode = labelNode;
                var iterNode = curNode.getNext();
                for (var j = 0; iterNode != null && j < 5; ++j) {
                  if (iterNode.getOpcode() == NEW)
                    break;
                  curNode = iterNode;
                  iterNode = curNode.getNext();
                }
                assert iterNode == null || iterNode.getOpcode() == NEW;
                curNode = iterNode;
                iterNode = curNode.getNext();
                ccInterm.instructions.remove(curNode);
                var count = descr.count;
                while (count > 0 && iterNode != null) {
                  curNode = iterNode;
                  iterNode = curNode.getNext();
                  switch (curNode.getOpcode()) {
                    case DUP:
                      count--;
                      break;
                    case NOP:
                    case -1:
                      continue;
                    default:
                      assert false : "couldn't instrument this byte code";
                  }
                  ccInterm.instructions.remove(curNode);
                }

              }
              super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
              return;
            }
            if (!callPredicate.test(owner, name)) {
              super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
              skipInvoke.add(id);
              return;
            }
            final var initedStack = new ArrayList<Object>();
            for (final var i : stack) {
              if (i instanceof Label)
                uninitialized.put((LabelNode) ((Label) i).info, (String) uninitializedTypes.get(i));
              else
                initedStack.add(i);
            }
            final var state = new StateDescr(descriptor, states.size() + 1, initedStack, locals, visitedLabels.size());
            getFields(state.stackFields, initedStack);
            getFields(state.localFields, locals);
            if (locals.contains(NULL))
              fixNullTypeLocals.add(state);
            states.add(state);
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            for (final var i : to1ElemOpTypes(stack)) {
              if (!(i instanceof Label))
                state.stackTypesAfter.add(i);
            }
            final var isStaticCall = opcode == INVOKESTATIC;
            final var type = state.type;
            var curArgsStackSize = type.getArgumentsAndReturnSizes() >> 2;
            if (isStaticCall)
              --curArgsStackSize;
            final var curStoredStackSize = state.stackTypes.length - curArgsStackSize;
            state.storedStackSize = curStoredStackSize;
            for (final var fieldDescr : fieldDescrs)
              fieldDescr.count = 0;
            if (curStoredStackSize > 0) {
              final var stackIter = state.stackFields.listIterator();
              for (var i = 0; i < curStoredStackSize && stackIter.hasNext();) {
                final var fieldDescr = stackIter.next();
                i += fieldDescr.shift;
                state.storedStackFields.add(fieldDescr);
              }
              while (stackIter.hasNext())
                state.argStackFields.add(stackIter.next());
              for (final var fieldDescr : state.storedStackFields)
                ++fieldDescr.count;
              for (final var fieldDescr : fieldDescrs)
                fieldDescr.stack = Math.max(fieldDescr.stack, fieldDescr.count);
            }
            maxStackSize = Math.max(maxStackSize, state.stackTypes.length);
          }

          @Override
          public void visitLabel(final Label label) {
            super.visitLabel(label);
            visitedLabels.put((LabelNode) ccInterm.instructions.getLast(), visitedLabels.size());
          }

          private void regVar(final FieldDescr fieldDescr, final int regId) {
            fieldDescr.regToIndex.computeIfAbsent(regId, _i -> {
              final var ret = new TreeMap<Integer, Integer>();
              final var index = fieldDescr.localsCount++;
              ret.put(-1, index);
              fieldDescr.indexToReg.put(index, regId);
              return ret;
            });
          }

          @Override
          public void visitVarInsn(final int opcode, final int regId) {
            super.visitVarInsn(opcode, regId);
            maxLocalsSize = Math.max(maxLocalsSize, regId + 1);
            regVar(opcodeFieldDescr(opcode), regId);
            if (opcode >= ISTORE)
              visitedVarIns.add(visitedLabels.size());
          }

          @Override
          public void visitIincInsn(final int regId, final int increment) {
            super.visitIincInsn(regId, increment);
            regVar(intFD, regId);
            maxLocalsSize = Math.max(maxLocalsSize, regId + 1);
            visitedVarIns.add(visitedLabels.size());
          }

          @Override
          public void visitCode() {
            mv = ccInterm;
            super.visitCode();
            paramsTypes = locals.toArray();
            getFields(paramsFields, locals);
            var regId = 0;
            for (final var fieldDescr : paramsFields) {
              regVar(fieldDescr, regId);
              regId += fieldDescr.shift;
            }
          }

          @Override
          public void visitEnd() {
            ccInterm.visitEnd();
            if (states.size() == 0) {
              ccInterm.accept(method);
              return;
            }
            anythingInstrumented = true;
            /* # handling different locals with the same index */
            final var num = methodIds.merge(name, 0, (name, prev) -> prev + 1);
            final var ccId = num == 0 ? name : format("%s$%d", name, num);
            final var ccName = format("%s$cc", ccId);
            final var ccLambdaName = format("%s$cc$lambda", ccId);
            final var localsStart = stackStart + maxStackSize;
            final var tempVarsStart = localsStart + maxLocalsSize;
            /* # fixing javac (but not ECJ) generated strange finally block (not needed for ECJ) */
            /* it seems to be a Sun javac bug */
            /* https://stackoverflow.com/questions/6386917/strange-exception-table-entry-produced-by-suns-javac */
            if (ccInterm.tryCatchBlocks != null) {
              final var toDelete = new ArrayList<TryCatchBlockNode>();
              for (final var i : ccInterm.tryCatchBlocks) {
                if (i.type != null)
                  continue;
                /* if handler is inside the body ignoreing this */
                final var startIndex = ccInterm.instructions.indexOf(i.start);
                final var endIndex = ccInterm.instructions.indexOf(i.end);
                final var handlerIndex = ccInterm.instructions.indexOf(i.handler);
                if (handlerIndex >= startIndex && handlerIndex < endIndex) {
                  if (startIndex == endIndex || startIndex == handlerIndex)
                    toDelete.add(i);
                  else
                    i.end = i.handler;
                }
              }
              for (final var i : toDelete)
                ccInterm.tryCatchBlocks.remove(i);
              /* injecting skip/check exception */
              for (final var i : ccInterm.tryCatchBlocks) {
                final var handler = i.handler;
                var iter = handler.getNext();
                while (iter != null && !(iter instanceof FrameNode) && iter.getOpcode() == -1)
                  iter = iter.getNext();
                assert iter instanceof FrameNode;
                ccInterm.instructions.insert(iter, new MethodInsnNode(INVOKEVIRTUAL, "javactrl:@@@EH@@@",
                    i.type == null || i.type.equals("java/lang/Throwable") ? "_skipException"
                        : i.type.equals("io/github/javactrl/rt/CThrowable") ? "_skipWind"
                            : "_checkException",
                    "(Ljava/lang/Throwable;)V", false));
              }
            }
            /* # fixing overlapping variables ids */
            if (ccInterm.localVariables != null) {
              final var varTypes = new HashMap<Integer, NavigableMap<Integer, String>>();
              final var declaredLocals = new HashMap<Integer, LabelNode>();
              for (final var i : ccInterm.localVariables) {
                final var regId = i.index;
                i.index += localsStart;
                final var fieldDescr = getTypeFieldDescr(Type.getType(i.desc));
                final var varMap = fieldDescr.regToIndex;
                /* asm sometimes run vars change before the start label, so we use the previous end label */
                final var prevEnd = declaredLocals.getOrDefault(regId, null);
                final var typeMap = varTypes.computeIfAbsent(regId, _i -> new TreeMap<>());
                final var indexMap = varMap.computeIfAbsent(regId, _i -> new TreeMap<>());
                int index;
                if (prevEnd == null) {
                  typeMap.put(-1, i.desc);
                  index = indexMap.getOrDefault(-1, -1);
                  if (index == -1) {
                    index = fieldDescr.localsCount++;
                    indexMap.put(-1, index);
                  }
                } else {
                  index = fieldDescr.localsCount++;
                  final var lab = visitedLabels.get(prevEnd);
                  typeMap.put(lab, i.desc);
                  indexMap.put(lab, index);
                }
                fieldDescr.indexToReg.put(index, regId);
                declaredLocals.put(regId, i.end);
              }
              for (final var state : fixNullTypeLocals) {
                for (int i = 0, len = state.localTypes.length; i < len; ++i) {
                  if (state.localTypes[i] == NULL) {
                    final var tmap = varTypes.getOrDefault(i, null);
                    if (tmap == null)
                      continue;
                    final var tentry = tmap.lowerEntry(state.when);
                    if (tentry == null)
                      continue;
                    state.localTypes[i] = Type.getType(tentry.getValue()).getInternalName();
                  }
                }
              }
            }
            /* # generating redirect method */
            method.visitCode();
            for (final var i : fieldDescrs)
              i.count = 0;
            var regId = 0;
            method.visitLdcInsn(Type.getObjectType(className));
            method.visitLdcInsn(ccId);
            for (final var fieldDescr : fieldDescrs)
              intConst(method, fieldDescr.stack + fieldDescr.localsCount);
            method.visitMethodInsn(INVOKESTATIC, "io/github/javactrl/rt/CallFrame", "_create",
                "(Ljava/lang/Class;Ljava/lang/String;IIIII)Lio/github/javactrl/rt/CallFrame;", false);
            for (final var fieldDescr : fieldDescrs)
              fieldDescr.count = 0;
            for (final var fieldDescr : paramsFields)
              fieldDescr.count++;
            for (final var fieldDescr : fieldDescrs) {
              if (fieldDescr.count == 0)
                continue;
              method.visitInsn(DUP);
              method.visitFieldInsn(GETFIELD, "io/github/javactrl/rt/CallFrame", format("v%s", fieldDescr.varName),
                  format("[%s", fieldDescr.type.getDescriptor()));
              final var vloadOp = fieldDescr.type.getOpcode(ILOAD);
              final var storeOp = fieldDescr.type.getOpcode(IASTORE);
              for (int index = 0, last = fieldDescr.count - 1; index <= last; ++index) {
                if (index != last)
                  method.visitInsn(DUP);
                intConst(method, index + fieldDescr.stack);
                method.visitVarInsn(vloadOp, fieldDescr.indexToReg.get(index));
                method.visitInsn(storeOp);
              }
            }
            method.visitInsn(DUP);
            method.visitVarInsn(ASTORE, 1);
            method.visitMethodInsn(INVOKESTATIC, className, ccLambdaName,
                "()Lio/github/javactrl/rt/CallFrame$_Handler;", false);
            method.visitInsn(DUP_X1);
            method.visitFieldInsn(PUTFIELD, "io/github/javactrl/rt/CallFrame", "handler",
                "Lio/github/javactrl/rt/CallFrame$_Handler;");
            method.visitVarInsn(ALOAD, 1);
            for (final var fieldDescr : fieldDescrs) {
              method.visitVarInsn(ALOAD, 1);
              method.visitFieldInsn(GETFIELD, "io/github/javactrl/rt/CallFrame", format("v%s", fieldDescr.varName),
                  "[" + fieldDescr.type.getDescriptor());
            }
            method.visitMethodInsn(INVOKEINTERFACE, "io/github/javactrl/rt/CallFrame$_Handler", "run",
                "(Lio/github/javactrl/rt/CallFrame;[I[J[F[D[Ljava/lang/Object;)Ljava/lang/Object;", true);
            final var retSort = retType.getSort();
            if (retSort > Type.VOID && retSort < Type.ARRAY) {
              final var numType = retType == Type.BOOLEAN_TYPE ? "java/lang/Boolean" : "java/lang/Number";
              method.visitTypeInsn(CHECKCAST, numType);
              method.visitMethodInsn(INVOKEVIRTUAL, numType, retType.getClassName() + "Value",
                  "()" + retType.getDescriptor(), false);
            } else if ((retSort == Type.ARRAY || retSort == Type.OBJECT)
                && !retType.getInternalName().equals("java/lang/Object"))
              method.visitTypeInsn(CHECKCAST, retType.getInternalName());
            method.visitInsn(retType.getOpcode(IRETURN));
            method.visitMaxs(5, regId);
            method.visitEnd();
            final var ccFinal = ci.visitMethod(ACC_SYNTHETIC + ACC_PRIVATE + ACC_STATIC, ccName,
                "(Lio/github/javactrl/rt/CallFrame;[I[J[F[D[Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
            final var ccLambda = ci.visitMethod(ACC_SYNTHETIC + ACC_PUBLIC + ACC_STATIC, ccLambdaName,
                "()Lio/github/javactrl/rt/CallFrame$_Handler;",
                null,
                null);
            ccLambda.visitCode();
            ccLambda.visitInvokeDynamicInsn("run", "()Lio/github/javactrl/rt/CallFrame$_Handler;", new Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false),
                new Object[] {
                    Type.getType("(Lio/github/javactrl/rt/CallFrame;[I[J[F[D[Ljava/lang/Object;)Ljava/lang/Object;"),
                    new Handle(H_INVOKESTATIC, className, ccName,
                        "(Lio/github/javactrl/rt/CallFrame;[I[J[F[D[Ljava/lang/Object;)Ljava/lang/Object;", false),
                    Type.getType("(Lio/github/javactrl/rt/CallFrame;[I[J[F[D[Ljava/lang/Object;)Ljava/lang/Object;") });
            ccLambda.visitInsn(ARETURN);
            ccLambda.visitMaxs(1, 0);
            ccLambda.visitEnd();
            final var prefixFrameLocals = new Object[stackStart];
            var prefixFrameLocalsIndex = 0;
            prefixFrameLocals[prefixFrameLocalsIndex++] = "io/github/javactrl/rt/CallFrame";
            for (final var fieldDescr : fieldDescrs)
              prefixFrameLocals[prefixFrameLocalsIndex++] = format("[%s", fieldDescr.type.getDescriptor());
            /* recalculating frames info */
            for (final var frameNode : frameNodes) {
              final var flocal = frameNode.local == null ? new Object[0] : frameNode.local.toArray();
              final var nlocal = new Object[localsStart + flocal.length];
              System.arraycopy(prefixFrameLocals, 0, nlocal, 0, stackStart);
              Arrays.fill(nlocal, stackStart, localsStart, TOP);
              System.arraycopy(flocal, 0, nlocal, localsStart, flocal.length);
              frameNode.local = List.of(nlocal);
              if (frameNode.stack != null) {
                final var nstack = new ArrayList<Object>();
                for (final var i : frameNode.stack) {
                  if (!(i instanceof LabelNode && uninitialized.containsKey((LabelNode) i)))
                    nstack.add(i);
                }
                frameNode.stack = nstack;
              }
            }

            final var varInsIter = visitedVarIns.iterator();
            ccInterm.accept(new MethodVisitor(ASM9, ccFinal) {
              int stateCount = 0;

              int getStoreIndex(FieldDescr fieldDescr, int reg, int when) {
                final var entry = fieldDescr.regToIndex.get(reg).lowerEntry(when);
                if (entry == null)
                  return reg;
                return entry.getValue();
              }

              void restoreLocals(List<FieldDescr> fields, Object[] types, int when) {
                int regIndex = 0;
                for (final var fieldDescr : fields) {
                  if (fieldDescr.size > 0) {
                    var refType = types[regIndex];
                    if (fieldDescr == refFD && (refType == NULL || refType instanceof Label)) {
                      ccFinal.visitInsn(ACONST_NULL);
                    } else {
                      ccFinal.visitVarInsn(ALOAD, fieldDescr.blockIndex);
                      intConst(ccFinal, getStoreIndex(fieldDescr, regIndex, when) + fieldDescr.stack);
                      ccFinal.visitInsn(fieldDescr.type.getOpcode(IALOAD));
                      if (fieldDescr == refFD && refType instanceof String && !refType.equals("java/lang/Object"))
                        ccFinal.visitTypeInsn(CHECKCAST, (String) refType);
                    }
                    ccFinal.visitVarInsn(fieldDescr.type.getOpcode(ISTORE), regIndex + localsStart);
                  }
                  regIndex += fieldDescr.shift;
                }
              }

              @Override
              public void visitCode() {
                ccFinal.visitCode();
                final var dflt = new Label();
                final var labelsArr = new Label[states.size() + 1];
                labelsArr[0] = new Label();
                for (final var state : states)
                  ccFinal.visitTryCatchBlock(state.start, state.unwind, state.unwind, "io/github/javactrl/rt/Unwind");
                var stateIndex = 0;
                for (final var i : states)
                  labelsArr[++stateIndex] = i.init;
                ccFinal.visitVarInsn(ALOAD, FRAME_VAR_INDEX);
                ccFinal.visitFieldInsn(GETFIELD, "io/github/javactrl/rt/CallFrame", "state", "I");
                ccFinal.visitTableSwitchInsn(0, states.size(), dflt, labelsArr);
                ccFinal.visitLabel(dflt);
                ccFinal.visitFrame(F_NEW, 0, null, 0, null);
                ccFinal.visitTypeInsn(NEW, "java/lang/Error");
                ccFinal.visitInsn(DUP);
                ccFinal.visitLdcInsn("INTERNAL: invalid state");
                ccFinal.visitMethodInsn(INVOKESPECIAL, "java/lang/Error", "<init>", "(Ljava/lang/String;)V", false);
                ccFinal.visitInsn(ATHROW);
                ccFinal.visitLabel(labelsArr[0]);
                ccFinal.visitFrame(F_NEW, prefixFrameLocals.length,
                    Arrays.copyOf(prefixFrameLocals, prefixFrameLocals.length), 0, null);
                restoreLocals(paramsFields, paramsTypes, 0);
              }

              @Override
              public void visitVarInsn(final int opcode, final int localId) {
                final var index = localsStart + localId;
                ccFinal.visitVarInsn(opcode, index);
                if (opcode < ISTORE)
                  return;
                final var fieldDescr = opcodeFieldDescr(opcode);
                ccFinal.visitVarInsn(ALOAD, fieldDescr.blockIndex);
                intConst(ccFinal, getStoreIndex(fieldDescr, localId, varInsIter.next()) + fieldDescr.stack);
                ccFinal.visitVarInsn(fieldDescr.type.getOpcode(ILOAD), index);
                ccFinal.visitInsn(fieldDescr.type.getOpcode(IASTORE));
              }

              @Override
              public void visitIincInsn(final int localId, final int increment) {
                final var index = localsStart + localId;
                ccFinal.visitIincInsn(index, increment);
                ccFinal.visitVarInsn(ALOAD, intFD.blockIndex);
                intConst(ccFinal, getStoreIndex(intFD, localId, varInsIter.next()) + intFD.stack);
                ccFinal.visitVarInsn(ILOAD, index);
                ccFinal.visitInsn(IASTORE);
              }

              int invokeCounterInner = 0;

              @Override
              public void visitMethodInsn(
                  final int opcode,
                  final String owner,
                  final String name,
                  final String descriptor,
                  final boolean isInterface) {
                if (owner.equals("javactrl:@@@EH@@@")) {
                  ccFinal.visitInsn(DUP);
                  ccFinal.visitVarInsn(ALOAD, FRAME_VAR_INDEX);
                  ccFinal.visitInsn(SWAP);
                  ccFinal.visitMethodInsn(opcode, "io/github/javactrl/rt/CallFrame", name, descriptor, isInterface);
                  return;
                }
                final var id = invokeCounterInner++;
                if (opcode == INVOKESPECIAL && name.equals("<init>")) {
                  final var descr = toShuffle.getOrDefault(id, null);
                  if (descr != null) {
                    var varShift = 0;
                    for (final var i = descr.fields.listIterator(descr.fields.size()); i.hasPrevious();) {
                      final var fieldDescr = i.previous();
                      ccFinal.visitVarInsn(fieldDescr.type.getOpcode(ISTORE), tempVarsStart + varShift);
                      varShift += fieldDescr.shift;
                    }
                    ccFinal.visitTypeInsn(NEW, descr.objType);
                    for (var i = 0; i < descr.count; ++i)
                      ccFinal.visitInsn(DUP);
                    for (final var fieldDescr : descr.fields) {
                      varShift -= fieldDescr.shift;
                      ccFinal.visitVarInsn(fieldDescr.type.getOpcode(ILOAD), tempVarsStart + varShift);
                    }
                  }
                  ccFinal.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                  return;
                }
                if (skipInvoke.contains(id)) {
                  ccFinal.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                  return;
                }
                final var state = states.get(stateCount++);
                int regId;
                if (state.storedStackFields.size() > 0) {
                  /* # opstack to locals */
                  regId = stackStart + state.stackTypes.length;
                  for (final var i = state.stackFields.listIterator(state.stackFields.size()); i.hasPrevious();) {
                    final var fieldDescr = i.previous();
                    regId -= fieldDescr.shift;
                    ccFinal.visitVarInsn(fieldDescr.type.getOpcode(ISTORE), regId);
                  }
                  for (final var fieldDescr : state.stackFields) {
                    ccFinal.visitVarInsn(fieldDescr.type.getOpcode(ILOAD), regId);
                    regId += fieldDescr.shift;
                  }
                }
                ccFinal.visitLabel(state.start);
                ccFinal.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                ccFinal.visitJumpInsn(GOTO, state.cont);
                /* # unwind handler */
                ccFinal.visitLabel(state.unwind);
                /* ## save opstack */
                for (final var fieldDescr : fieldDescrs)
                  fieldDescr.count = 0;
                var unwindLocals = new Object[localsStart + state.localTypes.length];
                System.arraycopy(prefixFrameLocals, 0, unwindLocals, 0, stackStart);
                System.arraycopy(state.stackTypes, 0, unwindLocals, stackStart, state.storedStackSize);
                Arrays.fill(unwindLocals, stackStart + state.storedStackSize, localsStart, TOP);
                System.arraycopy(state.localTypes, 0, unwindLocals, localsStart, state.localTypes.length);
                unwindLocals = to1ElemOpTypes(unwindLocals);
                ccFinal.visitFrame(F_NEW, unwindLocals.length, unwindLocals, 1,
                    new Object[] { "io/github/javactrl/rt/Unwind" });
                regId = stackStart;
                /* # save opstack */
                for (final var fieldDescr : state.storedStackFields) {
                  ccFinal.visitVarInsn(ALOAD, fieldDescr.blockIndex);
                  intConst(ccFinal, fieldDescr.count++);
                  ccFinal.visitVarInsn(fieldDescr.type.getOpcode(ILOAD), regId);
                  regId += fieldDescr.shift;
                  ccFinal.visitInsn(fieldDescr.type.getOpcode(IASTORE));
                }
                /* ## unused locals cleanup (to avoid leaks) */
                regId = 0;
                final var localsToClean = new HashMap<>(refFD.indexToReg);
                for (final var fieldDescr : state.localFields) {
                  if (fieldDescr == refFD)
                    localsToClean.remove(getStoreIndex(refFD, regId, state.when));
                  regId += fieldDescr.shift;
                }
                for (final var arrIndex : localsToClean.keySet()) {
                  ccFinal.visitVarInsn(ALOAD, refFD.blockIndex);
                  intConst(ccFinal, arrIndex + refFD.stack);
                  ccFinal.visitInsn(ACONST_NULL);
                  ccFinal.visitInsn(AASTORE);
                }
                ccFinal.visitInsn(DUP);
                ccFinal.visitVarInsn(ALOAD, FRAME_VAR_INDEX);
                ccFinal.visitInsn(SWAP);
                intConst(ccFinal, state.id);
                ccFinal.visitMethodInsn(INVOKEVIRTUAL, "io/github/javactrl/rt/CallFrame", "_unwind",
                    "(Lio/github/javactrl/rt/Unwind;I)V", false);
                ccFinal.visitInsn(ATHROW);
                /* # resume and a normal continuation join */
                /* # resume handler */
                ccFinal.visitLabel(state.resume);
                final var retSort = state.retType.getSort();
                var resumeStack = state.stackTypesAfter.toArray();
                if (retSort != Type.VOID)
                  resumeStack = Arrays.copyOf(resumeStack, resumeStack.length - 1);
                Object[] contLocals;
                if (state.localTypes.length == 0) {
                  contLocals = prefixFrameLocals;
                } else {
                  contLocals = new Object[localsStart + state.localTypes.length];
                  System.arraycopy(prefixFrameLocals, 0, contLocals, 0, stackStart);
                  Arrays.fill(contLocals, stackStart, localsStart, TOP);
                  System.arraycopy(state.localTypes, 0, contLocals, localsStart, state.localTypes.length);
                  contLocals = to1ElemOpTypes(contLocals);
                }
                ccFinal.visitFrame(F_NEW, contLocals.length, Arrays.copyOf(contLocals, contLocals.length),
                    resumeStack.length, resumeStack);
                ccFinal.visitVarInsn(ALOAD, FRAME_VAR_INDEX);
                if (retSort == Type.ARRAY || retSort == Type.OBJECT || retSort == Type.METHOD) {
                  ccFinal.visitMethodInsn(INVOKEVIRTUAL, "io/github/javactrl/rt/CallFrame",
                      "_refResult", "()Ljava/lang/Object;", false);
                  final var className = state.retType.getInternalName();
                  if (!className.equals("java/lang/Object"))
                    ccFinal.visitTypeInsn(CHECKCAST, className);
                } else
                  ccFinal.visitMethodInsn(INVOKEVIRTUAL, "io/github/javactrl/rt/CallFrame",
                      format("_%sResult", state.retType.getClassName()),
                      format("()%s", state.retType.getDescriptor()), false);
                /* ## cleanup opstack */
                var fieldCount = 0;
                for (final var fieldDescr : state.storedStackFields) {
                  if (fieldDescr != refFD)
                    continue;
                  ccFinal.visitVarInsn(ALOAD, refFD.blockIndex);
                  intConst(ccFinal, fieldCount++);
                  ccFinal.visitInsn(ACONST_NULL);
                  ccFinal.visitInsn(AASTORE);
                }
                ccFinal.visitLabel(state.cont);
                ccFinal.visitFrame(F_NEW, contLocals.length, Arrays.copyOf(contLocals, contLocals.length),
                    state.stackTypesAfter.size(),
                    state.stackTypesAfter.toArray());
                /* avoiding double frames if there is any after this point */
                ccFinal.visitInsn(NOP);
              }

              @Override
              public void visitInsn(final int opcode) {
                switch (opcode) {
                  case IRETURN:
                  case LRETURN:
                  case FRETURN:
                  case DRETURN:
                    final var retClass = retType == Type.INT_TYPE ? "Integer"
                        : retType == Type.CHAR_TYPE ? "Character" : retType.getClassName();
                    final var clName = format("java/lang/%s%s", retClass.substring(0, 1).toUpperCase(),
                        retClass.substring(1));
                    ccFinal.visitMethodInsn(INVOKESTATIC, clName, "valueOf",
                        format("(%s)L%s;", retType.getInternalName(), clName), false);
                    ccFinal.visitInsn(ARETURN);
                    break;
                  case RETURN:
                    ccFinal.visitInsn(ACONST_NULL);
                    ccFinal.visitInsn(ARETURN);
                    break;
                  default:
                    ccFinal.visitInsn(opcode);
                }
              }

              @Override
              public void visitMaxs(final int maxStack, final int maxLocals) {
                for (final var state : states) {
                  /* # restore vars */
                  ccFinal.visitLabel(state.init);
                  ccFinal.visitFrame(F_NEW, prefixFrameLocals.length,
                      Arrays.copyOf(prefixFrameLocals, prefixFrameLocals.length), 0, null);
                  /* ## restore opstack */
                  if (state.storedStackFields.size() > 0) {
                    for (final var fieldDescr : fieldDescrs)
                      fieldDescr.count = 0;
                    var regIndex = 0;
                    for (final var fieldDescr : state.storedStackFields) {
                      final var refType = state.stackTypes[regIndex];
                      regIndex += fieldDescr.shift;
                      if (fieldDescr == refFD && refType == NULL) {
                        ccFinal.visitInsn(ACONST_NULL);
                      } else {
                        ccFinal.visitVarInsn(ALOAD, fieldDescr.blockIndex);
                        intConst(ccFinal, fieldDescr.count++);
                        ccFinal.visitInsn(fieldDescr.type.getOpcode(IALOAD));
                        if (fieldDescr == refFD && !refType.equals("java/lang/Object"))
                          ccFinal.visitTypeInsn(CHECKCAST, (String) refType);
                      }
                    }
                  }
                  restoreLocals(state.localFields, state.localTypes, state.when);
                  ccFinal.visitJumpInsn(GOTO, state.resume);
                }
                ccFinal.visitMaxs(maxStack, maxLocals);
              }
            });
            assert !varInsIter.hasNext();
          }
        };
      };
    };
    cr.accept(visitor, ClassReader.EXPAND_FRAMES);
    if (!visitor.anythingInstrumented)
      return null;
    ci.accept(CHECK ? new CheckClassAdapter(cw, true) : cw);
    return cw.toByteArray();
  }
}
