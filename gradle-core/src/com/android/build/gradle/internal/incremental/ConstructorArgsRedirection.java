/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.internal.incremental;

import com.android.annotations.NonNull;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.LabelNode;

import java.util.List;

/**
 * A specialized redirection that handles redirecting the part that redirects the
 * argument construction for the super()/this() call in a constructor.
 * <p/>
 * Note that the generated bytecode does not have a direct translation to code, but as an
 * example, for a constructor of the form:
 * <code>
 *   <init>(int x) {
 *     super(x = 1, expr2() ? 3 : 7)
 *     doSomething(x)
 *   }
 * </code>
 * <p/>
 * it becomes:
 * <code>
 *   <init>(int x) {
 *     Change change = $change; // Move to a variable to avoid multithreading issues.
 *     int a, b; // These variables are not needed in bytecode but are needed for the example.
 *     if (change != null) {
 *       Object[] locals = new Object[2];
 *       locals[0] = locals; // So the unboxed receiver can update this array
 *       locals[1] = x;
 *       Object[] constructorArguments = change.access$dispatch("init$args", locals);
 *       x = locals[1];
 *       this(constructorArguments, null);
 *     } else {
 *       a = x = 1;
 *       b = expr2() ? 3 : 7;
 *       super(a, b);
 *     }
 *     if (change != null) {
 *       Object[] locals = new Object[2];
 *       locals[0] = this;
 *       locals[1] = x;
 *       change.access$dispatch("init$body", locals);
 *       return;
 *     }
 *     doSomething(x);
 *  }
 * </code>
 *
 * @see ConstructorDelegationDetector for the generation of init$args and init$body.
 */
public class ConstructorArgsRedirection extends Redirection {

    @NonNull
    private final String thisClassName;

    @NonNull
    private final Type[] types;

    @NonNull
    private final LabelNode end;

    private int locals;

    // The signature of the dynamically dispatching 'this' constructor. The final parameters is
    // to disambiguate from other constructors that might preexist on the class.
    static final String DISPATCHING_THIS_SIGNATURE =
            "([Ljava/lang/Object;L" + IncrementalVisitor.INSTANT_RELOAD_EXCEPTION + ";)V";

    /**
     * @param thisClassName name of the class that this constructor is in.
     * @param name the name to redirect to.
     * @param end the label where the redirection should end (before the super()/this() call).
     * @param types the types of the arguments on the super()/this() call.
     */
    ConstructorArgsRedirection(LabelNode label, String thisClassName, String name, @NonNull LabelNode end, @NonNull Type[] types) {
        super(label, name);
        this.thisClassName = thisClassName;
        this.types = types;
        this.end = end;

        locals = -1;
    }

    @Override
    protected void createLocals(GeneratorAdapter mv, List<Type> args) {
        super.createLocals(mv, args);

        // Override the locals creation to keep a reference to it. We keep a reference to this
        // array because we use it to receive the values of the local variables after the
        // redirection is done.
        locals = mv.newLocal(Type.getType("[Ljava/lang/Object;"));
        mv.dup();
        mv.storeLocal(locals);
    }

    @Override
    protected void redirectLocal(GeneratorAdapter mv, int stackIndex, Type arg) {
        // If the stack index is 0, we do not send the local variable 0 (this) as it
        // cannot escape the constructor. Instead, we use this argument position to send
        // a reference to the locals array where the redirected method will return their
        // values.
        if (stackIndex == 0) {
            mv.loadLocal(locals);
        } else {
            super.redirectLocal(mv, stackIndex, arg);
        }
    }

    @Override
    protected void restore(GeneratorAdapter mv, List<Type> args) {
        // At this point, init$args has been called and the result Object is on the stack.
        // The value of that Object is Object[] with exactly n + 1 elements.
        // The first element is a string with the qualified name of the constructor to call.
        // The remaining elements are the constructtor arguments.

        // Create a new local that holds the result of init$args call.
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        int constructorArgs = mv.newLocal(Type.getType("[Ljava/lang/Object;"));
        mv.storeLocal(constructorArgs);

        // Reinstate local values
        mv.loadLocal(locals);
        int stackIndex = 0;
        for (int arrayIndex = 0; arrayIndex < args.size(); arrayIndex++) {
            Type arg = args.get(arrayIndex);
            // Do not restore "this"
            if (arrayIndex > 0) {
                // duplicates the array
                mv.dup();
                // index in the array of objects to restore the boxed parameter.
                mv.push(arrayIndex);
                // get it from the array
                mv.arrayLoad(Type.getType(Object.class));
                // unbox the argument
                mv.unbox(arg);
                // restore the argument
                mv.visitVarInsn(arg.getOpcode(Opcodes.ISTORE), stackIndex);
          }
            // stack index must progress according to the parameter type we just processed.
            stackIndex += arg.getSize();
        }
        // pops the array
        mv.pop();

        // Push a null for the marker parameter.
        mv.loadLocal(constructorArgs);
        mv.visitInsn(Opcodes.ACONST_NULL);

        // Invoke the constructor
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, thisClassName, "<init>", DISPATCHING_THIS_SIGNATURE, false);

        mv.goTo(end.getLabel());
    }
}
