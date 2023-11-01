package era.put.datafixing;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Projections;
import era.put.base.MongoConnection;
import era.put.base.MongoUtil;
import era.put.base.Util;
import era.put.building.ImageDownloader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import vsdk.toolkit.io.image.ImageNotRecognizedException;
import vsdk.toolkit.media.RGBImage;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBPixel;

public class ImageEmptyBorderRemover {
    private static final Logger logger = LogManager.getLogger(ImageEmptyBorderRemover.class);
    private static final int NUMBER_OF_IMAGE_PROCESSING_THREADS = 72;

    private static BufferedWriter errors = null;
    private static BufferedWriter candidates = null;

    private static RegionOfInterest replaceImageWithBorderTrimmedVersion(AtomicInteger blackBordersRemoved, RegionOfInterest roi, RGBImage image, String filename, String _id, File originalImageFile, long originalTime) {
        if (roi != null && roi.idBigArea()) {
            RGBImage cropped = ImageFixes.cropImageFile(image, roi);
            try {
                candidates.write(filename + "\n");
                candidates.flush();
                File backup = new File("/tmp/backup_" + _id + ".jpg");
                Path originalPath = Path.of(originalImageFile.getAbsolutePath());
                Path backupPath = Path.of(backup.getAbsolutePath());
                Files.copy(originalPath, backupPath);


                // Debug! TODO: Test white logic case
                String debugInputFilename = originalImageFile.getAbsolutePath() + ".origBorders";
                FileUtils.copyFile(originalImageFile, new File(debugInputFilename));
                //String debugOutputFilename = "/tmp/" + _id + "_trimmed.jpg";
                //ImagePersistence.exportJPG(new File(debugInputFilename), image);
                //ImagePersistence.exportJPG(new File(debugOutputFilename), cropped);

                // Warning: at this point database descriptors are outdated, need to recalculate!
                ImagePersistence.exportJPG(new File(filename), cropped);

                Process p = Runtime.getRuntime().exec("sync");
                p.waitFor();
                File result = new File(filename);
                if (!result.exists() || result.length() == 0) {
                    logger.error("Failed conversion for [{}], should retry later!", filename);
                    Files.copy(backupPath, originalPath);
                    if (!result.setLastModified(originalTime)) {
                        logger.warn("Can not update time stamp to {}", originalImageFile.getAbsoluteFile());
                    }
                    roi = null; // Cancel operation!
                    errors.write(filename + " (empty copy)\n");
                    errors.flush();
                } else {
                    Files.delete(backupPath);
                    if (!result.setLastModified(originalTime)) {
                        logger.warn("Can not update time stamp to {}", result.getAbsoluteFile());
                    }
                }
            } catch (Exception e) {
                logger.error(e);
            }
            blackBordersRemoved.incrementAndGet();
        }
        return roi;
    }

    public static boolean removeEmptyImageBorderOnImageFile(Document parentImageObject, AtomicInteger totalImagesProcessed, AtomicInteger imagesWithBordersRemoved, ColorLogic colorLogic, RGBPixel referenceColor) {
        String _id = parentImageObject.get("_id").toString();
        String filename = ImageDownloader.imageFilename(_id, System.err);
        File originalImageFile = new File(filename);
        long originalTime = originalImageFile.lastModified();
        RGBImage image = null;
        int n = totalImagesProcessed.getAndIncrement();

        try {
            image = ImagePersistence.importRGB(originalImageFile);
            if (image == null) {
                return false;
            }
        } catch (ImageNotRecognizedException e) {
            logger.info("Could not load image [{}], skipped.", filename);
            try {
                errors.write(filename + "\n");
                errors.flush();
            } catch (IOException ex) {
                logger.error(ex);
            }
        } catch (Exception e) {
            logger.error(e);
            return false;
        }

        RegionOfInterest roi = BordersDetector.searchForBorders(image, referenceColor, colorLogic);
        roi = replaceImageWithBorderTrimmedVersion(imagesWithBordersRemoved, roi, image, filename, _id, originalImageFile, originalTime);

        int b = imagesWithBordersRemoved.get();
        if (n % 10000 == 0) {
            logger.info("Images searched for borders: {} (referenceColor borders: {})", n, b);
            if (image != null) {
                logger.info("  . Image of size {} x {}.", image.getXSize(), image.getYSize());
            }
        }
        return roi != null;
    }

    public static boolean removeEmptyImageBorderOnImageFile(Document parentImageObject, ColorLogic colorLogic, RGBPixel referenceColor) {
        boolean result = false;
        try {
            AtomicInteger totalImagesProcessed = new AtomicInteger(0);
            AtomicInteger bordersRemoved = new AtomicInteger(0);

            if (errors == null) {
                errors = new BufferedWriter(new FileWriter("/tmp/image_errors.log", true));
            }
            if (candidates == null) {
                candidates = new BufferedWriter(new FileWriter("/tmp/image_candidates.log", true));
            }

            result = removeEmptyImageBorderOnImageFile(parentImageObject, totalImagesProcessed, bordersRemoved, colorLogic, referenceColor);

            errors.flush();
            candidates.flush();
        } catch (Exception e) {
            logger.error(e);
        }
        return result;
    }

    public static void removeEmptyBordersFromImages() throws Exception{
        MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        errors = new BufferedWriter(new FileWriter("/tmp/image_errors.log"));
        candidates = new BufferedWriter(new FileWriter("/tmp/image_candidates.log"));

        ArrayList<Document> set = new ArrayList<>();
        set.add(new Document("x", true));
        set.add(new Document("md", new BasicDBObject("$exists", true)));
        set.add(new Document("a", new BasicDBObject("$exists", true)));
        set.add(new Document("u", new BasicDBObject("$exists", true)));
        Document filter = new Document("$and", set);
        FindIterable<Document> parentImageIterable = mongoConnection.image.find(filter)
            .projection(Projections.include("_id"));
        ThreadFactory threadFactory = Util.buildThreadFactory("EmptyImageBorderRemover[%03d]");
        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_IMAGE_PROCESSING_THREADS, threadFactory);
        AtomicInteger totalImagesProcessed = new AtomicInteger(0);
        AtomicInteger bordersRemoved = new AtomicInteger(0);
        AtomicInteger updatedDescriptors = new AtomicInteger(0);

        RGBPixel black = new RGBPixel();
        black.r = black.g = black.b = 0;
        //RGBPixel white = new RGBPixel();
        //white.r = white.g = white.b = VSDK.unsigned8BitInteger2signedByte(255);

        parentImageIterable.forEach((Consumer<? super Document>) parentImageObject ->
            executorService.submit(() -> {
                    if (removeEmptyImageBorderOnImageFile(parentImageObject, totalImagesProcessed, bordersRemoved, ColorLogic.BLACK_LOGIC, black)) {
                        ImageFixes.updateImageShaAndSizeDescriptorsFromFile(mongoConnection, parentImageObject, updatedDescriptors);
                    }

                    // TODO: This is not working...
                    //if (removeEmptyImageBorder(parentImageObject, totalImagesProcessed, bordersRemoved, ColorLogic.WHITE_LOGIC, white)) {
                    //    ImageFixes.updateImageShaAndSizeDescriptorsFromFile(mongoConnection, parentImageObject, updatedDescriptors);
                    //}
                }
            ));

        executorService.shutdown();
        if (!executorService.awaitTermination(10, TimeUnit.HOURS)) {
            Util.exitProgram("Image empty border remover processes taking so long!");
        }
        errors.close();
        candidates.close();
    }
}
