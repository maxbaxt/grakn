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
 */

package grakn.core.logic;

import grakn.core.Grakn;
import grakn.core.common.parameters.Arguments;
import grakn.core.common.parameters.Label;
import grakn.core.concept.ConceptManager;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.EntityType;
import grakn.core.concept.type.RelationType;
import grakn.core.logic.concludable.ConjunctionConcludable;
import grakn.core.logic.concludable.ThenConcludable;
import grakn.core.pattern.Conjunction;
import grakn.core.pattern.constraint.thing.RelationConstraint;
import grakn.core.pattern.variable.SystemReference;
import grakn.core.pattern.variable.ThingVariable;
import grakn.core.pattern.variable.TypeVariable;
import grakn.core.pattern.variable.Variable;
import grakn.core.rocks.RocksGrakn;
import grakn.core.test.integration.util.Util;
import grakn.core.traversal.common.Identifier;
import graql.lang.Graql;
import graql.lang.common.GraqlToken;
import graql.lang.pattern.constraint.ThingConstraint;
import graql.lang.pattern.variable.Reference;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static grakn.common.collection.Collections.list;
import static grakn.common.collection.Collections.set;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertEquals;

public class RuleTest {
    private static Path directory = Paths.get(System.getProperty("user.dir")).resolve("rule-test");
    private static String database = "rule-test";

    private long isaConjunctionConcludablesCount(Set<ConjunctionConcludable<?, ?>> concludables) {
        return concludables.stream().filter(ConjunctionConcludable::isIsa).count();
    }

    private long hasConjunctionConcludablesCount(Set<ConjunctionConcludable<?, ?>> concludables) {
        return concludables.stream().filter(ConjunctionConcludable::isHas).count();
    }

    private long relationConjunctionConcludablesCount(Set<ConjunctionConcludable<?, ?>> concludables) {
        return concludables.stream().filter(ConjunctionConcludable::isRelation).count();
    }

    private long valueConjunctionConcludablesCount(Set<ConjunctionConcludable<?, ?>> concludables) {
        return concludables.stream().filter(ConjunctionConcludable::isValue).count();
    }

    private long isaHeadConcludablesCount(Set<ThenConcludable<?, ?>> concludables) {
        return concludables.stream().filter(ThenConcludable::isIsa).count();
    }

    private long hasHeadConcludablesCount(Set<ThenConcludable<?, ?>> concludables) {
        return concludables.stream().filter(ThenConcludable::isHas).count();
    }

    private long relationHeadConcludablesCount(Set<ThenConcludable<?, ?>> concludables) {
        return concludables.stream().filter(ThenConcludable::isRelation).count();
    }

    private long valueHeadConcludablesCount(Set<ThenConcludable<?, ?>> concludables) {
        return concludables.stream().filter(ThenConcludable::isValue).count();
    }

