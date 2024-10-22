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

package baritone.pathing.path;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.actions.CalculationContext;
import baritone.pathing.actions.Movement;
import baritone.pathing.actions.MovementHelper;
import baritone.pathing.actions.movements.*;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Tuple;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Executes a pre-calculated path by managing actions states, handling path validation,
 * and coordinating with the broader pathing system.
 * <ol>
 * <li>Tracking the current position along the path</li>
 * <li>Validating path integrity and handling deviations</li>
 * <li>Managing actions states and transitions</li>
 * <li>Coordinating with the sprint controller</li>
 * <li>Handling path splicing and optimization</li>
 * </ol>
 *
 * @author leijurv (original)
 */
public class PathExecutor implements IPathExecutor, Helper {

    // Constants for path validation
    private static final double MAX_DIST_FROM_PATH = 2.0;
    private static final double MAX_MAX_DIST_FROM_PATH = 3.0;
    private static final double MAX_TICKS_AWAY = 200.0;

    // Core path state
    private final IPath path;
    private final PathingBehavior behavior;
    private final IPlayerContext ctx;

    // Path execution state
    private int pathPosition;
    private int ticksAway;
    private int ticksOnCurrent;
    private Double currentMovementOriginalCostEstimate;
    private Integer costEstimateIndex;
    private boolean failed;
    private boolean sprintNextTick;

    // Block modification tracking
    private boolean recalcBP = true;
    private final Set<BlockPos> toBreak = new HashSet<>();
    private final Set<BlockPos> toPlace = new HashSet<>();
    private final Set<BlockPos> toWalkInto = new HashSet<>();

    /**
     * Creates a new path executor for the given path.
     *
     * @param behavior The pathing behavior that owns this executor
     * @param path The path to execute
     */
    public PathExecutor(PathingBehavior behavior, IPath path) {
        this.behavior = behavior;
        this.ctx = behavior.ctx;
        this.path = path;
        this.pathPosition = 0;
    }

    @Override
    public int getPosition() {
        return pathPosition;
    }

    @Override
    public IPath getPath() {
        return path;
    }

    /**
     * Updates the path execution state for the current tick.
     *
     * @return true if the current actions is complete or cancellable, false otherwise
     */
    public boolean onTick() {
        if (pathPosition == path.length() - 1) {
            pathPosition++;
        }
        if (pathPosition >= path.length()) {
            return true;
        }

        Movement movement = (Movement) path.movements().get(pathPosition);
        BetterBlockPos playerPos = ctx.playerFeet();

        // Validate position
        if (!Baritone.settings().freeControl.value && !isPositionValid(movement, playerPos)) {
            logDebug("Invalid pos, pos: " + playerPos);
            if (invalidPosHandled(playerPos)) {
                return false;
            }
        }

        // Path proximity check
        Tuple<Double, BlockPos> status = closestPathPos(path);
        if (pathProximitHandled(status)) {
            return false;
        }

        // Update block states
        if (recalcBP) {
            updateBlockStates();
        }

        // Check loaded chunks
        if (pathPosition < path.movements().size() - 1) {
            IMovement next = path.movements().get(pathPosition + 1);
            if (!behavior.baritone.bsi.worldContainsLoadedChunk(next.getDest().x, next.getDest().z)) {
                logDebug("Pausing since destination is at edge of loaded chunks");
                clearKeys();
                return true;
            }
        }

        // Movement state handling
        boolean canCancel = movement.safeToCancel();

        // Cost validation
        if (!validateMovementCosts(movement, canCancel)) {
            return true;
        }

        // Execute actions
        MovementStatus movementStatus = movement.update();

        // logDebug("Movement status: " + movementStatus.toString());
        // logDebug("Current actions: " + actions);

        if (movementStatus == MovementStatus.UNREACHABLE || movementStatus == MovementStatus.FAILED) {
            logDebug("Movement returns status " + movementStatus);
            cancel();
            return true;
        }

        if (movementStatus == MovementStatus.SUCCESS) {
            pathPosition++;
            onChangeInPathPosition();
            onTick();
            return true;
        }

        // Handle ongoing actions
        sprintNextTick = shouldSprintNextTick();
        if (!sprintNextTick && !Baritone.settings().freeControl.value) {
            ctx.player().setSprinting(false);
        }

        ticksOnCurrent++;
        if (ticksOnCurrent > currentMovementOriginalCostEstimate + Baritone.settings().movementTimeoutTicks.value) {
            logDebug("This actions has taken too long (" + ticksOnCurrent + " ticks, expected " + currentMovementOriginalCostEstimate + "). Cancelling.");
            cancel();
            return true;
        }

        return canCancel;
    }

