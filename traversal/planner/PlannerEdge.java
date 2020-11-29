/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.traversal.planner;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPVariable;
import grakn.core.common.exception.GraknException;
import grakn.core.common.parameters.Label;
import grakn.core.graph.SchemaGraph;
import grakn.core.graph.util.Encoding;
import grakn.core.graph.vertex.TypeVertex;
import grakn.core.traversal.graph.TraversalEdge;
import grakn.core.traversal.structure.StructureEdge;
import graql.lang.common.GraqlToken;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static grakn.common.collection.Collections.pair;
import static grakn.common.collection.Collections.set;
import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static grakn.core.common.exception.ErrorMessage.Internal.UNRECOGNISED_VALUE;
import static grakn.core.graph.util.Encoding.Edge.ISA;
import static grakn.core.graph.util.Encoding.Edge.Thing.HAS;
import static grakn.core.graph.util.Encoding.Edge.Thing.PLAYING;
import static grakn.core.graph.util.Encoding.Edge.Thing.RELATING;
import static grakn.core.graph.util.Encoding.Edge.Thing.ROLEPLAYER;
import static grakn.core.graph.util.Encoding.Edge.Type.OWNS;
import static grakn.core.graph.util.Encoding.Edge.Type.OWNS_KEY;
import static grakn.core.graph.util.Encoding.Edge.Type.PLAYS;
import static grakn.core.graph.util.Encoding.Edge.Type.RELATES;
import static grakn.core.graph.util.Encoding.Edge.Type.SUB;
import static grakn.core.traversal.planner.Planner.OBJECTIVE_VARIABLE_COST_MAX_CHANGE;
import static grakn.core.traversal.planner.Planner.OBJECTIVE_VARIABLE_TO_PLANNER_COST_MIN_CHANGE;
import static graql.lang.common.GraqlToken.Predicate.Equality.EQ;
import static java.util.stream.Collectors.toSet;

@SuppressWarnings("NonAtomicOperationOnVolatileField") // Because Planner.optimise() is synchronised
public abstract class PlannerEdge extends TraversalEdge<PlannerVertex<?>> {

    protected final Planner planner;
    protected Directional forward;
    protected Directional backward;

    PlannerEdge(PlannerVertex<?> from, PlannerVertex<?> to) {
        super(from, to);
        this.planner = from.planner;
        assert this.planner.equals(to.planner);
        initialiseDirectionalEdges();
    }

    protected abstract void initialiseDirectionalEdges();

    static PlannerEdge of(PlannerVertex<?> from, PlannerVertex<?> to, StructureEdge structureEdge) {
        if (structureEdge.isEqual()) return new PlannerEdge.Equal(from, to);
        else if (structureEdge.isPredicate())
            return new PlannerEdge.Predicate(from.asThing(), to.asThing(), structureEdge.asPredicate().predicate());
        else if (structureEdge.isNative()) return PlannerEdge.Native.of(from, to, structureEdge.asNative());
        else throw GraknException.of(ILLEGAL_STATE);
    }

    Directional forward() {
        return forward;
    }

    Directional backward() {
        return backward;
    }

    void initialiseVariables() {
        forward.initialiseVariables();
        backward.initialiseVariables();
    }

    void initialiseConstraints() {
        String conPrefix = "edge::con::" + from + "::" + to + "::";
        MPConstraint conOneDirection = planner.solver().makeConstraint(1, 1, conPrefix + "one_direction");
        conOneDirection.setCoefficient(forward.varIsSelected, 1);
        conOneDirection.setCoefficient(backward.varIsSelected, 1);

        forward.initialiseConstraints();
        backward.initialiseConstraints();
    }

    void updateObjective(SchemaGraph graph) {
        forward.updateObjective(graph);
        backward.updateObjective(graph);
    }

    void recordCost() {
        forward.recordCost();
        backward.recordCost();
    }

    void recordValues() {
        forward.recordValues();
        backward.recordValues();
    }

    public abstract class Directional extends TraversalEdge<PlannerVertex<?>> {

