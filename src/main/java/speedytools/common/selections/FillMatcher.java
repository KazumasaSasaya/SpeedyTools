package speedytools.common.selections;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import speedytools.common.blocks.BlockWithMetadata;
import speedytools.common.utilities.ErrorLog;

/**
 * FillMatcher is used by fill algorithm to decide the type of blocks that should be added to the selection
 * Typical usage:
 * 1) client side: create the desired FillMatcher eg AnyNonAir
 * 2) the fill algorithm calls fillMatcher.matches(chunk, wcx, wcy, wcz).
 *    MATCH: add this block to the selection
 *    NO_MATCH: don't add this block to the selection
 *    NOT_LOADED: mark as not loaded / not available
 *    OUT_OF_BOUNDS: repeat using a call to fillMatcher.matches(world, .... )
 * To transfer to the server side:
 * 1) fillmatcher.writeToBuffer(ByteBuf)
 * 2) on server side: FillMatcher.createMatcherFromBuffer(ByteBuf)
 * Created by TheGreyGhost on 5/11/14.
 */
public abstract class FillMatcher
{
  /** generates a matcher from a ByteBuf serialised version
   * @param buf
   * @return  the matcher, or null for invalid
   */
  public static FillMatcher createMatcherFromBuffer(ByteBuf buf)
  {
    FillMatcher retval = null;
    try {
      byte matcherTypeID = buf.readByte();
      switch (matcherTypeID) {
        case ANY_NON_AIR: {
          retval = new AnyNonAir();
          break;
        }
        case ANY_SOLID: {
          retval = new AnySolid();
          break;
        }
        case ONLY_SPECIFIED_BLOCK: {
          BlockWithMetadata blockWithMetadata = new BlockWithMetadata();
          int blockID = buf.readInt();
          blockWithMetadata.block = Block.getBlockById(blockID);
          blockWithMetadata.metaData = buf.readInt();
          retval = new OnlySpecifiedBlock(blockWithMetadata);
          break;
        }
        case CONTOUR_FOLLOWER: {
          boolean additiveMode = buf.readBoolean();
          int contourDirectionIndex = buf.readInt();
          EnumFacing contourDirection = EnumFacing.getFront(contourDirectionIndex);
          retval = new ContourFollower(additiveMode, contourDirection);
          break;
        }
        default: {
          ErrorLog.defaultLog().info("Invalid matcherTypeID received:" + matcherTypeID);
        }
      }

    } catch (IndexOutOfBoundsException ioe) {
      ErrorLog.defaultLog().info("Exception while createMatcherFromBuffer: " + ioe);
      return null;
    }
    return retval;
  }

  public enum MatchResult {MATCH, NO_MATCH, NOT_LOADED, OUT_OF_BOUNDS}

  /**
   * does this block meet the matcher criteria?  (i.e. it should be added to the selection)
   * @param chunk
   * @param wcx the x coordinate within the chunk (i.e. 0 - 15)
   * @param wcy the y coordinate within the chunk
   * @param wcz the z coordinate within the chunk (i.e. 0 - 15)
   * @return MATCH if the block matches
   * @return NO_MATCH if the block doesn't match
   * @return NOT_LOADED if the matcher can't tell because one of the chunks isn't loaded
   * @return OUT_OF_BOUNDS if the matcher needs to access an adjacent chunk
   */
  public abstract MatchResult matches(Chunk chunk, int wcx, int wcy, int wcz);

  /**
   * does this block meet the matcher criteria?  (i.e. it should be added to the selection)
   * to be used if matches(Chunk,...) returns OUT_OF_BOUNDS
   * @param world
   * @param wx the world x coordinate
   * @param wy the world x coordinate
   * @param wz the world x coordinate
   * @return MATCH if the block matches
   * @return NO_MATCH if the block doesn't match
   * @return NOT_LOADED if the matcher can't tell because one of the chunks isn't loaded
   */
  public MatchResult matches(World world, int wx, int wy, int wz) {
//    Block block = world.getBlock(wx, wy, wz);
    Chunk chunk = world.getChunkFromChunkCoords(wx >> 4, wz >> 4);
    if (chunk.isEmpty()) return MatchResult.NOT_LOADED;
    return matches(chunk, wx & 0x0f, wy, wz & 0x0f);
  }

