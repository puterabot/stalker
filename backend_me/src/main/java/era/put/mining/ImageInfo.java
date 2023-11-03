package era.put.mining;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import era.put.base.MongoConnection;
import era.put.base.MongoUtil;
import era.put.base.Util;
import era.put.building.ImageDownloader;
import era.put.building.ImageFileAttributes;
import java.io.File;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
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
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.ObjectId;

public class ImageInfo {
    private static final Logger logger = LogManager.getLogger(ImageInfo.class);
    private static final int NUMBER_OF_REPORTER_THREADS = 72;

    private static void
    detectDuplicatedImagesBetweenProfiles(
        MongoCollection<Document> image,
        Document parentImageObject,
        ConcurrentHashMap<String, TreeSet<String>> groups,
        AtomicInteger totalImagesProcessed,
        AtomicInteger externalMatchCounter) {
        int n = totalImagesProcessed.incrementAndGet();
        if (n % 100000 == 0) {
            logger.info("Images processed for external profile repetitions ({})", n);
        }

        ImageFileAttributes attrPivot = MongoUtil.getImageAttributes(parentImageObject);
        if (attrPivot == null) {
            return;
        }

        ObjectId profileIdPivot = MongoUtil.getImageParentProfileId(parentImageObject);
        if (profileIdPivot == null) {
            return;
        }

        // Build candidate set
        List<Document> conditions = new ArrayList<>();
        conditions.add(new Document("x", true));
        conditions.add(new Document("_id", new BasicDBObject("$ne", parentImageObject.get("_id"))));
        conditions.add(new Document("a.shasum", attrPivot.getShasum()));
        Document filter = new Document("$and", conditions);

        FindIterable<Document> imageIterable = image.find(filter)
            .projection(Projections.include("_id", "a", "u", "md"))
            .sort(new BasicDBObject("md", 1));

        if (parentImageObject.getObjectId("_id").toString().equals("650f16e4b7b1e72a549ebe8c")) {
            logger.warn("Special case");
        }

        String basicDescriptor = attrPivot.getShasum() + "_" + attrPivot.getSize() + "_" + attrPivot.getDx() + "_" + attrPivot.getDy();
        TreeSet<String> group = groups.get(basicDescriptor);
        if (group == null) {
            group = new TreeSet<>();
            group.add(parentImageObject.get("_id").toString());
            groups.put(basicDescriptor, group);
            group = groups.get(basicDescriptor);
        }

        for (Document currentImage: imageIterable) {
            // 1. Validate query assumptions
            ImageFileAttributes attrI = MongoUtil.getImageAttributes(currentImage);
            ObjectId profileIdI = MongoUtil.getImageParentProfileId(currentImage);
            if (attrI == null || profileIdI == null) {
                continue;
            }

            if (attrPivot.compareTo(attrI) != 0) {
                continue;
            }

            // 2. Add image to common descriptor group
            //if (profileIdPivot.compareTo(profileIdI) != 0) {
                externalMatchCounter.incrementAndGet();
                group.add(currentImage.get("_id").toString());
            //}
        }
    }

    private static void deleteRepeatedExternalImages(ConcurrentHashMap<String, TreeSet<String>> commonImageGroups, MongoCollection<Document> imageCollection) {
        reportGroupsHistogram(commonImageGroups);

        // Removal algorithm by set
        for (String descriptor : commonImageGroups.keySet()) {
            Set<String> imageSetWithCommonDescriptor = commonImageGroups.get(descriptor);
            if (imageSetWithCommonDescriptor.size() <= 1) {
                continue;
            }

            String olderImage = null;
            HashMap<String, Document> imagesToRemove = new HashMap<>();
            for (String imageId: imageSetWithCommonDescriptor) {
                FindIterable<Document> imageIterable = imageCollection.find(new Document("_id", new ObjectId(imageId)));

                try {
                    if (imageIterable.cursor().hasNext()) {
                        Document imageDocument = imageIterable.cursor().next();
                        imagesToRemove.put(imageId, imageDocument);
                        Date currentImageDate = (Date) imageDocument.get("md");
                        if (olderImage == null || currentImageDate.before(currentImageDate)) {
                            olderImage = imageId;
                        }
                    }
                } catch (Exception e) {
                    logger.error(e);
                }
            }

            for (String id: imagesToRemove.keySet()) {
                if (id != olderImage) {
                    Document filter = new Document("_id", new ObjectId(id));
                    Document newDocument = new Document("x", new ObjectId(olderImage));
                    Document query = new Document().append("$set", newDocument);
                    imageCollection.updateOne(filter, query);
                    String filename = ImageDownloader.imageFilename(id, System.err);
                    File fd = new File(filename);

                    File bak = new File(filename + ".bakRepeatedChild");
                    try {
                        FileUtils.copyFile(fd, bak);
                    } catch (IOException e) {
                        logger.error("Can not copy backup {}", bak.getAbsoluteFile());
                    }
                    if (!fd.exists()) {
                        logger.error("Trying to delete non existent file {}", filename);
                    }
                    if (!fd.delete()) {
                        logger.error("Can not delete file {}", filename);
                    }
                }
            }
        }
    }