        MPVariable varIsSelected;
        MPVariable[] varOrderAssignment;
        private MPVariable varOrderNumber;
        private int valueIsSelected;
        private int valueOrderNumber;
        private final String varPrefix;
        private final String conPrefix;
        private final PlannerEdge parent;
        private final boolean isForward;
        private double costPrevious;
        private double costNext;
        private boolean isInitialisedVariables;
        private boolean isInitialisedConstraints;

        Directional(PlannerVertex<?> from, PlannerVertex<?> to, PlannerEdge parent, boolean isForward) {
            super(from, to);
            this.parent = parent;
            this.isForward = isForward;
            this.costPrevious = 0.01; // non-zero value for safe division
            this.isInitialisedVariables = false;
            this.isInitialisedConstraints = false;
            this.varPrefix = "edge::var::" + from + "::" + to + "::";
            this.conPrefix = "edge::con::" + from + "::" + to + "::";
        }

        abstract void updateObjective(SchemaGraph graph);

        public boolean isSelected() {
            return valueIsSelected == 1;
        }

        public int orderNumber() {
            return valueOrderNumber;
        }

        public boolean isInitialisedVariables() {
            return isInitialisedVariables;
        }

        public boolean isInitialisedConstraints() {
            return isInitialisedConstraints;
        }

        void initialiseVariables() {
            varIsSelected = planner.solver().makeIntVar(0, 1, varPrefix + "is_selected");
            varOrderNumber = planner.solver().makeIntVar(0, planner.edges().size(), varPrefix + "order_number");
            varOrderAssignment = new MPVariable[planner.edges().size()];
            for (int i = 0; i < planner.edges().size(); i++) {
                varOrderAssignment[i] = planner.solver().makeIntVar(0, 1, varPrefix + "order_assignment[" + i + "]");
            }
            isInitialisedVariables = true;
        }

        void initialiseConstraints() {
            assert from.isInitialisedVariables();
            assert to.isInitialisedConstraints();
            initialiseConstraintsForOrderNumber();
            initialiseConstraintsForVertexFlow();
            initialiseConstraintsForOrderSequence();
            isInitialisedConstraints = true;
        }

        private void initialiseConstraintsForOrderNumber() {
            MPConstraint conOrderIfSelected = planner.solver().makeConstraint(0, 0, conPrefix + "order_if_selected");
            conOrderIfSelected.setCoefficient(varIsSelected, -1);

            MPConstraint conAssignOrderNumber = planner.solver().makeConstraint(0, 0, conPrefix + "assign_order_number");
            conAssignOrderNumber.setCoefficient(varOrderNumber, -1);

            for (int i = 0; i < planner.edges().size(); i++) {
                conOrderIfSelected.setCoefficient(varOrderAssignment[i], 1);
                conAssignOrderNumber.setCoefficient(varOrderAssignment[i], i + 1);
            }
        }

        private void initialiseConstraintsForVertexFlow() {
            MPConstraint conOutFromVertex = planner.solver().makeConstraint(0, 1, conPrefix + "out_from_vertex");
            conOutFromVertex.setCoefficient(from.varHasOutgoingEdges, 1);
            conOutFromVertex.setCoefficient(varIsSelected, -1);

            MPConstraint conInToVertex = planner.solver().makeConstraint(0, 1, conPrefix + "in_to_vertex");
            conInToVertex.setCoefficient(to.varHasIncomingEdges, 1);
            conInToVertex.setCoefficient(varIsSelected, -1);
        }

        private void initialiseConstraintsForOrderSequence() {
            to.outs().stream().filter(e -> !e.parent.equals(this.parent)).forEach(subsequentEdge -> {
                MPConstraint conOrderSequence = planner.solver().makeConstraint(0, planner.edges().size() + 1, conPrefix + "order_sequence");
                conOrderSequence.setCoefficient(to.varIsEndingVertex, planner.edges().size());
                conOrderSequence.setCoefficient(subsequentEdge.varOrderNumber, 1);
                conOrderSequence.setCoefficient(this.varIsSelected, -1);
                conOrderSequence.setCoefficient(this.varOrderNumber, -1);
            });
        }

