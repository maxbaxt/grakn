#
# Copyright (C) 2020 Grakn Labs
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as
# published by the Free Software Foundation, either version 3 of the
# License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#

Feature: Debugging Space

#  Background:
#    Given connection has been opened
#    Given connection delete all keyspaces
#    Given connection does not have any keyspace

  # Paste any scenarios below for debugging.
  # Do not commit any changes to this file.


#  UNCOMMENT ABOVE, DELETE BELOW
  #
  #
  #  Scenario: When defining a rule with a variable atom type, an error is thrown.
#    Given graql define
#      """
#      define
#
#      element sub entity,
#      plays first,
#      plays second;
#
#      symmetric sub relation,
#      relates first,
#      relates second;
#      """
#
#    Given the integrity is validated
#    Then graql define throws
#
#      """
#      define
#      symmetry sub rule,
#      when {
#          $s sub symmetric;
#          (first: $a, second: $b) isa $s;
#      }, then {
#          (first: $b, second: $a) isa $s;
#      };
#      """
#    Then the integrity is validated


  Background: Initialise a session and transaction for each scenario
    Given connection has been opened
    Given connection delete all keyspaces
    Given connection open sessions for keyspaces:
      | test_rule_validation |
    Given transaction is initialised
    Given graql define
      """
      define
      person sub entity, plays employee, has name, key email;
      employment sub relation, relates employee, has start-date;
      name sub attribute, value string;
      email sub attribute, value string;
      start-date sub attribute, value datetime;
      """
    Given the integrity is validated


  Scenario: When defining a rule with a variable atom type, an error is thrown.
    Given graql define
      """
      define

      element sub entity,
      plays first,
      plays second;

      symmetric sub relation,
      relates first,
      relates second;
      """

    Given the integrity is validated
    Then graql define

      """
      define
      symmetry sub rule,
      when {
          $s sub symmetric;
          (first: $a, second: $b) isa $s;
      }, then {
          (first: $b, second: $a) isa $s;
      };
      """
    Then the integrity is validated


  Scenario: test 2
    Given graql define
      """
      define

      element sub entity,
      plays first,
      plays second;

      symmetric sub relation,
      relates first,
      relates second;
      """

    Given the integrity is validated
    Then graql define

      """
      define
      symmetry sub rule,
      when {
          $s sub symmetric;
          (first: $a, second: $b) isa symmetric;
      }, then {
          (first: $b, second: $a) isa symmetric;
      };
      """
    Then the integrity is validated