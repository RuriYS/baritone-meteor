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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.*;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.PathCalculationResult;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.actions.CalculationContext;
import baritone.pathing.actions.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.utils.PathRenderer;
import baritone.utils.PathingCommandContext;
import baritone.utils.pathing.Favoring;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import net.minecraft.core.BlockPos;

public final class PathingBehavior extends Behavior implements IPathingBehavior, Helper {

    private PathExecutor currentPath;
    private PathExecutor nextPlannedPath;

    private Goal goal;
    private CalculationContext context;

    // ETA Purposes
    private int ticksElapsedSoFar;
    private BetterBlockPos startPosition;

    // State flags
    private boolean safeToCancel;
    private boolean pauseRequestedLastTick;
    private boolean unpausedLastTick;
    private boolean pausedThisTick;
    private boolean cancelRequested;
    private boolean calcFailedLastTick;
    private boolean lastAutoJump;

    private volatile AbstractNodeCostSearch pathfinderProcess;

    // Atomic locks
    private final Object pathProcessLock = new Object();
    private final Object pathPlanningLock = new Object();

    private final LinkedBlockingQueue<PathEvent> eventsToDispatch = new LinkedBlockingQueue<>();
    private BetterBlockPos expectedSegmentStart;

    public PathingBehavior(Baritone baritone) {
        super(baritone);
    }

    private void queuePathEvent(PathEvent event) {
        eventsToDispatch.add(event);
    }

    private void dispatchEvents() {
        ArrayList<PathEvent> pathEvents = new ArrayList<>();
        eventsToDispatch.drainTo(pathEvents);
        calcFailedLastTick = pathEvents.contains(PathEvent.CALC_FAILED);
        for (PathEvent event : pathEvents) {
            baritone.getGameEventHandler().onPathEvent(event);
        }
    }

    @Override
    public void onTick(TickEvent event) {
        dispatchEvents();
        if (event.getType() == TickEvent.Type.OUT) {
            secretInternalSegmentCancel();
            baritone.getPathingControlManager().cancelEverything();
            return;
        }

        expectedSegmentStart = pathStart();
        baritone.getPathingControlManager().preTick();
        tickPath();
        ticksElapsedSoFar++;
        dispatchEvents();
    }

    @Override
    public void onPlayerSprintState(SprintStateEvent event) {
        if (Baritone.settings().freeControl.value) {
            return;
        }
        if (isPathing()) {
            event.setState(currentPath.isSprinting());
        }
    }

