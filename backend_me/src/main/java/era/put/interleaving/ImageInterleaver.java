package era.put.interleaving;

import com.mongodb.MongoCursorNotFoundException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.FindIterable;
import era.put.base.MongoUtil;
import era.put.base.Util;
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
import java.util.List;

import static com.mongodb.client.model.Filters.exists;

public class ImageInterleaver {
    private static final Logger logger = LogManager.getLogger(ImageInterleaver.class);
    private static final int NUMBER_OF_P0_REFERENCER_THREADS = 72;

    private static void cleanDuplicates(Document i, ArrayList<ObjectId> p, MongoConnection c, PrintStream out) {
        List<ObjectId> newReferences = new ArrayList<>();
        for (ObjectId id: p) {
            Document filter = new Document("_id", id);
            Document post = c.post.find(filter).first();
            if (post != null) {
                newReferences.add(post.getObjectId("_id"));
                logger.info("    . Adding " + id.toString());
            } else {
                logger.info("    . Deleting " + id.toString());
            }
        }

        Document filter = new Document("_id", i.getObjectId("_id"));
        if (newReferences.isEmpty()) {
            c.image.deleteOne(filter);
        } else if (newReferences.size() != p.size()) {
            Document newDocument = new Document("p", newReferences);
            Document query = new Document("$set", newDocument);
            c.image.updateOne(filter, query);
        }
    }

    public static void createP0References(PrintStream out) {
        try {
            MongoConnection c = MongoUtil.connectWithMongoDatabase();
            if (c == null) {
                return;
            }

            logger.info("= CREATING P0 REFERENCES =========================");
            FindIterable<Document> imageIterable = c.image.find(exists("p0", false));
            ThreadFactory threadFactory = Util.buildThreadFactory("ImagesP0ReferencerComparator[%03d]");
            ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_P0_REFERENCER_THREADS, threadFactory);
            AtomicInteger totalImagesProcessed = new AtomicInteger(0);
            imageIterable.forEach((Consumer<? super Document>)imageDocument -> {
                executorService.submit(() -> createP0Reference(out, imageDocument, c, totalImagesProcessed));
            });
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.MINUTES)) {
                    logger.error("Image P0 referencer threads taking so long!");
                }
            } catch (InterruptedException e) {
                logger.error(e);
            }
            logger.info("= P0 REFERENCES CREATED =========================");
        } catch (MongoCursorNotFoundException | MongoTimeoutException e) {
            logger.error(e);
        }
    }

    private static void createP0Reference(PrintStream out, Document imageDocument, MongoConnection c, AtomicInteger totalImagesProcessed) {
        int n = totalImagesProcessed.incrementAndGet();
        if (n % 1000 == 0) {
            logger.info("Images processed for P0 reference: {}", n);
        }
        Object genericList = imageDocument.get("p");
        if (!(genericList instanceof ArrayList)) {
            return;
        }
        ArrayList<Object> castedList = new ArrayList<>((ArrayList<?>) genericList);
        ArrayList<ObjectId> objectIds = new ArrayList<>();
        for (Object o: castedList) {
            if (o instanceof ObjectId) {
                objectIds.add((ObjectId)o);
            }
        }
        if (objectIds.size() != 1) {
            logger.info("  - Skipping {} - it has {} elements", imageDocument.getObjectId("_id").toString(), objectIds.size());
            cleanDuplicates(imageDocument, objectIds, c, out);
            return;
        }
        Document newDocument = new Document("p0", objectIds.get(0));
        Document filter = new Document("_id", imageDocument.getObjectId("_id"));
        Document query = new Document("$set", newDocument);
        c.image.updateOne(filter, query);
    }
}
