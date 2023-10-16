package era.put.mining;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import era.put.base.MongoConnection;
import era.put.base.MongoUtil;
import era.put.base.Util;
import era.put.building.ImageFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;

public class ImageInfo {
    private static final Logger logger = LogManager.getLogger(ImageInfo.class);
    private static final int NUMBER_OF_REPORTER_THREADS = 72;

    private static void
    detectDuplicatedImagesBetweenProfiles(
        MongoCollection<Document> image,
        Document parentImageObject,
        ConcurrentHashMap<String, Set<String>> groups,
        AtomicInteger totalImagesProcessed,
        AtomicInteger externalMatchCounter) {
        int n = totalImagesProcessed.incrementAndGet();
        if (n % 100000 == 0) {
            logger.info("Images processed for internal profile repetitions ({})", n);
        }

        ImageFileAttributes attrPivot = MongoUtil.getImageAttributes(parentImageObject);
        if (attrPivot == null) {
            return;
        }
        ObjectId profileIdPivot = MongoUtil.getImageParentProfileId(parentImageObject);
        if (profileIdPivot == null) {
            return;
        }
        String imageIdPivot = ((ObjectId)parentImageObject.get("_id")).toString();

        // Build candidate set
        Document filter = new Document()
            .append("_id", new BasicDBObject("$ne", parentImageObject.get("_id")))
            .append("a.shasum", attrPivot.getShasum())
            .append("x", true);
        FindIterable<Document> imageIterable = image.find(filter)
            .projection(Projections.include("_id", "a", "u", "md"))
            .sort(new BasicDBObject("md", 1));

        for (Document i: imageIterable) {
            // 1. Not comparing with itself
            String imageIdI = ((ObjectId) i.get("_id")).toString();
            if (imageIdPivot.equals(imageIdI)) {
                continue;
            }

            // 2. Extract pair [imagePivotObject, i]
            ImageFileAttributes attrI = MongoUtil.getImageAttributes(i);
            ObjectId profileIdI = MongoUtil.getImageParentProfileId(i);
            if (attrI == null || profileIdI == null) {
                continue;
            }

            // 3. Compare by attributes
            if (attrPivot.compareTo(attrI) != 0) {
                continue;
            }

            // 4. Compare by parent profile
            if (profileIdPivot.compareTo(profileIdI) != 0) {
                externalMatchCounter.incrementAndGet();
                Set<String> group = groups.get(imageIdPivot);
                if (group == null) {
                    group = new TreeSet<>();
                    group.add(profileIdPivot.toString());
                    groups.put(imageIdPivot, group);
                    group = groups.get(imageIdPivot);
                }
                group.add(profileIdI.toString());
            }
        }
    }

    /**
     * Report profiles with common images (i.e. the same people using several
     * different phone numbers).
     * Test disabled: previous results are empty.
     */
    public static void
    deleteExternalChildImages() {
        MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        logger.info("= DETECTING REPEATED IMAGES ACROSS PROFILES ==========================================");
        Document filter = new Document("md", new BasicDBObject("$exists", true))
                .append("x", true)
                .append("a", new BasicDBObject("$exists", true))
                .append("u", new BasicDBObject("$exists", true));
        FindIterable<Document> parentImageIterable = mongoConnection.image.find(filter)
            .projection(Projections.include("_id", "a", "u", "md"))
            .sort(new BasicDBObject("md", 1));

        ThreadFactory threadFactory = Util.buildThreadFactory("ParentImagesShaComparator[%03d]");
        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_REPORTER_THREADS, threadFactory);
        AtomicInteger totalImagesProcessed = new AtomicInteger(0);
        AtomicInteger externalMatchCounter = new AtomicInteger(0);
        ConcurrentHashMap<String, Set<String>> externalMatches; // imageId vs set of profileId :)
        externalMatches = new ConcurrentHashMap<>();

