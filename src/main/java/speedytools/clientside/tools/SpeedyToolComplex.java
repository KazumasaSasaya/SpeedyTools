package speedytools.clientside.tools;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.util.*;
import net.minecraft.world.World;
import speedytools.clientside.UndoManagerClient;
import speedytools.clientside.network.CloneToolsNetworkClient;
import speedytools.clientside.network.PacketSenderClient;
import speedytools.clientside.rendering.*;
import speedytools.clientside.selections.ClientVoxelSelection;
import speedytools.clientside.sound.SoundController;
import speedytools.clientside.sound.SoundEffectBoundaryHum;
import speedytools.clientside.sound.SoundEffectNames;
import speedytools.clientside.userinput.PowerUpEffect;
import speedytools.clientside.userinput.UserInput;
import speedytools.common.SpeedyToolsOptions;
import speedytools.common.items.ItemComplexBase;
import speedytools.common.network.ClientStatus;
import speedytools.common.network.ServerStatus;
import speedytools.common.utilities.Colour;
import speedytools.common.utilities.QuadOrientation;
import speedytools.common.utilities.ResultWithReason;
import speedytools.common.utilities.UsefulConstants;

import java.util.LinkedList;
import java.util.List;

import static speedytools.clientside.selections.BlockMultiSelector.selectFill;

/**
* User: The Grey Ghost
* Date: 18/04/2014
*
* The various renders for this tool are:
* 1) Wireframe highlight of the block(s) under the cursor when no selection made yet
* 2) The boundary field (if any)
* 3) The solid selection, when made
* 4) The various status indicators on the cursor:
*   a) When generating a selection - the "busy" cursor with progress
*   b) When a selection is made, the tool continually shows the server "ready" status
*   c) When a selection is made: the power up animation for the action or the undo
*   d) When an action is in progress: the progress meter
*   e) When an undo is in progress: the progress meter
*
*/
public abstract class SpeedyToolComplex extends SpeedyToolComplexBase
{
  public SpeedyToolComplex(ItemComplexBase i_parentItem, SpeedyToolRenderers i_renderers, SoundController i_speedyToolSounds, UndoManagerClient i_undoManagerClient,
                           CloneToolsNetworkClient i_cloneToolsNetworkClient, SpeedyToolBoundary i_speedyToolBoundary,
                           ClientVoxelSelection i_clientVoxelSelection,
                           SelectionPacketSender i_selectionPacketSender, PacketSenderClient i_packetSenderClient) {
    super(i_parentItem, i_renderers, i_speedyToolSounds, i_undoManagerClient, i_packetSenderClient);
    itemComplexBase = i_parentItem;
    speedyToolBoundary = i_speedyToolBoundary;
    boundaryFieldRendererUpdateLink = this.new BoundaryFieldRendererUpdateLink();
    wireframeRendererUpdateLink = this.new CopyToolWireframeRendererLink();
    solidSelectionRendererUpdateLink = this.new SolidSelectionRendererLink();
    cursorRenderInfoUpdateLink = this.new CursorRenderInfoLink();
    statusMessageRenderInfoUpdateLink = this.new StatusMessageRenderInfoLink();
    cloneToolsNetworkClient = i_cloneToolsNetworkClient;
//    selectionPacketSender = i_selectionPacketSender;
    clientVoxelSelection = i_clientVoxelSelection;
  }

  @Override
  public boolean activateTool() {
    LinkedList<RendererElement> rendererElements = new LinkedList<RendererElement>();
    rendererElements.add(new RendererWireframeSelection(wireframeRendererUpdateLink));
    rendererElements.add(new RendererBoundaryField(boundaryFieldRendererUpdateLink));
    rendererElements.add(new RendererSolidSelection(solidSelectionRendererUpdateLink));
    rendererElements.add(new RenderCursorStatus(cursorRenderInfoUpdateLink));
    rendererElements.add(new RendererStatusMessage(statusMessageRenderInfoUpdateLink));
    speedyToolRenderers.setRenderers(rendererElements);
//    selectionPacketSender.reset();

    if (soundEffectBoundaryHum == null) {
      BoundaryHumLink boundaryHumLink = this.new BoundaryHumLink();
      soundEffectBoundaryHum = new SoundEffectBoundaryHum(SoundEffectNames.BOUNDARY_HUM, soundController, boundaryHumLink);
    }
    soundEffectBoundaryHum.startPlayingLoop();

    iAmActive = true;
    cloneToolsNetworkClient.changeClientStatus(ClientStatus.MONITORING_STATUS);
    toolState = ToolState.IDLE;
    return true;
  }

  /** The user has unequipped this tool:
   * (1) if generating a selection, stop
   * (2) ungrab if dragging a selection
   * (3) if currently performing any actions or communications with the server, terminate them
   * The tool will not deactivate itself until the server has acknowledged abort.
   * @return true if deactivation complete, false if not yet ready to deactivate
   */
  @Override
  public boolean deactivateTool() {
    clientVoxelSelection.abortGenerationIfUnderway();

    if (cloneToolsNetworkClient.peekCurrentActionStatus() != CloneToolsNetworkClient.ActionStatus.NONE_PENDING
        && cloneToolsNetworkClient.peekCurrentActionStatus() != CloneToolsNetworkClient.ActionStatus.COMPLETED  ) {
      undoAction();
    }
    if (this.iAmBusy()) return false;

    speedyToolRenderers.setRenderers(null);
    if (soundEffectBoundaryHum != null) {
      soundEffectBoundaryHum.stopPlaying();
    }
    iAmActive = false;
    cloneToolsNetworkClient.changeClientStatus(ClientStatus.IDLE);
    return true;
  }

  /**
   * the various valid user inputs are:
   * While performing an action (eg selection creation, or selection copy):
   * (1) any left click = abort action.
   * Otherwise:
   * If there is no selection:
   * (1) long left hold = undo previous copy (if any)
   * (2) short right click = create selection
   * If there is a selection:
   * (1) long left hold = undo previous copy (if any)
   * (2) short left click = uncreate selection
   * (3) short right click with CTRL = mirror selection left-right
   * (4) short right click without CTRL = grab / ungrab selection to move it around
   * (5) long right hold = copy the selection to the current position
   * (6) long left hold = undo the previous copy
   * (7) CTRL + mousewheel = rotate selection

   * @param player
   * @param partialTick
   * @param userInput
   * @return
   */

