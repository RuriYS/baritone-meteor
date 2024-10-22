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

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.utils.BlockStateInterface;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.AABB;

public abstract class Movement implements IMovement, MovementHelper {

    public static final Direction[] VALID_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
    };

    protected final IBaritone baritone;
    protected final IPlayerContext ctx;
    protected final BetterBlockPos src;
    protected final BetterBlockPos dest;
    protected final BetterBlockPos[] blocksToBreak;
    protected final BetterBlockPos blockToPlace;

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);
    private Double cost;
    private Set<BetterBlockPos> validPositionsCached = null;
    private Boolean calculatedWhileLoaded;

    public List<BlockPos> toBreakCached = null;
    public List<BlockPos> toPlaceCached = null;
    public List<BlockPos> toWalkIntoCached = null;

    /**
     * Creates a new movement between two points.
     * @param baritone The baritone instance
     * @param src Starting position
     * @param dest Destination position
     * @param blocksToBreak Blocks that need to be broken for movement
     * @param blockToPlace Block that needs to be placed for movement
     */
    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] blocksToBreak, BetterBlockPos blockToPlace) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.src = src;
        this.dest = dest;
        this.blocksToBreak = blocksToBreak;
        this.blockToPlace = blockToPlace;
    }

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak) {
        this(baritone, src, dest, toBreak, null);
    }

    @Override
    public MovementStatus update() {
        currentState = updateState(currentState);

        if (!Baritone.settings().freeControl.value) {
            ctx.player().getAbilities().flying = false;
            handleMovementActions();
            handleRotations();
        }

        if (currentState.getStatus().isComplete()) {
            baritone.getInputOverrideHandler().clearAllKeys();
        }

        return currentState.getStatus();
    }

    /**
     * Handles swimming and wall-breaking actions based on player position
     */
    private void handleMovementActions() {
        // Auto-swim in liquids
        if (MovementHelper.isLiquid(ctx, ctx.playerFeet()) &&
                ctx.player().position().y < dest.y + 0.6) {
            currentState.setInput(Input.JUMP, true);
        }

        // Break blocks when stuck
        if (ctx.player().isInWall()) {
            ctx.getSelectedBlock().ifPresent(pos ->
                    MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, pos)));
            currentState.setInput(Input.CLICK_LEFT, true);
        }
    }

    /**
     * Updates player rotation and input states
     */
    private void handleRotations() {
        currentState.getTarget().getRotation().ifPresent(rotation ->
                baritone.getLookBehavior().updateTarget(
                        rotation,
                        currentState.getTarget().hasToForceRotations()
                )
        );

        baritone.getInputOverrideHandler().clearAllKeys();
        currentState.getInputStates().forEach((input, forced) ->
                baritone.getInputOverrideHandler().setInputForceState(input, forced));
        currentState.getInputStates().clear();
    }

    @Override
    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState currentState) {
        return true;
    }

    @Override
    public BetterBlockPos getSrc() {
        return src;
    }

    @Override
    public BetterBlockPos getDest() {
        return dest;
    }

    @Override
    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
    }

    public abstract double calculateCost(CalculationContext context);

    protected abstract Set<BetterBlockPos> calculateValidPositions();

    public double getCost() throws NullPointerException {
        return cost;
    }

    public double getCost(CalculationContext context) {
        if (cost == null) {
            cost = calculateCost(context);
        }
        return cost;
    }

    public double recalculateCost(CalculationContext context) {
        cost = null;
        return getCost(context);
    }

    public void overrideCost(double cost) {
        this.cost = cost;
    }

    public Set<BetterBlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    protected boolean playerNotInValidPosition() {
        return !getValidPositions().contains(ctx.playerFeet()) && !getValidPositions().contains(((PathingBehavior) baritone.getPathingBehavior()).pathStart());
    }

    /**
     * Checks if the movement is prepared to execute by validating block positions and setting player actions.
     *
     * @param state Current movement state
     * @return true if prepared or unreachable, false if still needs preparation
     */
    protected boolean isNotPrepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return false;
        }

        for (BetterBlockPos blockPos : blocksToBreak) {
            // Check for falling blocks that could interfere
            if (!ctx.world().getEntitiesOfClass(FallingBlockEntity.class,
                    new AABB(0, 0, 0, 1, 1.1, 1).move(blockPos)).isEmpty() &&
                    Baritone.settings().pauseMiningForFallingBlocks.value) {
                return true;
            }

            // Skip if block is already walkable
            if (MovementHelper.canWalkThrough(ctx, blockPos)) {
                continue;
            }

            // Prepare to break block
            MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, blockPos));
            Optional<Rotation> reachable = RotationUtils.reachable(ctx, blockPos,
                    ctx.playerController().getBlockReachDistance());

            // Handle block breaking based on reachability
            if (reachable.isPresent()) {
                Rotation rotTowardsBlock = reachable.get();
                state.setTarget(new MovementState.MovementTarget(rotTowardsBlock, true));

                if (ctx.isLookingAt(blockPos) || ctx.playerRotations().isReallyCloseTo(rotTowardsBlock)) {
                    state.setInput(Input.CLICK_LEFT, true);
                }
                return true;
            }

            // Fallback: attempt to break block even if not directly reachable
            state.setTarget(new MovementState.MovementTarget(
                    RotationUtils.calcRotationFromVec3d(
                            ctx.playerHead(),
                            VecUtils.getBlockPosCenter(blockPos),
                            ctx.playerRotations()
                    ), true)
            );
            state.setInput(Input.CLICK_LEFT, true);
            return true;
        }

        // If we found blocks but couldn't target any, mark as unreachable
        if (!MovementHelper.canWalkThrough(ctx, blocksToBreak[0])) {
            state.setStatus(MovementStatus.UNREACHABLE);
        }

        return false;
    }

    /**
     * Calculate latest movement state. Gets called once a tick.
     *
     * @param state The current state
     * @return The new state
     */
    public MovementState updateState(MovementState state) {
        if (isNotPrepared(state)) {
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }

        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }

        return state;
    }

    @Override
    public BlockPos getDirection() {
        return getDest().subtract(getSrc());
    }

    public void checkLoadedChunk(CalculationContext context) {
        calculatedWhileLoaded = context.bsi.worldContainsLoadedChunk(dest.x, dest.z);
    }

    @Override
    public boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }

    @Override
    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    public List<BlockPos> toBreak(BlockStateInterface bsi) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BetterBlockPos positionToBreak : blocksToBreak) {
            if (!MovementHelper.canWalkThrough(bsi, positionToBreak.x, positionToBreak.y, positionToBreak.z)) {
                result.add(positionToBreak);
            }
        }
        toBreakCached = result;
        return result;
    }

    public List<BlockPos> toPlace(BlockStateInterface bsi) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (blockToPlace != null && !MovementHelper.canWalkOn(bsi, blockToPlace.x, blockToPlace.y, blockToPlace.z)) {
            result.add(blockToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    public List<BlockPos> toWalkInto(BlockStateInterface bsi) { // overridden by movementdiagonal
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    public BlockPos[] toBreakAll() {
        return blocksToBreak;
    }
}