        parentImageIterable.forEach((Consumer<? super Document>)parentImageObject ->
            executorService.submit(() ->
                detectDuplicatedImagesBetweenProfiles(
                    mongoConnection.image, parentImageObject, externalMatches,
                    totalImagesProcessed, externalMatchCounter)
            )
        );

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.MINUTES)) {
                logger.error("Parent image comparator threads taking so long!");
            }
        } catch (InterruptedException e) {
            logger.error(e);
        }

        deleteProfileExternalRepeatedImages(externalMatches, mongoConnection.profileInfo);

        logger.info("Total parent images processed: {}", totalImagesProcessed.get());
        logger.info("External matches skipped: {}", externalMatchCounter.get());
        logger.info("= DETECTING REPEATED IMAGES ACROSS PROFILES PROCESS COMPLETE =========================");
    }

    private static void deleteProfileExternalRepeatedImages(ConcurrentHashMap<String, Set<String>> externalMatches, MongoCollection<Document> profileInfo) {
        logger.info("--------------------------------------------------------------------------------------");
        logger.info("Detected image hint sets: {}", externalMatches.size());
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (String imageId: externalMatches.keySet()) {
            Set<String> profileHints = externalMatches.get(imageId);
            int n = profileHints.size();
            if (n > max) {
                max = n;
            }
            if (n < min) {
                min = n;
            }

            /*
            if (n == 18) {
                logger.info("Group:");
                for (String profileId: profileHints) {
                    Document filter = new Document("_id", new ObjectId(profileId));
                    FindIterable<Document> profileIterable = profileInfo.find(filter)
                            .projection(Projections.include("p", "firstPostDate", "lastPostDate", "numPosts", "numImages", "lastLocation", "lastService"))
                            .sort(new BasicDBObject("p", 1));
                    profileIterable.forEach((Consumer<? super Document>)p -> {
                        logger.info("phone: {}", p.get("p"));
                        logger.info("  . firstPostDate: {}", p.get("firstPostDate"));
                        logger.info("  . lastPostDate: {}", p.get("lastPostDate"));
                        logger.info("  . numPosts: {}", p.get("numPosts"));
                        logger.info("  . numImages: {}", p.get("numImages"));
                        logger.info("  . lastLocation: {}", p.get("lastLocation"));
                        logger.info("  . lastService: {}", p.get("lastService"));
                    });
                }
            }
            */
        }
        logger.info("Minimum image hint set size (images with less usages are re-used this times): {}", min);
        logger.info("Maximum image hint set size (images with most usages are re-used this times): {}", max);

        Set<String> totalProfiles = new TreeSet<>();
        for (Set<String> subgroups: externalMatches.values()) {
            totalProfiles.addAll(subgroups);
        }

        logger.info("Profiles with repeated image relationship hints: {}", totalProfiles.size());

        logger.info("--------------------------------------------------------------------------------------");
        Graph<String, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);
        for (String node: totalProfiles) {
            graph.addVertex(node);
        }

        for (String imageId: externalMatches.keySet()) {
            Set<String> profileHints = externalMatches.get(imageId);
            List<String> profileHintsList = profileHints.stream().toList();
            for (int i = 1; i < profileHintsList.size(); i++) {
                String currentProfile = profileHintsList.get(i);
                graph.addEdge(profileHintsList.get(0), currentProfile);
            }
        }

        ConnectivityInspector<String, DefaultEdge> inspector = new ConnectivityInspector<>(graph);
        List<Set<String>> connectedComponents = inspector.connectedSets();

        min = Integer.MAX_VALUE;
        max = 0;
        int pairsCount = 0;
        for (Set<String> group: connectedComponents) {
            int n = group.size();
            if (n > max) {
                max = n;
            }
            if (n < min) {
                min = n;
            }
            if (n == 2) {
                pairsCount++;
            }
            if (n > 100) {
                StringBuilder msg = new StringBuilder();
                logger.info("Group of {} profiles:", n);
                for (String profileId: group) {
                    FindIterable<Document> profileIterable = profileInfo.find(new Document("_id", new ObjectId(profileId)));
                    for (Document p: profileIterable) {
                        msg.append(p.get("p").toString());
                        msg.append(",");
                    }
                }
                logger.info(msg.toString());
            }
        }
        logger.info("Profile groups found: {}", connectedComponents.size());
        logger.info("Pairs (2-sized groups) found: {}", pairsCount);
        logger.info("Minimum profile group size: {}", min);
        logger.info("Maximum profile group size: {}", max);

        logger.info("--------------------------------------------------------------------------------------");
    }

    /*
     * Find similar images (not same file) by external image processing tool.
     */

    /*
     * Find most long-used photos in time. (Time difference between the moment
     * when an image was firstly used and when image was lastly used).
     */

    /*
     * Traverse all the images to check _id/a.shasum pairs and report groups of size 2 or more
     * (result should be empty).
     */
}
