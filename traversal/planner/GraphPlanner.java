/*
 * Copyright (C) 2021 Grakn Labs
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
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPSolverParameters;
import com.google.ortools.linearsolver.MPVariable;
import grakn.core.common.exception.GraknException;
import grakn.core.concurrent.lock.ManagedCountDownLatch;
import grakn.core.graph.GraphManager;
import grakn.core.traversal.common.Identifier;
import grakn.core.traversal.graph.TraversalEdge;
import grakn.core.traversal.procedure.GraphProcedure;
import grakn.core.traversal.structure.Structure;
import grakn.core.traversal.structure.StructureEdge;
import grakn.core.traversal.structure.StructureVertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.ortools.linearsolver.MPSolver.ResultStatus.ABNORMAL;
import static com.google.ortools.linearsolver.MPSolver.ResultStatus.FEASIBLE;
import static com.google.ortools.linearsolver.MPSolver.ResultStatus.INFEASIBLE;
import static com.google.ortools.linearsolver.MPSolver.ResultStatus.OPTIMAL;
import static com.google.ortools.linearsolver.MPSolver.ResultStatus.UNBOUNDED;
import static com.google.ortools.linearsolver.MPSolverParameters.IncrementalityValues.INCREMENTALITY_ON;
import static com.google.ortools.linearsolver.MPSolverParameters.IntegerParam.INCREMENTALITY;
import static com.google.ortools.linearsolver.MPSolverParameters.IntegerParam.PRESOLVE;
import static com.google.ortools.linearsolver.MPSolverParameters.PresolveValues.PRESOLVE_ON;
import static grakn.core.common.exception.ErrorMessage.Internal.UNEXPECTED_PLANNING_ERROR;
import static java.time.Duration.between;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

public class GraphPlanner implements Planner {

    private static final Logger LOG = LoggerFactory.getLogger(GraphPlanner.class);

    static final long DEFAULT_TIME_LIMIT_MILLIS = 100;
    static final long HIGHER_TIME_LIMIT_MILLIS = 200;
    static final double OBJECTIVE_COEFFICIENT_MAX_EXPONENT_DEFAULT = 3.0;
    static final double OBJECTIVE_PLANNER_COST_MAX_CHANGE = 0.2;
    static final double OBJECTIVE_VARIABLE_COST_MAX_CHANGE = 2.0;
    static final double OBJECTIVE_VARIABLE_TO_PLANNER_COST_MIN_CHANGE = 0.02;

    private final MPSolver solver;
    private final MPSolverParameters parameters;
    private final Map<Identifier, PlannerVertex<?>> vertices;
    private final Set<PlannerEdge<?, ?>> edges;
    private final AtomicBoolean isOptimising;
    private final ManagedCountDownLatch procedureLatch;

    protected volatile GraphProcedure procedure;
    private volatile MPSolver.ResultStatus resultStatus;
    private volatile boolean isUpToDate;
    private volatile long totalDuration;
    private volatile long snapshot;

    volatile double totalCostLastRecorded;
    double totalCostNext;
    double branchingFactor;
    double costExponentUnit;

    private GraphPlanner() {
        solver = MPSolver.createSolver("SCIP");
        solver.objective().setMinimization();
        parameters = new MPSolverParameters();
        parameters.setIntegerParam(PRESOLVE, PRESOLVE_ON.swigValue());
        parameters.setIntegerParam(INCREMENTALITY, INCREMENTALITY_ON.swigValue());
        vertices = new HashMap<>();
        edges = new HashSet<>();
        procedureLatch = new ManagedCountDownLatch(1);
        isOptimising = new AtomicBoolean(false);
        resultStatus = MPSolver.ResultStatus.NOT_SOLVED;
        isUpToDate = false;
        totalDuration = 0L;
        totalCostLastRecorded = 0.01;
        totalCostNext = 0.01;
        branchingFactor = 0.01;
        costExponentUnit = 0.1;
        snapshot = -1L;
    }

    static GraphPlanner create(Structure structure) {
        assert structure.vertices().size() > 1;
        GraphPlanner planner = new GraphPlanner();
        Set<StructureVertex<?>> registeredVertices = new HashSet<>();
        Set<StructureEdge<?, ?>> registeredEdges = new HashSet<>();
        structure.vertices().forEach(vertex -> planner.registerVertex(vertex, registeredVertices, registeredEdges));
        assert planner.vertices().size() > 1 && !planner.edges().isEmpty();
        planner.initialise();
        return planner;
    }

    @Override
    public GraphProcedure procedure() {
        if (procedure == null) {
            assert isOptimising.get();
            try {
                procedureLatch.await();
                assert procedure != null;
            } catch (InterruptedException e) {
                throw GraknException.of(e);
            }
        }
        return procedure;
    }

    @Override
    public boolean isGraph() { return true; }

    @Override
    public GraphPlanner asGraph() { return this; }

    public Collection<PlannerVertex<?>> vertices() {
        return vertices.values();
    }

    public Set<PlannerEdge<?, ?>> edges() {
        return edges;
    }

    void setOutOfDate() {
        this.isUpToDate = false;
    }

    private boolean isUpToDate() {
        return isUpToDate;
    }

    private boolean isPlanned() {
        return resultStatus == FEASIBLE || resultStatus == OPTIMAL;
    }

    private boolean isOptimal() {
        return resultStatus == OPTIMAL;
    }

    private boolean isError() {
        return resultStatus == INFEASIBLE || resultStatus == UNBOUNDED || resultStatus == ABNORMAL;
    }

    MPSolver solver() {
        return solver;
    }

    MPObjective objective() {
        return solver.objective();
    }

    private void registerVertex(StructureVertex<?> structureVertex, Set<StructureVertex<?>> registeredVertices,
                                Set<StructureEdge<?, ?>> registeredEdges) {
        if (registeredVertices.contains(structureVertex)) return;
        registeredVertices.add(structureVertex);
        List<StructureVertex<?>> adjacents = new ArrayList<>();
        PlannerVertex<?> vertex = vertex(structureVertex);
        if (vertex.isThing()) vertex.asThing().props(structureVertex.asThing().props());
        else vertex.asType().props(structureVertex.asType().props());
        structureVertex.outs().forEach(structureEdge -> {
            if (!registeredEdges.contains(structureEdge)) {
                registeredEdges.add(structureEdge);
                adjacents.add(structureEdge.to());
                registerEdge(structureEdge);
            }
        });
        structureVertex.ins().forEach(structureEdge -> {
            if (!registeredEdges.contains(structureEdge)) {
                registeredEdges.add(structureEdge);
                adjacents.add(structureEdge.from());
                registerEdge(structureEdge);
            }
        });
        adjacents.forEach(v -> registerVertex(v, registeredVertices, registeredEdges));
    }

    private void registerEdge(StructureEdge<?, ?> structureEdge) {
        PlannerVertex<?> from = vertex(structureEdge.from());
        PlannerVertex<?> to = vertex(structureEdge.to());
        PlannerEdge<?, ?> edge = PlannerEdge.of(from, to, structureEdge);
        edges.add(edge);
        from.out(edge);
        to.in(edge);
    }

    private PlannerVertex<?> vertex(StructureVertex<?> structureVertex) {
        if (structureVertex.isThing()) return thingVertex(structureVertex.asThing());
        else return typeVertex(structureVertex.asType());
    }

    private PlannerVertex.Thing thingVertex(StructureVertex.Thing structureVertex) {
        return vertices.computeIfAbsent(
                structureVertex.id(), i -> new PlannerVertex.Thing(i, this)
        ).asThing();
    }

    private PlannerVertex.Type typeVertex(StructureVertex.Type structureVertex) {
        return vertices.computeIfAbsent(
                structureVertex.id(), i -> new PlannerVertex.Type(i, this)
        ).asType();
    }

    private void initialise() {
        initialiseVariables();
        initialiseConstraintsForVariables();
        initialiseConstraintsForEdges();
    }

    private void initialiseVariables() {
        vertices.values().forEach(PlannerVertex::initialiseVariables);
        edges.forEach(PlannerEdge::initialiseVariables);
    }

    private void initialiseConstraintsForVariables() {
        String conPrefix = "planner_vertex_con_";
        vertices.values().forEach(PlannerVertex::initialiseConstraints);
        MPConstraint conOneStartingVertex = solver.makeConstraint(1, 1, conPrefix + "one_starting_vertex");
        for (PlannerVertex<?> vertex : vertices.values()) {
            conOneStartingVertex.setCoefficient(vertex.varIsStartingVertex, 1);
        }
    }

    private void initialiseConstraintsForEdges() {
        String conPrefix = "planner_edge_con_";
        edges.forEach(PlannerEdge::initialiseConstraints);
        for (int i = 0; i < edges.size(); i++) {
            MPConstraint conOneEdgeAtOrderI = solver.makeConstraint(1, 1, conPrefix + "one_edge_at_order_" + i + 1);
            for (PlannerEdge<?, ?> edge : edges) {
                conOneEdgeAtOrderI.setCoefficient(edge.forward().varOrderAssignment[i], 1);
                conOneEdgeAtOrderI.setCoefficient(edge.backward().varOrderAssignment[i], 1);
            }
        }
    }

    private void updateObjective(GraphManager graph) {
        if (snapshot < graph.data().stats().snapshot()) {
            snapshot = graph.data().stats().snapshot();
            totalCostNext = 0.1;
            setBranchingFactor(graph);
            setCostExponentUnit(graph);
            computeTotalCostNext(graph);

            assert !Double.isNaN(totalCostNext) && !Double.isNaN(totalCostLastRecorded) && totalCostLastRecorded > 0;
            if (totalCostNext / totalCostLastRecorded >= OBJECTIVE_PLANNER_COST_MAX_CHANGE) setOutOfDate();
            if (!isUpToDate) {
                totalCostLastRecorded = totalCostNext;
                vertices.values().forEach(PlannerVertex::recordCost);
                edges.forEach(PlannerEdge::recordCost);
                new Initialiser().execute();
            }
        }
        LOG.trace(solver.exportModelAsLpFormat());
    }

    void updateCostNext(double costPrevious, double costNext) {
        assert !Double.isNaN(totalCostNext);
        assert !Double.isNaN(totalCostLastRecorded);
        assert !Double.isNaN(costPrevious);
        assert !Double.isNaN(costNext);
        assert costPrevious > 0 && totalCostLastRecorded > 0;

        totalCostNext += costNext;
        assert !Double.isNaN(totalCostNext);

        if (costNext / costPrevious >= OBJECTIVE_VARIABLE_COST_MAX_CHANGE &&
                costNext / totalCostLastRecorded >= OBJECTIVE_VARIABLE_TO_PLANNER_COST_MIN_CHANGE) {
            setOutOfDate();
        }
    }

    private void setBranchingFactor(GraphManager graph) {
        // TODO: We can refine the branching factor by not strictly considering entities being the only divisor
        double entities = graph.data().stats().thingVertexTransitiveCount(graph.schema().rootEntityType());
        double roles = graph.data().stats().thingVertexTransitiveCount(graph.schema().rootRoleType());
        if (roles == 0) roles += 1;
        if (entities > 0) branchingFactor = roles / entities;
        assert !Double.isNaN(branchingFactor);
    }

    private void setCostExponentUnit(GraphManager graph) {
        double expUnit, expMaxInc, expMax;
        expUnit = (OBJECTIVE_COEFFICIENT_MAX_EXPONENT_DEFAULT - 1) / edges.size();
        expUnit = Math.min(expUnit, 1.0);

        expMaxInc = expUnit * edges.size();
        expMax = 1 + expMaxInc;
        long things = graph.data().stats().thingVertexTransitiveCount(graph.schema().rootThingType());
        double maxCoefficient = Math.pow(things, expMax);
        if (Double.isNaN(maxCoefficient) || Double.isInfinite(maxCoefficient) || maxCoefficient > Long.MAX_VALUE) {
            expMax = Math.log(Long.MAX_VALUE) / Math.log(things);
            expMaxInc = expMax - 1;
        }
        assert !Double.isNaN(expMaxInc) && expMaxInc > 0;
        costExponentUnit = expMaxInc / edges.size();
    }

    private void computeTotalCostNext(GraphManager graph) {
        vertices.values().forEach(v -> v.updateObjective(graph));
        edges.forEach(e -> e.updateObjective(graph));
    }

    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    void optimise(GraphManager graph, boolean extraTime) {
        if (isOptimising.compareAndSet(false, true)) {
            updateObjective(graph);
            if (isUpToDate() && isOptimal()) {
                if (LOG.isDebugEnabled()) LOG.debug("Optimisation still optimal and up-to-date");
            } else {
                // TODO: we should have a more clever logic to allocate extra time
                long allocatedDuration = extraTime ? HIGHER_TIME_LIMIT_MILLIS : DEFAULT_TIME_LIMIT_MILLIS;
                Instant start, endSolver, end;
                totalDuration += allocatedDuration;
                solver.setTimeLimit(totalDuration);

                start = Instant.now();
                resultStatus = solver.solve(parameters);
                resetInitialValues();
                endSolver = Instant.now();
                if (isError()) throwPlanningError();
                else assert isPlanned();

                createProcedure();
                end = Instant.now();

                isUpToDate = true;
                totalDuration -= allocatedDuration - between(start, endSolver).toMillis();
                printDebug(start, endSolver, end);
            }
            isOptimising.set(false);
        }
    }

    private void resetInitialValues() {
        solver.setHint(new MPVariable[]{}, new double[]{});
    }

    private void throwPlanningError() {
        LOG.error(toString());
        LOG.error(solver.exportModelAsLpFormat());
        throw GraknException.of(UNEXPECTED_PLANNING_ERROR);
    }

    private void printDebug(Instant start, Instant endSolver, Instant end) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("Optimisation status         : %s", resultStatus.name()));
            LOG.debug(String.format("Solver duration             : %s (ms)", between(start, endSolver).toMillis()));
            LOG.debug(String.format("Procedure creation duration : %s (ms)", between(endSolver, end).toMillis()));
            LOG.debug(String.format("Total duration ------------ : %s (ms)", between(start, end).toMillis()));
        }
    }

    private void createProcedure() {
        vertices.values().forEach(PlannerVertex::recordResults);
        edges.forEach(PlannerEdge::recordResults);
        procedure = GraphProcedure.create(this);
        if (procedureLatch.getCount() > 0) procedureLatch.countDown();
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("Graph Planner: {");
        List<PlannerEdge<?, ?>> plannerEdges = new ArrayList<>(edges);
        plannerEdges.sort(Comparator.comparing(TraversalEdge::toString));
        List<PlannerVertex<?>> plannerVertices = new ArrayList<>(vertices.values());
        plannerVertices.sort(Comparator.comparing(v -> v.id().toString()));

        str.append("\n\tvertices:");
        for (PlannerVertex<?> v : plannerVertices) {
            str.append("\n\t\t").append(v);
        }
        str.append("\n\tedges:");
        for (PlannerEdge<?, ?> e : plannerEdges) {
            str.append("\n\t\t").append(e);
        }
        str.append("\n}");
        return str.toString();
    }

    private class Initialiser {

        private final LinkedHashSet<PlannerVertex<?>> queue;
        private final MPVariable[] variables;
        private final double[] initialValues;
        private int edgeCount;

        private Initialiser() {
            queue = new LinkedHashSet<>();
            edgeCount = 0;

            int count = countVariables();
            variables = new MPVariable[count];
            initialValues = new double[count];
        }

        private int countVariables() {
            int vertexVars = 4 * vertices.size();
            int edgeVars = (2 + edges.size()) * edges.size() * 2;
            return vertexVars + edgeVars;
        }

        public void execute() {
            resetInitialValues();
            PlannerVertex<?> start = vertices.values().stream().min(comparing(v -> v.costLastRecorded)).get();
            start.setStartingVertexInitial();
            queue.add(start);
            while (!queue.isEmpty()) {
                PlannerVertex<?> vertex = queue.iterator().next();
                List<PlannerEdge.Directional<?, ?>> outgoing = vertex.outs().stream()
                        .filter(e -> !e.hasInitialValue() && !(e.isSelfClosure() && e.direction().isBackward()))
                        .sorted(comparing(e -> e.costLastRecorded)).collect(toList());
                if (!outgoing.isEmpty()) {
                    vertex.setHasOutgoingEdgesInitial();
                    outgoing.forEach(e -> {
                        e.setInitialValue(++edgeCount);
                        e.to().setHasIncomingEdgesInitial();
                        queue.add(e.to());
                    });
                } else {
                    vertex.setEndingVertexInitial();
                }
                queue.remove(vertex);
            }

            int index = 0;
            for (PlannerVertex<?> v : vertices.values()) index = v.recordInitial(variables, initialValues, index);
            for (PlannerEdge<?, ?> e : edges) index = e.recordInitial(variables, initialValues, index);
            assert index == variables.length && index == initialValues.length;

            solver.setHint(variables, initialValues);
        }

        private void resetInitialValues() {
            vertices.values().forEach(PlannerVertex::resetInitialValue);
            edges.forEach(PlannerEdge::resetInitialValue);
        }
    }
}
