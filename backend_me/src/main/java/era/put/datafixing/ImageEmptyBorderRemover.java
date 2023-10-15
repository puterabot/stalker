package era.put.datafixing;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Projections;
import era.put.base.MongoConnection;
import era.put.base.MongoUtil;
import era.put.base.Util;
import era.put.building.FileToolReport;
import era.put.building.ImageAnalyser;
import era.put.building.ImageDownloader;
import era.put.building.ImageFileAttributes;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
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
import vsdk.toolkit.common.VSDK;
import vsdk.toolkit.io.image.ImageNotRecognizedException;
import vsdk.toolkit.media.RGBImage;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBPixel;

class RegionOfInterest {
    public int x0;
    public int y0;
    public int x1;
    public int y1;
}

public class ImageEmptyBorderRemover {
    private static final int BLACK_THRESHOLD = 6; // 2 is to low, 10 is to high
    private static final Logger logger = LogManager.getLogger(ImageEmptyBorderRemover.class);
    private static final int NUMBER_OF_IMAGE_PROCESSING_THREADS = 72;

    private static BufferedWriter errors;
    private static BufferedWriter candidates;

    private static RGBImage cropImage(RGBImage image, RegionOfInterest roi) {
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

    private static boolean removeEmptyImageBorder(Document parentImageObject, AtomicInteger totalImagesProcessed, AtomicInteger blackBordersRemoved) {
        String filename = ImageDownloader.imageFilename(parentImageObject.get("_id").toString(), System.out);
        File fd = new File(filename);
        RGBImage image = null;
        int n = totalImagesProcessed.getAndIncrement();

        try {
            image = ImagePersistence.importRGB(fd);
            if (image == null) {
                return false;
            }
        } catch (ImageNotRecognizedException e) {
            logger.info("Could not load image [{}], skipped.", filename);
            try {
                errors.write(filename + "\n");
            } catch (IOException ex) {
                logger.error(ex);
            }
        } catch (Exception e) {
            logger.error(e);
            return false;
        }

        RegionOfInterest roi = searchForBorders(image);
        if (roi != null) {
            RGBImage cropped = cropImage(image, roi);
            try {
                candidates.write(filename + "\n");
                String debugFilename = "/tmp/" + parentImageObject.get("_id").toString() + ".jpg";
                String debugModifiedFilename = "/tmp/" + parentImageObject.get("_id").toString() + "_trimmed.jpg";
                //ImagePersistence.exportJPG(new File(debugFilename), image);
                //ImagePersistence.exportJPG(new File(debugModifiedFilename), cropped);
            } catch (IOException e) {
                logger.error(e);
            }
            blackBordersRemoved.incrementAndGet();
        }

        int b = blackBordersRemoved.get();
        if (n % 10000 == 0) {
            logger.info("Images searched for borders: {} (black borders: {})", n, b);
            if (image != null) {
                logger.info("  . Image of size {} x {}.", image.getXSize(), image.getYSize());
            }
        }
        return true;
    }

    private static boolean rowColorUnder(RGBImage image, int y, RGBPixel targetColor, int threshold) {
        double rSum = 0.0;
        double gSum = 0.0;
        double bSum = 0.0;

        RGBPixel p = new RGBPixel();
        double dx = image.getXSize();
        for (int x = 0; x < image.getXSize(); x++) {
            image.getPixelRgb(x, y, p);
            rSum += VSDK.signedByte2unsignedInteger(p.r);
            gSum += VSDK.signedByte2unsignedInteger(p.g);
            bSum += VSDK.signedByte2unsignedInteger(p.b);
        }
        rSum /= dx;
        gSum /= dx;
        bSum /= dx;
        if (rSum > targetColor.r + threshold ||
            gSum > targetColor.g + threshold ||
            bSum > targetColor.b + threshold) {
            return false;
        }

        return true;
    }

    private static boolean columnColorUnder(RGBImage image, int x, RGBPixel targetColor, int threshold) {
        double rSum = 0.0;
        double gSum = 0.0;
        double bSum = 0.0;

        RGBPixel p = new RGBPixel();
        double dy = image.getYSize();
        for (int y = 0; y < image.getYSize(); y++) {
            image.getPixelRgb(x, y, p);
            rSum += VSDK.signedByte2unsignedInteger(p.r);
            gSum += VSDK.signedByte2unsignedInteger(p.g);
            bSum += VSDK.signedByte2unsignedInteger(p.b);
        }
        rSum /= dy;
        gSum /= dy;
        bSum /= dy;
        if (rSum > targetColor.r + threshold ||
            gSum > targetColor.g + threshold ||
            bSum > targetColor.b + threshold) {
            return false;
        }

        return true;
    }

    private static RegionOfInterest searchForBorders(RGBImage image) {
        if (image == null || image.getXSize() <= 1 || image.getYSize() <= 1) {
            return null;
        }

        RGBPixel black = new RGBPixel();
        black.r = black.g = black.b = 0;
        int threshold = BLACK_THRESHOLD;
        RegionOfInterest roi = new RegionOfInterest();
        roi.x0 = 0;
        roi.y0 = 0;
        roi.x1 = image.getXSize() - 1;
        roi.y1 = image.getYSize() - 1;
        boolean withRoi = false;

        int y;

        // First side: top
        for (y = 0; y < image.getYSize(); y++) {
            if (!rowColorUnder(image, y, black, threshold)) {
                break;
            }
        }

        if (y > 0) {
            roi.y0 = y + 1;
            withRoi = true;
        }

        // Second side: top
        for (y = image.getYSize() - 1; y >= 0; y--) {
            if (!rowColorUnder(image, y, black, threshold)) {
                break;
            }
        }

        if (y < image.getYSize() - 1) {
            roi.y1 = y - 1;
            withRoi = true;
        }

        // Third side: left
        int x;
        for (x = 0; x < image.getXSize(); x++) {
            if (!columnColorUnder(image, x, black, threshold)) {
                break;
            }
        }

        if (x > 0) {
            roi.x0 = x + 1;
            withRoi = true;
        }

        // Fourth side: right
        for (x = image.getXSize() - 1; x >= 0; x--) {
            if (!columnColorUnder(image, x, black, threshold)) {
                break;
            }
        }

        if (x < image.getXSize() - 1) {
            roi.x1 = x - 1;
            withRoi = true;
        }

        // Close
        if (withRoi) {
            return roi;
        }
        return null;
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
        AtomicInteger blackBordersRemoved = new AtomicInteger(0);
        AtomicInteger updatedDescriptors = new AtomicInteger(0);

        parentImageIterable.forEach((Consumer<? super Document>) parentImageObject ->
            executorService.submit(() -> {
                    if (removeEmptyImageBorder(parentImageObject, totalImagesProcessed, blackBordersRemoved)) {
                        updateChecksums(mongoConnection, parentImageObject, updatedDescriptors);
                    }
                }
            ));

        executorService.shutdown();
        if (!executorService.awaitTermination(10, TimeUnit.HOURS)) {
            Util.exitProgram("Image empty border remover processes taking so long!");
        }
        errors.close();
        candidates.close();
    }

    private static void updateChecksums(MongoConnection mongoConnection, Document parentImageObject, AtomicInteger updatedDescriptors) {
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
}
