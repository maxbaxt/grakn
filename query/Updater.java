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

package grakn.core.query;

import grabl.tracing.client.GrablTracingThreadStatic;
import grakn.core.common.exception.GraknException;
import grakn.core.common.iterator.ResourceIterator;
import grakn.core.common.parameters.Context;
import grakn.core.concept.ConceptManager;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.pattern.variable.ThingVariable;
import grakn.core.pattern.variable.VariableRegistry;
import grakn.core.reasoner.Reasoner;
import graql.lang.query.GraqlUpdate;

import java.util.List;
import java.util.Set;

import static grabl.tracing.client.GrablTracingThreadStatic.traceOnThread;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.ILLEGAL_TYPE_VARIABLE_IN_DELETE;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.ILLEGAL_TYPE_VARIABLE_IN_INSERT;
import static grakn.core.common.iterator.Iterators.iterate;

public class Updater {

    private static final String TRACE_PREFIX = "updater.";
    private final Matcher matcher;
    private final ConceptManager conceptMgr;
    private final Set<ThingVariable> deleteVariables;
    private final Set<ThingVariable> insertVariables;
    private final Context.Query context;

    public Updater(Matcher matcher, ConceptManager conceptMgr, Set<ThingVariable> deleteVariables,
                   Set<ThingVariable> insertVariables, Context.Query context) {
        this.matcher = matcher;
        this.conceptMgr = conceptMgr;
        this.deleteVariables = deleteVariables;
        this.insertVariables = insertVariables;
        this.context = context;
    }

    public static Updater create(Reasoner reasoner, ConceptManager conceptMgr, GraqlUpdate query, Context.Query context) {

        try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "create")) {
            VariableRegistry deleteRegistry = VariableRegistry.createFromThings(query.deleteVariables(), false);
            iterate(deleteRegistry.types()).filter(t -> !t.reference().isLabel()).forEachRemaining(t -> {
                throw GraknException.of(ILLEGAL_TYPE_VARIABLE_IN_DELETE, t.reference());
            });

            VariableRegistry insertRegistry = VariableRegistry.createFromThings(query.insertVariables());
            iterate(insertRegistry.types()).filter(t -> !t.reference().isLabel()).forEachRemaining(t -> {
                throw GraknException.of(ILLEGAL_TYPE_VARIABLE_IN_INSERT, t.reference());
            });

            assert query.match().namedVariablesUnbound().containsAll(query.namedDeleteVariablesUnbound());
            Matcher matcher = Matcher.create(reasoner, query.match().get(query.namedDeleteVariablesUnbound()));
            return new Updater(matcher, conceptMgr, deleteRegistry.things(), insertRegistry.things(), context);
        }
    }

    public ResourceIterator<ConceptMap> execute() {
        try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "execute")) {
            List<ConceptMap> matches = matcher.execute(context).onError(conceptMgr::exception).toList();
            List<ConceptMap> answers = iterate(matches).map(matched -> {
                new Deleter.Operation(matched, deleteVariables).execute();
                return new Inserter.Operation(conceptMgr, matched, insertVariables).execute();
            }).toList();
            return iterate(answers);
        }
    }
}
