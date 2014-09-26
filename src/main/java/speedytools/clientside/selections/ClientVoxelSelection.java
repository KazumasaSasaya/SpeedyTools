package speedytools.clientside.selections;

import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import speedytools.clientside.network.CloneToolsNetworkClient;
import speedytools.clientside.network.PacketSenderClient;
import speedytools.clientside.tools.SelectionPacketSender;
import speedytools.common.SpeedyToolsOptions;
import speedytools.common.network.ClientStatus;
import speedytools.common.network.Packet250ServerSelectionGeneration;
import speedytools.common.selections.BlockVoxelMultiSelector;
import speedytools.common.utilities.ErrorLog;
import speedytools.common.utilities.QuadOrientation;
import speedytools.common.utilities.ResultWithReason;

/**
 * Created by TheGreyGhost on 26/09/14.
 * Used by the client to track the current tool voxel selection and coordinate any updates with the server
 */
public class ClientVoxelSelection
{
  public ClientVoxelSelection(PacketSenderClient i_packetSenderClient)
  {
    packetSenderClient = i_packetSenderClient;
  }

  private PacketSenderClient packetSenderClient;

  private enum ClientSelectionState {IDLE, GENERATING, CREATING_RENDERLISTS, COMPLETE}
  private enum OutgoingTransmissionState {IDLE, SENDING, COMPLETE}
  private enum ServerSelectionState {IDLE, GENERATING, RECEIVING, CREATING_RENDERLISTS, COMPLETE}

  public enum VoxelSelectionState {NO_SELECTION, GENERATING, READY_FOR_DISPLAY}

  private ClientSelectionState clientSelectionState = ClientSelectionState.IDLE;
  private OutgoingTransmissionState outgoingTransmissionState = OutgoingTransmissionState.IDLE;
  private ServerSelectionState serverSelectionState = ServerSelectionState.IDLE;



  /**
   * find out whether the voxelselection is ready for display yet
   * @return
   */
  public VoxelSelectionState getReadinessForDisplaying()
  {
    assert testInvariants();
    switch (clientSelectionState) {
      case IDLE: return VoxelSelectionState.NO_SELECTION;
      case COMPLETE: return VoxelSelectionState.READY_FOR_DISPLAY;
      case GENERATING:
      case CREATING_RENDERLISTS: {
        return VoxelSelectionState.GENERATING;
      }
      default: {
        ErrorLog.defaultLog().severe("Invalid clientSelectionState " + clientSelectionState + " in " + this.getClass().getName());
        return VoxelSelectionState.NO_SELECTION;
      }
    }
  }

  /**
   * If the voxel selection is currently being generated, return the fraction complete
   * @return [0 .. 1] for the estimated fractional completion of the voxel selection generation
   */
  public float getGenerationFractionComplete()
  {

  }

  private boolean testInvariants()
  {
    assert !(clientSelectionState == ClientSelectionState.IDLE && serverSelectionState != ServerSelectionState.IDLE);
  }

  /**
   * If there is a current selection, destroy it.  No effect if waiting for the server to do something.
   */
  public void abortSelectionCreation()
  {
    clientVoxelSelection = null;
    if (voxelSelectionRenderer != null) {
      voxelSelectionRenderer.release();
      voxelSelectionRenderer = null;
    }
    if (serverSelectionState != ServerSelectionState.IDLE) {
      Packet250ServerSelectionGeneration abortPacket = Packet250ServerSelectionGeneration.abortSelectionGeneration();
      packetSenderClient.sendPacket(abortPacket);
      serverSelectionState = ServerSelectionState.IDLE;
    }

    //todo abort any selection transmission to the server
    outgoingTransmissionState = OutgoingTransmissionState.IDLE;

  }

  public ResultWithReason createFullBoxSelection(EntityClientPlayerMP thePlayer, ChunkCoordinates boundaryCorner1, ChunkCoordinates boundaryCorner2)
  {

  }

  public ResultWithReason createFillSelection(EntityClientPlayerMP thePlayer, ChunkCoordinates fillStartingBlock, ChunkCoordinates boundaryCorner1, ChunkCoordinates boundaryCorner2)
  {

  }