    private static void reportGroupsHistogram(ConcurrentHashMap<String, TreeSet<String>> commonImageGroups) {
        logger.info("Image sets with common basic descriptors: {}", commonImageGroups.size());
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (String descriptor : commonImageGroups.keySet()) {
            Set<String> imageSetWithCommonDescriptor = commonImageGroups.get(descriptor);
            int n = imageSetWithCommonDescriptor.size();
            if (n == 1) {
                continue;
            }
            if (n > max) {
                max = n;
            }
            if (n < min) {
                min = n;
            }
        }
        logger.info("Minimum image set size (images with less usages are re-used this times): {}", min);
        logger.info("Maximum image set size (images with most usages are re-used this times): {}", max);

        // Report distribution by sizes
        int[] histogram = new int[max + 2]; // Should be just + 1, but exceptions were present
        for (int i = 0; i <= max; i++) {
            histogram[i] = 0;
        }

        for (String descriptor : commonImageGroups.keySet()) {
            TreeSet<String> imageIdSetWithCommonDescriptor = commonImageGroups.get(descriptor);
            int n = imageIdSetWithCommonDescriptor.size();
	    if (n > 0 && n < histogram.length) {
                histogram[n]++;
	    } else {
		logger.error("imageIdSetWithCommonDescriptor with invalid {} size!", n);
	    }
        }

        for (int i = 0; i <= max; i++) {
            if (histogram[i] != 0) {
                logger.info("Sets with [{}] instances: {}", i, histogram[i]);
            }
        }
    }

    /**
     * Detects profiles with common images (i.e. the same people using several
     * different phone numbers).
     */
    public static void
    deleteExternalChildImages() {
        MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        logger.info("= DETECTING REPEATED IMAGES ACROSS PROFILES ==========================================");
        List<Document> conditions = new ArrayList<>();
        conditions.add(new Document("md", new BasicDBObject("$exists", true)));
        conditions.add(new Document("x", true));
        conditions.add(new Document("a", new BasicDBObject("$exists", true)));
        conditions.add(new Document("u", new BasicDBObject("$exists", true)));
        Document filter = new Document("$and", conditions);
        FindIterable<Document> parentImageIterable = mongoConnection.image.find(filter)
                .projection(Projections.include("_id", "a", "u", "md"))
                .sort(new BasicDBObject("md", 1));

        ThreadFactory threadFactory = Util.buildThreadFactory("ParentImagesShaComparator[%03d]");
        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_REPORTER_THREADS, threadFactory);
        AtomicInteger totalImagesProcessed = new AtomicInteger(0);
        AtomicInteger externalMatchCounter = new AtomicInteger(0);
        ConcurrentHashMap<String, TreeSet<String>> externalMatches; // imageId vs set of profileId :)
        externalMatches = new ConcurrentHashMap<>();

        parentImageIterable.forEach((Consumer<? super Document>)parentImageObject ->
                executorService.submit(() ->
                    detectDuplicatedImagesBetweenProfiles(
                        mongoConnection.image, parentImageObject, externalMatches,
                        totalImagesProcessed, externalMatchCounter)));

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.MINUTES)) {
                logger.error("Parent image comparator threads taking so long!");
            }
        } catch (InterruptedException e) {
            logger.error(e);
        }

        logger.info("Total parent images processed: {}", totalImagesProcessed.get());
        logger.info("External matches skipped: {}", externalMatchCounter.get());
        logger.info("= DETECTION OF REPEATED IMAGES ACROSS PROFILES PROCESS COMPLETE =========================");

        deleteRepeatedExternalImages(externalMatches, mongoConnection.image);
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