        protected void setObjectiveCoefficient(double cost) {
            int exp = planner.edges().size() - 1;
            for (int i = 0; i < planner.edges().size(); i++) {
                planner.objective().setCoefficient(varOrderAssignment[i], cost * Math.pow(planner.branchingFactor, exp--));
            }
            planner.totalCostNext += cost;
            assert costPrevious > 0;
            if (cost / costPrevious >= OBJECTIVE_VARIABLE_COST_MAX_CHANGE &&
                    cost / planner.totalCostPrevious >= OBJECTIVE_VARIABLE_TO_PLANNER_COST_MIN_CHANGE) {
                planner.setOutOfDate();
            }
            costNext = cost;
        }

        private void recordCost() {
            costPrevious = costNext;
        }

        private void recordValues() {
            valueIsSelected = (int) Math.round(varIsSelected.solutionValue());
            valueOrderNumber = (int) Math.round(varOrderNumber.solutionValue());
        }

        public boolean isForward() { return isForward; }

        public boolean isBackward() { return !isForward; }
    }

    static class Equal extends PlannerEdge {

        Equal(PlannerVertex<?> from, PlannerVertex<?> to) {
            super(from, to);
        }

        @Override
        protected void initialiseDirectionalEdges() {
            forward = new Directional(from, to, this, true);
            backward = new Directional(to, from, this, false);
        }

        class Directional extends PlannerEdge.Directional {

            Directional(PlannerVertex<?> from, PlannerVertex<?> to, Equal parent, boolean isForward) {
                super(from, to, parent, isForward);
            }

            @Override
            void updateObjective(SchemaGraph graph) {
                setObjectiveCoefficient(0);
            }
        }
    }

    static class Predicate extends PlannerEdge {

        private final GraqlToken.Predicate.Equality predicate;

        Predicate(PlannerVertex<?> from, PlannerVertex<?> to, GraqlToken.Predicate.Equality predicate) {
            super(from, to);
            this.predicate = predicate;
        }

        @Override
        protected void initialiseDirectionalEdges() {
            forward = new Directional(from.asThing(), to.asThing(), this, true);
            backward = new Directional(to.asThing(), from.asThing(), this, false);
        }

        class Directional extends PlannerEdge.Directional {

            Directional(PlannerVertex.Thing from, PlannerVertex.Thing to, Predicate parent, boolean isForward) {
                super(from, to, parent, isForward);
            }

            public GraqlToken.Predicate predicate() {
                return predicate;
            }

            void updateObjective(SchemaGraph graph) {
                long cost;
                if (predicate.equals(EQ)) {
                    if (!to.asThing().props().types().isEmpty()) {
                        cost = to.asThing().props().types().size();
                    } else if (!from.asThing().props().types().isEmpty()) {
                        cost = graph.stats().attTypesWithValTypeComparableTo(from.asThing().props().types());
                    } else {
                        cost = graph.stats().attributeTypeCount();
                    }
                } else {
                    if (!to.asThing().props().types().isEmpty()) {
                        cost = graph.stats().instancesSum(to.asThing().props().types());
                    } else if (!from.asThing().props().types().isEmpty()) {
                        Stream<TypeVertex> types = from.asThing().props().types().stream().map(graph::getType);
                        cost = graph.stats().instancesSum(types);
                    } else {
                        cost = graph.stats().instancesTransitive(graph.rootAttributeType());
                    }
                }
                setObjectiveCoefficient(cost);
            }
        }
    }

    static abstract class Native extends PlannerEdge {

        Native(PlannerVertex<?> from, PlannerVertex<?> to) {
            super(from, to);
        }

        static PlannerEdge.Native of(PlannerVertex<?> from, PlannerVertex<?> to, StructureEdge.Native structureEdge) {
            if (structureEdge.encoding().equals(ISA)) {
                return new Isa(from.asThing(), to.asType(), structureEdge.isTransitive());
            } else if (structureEdge.encoding().isType()) {
                return Type.of(from.asType(), to.asType(), structureEdge);
            } else if (structureEdge.encoding().isThing()) {
                return Thing.of(from.asThing(), to.asThing(), structureEdge);
            } else {
                throw GraknException.of(ILLEGAL_STATE);
            }
        }

        static class Isa extends Native {

            private final boolean isTransitive;

