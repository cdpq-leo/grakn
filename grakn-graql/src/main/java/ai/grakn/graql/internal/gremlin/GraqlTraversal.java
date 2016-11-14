/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.graql.internal.gremlin;

import ai.grakn.GraknGraph;
import ai.grakn.graql.internal.gremlin.fragment.Fragment;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.javatuples.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.grakn.graql.internal.util.CommonUtil.toImmutableSet;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

/**
 * A traversal over a Grakn graph, representing one of many ways to execute a {@code MatchQuery}.
 * Comprised of ordered {@code Fragment}s which are used to construct a TinkerPop {@code GraphTraversal}, which can be
 * retrieved and executed.
 */
public class GraqlTraversal {

    //            Set of disjunctions
    //             |
    //             |           List of fragments in order of execution
    //             |            |
    //             V            V
    private final ImmutableSet<ImmutableList<Fragment>> fragments;
    private final GraknGraph graph;

    // TODO: Find a better way to represent these values
    // Just a pretend big number
    private static final long NUM_VERTICES_ESTIMATE = 1_000;

    private static final long MAX_TRAVERSAL_ATTEMPTS = 1_000;

    private GraqlTraversal(GraknGraph graph, Set<? extends List<Fragment>> fragments) {
        this.graph = graph;
        this.fragments = fragments.stream().map(ImmutableList::copyOf).collect(toImmutableSet());
    }

    public static GraqlTraversal create(GraknGraph graph, Set<? extends List<Fragment>> fragments) {
        return new GraqlTraversal(graph, fragments);
    }

    static GraqlTraversal semiOptimal(GraknGraph graph, Collection<ConjunctionQuery> innerQueries) {

        Set<? extends List<Fragment>> fragments = innerQueries.stream()
                .map(GraqlTraversal::semiOptimalConjunction)
                .collect(toImmutableSet());

        return GraqlTraversal.create(graph, fragments);
    }

    private static List<Fragment> semiOptimalConjunction(ConjunctionQuery query) {
        Set<EquivalentFragmentSet> fragmentSets = Sets.newHashSet(query.getEquivalentFragmentSets());

        Set<String> names = new HashSet<>();

        List<Fragment> fragments = new ArrayList<>();

        // Calculate the depth to descend in the tree
        long numFragments = fragmentSets.stream().flatMap(EquivalentFragmentSet::getFragments).count();
        long depth = 1;
        long numTraversalAttempts = fragmentSets.stream().flatMap(EquivalentFragmentSet::getFragments).count();

        while (numFragments > 0 && numTraversalAttempts < MAX_TRAVERSAL_ATTEMPTS) {
            depth += 1;
            numTraversalAttempts *= numFragments;
            numFragments -= 1;
            System.out.println(depth);
        }

        long cost = 1;

        while (!fragmentSets.isEmpty()) {
            Pair<Long, List<Fragment>> pair = proposeFragment(fragmentSets, names, cost, depth);
            cost = pair.getValue0();
            List<Fragment> newFragments = Lists.reverse(pair.getValue1());

            newFragments.forEach(fragment -> {
                fragmentSets.remove(fragment.getEquivalentFragmentSet());
                fragment.getVariableNames().forEach(names::add);
            });
            fragments.addAll(newFragments);
        }

        return fragments;
    }

    private static Pair<Long, List<Fragment>> proposeFragment(
            Set<EquivalentFragmentSet> fragmentSets, Set<String> names, long cost, long depth
    ) {
        if (depth == 0) {
            return Pair.with(cost, Lists.newArrayList());
        }

        return fragmentSets.stream().flatMap(EquivalentFragmentSet::getFragments).map(fragment -> {
            long newCost = fragmentCost(fragment, cost, names);

            Set<EquivalentFragmentSet> newFragmentSets = Sets.difference(fragmentSets, ImmutableSet.of(fragment.getEquivalentFragmentSet()));
            Set<String> newNames = Sets.union(names, fragment.getVariableNames().collect(toSet()));
            Pair<Long, List<Fragment>> pair = proposeFragment(newFragmentSets, newNames, newCost, depth - 1);
            pair.getValue1().add(fragment);
            return pair.setAt0(pair.getValue0() + newCost);
        }).min(comparing(Pair::getValue0)).orElse(Pair.with(cost, Lists.newArrayList()));
    }

