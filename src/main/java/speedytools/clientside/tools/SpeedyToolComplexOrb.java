package speedytools.clientside.tools;

import speedytools.clientside.UndoManagerClient;
import speedytools.clientside.network.CloneToolsNetworkClient;
import speedytools.clientside.network.PacketSenderClient;
import speedytools.clientside.rendering.RenderCursorStatus;
import speedytools.clientside.rendering.SpeedyToolRenderers;
import speedytools.clientside.selections.ClientVoxelSelection;
import speedytools.clientside.sound.SoundController;
import speedytools.common.items.ItemSpeedyTool;
import speedytools.common.utilities.Colour;

/**
* User: The Grey Ghost
* Date: 8/08/2014
*/
public class SpeedyToolComplexOrb extends SpeedyToolComplex
{
  public SpeedyToolComplexOrb(ItemSpeedyTool i_parentItem, SpeedyToolRenderers i_renderers, SoundController i_speedyToolSounds, UndoManagerClient i_undoManagerClient, CloneToolsNetworkClient i_cloneToolsNetworkClient,
                              SpeedyToolBoundary i_speedyToolBoundary, ClientVoxelSelection i_clientVoxelSelection, CommonSelectionState i_commonSelectionState,
                              SelectionPacketSender packetSender, PacketSenderClient i_packetSenderClient) {
    super(i_parentItem, i_renderers, i_speedyToolSounds, i_undoManagerClient, i_cloneToolsNetworkClient, i_speedyToolBoundary,
          i_clientVoxelSelection, i_commonSelectionState, packetSender, i_packetSenderClient);
  }

  @Override
  public RenderCursorStatus.CursorRenderInfo.CursorType getCursorType() {
    return RenderCursorStatus.CursorRenderInfo.CursorType.REPLACE;
  }

  @Override
  protected Colour getSelectionRenderColour() {
    return Colour.WHITE_40;
  }

  @Override
  protected boolean cancelSelectionAfterAction() {
    return true;
  }

  /**
   * if true, selections made using this tool can be dragged around
   *
   * @return
   */
  @Override
  protected boolean selectionIsMoveable() {
    return false;
  }

  /**
   * if true, CTRL + mousewheel changes the item count
   *
   * @return
   */
  @Override
  protected boolean  mouseWheelChangesCount() {
    return true;
  }

//  @Override
//  public boolean updateForThisFrame(World world, EntityClientPlayerMP player, float partialTick)
//  {
////    checkInvariants();
//    if (clientVoxelSelection.getReadinessForDisplaying() != ClientVoxelSelection.VoxelSelectionState.NO_SELECTION) return false;
//    updateBoundaryCornersFromToolBoundary();
//
//    MovingObjectPosition target = itemComplexBase.rayTraceLineOfSight(player.worldObj, player);
//

//    final int MAX_NUMBER_OF_HIGHLIGHTED_BLOCKS = 64;
//    blockUnderCursor = null;
//    highlightedBlocks = null;
//    currentHighlighting = SelectionType.NONE;
//
//    if (target != null && target.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
//      blockUnderCursor = new ChunkCoordinates(target.blockX, target.blockY, target.blockZ);
//      boolean selectedBlockIsInsideBoundaryField = false;
//
//      if (boundaryCorner1 != null && boundaryCorner2 != null) {
//        if (   blockUnderCursor.posX >= boundaryCorner1.posX && blockUnderCursor.posX <= boundaryCorner2.posX
//                && blockUnderCursor.posY >= boundaryCorner1.posY && blockUnderCursor.posY <= boundaryCorner2.posY
//                && blockUnderCursor.posZ >= boundaryCorner1.posZ && blockUnderCursor.posZ <= boundaryCorner2.posZ ) {
//          selectedBlockIsInsideBoundaryField = true;
//        }
//      }
//
//      if (selectedBlockIsInsideBoundaryField) {
//        currentHighlighting = SelectionType.BOUND_FILL_STRICT;
//        highlightedBlocks = selectFill(target, player.worldObj, MAX_NUMBER_OF_HIGHLIGHTED_BLOCKS, controlKeyIsDown, false,
//                boundaryCorner1.posX, boundaryCorner2.posX,
//                boundaryCorner1.posY, boundaryCorner2.posY,
//                boundaryCorner1.posZ, boundaryCorner2.posZ);
//      } else {
//        currentHighlighting = SelectionType.UNBOUND_FILL_STRICT;
//        highlightedBlocks = selectFill(target, player.worldObj, MAX_NUMBER_OF_HIGHLIGHTED_BLOCKS, controlKeyIsDown, false,
//                Integer.MIN_VALUE, Integer.MAX_VALUE,
//                0, 255,
//                Integer.MIN_VALUE, Integer.MAX_VALUE);
//      }
//      return true;
//    }
//    return false;
//  }


}
