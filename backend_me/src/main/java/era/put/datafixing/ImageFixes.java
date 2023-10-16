package era.put.datafixing;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import era.put.MeLocalDataProcessorApp;
import era.put.base.Configuration;
import era.put.base.MongoConnection;
import era.put.base.Util;
import era.put.building.FileToolReport;
import era.put.building.ImageAnalyser;
import era.put.building.ImageDownloader;
import era.put.building.ImageFileAttributes;
import era.put.building.RepeatedImageDetector;
import java.io.File;
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
import vsdk.toolkit.media.RGBImage;
import vsdk.toolkit.media.RGBPixel;

import static com.mongodb.client.model.Filters.exists;

public class ImageFixes {
    private static final Logger logger = LogManager.getLogger(ImageFixes.class);
    private static final int NUMBER_OF_DELETER_THREADS = 72;
    private static final int NUMBER_OF_IMAGE_DESCRIPTOR_ANALYZER_THREADS = 72;

    /**
    An image is a "child" when its register at the database is not true and contains an ObjectId reference
    to another image that is the "parent". This method delete child image files since they are redundant
    with the parent or reference ("x" attribute in database) image. Note that child images are kept on the
    database, since posts and profiles are still referencing them.
    */
    public static void deleteChildImageFiles(MongoCollection<Document> image) throws Exception {
        logger.info("= DELETING CHILD IMAGE FILES =");

        Document query = new Document("x", new Document().append("$ne", true));
        FindIterable<Document> childImageIterable = image.find(query).projection(Projections.include("_id", "x"));
        AtomicInteger counter = new AtomicInteger(0);
        AtomicInteger deleteCounter = new AtomicInteger(0);

        ThreadFactory threadFactory = Util.buildThreadFactory("ImageFileDeleter[%03d]");

        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_DELETER_THREADS, threadFactory);
        childImageIterable.forEach((Consumer<? super Document>) imageObject -> {
            executorService.submit(() -> processImageFileDeletion(imageObject, counter, deleteCounter));
        });
        executorService.shutdown();
        if (!executorService.awaitTermination(10, TimeUnit.MINUTES)) {
            Util.exitProgram("deleteDanglingImages processes taking so long!");
        }

        logger.info("= {} CHILD IMAGE FILES DELETED =", deleteCounter.get());
    }

    private static void processImageFileDeletion(Document imageObject, AtomicInteger counter, AtomicInteger deleteCounter) {
        Object parentId = imageObject.get("x");
        if (parentId instanceof ObjectId) {
            int n = counter.incrementAndGet();
            if (n % 100000 == 0) {
                logger.info("Images processed so far: " + n);
            }
            String _id = ((ObjectId) imageObject.get("_id")).toString();
            String filename = ImageDownloader.imageFilename(_id, System.out);
            File fd = new File(filename);
            if (fd.exists()) {
                if (fd.delete()) {
                    logger.info("Deleted " + fd.getAbsolutePath());
                    deleteCounter.incrementAndGet();
                } else {
                    logger.error("Cannot delete " + filename);
                }
            }
        } else {
            Util.exitProgram("UNSUPPORTED IMAGE x FIELD OF CLASS: " + imageObject.get("x").getClass().getName());
        }
    }

    public static void downloadMissingImages(MongoCollection<Document> image) throws Exception {
        logger.info("= DOWNLOADING IMAGES =");
        int count = 0;
        for (Document d: image.find(exists("d", false))) {
            if (ImageDownloader.downloadImageIfNeeded(d, image, System.out)) {
                count++;
            }
        }
        logger.info("= DOWNLOADED " + count + " IMAGES =");
    }

    public static void updateImageDates(Configuration c, MongoConnection mongoConnection) {
        for (Document i: mongoConnection.image.find(exists("md", false))) {
            ImageAnalyser.updateDate(mongoConnection.image, i, c);
        }
    }

    public static void buildImageSizeAndShaSumDescriptors(MongoConnection mongoConnection) throws Exception {
        FileToolReport fileToolReport = new FileToolReport();
        Document filter = new Document("a", new BasicDBObject("$exists", false)).append("d", true);
        FindIterable<Document> imageWithNoDescriptorsIterable = mongoConnection.image.find(filter);

        ThreadFactory threadFactory = Util.buildThreadFactory("ImagesSizeAndShaDescriptorCreator[%03d]");
        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_IMAGE_DESCRIPTOR_ANALYZER_THREADS, threadFactory);
        AtomicInteger totalImagesProcessed = new AtomicInteger(0);

        imageWithNoDescriptorsIterable.forEach((Consumer<? super Document>)imageDocument -> {
            executorService.submit(() ->
                ImageAnalyser.processImageFile(mongoConnection.image, imageDocument, fileToolReport, System.out, totalImagesProcessed)
            );
        });

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.MINUTES)) {
                logger.error("Image descriptor analyzer threads taking so long!");
            }
        } catch (InterruptedException e) {
            logger.error(e);
        }

        fileToolReport.print();
        RepeatedImageDetector.groupImages(mongoConnection.image);
    }

    public static void updateImageShaAndSizeDescriptorsFromFile(MongoConnection mongoConnection, Document parentImageObject, AtomicInteger updatedDescriptors) {
        // Update base descriptors
        FileToolReport fileToolReport = new FileToolReport();

        //
        Document query = new Document("x", parentImageObject.get("_id"));
        FindIterable<Document> childImageIterable = mongoConnection.image.find(query);
        ImageFileAttributes attributes; // Basic sha and image size descriptors
        attributes = ImageAnalyser.processImageFile(mongoConnection.image, parentImageObject, fileToolReport, System.out, updatedDescriptors);

        if (attributes == null) {
            Util.exitProgram(
                    "FATAL: Parent image id [" + parentImageObject.get("_id").toString() +
                            "] could not be analyzed! Check database SHA consistency!");
        }

        childImageIterable.forEach((Consumer<? super Document>)childImage -> {
            Document filter = new Document().append("_id", childImage.get("_id"));
            Document newDocument = new Document().append("a", attributes);
            mongoConnection.image.updateOne(filter, new Document("$set", newDocument));
        });
    }

    public static RGBImage cropImageFile(RGBImage image, RegionOfInterest roi) {
        RGBImage cropped = new RGBImage();
        cropped.init(roi.x1 - roi.x0 + 1, roi.y1 - roi.y0 + 1);

        RGBPixel p = new RGBPixel();
        for (int y = roi.y0; y <= roi.y1; y++) {
            for (int x = roi.x0; x <= roi.x1; x++) {
                image.getPixelRgb(x, y, p);
                cropped.putPixelRgb(x - roi.x0, y - roi.y0, p);
            }
        }

        return cropped;
    }
}
