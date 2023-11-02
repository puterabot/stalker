package era.put.interleaving;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoCursorNotFoundException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import era.put.base.MongoUtil;
import era.put.base.Util;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.ObjectId;
import era.put.base.MongoConnection;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;

public class ProfileInfoInterleaver {
    private static final Logger logger = LogManager.getLogger(ProfileInfoInterleaver.class);
    private static int NUMBER_OF_PROFILE_BUILDER_THREADS = 72;
    public static void createExtendedProfileInfo(PrintStream out) {
        try {
            MongoConnection c = MongoUtil.connectWithMongoDatabase();
            if (c == null) {
                return;
            }

            System.out.println("= POPULATING EXTENDED PROFILE INFO =========================");
            System.out.println("Populating start timestamp: " + new Date());
            FindIterable<Document> profileIterable = c.profile.find().sort(new BasicDBObject("p", 1));

            ThreadFactory threadFactory = Util.buildThreadFactory("ExtendedProfileBuilder[%03d]");
            ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_PROFILE_BUILDER_THREADS, threadFactory);
            AtomicInteger counter = new AtomicInteger(0);

            profileIterable.forEach((Consumer<? super Document>) profileDocument -> {
                executorService.submit(() -> buildExtendedInfoProfile(out, profileDocument, c, counter));
            });

            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.MINUTES)) {
                    logger.error("Extended profile builders threads taking so long!");
                }
            } catch (InterruptedException e) {
                logger.error(e);
            }

            System.out.println("= EXTENDED PROFILE INFO COMPLETE =========================");
        } catch (MongoCursorNotFoundException | MongoTimeoutException e) {
            out.println("ERROR: creating extended profile info");
            e.printStackTrace();
        }
    }

    private static void buildExtendedInfoProfile(PrintStream out, Document profileDocument, MongoConnection c, AtomicInteger counter) {
        //
        int numberOfProfilesProcessed = counter.incrementAndGet();
        if (numberOfProfilesProcessed % 10000 == 0) {
            logger.info("Profiles processed: {}", numberOfProfilesProcessed);
        }

        //
        Document filter = new Document("u", profileDocument.getObjectId("_id"));
        int numPosts = 0;
        Date firstPostDate = null;
        Date lastPostDate = null;
        ArrayList<ObjectId> postIdArray = new ArrayList<>();
        ArrayList<String> postUrlArray = new ArrayList<>();
        ArrayList<String> locationArray = new ArrayList<>();
        String lastLocation = null;
        String lastService = null;
        for(Document postDocument: c.post.find(filter).sort(new BasicDBObject("md", 1).append("t", 1))) {
            lastPostDate = MongoUtil.getDateFromMdOrT(postDocument);
            if (firstPostDate == null) {
                firstPostDate = lastPostDate;
            }
            postIdArray.add(postDocument.getObjectId("_id"));
            postUrlArray.add(postDocument.getString("url"));
            lastLocation = postDocument.getString("c") + "/" + postDocument.getString("r");
            String l = postDocument.getString("l");
            if (l != null) {
                lastLocation += "(" + l + ")";
            }
            locationArray.add(lastLocation);
            lastService = postDocument.getString("s");
            numPosts++;
        }

        //
        int numImages = 0;
        Document childImageFilter = new Document("u", profileDocument.getObjectId("_id"));
        ArrayList<ObjectId> childImageIds = new ArrayList<>();
        for(Document i: c.image.find(childImageFilter)) {
            childImageIds.add(i.getObjectId("_id"));
            numImages++;
        }

        TreeSet<ObjectId> relatedProfilesByReplicatedImages = new TreeSet<>();
        List<ObjectId> parentImageIds = traverseChildImages(childImageIds, c.image, relatedProfilesByReplicatedImages);
        if (relatedProfilesByReplicatedImages.contains(profileDocument.getObjectId("_id"))) {
            relatedProfilesByReplicatedImages.remove(profileDocument.getObjectId("_id"));
        }

        //
        Document newDocument = new Document("_id", profileDocument.getObjectId("_id"))
            .append("p", profileDocument.getString("p"))
            .append("firstPostDate", firstPostDate)
            .append("lastPostDate", lastPostDate)
            .append("numPosts", numPosts)
            .append("numImages", numImages)
            .append("postIdArray", postIdArray)
            .append("postUrlArray", postUrlArray)
            .append("imageIdArray", parentImageIds)
            .append("lastLocation", lastLocation)
            .append("locationArray", locationArray)
            .append("lastService", lastService)
            .append("relatedProfilesByReplicatedImages", relatedProfilesByReplicatedImages);

        //
        filter = new Document("_id", profileDocument.getObjectId("_id"));
        Document prev = c.profileInfo.find(filter).first();
        if (prev == null) {
            c.profileInfo.insertOne(newDocument);
        } else {
            c.profileInfo.deleteOne(filter);
            c.profileInfo.insertOne(newDocument);
        }

        //
        out.println(profileDocument.getString("p") + ", " + numPosts + ", " + numImages);
    }

    /**
    Given a set of child images, returns the set of parent images, so in the set there are no repeated content.
    */
    private static List<ObjectId> traverseChildImages(ArrayList<ObjectId> childImageIds, MongoCollection<Document> image, TreeSet<ObjectId> relatedProfilesByReplicatedImages) {
        TreeSet<ObjectId> resolvedIds = new TreeSet<>();
        for (ObjectId childImage: childImageIds) {
            ObjectId finalParent = resolveImage(childImage, 0, image, relatedProfilesByReplicatedImages);
            if (finalParent != null && !resolvedIds.contains(finalParent)) {
                resolvedIds.add(finalParent);
            }
        }
        return resolvedIds.stream().toList();
    }

    private static ObjectId resolveImage(ObjectId childImage, int depth, MongoCollection<Document> image, TreeSet<ObjectId> relatedProfilesByReplicatedImages) {
        if (depth > 100) {
            logger.warn("To many levels on image {}", childImage.toString());
            return null;
        }
        Document filter = new Document("_id", childImage);
        Document parent = image.find(filter).first();
        if (parent == null) {
            return null;
        }
        if (!relatedProfilesByReplicatedImages.contains(parent.getObjectId("_id"))) {
            relatedProfilesByReplicatedImages.add(parent.getObjectId("_id"));
        }
        Object x = parent.get("x");
        if ((x instanceof Boolean)) {
            Boolean value = (Boolean)x;
            if (value.booleanValue()) {
                return childImage;
            }
        }
        ObjectId referenceId = parent.getObjectId("x");
        return resolveImage(referenceId, depth + 1, image, relatedProfilesByReplicatedImages);
    }
}
