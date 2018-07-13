/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates.
 * Copyright (c) 2014, Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
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
package com.oracle.graal.python.builtins.objects.set;

import static com.oracle.graal.python.nodes.SpecialMethodNames.__AND__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__CONTAINS__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__EQ__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__ITER__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__LEN__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__LE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__SUB__;

import java.util.List;

import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.CoreFunctions;
import com.oracle.graal.python.builtins.PythonBuiltins;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.PNotImplemented;
import com.oracle.graal.python.builtins.objects.common.EconomicMapStorage;
import com.oracle.graal.python.builtins.objects.common.HashingStorage;
import com.oracle.graal.python.builtins.objects.common.HashingStorage.Equivalence;
import com.oracle.graal.python.builtins.objects.common.HashingStorageNodes;
import com.oracle.graal.python.builtins.objects.common.HashingStorageNodes.PythonEquivalence;
import com.oracle.graal.python.builtins.objects.common.PHashingCollection;
import com.oracle.graal.python.builtins.objects.set.FrozenSetBuiltinsFactory.BinaryUnionNodeGen;
import com.oracle.graal.python.nodes.PBaseNode;
import com.oracle.graal.python.nodes.control.GetIteratorNode;
import com.oracle.graal.python.nodes.control.GetNextNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinBaseNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonBinaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonUnaryBuiltinNode;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.profiles.ValueProfile;

@CoreFunctions(extendClasses = {PFrozenSet.class, PSet.class})
public final class FrozenSetBuiltins extends PythonBuiltins {

    @Override
    protected List<? extends NodeFactory<? extends PythonBuiltinBaseNode>> getNodeFactories() {
        return FrozenSetBuiltinsFactory.getFactories();
    }

    @Builtin(name = __ITER__, fixedNumOfArguments = 1)
    @GenerateNodeFactory
    abstract static class IterNode extends PythonUnaryBuiltinNode {
        @Specialization
        public Object iter(PBaseSet self) {
            return factory().createBaseSetIterator(self);
        }
    }

    @Builtin(name = __LEN__, fixedNumOfArguments = 1)
    @GenerateNodeFactory
    abstract static class LenNode extends PythonUnaryBuiltinNode {
        @Specialization
        public int len(PBaseSet self) {
            return self.size();
        }
    }

    @Builtin(name = __EQ__, fixedNumOfArguments = 2)
    @GenerateNodeFactory
    abstract static class EqNode extends PythonBinaryBuiltinNode {
        @Specialization
        boolean doSetSameType(PBaseSet self, PBaseSet other,
                        @Cached("create()") HashingStorageNodes.KeysEqualsNode equalsNode) {
            return equalsNode.execute(self.getDictStorage(), other.getDictStorage());
        }

        @Fallback
        @SuppressWarnings("unused")
        Object doGeneric(Object self, Object other) {
            return PNotImplemented.NOT_IMPLEMENTED;
        }
    }

    @Builtin(name = __LE__, fixedNumOfArguments = 2)
    @GenerateNodeFactory
    abstract static class LeNode extends PythonBinaryBuiltinNode {
        @Child private HashingStorageNodes.ContainsKeyNode containsKeyNode = HashingStorageNodes.ContainsKeyNode.create();

        @Specialization
        Object run(PBaseSet self, PBaseSet other) {
            if (self.size() > other.size()) {
                return false;
            }

            for (Object value : self.values()) {
                if (!containsKeyNode.execute(other.getDictStorage(), value)) {
                    return false;
                }
            }

            return true;
        }
    }

    @Builtin(name = __AND__, fixedNumOfArguments = 2)
    @GenerateNodeFactory
    abstract static class AndNode extends PythonBinaryBuiltinNode {
        @Child private HashingStorageNodes.IntersectNode intersectNode;

        private HashingStorageNodes.IntersectNode getIntersectNode() {
            if (intersectNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                intersectNode = insert(HashingStorageNodes.IntersectNode.create());
            }
            return intersectNode;
        }

        @Specialization
        PBaseSet doPBaseSet(PSet left, PBaseSet right) {
            HashingStorage intersectedStorage = getIntersectNode().execute(left.getDictStorage(), right.getDictStorage());
            return factory().createSet(intersectedStorage);
        }

        @Specialization
        PBaseSet doPBaseSet(PFrozenSet left, PBaseSet right) {
            HashingStorage intersectedStorage = getIntersectNode().execute(left.getDictStorage(), right.getDictStorage());
            return factory().createFrozenSet(intersectedStorage);
        }
    }

    @Builtin(name = __SUB__, fixedNumOfArguments = 2)
    @GenerateNodeFactory
    abstract static class SubNode extends PythonBinaryBuiltinNode {
        @Child private HashingStorageNodes.DiffNode diffNode;

        private HashingStorageNodes.DiffNode getDiffNode() {
            if (diffNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                diffNode = HashingStorageNodes.DiffNode.create();
            }
            return diffNode;
        }

        @Specialization
        PBaseSet doPBaseSet(PSet left, PBaseSet right) {
            HashingStorage storage = getDiffNode().execute(left.getDictStorage(), right.getDictStorage());
            return factory().createSet(storage);
        }

        @Specialization
        PBaseSet doPBaseSet(PFrozenSet left, PBaseSet right) {
            HashingStorage storage = getDiffNode().execute(left.getDictStorage(), right.getDictStorage());
            return factory().createSet(storage);
        }
    }

