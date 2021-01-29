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
import grakn.core.concept.thing.Attribute;
import grakn.core.concept.thing.Relation;
import grakn.core.concept.thing.Thing;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.EntityType;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.RoleType;
import grakn.core.concept.type.ThingType;
import grakn.core.concept.type.impl.ThingTypeImpl;
import grakn.core.pattern.constraint.thing.HasConstraint;
import grakn.core.pattern.constraint.thing.IsaConstraint;
import grakn.core.pattern.constraint.thing.ValueConstraint;
import grakn.core.pattern.constraint.type.LabelConstraint;
import grakn.core.pattern.variable.ThingVariable;
import grakn.core.pattern.variable.VariableRegistry;
import grakn.core.reasoner.Reasoner;
import graql.lang.pattern.variable.Reference;
import graql.lang.pattern.variable.UnboundVariable;
import graql.lang.query.GraqlInsert;
import graql.lang.query.GraqlMatch;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static grabl.tracing.client.GrablTracingThreadStatic.traceOnThread;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.ATTRIBUTE_VALUE_MISSING;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.ATTRIBUTE_VALUE_TOO_MANY;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.ILLEGAL_ABSTRACT_WRITE;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.ILLEGAL_IS_CONSTRAINT;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.ILLEGAL_TYPE_VARIABLE_IN_INSERT;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.INSERT_RELATION_CONSTRAINT_TOO_MANY;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.RELATION_CONSTRAINT_MISSING;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.THING_IID_NOT_INSERTABLE;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.THING_ISA_MISSING;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.THING_ISA_REINSERTION;
import static grakn.core.common.exception.ErrorMessage.TypeRead.TYPE_NOT_FOUND;
import static grakn.core.common.iterator.Iterators.iterate;
import static grakn.core.common.iterator.Iterators.single;
import static grakn.core.common.parameters.Arguments.Query.Producer.EXHAUSTIVE;
import static grakn.core.query.common.Util.getRoleType;

public class Inserter {

    private static final String TRACE_PREFIX = "inserter.";

    private final Matcher matcher;
    private final ConceptManager conceptMgr;
    private final Set<ThingVariable> variables;
    private final Context.Query context;

    public Inserter(@Nullable Matcher matcher, ConceptManager conceptMgr,
                    Set<ThingVariable> variables, Context.Query context) {
        this.matcher = matcher;
        this.conceptMgr = conceptMgr;
        this.variables = variables;
        this.context = context;
        this.context.producer(EXHAUSTIVE);
    }