  public void writeToBuffer(ByteBuf buf) {
    buf.writeByte(getUniqueID());
  }

  protected abstract byte getUniqueID();

  // -----------------------

  public static class AnyNonAir extends FillMatcher {
    public MatchResult matches(Chunk chunk, int wcx, int wcy, int wcz) {
      if (chunk.isEmpty()) return MatchResult.NOT_LOADED;
      Block block = chunk.getBlock(wcx, wcy, wcz);
      return (block != Blocks.air) ? MatchResult.MATCH : MatchResult.NO_MATCH;
    }
    protected byte getUniqueID() {return ANY_NON_AIR;}
  }

  // -----------------------

  public static class AnySolid extends FillMatcher {
    public MatchResult matches(Chunk chunk, int wcx, int wcy, int wcz) {
      if (chunk.isEmpty()) return MatchResult.NOT_LOADED;
      Block block = chunk.getBlock(wcx, wcy, wcz);
      if (block == Blocks.air) return MatchResult.NO_MATCH;
      final int NO_INTERACTION = 1;
      return (block.getMaterial() == Material.water || block.getMobilityFlag() != NO_INTERACTION) ? MatchResult.MATCH : MatchResult.NO_MATCH;
    }
    protected byte getUniqueID() {return ANY_SOLID;}
  }

  // -----------------------

  public static class NullMatcher extends FillMatcher {
    public MatchResult matches(Chunk chunk, int wcx, int wcy, int wcz) {
      return null;
    }
    protected byte getUniqueID() {return NULL;}
  }
  // -----------------------

  // matches the specified block only; metadata sensitive except for lava and water materials
  public static class OnlySpecifiedBlock extends FillMatcher {
    public OnlySpecifiedBlock(BlockWithMetadata i_blockToMatch) {
      blockToMatch = i_blockToMatch;
    }

    public MatchResult matches(Chunk chunk, int wcx, int wcy, int wcz) {
      if (chunk.isEmpty()) return MatchResult.NOT_LOADED;
      Block block = chunk.getBlock(wcx, wcy, wcz);
      if (block != blockToMatch.block) return MatchResult.NO_MATCH;

      int metadata = chunk.getBlockMetadata(new BlockPos(wcx, wcy, wcz));

      if (metadata == blockToMatch.metaData) return MatchResult.MATCH;
      if (block.getMaterial() == Material.lava || block.getMaterial() == Material.water) return MatchResult.MATCH;
      return MatchResult.NO_MATCH;
    }
    @Override
    public void writeToBuffer(ByteBuf buf) {
      super.writeToBuffer(buf);
      if (blockToMatch == null) {
        buf.writeInt(Block.getIdFromBlock(Blocks.air));
        buf.writeInt(0);
      } else {
        buf.writeInt(Block.getIdFromBlock(blockToMatch.block));
        buf.writeInt(blockToMatch.metaData);
      }
    }

    protected byte getUniqueID() {return ONLY_SPECIFIED_BLOCK;}
    private BlockWithMetadata blockToMatch;
  }

  // -----------------------

