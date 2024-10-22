/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.pathing.calc;

import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;

import java.util.HashSet;
import java.util.List;

/**
 * Represents a calculated path consisting of a series of movements between positions.
 * A path is an ordered sequence of movements that lead from a starting position to a goal.
 *
 * @author leijurv, Brady
 */
public interface IPath {
    /**
     * Returns the sequence of movements that make up this path.
     * @return Ordered list of movements where each movement connects consecutive positions
     */
    List<IMovement> movements();

    /**
     * Returns all positions along this path.
     * @return List of positions, starting with source and ending with destination
     */
    List<BetterBlockPos> positions();

    /**
     * Gets the goal this path was calculated towards.
     * @return The target goal
     */
    Goal getGoal();

    /**
     * Gets the number of nodes evaluated during pathfinding.
     * @return Count of nodes considered
     */
    int getNumNodesConsidered();

    /**
     * Returns the starting position.
     * @return First position in the path
     */
    default BetterBlockPos getSrc() {
        return positions().get(0);
    }

    /**
     * Returns the ending position.
     * @return Last position in the path
     */
    default BetterBlockPos getDest() {
        List<BetterBlockPos> pos = positions();
        return pos.get(pos.size() - 1);
    }

    /**
     * Returns total path length.
     * @return Number of positions
     */
    default int length() {
        return positions().size();
    }

    /**
     * Calculates remaining ticks from given position.
     * @param pathPosition Index in path to calculate from
     * @return Estimated ticks remaining
     */
    default double ticksRemainingFrom(int pathPosition) {
        double sum = 0;
        List<IMovement> movements = movements();
        for (int i = pathPosition; i < movements.size(); i++) {
            sum += movements.get(i).getCost();
        }
        return sum;
    }

    /**
     * Performs post-processing for path execution.
     * @return Processed path
     * @throws UnsupportedOperationException if not implemented
     */
    default IPath postProcess() {
        throw new UnsupportedOperationException();
    }

    /**
     * Truncates path at chunk boundaries.
     * @param bsi Block state interface for chunk checking
     * @return Truncated path
     * @throws UnsupportedOperationException if not implemented
     */
    default IPath cutoffAtLoadedChunks(Object bsi) {
        throw new UnsupportedOperationException();
    }

    /**
     * Applies static cutoff based on settings.
     * @param destination Goal to measure against
     * @return Modified path
     * @throws UnsupportedOperationException if not implemented
     */
    default IPath staticCutoff(Goal destination) {
        throw new UnsupportedOperationException();
    }

    /**
     * Validates path integrity.
     * @throws IllegalStateException if validation fails
     */
    default void sanityCheck() {
        List<BetterBlockPos> path = positions();
        List<IMovement> movements = movements();

        if (!getSrc().equals(path.get(0))) {
            throw new IllegalStateException("Start node does not equal first path element");
        }
        if (!getDest().equals(path.get(path.size() - 1))) {
            throw new IllegalStateException("End node does not equal last path element");
        }
        if (path.size() != movements.size() + 1) {
            throw new IllegalStateException("Size of path array is unexpected");
        }

        HashSet<BetterBlockPos> seenSoFar = new HashSet<>();
        for (int i = 0; i < path.size() - 1; i++) {
            BetterBlockPos src = path.get(i);
            BetterBlockPos dest = path.get(i + 1);
            IMovement movement = movements.get(i);

            if (!src.equals(movement.getSrc())) {
                throw new IllegalStateException("Path source is not equal to the movement source");
            }
            if (!dest.equals(movement.getDest())) {
                throw new IllegalStateException("Path destination is not equal to the movement destination");
            }
            if (seenSoFar.contains(src)) {
                throw new IllegalStateException("Path doubles back on itself, making a loop");
            }
            seenSoFar.add(src);
        }
    }
}