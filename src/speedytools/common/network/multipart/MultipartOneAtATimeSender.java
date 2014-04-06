package speedytools.common.network.multipart;

import net.minecraft.network.packet.Packet250CustomPayload;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import speedytools.common.network.PacketSender;
import speedytools.common.utilities.ErrorLog;

import java.util.*;

/**
 * User: The Grey Ghost
 * Date: 31/03/14
 * CLASS IS NOT COMPLETED
 */
public class MultipartOneAtATimeSender
{
  public MultipartOneAtATimeSender()
  {
    abortedPacketsByID = new HashMap<Integer, Long>();
    abortedPacketsByTime = new PriorityQueue<Pair<Long, Integer>>();
  }

  /**
   * Changes to a new PacketSender
   * @param newPacketSender
   */
  public void setPacketSender(PacketSender newPacketSender)
  {
    packetSender = newPacketSender;
  }

  /**
   * start sending the given packet.
   * Only one packet of any given type can be sent at any one time.  If a second packet of the same type is added, the first is aborted.
   * the packet uniqueID must greater than all previously sent packets
   * @param linkage the linkage that should be used to inform the sender of progress
   * @param packet the packet to be sent.  The uniqueID of the packet must match the unique ID of the linkage!
   * @return true if the packet was successfully added (and hadn't previously been added)
   */
  public boolean sendMultipartPacket(PacketLinkage linkage, MultipartPacket packet)
  {
  //  - start transmission, provide a callback
    byte packetTypeID = packet.getPacketTypeID();

    if (packet.getUniqueID() <= previousPacketIDs.get(packetTypeID)) {
      throw new IllegalArgumentException("packetID " + packet.getUniqueID() + " was older than a previous saved packetID "+ previousPacketIDs.get(packetTypeID));
    }

    if (packetsBeingSent.containsKey(packetTypeID)) {
      if (packet.getUniqueID() <= packetsBeingSent.get(packetTypeID).packet.getUniqueID()) {
        throw new IllegalArgumentException("packetID " + packet.getUniqueID() + " was older than existing packetID "+ packetsBeingSent.get(packetTypeID).packet.getUniqueID());
      }
      abortPacket(packetTypeID);
    }
    if (linkage.getPacketID() != packet.getUniqueID()) {
      throw new IllegalArgumentException("linkage packetID " + linkage.getPacketID() + " did not match packet packetID "+ packet.getUniqueID());
    }
    if (packet.hasBeenAborted()) return false;

    PacketTransmissionInfo packetTransmissionInfo = new PacketTransmissionInfo();
    packetTransmissionInfo.packet = packet;
    packetTransmissionInfo.linkage = linkage;
    packetTransmissionInfo.transmissionState = PacketTransmissionInfo.TransmissionState.SENDING_INITIAL_SEGMENTS;
    packetTransmissionInfo.timeOfLastAction = 0;
    packetsBeingSent.put(packetTypeID, packetTransmissionInfo);
    doTransmission(packetTransmissionInfo);
    linkage.progressUpdate(packet.getPercentComplete());
    return true;
  }

  private void abortPacket(byte packetTypeID)
  {
    PacketTransmissionInfo pti = packetsBeingSent.get(packetTypeID);
    pti.linkage.packetAborted();
    Packet250CustomPayload abortPacket = pti.packet.getAbortPacket();
    packetSender.sendPacket(abortPacket);
    assert previousPacketIDs.get(packetTypeID) <= pti.packet.getUniqueID();
    previousPacketIDs.put(packetTypeID, pti.packet.getUniqueID());
    packetsBeingSent.remove(packetTypeID);
  }

  private static final int ACKNOWLEDGEMENT_WAIT_MS = 100;  // minimum ms elapsed between sending a packet and expecting an acknowledgement
  private static final int MS_TO_NS = 1000000;

  /**
   * Transmit the next part of this packet as necessary
   * @param packetTransmissionInfo
   * @return true if something was transmitted, false if no action was performed
   */