    /**
     * Get the {@code GraphTraversal} that this {@code GraqlTraversal} represents.
     */
    GraphTraversal<Vertex, Map<String, Vertex>> getGraphTraversal() {
        Traversal[] traversals =
                fragments.stream().map(this::getConjunctionTraversal).toArray(Traversal[]::new);

        // Because 'union' accepts an array, we can't use generics...
        //noinspection unchecked
        return graph.getTinkerTraversal().limit(1).union(traversals);
    }

    /**
     * @return a gremlin traversal that represents this inner query
     */
    private GraphTraversal<Vertex, Map<String, Vertex>> getConjunctionTraversal(ImmutableList<Fragment> fragmentList) {
        GraphTraversal<Vertex, Vertex> traversal = graph.getTinkerTraversal();

        Set<String> foundNames = new HashSet<>();

        // Apply fragments in order into one single traversal
        String currentName = null;

        for (Fragment fragment : fragmentList) {
            applyFragment(fragment, traversal, currentName, foundNames);
            currentName = fragment.getEnd().orElse(fragment.getStart());
        }

        // Select all the variable names
        String[] traversalNames = foundNames.toArray(new String[foundNames.size()]);
        return traversal.select(traversalNames[0], traversalNames[0], traversalNames);
    }

    /**
     * Apply the given fragment to the traversal. Keeps track of variable names so far so that it can decide whether
     * to use "as" or "select" steps in gremlin.
     * @param fragment the fragment to apply to the traversal
     * @param traversal the gremlin traversal to apply the fragment to
     * @param currentName the variable name that the traversal is currently at
     * @param names a set of variable names so far encountered in the query
     */
    private void applyFragment(
            Fragment fragment, GraphTraversal<Vertex, Vertex> traversal, String currentName, Set<String> names
    ) {
        String start = fragment.getStart();

        if (currentName != null) {
            if (!currentName.equals(start)) {
                if (names.contains(start)) {
                    // If the variable name has been visited but the traversal is not at that variable name, select it
                    traversal.select(start);
                } else {
                    // Restart traversal when fragments are disconnected
                    traversal.V().as(start);
                }
            }
        } else {
            // If the variable name has not been visited yet, remember it and use the 'as' step
            traversal.as(start);
        }

        names.add(start);

        // Apply fragment to traversal
        fragment.applyTraversal(traversal);

        fragment.getEnd().ifPresent(end -> {
            if (!names.contains(end)) {
                // This variable name has not been encountered before, remember it and use the 'as' step
                names.add(end);
                traversal.as(end);
            } else {
                // This variable name has been encountered before, confirm it is the same
                traversal.where(P.eq(end));
            }
        });
    }

    /**
     * Get the estimated complexity of the traversal.
     */
    public long getComplexity() {

        long totalCost = 0;

        for (List<Fragment> list : fragments) {
            Set<String> names = new HashSet<>();

            long cost = 1;
            long listCost = 0;

            for (Fragment fragment : list) {
                cost = fragmentCost(fragment, cost, names);
                fragment.getVariableNames().forEach(names::add);
                listCost += cost;
            }

            totalCost += listCost;
        }

        return totalCost;
    }

    private static long fragmentCost(Fragment fragment, long previousCost, Set<String> names) {
        if (names.contains(fragment.getStart())) {
            return fragment.fragmentCost(previousCost);
        } else {
            // Restart traversal, meaning we are navigating from all vertices
            // The constant '1' cost is to discourage constant restarting, even when indexed
            return fragment.fragmentCost(NUM_VERTICES_ESTIMATE) * previousCost + 1;
        }
    }

    @Override
    public String toString() {
        return "{" + fragments.stream().map(list -> {
            StringBuilder sb = new StringBuilder();
            String currentName = null;

            for (Fragment fragment : list) {
                if (!fragment.getStart().equals(currentName)) {
                    if (currentName != null) sb.append(" ");

                    sb.append("$").append(StringUtils.left(fragment.getStart(), 3));
                    currentName = fragment.getStart();
                }

                sb.append(fragment.getName());

                Optional<String> end = fragment.getEnd();
                if (end.isPresent()) {
                    sb.append("$").append(StringUtils.left(end.get(), 3));
                    currentName = end.get();
                }
            }

            return sb.toString();
        }).collect(joining(", ")) + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        GraqlTraversal that = (GraqlTraversal) o;

        if (fragments != null ? !fragments.equals(that.fragments) : that.fragments != null) return false;
        return graph != null ? graph.equals(that.graph) : that.graph == null;

    }

    @Override
    public int hashCode() {
        int result = fragments != null ? fragments.hashCode() : 0;
        result = 31 * result + (graph != null ? graph.hashCode() : 0);
        return result;
    }
}