            Isa(PlannerVertex.Thing from, PlannerVertex.Type to, boolean isTransitive) {
                super(from, to);
                this.isTransitive = isTransitive;
            }

            @Override
            protected void initialiseDirectionalEdges() {
                forward = new Forward(from.asThing(), to.asType(), this);
                backward = new Backward(to.asType(), from.asThing(), this);
            }

            private class Forward extends Directional {

                private Forward(PlannerVertex.Thing from, PlannerVertex.Type to, Isa parent) {
                    super(from, to, parent, true);
                }

                @Override
                void updateObjective(SchemaGraph graph) {
                    long cost;
                    if (!isTransitive) cost = 1;
                    else if (!to.asType().props().labels().isEmpty())
                        cost = graph.stats().subTypesDepth(to.asType().props().labels());
                    else cost = graph.stats().subTypesDepth(graph.rootThingType());
                    setObjectiveCoefficient(cost);
                }
            }

            private class Backward extends Directional {

                private Backward(PlannerVertex.Type from, PlannerVertex.Thing to, Isa parent) {
                    super(from, to, parent, false);
                }

                @Override
                void updateObjective(SchemaGraph graph) {
                    long cost;
                    if (!to.asThing().props().types().isEmpty()) {
                        if (!isTransitive) cost = graph.stats().instancesMax(to.asThing().props().types());
                        else
                            cost = graph.stats().instancesTransitiveMax(to.asThing().props().types(), to.asThing().props().types());
                    } else if (!from.asType().props().labels().isEmpty()) {
                        if (!isTransitive) cost = graph.stats().instancesMax(from.asType().props().labels());
                        else
                            cost = graph.stats().instancesTransitiveMax(from.asType().props().labels(), to.asType().props().labels());
                    } else {
                        if (!isTransitive) cost = graph.stats().instancesMax(graph.thingTypes());
                        else cost = graph.stats().instancesTransitiveMax(graph.thingTypes(), set());
                    }
                    setObjectiveCoefficient(cost);
                }
            }
        }

        static abstract class Type extends Native {

            protected final Encoding.Edge.Type encoding;
            protected final boolean isTransitive;

            Type(PlannerVertex.Type from, PlannerVertex.Type to, Encoding.Edge.Type encoding, boolean isTransitive) {
                super(from, to);
                this.encoding = encoding;
                this.isTransitive = isTransitive;
            }

            public Encoding.Edge.Type encoding() {
                return encoding;
            }

            public boolean isTransitive() {
                return isTransitive;
            }

            static Type of(PlannerVertex.Type from, PlannerVertex.Type to, StructureEdge.Native structureEdge) {
                Encoding.Edge.Type encoding = structureEdge.encoding().asType();
                switch (encoding) {
                    case SUB:
                        return new Type.Sub(from.asType(), to.asType(), structureEdge.isTransitive());
                    case OWNS:
                        return new Type.Owns(from.asType(), to.asType(), false);
                    case OWNS_KEY:
                        return new Type.Owns(from.asType(), to.asType(), true);
                    case PLAYS:
                        return new Type.Plays(from.asType(), to.asType());
                    case RELATES:
                        return new Type.Relates(from.asType(), to.asType());
                    default:
                        throw GraknException.of(UNRECOGNISED_VALUE);
                }
            }

            static class Sub extends Type {

                Sub(PlannerVertex.Type from, PlannerVertex.Type to, boolean isTransitive) {
                    super(from, to, SUB, isTransitive);
                }

                @Override
                protected void initialiseDirectionalEdges() {
                    forward = new Forward(from.asType(), to.asType(), this);
                    backward = new Backward(to.asType(), from.asType(), this);
                }

                private class Forward extends Directional {

                    private Forward(PlannerVertex.Type from, PlannerVertex.Type to, Type parent) {
                        super(from, to, parent, true);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        long cost;
                        if (!isTransitive) cost = 1;
                        else if (!to.asType().props().labels().isEmpty())
                            cost = graph.stats().subTypesDepth(to.asType().props().labels());
                        else cost = graph.stats().subTypesDepth(graph.rootThingType());
                        setObjectiveCoefficient(cost);
                    }
                }

                private class Backward extends Directional {

