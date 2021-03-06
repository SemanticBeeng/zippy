/*
 * Copyright (c) 2013, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package edu.uci.python.nodes.frame;

import java.math.*;

import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;

import edu.uci.python.ast.VisitorIF;
import edu.uci.python.nodes.*;
import edu.uci.python.nodes.NodeFactory;
import edu.uci.python.runtime.datatype.*;

@NodeInfo(shortName = "write_local")
@NodeChild(value = "rightNode", type = PNode.class)
@GenerateNodeFactory
public abstract class WriteLocalVariableNode extends FrameSlotNode implements WriteNode {

    public abstract PNode getRightNode();

    public WriteLocalVariableNode(FrameSlot slot) {
        super(slot);
    }

    public WriteLocalVariableNode(WriteLocalVariableNode specialized) {
        this(specialized.frameSlot);
    }

    @Override
    public PNode makeReadNode() {
        return NodeFactory.getInstance().createReadLocal(frameSlot);
    }

    @Override
    public PNode getRhs() {
        return getRightNode();
    }

    @Override
    public Object executeWrite(VirtualFrame frame, Object value) {
        return executeWith(frame, value);
    }

    public abstract Object executeWith(VirtualFrame frame, Object value);

    @Specialization
    public PNone write(VirtualFrame frame, PNone right) {
        frame.setObject(frameSlot, PNone.NONE);
        return right;
    }

    @Specialization(guards = "isBooleanKind(frame)")
    public boolean write(VirtualFrame frame, boolean right) {
        frame.setBoolean(frameSlot, right);
        return right;
    }

    @Specialization(guards = "isIntegerKind(frame)")
    public int write(VirtualFrame frame, int value) {
        frame.setInt(frameSlot, value);
        return value;
    }

    @Specialization(guards = "isIntOrObjectKind(frame)")
    public BigInteger write(VirtualFrame frame, BigInteger value) {
        setObject(frame, value);
        frameSlot.setKind(FrameSlotKind.Object);
        return value;
    }

    @Specialization(guards = "isDoubleKind(frame)")
    public double write(VirtualFrame frame, double right) {
        frame.setDouble(frameSlot, right);
        return right;
    }

    @Specialization(guards = "isObjectKind(frame)")
    public Object write(VirtualFrame frame, Object right) {
        setObject(frame, right);
        return right;
    }

    @Override
    public <R> R accept(VisitorIF<R> visitor) throws Exception {
        return visitor.visitWriteLocalVariableNode(this);
    }

}