  private boolean doTransmission(PacketTransmissionInfo packetTransmissionInfo)
  {
    // see multipartprotocols.txt for more information on the transmission behaviour
    assert !packetTransmissionInfo.packet.hasBeenAborted();   // packet should have been removed from transmission list

    if (!packetSender.readyForAnotherPacket()) return false;

    boolean sentSomethingFlag = false;

    switch (packetTransmissionInfo.transmissionState) {
      case RECEIVING: {
        assert false: "doTransmission called for a packet in RECEIVING state:";
        break;
      }
      case SENDING_INITIAL_SEGMENTS: {
        if (!packetTransmissionInfo.packet.allSegmentsSent()) {
          Packet250CustomPayload nextSegment = packetTransmissionInfo.packet.getNextUnsentSegment();
          if (nextSegment != null) {
            sentSomethingFlag = packetSender.sendPacket(nextSegment);
            packetTransmissionInfo.timeOfLastAction = System.nanoTime();
          }
        }
        if (packetTransmissionInfo.packet.allSegmentsSent()) {
          packetTransmissionInfo.transmissionState = PacketTransmissionInfo.TransmissionState.SENDER_WAITING_FOR_ACK;
        }
        break;
      }
      case SENDER_WAITING_FOR_ACK: {
        assert !packetTransmissionInfo.packet.allSegmentsAcknowledged();       // packet should have been removed from transmission list
        if (System.nanoTime() - packetTransmissionInfo.timeOfLastAction >= ACKNOWLEDGEMENT_WAIT_MS * MS_TO_NS) {  // timeout waiting for ack: send the first unacked segment, then wait
          Packet250CustomPayload nextSegment = packetTransmissionInfo.packet.getNextUnacknowledgedSegment();
          if (nextSegment != null) {
            sentSomethingFlag = packetSender.sendPacket(nextSegment);
            packetTransmissionInfo.timeOfLastAction = System.nanoTime();
            packetTransmissionInfo.transmissionState = PacketTransmissionInfo.TransmissionState.WAITING_FOR_FIRST_RESEND;
            packetTransmissionInfo.packet.resetAcknowledgementsReceivedFlag();
          }
        }
        break;
      }
      case WAITING_FOR_FIRST_RESEND: {
        assert !packetTransmissionInfo.packet.allSegmentsAcknowledged();       // packet should have been removed from transmission list
        if (packetTransmissionInfo.packet.getAcknowledgementsReceivedFlag()) {
          packetTransmissionInfo.transmissionState = PacketTransmissionInfo.TransmissionState.RESENDING;
        } else if (System.nanoTime() - packetTransmissionInfo.timeOfLastAction >= ACKNOWLEDGEMENT_WAIT_MS * MS_TO_NS) {  // timeout waiting for ack: resend the first unacked segment, then wait again
          packetTransmissionInfo.packet.resetToOldestUnacknowledgedSegment();
          Packet250CustomPayload nextSegment = packetTransmissionInfo.packet.getNextUnacknowledgedSegment();
          if (nextSegment != null) {
            sentSomethingFlag = packetSender.sendPacket(nextSegment);
            packetTransmissionInfo.timeOfLastAction = System.nanoTime();
          }
        }
        break;
      }
      case RESENDING: {
        assert !packetTransmissionInfo.packet.allSegmentsAcknowledged();       // packet should have been removed from transmission list
        Packet250CustomPayload nextSegment = packetTransmissionInfo.packet.getNextUnacknowledgedSegment();
        if (nextSegment == null) {
          packetTransmissionInfo.transmissionState = PacketTransmissionInfo.TransmissionState.SENDER_WAITING_FOR_ACK;
        } else {
          sentSomethingFlag = packetSender.sendPacket(nextSegment);
          packetTransmissionInfo.timeOfLastAction = System.nanoTime();
          packetTransmissionInfo.packet.resetToOldestUnacknowledgedSegment();
        }
        break;
      }
      default: {
        assert false: "invalid transmission state: " + packetTransmissionInfo.transmissionState;
      }
    }
    return sentSomethingFlag;
  }