  @Override
  public boolean processUserInput(EntityClientPlayerMP player, float partialTick, UserInput userInput) {
    if (!iAmActive) return false;

    final long MIN_UNDO_HOLD_DURATION_NS = SpeedyToolsOptions.getLongClickMinDurationNS(); // length of time to hold for undo
    final long MIN_PLACE_HOLD_DURATION_NS = SpeedyToolsOptions.getLongClickMinDurationNS(); // length of time to hold for action (place)
    final long MAX_SHORT_CLICK_DURATION_NS = SpeedyToolsOptions.getShortClickMaxDurationNS();  // maximum length of time for a "short" click

    checkInvariants();

    UserInput.InputEvent nextEvent;
    while (null != (nextEvent = userInput.poll())) {
      // if we are busy with an action - a short left click will abort current action
      if (cloneToolsNetworkClient.peekCurrentActionStatus() != CloneToolsNetworkClient.ActionStatus.NONE_PENDING) {
        if (nextEvent.eventType == UserInput.InputEventType.LEFT_CLICK_DOWN) {
          if (cloneToolsNetworkClient.peekCurrentActionStatus() != CloneToolsNetworkClient.ActionStatus.NONE_PENDING
                  && cloneToolsNetworkClient.peekCurrentActionStatus() != CloneToolsNetworkClient.ActionStatus.COMPLETED) {
            undoAction();
          }
        }

         // if we are busy with an undo - we can't interrupt
      } else if (cloneToolsNetworkClient.peekCurrentUndoStatus() != CloneToolsNetworkClient.ActionStatus.NONE_PENDING) {
        // do nothing

        // otherwise - is this an undo?
      } else if (nextEvent.eventType == UserInput.InputEventType.LEFT_CLICK_UP &&
              nextEvent.eventDuration >= MIN_UNDO_HOLD_DURATION_NS
          ) {
        undoAction();

        // otherwise - split up according to whether we have a selection or not
      } else {
        switch (clientVoxelSelection.getReadinessForDisplaying()) {
          case NO_SELECTION: {
//            System.out.println("TEST:" + nextEvent.eventType + " : " + nextEvent.eventDuration);
            if (nextEvent.eventType == UserInput.InputEventType.RIGHT_CLICK_UP &&
                    nextEvent.eventDuration <= MAX_SHORT_CLICK_DURATION_NS) {
              initiateSelectionCreation(player);
            }
            break;
          }
          case GENERATING: {
            if (nextEvent.eventType == UserInput.InputEventType.LEFT_CLICK_DOWN) {
              undoSelectionCreation();
            }
            return false;
          }
          case READY_FOR_DISPLAY: {
            processSelectionUserInput(player, partialTick, nextEvent);
            break;
          }
          default:
            assert false : "Illegal clientVoxelSelection state: " + clientVoxelSelection.getReadinessForDisplaying();
        }
      }
    }

    // update the powerup for action or undo action
    // used for rendering display only; CLICK_UP is used for reacting to the event

    // undo action check
    long timeNow = System.nanoTime();
//    if (this.iAmBusy()) {
//      if (!leftClickPowerup.isIdle()) {
//        leftClickPowerup.abort();
//      }
//    } else {
      long leftButtonHoldTime = userInput.leftButtonHoldTimeNS(timeNow);

      if (leftButtonHoldTime >= MAX_SHORT_CLICK_DURATION_NS) {
        if (leftClickPowerup.isIdle()) {
          leftClickPowerup.initiate(timeNow, timeNow + MIN_UNDO_HOLD_DURATION_NS, leftButtonHoldTime);
        }
        leftClickPowerup.updateHolddownTime(leftButtonHoldTime);
      } else if (leftButtonHoldTime < 0) { // released button
        if (!leftClickPowerup.isIdle()) {
          long releaseTime = timeNow + leftButtonHoldTime;
          leftClickPowerup.release(releaseTime);
        }
      }
//    }

    if (clientVoxelSelection.getReadinessForDisplaying() != ClientVoxelSelection.VoxelSelectionState.READY_FOR_DISPLAY) {
      if (!rightClickPowerup.isIdle()) {
        rightClickPowerup.abort();
      }
    } else {
      long rightButtonHoldTime = userInput.rightButtonHoldTimeNS(timeNow);
//      System.out.print("SpeedyToolCopy rightButtonHoldTime= " + rightButtonHoldTime);

      if (rightButtonHoldTime >= MAX_SHORT_CLICK_DURATION_NS) {
//        System.out.print("hold time greater than MAX_SHORT_CLICK_DURATION_NS");
        if (rightClickPowerup.isIdle()) {
          rightClickPowerup.initiate(timeNow, timeNow + MIN_PLACE_HOLD_DURATION_NS, rightButtonHoldTime);
//          System.out.print("initiate rightClickPowerUp");
        }
        rightClickPowerup.updateHolddownTime(rightButtonHoldTime);
      } else if (rightButtonHoldTime < 0) { // released button
//        System.out.print("released ");
        if (!rightClickPowerup.isIdle()) {
          long releaseTime = timeNow + rightButtonHoldTime;
          rightClickPowerup.release(releaseTime);
//          System.out.print("Powerup release");
        }
      }
//      System.out.println();
    }

    return true;
  }

  protected abstract RenderCursorStatus.CursorRenderInfo.CursorType getCursorType();

  protected abstract Colour getSelectionRenderColour();

    /** Is the tool currently busy with something?
     * @return true if busy, false if not
     */
  private boolean iAmBusy()
  {
    if (clientVoxelSelection.getReadinessForDisplaying() == ClientVoxelSelection.VoxelSelectionState.GENERATING) {
      return true;
    }
    if (cloneToolsNetworkClient.peekCurrentActionStatus() != CloneToolsNetworkClient.ActionStatus.NONE_PENDING
        && cloneToolsNetworkClient.peekCurrentActionStatus() != CloneToolsNetworkClient.ActionStatus.COMPLETED) {
      return true;
    }
    if (cloneToolsNetworkClient.peekCurrentUndoStatus() != CloneToolsNetworkClient.ActionStatus.NONE_PENDING
            && cloneToolsNetworkClient.peekCurrentUndoStatus() != CloneToolsNetworkClient.ActionStatus.COMPLETED) {
      return true;
    }

    return false;
  }