                    private Backward(PlannerVertex.Type from, PlannerVertex.Type to, Type parent) {
                        super(from, to, parent, false);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        double cost;
                        if (!to.asType().props().labels().isEmpty()) {
                            cost = to.asType().props().labels().size();
                        } else if (!from.asType().props().labels().isEmpty()) {
                            cost = graph.stats().subTypesMean(from.asType().props().labels(), isTransitive);
                        } else {
                            cost = graph.stats().subTypesMean(graph.thingTypes(), isTransitive);
                        }
                        setObjectiveCoefficient(cost);
                    }
                }
            }

            static class Owns extends Type {

                private final boolean isKey;

                Owns(PlannerVertex.Type from, PlannerVertex.Type to, boolean isKey) {
                    super(from, to, isKey ? OWNS_KEY : OWNS, false);
                    this.isKey = isKey;
                }

                public boolean isKey() {
                    return isKey;
                }

                @Override
                protected void initialiseDirectionalEdges() {
                    forward = new Forward(from.asType(), to.asType(), this);
                    backward = new Backward(to.asType(), from.asType(), this);
                }

                private class Forward extends Directional {

                    private Forward(PlannerVertex.Type from, PlannerVertex.Type to, Type parent) {
                        super(from, to, parent, true);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        double cost;
                        if (!to.asType().props().labels().isEmpty()) {
                            cost = to.asType().props().labels().size();
                        } else if (!from.asType().props().labels().isEmpty()) {
                            cost = graph.stats().outOwnsMean(from.asType().props().labels(), isKey);
                        } else {
                            // TODO: We can refine the branching factor by not strictly considering entity types only
                            cost = graph.stats().outOwnsMean(graph.entityTypes(), isKey);
                        }
                        setObjectiveCoefficient(cost);
                    }
                }

                private class Backward extends Directional {

                    private Backward(PlannerVertex.Type from, PlannerVertex.Type to, Type parent) {
                        super(from, to, parent, false);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        // TODO: We can refine the branching factor by not strictly considering entity types only
                        double cost;
                        if (!to.asType().props().labels().isEmpty()) {
                            cost = graph.stats().subTypesSum(to.asType().props().labels(), true);
                        } else if (!from.asType().props().labels().isEmpty()) {
                            cost = graph.stats().inOwnsMean(from.asType().props().labels(), isKey) *
                                    graph.stats().subTypesMean(graph.entityTypes(), true);
                        } else {
                            cost = graph.stats().inOwnsMean(graph.attributeTypes(), isKey) *
                                    graph.stats().subTypesMean(graph.entityTypes(), true);
                        }
                        setObjectiveCoefficient(cost);
                    }
                }
            }

            static class Plays extends Type {

                Plays(PlannerVertex.Type from, PlannerVertex.Type to) {
                    super(from, to, PLAYS, false);
                }

                @Override
                protected void initialiseDirectionalEdges() {
                    forward = new Forward(from.asType(), to.asType(), this);
                    backward = new Backward(to.asType(), from.asType(), this);
                }

                private class Forward extends Directional {

                    private Forward(PlannerVertex.Type from, PlannerVertex.Type to, Type parent) {
                        super(from, to, parent, true);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        double cost;
                        if (!to.asType().props().labels().isEmpty()) {
                            cost = to.asType().props().labels().size();
                        } else if (!from.asType().props().labels().isEmpty()) {
                            cost = graph.stats().outPlaysMean(from.asType().props().labels());
                        } else {
                            // TODO: We can refine the branching factor by not strictly considering entity types only
                            cost = graph.stats().outPlaysMean(graph.entityTypes());
                        }
                        setObjectiveCoefficient(cost);
                    }
                }

                private class Backward extends Directional {

                    private Backward(PlannerVertex.Type from, PlannerVertex.Type to, Type parent) {
                        super(from, to, parent, false);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        // TODO: We can refine the branching factor by not strictly considering entity types only
                        double cost;
                        if (!to.asType().props().labels().isEmpty()) {
                            cost = graph.stats().subTypesSum(to.asType().props().labels(), true);
                        } else if (!from.asType().props().labels().isEmpty()) {
                            cost = graph.stats().inPlaysMean(from.asType().props().labels()) *
                                    graph.stats().subTypesMean(graph.entityTypes(), true);
                        } else {
                            cost = graph.stats().inPlaysMean(graph.attributeTypes()) *
                                    graph.stats().subTypesMean(graph.entityTypes(), true);
                        }
                        setObjectiveCoefficient(cost);
                    }
                }
            }