  private void initiateSelectionCreation(EntityClientPlayerMP thePlayer, )
  {       // todo blocks which aren't loaded are empty!
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
        clientVoxelSelection = new BlockVoxelMultiSelector();
        clientVoxelSelection.selectAllInBoxStart(thePlayer.worldObj, boundaryCorner1, boundaryCorner2);
//        selectionOrigin = new ChunkCoordinates(boundaryCorner1);
        currentToolSelectionState = ToolSelectionStates.GENERATING_SELECTION;
        selectionGenerationState = SelectionGenerationState.VOXELS;
        selectionGenerationPercentComplete = 0;
        break;
      }
      case UNBOUND_FILL: {
        clientVoxelSelection = new BlockVoxelMultiSelector();
        clientVoxelSelection.selectUnboundFillStart(thePlayer.worldObj, blockUnderCursor);
//        selectionOrigin = new ChunkCoordinates(blockUnderCursor);
        currentToolSelectionState = ToolSelectionStates.GENERATING_SELECTION;
        selectionGenerationState = SelectionGenerationState.VOXELS;
        selectionGenerationPercentComplete = 0;
        break;
      }
      case BOUND_FILL: {
        clientVoxelSelection = new BlockVoxelMultiSelector();
        clientVoxelSelection.selectBoundFillStart(thePlayer.worldObj, blockUnderCursor, boundaryCorner1, boundaryCorner2);
//        selectionOrigin = new ChunkCoordinates(blockUnderCursor);
        currentToolSelectionState = ToolSelectionStates.GENERATING_SELECTION;
        selectionGenerationState = SelectionGenerationState.VOXELS;
        selectionGenerationPercentComplete = 0;
        break;
      }
    }
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
    updateGrabRenderTick(selectionGrabActivated && currentToolSelectionState == ToolSelectionStates.DISPLAYING_SELECTION);

    final long MAX_TIME_IN_NS = SpeedyToolsOptions.getMaxClientBusyTimeMS() * 1000L * 1000L;
    final float VOXEL_MAX_COMPLETION = 75.0F;
    if (currentToolSelectionState == ToolSelectionStates.GENERATING_SELECTION) {
      switch (selectionGenerationState) {
        case VOXELS: {
          float progress = clientVoxelSelection.continueSelectionGeneration(world, MAX_TIME_IN_NS);
          if (progress >= 0) {
            selectionGenerationPercentComplete = VOXEL_MAX_COMPLETION * progress;
          } else {
            voxelCompletionReached = selectionGenerationPercentComplete;
            selectionGenerationState = SelectionGenerationState.RENDERLISTS;
            selectionPacketSender.reset();
            if (clientVoxelSelection.isEmpty()) {
              currentToolSelectionState = ToolSelectionStates.NO_SELECTION;
            } else {
              selectionOrigin = clientVoxelSelection.getWorldOrigin();
              if (voxelSelectionRenderer == null) {
                voxelSelectionRenderer = new BlockVoxelMultiSelectorRenderer();
              }
              ChunkCoordinates wOrigin = clientVoxelSelection.getWorldOrigin();
              voxelSelectionRenderer.createRenderListStart(world, wOrigin.posX, wOrigin.posY, wOrigin.posZ,
                      clientVoxelSelection.getSelection(), clientVoxelSelection.getUnavailableVoxels());
            }
          }
          break;
        }
        case RENDERLISTS: {
          ChunkCoordinates wOrigin = clientVoxelSelection.getWorldOrigin();
          float progress = voxelSelectionRenderer.createRenderListContinue(world, wOrigin.posX, wOrigin.posY, wOrigin.posZ,
                  clientVoxelSelection.getSelection(), clientVoxelSelection.getUnavailableVoxels(), MAX_TIME_IN_NS);

          if (progress >= 0) {
            selectionGenerationPercentComplete = voxelCompletionReached + (100.0F - voxelCompletionReached) * progress;
          } else {
            currentToolSelectionState = ToolSelectionStates.DISPLAYING_SELECTION;
            selectionGenerationState = SelectionGenerationState.IDLE;
            selectionOrientation = new QuadOrientation(0, 0, clientVoxelSelection.getSelection().getxSize(), clientVoxelSelection.getSelection().getzSize());
            hasBeenMoved = false;
            selectionGrabActivated = false;
          }
          break;
        }
        default: assert false : "Invalid selectionGenerationState:" + selectionGenerationState;
      }
    }

    if (currentToolSelectionState == ToolSelectionStates.NO_SELECTION) {
      selectionPacketSender.reset();
    }

    // if the selection has been freshly generated, keep trying to transmit it until we successfully start transmission
    if (currentToolSelectionState == ToolSelectionStates.DISPLAYING_SELECTION
            && selectionPacketSender.getCurrentPacketProgress() == SelectionPacketSender.PacketProgress.IDLE) {
      selectionPacketSender.startSendingSelection(clientVoxelSelection);
    }
    selectionPacketSender.tick();

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


  private enum SelectionGenerationState {IDLE, VOXELS, RENDERLISTS};
  SelectionGenerationState selectionGenerationState = SelectionGenerationState.IDLE;


  private enum SelectionType {
    NONE, FULL_BOX, BOUND_FILL, UNBOUND_FILL
  }

  private enum ToolSelectionStates  {
    NO_SELECTION( true, false,  true, false),
    GENERATING_SELECTION(false, false,  true,  true),
    DISPLAYING_SELECTION(false,  true, false, false),
    ;

    public final boolean displayWireframeHighlight;
    public final boolean        displaySolidSelection;
    public final boolean               displayBoundaryField;
    public final boolean                      performingAction;

    private ToolSelectionStates(boolean init_displayHighlight, boolean init_displaySelection, boolean init_displayBoundaryField, boolean init_performingAction)
    {
      displayWireframeHighlight = init_displayHighlight;
      displaySolidSelection = init_displaySelection;
      displayBoundaryField = init_displayBoundaryField;
      performingAction = init_performingAction;
    }
  }

  private SelectionPacketSender selectionPacketSender;

  private BlockVoxelMultiSelector clientVoxelSelection;
  private BlockVoxelMultiSelectorRenderer voxelSelectionRenderer;

}
