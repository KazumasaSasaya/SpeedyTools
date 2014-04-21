package speedytools.clientside.network;

import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.packet.Packet250CustomPayload;
import speedytools.common.network.*;
import speedytools.common.utilities.ErrorLog;


TO DO

rewrite CloneToolsNetworkCLient and Server to use FMLNetworkHandler - network handler instead of player.

/**
 * User: The Grey Ghost
 * Date: 8/03/14
 * Used to send commands to the server, received status updates from the server
 * When issued with a command, keeps trying to contact the server until it receives acknowledgement
 * Usage:
 * (1) connectedToServer when player joins
 * (2) changeClientStatus when the client is interested in whether the server is busy
 * (3) informSelectionMade
 *     performToolAction
 *     performToolUndo
 *     These are called when the client needs to send the command to the server.  Once issued, their
 *     progress can be followed by calls to getCurrentActionStatus, getCurrentUndoStatus
 *     These will progress from WAITING to REJECTED, or to PROCESSING and then to COMPLETED
 * (4) The current busy status of the server can be read using getServerStatus and getPercentComplete
 * (5) disconnect when player leaves
 * NB tick() must be called at frequent intervals to check for timeouts - at least once per second
 */
public class CloneToolsNetworkClient
{
  public CloneToolsNetworkClient()
  {
    clientStatus = ClientStatus.IDLE;
    serverStatus = ServerStatus.IDLE;
  }

  public void connectedToServer(EntityClientPlayerMP newPlayer)
  {
    player = newPlayer;
    clientStatus = ClientStatus.IDLE;
    serverStatus = ServerStatus.IDLE;
    lastActionStatus = ActionStatus.NONE_PENDING;
    lastUndoStatus = ActionStatus.NONE_PENDING;
  }

  public void disconnect()
  {
    player = null;
  }

  /**
   * Informs the server of the new client status
   */
  public void changeClientStatus(ClientStatus newClientStatus)
  {
    assert player != null;
    clientStatus = newClientStatus;
    Packet250CloneToolStatus packet = Packet250CloneToolStatus.clientStatusChange(newClientStatus);
    Packet250CustomPayload packet250 = packet.getPacket250CustomPayload();
    if (packet250 != null) {
      player.sendQueue.addToSendQueue(packet250);
      lastServerStatusUpdateTime = System.nanoTime();
    }
  }

  /**
   * sends the "Selection Performed" command to the server
   * @return true for success
   */
  public boolean informSelectionMade()
  {
    Packet250CloneToolUse packet = Packet250CloneToolUse.informSelectionMade();
    Packet250CustomPayload packet250 = packet.getPacket250CustomPayload();
    if (packet250 != null) {
      player.sendQueue.addToSendQueue(packet250);
      return true;
    }

    return false;
  }

  /**
   * sends the "Tool Action Performed" command to the server
   * @param toolID
   * @param x
   * @param y
   * @param z
   * @param rotationCount number of quadrants rotated clockwise
   * @param flipped true if flipped left-right
   * @return true for success, false otherwise
   */
  public boolean performToolAction(int toolID, int x, int y, int z, byte rotationCount, boolean flipped)
  {
    if (lastActionStatus != ActionStatus.NONE_PENDING || lastUndoStatus != ActionStatus.NONE_PENDING || serverStatus != ServerStatus.IDLE) {
      return false;
    }

    Packet250CloneToolUse packet = Packet250CloneToolUse.performToolAction(currentActionSequenceNumber, toolID, x, y, z, rotationCount, flipped);
    lastActionPacket = packet.getPacket250CustomPayload();
    if (lastActionPacket != null) {
      player.sendQueue.addToSendQueue(lastActionPacket);
      lastActionStatus = ActionStatus.WAITING_FOR_ACKNOWLEDGEMENT;
      lastActionSentTime = System.nanoTime();
      return true;
    }
    return false;
  }