    private boolean isPositionValid(Movement movement, BetterBlockPos position) {
        // Direct position check
        if (movement.getValidPositions().contains(position)) {
            return true;
        }

        // Special case for path start
        if (pathPosition == 0) {
            return movement.getSrc().equals(position) ||
                    Math.abs(position.getX() - movement.getSrc().getX()) <= 1 &&
                            Math.abs(position.getZ() - movement.getSrc().getZ()) <= 1 &&
                            Math.abs(position.getY() - movement.getSrc().getY()) <= 1;
        }

        return false;
    }

    private boolean invalidPosHandled(BetterBlockPos playerPos) {
        // Check previous positions
        for (int i = 0; i < pathPosition && i < path.length(); i++) {
            if (((Movement) path.movements().get(i)).getValidPositions().contains(playerPos)) {
                int previousPos = pathPosition;
                pathPosition = i;
                for (int j = pathPosition; j <= previousPos; j++) {
                    path.movements().get(j).reset();
                }
                onChangeInPathPosition();
                onTick();
                return true;
            }
        }

        // Check future positions
        for (int i = pathPosition + 3; i < path.length() - 1; i++) {
            if (((Movement) path.movements().get(i)).getValidPositions().contains(playerPos)) {
                if (i - pathPosition > 2) {
                    logDebug("Skipping forward " + (i - pathPosition) + " steps, to " + i);
                }
                pathPosition = i - 1;
                onChangeInPathPosition();
                onTick();
                return true;
            }
        }

        return false;
    }

    private boolean pathProximitHandled(Tuple<Double, BlockPos> status) {
        if (possiblyOffPath(status, MAX_DIST_FROM_PATH)) {
            ticksAway++;
            if (ticksAway > MAX_TICKS_AWAY) {
                logDebug("Too far away from path for too long, cancelling path");
                cancel();
                return true;
            }
        } else {
            ticksAway = 0;
        }

        if (possiblyOffPath(status, MAX_MAX_DIST_FROM_PATH)) {
            logDebug("Too far from path, cancelling");
            cancel();
            return true;
        }
        return false;
    }

    private void updateBlockStates() {
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        Set<BlockPos> newBreak = new HashSet<>();
        Set<BlockPos> newPlace = new HashSet<>();
        Set<BlockPos> newWalkInto = new HashSet<>();

        for (int i = pathPosition; i < path.movements().size(); i++) {
            Movement m = (Movement) path.movements().get(i);
            newBreak.addAll(m.toBreak(bsi));
            newPlace.addAll(m.toPlace(bsi));
            newWalkInto.addAll(m.toWalkInto(bsi));
        }

        toBreak.clear();
        toPlace.clear();
        toWalkInto.clear();
        toBreak.addAll(newBreak);
        toPlace.addAll(newPlace);
        toWalkInto.addAll(newWalkInto);
        recalcBP = false;
    }

