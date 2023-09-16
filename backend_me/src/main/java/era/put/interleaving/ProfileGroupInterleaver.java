package era.put.interleaving;

public class ProfileGroupInterleaver {
    /**
     * Given a set of / query to select a subset of posts, reload all those post
     * to check wether they:
     * 1. Still exists (if not, mark as deleted)
     * 2. Exists with the same phone (add a lastVerified date to post)
     * 3. Exists with a different phone (find or add old phone, create or update group and select
     *    last modified profile in group as reference in group)
     */
    public static void buildGroupsByProfilePhoneUpdate() {

    }
}