  /** processes user input received while there is a solid selection
   * (1) short left click = uncreate selection
   * (2) short right click with CTRL = mirror selection left-right
   * (3) short right click without CTRL = grab / ungrab selection to move it around
   * (4) long right hold = copy the selection to the current position
   * (5) long left hold = undo the previous copy
   * (6) CTRL + mousewheel = rotate selection
   *
   * @param player
   * @param partialTick
   * @param inputEvent
   * @return
   */
  private boolean processSelectionUserInput(EntityClientPlayerMP player, float partialTick, UserInput.InputEvent inputEvent) {
    final long MIN_UNDO_HOLD_DURATION_NS = SpeedyToolsOptions.getLongClickMinDurationNS(); // length of time to hold for undo
    final long MIN_PLACE_HOLD_DURATION_NS = SpeedyToolsOptions.getLongClickMinDurationNS(); // length of time to hold for action (place)
    final long MAX_SHORT_CLICK_DURATION_NS = SpeedyToolsOptions.getShortClickMaxDurationNS();  // maximum length of time for a "short" click

    switch (inputEvent.eventType) {
      case LEFT_CLICK_UP: {
        if (inputEvent.eventDuration <= MAX_SHORT_CLICK_DURATION_NS) {
          undoSelectionCreation();
        } else if (inputEvent.eventDuration >= MIN_UNDO_HOLD_DURATION_NS) {
          undoAction();
        }
        break;
      }
      case RIGHT_CLICK_UP: {
        if (inputEvent.eventDuration <= MAX_SHORT_CLICK_DURATION_NS) {
          if (inputEvent.controlKeyDown) {
            flipSelection(player);
          } else { // toggle selection grabbing
            hasBeenMoved = true;
            Vec3 playerPosition = player.getPosition(partialTick);  // beware, Vec3 is short-lived
            selectionGrabActivated = !selectionGrabActivated;
            if (selectionGrabActivated) {
              selectionMovedFastYet = false;
              selectionGrabPoint = Vec3.createVectorHelper(playerPosition.xCoord, playerPosition.yCoord, playerPosition.zCoord);
            } else {
              Vec3 distanceMoved = selectionGrabPoint.subtract(playerPosition);
              selectionOrigin.posX += (int)Math.round(distanceMoved.xCoord);
              selectionOrigin.posY += (int)Math.round(distanceMoved.yCoord);
              selectionOrigin.posZ += (int)Math.round(distanceMoved.zCoord);
            }
          }
        } else if (inputEvent.eventDuration >= MIN_PLACE_HOLD_DURATION_NS) {
          placeSelection(player, partialTick);
        }
        break;
      }
      case WHEEL_MOVE: {
        rotateSelection(-inputEvent.count);  // wheel down rotates clockwise
        break;
      }
    }
    return true;
  }

  /**
   * (1) Selects the first Block that will be affected by the tool when the player presses right-click,
   * (2) Determines which algorithm will be used to select blocks (see below), and
   * (3) creates a wireframe highlight showing up to MAX_NUMBER_OF_HIGHLIGHTED_BLOCKS of the blocks that will be selected
   * 1) no boundary field: cursor pointing at a block - floodfill (all non-air blocks, including diagonal fill) from block clicked up
   * 2) boundary field: standing outside, cursor pointing at one of the boundaries - all solid blocks in the field
   * 3) boundary field: standing inside - cursor pointing at a block - floodfill to boundary field
   * So the selection algorithm is:
   * a) if the player is pointing at a block, specify that, and also check if inside a boundary field; else
   * b) check the player is pointing at a side of the boundary field (from outside)
   *
   * @param player the player
   * @param partialTick partial tick time.
   */
//  @Override
//  public void highlightBlocks(MovingObjectPosition target, EntityPlayer player, ItemStack currentItem, float partialTick)

