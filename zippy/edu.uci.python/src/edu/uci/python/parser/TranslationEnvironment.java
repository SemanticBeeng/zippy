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
package edu.uci.python.parser;

import java.util.*;

import org.python.antlr.*;

import com.oracle.truffle.api.frame.*;

import edu.uci.python.nodes.*;
import edu.uci.python.nodes.argument.*;
import edu.uci.python.nodes.frame.*;
import edu.uci.python.parser.ScopeInfo.ScopeKind;
import edu.uci.python.runtime.*;
import edu.uci.python.runtime.standardtype.*;

public class TranslationEnvironment {

    private final PythonContext context;
    private final NodeFactory factory;
    private final PythonModule module;

    private Map<PythonTree, ScopeInfo> scopeInfos;
    private ScopeInfo currentScope;
    private ScopeInfo globalScope;
    private int scopeLevel;

    public static final String RETURN_SLOT_ID = "<return_val>";
    private static final String LIST_COMPREHENSION_SLOT_ID = "<list_comp_val>";
    private static final String TEMP_LOCAL_PREFIX = "temp_";
    private int listComprehensionSlotCounter = 0;

    private Collection<PNode> statementPatch;

    public TranslationEnvironment(PythonContext context, PythonModule pythonModule) {
        this.context = context;
        this.module = pythonModule;
        this.factory = NodeFactory.getInstance();
        scopeInfos = new HashMap<>();
    }

    public TranslationEnvironment reset() {
        scopeLevel = 0;
        listComprehensionSlotCounter = 0;
        return this;
    }

    protected PythonModule getModule() {
        return module;
    }

    public void beginScope(PythonTree scopeEntity, ScopeInfo.ScopeKind kind) {
        scopeLevel++;
        ScopeInfo info = scopeInfos.get(scopeEntity);
        currentScope = info != null ? info : new ScopeInfo(TranslationUtil.getScopeId(scopeEntity, kind), kind, new FrameDescriptor(), currentScope);

        if (globalScope == null) {
            globalScope = currentScope;
        }
    }

    public void endScope(PythonTree scopeEntity) throws Exception {
        scopeLevel--;
        scopeInfos.put(scopeEntity, currentScope);
        currentScope = currentScope.getParent();
    }

    public boolean atModuleLevel() {
        assert scopeLevel > 0;
        return scopeLevel == 1;
    }

    public boolean atNonModuleLevel() {
        assert scopeLevel > 0;
        return scopeLevel > 1;
    }

    public boolean isInConstructorScope() {
        return isInFunctionScope() && //
                        currentScope.getScopeId().equals("__init__") && //
                        currentScope.getParent().getScopeKind() == ScopeKind.Class;
    }

    public ScopeInfo.ScopeKind getScopeKind() {
        return currentScope.getScopeKind();
    }

    public void setToGeneratorScope() {
        currentScope.setAsGenerator();
    }

    public boolean isInModuleScope() {
        return getScopeKind() == ScopeInfo.ScopeKind.Module;
    }

    public boolean isInFunctionScope() {
        return getScopeKind() == ScopeInfo.ScopeKind.Function || getScopeKind() == ScopeInfo.ScopeKind.Generator;
    }

    public boolean isInClassScope() {
        return getScopeKind() == ScopeInfo.ScopeKind.Class;
    }

    public boolean isInGeneratorScope() {
        return getScopeKind() == ScopeInfo.ScopeKind.Generator;
    }

    public String getCurrentScopeId() {
        return currentScope.getScopeId();
    }

    public FrameDescriptor getCurrentFrame() {
        FrameDescriptor frameDescriptor = currentScope.getFrameDescriptor();
        assert frameDescriptor != null;
        return frameDescriptor;
    }

    public FrameDescriptor getEnclosingFrame() {
        FrameDescriptor frameDescriptor = currentScope.getParent().getFrameDescriptor();
        assert frameDescriptor != null;
        return frameDescriptor;
    }

    public FrameSlot createLocal(String name) {
        assert name != null : "name is null!";
        return currentScope.getFrameDescriptor().findOrAddFrameSlot(name);
    }

    private FrameSlot findSlot(String name) {
        assert name != null : "name is null!";
        return currentScope.getFrameDescriptor().findFrameSlot(name);
    }

    public PNode getWriteArgumentToLocal(String name) {
        FrameSlot slot = findSlot(name);
        ReadIndexedArgumentNode right = ReadIndexedArgumentNode.create(slot.getIndex());
        return factory.createWriteLocal(right, slot);
    }

    public PNode getWriteVarArgsToLocal(String name) {
        FrameSlot slot = findSlot(name);
        ReadVarArgsNode right = new ReadVarArgsNode(slot.getIndex());
        return factory.createWriteLocal(right, slot);
    }

    public PNode getWriteKwArgsToLocal(String name) {
        FrameSlot slot = findSlot(name);
        ReadVarKeywordsNode right = new ReadVarKeywordsNode(new String[]{});
        return factory.createWriteLocal(right, slot);
    }

