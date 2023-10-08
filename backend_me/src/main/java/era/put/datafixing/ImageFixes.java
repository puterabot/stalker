package era.put.datafixing;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import era.put.base.Util;
import era.put.building.ImageDownloader;
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

import static com.mongodb.client.model.Filters.exists;

public class ImageFixes {
    private static final Logger logger = LogManager.getLogger(ImageFixes.class);
    private static final int NUMBER_OF_DELETER_THREADS = 24;

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

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadCounter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                String name = String.format("ImageFileDeleter[%03d]", threadCounter.getAndIncrement());
                return new Thread(r, name);
            }
        };

        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_DELETER_THREADS, threadFactory);
        childImageIterable.forEach((Consumer<? super Document>) imageObject -> {
            executorService.submit(() -> processImageFileDeletion(imageObject, counter, deleteCounter));
        });
        executorService.shutdown();
        if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
            Util.exitProgram("deleteDanglingImages processes taking so long!");
        }

        logger.info("= {} CHILD IMAGE FILES DELETED =", deleteCounter.get());
    }

    private static void processImageFileDeletion(Document imageObject, AtomicInteger counter, AtomicInteger deleteCounter) {
        Object parentId = imageObject.get("x");
        if (parentId instanceof ObjectId) {
            int n = counter.incrementAndGet();
            if (n % 100000 == 0) {
                logger.info("Processed so far: " + n);
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
}