  @Override
  public boolean updateForThisFrame(World world, EntityClientPlayerMP player, float partialTick)
  {
    checkInvariants();
    if (clientVoxelSelection.getReadinessForDisplaying() != ClientVoxelSelection.VoxelSelectionState.NO_SELECTION) return false;
    updateBoundaryCornersFromToolBoundary();

    MovingObjectPosition target = itemComplexBase.rayTraceLineOfSight(player.worldObj, player);

    final int MAX_NUMBER_OF_HIGHLIGHTED_BLOCKS = 64;
    blockUnderCursor = null;
    highlightedBlocks = null;
    currentHighlighting = SelectionType.NONE;

    if (target != null && target.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
      blockUnderCursor = new ChunkCoordinates(target.blockX, target.blockY, target.blockZ);
      boolean selectedBlockIsInsideBoundaryField = false;

      if (boundaryCorner1 != null && boundaryCorner2 != null) {
        if (   blockUnderCursor.posX >= boundaryCorner1.posX && blockUnderCursor.posX <= boundaryCorner2.posX
                && blockUnderCursor.posY >= boundaryCorner1.posY && blockUnderCursor.posY <= boundaryCorner2.posY
                && blockUnderCursor.posZ >= boundaryCorner1.posZ && blockUnderCursor.posZ <= boundaryCorner2.posZ ) {
          selectedBlockIsInsideBoundaryField = true;
        }
      }

      if (selectedBlockIsInsideBoundaryField) {
        currentHighlighting = SelectionType.BOUND_FILL;
        highlightedBlocks = selectFill(target, player.worldObj, MAX_NUMBER_OF_HIGHLIGHTED_BLOCKS, true, true,
                boundaryCorner1.posX, boundaryCorner2.posX,
                boundaryCorner1.posY, boundaryCorner2.posY,
                boundaryCorner1.posZ, boundaryCorner2.posZ);
      } else {
        currentHighlighting = SelectionType.UNBOUND_FILL;
        highlightedBlocks = selectFill(target, player.worldObj, MAX_NUMBER_OF_HIGHLIGHTED_BLOCKS, true, true,
                Integer.MIN_VALUE, Integer.MAX_VALUE,
                blockUnderCursor.posY, 255,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
      }
      checkInvariants();
      return true;
    }

    // if there is no block selected, check whether the player is pointing at the boundary field from outside.
    if (boundaryCorner1 == null || boundaryCorner2 == null) return false;
    Vec3 playerPosition = player.getPosition(1.0F);
    if (   playerPosition.xCoord >= boundaryCorner1.posX && playerPosition.xCoord <= boundaryCorner2.posX +1
            && playerPosition.yCoord >= boundaryCorner1.posY && playerPosition.yCoord <= boundaryCorner2.posY +1
            && playerPosition.zCoord >= boundaryCorner1.posZ && playerPosition.zCoord <= boundaryCorner2.posZ +1) {
      return false;
    }
    MovingObjectPosition highlightedFace = boundaryFieldFaceSelection(player);
    if (highlightedFace == null) return false;
    currentHighlighting = SelectionType.FULL_BOX;
    checkInvariants();
    return true;
  }

  @Override
  public void resetTool() {
    super.resetTool();
    clientVoxelSelection.reset();
  }

  /**
   * If there is a current selection, destroy it.  No effect if waiting for the server to do something.
   */
  private void undoSelectionCreation()
  {
    if (cloneToolsNetworkClient.peekCurrentActionStatus() != CloneToolsNetworkClient.ActionStatus.NONE_PENDING
        || cloneToolsNetworkClient.peekCurrentUndoStatus() != CloneToolsNetworkClient.ActionStatus.NONE_PENDING    ) {
      return;
    }
    clientVoxelSelection.reset();
  }

  /**
   * if the player has previously performed an action, undo it.
   */
  private void undoAction()
  {
    checkInvariants();
    if (cloneToolsNetworkClient.getServerStatus() != ServerStatus.IDLE
        && cloneToolsNetworkClient.getServerStatus() != ServerStatus.PERFORMING_YOUR_ACTION) {
      return;
    }

    if (toolState == ToolState.PERFORMING_ACTION) {
      toolState = ToolState.PERFORMING_UNDO_FROM_PARTIAL;
    } else {
      toolState = ToolState.PERFORMING_UNDO_FROM_FULL;
    }
    ResultWithReason result = cloneToolsNetworkClient.performComplexToolUndo();
    if (result.succeeded()) {
      cloneToolsNetworkClient.changeClientStatus(ClientStatus.WAITING_FOR_ACTION_COMPLETE);
    } else {
      displayNewErrorMessage(result.getReason());
    }
//        playSound(CustomSoundsHandler.BOUNDARY_UNPLACE, thePlayer);
  }

  /**
   * don't call if an action is already pending
   */
  private void placeSelection(EntityClientPlayerMP player, float partialTick)
  {
    checkInvariants();
    if (!clientVoxelSelection.isSelectionCompleteOnServer()) {
      displayNewErrorMessage("Must wait for spell preparation to finish ...");
      return;
    }

    Vec3 selectionPosition = getSelectionPosition(player, partialTick, false);
    ResultWithReason result = cloneToolsNetworkClient.performComplexToolAction(Item.getIdFromItem(itemComplexBase),
            Math.round((float) selectionPosition.xCoord),
            Math.round((float) selectionPosition.yCoord),
            Math.round((float) selectionPosition.zCoord),
            selectionOrientation);
    if (result.succeeded()) {
      cloneToolsNetworkClient.changeClientStatus(ClientStatus.WAITING_FOR_ACTION_COMPLETE);
      toolState = ToolState.PERFORMING_ACTION;
    } else {
      displayNewErrorMessage(result.getReason());
    }
  }

  private void flipSelection(EntityClientPlayerMP entityClientPlayerMP)
  {
    float modulusYaw =  MathHelper.wrapAngleTo180_float(entityClientPlayerMP.rotationYaw);

    if (Math.abs(modulusYaw) < 45 || Math.abs(modulusYaw) > 135) { // looking mostly north-south
      selectionOrientation.flipWX();
    } else {
      selectionOrientation.flipWZ();
    }
  }

  private void rotateSelection(int rotationCountAndDirection)
  {
    selectionOrientation.rotateClockwise(rotationCountAndDirection);
  }

  private void initiateSelectionCreation(EntityClientPlayerMP thePlayer)
  {
    selectionGrabActivated = false;
    switch (currentHighlighting) {
      case NONE: {
        if (updateBoundaryCornersFromToolBoundary()) {
          displayNewErrorMessage("First point your cursor at a nearby block, or at the boundary field ...");
        } else {
          displayNewErrorMessage("First point your cursor at a nearby block...");
        }
        return;
      }
      case FULL_BOX: {
        clientVoxelSelection.createFullBoxSelection(thePlayer, boundaryCorner1, boundaryCorner2);
        break;
      }
      case UNBOUND_FILL: {
        clientVoxelSelection.createUnboundFillSelection(thePlayer, blockUnderCursor);
        break;
      }
      case BOUND_FILL: {
        clientVoxelSelection.createBoundFillSelection(thePlayer, blockUnderCursor, boundaryCorner1, boundaryCorner2);
        break;
      }
    }

    cloneToolsNetworkClient.informSelectionMade();
  }

  /** called once per tick on the client side while the user is holding an ItemCloneTool
   * used to:
   * (1) background generation of a selection, if it has been initiated
   * (2) start transmission of the selection, if it has just been completed
   * (3) acknowledge (get) the action and undo statuses
   */
  @Override
  public void performTick(World world) {
    checkInvariants();
    super.performTick(world);
    updateGrabRenderTick(selectionGrabActivated && clientVoxelSelection.getReadinessForDisplaying() == ClientVoxelSelection.VoxelSelectionState.READY_FOR_DISPLAY);

    final long MAX_TIME_IN_NS = SpeedyToolsOptions.getMaxClientBusyTimeMS() * 1000L * 1000L;
    ClientVoxelSelection.VoxelSelectionState oldState = clientVoxelSelection.getReadinessForDisplaying();
    clientVoxelSelection.performTick(world, MAX_TIME_IN_NS);
    if (clientVoxelSelection.hasSelectionBeenUpdated()) {   // update the origin and orientation if the selection has been updated
      if (oldState != ClientVoxelSelection.VoxelSelectionState.READY_FOR_DISPLAY) {
        initialSelectionOrigin = clientVoxelSelection.getSourceWorldOrigin();
        initialSelectionOrientation = clientVoxelSelection.getSourceQuadOrientation();
        selectionOrigin = initialSelectionOrigin;
        selectionOrientation = initialSelectionOrientation;
      } else {                                                         // apply any user translations and orientation changes to the new values
        int dx = selectionOrigin.posX - initialSelectionOrigin.posX;
        int dy = selectionOrigin.posY - initialSelectionOrigin.posY;
        int dz = selectionOrigin.posZ - initialSelectionOrigin.posZ;
        ChunkCoordinates newOrigin = clientVoxelSelection.getSourceWorldOrigin();
        selectionOrigin = new ChunkCoordinates(newOrigin.posX + dx, newOrigin.posY + dy, newOrigin.posZ + dz);
        QuadOrientation newOrientation = clientVoxelSelection.getSourceQuadOrientation();
        if (initialSelectionOrientation.isFlippedX()) newOrientation.flipX();
        newOrientation.rotateClockwise(initialSelectionOrientation.getClockwiseRotationCount());
        selectionOrientation = newOrientation;
      }
    }

    CloneToolsNetworkClient.ActionStatus actionStatus = cloneToolsNetworkClient.getCurrentActionStatus();
    CloneToolsNetworkClient.ActionStatus undoStatus = cloneToolsNetworkClient.getCurrentUndoStatus();

    if (undoStatus == CloneToolsNetworkClient.ActionStatus.COMPLETED) {
      lastActionWasRejected = false;
      toolState = ToolState.UNDO_SUCCEEDED;
      cloneToolsNetworkClient.changeClientStatus(ClientStatus.MONITORING_STATUS);
    }
    if (undoStatus == CloneToolsNetworkClient.ActionStatus.REJECTED) {
      lastActionWasRejected = true;
      toolState = ToolState.UNDO_FAILED;
      displayNewErrorMessage(cloneToolsNetworkClient.getLastRejectionReason());
      cloneToolsNetworkClient.changeClientStatus(ClientStatus.MONITORING_STATUS);
    }

    if (undoStatus == CloneToolsNetworkClient.ActionStatus.NONE_PENDING) { // ignore action statuses if undo status is not idle, since we are undoing the current action
      if (actionStatus == CloneToolsNetworkClient.ActionStatus.COMPLETED) {
        lastActionWasRejected = false;
        toolState = ToolState.ACTION_SUCCEEDED;
        cloneToolsNetworkClient.changeClientStatus(ClientStatus.MONITORING_STATUS);
        hasBeenMoved = false;
      }
      if (actionStatus == CloneToolsNetworkClient.ActionStatus.REJECTED) {
        lastActionWasRejected = true;
        toolState = ToolState.ACTION_FAILED;
        displayNewErrorMessage(cloneToolsNetworkClient.getLastRejectionReason());
        cloneToolsNetworkClient.changeClientStatus(ClientStatus.MONITORING_STATUS);
      }
    }

    checkInvariants();
  }

  /**
   * start displaying a new error message
   * @param newErrorMessage
   */
  private void displayNewErrorMessage(String newErrorMessage)
  {
    if (newErrorMessage.isEmpty()) return;
    errorMessageDisplayTimeStartNS = System.nanoTime();
    errorMessageBeingDisplayed = newErrorMessage;
  }

  private String errorMessageBeingDisplayed = "";
  private long errorMessageDisplayTimeStartNS = 0;

  /**
   * copies the boundary field coordinates from the boundary tool, if a boundary is defined
   * @return true if a boundary field is defined, false otherwise
   */
  private boolean updateBoundaryCornersFromToolBoundary()
  {
    boundaryCorner1 = null;
    boundaryCorner2 = null;

    if (speedyToolBoundary == null) return false;
    ChunkCoordinates cnrMin = new ChunkCoordinates();
    ChunkCoordinates cnrMax = new ChunkCoordinates();

    boolean hasABoundaryField = speedyToolBoundary.copyBoundaryCorners(cnrMin, cnrMax);
    if (hasABoundaryField) {
      boundaryCorner1 = cnrMin;
      boundaryCorner2 = cnrMax;
    }
    return hasABoundaryField;
  }

  /**
   * This class is used to provide information to the Boundary Field Renderer when it needs it.
   * The information is taken from the reference to the SpeedyToolBoundary.
   */
  public class BoundaryFieldRendererUpdateLink implements RendererBoundaryField.BoundaryFieldRenderInfoUpdateLink
  {
    @Override
    public boolean refreshRenderInfo(RendererBoundaryField.BoundaryFieldRenderInfo infoToUpdate, Vec3 playerPosition)
    {
      ToolSelectionStates currentToolSelectionState = ToolSelectionStates.getState(clientVoxelSelection.getReadinessForDisplaying());
      if (!currentToolSelectionState.displayBoundaryField) return false;
      if (speedyToolBoundary == null) return false;
      AxisAlignedBB boundaryFieldAABB = speedyToolBoundary.getBoundaryField();
      if (boundaryFieldAABB == null) return false;
      infoToUpdate.boundaryCursorSide = (currentHighlighting == SelectionType.FULL_BOX) ? UsefulConstants.FACE_ALL : UsefulConstants.FACE_NONE;
      infoToUpdate.boundaryGrabActivated = false;
      infoToUpdate.boundaryGrabSide = -1;
      infoToUpdate.boundaryFieldAABB = boundaryFieldAABB;
      return true;
    }
  }

  /**
   * This class is used to provide information to the WireFrame Renderer for rendering the wireframe
   *   of highlighted blocks.
   * The Renderer calls refreshRenderInfo, which copies the relevant information from the tool.
   * Draws a wireframe selection, provided that not both of the corners have been placed yet
   */
  public class CopyToolWireframeRendererLink implements RendererWireframeSelection.WireframeRenderInfoUpdateLink
  {
    @Override
    public boolean refreshRenderInfo(RendererWireframeSelection.WireframeRenderInfo infoToUpdate)
    {
      ToolSelectionStates currentToolSelectionState = ToolSelectionStates.getState(clientVoxelSelection.getReadinessForDisplaying());
      if (   !currentToolSelectionState.displayWireframeHighlight
          || highlightedBlocks == null) {
        return false;
      }
      infoToUpdate.currentlySelectedBlocks = highlightedBlocks;
      return true;
    }
  }

  /**
   * This class passes the needed information for the rendering of the solid selection:
   *  - the voxelManager
   *  - the coordinates of the selectionOrigin after it has been dragged from its starting point
   */
  public class SolidSelectionRendererLink implements RendererSolidSelection.SolidSelectionRenderInfoUpdateLink
  {
    @Override
    public boolean refreshRenderInfo(RendererSolidSelection.SolidSelectionRenderInfo infoToUpdate, EntityPlayer player, float partialTick) {
      ToolSelectionStates currentToolSelectionState = ToolSelectionStates.getState(clientVoxelSelection.getReadinessForDisplaying());
      if (!currentToolSelectionState.displaySolidSelection) return false;
      final double THRESHOLD_SPEED_SQUARED_FOR_SNAP_GRID = 0.01;
      checkInvariants();

      double currentSpeedSquared = player.motionX * player.motionX + player.motionY * player.motionY + player.motionZ * player.motionZ;
      if (currentSpeedSquared >= THRESHOLD_SPEED_SQUARED_FOR_SNAP_GRID) {
        selectionMovedFastYet = true;
      }
      final boolean snapToGridWhileMoving = selectionMovedFastYet && currentSpeedSquared <= THRESHOLD_SPEED_SQUARED_FOR_SNAP_GRID;
      Vec3 selectionPosition = getSelectionPosition(player, partialTick, snapToGridWhileMoving);
      infoToUpdate.selectorRenderer = clientVoxelSelection.getVoxelSelectionRenderer();
      infoToUpdate.draggedSelectionOriginX = selectionPosition.xCoord;
      infoToUpdate.draggedSelectionOriginY = selectionPosition.yCoord;
      infoToUpdate.draggedSelectionOriginZ = selectionPosition.zCoord;
      infoToUpdate.opaque = hasBeenMoved;
      infoToUpdate.renderColour = SpeedyToolComplex.this.getSelectionRenderColour();
      infoToUpdate.selectionOrientation = selectionOrientation;
      return true;
    }
  }

  /** find the closest part of the boundary field (the epicentre), calculate the distance to it.
   *
   */
  private class BoundaryHumLink implements SoundEffectBoundaryHum.BoundaryHumUpdateLink
  {

    @Override
    public boolean refreshHumInfo(SoundEffectBoundaryHum.BoundaryHumInfo infoToUpdate) {
      EntityClientPlayerMP entityClientPlayerMP = Minecraft.getMinecraft().thePlayer;

      ToolSelectionStates currentToolSelectionState = ToolSelectionStates.getState(clientVoxelSelection.getReadinessForDisplaying());
      if (!currentToolSelectionState.displayBoundaryField) return false;
      if (speedyToolBoundary == null) return false;
      AxisAlignedBB boundaryFieldAABB = speedyToolBoundary.getBoundaryField();
      if (boundaryFieldAABB == null) return false;

      Vec3 playerPosition = entityClientPlayerMP.getPosition(0);
      Vec3 epicentre = Vec3.createVectorHelper((boundaryFieldAABB.minX + boundaryFieldAABB.maxX) / 2.0,
              (boundaryFieldAABB.minY + boundaryFieldAABB.maxY) / 2.0,
              (boundaryFieldAABB.minZ + boundaryFieldAABB.maxZ) / 2.0);
      MovingObjectPosition mop = boundaryFieldAABB.calculateIntercept(playerPosition, epicentre);

      if (mop == null) {        // full volume when inside field
        infoToUpdate.soundEpicentre = playerPosition;
        infoToUpdate.distanceToEpicentre = 0;
        return true;
      }
      infoToUpdate.soundEpicentre = mop.hitVec;
      infoToUpdate.distanceToEpicentre = (float)playerPosition.distanceTo(infoToUpdate.soundEpicentre);

      return true;
    }
  }

  /**
   * returns the current position of the selection, i.e. the corner where it will be placed if the user performs an action
   * @param snapToGrid if true, snap to the nearest integer coordinates
   * @return
   */
  private Vec3 getSelectionPosition(EntityPlayer player, float partialTick, boolean snapToGrid)
  {
    Vec3 playerOrigin = player.getPosition(partialTick);

    double draggedSelectionOriginX = selectionOrigin.posX;
    double draggedSelectionOriginY = selectionOrigin.posY;
    double draggedSelectionOriginZ = selectionOrigin.posZ;
    if (selectionGrabActivated) {
      Vec3 distanceMoved = selectionGrabPoint.subtract(playerOrigin);
      draggedSelectionOriginX += distanceMoved.xCoord;
      draggedSelectionOriginY += distanceMoved.yCoord;
      draggedSelectionOriginZ += distanceMoved.zCoord;
      if (snapToGrid) {
        draggedSelectionOriginX = Math.round(draggedSelectionOriginX);
        draggedSelectionOriginY = Math.round(draggedSelectionOriginY);
        draggedSelectionOriginZ = Math.round(draggedSelectionOriginZ);
      }
    }
    return Vec3.createVectorHelper(draggedSelectionOriginX, draggedSelectionOriginY, draggedSelectionOriginZ);
  }

  /**
   * This class is used to provide information to the Cursor Status indicator when it needs it:
   * The Renderer calls refreshRenderInfo, which copies the relevant information from the tool.
   */
  public class CursorRenderInfoLink implements RenderCursorStatus.CursorRenderInfoUpdateLink
  {
    @Override
    public boolean refreshRenderInfo(RenderCursorStatus.CursorRenderInfo infoToUpdate)
    {
      infoToUpdate.animationState = RenderCursorStatus.CursorRenderInfo.AnimationState.IDLE;
      if (clientVoxelSelection.getReadinessForDisplaying() == ClientVoxelSelection.VoxelSelectionState.GENERATING) {
        infoToUpdate.vanillaCursorSpin = true;
        infoToUpdate.cursorSpinProgress = 100.0F * clientVoxelSelection.getLocalGenerationFractionComplete();
      } else {
        infoToUpdate.vanillaCursorSpin = false;
      }
      infoToUpdate.aSelectionIsDefined = (clientVoxelSelection.getReadinessForDisplaying() == ClientVoxelSelection.VoxelSelectionState.READY_FOR_DISPLAY);

      PowerUpEffect activePowerUp;
      if (!leftClickPowerup.isIdle()) {
        activePowerUp = leftClickPowerup;
      } else {
        activePowerUp = rightClickPowerup;
      }
//      if (cloneToolsNetworkClient.peekCurrentActionStatus() == CloneToolsNetworkClient.ActionStatus.NONE_PENDING
//          && cloneToolsNetworkClient.peekCurrentUndoStatus() == CloneToolsNetworkClient.ActionStatus.NONE_PENDING ) {
//        toolState = ToolState.IDLE;
//      }
      if (!activePowerUp.isIdle()) {
        lastPowerupStarted = activePowerUp;
      }

      switch (toolState) {
        case ACTION_FAILED:
        case ACTION_SUCCEEDED:
        case UNDO_FAILED:
        case UNDO_SUCCEEDED:
        case IDLE: {
          if (!rightClickPowerup.isIdle()) {
            infoToUpdate.animationState = RenderCursorStatus.CursorRenderInfo.AnimationState.SPIN_UP_CW;
          } else if (!leftClickPowerup.isIdle()) {
            infoToUpdate.animationState = RenderCursorStatus.CursorRenderInfo.AnimationState.SPIN_UP_CCW;
          } else {
            if (lastPowerupStarted != null && lastPowerupStarted.isIdle()) {
              infoToUpdate.animationState = lastPowerupStarted == rightClickPowerup ?  RenderCursorStatus.CursorRenderInfo.AnimationState.SPIN_DOWN_CW_CANCELLED
                                                                                    : RenderCursorStatus.CursorRenderInfo.AnimationState.SPIN_DOWN_CCW_CANCELLED;
            } else {
              switch (toolState) {
                case ACTION_FAILED: {
                  infoToUpdate.animationState = RenderCursorStatus.CursorRenderInfo.AnimationState.SPIN_DOWN_CW_CANCELLED;
                  break;
                }
                case ACTION_SUCCEEDED: {
                  infoToUpdate.animationState = RenderCursorStatus.CursorRenderInfo.AnimationState.SPIN_DOWN_CW_SUCCESS;
                  break;
                }
                case UNDO_FAILED: {
                  infoToUpdate.animationState = RenderCursorStatus.CursorRenderInfo.AnimationState.SPIN_DOWN_CCW_CANCELLED;
                  break;
                }
                case UNDO_SUCCEEDED: {
                  infoToUpdate.animationState = RenderCursorStatus.CursorRenderInfo.AnimationState.SPIN_DOWN_CCW_SUCCESS;
                  break;
                }
              }
            }
          }
          break;
        }
        case PERFORMING_ACTION: {
          infoToUpdate.animationState = RenderCursorStatus.CursorRenderInfo.AnimationState.SPINNING_CW;
          lastPowerupStarted = null;
          break;
        }
        case PERFORMING_UNDO_FROM_FULL: {
          infoToUpdate.animationState = RenderCursorStatus.CursorRenderInfo.AnimationState.SPINNING_CCW_FROM_FULL;
          lastPowerupStarted = null;
          break;
        }
        case PERFORMING_UNDO_FROM_PARTIAL: {
          infoToUpdate.animationState = RenderCursorStatus.CursorRenderInfo.AnimationState.SPINNING_CCW_FROM_PARTIAL;
          lastPowerupStarted = null;
          break;
        }
        default: {
          assert false : "Invalid toolstate = " + toolState + " in refreshRenderInfo()";
        }
      }


//      if (cloneToolsNetworkClient.peekCurrentActionStatus() != CloneToolsNetworkClient.ActionStatus.NONE_PENDING) {
//        performingTask = true;
//        infoToUpdate.
//      } else if (cloneToolsNetworkClient.peekCurrentUndoStatus() != CloneToolsNetworkClient.ActionStatus.NONE_PENDING) {
//        infoToUpdate.performingTask = true;
//        infoToUpdate.clockwise = false;
//      } else {
//        infoToUpdate.performingTask = false;
//        infoToUpdate.idle = activePowerUp.isIdle();
//      }

//      System.out.println("ServerStatus:" + cloneToolsNetworkClient.getServerStatus() + ", " + cloneToolsNetworkClient.getServerPercentComplete());
      boolean serverIsIdle = cloneToolsNetworkClient.getServerStatus() == ServerStatus.IDLE;
      boolean selectionReadyOnServer = clientVoxelSelection.isSelectionCompleteOnServer();
      if (!leftClickPowerup.isIdle()) selectionReadyOnServer = true;   // undo doesn't need server selection

      float serverReadiness = serverIsIdle ? 100 : cloneToolsNetworkClient.getServerPercentComplete();
      float serverSelectionReadiness =   selectionReadyOnServer ? 100.0F : clientVoxelSelection.getServerSelectionFractionComplete();

      infoToUpdate.fullyChargedAndReady = (!activePowerUp.isIdle() && activePowerUp.getPercentCompleted() >= 99.999
                                           && serverIsIdle
                                           && selectionReadyOnServer );
      infoToUpdate.chargePercent = (float)activePowerUp.getPercentCompleted();
      infoToUpdate.readinessPercent = Math.min(serverReadiness, serverSelectionReadiness);
      infoToUpdate.cursorType = SpeedyToolComplex.this.getCursorType();
      infoToUpdate.taskCompletionPercent = cloneToolsNetworkClient.getServerPercentComplete();

//      System.out.println("CurserRenderInfoLink - refresh.  readinessPercent=" + infoToUpdate.readinessPercent +
//                         "; taskCompletionPercent=" + infoToUpdate.taskCompletionPercent +
//                         "; chargePercent= " + infoToUpdate.chargePercent +
//                         "; chargedAndReady=" + infoToUpdate.fullyChargedAndReady
//                        );
      if (infoToUpdate.animationState != lastState) {
//        System.out.println("State:" + infoToUpdate.animationState);
        lastState = infoToUpdate.animationState;
      }
      return true;
    }
    private RenderCursorStatus.CursorRenderInfo.AnimationState lastState;
  }

  public class StatusMessageRenderInfoLink implements RendererStatusMessage.StatusMessageRenderInfoUpdateLink
  {
    @Override
    public boolean refreshRenderInfo(RendererStatusMessage.StatusMessageRenderInfo infoToUpdate) {
      long timeMessageHasBeenDisplayed =  System.nanoTime() - errorMessageDisplayTimeStartNS;
      if (timeMessageHasBeenDisplayed <= SpeedyToolsOptions.getErrorMessageDisplayDurationNS()) {
        infoToUpdate.messageToDisplay = errorMessageBeingDisplayed;
      } else {
        infoToUpdate.messageToDisplay = "";
      }
      return true;
    }
  }

  private ItemComplexBase itemComplexBase;

  private void checkInvariants()
  {
//    assert (    currentToolSelectionState != ToolSelectionStates.GENERATING_SELECTION || voxelSelectionManager != null);
//    assert (   currentToolSelectionState != ToolSelectionStates.DISPLAYING_SELECTION
//            || (voxelSelectionManager != null && selectionOrigin != null) );

    assert (    selectionGrabActivated == false || selectionGrabPoint != null);
  }


  // the Tool Selection can be in several states as given by currentToolState:
  // 1) NO_SELECTION
  //    highlightBlocks() is used to update some variables, based on what the player is looking at
  //      a) highlightedBlocks (block wire outline)
  //      b) currentHighlighting (what type of highlighting depending on whether there is a boundary field, whether
  //         the player is looking at a block or at a side of the boundary field
  //      c) blockUnderCursor (if looking at a block)
  //      d) boundaryCursorSide (if looking at the boundary field)
  //    voxelSelectionManager is not valid
  // 2) GENERATING_SELECTION - user has clicked to generate a selection
  //    a) actionInProgress gives the action being performed
  //    b) voxelSelectionManager has been created and initialised
  //    c) every tick, the voxelSelectionManager is updated further until complete
  // 3) DISPLAYING_SELECTION - selection is being displayed and/or moved
  //    voxelSelectionManager is valid and has a renderlist
  // combined with this are the various other actions that the tool can perform:
  //  1) transmitting selection to server
  //  2) performing an action / waiting for server
  //  3) performing an undo / waiting for server

  private SelectionType currentHighlighting = SelectionType.NONE;
  private List<ChunkCoordinates> highlightedBlocks;

//  private float selectionGenerationPercentComplete;
  private boolean lastActionWasRejected;
//  private ToolSelectionStates currentToolSelectionState = ToolSelectionStates.NO_SELECTION;

//  private BlockVoxelMultiSelector voxelSelectionManager;
//  private BlockVoxelMultiSelectorRenderer voxelSelectionRenderer;
  private ChunkCoordinates selectionOrigin;
  private boolean selectionGrabActivated = false;
  private Vec3    selectionGrabPoint = null;
  private boolean selectionMovedFastYet;
  private boolean hasBeenMoved;               // used to change the appearance when freshed created or placed.
  private QuadOrientation selectionOrientation;
  ChunkCoordinates initialSelectionOrigin;
  QuadOrientation initialSelectionOrientation;

  private ClientVoxelSelection clientVoxelSelection;
  private CloneToolsNetworkClient cloneToolsNetworkClient;
//  private SelectionPacketSender selectionPacketSender;

  private SpeedyToolBoundary speedyToolBoundary;   // used to retrieve the boundary field coordinates, if selected

  private enum SelectionType {
    NONE, FULL_BOX, BOUND_FILL, UNBOUND_FILL
  }

  // logic table used to determine which renderer parts to display
  private enum ToolSelectionStates  {
            NO_SELECTION(ClientVoxelSelection.VoxelSelectionState.NO_SELECTION,       true, false,  true, false),
    GENERATING_SELECTION(ClientVoxelSelection.VoxelSelectionState.GENERATING,        false, false,  true,  true),
    DISPLAYING_SELECTION(ClientVoxelSelection.VoxelSelectionState.READY_FOR_DISPLAY, false,  true, false, false),
    ;

    public final boolean displayWireframeHighlight;
    public final boolean        displaySolidSelection;
    public final boolean               displayBoundaryField;
    public final boolean                      performingAction;
    public final ClientVoxelSelection.VoxelSelectionState voxelSelectionState;

    // returns the ToolSelectionState corresponding to a ClientVoxelSelection state
    public static ToolSelectionStates getState(ClientVoxelSelection.VoxelSelectionState stateToMatch)
    {
      for (ToolSelectionStates toolSelectionStates : ToolSelectionStates.values()) {
        if (toolSelectionStates.voxelSelectionState == stateToMatch) return toolSelectionStates;
      }
      assert false : "No matching ToolSelectionStates for VoxelSelectionState:" + stateToMatch;
      return null;
    }

    private ToolSelectionStates(ClientVoxelSelection.VoxelSelectionState i_voxelSelectionState, boolean init_displayHighlight, boolean init_displaySelection, boolean init_displayBoundaryField, boolean init_performingAction)
    {
      voxelSelectionState = i_voxelSelectionState;
      displayWireframeHighlight = init_displayHighlight;
      displaySolidSelection = init_displaySelection;
      displayBoundaryField = init_displayBoundaryField;
      performingAction = init_performingAction;
    }
  }
//  private enum SelectionGenerationState {IDLE, VOXELS, RENDERLISTS};
//  SelectionGenerationState selectionGenerationState = SelectionGenerationState.IDLE;
//  private float voxelCompletionReached;

  private RendererSolidSelection.SolidSelectionRenderInfoUpdateLink solidSelectionRendererUpdateLink;
  private RenderCursorStatus.CursorRenderInfoUpdateLink cursorRenderInfoUpdateLink;
  private RendererStatusMessage.StatusMessageRenderInfoUpdateLink statusMessageRenderInfoUpdateLink;

  private enum ToolState {
    IDLE, PERFORMING_ACTION, PERFORMING_UNDO_FROM_FULL, PERFORMING_UNDO_FROM_PARTIAL, ACTION_SUCCEEDED, ACTION_FAILED, UNDO_SUCCEEDED, UNDO_FAILED
  }
  private ToolState toolState;

  private PowerUpEffect leftClickPowerup = new PowerUpEffect();
  private PowerUpEffect rightClickPowerup = new PowerUpEffect();
  private PowerUpEffect lastPowerupStarted = null;  // points to the last powerup which was started (to detect when it has been released)

  private SoundEffectBoundaryHum soundEffectBoundaryHum;

}