    private boolean validateMovementCosts(Movement movement, boolean canCancel) {
        if (costEstimateIndex == null || costEstimateIndex != pathPosition) {
            costEstimateIndex = pathPosition;
            currentMovementOriginalCostEstimate = movement.getCost();

            for (int i = 1; i < Baritone.settings().costVerificationLookahead.value && pathPosition + i < path.length() - 1; i++) {
                if (((Movement) path.movements().get(pathPosition + i)).calculateCost(behavior.secretInternalGetCalculationContext()) >= ActionCosts.COST_INF && canCancel) {
                    logDebug("Something has changed in the world and a future actions has become impossible. Cancelling.");
                    cancel();
                    return false;
                }
            }
        }

        double currentCost = movement.recalculateCost(behavior.secretInternalGetCalculationContext());
        if (currentCost >= ActionCosts.COST_INF && canCancel) {
            logDebug("Something has changed in the world and this actions has become impossible. Cancelling.");
            cancel();
            return false;
        }

        if (!movement.calculatedWhileLoaded() &&
                currentCost - currentMovementOriginalCostEstimate > Baritone.settings().maxCostIncrease.value &&
                canCancel) {
            logDebug("Original cost " + currentMovementOriginalCostEstimate + " current cost " + currentCost + ". Cancelling.");
            cancel();
            return false;
        }

        if (shouldPause()) {
            logDebug("Pausing since current best path is a backtrack");
            clearKeys();
            return false;
        }

        return true;
    }

    private void onChangeInPathPosition() {
        clearKeys();
        ticksOnCurrent = 0;
    }

    private void clearKeys() {
        behavior.baritone.getInputOverrideHandler().clearAllKeys();
    }

    private void cancel() {
        clearKeys();
        behavior.baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        pathPosition = path.length() + 3;
        failed = true;
    }

