package era.put.datafixing;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import era.put.base.MongoConnection;
import era.put.base.Util;
import era.put.building.ImageDownloader;
import java.io.File;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.ObjectId;

import static com.mongodb.client.model.Filters.exists;

public class Fixes {
    private static final Logger logger = LogManager.getLogger(Fixes.class);

    public static void deleteDanglingImages(MongoCollection<Document> image) throws Exception {
        Document query = new Document("x", new Document().append("$ne", true));
        logger.info("= DELETING DANGLING FILES =");
        int count = 0;
        int pending = 0;

        FindIterable<Document> imageIterable = image.find(query);

        int n = 0;
        for (Document d: imageIterable) {
            n++;
        }

        imageIterable = image.find(query);
        logger.info("Number of images with 'x' reference to a parent image: " + n);
        logger.info("Deleting the image files for those.");

        for (Document d: imageIterable) {
            Object x = d.get("x");
            if (x == null) {
                pending++;
            } else if (x instanceof ObjectId) {
                String filename = ImageDownloader.imageFilename(d, System.out);
                File fd = new File(filename);
                if (fd.exists()) {
                    if (fd.delete()) {
                        count++;
                        if (count % 0 == 100) {
                            logger.info("{} deleted so far", count);
                        }
                    } else {
                        logger.error("Cannot delete " + filename);
                    }
                }
            } else {
                Util.exitProgram("UNSUPPORTED IMAGE x FIELD OF CLASS: " + d.get("x").getClass().getName());
            }
        }
        logger.info("= DELETED " + count + " DANGLING FILES, " + pending + " PENDING TO DOWNLOAD =");
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

    public static void fixPostCollection(MongoCollection<Document> post) throws Exception {
        // Every post should have a integer ID for url
        MongoCursor<Document> cursor = post.find(exists("i", false)).iterator();;
        while (cursor.hasNext()) {
            Document p = cursor.next();
            String url = p.getString("url");
            if (url == null || url.isEmpty()) {
                logger.error("Post " + p.get("_id").toString() + " does not have a url!");
                continue;
            }
            Integer i = Util.extractIdFromPostUrl(url);
            if (i == null) {
                logger.error("Post " + p.get("_id").toString() + " url " + url + " in wrong format!");
                continue;
            }
            logger.info("  - " + i.intValue());
            Document newDocument = new Document().append("i", i.intValue());
            Document filter = new Document().append("_id", p.get("_id"));
            Document query = new Document().append("$set", newDocument);
            post.updateOne(filter, query);
        }
    }

    public static void fixRelationships(MongoConnection mongoConnection) {
        // TODO: Implement this
    }
}