  /**
   * processes an incoming packet;
   * informs the appropriate linkage of progress
   * sends an abort packet back if this packet has already been completed
   * @param packet
   * @return true for success, false if packet is invalid or is ignored
   */
  public boolean processIncomingPacket(Packet250CustomPayload packet)
  {
    Integer packetUniqueID = MultipartPacket.readUniqueID(packet);
    if (packetUniqueID == null) return false;

    // perhaps we sent an abort message that was never received
    Byte packetTypeID = MultipartPacket.readPacketTypeID(packet);
    if (packetUniqueID <= previousPacketIDs.get(packetTypeID)) {
      Packet250CustomPayload abortPacket = MultipartPacket.getAbortPacketForLostPacket(packet);
      if (abortPacket != null) packetSender.sendPacket(abortPacket);
      return false;
    }
    PacketTransmissionInfo pti = packetsBeingSent.get(packetTypeID);
    if (packetUniqueID != pti.packet.getUniqueID()) {
      ErrorLog.defaultLog().warning("Incoming packetUniqueID " + packetUniqueID + " did not match packet being sent ID " + pti.packet.getUniqueID() + " for packet type " + packetTypeID);
      return false;
    }
    boolean success = doProcessIncoming(pti, packet);
    if (success) pti.linkage.progressUpdate(pti.packet.getPercentComplete());
    return success;
  }

  private boolean doProcessIncoming(PacketTransmissionInfo packetTransmissionInfo, Packet250CustomPayload packet)
  {
    boolean success = packetTransmissionInfo.packet.processIncomingPacket(packet);

    if (   packetTransmissionInfo.packet.hasBeenAborted()
        || packetTransmissionInfo.packet.allSegmentsReceived()
        || packetTransmissionInfo.packet.allSegmentsAcknowledged()) {
      byte packetTypeID = packetTransmissionInfo.packet.getPacketTypeID();
      assert previousPacketIDs.get(packetTypeID) <= packetTransmissionInfo.packet.getUniqueID();
      previousPacketIDs.put(packetTypeID, packetTransmissionInfo.packet.getUniqueID());
      packetsBeingSent.remove(packetTypeID);
    } else {
      packetTransmissionInfo.linkage.progressUpdate(packetTransmissionInfo.packet.getPercentComplete());
    }
    return success;
  }

  private static final long TIMEOUT_AGE_S = 120;           // discard "stale" packets older than this - also abort packets and completed receive packets
  private static final long NS_TO_S = 1000 * 1000 * 1000;

  /**
   * should be called frequently to handle sending of segments within packet, etc
   */
  public void onTick()
  {
    // must make a copy to avoid the transmission altering packetsBeingSent while we're iterating through it
    LinkedList<PacketTransmissionInfo> packetListCopy = new LinkedList<PacketTransmissionInfo>(packetsBeingSent.values());

    for (PacketTransmissionInfo packetTransmissionInfo : packetListCopy) {
      doTransmission(packetTransmissionInfo);
    }
  }

  public void abortPacket(PacketLinkage linkage)
  {

  }

  /**
   * This class is used by the MultipartPacketHandler to communicate the packet transmission progress to the sender
   */
  public interface PacketLinkage
  {
    public void progressUpdate(int percentComplete);
    public void packetCompleted();
    public void packetAborted();
    public int getPacketID();
  }

  private static class PacketTransmissionInfo {
    public MultipartPacket packet;
    public PacketLinkage linkage;
    public long timeOfLastAction;
    public TransmissionState transmissionState;
    public enum TransmissionState {RECEIVING, SENDING_INITIAL_SEGMENTS, SENDER_WAITING_FOR_ACK, WAITING_FOR_FIRST_RESEND, RESENDING};
  }

  private HashMap<Byte, PacketTransmissionInfo> packetsBeingSent;
  private HashMap<Byte, Integer> previousPacketIDs;

  private PacketSender packetSender;

}