    public static Inserter create(Reasoner reasoner, ConceptManager conceptMgr, GraqlInsert query, Context.Query context) {
        try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "create")) {
            VariableRegistry registry = VariableRegistry.createFromThings(query.variables());
            iterate(registry.types()).filter(t -> !t.reference().isLabel()).forEachRemaining(t -> {
                throw GraknException.of(ILLEGAL_TYPE_VARIABLE_IN_INSERT, t.reference());
            });

            Matcher matcher = null;
            if (query.match().isPresent()) {
                GraqlMatch.Unfiltered match = query.match().get();
                List<UnboundVariable> filter = new ArrayList<>(match.namedVariablesUnbound());
                filter.retainAll(query.namedVariablesUnbound());
                assert !filter.isEmpty();
                matcher = Matcher.create(reasoner, match.get(filter));
            }

            return new Inserter(matcher, conceptMgr, registry.things(), context);
        }
    }

    public ResourceIterator<ConceptMap> execute() {
        try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "execute")) {
            if (matcher != null) {
                List<ConceptMap> matches = matcher.execute(context).toList();
                return iterate(iterate(matches).map(matched -> new Operation(conceptMgr, matched, variables).execute()).toList());
            } else {
                return single(new Operation(conceptMgr, new ConceptMap(), variables).execute());
            }
        }
    }

    public static class Operation {

        private static final String TRACE_PREFIX = "operation.";

        private final ConceptManager conceptMgr;
        private final ConceptMap matched;
        private final Set<ThingVariable> variables;
        private final Map<Reference.Name, Thing> inserted;

        Operation(ConceptManager conceptMgr, ConceptMap matched, Set<ThingVariable> variables) {
            this.conceptMgr = conceptMgr;
            this.matched = matched;
            this.variables = variables;
            this.inserted = new HashMap<>();
        }

        public ConceptMap execute() {
            try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "execute")) {
                variables.forEach(this::insert);
                matched.forEach((ref, concept) -> inserted.putIfAbsent(ref, concept.asThing()));
                return new ConceptMap(inserted);
            }
        }

        private boolean matchedContains(ThingVariable var) {
            return var.reference().isName() && matched.contains(var.reference().asName());
        }

        public Thing matchedGet(ThingVariable var) {
            return matched.get(var.reference().asName()).asThing();
        }

        private Thing insert(ThingVariable var) {
            try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "insert")) {
                Thing thing;
                Reference ref = var.reference();

                if (ref.isName() && (thing = inserted.get(ref.asName())) != null) return thing;
                else if (matchedContains(var) && var.constraints().isEmpty()) return matchedGet(var);
                else validate(var);

                if (matchedContains(var)) {
                    thing = matchedGet(var);
                    if (var.isa().isPresent() &&
                            !thing.getType().equals(getThingType(var.isa().get().type().label().get()))) {
                        throw GraknException.of(THING_ISA_REINSERTION, ref, var.isa().get().type());
                    }
                } else if (var.isa().isPresent()) thing = insertIsa(var.isa().get(), var);
                else throw GraknException.of(THING_ISA_MISSING, ref);
                assert thing != null;

                if (ref.isName()) inserted.put(ref.asName(), thing);
                if (!var.relation().isEmpty()) insertRolePlayers(thing.asRelation(), var);
                if (!var.has().isEmpty()) insertHas(thing, var.has());
                return thing;
            }
        }

        private void validate(ThingVariable var) {
            try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "validate")) {
                Reference ref = var.reference();
                if (var.iid().isPresent()) {
                    throw GraknException.of(THING_IID_NOT_INSERTABLE, ref, var.iid().get());
                } else if (!var.is().isEmpty()) {
                    throw GraknException.of(ILLEGAL_IS_CONSTRAINT, var, var.is().iterator().next());
                }
            }
        }

        public ThingType getThingType(LabelConstraint labelConstraint) {
            try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "get_thing_type")) {
                ThingType thingType = conceptMgr.getThingType(labelConstraint.label());
                if (thingType == null) throw GraknException.of(TYPE_NOT_FOUND, labelConstraint.label());
                else return thingType.asThingType();
            }
        }

        private Thing insertIsa(IsaConstraint isaConstraint, ThingVariable var) {
            try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "insert_isa")) {
                assert isaConstraint.type().label().isPresent();
                ThingType thingType = getThingType(isaConstraint.type().label().get());

                if (thingType instanceof EntityType) {
                    return thingType.asEntityType().create();
                } else if (thingType instanceof RelationType) {
                    if (!var.relation().isEmpty()) return thingType.asRelationType().create();
                    else throw GraknException.of(RELATION_CONSTRAINT_MISSING, var.reference());
                } else if (thingType instanceof AttributeType) {
                    return insertAttribute(thingType.asAttributeType(), var);
                } else if (thingType instanceof ThingTypeImpl.Root) {
                    throw GraknException.of(ILLEGAL_ABSTRACT_WRITE, Thing.class.getSimpleName(), thingType.getLabel());
                } else {
                    assert false;
                    return null;
                }
            }
        }

        private Attribute insertAttribute(AttributeType attributeType, ThingVariable var) {
            try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "insert_attribute")) {
                ValueConstraint<?> valueConstraint;

                if (var.value().size() > 1) {
                    throw GraknException.of(ATTRIBUTE_VALUE_TOO_MANY, var.reference(), attributeType.getLabel());
                } else if (!var.value().isEmpty() &&
                        (valueConstraint = var.value().iterator().next()).isValueIdentity()) {
                    switch (attributeType.getValueType()) {
                        case LONG:
                            return attributeType.asLong().put(valueConstraint.asLong().value());
                        case DOUBLE:
                            return attributeType.asDouble().put(valueConstraint.asDouble().value());
                        case BOOLEAN:
                            return attributeType.asBoolean().put(valueConstraint.asBoolean().value());
                        case STRING:
                            return attributeType.asString().put(valueConstraint.asString().value());
                        case DATETIME:
                            return attributeType.asDateTime().put(valueConstraint.asDateTime().value());
                        default:
                            assert false;
                            return null;
                    }
                } else {
                    throw GraknException.of(ATTRIBUTE_VALUE_MISSING, var.reference(), attributeType.getLabel());
                }
            }
        }

        private void insertRolePlayers(Relation relation, ThingVariable var) {
            assert !var.relation().isEmpty();
            try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "extend_relation")) {
                if (var.relation().size() == 1) {
                    var.relation().iterator().next().players().forEach(rolePlayer -> {
                        Thing player = insert(rolePlayer.player());
                        RoleType roleType = getRoleType(relation, player, rolePlayer);
                        relation.addPlayer(roleType, player);
                    });
                } else { // var.relation().size() > 1
                    throw GraknException.of(INSERT_RELATION_CONSTRAINT_TOO_MANY, var.reference());
                }
            }
        }

        private void insertHas(Thing thing, Set<HasConstraint> hasConstraints) {
            try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "insert_has")) {
                hasConstraints.forEach(has -> thing.setHas(insert(has.attribute()).asAttribute()));
            }
        }
    }
}