            static class Relates extends Type {

                Relates(PlannerVertex.Type from, PlannerVertex.Type to) {
                    super(from, to, RELATES, false);
                }

                @Override
                protected void initialiseDirectionalEdges() {
                    forward = new Forward(from.asType(), to.asType(), this);
                    backward = new Backward(to.asType(), from.asType(), this);
                }

                private class Forward extends Directional {

                    private Forward(PlannerVertex.Type from, PlannerVertex.Type to, Type parent) {
                        super(from, to, parent, true);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        double cost;
                        if (!to.asType().props().labels().isEmpty()) {
                            cost = to.asType().props().labels().size();
                        } else if (!from.asType().props().labels().isEmpty()) {
                            cost = graph.stats().outRelates(from.asType().props().labels());
                        } else {
                            cost = graph.stats().outRelates(graph.relationTypes());
                        }
                        setObjectiveCoefficient(cost);
                    }
                }

                private class Backward extends Directional {

                    private Backward(PlannerVertex.Type from, PlannerVertex.Type to, Type parent) {
                        super(from, to, parent, false);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        double cost;
                        if (!to.asType().props().labels().isEmpty()) {
                            cost = graph.stats().subTypesMean(to.asType().props().labels(), true);
                        } else if (!from.asType().props().labels().isEmpty()) {
                            Stream<TypeVertex> relationTypes = from.asType().props().labels().stream().map(l -> {
                                assert l.scope().isPresent();
                                return Label.of(l.scope().get());
                            }).map(graph::getType);
                            cost = graph.stats().subTypesMean(relationTypes, true);
                        } else {
                            cost = graph.stats().subTypesMean(graph.relationTypes(), true);
                        }
                        setObjectiveCoefficient(cost);
                    }
                }
            }
        }

        static abstract class Thing extends Native {

            private final Encoding.Edge.Thing encoding;

            Thing(PlannerVertex.Thing from, PlannerVertex.Thing to, Encoding.Edge.Thing encoding) {
                super(from, to);
                this.encoding = encoding;
            }

            public Encoding.Edge.Thing encoding() {
                return encoding;
            }

            static Thing of(PlannerVertex.Thing from, PlannerVertex.Thing to, StructureEdge.Native structureEdge) {
                Encoding.Edge.Thing encoding = structureEdge.encoding().asThing();
                switch (encoding) {
                    case HAS:
                        return new Has(from, to);
                    case PLAYING:
                        return new Playing(from, to);
                    case RELATING:
                        return new Relating(from, to);
                    case ROLEPLAYER:
                        return new RolePlayer(from, to, structureEdge.asOptimised().types());
                    default:
                        throw GraknException.of(UNRECOGNISED_VALUE);
                }
            }

            static class Has extends Thing {

                Has(PlannerVertex.Thing from, PlannerVertex.Thing to) {
                    super(from, to, HAS);
                }

                @Override
                protected void initialiseDirectionalEdges() {
                    forward = new Forward(from.asThing(), to.asThing(), this);
                    backward = new Backward(to.asThing(), from.asThing(), this);
                }

                private class Forward extends Directional {

