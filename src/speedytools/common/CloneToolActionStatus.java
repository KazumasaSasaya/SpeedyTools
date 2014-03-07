package speedytools.common;

/**
 * Created by TheGreyGhost on 7/03/14.
 *
 * Used to keep the Client and Server informed of each others' status
 */
public class CloneToolActionStatus
{
  public CloneToolActionStatus()
  {

  }


  public boolean changeServerState(ServerStatus newState)
  {

  }

  public boolean changeClientState(ClientStatus newState)
  {

  }

  public ClientStatus getClientStatus() {
    return clientStatus;
  }

  public ServerStatus getServerStatus() {
    return serverStatus;
  }

  public byte getServerPercentComplete() {
    return serverPercentComplete;
  }

  public enum ClientStatus {
    IDLE, WAITING_FOR_ACTION_COMPLETE
  }

  public enum ServerStatus {
    IDLE, PERFORMING_BACKUP, PERFORMING_YOUR_ACTION, UNDOING_YOUR_ACTION, BUSY_WITH_OTHER_PLAYER
  }

  private ClientStatus clientStatus = ClientStatus.IDLE;
  private ServerStatus serverStatus = ServerStatus.IDLE;
  private byte serverPercentComplete = 100;

}