  // follows a contour
  // additive mode: if true, follows an adjacent contour of solid blocks, stops when the block itself is solid
  //                if false, follows an adjacent "contour" of air blocks, stops when the block itself is air
  public static class ContourFollower extends FillMatcher {
    public ContourFollower(boolean i_additiveMode, EnumFacing i_directionToContour) {
      additiveMode = i_additiveMode;
      directionToContour = i_directionToContour;
    }
    @Override
    public MatchResult matches(Chunk chunk, int wcx, int wcy, int wcz) {
      if (chunk.isEmpty()) return MatchResult.NOT_LOADED;

      final int NO_INTERACTION = 1;
      // first check if the block itself is suitable based on the additive mode
      Block block = chunk.getBlock(wcx, wcy, wcz);
      boolean thisBlockIsSolid = (block != Blocks.air && (block.getMaterial() == Material.water || block.getMobilityFlag() != NO_INTERACTION));
      if (thisBlockIsSolid == additiveMode) return MatchResult.NO_MATCH;

       // next check if the contour is suitable based on the additive mode
//      wcx += Facing.offsetsXForSide[directionToContour];
//      wcy += Facing.offsetsYForSide[directionToContour];
//      wcz += Facing.offsetsZForSide[directionToContour];
      wcx += directionToContour.getFrontOffsetX();
      wcy += directionToContour.getFrontOffsetY();
      wcz += directionToContour.getFrontOffsetZ();

      if (wcx < 0 || wcx > 15 || wcz < 0 || wcz > 15) return MatchResult.OUT_OF_BOUNDS;
      final int MINIMUM_Y = 0;
      final int MAXIMUM_Y = 255;
      boolean contourBlockIsSolid;
      if (wcy < MINIMUM_Y || wcy > MAXIMUM_Y) {
        contourBlockIsSolid = false;
      } else {
        block = chunk.getBlock(wcx, wcy, wcz);
        contourBlockIsSolid = (block != Blocks.air && (block.getMaterial() == Material.water || block.getMobilityFlag() != NO_INTERACTION));
      }
      return (contourBlockIsSolid != additiveMode) ? MatchResult.NO_MATCH : MatchResult.MATCH;
    }

    @Override
    public MatchResult matches(World world, int wx, int wy, int wz) {
      Chunk chunk = world.getChunkFromBlockCoords(new BlockPos(wx, wy, wz));
      if (chunk.isEmpty()) return MatchResult.NOT_LOADED;

      final int NO_INTERACTION = 1;
      // first check if the block itself is suitable based on the additive mode
      Block block = chunk.getBlock(wx & 0x0f, wy, wz & 0x0f);
      boolean thisBlockIsSolid = (block != Blocks.air && (block.getMaterial() == Material.water || block.getMobilityFlag() != NO_INTERACTION));
      if (thisBlockIsSolid == additiveMode) return MatchResult.NO_MATCH;

      // next check if the contour is suitable based on the additive mode
      wx += directionToContour.getFrontOffsetX();
      wy += directionToContour.getFrontOffsetY();
      wz += directionToContour.getFrontOffsetZ();
      final int MINIMUM_Y = 0;
      final int MAXIMUM_Y = 255;
      boolean contourBlockIsSolid;
      if (wy < MINIMUM_Y || wy > MAXIMUM_Y) {
        contourBlockIsSolid = false;
      } else {
        chunk = world.getChunkFromBlockCoords(new BlockPos(wx, wy, wz));
        if (chunk.isEmpty()) return MatchResult.NOT_LOADED;
        block = chunk.getBlock(wx & 0x0f, wy, wz & 0x0f);
        contourBlockIsSolid = (block != Blocks.air && (block.getMaterial() == Material.water || block.getMobilityFlag() != NO_INTERACTION));
      }
      return (contourBlockIsSolid != additiveMode) ? MatchResult.NO_MATCH : MatchResult.MATCH;
    }

    @Override
    public void writeToBuffer(ByteBuf buf) {
      super.writeToBuffer(buf);
      buf.writeBoolean(additiveMode);
      buf.writeInt(directionToContour.getIndex());
    }

    protected byte getUniqueID() {return CONTOUR_FOLLOWER;}  // used to be ONLY_SPECIFIED_BLOCK -?!!!
    private EnumFacing directionToContour;
    private boolean additiveMode;
  }

  static private final byte ANY_NON_AIR = 1;
  static private final byte ANY_SOLID = 3;
  static private final byte ONLY_SPECIFIED_BLOCK = 5;
  static private final byte NULL = 7;
  static private final byte CONTOUR_FOLLOWER = 9;

}
