package era.put.mining;

import com.mongodb.BasicDBObject;
import com.mongodb.client.MongoCollection;
import era.put.base.MongoConnection;
import org.bson.Document;
import org.bson.types.ObjectId;
import era.put.base.Util;
import era.put.building.ImageDownloader;
import era.put.building.ImageFileAttributes;

import java.util.ArrayList;
import java.util.List;

public class ImageInfo {
    private static int counter = 0;
    /**
     * Find the profile(s) with highest number of image groups.
     */

    /**
     * Find the profile(s) with highest pixel count.
     */

    private static void
    reportProfilesWithCommonImagesForPivot(
        MongoCollection<Document> image,
        MongoCollection<Document> profile,
        Document pivot) {
        ImageFileAttributes attrPivot = Util.getImageAttributes(pivot);
        if (attrPivot == null) {
            return;
        }
        ObjectId parentPivot = Util.getImageParentProfileId(pivot);
        if (parentPivot == null) {
            return;
        }
        String filenamePivot = ImageDownloader.imageFilename(pivot, System.out);

        // Build candidate set
        List<Document> candidateSet = new ArrayList<>();
        Document filter = new Document().append("a.shasum", attrPivot.getShasum()).append("x", true);
        for (Document i: image.find(filter).sort(new BasicDBObject("md", 1))) {
            // 1. Extract pair [pivot, i]
            ImageFileAttributes attrI = Util.getImageAttributes(i);
            ObjectId parentI = Util.getImageParentProfileId(i);
            String filenameI = ImageDownloader.imageFilename(i, System.out);

            // 2. Compare by attributes
            if (attrPivot.compareTo(attrI) != 0) {
                continue;
            }

            // 3. Compare by parent profile
            if (parentPivot.compareTo(parentI) != 0) {
                continue;
            }

            // 4. Compare files
            try {
                // If i has an parent, it is part of the group (previously cleaned)
                if (i.get("x") == null
                        && !Util.fileDiff(filenamePivot, filenameI)) {
                    continue;
                }
            } catch (Exception e) {
                continue;
            }

            // 5. Add to candidate set
            candidateSet.add(i);
        }

        // Report if repeated images
        if (candidateSet.size() < 2) {
            counter++;
            if (counter % 10000 == 0) {
                System.out.println("  - No repeated images (" + counter + ")");
            }
            return;
        }

        for (int i = 0; i < candidateSet.size(); i++) {
            Document d = candidateSet.get(i);
            if (i == 0) {
                System.out.println("Group of " + d.get("url") + ":");
            }
            Document p = Util.getImageParentProfile(d, profile);
            System.out.println("  - " + p.get("p"));
        }
    }

    /**
     * Report profiles with common images (i.e. the same people using several
     * different phone numbers).
     * Test disabled: previous results are empty.
     */
    public static void
    reportProfilesWithCommonImages() {
        MongoConnection mongoConnection = Util.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        System.out.println("= DETECTING REPEATED IMAGES ACROSS PROFILES ==========================================");
        Document filter = new Document().append("x", true);
        for (Document i: mongoConnection.image.find(filter).sort(new BasicDBObject("md", 1))) {
            reportProfilesWithCommonImagesForPivot(mongoConnection.image, mongoConnection.profile, i);
        }
        System.out.println("= DETECTING REPEATED IMAGES ACROSS PROFILES PROCESS COMPLETE =========================");
    }

    /**
     * Find similar images (not same file) by external image processing tool.
     */

    /**
     * Find most long-used photos in time. (Time difference between the moment
     * when an image was firstly used and when image was lastly used).
     */

    /**
     * Traverse all the images to check _id/a.shasum pairs and report groups of size 2 or more
     * (result should be empty).
     */
}
