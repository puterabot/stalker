package era.put.datafixing;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import era.put.base.MongoConnection;
import era.put.base.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

import static com.mongodb.client.model.Filters.exists;

public class Fixes {
    private static final Logger logger = LogManager.getLogger(Fixes.class);

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
            logger.info("  - {}", i);
            Document newDocument = new Document().append("i", i);
            Document filter = new Document().append("_id", p.get("_id"));
            Document query = new Document().append("$set", newDocument);
            post.updateOne(filter, query);
        }
    }

    public static void fixRelationships(MongoConnection mongoConnection) {
        // TODO: Implement this
    }
}