  /**
   * sends the "Tool Undo" command to the server
   * undoes the last action (or the action currently in progress)
   * @return true for success, false otherwise
   */
  public boolean performToolUndo()
  {
    Packet250CloneToolUse packet;
    if (lastUndoStatus != ActionStatus.NONE_PENDING) {
      return false;
    }
    if (lastActionStatus == ActionStatus.PROCESSING || lastActionStatus == ActionStatus.WAITING_FOR_ACKNOWLEDGEMENT) {
      packet = Packet250CloneToolUse.cancelCurentAction(currentUndoSequenceNumber, currentActionSequenceNumber);
    } else {
      packet = Packet250CloneToolUse.performToolUndo(currentUndoSequenceNumber);
    }
    lastUndoPacket = packet.getPacket250CustomPayload();
    if (lastUndoPacket != null) {
      player.sendQueue.addToSendQueue(lastUndoPacket);
      lastUndoStatus = ActionStatus.WAITING_FOR_ACKNOWLEDGEMENT;
      lastUndoSentTime = System.nanoTime();
      return true;
    }
    return false;
  }

  public byte getServerPercentComplete() {
    return serverPercentComplete;
  }

  public ServerStatus getServerStatus() {
    return serverStatus;
  }

  /**
   * respond to an incoming status packet
   * @param player
   * @param packet
   */
  public void handlePacket(EntityClientPlayerMP player, Packet250CloneToolStatus packet)
  {
    serverStatus = packet.getServerStatus();
    serverPercentComplete = packet.getCompletionPercentage();
    lastServerStatusUpdateTime = System.nanoTime();
  }

  /**
   * act on an incoming acknowledgement packet
   * reject any packets which don't match the current sequencenumber
   * reject any packets we're not waiting for
   * @param player
   * @param packet
   */
  public void handlePacket(EntityClientPlayerMP player, Packet250CloneToolAcknowledge packet)
  {
    if (lastActionStatus == ActionStatus.WAITING_FOR_ACKNOWLEDGEMENT || lastActionStatus == ActionStatus.PROCESSING) {
      if (packet.getActionSequenceNumber() == currentActionSequenceNumber) {
        switch (packet.getActionAcknowledgement()) {
          case NOUPDATE: {
            break;
          }
          case REJECTED: {
            lastActionStatus = ActionStatus.REJECTED;
            ++currentActionSequenceNumber;
            break;
          }
          case ACCEPTED: {
            lastActionStatus = ActionStatus.PROCESSING;
            lastActionSentTime = System.nanoTime();
            break;
          }
          case COMPLETED: {
            lastActionStatus = ActionStatus.COMPLETED;
            ++currentActionSequenceNumber;
            break;
          }
          default: {
            ErrorLog.defaultLog().warning("Illegal action Acknowledgement in Packet250CloneToolAcknowledgement");
            return;
          }
        }
      }
    }
    if (lastUndoStatus == ActionStatus.WAITING_FOR_ACKNOWLEDGEMENT || lastUndoStatus == ActionStatus.PROCESSING) {
      if (packet.getUndoSequenceNumber() == currentUndoSequenceNumber) {
        switch (packet.getUndoAcknowledgement()) {
          case NOUPDATE: {
            break;
          }
          case REJECTED: {
            lastUndoStatus = ActionStatus.REJECTED;
            ++currentUndoSequenceNumber;
            break;
          }
          case ACCEPTED: {
            lastUndoStatus = ActionStatus.PROCESSING;
            lastUndoSentTime = System.nanoTime();
            break;
          }
          case COMPLETED: {
            lastUndoStatus = ActionStatus.COMPLETED;
            ++currentUndoSequenceNumber;
            break;
          }
          default: {
            ErrorLog.defaultLog().warning("Illegal undo Acknowledgement in Packet250CloneToolAcknowledgement");
            return;
          }
        }
      }
    }
  }