    private boolean shouldPause() {
        Optional<AbstractNodeCostSearch> current = behavior.getInProgress();
        if (current.isEmpty()) {
            return false;
        }
        if (!ctx.player().onGround()) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, ctx.playerFeet().below())) {
            // we're in some kind of sketchy situation, maybe parkouring
            return false;
        }
        if (!MovementHelper.canWalkThrough(ctx, ctx.playerFeet()) || !MovementHelper.canWalkThrough(ctx, ctx.playerFeet().above())) {
            // suffocating?
            return false;
        }
        if (!path.movements().get(pathPosition).safeToCancel()) {
            return false;
        }
        Optional<IPath> currentBest = current.get().bestPathSoFar();
        if (currentBest.isEmpty()) {
            return false;
        }
        List<BetterBlockPos> positions = currentBest.get().positions();
        if (positions.size() < 3) {
            return false; // not long enough yet to justify pausing, its far from certain we'll actually take this route
        }
        // the first block of the next path will always overlap
        // no need to pause our very last actions when it would have otherwise cleanly exited with MovementStatus SUCCESS
        positions = positions.subList(1, positions.size());
        return positions.contains(ctx.playerFeet());
    }

    private boolean possiblyOffPath(Tuple<Double, BlockPos> status, double leniency) {
        double distanceFromPath = status.getA();
        if (Baritone.settings().freeControl.value) {
            leniency = 30.0; // Allow much more distance when in free control TODO config for this
        }
        if (distanceFromPath > leniency) {
            // when we're midair in the middle of a fall, we're very far from both the beginning and the end, but we aren't actually off path
            if (path.movements().get(pathPosition) instanceof Fall) {
                BlockPos fallDest = path.positions().get(pathPosition + 1); // .get(pathPosition) is the block we fell off of
                return VecUtils.entityFlatDistanceToCenter(ctx.player(), fallDest) >= leniency; // ignore Y by using flat distance
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    public boolean snipsnapifpossible() {
        if (!ctx.player().onGround() && ctx.world().getFluidState(ctx.playerFeet()).isEmpty()) {
            // if we're falling in the air, and not in water, don't splice
            return false;
        } else {
            // we are either onGround or in liquid
            if (ctx.player().getDeltaMovement().y < -0.1) {
                // if we are strictly moving downwards (not stationary)
                // we could be falling through water, which could be unsafe to splice
                return false; // so don't
            }
        }
        int index = path.positions().indexOf(ctx.playerFeet());
        if (index == -1) {
            return false;
        }
        pathPosition = index; // jump directly to current position
        clearKeys();
        return true;
    }

    private boolean shouldSprintNextTick() {
        boolean requested = behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SPRINT);

        // we'll take it from here, no need for minecraft to see we're holding down control and sprint for us
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);

        // first and foremost, if allowSprint is off, or if we don't have enough hunger, don't try and sprint
        if (!new CalculationContext(behavior.baritone, false).canSprint) {
            return false;
        }
        IMovement current = path.movements().get(pathPosition);

        // traverse requests sprinting, so we need to do this check first
        if (current instanceof Traverse && pathPosition < path.length() - 3) {
            IMovement next = path.movements().get(pathPosition + 1);
            if (next instanceof Ascend && sprintableAscend(ctx, (Traverse) current, (Ascend) next, path.movements().get(pathPosition + 2))) {
                if (skipNow(ctx, current)) {
                    logDebug("Skipping traverse to straight ascend");
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick();
                    behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    return true;
                } else {
                    logDebug("Too far to the side to safely sprint ascend");
                }
            }
        }

        // if the actions requested sprinting, then we're done
        if (requested) {
            return true;
        }

        // however, descend and ascend don't request sprinting, because they don't know the context of what actions comes after it
        if (current instanceof Descend) {

            if (pathPosition < path.length() - 2) {
                // keep this out of onTick, even if that means a tick of delay before it has an effect
                IMovement next = path.movements().get(pathPosition + 1);
                if (MovementHelper.canUseFrostWalker(ctx, next.getDest().below())) {
                    // frostwalker only works if you cross the edge of the block on ground so in some cases we may not overshoot
                    // Since Descend can't know the next actions we have to tell it
                    if (next instanceof Traverse || next instanceof Parkour) {
                        boolean couldPlaceInstead = Baritone.settings().allowPlace.value && behavior.baritone.getInventoryBehavior().hasGenericThrowaway() && next instanceof Parkour; // traverse doesn't react fast enough
                        // this is true if the next actions does not ascend or descends and goes into the same cardinal direction (N-NE-E-SE-S-SW-W-NW) as the descend
                        // in that case current.getDirection() is e.g. (0, -1, 1) and next.getDirection() is e.g. (0, 0, 3) so the cross product of (0, 0, 1) and (0, 0, 3) is taken, which is (0, 0, 0) because the vectors are colinear (don't form a plane)
                        // since movements in exactly the opposite direction (e.g. descend (0, -1, 1) and traverse (0, 0, -1)) would also pass this check we also have to rule out that case
                        // we can do that by adding the directions because traverse is always 1 long like descend and parkour can't jump through current.getSrc().down()
                        boolean sameFlatDirection = !current.getDirection().above().offset(next.getDirection()).equals(BlockPos.ZERO)
                                && current.getDirection().above().cross(next.getDirection()).equals(BlockPos.ZERO); // here's why you learn maths in school
                        if (sameFlatDirection && !couldPlaceInstead) {
                            ((Descend) current).forceSafeMode();
                        }
                    }
                }
            }
            if (((Descend) current).safeMode() && !((Descend) current).skipToAscend()) {
                logDebug("Sprinting would be unsafe");
                return false;
            }

            if (pathPosition < path.length() - 2) {
                IMovement next = path.movements().get(pathPosition + 1);
                if (next instanceof Ascend && current.getDirection().above().equals(next.getDirection().below())) {
                    // a descend then an ascend in the same direction
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick();
                    // okay to skip clearKeys and / or onChangeInPathPosition here since this isn't possible to repeat, since it's asymmetric
                    logDebug("Skipping descend to straight ascend");
                    return true;
                }
                if (canSprintFromDescendInto(ctx, current, next)) {

                    if (next instanceof Descend && pathPosition < path.length() - 3) {
                        IMovement next_next = path.movements().get(pathPosition + 2);
                        if (next_next instanceof Descend && !canSprintFromDescendInto(ctx, next, next_next)) {
                            return false;
                        }

                    }
                    if (ctx.playerFeet().equals(current.getDest())) {
                        pathPosition++;
                        onChangeInPathPosition();
                        onTick();
                    }

                    return true;
                }
                //logDebug("Turning off sprinting " + actions + " " + next + " " + actions.getDirection() + " " + next.getDirection().down() + " " + next.getDirection().down().equals(actions.getDirection()));
            }
        }
        if (current instanceof Ascend && pathPosition != 0) {
            IMovement prev = path.movements().get(pathPosition - 1);
            if (prev instanceof Descend && prev.getDirection().above().equals(current.getDirection().below())) {
                BlockPos center = current.getSrc().above();
                // playerFeet adds 0.1251 to account for soul sand
                // farmland is 0.9375
                // 0.07 is to account for farmland
                if (ctx.player().position().y >= center.getY() - 0.07) {
                    behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
                    return true;
                }
            }
            if (pathPosition < path.length() - 2 && prev instanceof Traverse && sprintableAscend(ctx, (Traverse) prev, (Ascend) current, path.movements().get(pathPosition + 1))) {
                return true;
            }
        }
        if (current instanceof Fall) {
            Tuple<Vec3, BlockPos> data = overrideFall((Fall) current);
            if (data != null) {
                BetterBlockPos fallDest = new BetterBlockPos(data.getB());
                if (!path.positions().contains(fallDest)) {
                    throw new IllegalStateException();
                }
                if (ctx.playerFeet().equals(fallDest)) {
                    pathPosition = path.positions().indexOf(fallDest);
                    onChangeInPathPosition();
                    onTick();
                    return true;
                }
                clearKeys();
                behavior.baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), data.getA(), ctx.playerRotations()), false);
                behavior.baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                return true;
            }
        }
        return false;
    }

    private static boolean skipNow(IPlayerContext ctx, IMovement current) {
        double offTarget = Math.abs(current.getDirection().getX() * (current.getSrc().z + 0.5D - ctx.player().position().z)) + Math.abs(current.getDirection().getZ() * (current.getSrc().x + 0.5D - ctx.player().position().x));
        if (offTarget > 0.1) {
            return false;
        }
        // we are centered
        BlockPos headBonk = current.getSrc().subtract(current.getDirection()).above(2);
        if (MovementHelper.fullyPassable(ctx, headBonk)) {
            return true;
        }
        // wait 0.3
        double flatDist = Math.abs(current.getDirection().getX() * (headBonk.getX() + 0.5D - ctx.player().position().x)) + Math.abs(current.getDirection().getZ() * (headBonk.getZ() + 0.5 - ctx.player().position().z));
        return flatDist > 0.8;
    }

    private static boolean sprintableAscend(IPlayerContext ctx, Traverse current, Ascend next, IMovement nextnext) {
        if (!Baritone.settings().sprintAscends.value) {
            return false;
        }
        if (!current.getDirection().equals(next.getDirection().below())) {
            return false;
        }
        if (nextnext.getDirection().getX() != next.getDirection().getX() || nextnext.getDirection().getZ() != next.getDirection().getZ()) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, current.getDest().below())) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, next.getDest().below())) {
            return false;
        }
        if (!next.toBreakCached.isEmpty()) {
            return false; // it's breaking
        }
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos chk = current.getSrc().above(y);
                if (x == 1) {
                    chk = chk.offset(current.getDirection());
                }
                if (!MovementHelper.fullyPassable(ctx, chk)) {
                    return false;
                }
            }
        }
        if (MovementHelper.avoidWalkingInto(ctx.world().getBlockState(current.getSrc().above(3)))) {
            return false;
        }
        return !MovementHelper.avoidWalkingInto(ctx.world().getBlockState(next.getDest().above(2))); // codacy smh my head
    }

    private static boolean canSprintFromDescendInto(IPlayerContext ctx, IMovement current, IMovement next) {
        if (next instanceof Descend && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        if (!MovementHelper.canWalkOn(ctx, current.getDest().offset(current.getDirection()))) {
            return false;
        }
        if (next instanceof Traverse && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        return next instanceof Diagonal && Baritone.settings().allowOvershootDiagonalDescend.value;
    }

    public boolean failed() {
        return failed;
    }

    public boolean finished() {
        return pathPosition >= path.length();
    }

    public boolean isSprinting() {
        return sprintNextTick;
    }

    private Tuple<Double, BlockPos> closestPathPos(IPath path) {
        double best = -1;
        BlockPos bestPos = null;
        for (IMovement movement : path.movements()) {
            for (BlockPos pos : ((Movement) movement).getValidPositions()) {
                double dist = VecUtils.entityDistanceToCenter(ctx.player(), pos);
                if (dist < best || best == -1) {
                    best = dist;
                    bestPos = pos;
                }
            }
        }
        return new Tuple<>(best, bestPos);
    }

    private Tuple<Vec3, BlockPos> overrideFall(Fall movement) {
        Vec3i dir = movement.getDirection();
        if (dir.getY() < -3) {
            return null;
        }
        if (!movement.toBreakCached.isEmpty()) {
            return null; // it's breaking
        }
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        int i;
        outer:
        for (i = pathPosition + 1; i < path.length() - 1 && i < pathPosition + 3; i++) {
            IMovement next = path.movements().get(i);
            if (!(next instanceof Traverse)) {
                break;
            }
            if (!flatDir.equals(next.getDirection())) {
                break;
            }
            for (int y = next.getDest().y; y <= movement.getSrc().y + 1; y++) {
                BlockPos chk = new BlockPos(next.getDest().x, y, next.getDest().z);
                if (!MovementHelper.fullyPassable(ctx, chk)) {
                    break outer;
                }
            }
            if (!MovementHelper.canWalkOn(ctx, next.getDest().below())) {
                break;
            }
        }
        i--;
        if (i == pathPosition) {
            return null; // no valid extension exists
        }
        double len = i - pathPosition - 0.4;
        return new Tuple<>(
                new Vec3(flatDir.getX() * len + movement.getDest().x + 0.5, movement.getDest().y, flatDir.getZ() * len + movement.getDest().z + 0.5),
                movement.getDest().offset(flatDir.getX() * (i - pathPosition), 0, flatDir.getZ() * (i - pathPosition)));
    }

    public PathExecutor trySplice(PathExecutor next) {
        if (next == null) {
            return cutIfTooLong();
        }
        return SplicedPath.trySplice(path, next.path, false).map(path -> {
            if (!path.getDest().equals(next.getPath().getDest())) {
                throw new IllegalStateException();
            }
            PathExecutor ret = new PathExecutor(behavior, path);
            ret.pathPosition = pathPosition;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            ret.costEstimateIndex = costEstimateIndex;
            ret.ticksOnCurrent = ticksOnCurrent;
            return ret;
        }).orElseGet(this::cutIfTooLong); // dont actually call cutIfTooLong every tick if we won't actually use it, use a method reference
    }

    private PathExecutor cutIfTooLong() {
        if (pathPosition > Baritone.settings().maxPathHistoryLength.value) {
            int cutoffAmt = Baritone.settings().pathHistoryCutoffAmount.value;
            CutoffPath newPath = new CutoffPath(path, cutoffAmt, path.length() - 1);
            if (!newPath.getDest().equals(path.getDest())) {
                throw new IllegalStateException();
            }
            logDebug("Discarding earliest segment movements, length cut from " + path.length() + " to " + newPath.length());
            PathExecutor ret = new PathExecutor(behavior, newPath);
            ret.pathPosition = pathPosition - cutoffAmt;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            if (costEstimateIndex != null) {
                ret.costEstimateIndex = costEstimateIndex - cutoffAmt;
            }
            ret.ticksOnCurrent = ticksOnCurrent;
            return ret;
        }
        return this;
    }

    public Set<BlockPos> toBreak() {
        return Collections.unmodifiableSet(toBreak);
    }

    public Set<BlockPos> toPlace() {
        return Collections.unmodifiableSet(toPlace);
    }

    public Set<BlockPos> toWalkInto() {
        return Collections.unmodifiableSet(toWalkInto);
    }
}
