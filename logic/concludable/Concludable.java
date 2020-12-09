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

package grakn.core.logic.concludable;

import grakn.core.common.exception.GraknException;
import grakn.core.common.parameters.Label;
import grakn.core.pattern.constraint.Constraint;
import grakn.core.pattern.constraint.thing.HasConstraint;
import grakn.core.pattern.constraint.thing.IsaConstraint;
import grakn.core.pattern.constraint.thing.RelationConstraint;
import grakn.core.pattern.constraint.thing.ValueConstraint;
import grakn.core.pattern.constraint.type.SubConstraint;
import grakn.core.pattern.variable.ThingVariable;
import grakn.core.pattern.variable.TypeVariable;
import grakn.core.pattern.variable.Variable;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;

public abstract class Concludable<C extends Constraint, T extends Concludable<C, T>> {

    final C constraint;

    Concludable(C constraint) {
        this.constraint = constraint;
    }

    public C constraint() {
        return constraint;
    }

    static RelationConstraint copyConstraint(RelationConstraint relationConstraint) {
        ThingVariable ownerCopy = copyIsaAndValues(relationConstraint.owner());
        List<RelationConstraint.RolePlayer> rolePlayersCopy = copyRolePlayers(relationConstraint.players());
        return new RelationConstraint(ownerCopy, rolePlayersCopy);
    }

    static List<RelationConstraint.RolePlayer> copyRolePlayers(List<RelationConstraint.RolePlayer> players) {
        return players.stream().map(rolePlayer -> {
            TypeVariable roleTypeCopy = rolePlayer.roleType().isPresent() ? copyVariableWithLabelAndValueType(rolePlayer.roleType().get()) : null;
            ThingVariable playerCopy = copyIsaAndValues(rolePlayer.player());
            RelationConstraint.RolePlayer rolePlayerCopy = new RelationConstraint.RolePlayer(roleTypeCopy, playerCopy);
            rolePlayerCopy.addRoleTypeHints(rolePlayer.roleTypeHints());
            return rolePlayerCopy;
        }).collect(Collectors.toList());
    }

    static HasConstraint copyConstraint(HasConstraint hasConstraint) {
        ThingVariable ownerCopy = copyIsaAndValues(hasConstraint.owner());
        ThingVariable attributeCopy = copyIsaAndValues(hasConstraint.attribute());
        return ownerCopy.has(attributeCopy);
    }

    static IsaConstraint copyConstraint(IsaConstraint isa) {
        ThingVariable newOwner = ThingVariable.of(isa.owner().identifier());
        copyValuesOntoVariable(isa.owner().value(), newOwner);
        return copyIsaOntoVariable(isa, newOwner);
    }

    static ValueConstraint<?> copyConstraint(ValueConstraint<?> value) {
        //NOTE: isa can never exist on a Value Concludable (or else it would be a Isa Concludable).
        ThingVariable newOwner = ThingVariable.of(value.owner().identifier());
        Set<ValueConstraint<?>> otherValues = value.owner().value().stream().filter(value1 -> value != value1)
                .collect(Collectors.toSet());
        copyValuesOntoVariable(otherValues, newOwner);
        return copyValueOntoVariable(value, newOwner);
    }

    static IsaConstraint copyIsaOntoVariable(IsaConstraint toCopy, ThingVariable variableToConstrain) {
        TypeVariable typeCopy = copyVariableWithLabelAndValueType(toCopy.type());
        IsaConstraint newIsa = variableToConstrain.isa(typeCopy, toCopy.isExplicit());
        newIsa.addHints(toCopy.typeHints());
        return newIsa;
    }

    static void copyValuesOntoVariable(Set<ValueConstraint<?>> toCopy, ThingVariable newOwner) {
        toCopy.forEach(valueConstraint -> copyValueOntoVariable(valueConstraint, newOwner));
    }