  /**
   * Called once per tick to handle timeouts (if no response obtained, send packet again)
   */
  public void tick()
  {
    if (player == null) return;
    long timenow = System.nanoTime();
    if (lastActionStatus == ActionStatus.WAITING_FOR_ACKNOWLEDGEMENT || lastActionStatus == ActionStatus.PROCESSING) {
      if (timenow - lastActionSentTime > RESPONSE_TIMEOUT_MS * 1000 * 1000) {
        player.sendQueue.addToSendQueue(lastActionPacket);
        lastActionSentTime = timenow;
      }
    }
    if (lastUndoStatus == ActionStatus.WAITING_FOR_ACKNOWLEDGEMENT || lastUndoStatus == ActionStatus.PROCESSING) {
      if (timenow - lastUndoSentTime > RESPONSE_TIMEOUT_MS * 1000 * 1000) {
        player.sendQueue.addToSendQueue(lastUndoPacket);
        lastUndoSentTime = timenow;
      }
    }
    if (clientStatus != ClientStatus.IDLE && (timenow - lastServerStatusUpdateTime > RESPONSE_TIMEOUT_MS * 1000 * 1000) ) {
      Packet250CloneToolStatus packet = Packet250CloneToolStatus.clientStatusChange(clientStatus);
      Packet250CustomPayload packet250 = packet.getPacket250CustomPayload();
      if (packet250 != null) {
        player.sendQueue.addToSendQueue(packet250);
        lastServerStatusUpdateTime = timenow;
      }
    }
  }

  /**
   * retrieves the status of the action currently being peformed
   * If the status is REJECTED or COMPLETED, it will revert to NONE_PENDING after the call
   * @return
   */
  public ActionStatus getCurrentActionStatus()
  {
    ActionStatus retval = lastActionStatus;
    if (lastActionStatus == ActionStatus.COMPLETED || lastActionStatus == ActionStatus.REJECTED) {
      lastActionStatus = ActionStatus.NONE_PENDING;
    }
    return retval;
  }

  /**
   * retrieves the status of the undo currently being performed
   * If the status is REJECTED or COMPLETED, it will revert to NONE_PENDING after the call
   * @return
   */
  public ActionStatus getCurrentUndoStatus()
  {
    ActionStatus retval = lastUndoStatus;
    if (lastUndoStatus == ActionStatus.COMPLETED || lastUndoStatus == ActionStatus.REJECTED) {
      lastUndoStatus = ActionStatus.NONE_PENDING;
    }
    return retval;
  }

  /** retrieves the status of the action currently being performed, without
   *   acknowledging a REJECTED or COMPLETED, i.e. unlike getCurrentActionStatus
   *   it won't revert to NONE_PENDING after the call if the status is REJECTED or COMPLETED
   * @return
   */
  public ActionStatus peekCurrentActionStatus()
  {
    return lastActionStatus;
  }

  /** retrieves the status of the undo currently being performed, without
   *   acknowledging a REJECTED or COMPLETED, i.e. unlike getCurrentUndoStatus
   *   it won't revert to NONE_PENDING after the call if the status is REJECTED or COMPLETED
   * @return
   */
  public ActionStatus peekCurrentUndoStatus()
  {
    return lastUndoStatus;
  }

  private EntityClientPlayerMP player;

  private ClientStatus clientStatus;
  private ServerStatus serverStatus;
  private byte serverPercentComplete;

  private ActionStatus lastActionStatus;
  private ActionStatus lastUndoStatus;

  private long lastServerStatusUpdateTime;  //time in ns.
  private long lastActionSentTime;          //time in ns.
  private long lastUndoSentTime;            //time in ns.

  private Packet250CustomPayload lastActionPacket = null;
  private Packet250CustomPayload lastUndoPacket = null;

  static int currentActionSequenceNumber = 0;
  static int currentUndoSequenceNumber = 0;

  private static final int RESPONSE_TIMEOUT_MS = 2000;  // how long to wait for a response before sending another query

  public static enum ActionStatus
  {
    NONE_PENDING, WAITING_FOR_ACKNOWLEDGEMENT, REJECTED, PROCESSING, COMPLETED;
    public static final ActionStatus[] allValues = {NONE_PENDING, WAITING_FOR_ACKNOWLEDGEMENT, REJECTED, PROCESSING, COMPLETED};
  }
}
