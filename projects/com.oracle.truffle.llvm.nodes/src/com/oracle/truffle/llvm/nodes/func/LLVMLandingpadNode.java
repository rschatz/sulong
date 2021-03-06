/*
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.nodes.func;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.llvm.runtime.LLVMBitcodeLibraryFunctions;
import com.oracle.truffle.llvm.runtime.LLVMContext;
import com.oracle.truffle.llvm.runtime.except.LLVMUserException;
import com.oracle.truffle.llvm.runtime.memory.LLVMMemory;
import com.oracle.truffle.llvm.runtime.memory.LLVMStack;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMToNativeNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMToNativeNodeGen;
import com.oracle.truffle.llvm.runtime.pointer.LLVMNativePointer;

public final class LLVMLandingpadNode extends LLVMExpressionNode {

    @Child private LLVMExpressionNode getStack;
    @Child private LLVMToNativeNode allocateLandingPadValue;
    @Children private final LandingpadEntryNode[] entries;
    private final FrameSlot exceptionSlot;
    private final boolean cleanup;

    @Child private LLVMToNativeNode unwindHeaderToNative;

    public LLVMLandingpadNode(LLVMExpressionNode getStack, LLVMExpressionNode allocateLandingPadValue, FrameSlot exceptionSlot, boolean cleanup,
                    LandingpadEntryNode[] entries) {
        this.getStack = getStack;
        this.allocateLandingPadValue = LLVMToNativeNodeGen.create(allocateLandingPadValue);
        this.exceptionSlot = exceptionSlot;
        this.cleanup = cleanup;
        this.entries = entries;
        this.unwindHeaderToNative = LLVMToNativeNode.createToNativeWithTarget();
    }

    @CompilationFinal private LLVMMemory memory;

    private LLVMMemory getMemory() {
        if (memory == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            memory = getLLVMMemory();
        }
        return memory;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        try {
            LLVMUserException exception = (LLVMUserException) frame.getObject(exceptionSlot);
            Object unwindHeader = exception.getUnwindHeader();
            LLVMStack.StackPointer stack = (LLVMStack.StackPointer) getStack.executeGeneric(frame);

            int clauseId = getEntryIdentifier(frame, stack, unwindHeader);
            if (clauseId == 0 && !cleanup) {
                throw exception;
            } else {
                LLVMNativePointer landingPadValue = allocateLandingPadValue.execute(frame);
                getMemory().putPointer(landingPadValue, unwindHeaderToNative.executeWithTarget(unwindHeader));
                getMemory().putI32(landingPadValue.increment(LLVMExpressionNode.ADDRESS_SIZE_IN_BYTES), clauseId);
                return landingPadValue;
            }
        } catch (FrameSlotTypeException e) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException(e);
        }
    }

    @ExplodeLoop
    private int getEntryIdentifier(VirtualFrame frame, LLVMStack.StackPointer stack, Object unwindHeader) {
        for (int i = 0; i < entries.length; i++) {
            int clauseId = entries[i].getIdentifier(frame, stack, unwindHeader);
            if (clauseId != 0) {
                return clauseId;
            }
        }
        return 0;
    }

    public abstract static class LandingpadEntryNode extends LLVMExpressionNode {

        public abstract int getIdentifier(VirtualFrame frame, LLVMStack.StackPointer stack, Object unwindHeader);

        @Override
        public final Object executeGeneric(VirtualFrame frame) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException();
        }
    }

    public static final class LandingpadCatchEntryNode extends LandingpadEntryNode {

        @Child private LLVMToNativeNode catchType;
        @Child private LLVMBitcodeLibraryFunctions.SulongCanCatchNode canCatch;

        public LandingpadCatchEntryNode(LLVMExpressionNode catchType) {
            this.catchType = LLVMToNativeNodeGen.create(catchType);
        }

        public LLVMBitcodeLibraryFunctions.SulongCanCatchNode getCanCatch() {
            if (canCatch == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                LLVMContext context = getContextReference().get();
                this.canCatch = insert(new LLVMBitcodeLibraryFunctions.SulongCanCatchNode(context));
            }
            return canCatch;
        }

        @Override
        public int getIdentifier(VirtualFrame frame, LLVMStack.StackPointer stack, Object unwindHeader) {
            LLVMNativePointer catchAddress = catchType.execute(frame);
            if (catchAddress.asNative() == 0) {
                /*
                 * If ExcType is null, any exception matches, so the landing pad should always be
                 * entered. catch (...)
                 */
                return 1;
            }
            if (getCanCatch().canCatch(stack, unwindHeader, catchAddress) != 0) {
                return (int) catchAddress.asNative();
            }
            return 0;
        }
    }

    public static final class LandingpadFilterEntryNode extends LandingpadEntryNode {

        @Children private final LLVMToNativeNode[] filterTypes;
        @Child private LLVMBitcodeLibraryFunctions.SulongCanCatchNode canCatch;

        public LandingpadFilterEntryNode(LLVMToNativeNode[] filterTypes) {
            this.filterTypes = filterTypes;
        }

        public LLVMBitcodeLibraryFunctions.SulongCanCatchNode getCanCatch() {
            if (canCatch == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                LLVMContext context = getContextReference().get();
                this.canCatch = insert(new LLVMBitcodeLibraryFunctions.SulongCanCatchNode(context));
            }
            return canCatch;
        }

        @Override
        public int getIdentifier(VirtualFrame frame, LLVMStack.StackPointer stack, Object unwindHeader) {
            if (!filterMatches(frame, stack, unwindHeader)) {
                // when this clause is matched, the selector value has to be negative
                return -1;
            }
            return 0;
        }

        @ExplodeLoop
        private boolean filterMatches(VirtualFrame frame, LLVMStack.StackPointer stack, Object unwindHeader) {
            /*
             * Landingpad should be entered if the exception being thrown does not match any of the
             * types in the list
             */
            for (int i = 0; i < filterTypes.length; i++) {
                LLVMNativePointer filterAddress = filterTypes[i].execute(frame);
                if (filterAddress.asNative() == 0) {
                    /*
                     * If ExcType is null, any exception matches, so the landing pad should always
                     * be entered. catch (...)
                     */
                    return true;
                }
                if (getCanCatch().canCatch(stack, unwindHeader, filterAddress) != 0) {
                    return true;
                }
            }
            return false;
        }
    }

}
