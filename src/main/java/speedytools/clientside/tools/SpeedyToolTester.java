package speedytools.clientside.tools;

import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import speedytools.clientside.UndoManagerClient;
import speedytools.clientside.network.PacketSenderClient;
import speedytools.clientside.rendering.SpeedyToolRenderers;
import speedytools.clientside.rendering.SpeedyToolSounds;
import speedytools.clientside.userinput.UserInput;
import speedytools.common.items.ItemSpeedyTool;
import speedytools.common.network.Packet250SpeedyIngameTester;

/**
* User: The Grey Ghost
* Date: 18/04/2014
*/
public class SpeedyToolTester extends SpeedyTool
{
  public SpeedyToolTester(ItemSpeedyTool i_parentItem, SpeedyToolRenderers i_renderers, SpeedyToolSounds i_speedyToolSounds,
                          UndoManagerClient i_undoManagerClient, PacketSenderClient i_packetSenderClient)
  {
    super(i_parentItem, i_renderers, i_speedyToolSounds, i_undoManagerClient, i_packetSenderClient);
  }

  /**
   * Process user input
   * no effect if the tool is not active.
   * @param userInput
   * @return
   */
  public boolean processUserInput(EntityClientPlayerMP player, float partialTick, UserInput userInput) {
    if (!iAmActive) return false;

    UserInput.InputEvent nextEvent;
    while (null != (nextEvent = userInput.poll())) {
      switch (nextEvent.eventType) {
        case LEFT_CLICK_DOWN: {
          Packet250SpeedyIngameTester packet = new Packet250SpeedyIngameTester(player.getHeldItem().stackSize, false);
          packetSenderClient.sendPacket(packet);
          break;
        }
        case RIGHT_CLICK_DOWN: {
          Packet250SpeedyIngameTester packet = new Packet250SpeedyIngameTester(player.getHeldItem().stackSize, true);
          packetSenderClient.sendPacket(packet);
          break;
        }
        case WHEEL_MOVE: {
          ItemStack currentItem = player.inventory.getCurrentItem();
          int currentcount = currentItem.stackSize;
          int maxStackSize = currentItem.getMaxStackSize();
          if (currentcount >= 1 && currentcount <= maxStackSize) {
            currentcount += nextEvent.count;
            currentcount = ((currentcount - 1) % maxStackSize);
            currentcount = ((currentcount + maxStackSize) % maxStackSize) + 1;    // take care of negative
            currentItem.stackSize = currentcount;
          }
          break;
        }
      }
    }
    return true;
  }

  /**
   * update the tool state based on the player selected items; where the player is looking; etc
   * No effect if not active.
   * @param world
   * @param player
   * @param partialTick
   * @return
   */
  public boolean updateForThisFrame(World world, EntityClientPlayerMP player, float partialTick)
  {
    return true;
  }

  @Override
  public void resetTool() {
    // do nothing....
  }

  /** The user is now holding this tool, prepare it
   * @return
   */
  @Override
  public boolean activateTool()
  {
    iAmActive = true;
    return true;
  }

  /** The user has unequipped this tool, deactivate it, stop any effects, etc
   * @return
   */
  @Override
  public boolean deactivateTool()
  {
    iAmActive = false;
    return true;
  }
}