    static ValueConstraint<?> copyValueOntoVariable(ValueConstraint<?> toCopy, ThingVariable toConstrain) {
        ValueConstraint<?> value;
        if (toCopy.isLong())
            value = toConstrain.valueLong(toCopy.asLong().predicate().asEquality(), toCopy.asLong().value());
        else if (toCopy.isDouble())
            value = toConstrain.valueDouble(toCopy.asDouble().predicate().asEquality(), toCopy.asDouble().value());
        else if (toCopy.isBoolean())
            value = toConstrain.valueBoolean(toCopy.asBoolean().predicate().asEquality(), toCopy.asBoolean().value());
        else if (toCopy.isString())
            value = toConstrain.valueString(toCopy.asString().predicate(), toCopy.asString().value());
        else if (toCopy.isDateTime())
            value = toConstrain.valueDateTime(toCopy.asDateTime().predicate().asEquality(), toCopy.asDateTime().value());
        else if (toCopy.isVariable()) {
            ThingVariable copyOfVar = copyIsaAndValues(toCopy.asVariable().value());
            value = toConstrain.valueVariable(toCopy.asValue().predicate().asEquality(), copyOfVar);
        } else throw GraknException.of(ILLEGAL_STATE);
        return value;
    }

    static ThingVariable copyIsaAndValues(ThingVariable copyFrom) {
        ThingVariable copy = ThingVariable.of(copyFrom.identifier());
        copyIsaAndValues(copyFrom, copy);
        return copy;
    }

    static void copyIsaAndValues(ThingVariable oldOwner, ThingVariable newOwner) {
        if (oldOwner.isa().isPresent()) copyIsaOntoVariable(oldOwner.isa().get(), newOwner);
        copyValuesOntoVariable(oldOwner.value(), newOwner);
    }

    static void copyLabelAndValueType(TypeVariable copyFrom, TypeVariable copyTo) {
        if (copyFrom.label().isPresent()) copyTo.label(Label.of(copyFrom.label().get().label()));
        if (copyFrom.sub().isPresent()) {
            SubConstraint subCopy = copyFrom.sub().get();
            copyTo.sub(subCopy.type(), subCopy.isExplicit());
            copyTo.sub().get().addHints(subCopy.typeHints());
        }
        if (copyFrom.valueType().isPresent()) copyTo.valueType(copyFrom.valueType().get().valueType());
    }

    static TypeVariable copyVariableWithLabelAndValueType(TypeVariable copyFrom) {
        TypeVariable copy = TypeVariable.of(copyFrom.identifier());
        copyLabelAndValueType(copyFrom, copy);
        return copy;
    }

    static boolean hasNoHints(Variable variable) {
        //TODO: refactor once new 'all things' symbol is introduced
        if (variable.isThing()) {
            return !variable.asThing().isa().isPresent() || variable.asThing().isa().get().typeHints().isEmpty();
        } else if (variable.isType()) {
            return !variable.asType().sub().isPresent() || variable.asType().sub().get().typeHints().isEmpty();
        } else {
            throw GraknException.of(ILLEGAL_STATE);
        }
    }

    static boolean hintsIntersect(Variable first, Variable second) {
        if (hasNoHints(first) || hasNoHints(second)) return true;
        Set<Label> firstHints;
        Set<Label> secondHints;
        if (first.isThing() && second.isThing()) {
            firstHints = first.asThing().isa().get().typeHints();
            secondHints = second.asThing().isa().get().typeHints();
        } else if (first.isType() && second.isType()) {
            firstHints = first.asType().sub().get().typeHints();
            secondHints = second.asType().sub().get().typeHints();
        } else {
            return false;
        }
        return !Collections.disjoint(firstHints, secondHints);
    }

    static boolean hintsIntersect(RelationConstraint.RolePlayer first, RelationConstraint.RolePlayer second) {
        return !Collections.disjoint(first.roleTypeHints(), second.roleTypeHints());
    }

}