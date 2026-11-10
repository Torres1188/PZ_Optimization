package pzopt;

import zombie.iso.IsoChunkMap;

/** The existing chunk-handoff slack callbacks without a new unsigned game-package class. */
public final class ChunkHandoffProducer implements SlackWork.Producer {
   public boolean pending() {
      return IsoChunkMap.pzoptHandoffPending();
   }

   public long nextCostNs() {
      return IsoChunkMap.pzoptHandoffNextCostNs();
   }

   public boolean forceAnyFrame() {
      return IsoChunkMap.pzoptHandoffForceAnyFrame();
   }

   public int nextAgeFrames() {
      return IsoChunkMap.pzoptHandoffNextAgeFrames();
   }

   public int maxWaitFrames() {
      return IsoChunkMap.pzoptHandoffMaxWaitFrames();
   }

   public void runNext() {
      IsoChunkMap.pzoptHandoffRunNext();
   }
}
