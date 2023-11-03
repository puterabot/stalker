package era.put.datafixing;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import era.put.base.Configuration;
import era.put.base.MongoConnection;
import era.put.base.Util;
import era.put.building.FileToolReport;
import era.put.building.ImageAnalyser;
import era.put.building.ImageDownloader;
import era.put.building.ImageFileAttributes;
import era.put.building.RepeatedImageDetector;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
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
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImage;
import vsdk.toolkit.media.RGBPixel;

import static com.mongodb.client.model.Filters.exists;

public class ImageFixes {
    private static final Logger logger = LogManager.getLogger(ImageFixes.class);
    private static final int NUMBER_OF_DELETER_THREADS = 72;
    private static final int NUMBER_OF_IMAGE_DESCRIPTOR_ANALYZER_THREADS = 72;
    private static final int NUMBER_OF_IMAGE_CHECKER_THREADS = 72;
    private static final int MINIMUM_IMAGE_DIMENSION = 32;

    private static String ME_IMAGE_DOWNLOAD_PATH;

    static {
        try {
            ClassLoader classLoader = Util.class.getClassLoader();
            InputStream input = classLoader.getResourceAsStream("application.properties");
            if (input == null) {
                throw new Exception("application.properties not found on classpath");
            }
            Properties properties = new Properties();
            properties.load(input);
            ME_IMAGE_DOWNLOAD_PATH = properties.getProperty("me.image.download.path");
        } catch (Exception e) {
            ME_IMAGE_DOWNLOAD_PATH = "/tmp";
        }
    }

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
                try {
                    FileUtils.copyFile(fd, new File(filename + ".bakReferenceTo_" + parentId.toString()));
                } catch (IOException e) {
                    logger.error("Can not copy {}", filename);
                }
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
        Document parentFilter = new Document("x", parentImageObject.get("_id"));
        FindIterable<Document> childImageIterable = mongoConnection.image.find(parentFilter);
        ImageFileAttributes attributes; // Basic sha and image size descriptors
        attributes = ImageAnalyser.processImageFile(mongoConnection.image, parentImageObject, fileToolReport, System.out, updatedDescriptors);
        mongoConnection.image.updateOne(parentFilter, new Document("$unset", new BasicDBObject("af", false)));

        if (attributes == null) {
            Util.exitProgram(
                    "FATAL: Parent image id [" + parentImageObject.get("_id").toString() +
                            "] could not be analyzed! Check database SHA consistency!");
        }

