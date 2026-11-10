package pzopt;

/** The existing mirror-wall cutaway hold state, outside signed game packages. */
public final class MirrorCutState {
   public final boolean[] cut = new boolean[2], wanted = new boolean[2];
   public final long[] changedAt = new long[2], wantedAt = new long[2];
}