                    private Forward(PlannerVertex.Thing from, PlannerVertex.Thing to, Thing parent) {
                        super(from, to, parent, true);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        Set<TypeVertex> ownerTypes = null;
                        Set<TypeVertex> attTypes = null;
                        Map<TypeVertex, Set<TypeVertex>> ownerToAttributeTypes = new HashMap<>();

                        if (!from.asThing().props().types().isEmpty()) {
                            ownerTypes = from.asThing().props().types().stream().map(graph::getType).collect(toSet());
                        }
                        if (!to.asThing().props().types().isEmpty()) {
                            attTypes = to.asThing().props().types().stream().map(graph::getType).collect(toSet());
                        }

                        if (ownerTypes != null && attTypes != null) {
                            for (final TypeVertex ownerType : ownerTypes)
                                ownerToAttributeTypes.put(ownerType, attTypes);
                        } else if (ownerTypes != null) {
                            ownerTypes.stream().map(o -> pair(o, graph.ownedAttributeTypes(o))).forEach(
                                    pair -> ownerToAttributeTypes.put(pair.first(), pair.second())
                            );
                        } else if (attTypes != null) {
                            attTypes.stream().flatMap(a -> graph.ownersOfAttributeType(a).stream().map(o -> pair(o, a)))
                                    .forEach(pair -> ownerToAttributeTypes.computeIfAbsent(pair.first(), o -> new HashSet<>())
                                            .add(pair.second()));
                        } else { // fromTypes == null && toTypes == null;
                            // TODO: We can refine this by not strictly considering entities being the only divisor
                            ownerToAttributeTypes.put(graph.rootEntityType(), set(graph.rootAttributeType()));
                        }

                        double cost = 0.0;
                        for (TypeVertex owner : ownerToAttributeTypes.keySet()) {
                            cost += (double) graph.stats().countHasEdges(owner, ownerToAttributeTypes.get(owner)) /
                                    owner.instancesCount();
                        }
                        cost /= ownerToAttributeTypes.size();
                        setObjectiveCoefficient(cost);
                    }
                }

                private class Backward extends Directional {

                    private Backward(PlannerVertex.Thing from, PlannerVertex.Thing to, Thing parent) {
                        super(from, to, parent, false);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        Set<TypeVertex> ownerTypes = null;
                        Set<TypeVertex> attTypes = null;
                        Map<TypeVertex, Set<TypeVertex>> attributeTypesToOwners = new HashMap<>();

                        if (!from.asThing().props().types().isEmpty()) {
                            attTypes = from.asThing().props().types().stream().map(graph::getType).collect(toSet());
                        }
                        if (!to.asThing().props().types().isEmpty()) {
                            ownerTypes = to.asThing().props().types().stream().map(graph::getType).collect(toSet());
                        }

                        if (ownerTypes != null && attTypes != null) {
                            for (final TypeVertex attType : attTypes) attributeTypesToOwners.put(attType, ownerTypes);
                        } else if (attTypes != null) {
                            attTypes.stream().map(a -> pair(a, graph.ownersOfAttributeType(a))).forEach(
                                    pair -> attributeTypesToOwners.put(pair.first(), pair.second())
                            );
                        } else if (ownerTypes != null) {
                            ownerTypes.stream().flatMap(o -> graph.ownedAttributeTypes(o).stream().map(a -> pair(a, o)))
                                    .forEach(pair -> attributeTypesToOwners.computeIfAbsent(pair.first(), a -> new HashSet<>())
                                            .add(pair.second()));
                        } else { // fromTypes == null && toTypes == null;
                            attributeTypesToOwners.put(graph.rootAttributeType(), set(graph.rootThingType()));
                        }

                        double cost = 0.0;
                        for (Map.Entry<TypeVertex, Set<TypeVertex>> entry : attributeTypesToOwners.entrySet()) {
                            cost += (double) graph.stats().countHasEdges(entry.getValue(), entry.getKey()) /
                                    entry.getKey().instancesCount();
                        }
                        cost /= attributeTypesToOwners.size();
                        setObjectiveCoefficient(cost);
                    }
                }
            }

            static class Playing extends Thing {

                Playing(PlannerVertex.Thing from, PlannerVertex.Thing to) {
                    super(from, to, PLAYING);
                }

                @Override
                protected void initialiseDirectionalEdges() {
                    forward = new Forward(from.asThing(), to.asThing(), this);
                    backward = new Backward(to.asThing(), from.asThing(), this);
                }

                private class Forward extends Directional {

