package era.put.building;

import era.put.base.MongoUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoCursorNotFoundException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoCollection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.ObjectId;

import era.put.base.MongoConnection;
import era.put.base.Util;

import static com.mongodb.client.model.Filters.exists;

public class RepeatedImageDetector {
    private static final Logger logger = LogManager.getLogger(RepeatedImageDetector.class);
    public static int maxGroupSize = 0;

    private static void markAsReferenceAndRemoveFile(Document c, Document parent, MongoCollection<Document> image) {
        Document newDocument = new Document().append("x", parent.get("_id"));
        Document filter = new Document().append("_id", c.get("_id"));
        Document query = new Document().append("$set", newDocument);
        image.updateOne(filter, query);

        String filenameToRemove = ImageDownloader.imageFilename(c, System.out);
        File fd = new File(filenameToRemove);
        if (fd.exists()) {
            if (!fd.delete()) {
                logger.error("Can not delete " + fd.getAbsolutePath());
            }
        }
    }

    private static void processGroupCandidatesForImage(MongoCollection<Document> image, Document pivot) {
        ImageFileAttributes attrPivot = MongoUtil.getImageAttributes(pivot);
        if (attrPivot == null) {
            return;
        }
        ObjectId parentPivot = MongoUtil.getImageParentProfileId(pivot);
        if (parentPivot == null) {
            return;
        }
        String filenamePivot = ImageDownloader.imageFilename(pivot, System.out);

        // Build candidate set
        List<Document> candidateSet = new ArrayList<>();
        Document filter = new Document().append("a.shasum", attrPivot.getShasum());
        for (Document currentDocument: image.find(filter).sort(new BasicDBObject("md", 1))) {
            // 1. Extract pair [pivot, currentDocument]
            ImageFileAttributes attrI = MongoUtil.getImageAttributes(currentDocument);
            if (attrI == null) {
                continue;
            }
            ObjectId parentI = MongoUtil.getImageParentProfileId(currentDocument);
            if (parentI == null) {
                continue;
            }
            String filenameI = ImageDownloader.imageFilename(currentDocument, System.out);

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
                // If currentDocument has parent, it is part of the group (previously cleaned)
                if (currentDocument.get("x") == null
                    && Util.filesAreDifferent(filenamePivot, filenameI)) {
                    continue;
                }
            } catch (Exception e) {
                continue;
            }

            // 5. Add to candidate set
            candidateSet.add(currentDocument);
        }

        // Merge groups and trim repeated images, keep older one
        if (candidateSet.isEmpty()) {
            return;
        }

        if (candidateSet.size() > 9) {
            if (candidateSet.size() > maxGroupSize) {
                maxGroupSize = candidateSet.size();
            }
            System.out.println("SET: " + candidateSet.get(0).get("_id") + " (max " + maxGroupSize + ")");
            System.out.println("  - Size: " + candidateSet.size());
            System.out.println("  - Shasum: " + ((Document)candidateSet.get(0).get("a")).get("shasum"));
            System.out.println("  - Group sample: " + ImageDownloader.imageFilename(candidateSet.get(0), System.out));
        }
        Document parent = null;

        for (int j = 0; j < candidateSet.size(); j++) {
            Document c = candidateSet.get(j);
            if (j == 0) {
                parent = c;
                Document newDocument = new Document().append("x", true);
                Document parentFilter = new Document().append("_id", parent.get("_id"));
                Document query = new Document().append("$set", newDocument);
                image.updateOne(parentFilter, query);
            } else {
                try {
                    markAsReferenceAndRemoveFile(c, parent, image);
                } catch (Exception e) {
                    return;
                }
            }
        }
    }

    public static void
    groupImages(MongoCollection<Document> image) {
        try {
            System.out.println("= DETECTING REPEATED IMAGES ==========================================================");
            for (Document i : image.find(exists("x", false))) {
                processGroupCandidatesForImage(image, i);
            }
            System.out.println("= DETECTING REPEATED IMAGES PROCESS COMPLETE =========================================");
        } catch (MongoCursorNotFoundException | MongoTimeoutException e) {
            MongoConnection c = MongoUtil.connectWithMongoDatabase();
            if (c != null && c.image != null) {
                groupImages(c.image);
            }
        }
    }
}