    @Builtin(name = __CONTAINS__, fixedNumOfArguments = 2)
    @GenerateNodeFactory
    abstract static class ContainsNode extends PythonBinaryBuiltinNode {
        @Specialization
        boolean contains(PBaseSet self, Object key,
                        @Cached("create()") HashingStorageNodes.ContainsKeyNode containsKeyNode) {
            return containsKeyNode.execute(self.getDictStorage(), key);
        }
    }

    @Builtin(name = "union", minNumOfArguments = 1, takesVariableArguments = true)
    @GenerateNodeFactory
    abstract static class UnionNode extends PythonBuiltinNode {

        @Child private BinaryUnionNode binaryUnionNode;

        @CompilationFinal private ValueProfile setTypeProfile;

        private BinaryUnionNode getBinaryUnionNode() {
            if (binaryUnionNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                binaryUnionNode = insert(BinaryUnionNode.create());
            }
            return binaryUnionNode;
        }

        private ValueProfile getSetTypeProfile() {
            if (setTypeProfile == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                setTypeProfile = ValueProfile.createClassProfile();
            }
            return setTypeProfile;
        }

        @Specialization(guards = {"args.length == len", "args.length < 32"}, limit = "3")
        PBaseSet doCached(PBaseSet self, Object[] args,
                        @Cached("args.length") int len,
                        @Cached("create()") HashingStorageNodes.CopyNode copyNode) {
            PBaseSet result = create(self, copyNode.execute(self.getDictStorage()));
            for (int i = 0; i < len; i++) {
                getBinaryUnionNode().execute(result, result.getDictStorage(), args[i]);
            }
            return result;
        }

        @Specialization(replaces = "doCached")
        PBaseSet doGeneric(PBaseSet self, Object[] args,
                        @Cached("create()") HashingStorageNodes.CopyNode copyNode) {
            PBaseSet result = create(self, copyNode.execute(self.getDictStorage()));
            for (int i = 0; i < args.length; i++) {
                getBinaryUnionNode().execute(result, result.getDictStorage(), args[i]);
            }
            return result;
        }

        private PBaseSet create(PBaseSet left, HashingStorage storage) {
            if (getSetTypeProfile().profile(left) instanceof PFrozenSet) {
                return factory().createFrozenSet(storage);
            }
            return factory().createSet(storage);
        }
    }

    abstract static class BinaryUnionNode extends PBaseNode {
        @Child private Equivalence equivalenceNode;

        public abstract PBaseSet execute(PBaseSet container, HashingStorage left, Object right);

        protected Equivalence getEquivalence() {
            if (equivalenceNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                equivalenceNode = insert(new PythonEquivalence());
            }
            return equivalenceNode;
        }

        @Specialization
        PBaseSet doHashingCollection(PBaseSet container, EconomicMapStorage selfStorage, PHashingCollection other) {
            for (Object key : other.getDictStorage().keys()) {
                selfStorage.setItem(key, PNone.NO_VALUE, getEquivalence());
            }
            return container;
        }

        @Specialization
        PBaseSet doIterable(PBaseSet container, HashingStorage dictStorage, Object iterable,
                        @Cached("create()") GetIteratorNode getIteratorNode,
                        @Cached("create()") GetNextNode next,
                        @Cached("createBinaryProfile()") ConditionProfile errorProfile,
                        @Cached("create()") HashingStorageNodes.SetItemNode setItemNode) {

            Object iterator = getIteratorNode.executeWith(iterable);
            while (true) {
                Object value;
                try {
                    value = next.execute(iterator);
                } catch (PException e) {
                    e.expectStopIteration(getCore(), errorProfile);
                    return container;
                }
                setItemNode.execute(container, dictStorage, value, PNone.NO_VALUE);
            }
        }

        public static BinaryUnionNode create() {
            return BinaryUnionNodeGen.create();
        }
    }

    @Builtin(name = "issubset", fixedNumOfArguments = 2)
    @GenerateNodeFactory
    abstract static class IsSubsetNode extends PythonBinaryBuiltinNode {
        @Specialization
        boolean isSubSet(PBaseSet self, PBaseSet other,
                        @Cached("create()") HashingStorageNodes.KeysIsSubsetNode isSubsetNode) {
            return isSubsetNode.execute(self.getDictStorage(), other.getDictStorage());
        }

        @Specialization
        boolean isSubSet(PBaseSet self, String other,
                        @Cached("create()") SetNodes.ConstructSetNode constructSetNode,
                        @Cached("create()") HashingStorageNodes.KeysIsSubsetNode isSubsetNode) {
            PSet otherSet = constructSetNode.executeWith(other);
            return isSubsetNode.execute(self.getDictStorage(), otherSet.getDictStorage());
        }
    }

    @Builtin(name = "issuperset", fixedNumOfArguments = 2)
    @GenerateNodeFactory
    abstract static class IsSupersetNode extends PythonBinaryBuiltinNode {
        @Specialization
        boolean isSuperSet(PBaseSet self, PBaseSet other,
                        @Cached("create()") HashingStorageNodes.KeysIsSupersetNode isSupersetNode) {
            return isSupersetNode.execute(self.getDictStorage(), other.getDictStorage());
        }

        @Specialization
        boolean isSuperSet(PBaseSet self, String other,
                        @Cached("create()") SetNodes.ConstructSetNode constructSetNode,
                        @Cached("create()") HashingStorageNodes.KeysIsSupersetNode isSupersetNode) {
            PSet otherSet = constructSetNode.executeWith(other);
            return isSupersetNode.execute(self.getDictStorage(), otherSet.getDictStorage());
        }
    }
}