    @Test
    public void rule_concludables_built_correctly_from_rule_concerning_relation() throws IOException {
        Util.resetDirectory(directory);

        try (Grakn grakn = RocksGrakn.open(directory)) {
            grakn.databases().create(database);
            try (Grakn.Session session = grakn.session(database, Arguments.Session.Type.SCHEMA)) {
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.WRITE)) {
                    final ConceptManager conceptMgr = txn.concepts();
                    final LogicManager logicMgr = txn.logic();

                    final EntityType person = conceptMgr.putEntityType("person");
                    final RelationType friendship = conceptMgr.putRelationType("friendship");
                    friendship.setRelates("friend");
                    final RelationType marriage = conceptMgr.putRelationType("marriage");
                    marriage.setRelates("spouse");
                    person.setPlays(friendship.getRelates("friend"));
                    person.setPlays(marriage.getRelates("spouse"));
                    logicMgr.putRule(
                            "marriage-is-friendship",
                            Graql.parsePattern("{$x isa person; $y isa person; (spouse: $x, spouse: $y) isa marriage; }").asConjunction(),
                            Graql.parseVariable("(friend: $x, friend: $y) isa friendship").asThing());
                    txn.commit();
                }
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.READ)) {
                    final LogicManager logicMgr = txn.logic();
                    final Rule rule = logicMgr.getRule("marriage-is-friendship");

                    Set<ThenConcludable<?, ?>> thenConcludables = rule.possibleThenConcludables();
                    assertEquals(1, isaHeadConcludablesCount(thenConcludables));
                    assertEquals(0, hasHeadConcludablesCount(thenConcludables));
                    assertEquals(1, relationHeadConcludablesCount(thenConcludables));
                    assertEquals(0, valueHeadConcludablesCount(thenConcludables));

                    Set<ConjunctionConcludable<?, ?>> bodyConcludables = rule.whenConcludables();
                    assertEquals(2, isaConjunctionConcludablesCount(bodyConcludables));
                    assertEquals(0, hasConjunctionConcludablesCount(bodyConcludables));
                    assertEquals(1, relationConjunctionConcludablesCount(bodyConcludables));
                    assertEquals(0, valueConjunctionConcludablesCount(bodyConcludables));
                }
            }
        }
    }

    //TODO: re-enable when the ThenConcludables don't include the "$_num=5" case.
    @Test
    @Ignore
    public void rule_concludables_built_correctly_from_rule_concerning_has_isa_value() throws IOException {
        Util.resetDirectory(directory);

        try (Grakn grakn = RocksGrakn.open(directory)) {
            grakn.databases().create(database);
            try (Grakn.Session session = grakn.session(database, Arguments.Session.Type.SCHEMA)) {
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.WRITE)) {
                    final ConceptManager conceptMgr = txn.concepts();
                    final LogicManager logicMgr = txn.logic();

                    final EntityType milk = conceptMgr.putEntityType("milk");
                    final AttributeType ageInDays = conceptMgr.putAttributeType("age-in-days", AttributeType.ValueType.LONG);
                    final AttributeType isStillGood = conceptMgr.putAttributeType("is-still-good", AttributeType.ValueType.BOOLEAN);
                    milk.setOwns(ageInDays);
                    milk.setOwns(isStillGood);
                    logicMgr.putRule(
                            "old-milk-is-not-good",
                            Graql.parsePattern("{ $x isa milk, has age-in-days >= 10; }").asConjunction(),
                            Graql.parseVariable("$x has is-still-good false").asThing());
                    txn.commit();
                }
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.READ)) {
                    final ConceptManager conceptMgr = txn.concepts();
                    final LogicManager logicMgr = txn.logic();
                    final Rule rule = logicMgr.getRule("old-milk-is-not-good");

                    Set<ThenConcludable<?, ?>> thenConcludables = rule.possibleThenConcludables();
                    assertEquals(1, isaHeadConcludablesCount(thenConcludables));
                    assertEquals(1, hasHeadConcludablesCount(thenConcludables));
                    assertEquals(0, relationHeadConcludablesCount(thenConcludables));
                    assertEquals(1, valueHeadConcludablesCount(thenConcludables));

                    Set<ConjunctionConcludable<?, ?>> bodyConcludables = rule.whenConcludables();
                    assertEquals(1, isaConjunctionConcludablesCount(bodyConcludables));
                    assertEquals(1, hasConjunctionConcludablesCount(bodyConcludables));
                    assertEquals(0, relationConjunctionConcludablesCount(bodyConcludables));
                    assertEquals(0, valueConjunctionConcludablesCount(bodyConcludables));
                }
            }
        }
    }

    @Test
    public void rule_concludables_built_correctly_from_rule_concerning_has() throws IOException {
        Util.resetDirectory(directory);

        try (Grakn grakn = RocksGrakn.open(directory)) {
            grakn.databases().create(database);
            try (Grakn.Session session = grakn.session(database, Arguments.Session.Type.SCHEMA)) {
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.WRITE)) {
                    final ConceptManager conceptMgr = txn.concepts();
                    final LogicManager logicMgr = txn.logic();

                    final EntityType milk = conceptMgr.putEntityType("milk");
                    final AttributeType ageInDays = conceptMgr.putAttributeType("age-in-days", AttributeType.ValueType.LONG);
                    final AttributeType isStillGood = conceptMgr.putAttributeType("is-still-good", AttributeType.ValueType.BOOLEAN);
                    milk.setOwns(ageInDays);
                    milk.setOwns(isStillGood);
                    logicMgr.putRule(
                            "old-milk-is-not-good",
                            Graql.parsePattern("{ $x isa milk; $a 10 isa age-in-days; }").asConjunction(),
                            Graql.parseVariable("$x has $a").asThing());
                    txn.commit();
                }
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.READ)) {
                    final LogicManager logicMgr = txn.logic();
                    final Rule rule = logicMgr.getRule("old-milk-is-not-good");

                    Set<ThenConcludable<?, ?>> thenConcludables = rule.possibleThenConcludables();
                    assertEquals(0, isaHeadConcludablesCount(thenConcludables));
                    assertEquals(1, hasHeadConcludablesCount(thenConcludables));
                    assertEquals(0, relationHeadConcludablesCount(thenConcludables));
                    assertEquals(0, valueHeadConcludablesCount(thenConcludables));

                    Set<ConjunctionConcludable<?, ?>> bodyConcludables = rule.whenConcludables();
                    assertEquals(2, isaConjunctionConcludablesCount(bodyConcludables));
                    assertEquals(0, hasConjunctionConcludablesCount(bodyConcludables));
                    assertEquals(0, relationConjunctionConcludablesCount(bodyConcludables));
                    assertEquals(0, valueConjunctionConcludablesCount(bodyConcludables));
                }
            }
        }
    }



    //THEN PATTERN TESTING

    private boolean conjsEqual(Conjunction expected, Conjunction result) {

        for (Variable var : expected.variables()) {
            Optional<Variable> other = result.variables().stream().filter(variable -> variable.equals(var)).findFirst();
            if (!other.isPresent()) {
                return false;
            }
            if (!other.get().constraints().equals(var.constraints())) {
                return false;
            }
        }
        return true;
    }

    @Test
    public void has_concludable_variable_attribute() throws IOException {
        Util.resetDirectory(directory);

        try (Grakn grakn = RocksGrakn.open(directory)) {
            grakn.databases().create(database);
            try (Grakn.Session session = grakn.session(database, Arguments.Session.Type.SCHEMA)) {
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.WRITE)) {
                    final ConceptManager conceptMgr = txn.concepts();
                    final LogicManager logicMgr = txn.logic();

                    final EntityType milk = conceptMgr.putEntityType("milk");
                    final AttributeType ageInDays = conceptMgr.putAttributeType("age-in-days", AttributeType.ValueType.LONG);
                    final AttributeType isStillGood = conceptMgr.putAttributeType("is-still-good", AttributeType.ValueType.BOOLEAN);
                    milk.setOwns(ageInDays);
                    milk.setOwns(isStillGood);
                    logicMgr.putRule(
                            "old-milk-is-not-good",
                            Graql.parsePattern("{ $x isa milk; $a 10 isa age-in-days; }").asConjunction(),
                            Graql.parseVariable("$x has $a").asThing());
                    txn.commit();
                }
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.READ)) {
                    final LogicManager logicMgr = txn.logic();
                    final Rule rule = logicMgr.getRule("old-milk-is-not-good");

                    ThingVariable expectedOwner = ThingVariable.createNamed("x");
                    ThingVariable expectedAttribute = ThingVariable.createNamed("a");

                    expectedOwner.has(expectedAttribute);
                    Set<Variable> expectedSet = set(expectedOwner, expectedAttribute);
                    Conjunction expected = new Conjunction(expectedSet, set());
                    assertEquals(expected, rule.then());

                }
            }
        }
    }

    @Test
    public void has_concludable_concrete_attribute() throws IOException {
        Util.resetDirectory(directory);

        try (Grakn grakn = RocksGrakn.open(directory)) {
            grakn.databases().create(database);
            try (Grakn.Session session = grakn.session(database, Arguments.Session.Type.SCHEMA)) {
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.WRITE)) {
                    final ConceptManager conceptMgr = txn.concepts();
                    final LogicManager logicMgr = txn.logic();

                    final EntityType milk = conceptMgr.putEntityType("milk");
                    final AttributeType ageInDays = conceptMgr.putAttributeType("age-in-days", AttributeType.ValueType.LONG);
                    final AttributeType isStillGood = conceptMgr.putAttributeType("is-still-good", AttributeType.ValueType.BOOLEAN);
                    milk.setOwns(ageInDays);
                    milk.setOwns(isStillGood);
                    logicMgr.putRule(
                            "old-milk-is-not-good",
                            Graql.parsePattern("{ $x isa milk, has age-in-days >= 10; }").asConjunction(),
                            Graql.parseVariable("$x has is-still-good false").asThing());
                    txn.commit();
                }
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.READ)) {
                    final ConceptManager conceptMgr = txn.concepts();
                    final LogicManager logicMgr = txn.logic();
                    final Rule rule = logicMgr.getRule("old-milk-is-not-good");
                    final Conjunction result = rule.then();

                    ThingVariable expectedOwner = ThingVariable.createNamed("x");
                    ThingVariable expectedAttribute = ThingVariable.createTemp("attr");
                    TypeVariable expectedAttrType = TypeVariable.createTemp("attr_type");
                    ThingVariable expectedAttrValue = ThingVariable.createTemp("value");
                    Label expectedAttrTypeLabel = Label.of("is-still-good");
                    expectedAttrType.label(expectedAttrTypeLabel);
                    expectedAttrValue.valueBoolean(GraqlToken.Predicate.Equality.EQ, false);
                    expectedAttribute.valueVariable(GraqlToken.Predicate.Equality.EQ, expectedAttrValue);
                    expectedAttribute.isa(expectedAttrType, false);
                    expectedOwner.has(expectedAttribute);



                    Conjunction expected = new Conjunction(
                            set(expectedOwner, expectedAttribute, expectedAttrType, expectedAttrValue),
                            set()
                    );

                    assertEquals(expected, result);
                    assertTrue(conjsEqual(expected, result));
                }
            }
        }
    }

    @Test
    public void has_concludable_concrete_string_attribute() throws IOException {
        Util.resetDirectory(directory);

        try (Grakn grakn = RocksGrakn.open(directory)) {
            grakn.databases().create(database);
            try (Grakn.Session session = grakn.session(database, Arguments.Session.Type.SCHEMA)) {
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.WRITE)) {
                    final ConceptManager conceptMgr = txn.concepts();
                    final LogicManager logicMgr = txn.logic();

                    final EntityType milk = conceptMgr.putEntityType("milk");
                    final AttributeType ageInDays = conceptMgr.putAttributeType("age-in-days", AttributeType.ValueType.LONG);
                    final AttributeType isStillGood = conceptMgr.putAttributeType("label", AttributeType.ValueType.STRING);
                    milk.setOwns(ageInDays);
                    milk.setOwns(isStillGood);
                    logicMgr.putRule(
                            "old-milk-is-not-good",
                            Graql.parsePattern("{ $x isa milk, has age-in-days >= 10; }").asConjunction(),
                            Graql.parseVariable("$x has judgement 'bad'").asThing());
                    txn.commit();
                }
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.READ)) {
                    final LogicManager logicMgr = txn.logic();
                    final Rule rule = logicMgr.getRule("old-milk-is-not-good");
                    final Conjunction result = rule.then();

                    ThingVariable expectedOwner = ThingVariable.createNamed("x");
                    ThingVariable expectedAttribute = ThingVariable.createTemp("attr");
                    TypeVariable expectedAttrType = TypeVariable.createTemp("attr_type");
                    ThingVariable expectedAttrValue = ThingVariable.createTemp("value");
                    Label expectedAttrTypeLabel = Label.of("judgement");
                    expectedAttrType.label(expectedAttrTypeLabel);
                    expectedAttrValue.valueString(GraqlToken.Predicate.Equality.EQ, "bad");
                    expectedAttribute.valueVariable(GraqlToken.Predicate.Equality.EQ, expectedAttrValue);
                    expectedAttribute.isa(expectedAttrType, false);
                    expectedOwner.has(expectedAttribute);

                    Conjunction expected = new Conjunction(
                            set(expectedOwner, expectedAttribute, expectedAttrType, expectedAttrValue),
                            set()
                    );

                    assertEquals(expected, result);
                    assertTrue(conjsEqual(expected, result));
                }
            }
        }
    }

    @Test
    public void relation_concludable_one_player_concrete_relation() throws IOException {
        Util.resetDirectory(directory);

        try (Grakn grakn = RocksGrakn.open(directory)) {
            grakn.databases().create(database);
            try (Grakn.Session session = grakn.session(database, Arguments.Session.Type.SCHEMA)) {
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.WRITE)) {
                    final ConceptManager conceptMgr = txn.concepts();
                    final LogicManager logicMgr = txn.logic();

                    final EntityType person = conceptMgr.putEntityType("person");
                    final RelationType employment = conceptMgr.putRelationType("employment");
                    final AttributeType name = conceptMgr.putAttributeType("name", AttributeType.ValueType.STRING);
                    employment.setRelates("employee");
                    person.setPlays(employment.getRelates("employee"));
                    person.setOwns(name);
                    logicMgr.putRule(
                            "bob-is-employed",
                            Graql.parsePattern("{$x isa person; $x has name 'bob'; }").asConjunction(),
                            Graql.parseVariable("(employee: $x) isa employment").asThing());
                    txn.commit();
                }
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.READ)) {
                    final LogicManager logicMgr = txn.logic();
                    final Rule rule = logicMgr.getRule("bob-is-employed");
                    final Conjunction result = rule.then();

                    ThingVariable expectedOwner = ThingVariable.createTemp("rel_owner");
                    TypeVariable expectedRelType = TypeVariable.createTemp("rel_type");
                    Label expectedRelTypeLabel = Label.of("employment");
                    TypeVariable expectedRoleType = TypeVariable.createTemp("role_0");
                    Label expectedRoleTypeLabel = Label.of("employee", "employment");
                    ThingVariable expectedPlayer = ThingVariable.createNamed("x");
                    List<RelationConstraint.RolePlayer> expectedRolePlayers =
                            list(new RelationConstraint.RolePlayer(expectedRoleType, expectedPlayer));
                    expectedOwner.relation(expectedRolePlayers);
                    expectedOwner.isa(expectedRelType, false);
                    expectedRelType.label(expectedRelTypeLabel);
                    expectedRoleType.label(expectedRoleTypeLabel);

                    Conjunction expected = new Conjunction(
                            set(expectedOwner, expectedPlayer, expectedRelType, expectedRoleType),
                            set()
                    );

                    assertEquals(expected, result);
                    assertTrue(conjsEqual(expected, result));
                }
            }
        }
    }




}
