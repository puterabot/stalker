package era.put.building;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import era.put.base.MongoConnection;
import era.put.base.Util;
import java.io.File;
import org.bson.Document;
import org.bson.types.ObjectId;

import static com.mongodb.client.model.Filters.exists;

public class Fixes {

    public static void deleteDanglingImages(MongoCollection<Document> image) throws Exception {
        Document query = new Document("x", new Document().append("$ne", true));
        System.out.println("= DELETING DANGLING FILES =");
        int count = 0;
        int pending = 0;
        for (Document d: image.find(query)) {
            Object x = d.get("x");
            if (x == null) {
                pending++;
            } else if (x instanceof ObjectId) {
                String filename = ImageDownloader.imageFilename(d, System.out);
                File fd = new File(filename);
                if (fd.exists()) {
                    if (fd.delete()) {
                        count++;
                    } else {
                        System.err.println("Cannot delete " + filename);
                    }
                }
            } else if (x instanceof ObjectId) {
                Util.exitProgram("DIFFERENT: " + d.get("x").getClass().getName());
            }
        }
        System.out.println("= DELETED " + count + " DANGLING FILES, " + pending + " PENDING TO DOWNLOAD =");
    }

    public static void downloadMissingImages(MongoCollection<Document> image) throws Exception {
        System.out.println("= DOWNLOADING IMAGES =");
        int count = 0;
        for (Document d: image.find(exists("x", false))) {
            if (ImageDownloader.downloadImageIfNeeded(d, image, System.out)) {
                count++;
            }
        }
        System.out.println("= DOWNLOADED " + count + " IMAGES =");
    }

    public static void fixPostCollection(MongoCollection<Document> post) throws Exception {
        // Every post should have a integer ID for url
        MongoCursor<Document> cursor = post.find(exists("i", false)).iterator();;
        while (cursor.hasNext()) {
            Document p = cursor.next();
            String url = p.getString("url");
            if (url == null || url.isEmpty()) {
                System.err.println("ERROR: Post " + p.get("_id").toString() + " does not have a url!");
                continue;
            }
            Integer i = Util.extractIdFromPostUrl(url);
            if (i == null) {
                System.err.println("ERROR: Post " + p.get("_id").toString() + " url " + url + " in wrong format!");
                continue;
            }
            System.out.println("  - " + i.intValue());
            Document newDocument = new Document().append("i", i.intValue());
            Document filter = new Document().append("_id", p.get("_id"));
            Document query = new Document().append("$set", newDocument);
            post.updateOne(filter, query);
        }
    }

    public static void fixRelationships(MongoConnection mongoConnection) {

    }
}