                    private Forward(PlannerVertex.Thing from, PlannerVertex.Thing to, Thing parent) {
                        super(from, to, parent, true);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        double cost;
                        if (!to.asThing().props().types().isEmpty() && !from.asThing().props().types().isEmpty()) {
                            cost = (double) graph.stats().instancesSum(to.asThing().props().types()) /
                                    graph.stats().instancesSum(from.asThing().props().types());
                        } else {
                            // TODO: We can refine this by not strictly considering entities being the only divisor
                            cost = (double) graph.stats().instancesTransitive(graph.rootRoleType()) /
                                    graph.stats().instancesTransitive(graph.rootEntityType());
                        }
                        setObjectiveCoefficient(cost);
                    }
                }

                private class Backward extends Directional {

                    private Backward(PlannerVertex.Thing from, PlannerVertex.Thing to, Thing parent) {
                        super(from, to, parent, false);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        setObjectiveCoefficient(1);
                    }
                }
            }

            static class Relating extends Thing {

                Relating(PlannerVertex.Thing from, PlannerVertex.Thing to) {
                    super(from, to, RELATING);
                }

                @Override
                protected void initialiseDirectionalEdges() {
                    forward = new Forward(from.asThing(), to.asThing(), this);
                    backward = new Backward(to.asThing(), from.asThing(), this);
                }

                private class Forward extends Directional {

                    private Forward(PlannerVertex.Thing from, PlannerVertex.Thing to, Thing parent) {
                        super(from, to, parent, true);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        double cost;
                        if (!to.asThing().props().types().isEmpty()) {
                            cost = 0;
                            for (final Label roleType : to.asThing().props().types()) {
                                assert roleType.scope().isPresent();
                                cost += (double) graph.getType(roleType).instancesCount() /
                                        graph.getType(roleType.scope().get()).instancesCount();
                            }
                            cost = cost / to.asThing().props().types().size();
                        } else {
                            cost = (double) graph.stats().instancesTransitive(graph.rootRoleType()) /
                                    graph.stats().instancesTransitive(graph.rootRelationType());
                        }
                        setObjectiveCoefficient(cost);
                    }
                }

                private class Backward extends Directional {

                    private Backward(PlannerVertex.Thing from, PlannerVertex.Thing to, Thing parent) {
                        super(from, to, parent, false);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        setObjectiveCoefficient(1);
                    }
                }
            }

            static class RolePlayer extends Thing {

                private final Set<Label> roleTypes;

                RolePlayer(PlannerVertex.Thing from, PlannerVertex.Thing to, Set<Label> roleTypes) {
                    super(from, to, ROLEPLAYER);
                    this.roleTypes = roleTypes;
                }

                @Override
                protected void initialiseDirectionalEdges() {
                    forward = new Forward(from.asThing(), to.asThing(), this);
                    backward = new Backward(to.asThing(), from.asThing(), this);
                }

                private class Forward extends Directional {

                    private Forward(PlannerVertex.Thing from, PlannerVertex.Thing to, Thing parent) {
                        super(from, to, parent, true);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        double cost;
                        if (!roleTypes.isEmpty()) {
                            cost = 0;
                            for (final Label roleType : roleTypes) {
                                assert roleType.scope().isPresent();
                                cost += (double) graph.getType(roleType).instancesCount() /
                                        graph.getType(roleType.scope().get()).instancesCount();
                            }
                            cost = cost / roleTypes.size();
                        } else {
                            cost = (double) graph.stats().instancesTransitive(graph.rootRoleType()) /
                                    graph.stats().instancesTransitive(graph.rootRelationType());
                        }
                        setObjectiveCoefficient(cost);
                    }
                }

                private class Backward extends Directional {

                    private Backward(PlannerVertex.Thing from, PlannerVertex.Thing to, Thing parent) {
                        super(from, to, parent, false);
                    }

                    @Override
                    void updateObjective(SchemaGraph graph) {
                        double cost;
                        if (!roleTypes.isEmpty() && !from.asThing().props().types().isEmpty()) {
                            cost = (double) graph.stats().instancesSum(roleTypes) /
                                    graph.stats().instancesSum(from.asThing().props().types());
                        } else {
                            // TODO: We can refine this by not strictly considering entities being the only divisor
                            cost = (double) graph.stats().instancesTransitive(graph.rootRoleType()) /
                                    graph.stats().instancesTransitive(graph.rootEntityType());
                        }
                        setObjectiveCoefficient(cost);
                    }
                }
            }
        }
    }
}