    /**
     * Processes the current pathing state and manages path execution on each game tick.
     * This method handles path transitions, calculations, and dispatches appropriate events.
     *
     * @see PathEvent
     * @see PathExecutor
     */
    private void tickPath() {
        pausedThisTick = false;
        if (pauseRequestedLastTick && safeToCancel) {
            handlePause();
            return;
        }
        unpausedLastTick = true;

        if (cancelRequested) {
            cancelRequested = false;
            baritone.getInputOverrideHandler().clearAllKeys();
        }

        synchronized (pathPlanningLock) {
            synchronized (pathProcessLock) {
                validateCurrentCalculation();
            }

            if (currentPath == null) return;

            safeToCancel = currentPath.onTick();

            if (currentPath.failed() || currentPath.finished()) {
                handlePathEnd();
                return;
            }

            if (safeToCancel && nextPlannedPath != null && nextPlannedPath.snipsnapifpossible()) {
                logDebug("Splicing into planned next path early...");
                queuePathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
                currentPath = nextPlannedPath;
                nextPlannedPath = null;
                currentPath.onTick();
                return;
            }
            if (Baritone.settings().splicePath.value) {
                currentPath = currentPath.trySplice(nextPlannedPath);
            }
            if (nextPlannedPath != null && currentPath.getPath().getDest().equals(nextPlannedPath.getPath().getDest())) {
                nextPlannedPath = null;
            }
            synchronized (pathProcessLock) {
                if (!lookAhead()) return;
                if (ticksRemainingInSegment().isEmpty()) return;

                // Plan ahead
                logDebug("Path almost over. Planning ahead...");
                queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);
                findPathInNewThread(currentPath.getPath().getDest(), false, context);
            }
        }
    }

    private boolean lookAhead() {
        if (pathfinderProcess != null) {
            return false;
        }
        if (nextPlannedPath == null) {
            return false;
        }
        if (goal == null || goal.isInGoal(currentPath.getPath().getDest())) {
            return false;
        }
        return ticksRemainingInSegment(false).isPresent();
    }

    private void handlePathEnd() {
        currentPath = null;

        if (goal == null || goal.isInGoal(ctx.playerFeet())) {
            logDebug("All done. At " + goal);
            queuePathEvent(PathEvent.AT_GOAL);
            nextPlannedPath = null;
            if (Baritone.settings().disconnectOnArrival.value) {
                ctx.world().disconnect();
            }
            return;
        }

        if (nextPlannedPath != null && !nextPlannedPath.getPath().positions().contains(ctx.playerFeet()) && !nextPlannedPath.getPath().positions().contains(expectedSegmentStart)) { // can contain either one
            logDebug("Discarding next path as it does not contain current position");
            queuePathEvent(PathEvent.DISCARD_NEXT);
            nextPlannedPath = null;
        }

        if (nextPlannedPath != null) {
            logDebug("Continuing on to planned next path");
            queuePathEvent(PathEvent.CONTINUING_ONTO_PLANNED_NEXT);
            currentPath = nextPlannedPath;
            nextPlannedPath = null;
            currentPath.onTick();
            return;
        }

        // Calculate new path immediately
        synchronized (pathProcessLock) {
            if (pathfinderProcess != null) {
                queuePathEvent(PathEvent.PATH_FINISHED_NEXT_STILL_CALCULATING);
                return;
            }

            queuePathEvent(PathEvent.CALC_STARTED);
            findPathInNewThread(expectedSegmentStart, true, context);
        }
    }


    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (currentPath != null) {
            switch (event.getState()) {
                case PRE:
                    lastAutoJump = ctx.minecraft().options.autoJump().get();
                    ctx.minecraft().options.autoJump().set(false);
                    break;
                case POST:
                    ctx.minecraft().options.autoJump().set(lastAutoJump);
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        PathRenderer.render(event, this);
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    @Override
    public boolean isPathing() {
        return hasPath() && !pausedThisTick;
    }

    @Override
    public PathExecutor getCurrentPath() {
        return currentPath;
    }

    @Override
    public PathExecutor getNextPlannedPath() {
        return nextPlannedPath;
    }

    @Override
    public Optional<AbstractNodeCostSearch> getInProgress() {
        return Optional.ofNullable(pathfinderProcess);
    }

    @Override
    public boolean cancelEverything() {
        boolean doIt = isSafeToCancel();
        if (doIt) {
            secretInternalSegmentCancel();
        }
        baritone.getPathingControlManager().cancelEverything(); // regardless of if we can stop the current segment, we can still stop the processes
        return doIt;
    }

    @Override
    public void forceCancel() { // exposed on public api because :sob:
        cancelEverything();
        secretInternalSegmentCancel();
        synchronized (pathProcessLock) {
            pathfinderProcess = null;
        }
    }

    private void handlePause() {
        pauseRequestedLastTick = false;
        if (unpausedLastTick) {
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        }
        unpausedLastTick = false;
        pausedThisTick = true;
    }

    private void validateCurrentCalculation() {
        if (pathfinderProcess != null) {
            BetterBlockPos calcFrom = pathfinderProcess.getStart();
            Optional<IPath> currentBest = pathfinderProcess.bestPathSoFar();
            if ((currentPath == null || !currentPath.getPath().getDest().equals(calcFrom)) // if current ends in inProgress's start, then we're ok
                    && !calcFrom.equals(ctx.playerFeet()) && !calcFrom.equals(expectedSegmentStart) // if current starts in our playerFeet or pathStart, then we're ok
                    && (currentBest.isEmpty() || (!currentBest.get().positions().contains(ctx.playerFeet()) && !currentBest.get().positions().contains(expectedSegmentStart))) // if
            ) {
                pathfinderProcess.cancel();
            }
        }
    }

    public void secretInternalSetGoal(Goal goal) {
        this.goal = goal;
    }

    public void secretInternalSetGoalAndPath(PathingCommand command) {
        secretInternalSetGoal(command.goal);
        if (command instanceof PathingCommandContext) {
            context = ((PathingCommandContext) command).desiredCalcContext;
        } else {
            context = new CalculationContext(baritone, true);
        }
        if (goal == null) {
            return;
        }
        if (goal.isInGoal(ctx.playerFeet()) || goal.isInGoal(expectedSegmentStart)) {
            return;
        }
        synchronized (pathPlanningLock) {
            if (currentPath != null) {
                return;
            }
            synchronized (pathProcessLock) {
                if (pathfinderProcess != null) {
                    return;
                }
                queuePathEvent(PathEvent.CALC_STARTED);
                findPathInNewThread(expectedSegmentStart, true, context);
            }
        }
    }

    public void cancelSegmentIfSafe() {
        if (isSafeToCancel()) {
            secretInternalSegmentCancel();
        }
    }

    public void requestPause() {
        pauseRequestedLastTick = true;
    }

    private void resetEstimatedTicksToGoal() {
        resetEstimatedTicksToGoal(expectedSegmentStart);
    }

    private void resetEstimatedTicksToGoal(BlockPos start) {
        resetEstimatedTicksToGoal(new BetterBlockPos(start));
    }

    private void resetEstimatedTicksToGoal(BetterBlockPos start) {
        ticksElapsedSoFar = 0;
        startPosition = start;
    }

    public void softCancelIfSafe() {
        synchronized (pathPlanningLock) {
            getInProgress().ifPresent(AbstractNodeCostSearch::cancel); // only cancel ours
            if (!isSafeToCancel()) {
                return;
            }
            currentPath = null;
            nextPlannedPath = null;
        }
        cancelRequested = true;
        // do everything BUT clear keys
    }

    public void secretInternalSegmentCancel() {
        queuePathEvent(PathEvent.CANCELED);
        synchronized (pathPlanningLock) {
            getInProgress().ifPresent(AbstractNodeCostSearch::cancel);
            if (currentPath != null) {
                currentPath = null;
                nextPlannedPath = null;
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            }
        }
    }

    public boolean isSafeToCancel() {
        if (currentPath == null) {
            return !baritone.getElytraProcess().isActive() || baritone.getElytraProcess().isSafeToCancel();
        }
        return safeToCancel;
    }

    public boolean calcFailedLastTick() { // NOT exposed on public api
        return calcFailedLastTick;
    }

    public CalculationContext secretInternalGetCalculationContext() {
        return context;
    }

    public Optional<Double> estimatedTicksToGoal() {
        BetterBlockPos currentPos = ctx.playerFeet();
        if (goal == null || currentPos == null || startPosition == null) {
            return Optional.empty();
        }
        if (goal.isInGoal(ctx.playerFeet())) {
            resetEstimatedTicksToGoal();
            return Optional.of(0.0);
        }
        if (ticksElapsedSoFar == 0) {
            return Optional.empty();
        }
        double current = goal.heuristic(currentPos.x, currentPos.y, currentPos.z);
        double start = goal.heuristic(startPosition.x, startPosition.y, startPosition.z);
        if (current == start) {// can't check above because current and start can be equal even if currentPos and startPosition are not
            return Optional.empty();
        }
        double eta = Math.abs(current - goal.heuristic()) * ticksElapsedSoFar / Math.abs(start - current);
        return Optional.of(eta);
    }

    /**
     * See issue #209
     *
     * @return The starting {@link BlockPos} for a new path
     */
    public BetterBlockPos pathStart() { // TODO move to a helper or util class
        BetterBlockPos feet = ctx.playerFeet();
        if (!MovementHelper.canWalkOn(ctx, feet.below())) {
            if (ctx.player().onGround()) {
                double playerX = ctx.player().position().x;
                double playerZ = ctx.player().position().z;
                ArrayList<BetterBlockPos> closest = new ArrayList<>();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        closest.add(new BetterBlockPos(feet.x + dx, feet.y, feet.z + dz));
                    }
                }
                closest.sort(Comparator.comparingDouble(pos -> ((pos.x + 0.5D) - playerX) * ((pos.x + 0.5D) - playerX) + ((pos.z + 0.5D) - playerZ) * ((pos.z + 0.5D) - playerZ)));
                for (int i = 0; i < 4; i++) {
                    BetterBlockPos possibleSupport = closest.get(i);
                    double xDist = Math.abs((possibleSupport.x + 0.5D) - playerX);
                    double zDist = Math.abs((possibleSupport.z + 0.5D) - playerZ);
                    if (xDist > 0.8 && zDist > 0.8) {
                        // can't possibly be sneaking off of this one, we're too far away
                        continue;
                    }
                    if (MovementHelper.canWalkOn(ctx, possibleSupport.below()) && MovementHelper.canWalkThrough(ctx, possibleSupport) && MovementHelper.canWalkThrough(ctx, possibleSupport.above())) {
                        // this is plausible
                        //logDebug("Faking path start assuming player is standing off the edge of a block");
                        return possibleSupport;
                    }
                }

            } else {
                // !onGround
                // we're in the middle of a jump
                if (MovementHelper.canWalkOn(ctx, feet.below().below())) {
                    //logDebug("Faking path start assuming player is midair and falling");
                    return feet.below();
                }
            }
        }
        return feet;
    }

    /**
     * In a new thread, pathfind to target blockpos
     *
     * @param start
     * @param talkAboutIt
     */
    private void findPathInNewThread(final BlockPos start, final boolean talkAboutIt, CalculationContext context) {
        // this must be called with synchronization on pathCalcLock!
        // actually, we can check this, muahaha
        if (!Thread.holdsLock(pathProcessLock)) {
            throw new IllegalStateException("Must be called with synchronization on pathCalcLock");
            // why do it this way? it's already indented so much that putting the whole thing in a synchronized(pathCalcLock) was just too much lol
        }
        if (pathfinderProcess != null) {
            throw new IllegalStateException("Already doing it"); // should have been checked by caller
        }
        if (!context.safeForThreadedUse) {
            throw new IllegalStateException("Improper context thread safety level");
        }
        Goal goal = this.goal;
        if (goal == null) {
            logDebug("no goal"); // TODO should this be an exception too? definitely should be checked by caller
            return;
        }
        long primaryTimeout;
        long failureTimeout;
        if (currentPath == null) {
            primaryTimeout = Baritone.settings().primaryTimeoutMS.value;
            failureTimeout = Baritone.settings().failureTimeoutMS.value;
        } else {
            primaryTimeout = Baritone.settings().planAheadPrimaryTimeoutMS.value;
            failureTimeout = Baritone.settings().planAheadFailureTimeoutMS.value;
        }
        AbstractNodeCostSearch pathfinder = createPathfinder(start, goal, currentPath == null ? null : currentPath.getPath(), context);
        if (!Objects.equals(pathfinder.getGoal(), goal)) { // will return the exact same object if simplification didn't happen
            logDebug("Simplifying " + goal.getClass() + " to GoalXZ due to distance");
        }
        this.pathfinderProcess = pathfinder;
        Baritone.getExecutor().execute(() -> {
            if (talkAboutIt) {
                logDebug("Starting to search for path from " + start + " to " + goal);
            }

            PathCalculationResult calcResult = pathfinder.calculate(primaryTimeout, failureTimeout);
            synchronized (pathPlanningLock) {
                Optional<PathExecutor> executor = calcResult.getPath().map(p -> new PathExecutor(PathingBehavior.this, p));
                if (currentPath == null) {
                    if (executor.isPresent()) {
                        if (executor.get().getPath().positions().contains(expectedSegmentStart)) {
                            queuePathEvent(PathEvent.CALC_FINISHED_NOW_EXECUTING);
                            currentPath = executor.get();
                            resetEstimatedTicksToGoal(start);
                        } else {
                            logDebug("Warning: discarding orphan path segment with incorrect start");
                        }
                    } else {
                        if (calcResult.getType() != PathCalculationResult.Type.CANCELLATION && calcResult.getType() != PathCalculationResult.Type.EXCEPTION) {
                            // don't dispatch CALC_FAILED on cancellation
                            queuePathEvent(PathEvent.CALC_FAILED);
                        }
                    }
                } else {
                    if (nextPlannedPath == null) {
                        if (executor.isPresent()) {
                            if (executor.get().getPath().getSrc().equals(currentPath.getPath().getDest())) {
                                queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
                                nextPlannedPath = executor.get();
                            } else {
                                logDebug("Warning: discarding orphan next segment with incorrect start");
                            }
                        } else {
                            queuePathEvent(PathEvent.NEXT_CALC_FAILED);
                        }
                    } else {
                        //throw new IllegalStateException("I have no idea what to do with this path");
                        // no point in throwing an exception here, and it gets it stuck with inProgress being not null
                        logDirect("Warning: PathingBehaivor illegal state! Discarding invalid path!");
                    }
                }
                if (talkAboutIt && currentPath != null && currentPath.getPath() != null) {
                    if (goal.isInGoal(currentPath.getPath().getDest())) {
                        logDebug("Finished finding a path from " + start + " to " + goal + ". " + currentPath.getPath().getNumNodesConsidered() + " nodes considered");
                    } else {
                        logDebug("Found path segment from " + start + " towards " + goal + ". " + currentPath.getPath().getNumNodesConsidered() + " nodes considered");
                    }
                }
                synchronized (pathProcessLock) {
                    this.pathfinderProcess = null;
                }
            }
        });
    }

    private static AbstractNodeCostSearch createPathfinder(BlockPos start, Goal goal, IPath previous, CalculationContext context) {
        Goal transformed = goal;
        if (Baritone.settings().simplifyUnloadedYCoord.value && goal instanceof IGoalRenderPos) {
            BlockPos pos = ((IGoalRenderPos) goal).getGoalPos();
            if (!context.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                transformed = new GoalXZ(pos.getX(), pos.getZ());
            }
        }
        Favoring favoring = new Favoring(context.getBaritone().getPlayerContext(), previous, context);
        return new AStarPathFinder(start.getX(), start.getY(), start.getZ(), transformed, favoring, context);
    }
}
