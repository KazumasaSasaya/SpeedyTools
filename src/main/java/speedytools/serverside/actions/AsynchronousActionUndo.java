package speedytools.serverside.actions;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import speedytools.common.utilities.ErrorLog;
import speedytools.serverside.network.SpeedyToolsNetworkServer;
import speedytools.serverside.worldmanipulation.AsynchronousToken;
import speedytools.serverside.worldmanipulation.UniqueTokenID;
import speedytools.serverside.worldmanipulation.WorldHistory;

/**
* User: The Grey Ghost
* Date: 3/08/2014
*/
public class AsynchronousActionUndo extends AsynchronousActionBase
{
  public AsynchronousActionUndo(SpeedyToolsNetworkServer i_speedyToolsNetworkServer, WorldServer i_worldServer, EntityPlayerMP i_player, WorldHistory i_worldHistory,
                                int i_undoSequenceNumber)
  {
    super(i_worldServer, i_player, i_worldHistory, i_undoSequenceNumber);
    currentStage = ActionStage.SETUP;
  }

  @Override
  public void continueProcessing() {
    switch (currentStage) {
      case SETUP: {
        transactionToUndo = worldHistory.getTransactionIDForNextComplexUndo(entityPlayerMP, worldServer);
        AsynchronousToken token = worldHistory.performComplexUndoAsynchronous(entityPlayerMP, worldServer, transactionToUndo);
        if (token == null) {
          aborting = true;
          currentStage = ActionStage.COMPLETE;
          completed = true;
          break;
        }
        currentStage = ActionStage.UNDO;
        setSubTask(token, currentStage.durationWeight, false);
        break;
      }
      case UNDO: {
        if (!executeSubTask()) break;
        AsynchronousToken token = worldHistory.performComplexUndoAsynchronous(entityPlayerMP, worldServer, transactionToUndo);  // repeat for any other undo
        if (token == null) {
          currentStage = ActionStage.COMPLETE;
        } else {
          setSubTask(token, currentStage.durationWeight, true);
        }
        break;
      }
      case COMPLETE: {
        if (!completed) {
          completed = true;
        }
        break;
      }
      default: {
        assert false : "Invalid currentStage : " + currentStage;
      }
    }
  }

  @Override
  public void abortProcessing() {  // can't abort an undo yet.  maybe later (or not)
    ErrorLog.defaultLog().debug("Can't abort an undo yet: AsynchronousActionUndo.abortProcessing() called");
  }

  public enum ActionStage {
    SETUP(0.0), UNDO(1.0), COMPLETE(0.0);
    ActionStage(double i_durationWeight) {durationWeight = i_durationWeight;}
    public double durationWeight;
  }

  private ActionStage currentStage;
  private UniqueTokenID transactionToUndo;
}