    public ReadNode findVariable(String name) {
        assert name != null : "name is null!";
        FrameSlot slot = findSlot(name);

        switch (getScopeKind()) {
            case Module:
                return (ReadNode) factory.createReadGlobalScope(context, module, name);
            case Generator:
            case ListComp:
            case Function:
                return (ReadNode) (slot != null ? factory.createReadLocal(slot) : findVariableInEnclosingOrGlobalScope(name));
            case Class:
                return (ReadNode) (slot != null ? factory.createGetAttribute(ReadIndexedArgumentNode.create(0), name) : findVariableInEnclosingOrGlobalScope(name));
            default:
                throw new IllegalStateException("Unexpected scopeKind " + getScopeKind());
        }
    }

    protected ReadNode findVariableInEnclosingOrGlobalScope(String name) {
        ReadNode readLevel = findVariableInEnclosingScopes(name);
        if (readLevel != null) {
            return readLevel;
        }

        assert readLevel == null;
        return (ReadNode) factory.createReadGlobalScope(context, module, name);
    }

    public ReadNode makeTempLocalVariable() {
        String tempName = TEMP_LOCAL_PREFIX + currentScope.getFrameDescriptor().getSize();
        FrameSlot tempSlot = createLocal(tempName);
        return (ReadNode) factory.createReadLocal(tempSlot);
    }

    public static FrameSlot makeTempLocalVariable(FrameDescriptor frameDescriptor) {
        String tempName = TEMP_LOCAL_PREFIX + frameDescriptor.getSize();
        return frameDescriptor.findOrAddFrameSlot(tempName);
    }

    public List<PNode> makeTempLocalVariables(List<PNode> rights) {
        List<PNode> tempWrites = new ArrayList<>();

        for (PNode right : rights) {
            tempWrites.add(makeTempLocalVariable().makeWriteNode(right));
        }

        return tempWrites;
    }

    public FrameSlot createGlobal(String name) {
        assert name != null : "name is null!";
        return globalScope.getFrameDescriptor().findOrAddFrameSlot(name);
    }

    public void addLocalGlobals(String name) {
        assert name != null : "name is null!";
        currentScope.addExplicitGlobalVariable(name);
    }

    public boolean isLocalGlobals(String name) {
        assert name != null : "name is null!";
        return currentScope.isExplicitGlobalVariable(name);
    }

    protected ReadNode findVariableInEnclosingScopes(String name) {
        assert name != null : "name is null!";
        int level = 0;
        ScopeInfo current = currentScope;

        try {
            while (current != globalScope) {
                FrameSlot slot = current.getFrameDescriptor().findFrameSlot(name);

                if (slot != null) {
                    return (ReadNode) factory.createReadLevel(slot, level);
                }

                current = current.getParent();
                level++;
            }
        } finally {
            if (current != null && current != globalScope) {
                current = currentScope;
                while (level-- > 0) {
                    current.setNeedsDeclaringScope();
                    current = current.getParent();
                }
            }
        }

        return null;
    }

    public int getCurrentFrameSize() {
        return currentScope.getFrameDescriptor().getSize();
    }

    protected void setDefaultArgumentNodes(List<PNode> defaultArgs) {
        currentScope.setDefaultArgumentNodes(defaultArgs);
    }

    protected List<PNode> getDefaultArgumentNodes() {
        List<PNode> defaultArgs = currentScope.getDefaultArgumentNodes();
        return defaultArgs;
    }

    protected boolean hasDefaultArguments() {
        return currentScope.getDefaultArgumentNodes() != null && currentScope.getDefaultArgumentNodes().size() > 0;
    }

    protected void setDefaultArgumentReads(ReadDefaultArgumentNode[] defaultReads) {
        currentScope.setDefaultArgumentReads(defaultReads);
    }

    protected ReadDefaultArgumentNode[] getDefaultArgumentReads() {
        return currentScope.getDefaultArgumentReads();
    }

    public boolean needsDeclarationFrame() {
        return currentScope.needsDeclaringScope();
    }

    public FrameSlot getReturnSlot() {
        return currentScope.getFrameDescriptor().findOrAddFrameSlot(RETURN_SLOT_ID);
    }

    public FrameSlot nextListComprehensionSlot() {
        listComprehensionSlotCounter++;
        return getListComprehensionSlot();
    }

    public FrameSlot getListComprehensionSlot() {
        return currentScope.getFrameDescriptor().findOrAddFrameSlot(LIST_COMPREHENSION_SLOT_ID + listComprehensionSlotCounter);
    }

    public boolean hasStatementPatch() {
        return statementPatch != null;
    }

    public Collection<PNode> getStatementPatch() {
        Collection<PNode> patch = statementPatch;
        statementPatch = null; // have to reset
        return patch;
    }

    public void storeStatementPatch(Collection<PNode> patch) {
        if (this.statementPatch == null) {
            this.statementPatch = patch;
        } else {
            this.statementPatch.addAll(patch);
        }
    }

}