        childImageIterable.forEach((Consumer<? super Document>)childImage -> {
            Document childFilter = new Document().append("_id", childImage.get("_id"));
            Document newDocument = new Document().append("a", attributes);
            mongoConnection.image.updateOne(childFilter, new Document("$set", newDocument));
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

    private static void markImageToReDownload(String _id, MongoCollection<Document> imageCollection) {
        Document filter = new Document("_id", new ObjectId(_id));
        imageCollection.updateOne(filter, new Document("$unset", new BasicDBObject("d", false)));
    }

    private static void verifyImageInDatabaseHasACorrespondingCorrectImageFile(String _id, AtomicInteger totalImagesProcessed, AtomicInteger errorCount, MongoCollection<Document> imageCollection) {
        String imageFilename = ImageDownloader.imageFilename(_id, System.err);
        try {
            int n = totalImagesProcessed.getAndIncrement();
            int ne = errorCount.get();
            if (n % 10000 == 0) {
                logger.info("Images verified to have correct image file: {}, errors: {}", n, ne);
            }
            File fd = new File(imageFilename);
            if (!fd.exists()) {
                logger.error("File does not exists: {}", imageFilename);
                errorCount.incrementAndGet();
                markImageToReDownload(_id, imageCollection);
                return;
            }
            if (fd.length() == 0) {
                logger.error("Empty file: {}", imageFilename);
                if (!fd.delete()) {
                    logger.error("Error deleting empty file {}", imageFilename);
                }
                errorCount.incrementAndGet();
                markImageToReDownload(_id, imageCollection);
                return;
            }
            RGBImage image = ImagePersistence.importRGB(fd);
            if (image.getXSize() < MINIMUM_IMAGE_DIMENSION || image.getYSize() < MINIMUM_IMAGE_DIMENSION) {
                logger.error("So small image ({} x {}): {}", image.getXSize(), image.getYSize(), imageFilename);
                errorCount.incrementAndGet();
                return;
            }
        } catch (Exception e) {
            logger.error("Error processing: {}", imageFilename);
            logger.error(e);
            errorCount.incrementAndGet();
        }
    }

    public static void verifyAllImageObjectsInDatabaseHasCorrespondingImageFile(MongoCollection<Document> imageCollection) {
        logger.info("= VERIFYING THAT ALL IMAGES REFERENCED ON DATABASE HAS A CORRECT IMAGE FILE ==========");
        Document filter = new Document("md", new BasicDBObject("$exists", true))
                .append("x", true)
                .append("a", new BasicDBObject("$exists", true))
                .append("u", new BasicDBObject("$exists", true));
        FindIterable<Document> parentImageIterable = imageCollection.find(filter)
                .projection(Projections.include("_id", "a", "u", "md"))
                .sort(new BasicDBObject("md", 1));

        ThreadFactory threadFactory = Util.buildThreadFactory("ParentImagesChecker[%03d]");
        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_IMAGE_CHECKER_THREADS, threadFactory);
        AtomicInteger totalImagesProcessed = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        parentImageIterable.forEach((Consumer<? super Document>)parentImageObject ->
            executorService.submit(() ->
                verifyImageInDatabaseHasACorrespondingCorrectImageFile(parentImageObject.get("_id").toString(), totalImagesProcessed, errorCount, imageCollection)
            )
        );

        executorService.shutdown();
        try {
            //executorService.wait();
            if (!executorService.awaitTermination(2, TimeUnit.HOURS)) {
                logger.error("Parent image comparator threads taking so long!");
            }
        } catch (InterruptedException e) {
            logger.error(e);
        }

        logger.info("Total parent images verified: {}", totalImagesProcessed.get());
        logger.info("= DETECTING REPEATED IMAGES ACROSS PROFILES PROCESS COMPLETE =========================");

    }

    private static void verifyDirectoryImageFilesHasRecordsInDatabase(MongoCollection<Document> imageCollection, String folderPath, AtomicInteger totalImagesProcessed, AtomicInteger errorCount) {
        try {
            File fd = new File(folderPath);
            if (!fd.exists() || !fd.isDirectory()) {
                logger.error("Can not open directory {}", fd.getAbsoluteFile());
                return;
            }
            File[] children = fd.listFiles();
            for (File child: children) {
                int n = totalImagesProcessed.getAndIncrement();
                int ne = errorCount.get();
                if (n % 10000 == 0) {
                    logger.info("Images verified so far: {}, errors: {}", n, ne);
                }

                String filename = child.getName();
                String id = filename.replace(".jpg", "");

                Document filter = new Document("_id", new ObjectId(id)).append("x", new BasicDBObject("$exists", true));
                if (!imageCollection.find(filter).cursor().hasNext()) {
                    errorCount.incrementAndGet();
                    logger.error("Dangling file: {} - removed!", child.getAbsoluteFile());
                    if (!child.delete()) {
                        logger.error("Could not delete! {}", child.getAbsoluteFile());
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e);
        }
    }
    public static void removeDanglingImageFiles(MongoCollection<Document> imageCollection) {
        try {
            File imagesFolder = new File(ME_IMAGE_DOWNLOAD_PATH);

            ThreadFactory threadFactory = Util.buildThreadFactory("ImageFileValidator[%03d]");
            ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_IMAGE_CHECKER_THREADS, threadFactory);
            AtomicInteger totalImagesProcessed = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0x00; i <= 0xff; i++) {
                String folderPath = String.format("%s/%02x", ME_IMAGE_DOWNLOAD_PATH, i);
                executorService.submit(() ->
                    verifyDirectoryImageFilesHasRecordsInDatabase(
                        imageCollection, folderPath, totalImagesProcessed, errorCount)
                );

            }
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(2, TimeUnit.HOURS)) {
                    logger.error("Image file validator taking so long!");
                }
            } catch (InterruptedException e) {
                logger.error(e);
            }
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
